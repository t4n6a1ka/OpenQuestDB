/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2019 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 ******************************************************************************/

package com.questdb.griffin.engine.join;

import com.questdb.cairo.AbstractRecordCursorFactory;
import com.questdb.cairo.CairoConfiguration;
import com.questdb.cairo.ColumnTypes;
import com.questdb.cairo.RecordSink;
import com.questdb.cairo.map.Map;
import com.questdb.cairo.map.MapFactory;
import com.questdb.cairo.map.MapKey;
import com.questdb.cairo.map.MapValue;
import com.questdb.cairo.sql.*;
import com.questdb.griffin.SqlExecutionContext;
import com.questdb.std.Misc;
import com.questdb.std.Numbers;
import com.questdb.std.Transient;

public class AsOfJoinLightRecordCursorFactory extends AbstractRecordCursorFactory {
    private final Map joinKeyMap;
    private final RecordCursorFactory masterFactory;
    private final RecordCursorFactory slaveFactory;
    private final RecordSink masterKeySink;
    private final RecordSink slaveKeySink;
    private final HashJoinRecordCursor cursor;

    public AsOfJoinLightRecordCursorFactory(
            CairoConfiguration configuration,
            RecordMetadata metadata,
            RecordCursorFactory masterFactory,
            RecordCursorFactory slaveFactory,
            @Transient ColumnTypes joinColumnTypes,
            @Transient ColumnTypes valueTypes, // this expected to be just LONG, we store chain references in map
            RecordSink masterKeySink,
            RecordSink slaveKeySink,
            int columnSplit

    ) {
        super(metadata);
        this.masterFactory = masterFactory;
        this.slaveFactory = slaveFactory;
        joinKeyMap = MapFactory.createMap(configuration, joinColumnTypes, valueTypes);
        this.masterKeySink = masterKeySink;
        this.slaveKeySink = slaveKeySink;
        this.cursor = new HashJoinRecordCursor(
                columnSplit,
                joinKeyMap,
                NullRecordFactory.getInstance(slaveFactory.getMetadata()),
                masterFactory.getMetadata().getTimestampIndex(),
                slaveFactory.getMetadata().getTimestampIndex()
        );
    }

    @Override
    public void close() {
        joinKeyMap.close();
        ((JoinRecordMetadata) getMetadata()).close();
        masterFactory.close();
        slaveFactory.close();
    }

    @Override
    public RecordCursor getCursor(SqlExecutionContext executionContext) {
        cursor.of(
                masterFactory.getCursor(executionContext),
                slaveFactory.getCursor(executionContext)
        );
        return cursor;
    }

    @Override
    public boolean isRandomAccessCursor() {
        return false;
    }

    private class HashJoinRecordCursor implements NoRandomAccessRecordCursor {
        private final OuterJoinRecord record;
        private final Map joinKeyMap;
        private final int columnSplit;
        private final int masterTimestampIndex;
        private final int slaveTimestampIndex;
        private RecordCursor masterCursor;
        private RecordCursor slaveCursor;
        private Record masterRecord;
        private Record slaveRecord;
        private long slaveTimestamp = Long.MIN_VALUE;
        private long lastSlaveRowID = Long.MIN_VALUE;

        public HashJoinRecordCursor(int columnSplit, Map joinKeyMap, Record nullRecord, int masterTimestampIndex, int slaveTimestampIndex) {
            this.record = new OuterJoinRecord(columnSplit, nullRecord);
            this.joinKeyMap = joinKeyMap;
            this.columnSplit = columnSplit;
            this.masterTimestampIndex = masterTimestampIndex;
            this.slaveTimestampIndex = slaveTimestampIndex;
        }

        @Override
        public void close() {
            masterCursor = Misc.free(masterCursor);
            slaveCursor = Misc.free(slaveCursor);
        }

        @Override
        public Record getRecord() {
            return record;
        }

        @Override
        public SymbolTable getSymbolTable(int columnIndex) {
            if (columnIndex < columnSplit) {
                return masterCursor.getSymbolTable(columnIndex);
            }
            return slaveCursor.getSymbolTable(columnIndex - columnSplit);
        }

        @Override
        public boolean hasNext() {

            if (masterCursor.hasNext()) {
                final long masterTimestamp = masterRecord.getTimestamp(masterTimestampIndex);
                MapKey key;
                MapValue value;
                long slaveTimestamp = this.slaveTimestamp;
                if (slaveTimestamp <= masterTimestamp) {

                    if (lastSlaveRowID != Numbers.LONG_NaN) {
                        slaveCursor.recordAt(lastSlaveRowID);
                        key = joinKeyMap.withKey();
                        key.put(slaveRecord, slaveKeySink);
                        value = key.createValue();
                        value.putLong(0, lastSlaveRowID);
                    }


                    while (slaveCursor.hasNext()) {
                        slaveTimestamp = slaveRecord.getTimestamp(slaveTimestampIndex);
                        if (slaveTimestamp <= masterTimestamp) {
                            key = joinKeyMap.withKey();
                            key.put(slaveRecord, slaveKeySink);
                            value = key.createValue();
                            value.putLong(0, slaveRecord.getRowId());
                        } else {
                            break;
                        }
                    }

                    // now we have dangling slave record, which we need to hold on to
                    this.slaveTimestamp = slaveTimestamp;
                    this.lastSlaveRowID = slaveRecord.getRowId();

                }
                key = joinKeyMap.withKey();
                key.put(masterRecord, masterKeySink);
                value = key.findValue();
                if (value != null) {
                    slaveCursor.recordAt(value.getLong(0));
                    record.hasSlave(true);
                } else {
                    record.hasSlave(false);
                }

                return true;
            }
            return false;
        }

        @Override
        public void toTop() {
            joinKeyMap.clear();
            slaveTimestamp = Long.MIN_VALUE;
            lastSlaveRowID = Long.MIN_VALUE;
            masterCursor.toTop();
            slaveCursor.toTop();
        }

        void of(RecordCursor masterCursor, RecordCursor slaveCursor) {
            joinKeyMap.clear();
            slaveTimestamp = Long.MIN_VALUE;
            lastSlaveRowID = Long.MIN_VALUE;
            this.masterCursor = masterCursor;
            this.slaveCursor = slaveCursor;
            this.masterRecord = masterCursor.getRecord();
            this.slaveRecord = slaveCursor.getRecord();
            record.of(masterRecord, slaveRecord);
        }
    }
}
