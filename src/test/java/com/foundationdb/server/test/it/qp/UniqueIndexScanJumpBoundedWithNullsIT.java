/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.foundationdb.server.test.it.qp;

import com.foundationdb.qp.expression.IndexBound;
import com.foundationdb.qp.operator.Operator;
import org.junit.Test;
import com.foundationdb.qp.operator.API;
import com.foundationdb.qp.expression.IndexKeyRange;
import com.foundationdb.qp.operator.Cursor;
import com.foundationdb.qp.row.Row;
import com.foundationdb.qp.rowtype.IndexRowType;
import com.foundationdb.qp.rowtype.RowType;
import com.foundationdb.server.api.dml.SetColumnSelector;
import com.foundationdb.server.api.dml.scan.NewRow;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.foundationdb.qp.rowtype.Schema;

import static com.foundationdb.qp.operator.API.cursor;
import static com.foundationdb.qp.operator.API.indexScan_Default;
import static com.foundationdb.server.test.ExpressionGenerators.field;
import static org.junit.Assert.*;

public class UniqueIndexScanJumpBoundedWithNullsIT extends OperatorITBase
{
     // Positions of fields within the index row
    private static final int A = 0;
    private static final int B = 1;
    private static final int C = 2;

    private static final int COLUMN_COUNT = 3;

    private static final boolean ASC = true;
    private static final boolean DESC = false;

    private static final SetColumnSelector INDEX_ROW_SELECTOR = new SetColumnSelector(0, 1, 2);

    private int t;
    private RowType tRowType;
    private IndexRowType idxRowType;
    private Map<Long, TestRow> indexRowMap = new HashMap<>();

    @Override
    protected void setupCreateSchema()
    {
        t = createTable(
            "schema", "t",
            "id int not null primary key",
            "a int",
            "b int",
            "c int");
        createUniqueIndex("schema", "t", "idx", "a", "b", "c");
    }

    @Override
    protected void setupPostCreateSchema()
    {
        schema = new Schema(ais());
        tRowType = schema.tableRowType(table(t));
        idxRowType = indexType(t, "a", "b", "c");
        db = new NewRow[] {
            createNewRow(t, 1010L, 1L, 11L, 110L),
            createNewRow(t, 1011L, 1L, 11L, 111L),
            createNewRow(t, 1012L, 1L, (Long)null, 122L),
            createNewRow(t, 1013L, 1L, (Long)null, 122L),
            createNewRow(t, 1014L, 1L, 13L, 132L),
            createNewRow(t, 1015L, 1L, 13L, 133L),
            createNewRow(t, 1016L, 1L, null, 122L),
            createNewRow(t, 1017L, 1L, 14L, 142L),
            createNewRow(t, 1018L, 1L, 30L, 201L),
            createNewRow(t, 1019L, 1L, 30L, null),
            createNewRow(t, 1020L, 1L, 30L, null),
            createNewRow(t, 1021L, 1L, 30L, null),
            createNewRow(t, 1022L, 1L, 30L, 300L),
            createNewRow(t, 1023L, 1L, 40L, 401L)
        };
        adapter = newStoreAdapter(schema);
        queryContext = queryContext(adapter);
        queryBindings = queryContext.createBindings();
        use(db);
        for (NewRow row : db) {
            indexRowMap.put((Long) row.get(0),
                            new TestRow(tRowType,
                                        new Object[] {row.get(1),     // a
                                                      row.get(2),     // b
                                                      row.get(3),     // c
                                                      row.get(0)    //id
                                                      }));
        }
    }
    
    /**
     * 
     * @param id
     * @return the b column of this id (used to make the lower and upper bound.
     *         This is to avoid confusion as to what 'b' values correspond to what id
     */
    private int b_of(long id)
    {
        return (int)indexRow(id).value(1).getInt32();
    }

    @Test
    public void testAAA()
    {
        testSkipNulls(1010,
                      b_of(1010), true,
                      b_of(1015), true,
                      getAAA(),
                      new long[]{1010, 1011, 1014, 1015}); // skip 1012 and 1013
    }

    @Test
    public void testAAAToMinNull()
    {
        testSkipNulls(1012, // jump to one of the nulls
                      b_of(1010), true,
                      b_of(1015), true,
                      getAAA(),
                      new long[] {1012, 1013, 1016, 1010, 1011, 1014, 1015}); // should see everything
    }                                                                         // with nulls appearing first

    @Test
    public void testDDD()
    {
        testSkipNulls(1015,
                      b_of(1010), true,
                      b_of(1015), true,
                      getDDD(),
                      new long[] {1015, 1014, 1011, 1010}); // skip 1012 and 1013
        
    }

    
    @Test
    public void testDDDToFirstNull()
    {
        testSkipNulls(1019, // jump to the first null
                      b_of(1018), true,
                      b_of(1021), true,
                      getDDD(),
                      new long[] {1021, 1020, 1019});   // 3 rows of [1L, 30L, null]
    }                                                   // (The use of (1021, 1020, 1019) is just for demonstrative purpose.
                                                        //  They could be anything as long as their mapping
    @Test                                               // index row is [1L, 30L, null] )
    public void testDDDToMiddleNull()
    {
        testSkipNulls(1020, // jump to the middle null
                      b_of(1018), true,
                      b_of(1021), true,
                      getDDD(),
                      new long[] {1021, 1020, 1019});
    }

    @Test
    public void testDDDToLastNull()
    {
        testSkipNulls(1021, // jump to the first null
                      b_of(1018), true,
                      b_of(1021), true,
                      getDDD(),
                      new long[] {1021, 1020, 1019});
    }

    @Test
    public void testAAAToFirstNull()
    {
        testSkipNulls(1019, // jump to the first null
                      b_of(1018), true,
                      b_of(1021), true,
                      getAAA(),
                      new long[] {1019, 1020, 1021, 1018, 1022});
    }
 
    @Test
    public void testAAAToMiddleNull()
    {
        testSkipNulls(1020, // jump to the middle null
                      b_of(1018), true,
                      b_of(1021), true,
                      getAAA(),
                      new long[] {1019, 1020, 1021, 1018, 1022});
    }

    @Test
    public void testAAAToLastNull()
    {
        testSkipNulls(1021, // jump to the first null
                      b_of(1018), true,
                      b_of(1021), true,
                      getAAA(),
                      new long[] {1019, 1020, 1021, 1018, 1022});
    }

    @Test
    public void testDDDToMaxNull()
    {
        testSkipNulls(1016,
                      b_of(1015), false,
                      b_of(1017), true,
                      getDDD(),
                      new long[] {}); 
    }
    
    @Test
    public void testAAD()
    {
        // currently failing
        // throw IndexOutOfBoundException

        testSkipNulls(1014,
                      b_of(1010), true,
                      b_of(1017), true,
                      getAAD(),
                      new long[] {1014, 1017});
    }

    //TODO: add more test****()

    private void testSkipNulls(long targetId,                  // location to jump to
                               int bLo, boolean lowInclusive,  // lower bound
                               int bHi, boolean hiInclusive,   // upper bound
                               API.Ordering ordering,          
                               long expected[])
    {
        Operator plan = indexScan_Default(idxRowType, bounded(1, bLo, lowInclusive, bHi, hiInclusive), ordering);
        Cursor cursor = cursor(plan, queryContext, queryBindings);
        cursor.openTopLevel();
        cursor.jump(indexRow(targetId), INDEX_ROW_SELECTOR);

        Row row;
        List<Row> actualRows = new ArrayList<>();
        
        while ((row = cursor.next()) != null)
        {
            actualRows.add(row);
        }
        cursor.closeTopLevel();

             // check the list of rows
        checkRows(actualRows, expected);
    }

    private void checkRows(List<Row> actual, long expected[])
    {
        List<Long> actualList = toListOfLong(actual);
        List<Long> expectedList = new ArrayList<>(expected.length);
        for (long val : expected)
            expectedList.add(val);

        assertEquals(expectedList, actualList);
    }

    private List<Long> toListOfLong(List<Row> rows)
    {
        List<Long> ret = new ArrayList<>(rows.size());

        for (Row row : rows)
            ret.add(getLong(row, 3));

        return ret;
    }
    
    private API.Ordering getAAA()
    {
        return ordering(A, ASC, B, ASC, C, ASC);
    }

    private API.Ordering getAAD()
    {
        return ordering(A, ASC, B, ASC, C, DESC);
    }
    
    private API.Ordering getADA()
    {
        return ordering(A, ASC, B, DESC, C, ASC);
    }

    private API.Ordering getDAA()
    {
        return ordering(A, DESC, B, ASC, C, ASC);
    }

    private API.Ordering getDAD()
    {
        return ordering(A, DESC, B, ASC, C, DESC);
    }

    private API.Ordering getDDA()
    {
        return ordering(A, DESC, B, DESC, C, ASC);
    }


    private API.Ordering getADD()
    {
         return ordering(A, ASC, B, ASC, C, DESC);
    }

    private API.Ordering getDDD()
    {
        return ordering(A, DESC, B, DESC, C, DESC);
    }

    private TestRow indexRow(long id)
    {
        return indexRowMap.get(id);
    }

    private long[] longs(long... longs)
    {
        return longs;
    }

    private IndexKeyRange bounded(long a, long bLo, boolean loInclusive, long bHi, boolean hiInclusive)
    {
        IndexBound lo = new IndexBound(new TestRow(tRowType, new Object[] {a, bLo, null, null}), new SetColumnSelector(0, 1));
        IndexBound hi = new IndexBound(new TestRow(tRowType, new Object[] {a, bHi, null, null}), new SetColumnSelector(0, 1));
        return IndexKeyRange.bounded(idxRowType, lo, loInclusive, hi, hiInclusive);
    }

    private API.Ordering ordering(Object... ord) // alternating column positions and asc/desc
    {
        API.Ordering ordering = API.ordering();
        int i = 0;
        while (i < ord.length)
        {
            int column = (Integer) ord[i++];
            boolean asc = (Boolean) ord[i++];
            ordering.append(field(idxRowType, column), asc);
        }
        return ordering;
    }
}
