/**
 * Copyright (C) 2011 Akiban Technologies Inc.
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see http://www.gnu.org/licenses.
 */

package com.akiban.qp.persistitadapter;

import com.akiban.ais.model.TableIndex;
import com.akiban.qp.physicaloperator.Bindings;
import com.akiban.qp.row.HKey;
import com.akiban.qp.row.RowBase;
import com.akiban.qp.rowtype.RowType;
import com.akiban.server.FieldDef;
import com.akiban.server.api.dml.scan.NewRow;

public class NewRowBackedIndexRow implements RowBase
{
    NewRowBackedIndexRow(RowType rowType, NewRow row, TableIndex index) {
        this.rowType = rowType;
        this.row = row;
        this.index = index;
    }

    @Override
    public RowType rowType() {
        return rowType;
    }

    @Override
    public Object field(int i, Bindings bindings) {
        FieldDef fieldDef = (FieldDef) index.getColumns().get(i).getColumn().getFieldDef();
        int fieldPos = fieldDef.getFieldIndex();
        if(row.isColumnNull(fieldPos)) {
            return null;
        }
        return row.get(fieldPos);
    }

    @Override
    public HKey hKey() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean ancestorOf(RowBase that) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int runId() {
        return runId;
    }

    @Override
    public void runId(int runId) {
        this.runId  = runId;
    }

    private int runId = RowBase.UNDEFINED_RUN_ID;
    private final NewRow row;
    private final RowType rowType;
    private final TableIndex index;
}