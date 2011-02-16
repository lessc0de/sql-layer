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

package com.akiban.server.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.akiban.server.AkServerConstants;
import com.akiban.server.AkServerTestCase;
import com.akiban.server.IndexDef;
import com.akiban.server.InvalidOperationException;
import com.akiban.server.RowData;
import com.akiban.server.RowDef;
import com.akiban.server.service.session.SessionImpl;
import com.akiban.server.service.tree.TreeLink;
import com.akiban.message.ErrorCode;
import com.akiban.util.ByteBufferFactory;
import com.persistit.Exchange;
import com.persistit.KeyState;
import com.persistit.Tree;
import com.persistit.Volume;

public class PersistitStoreWithAISTest extends AkServerTestCase implements
        AkServerConstants {

    private final static String DDL_FILE_NAME = "data_dictionary_test.ddl";

    private final static String SCHEMA = "data_dictionary_test";

    private interface RowVisitor {
        void visit(final int depth) throws Exception;
    }

    private RowDef rowDef(final String name) {
        return rowDefCache.getRowDef(SCHEMA + "." + name);
    }

    class TestData {
        final RowDef defC = rowDef("customer");
        final RowDef defO = rowDef("order");
        final RowDef defI = rowDef("item");
        final RowDef defA = rowDef("address");
        final RowDef defX = rowDef("component");
        final RowDef defCOI = rowDef("_akiban_customer");
        final RowData rowC = new RowData(new byte[256]);
        final RowData rowO = new RowData(new byte[256]);
        final RowData rowI = new RowData(new byte[256]);
        final RowData rowA = new RowData(new byte[256]);
        final RowData rowX = new RowData(new byte[256]);
        final int customers;
        final int ordersPerCustomer;
        final int itemsPerOrder;
        final int componentsPerItem;

        long cid;
        long oid;
        long iid;
        long xid;

        long elapsed;
        long count = 0;

        TestData(final int customers, final int ordersPerCustomer,
                final int itemsPerOrder, final int componentsPerItem) {
            this.customers = customers;
            this.ordersPerCustomer = ordersPerCustomer;
            this.itemsPerOrder = itemsPerOrder;
            this.componentsPerItem = componentsPerItem;
        }

        void insertTestRows() throws Exception {
            elapsed = System.nanoTime();
            int unique = 0;
            for (int c = 0; ++c <= customers;) {
                cid = c;
                rowC.reset(0, 256);
                rowC.createRow(defC, new Object[] { cid, "Customer_" + cid });
                store.writeRow(session, rowC);
                for (int o = 0; ++o <= ordersPerCustomer;) {
                    oid = cid * 1000 + o;
                    rowO.reset(0, 256);
                    rowO.createRow(defO, new Object[] { oid, cid, 12345 });
                    store.writeRow(session, rowO);
                    for (int i = 0; ++i <= itemsPerOrder;) {
                        iid = oid * 1000 + i;
                        rowI.reset(0, 256);
                        rowI.createRow(defI, new Object[] { oid, iid, 123456,
                                654321 });
                        store.writeRow(session, rowI);
                        for (int x = 0; ++x <= componentsPerItem;) {
                            xid = iid * 1000 + x;
                            rowX.reset(0, 256);
                            rowX.createRow(defX, new Object[] { iid, xid, c,
                                    ++unique, "Description_" + unique });
                            store.writeRow(session, rowX);
                        }
                    }
                }
                for (int a = 0; a < (c % 3); a++) {
                    rowA.reset(0, 256);
                    rowA.createRow(defA, new Object[] { c, a, "addr1_" + c,
                            "addr2_" + c, "addr3_" + c });
                    store.writeRow(session, rowA);
                }
            }
            elapsed = System.nanoTime() - elapsed;
        }

        void visitTestRows(final RowVisitor visitor) throws Exception {
            elapsed = System.nanoTime();
            int unique = 0;
            for (int c = 0; ++c <= customers;) {
                cid = c;
                rowC.reset(0, 256);
                rowC.createRow(defC, new Object[] { cid, "Customer_" + cid });
                visitor.visit(0);
                for (int o = 0; ++o <= ordersPerCustomer;) {
                    oid = cid * 1000 + o;
                    rowO.reset(0, 256);
                    rowO.createRow(defO, new Object[] { oid, cid, 12345 });
                    visitor.visit(1);
                    for (int i = 0; ++i <= itemsPerOrder;) {
                        iid = oid * 1000 + i;
                        rowI.reset(0, 256);
                        rowI.createRow(defI, new Object[] { oid, iid, 123456,
                                654321 });
                        visitor.visit(2);
                        for (int x = 0; ++x <= componentsPerItem;) {
                            xid = iid * 1000 + x;
                            rowX.reset(0, 256);
                            rowX.createRow(defX, new Object[] { iid, xid, c,
                                    ++unique, "Description_" + unique });
                            visitor.visit(3);
                        }
                    }
                }
            }
            elapsed = System.nanoTime() - elapsed;

        }

        int totalRows() {
            return totalCustomerRows() + totalOrderRows() + totalItemRows()
                    + totalComponentRows();
        }

        int totalCustomerRows() {
            return customers;
        }

        int totalOrderRows() {
            return customers * ordersPerCustomer;
        }

        int totalItemRows() {
            return customers * ordersPerCustomer * itemsPerOrder;
        }

        int totalComponentRows() {
            return customers * ordersPerCustomer * itemsPerOrder
                    * componentsPerItem;
        }

        void start() {
            elapsed = System.nanoTime();
        }

        void end() {
            elapsed = System.nanoTime() - elapsed;
        }
    }

    @Before
    public void setUp() throws Exception {
        baseSetUp();
        setUpAisForTests(DDL_FILE_NAME);
    }

    @After
    public void tearDown() throws Exception {
        baseTearDown();
    }

    @Test
    public void testWriteCOIrows() throws Exception {
        final TestData td = new TestData(10, 10, 10, 10);
        td.insertTestRows();
        System.out.println("testWriteCOIrows: inserted " + td.totalRows()
                + " rows in " + (td.elapsed / 1000L) + "us");

    }

    @Test
    public void testScanCOIrows() throws Exception {
        final TestData td = new TestData(1000, 10, 3, 2);
        td.insertTestRows();
        System.out.println("testScanCOIrows: inserted " + td.totalRows()
                + " rows in " + (td.elapsed / 1000L) + "us");
        {
            // simple test - get all I rows
            td.start();
            int scanCount = 0;
            td.rowI.createRow(td.defI, new Object[] { null, null, null });

            final byte[] columnBitMap = new byte[] { 0xF };
            final int indexId = 0;

            final RowCollector rc = store.newRowCollector(session,
                    td.defI.getRowDefId(), indexId, 0, td.rowI, td.rowI,
                    columnBitMap);
            final ByteBuffer payload = ByteBufferFactory.allocate(256);

            while (rc.hasMore()) {
                payload.clear();
                while (rc.collectNextRow(payload))
                    ;
                payload.flip();
                RowData rowData = new RowData(payload.array(), payload.position(), payload.limit());
                for (int p = rowData.getBufferStart(); p < rowData.getBufferEnd();) {
                    rowData.prepareRow(p);
                    p = rowData.getRowEnd();
                    scanCount++;
                }
            }
            assertEquals(td.totalItemRows(), scanCount);
            td.end();
            System.out.println("testScanCOIrows: scanned " + scanCount
                    + " rows in " + (td.elapsed / 1000L) + "us");

        }

        {
            // select item by IID in user table `item`
            td.start();
            int scanCount = 0;
            td.rowI.createRow(td.defI,
                    new Object[] { null, Integer.valueOf(1001001), null, null });

            final byte[] columnBitMap = new byte[] { (byte) 0x3 };
            final int indexId = td.defI.getPKIndexDef().getId();

            final RowCollector rc = store.newRowCollector(session,
                    td.defI.getRowDefId(), indexId, 0, td.rowI, td.rowI,
                    columnBitMap);
            final ByteBuffer payload = ByteBufferFactory.allocate(256);

            while (rc.hasMore()) {
                payload.clear();
                while (rc.collectNextRow(payload))
                    ;
                payload.flip();
                RowData rowData = new RowData(payload.array(),
                        payload.position(), payload.limit());
                for (int p = rowData.getBufferStart(); p < rowData
                        .getBufferEnd();) {
                    rowData.prepareRow(p);
                    p = rowData.getRowEnd();
                    scanCount++;
                }
            }
            assertEquals(1, scanCount);
            td.end();
            System.out.println("testScanCOIrows: scanned " + scanCount
                    + " rows in " + (td.elapsed / 1000L) + "us");

        }

        {
            // select items in COI table by index values on Order
            td.start();
            int scanCount = 0;
            final RowData start = new RowData(new byte[256]);
            final RowData end = new RowData(new byte[256]);
            // C has 2 columns, O has 3 columns, A has 5 columns, I has 4
            // columns, CC has 5 columns
            final Object[] values = new Object[td.defCOI.getFieldCount()];
            final int order_id_field = td.defCOI
                    .getFieldIndex("order$order_id");
            values[order_id_field] = 1004;
            start.createRow(td.defCOI, values);
            values[order_id_field] = 1007;
            end.createRow(td.defCOI, values);
            final byte[] columnBitMap = projection(new RowDef[] { td.defC,
                    td.defO, td.defI }, td.defCOI.getFieldCount());

            int indexId = findIndexId(td.defCOI, td.defO, 0);
            final RowCollector rc = store.newRowCollector(session,
                    td.defCOI.getRowDefId(), indexId, 0, start, end,
                    columnBitMap);
            final ByteBuffer payload = ByteBufferFactory.allocate(256);
            //
            // Expect all the C, O and I rows for orders 1004 through 1007,
            // inclusive
            // Total of 40
            //
            while (rc.hasMore()) {
                payload.clear();
                while (rc.collectNextRow(payload))
                    ;
                payload.flip();
                RowData rowData = new RowData(payload.array(),
                        payload.position(), payload.limit());
                for (int p = rowData.getBufferStart(); p < rowData
                        .getBufferEnd();) {
                    rowData.prepareRow(p);
                    System.out.println(rowData.toString(rowDefCache));
                    p = rowData.getRowEnd();
                    scanCount++;
                }
            }
            assertEquals(rc.getDeliveredRows(), scanCount);
            assertEquals(17, scanCount - rc.getRepeatedRows());
            td.end();
            System.out.println("testScanCOIrows: scanned " + scanCount
                    + " rows in " + (td.elapsed / 1000L) + "us");
        }
    }

    int findIndexId(final RowDef groupRowDef, final RowDef userRowDef,
            final int fieldIndex) {
        int indexId = -1;
        final int findField = fieldIndex + userRowDef.getColumnOffset();
        for (final IndexDef indexDef : groupRowDef.getIndexDefs()) {
            if (indexDef.getFields().length == 1
                    && indexDef.getFields()[0] == findField) {
                indexId = indexDef.getId();
            }
        }
        return indexId;
    }

    final byte[] projection(final RowDef[] rowDefs, final int width) {
        final byte[] bitMap = new byte[(width + 7) / 8];
        for (final RowDef rowDef : rowDefs) {
            for (int bit = rowDef.getColumnOffset(); bit < rowDef
                    .getColumnOffset() + rowDef.getFieldCount(); bit++) {
                bitMap[bit / 8] |= (1 << (bit % 8));
            }
        }
        return bitMap;
    }

    @Test
    public void testBug686910() throws Exception {
        //
        for (int loop = 0; loop < 5; loop++) {
            final TestData td = new TestData(5, 5, 5, 5);
            td.insertTestRows();
            store.truncateTable(session, td.defI.getRowDefId());
            store.truncateTable(session, td.defO.getRowDefId());
            store.truncateTable(session, td.defC.getRowDefId());
            store.truncateTable(session, td.defCOI.getRowDefId());
            store.truncateTable(session, td.defA.getRowDefId());
            store.truncateTable(session, td.defX.getRowDefId());

            assertTrue(isGone(td.defCOI));
            assertTrue(isGone(td.defO));
            assertTrue(isGone(td.defI));
        }
    }

    @Test
    public void testUniqueIndexes() throws Exception {
        final TestData td = new TestData(5, 5, 5, 5);
        td.insertTestRows();
        td.rowX.createRow(td.defX, new Object[] { 1002003, 23890345, 123, 44,
                "test1" });
        ErrorCode actual = null;
        try {
            store.writeRow(session, td.rowX);
        } catch (InvalidOperationException e) {
            actual = e.getCode();
        }
        assertEquals(ErrorCode.DUPLICATE_KEY, actual);
        td.rowX.createRow(td.defX, new Object[] { 1002003, 23890345, 123,
                44444, "test2" });
        store.writeRow(session, td.rowX);
    }

    @Test
    public void testUpdateRows() throws Exception {
        final TestData td = new TestData(5, 5, 5, 5);
        td.insertTestRows();
        long cid = 3;
        long oid = cid * 1000 + 2;
        long iid = oid * 1000 + 4;
        long xid = iid * 1000 + 3;
        td.rowX.createRow(td.defX, new Object[] { iid, xid, null, null });
        final byte[] columnBitMap = new byte[] { (byte) 0x1F };
        final ByteBuffer payload = ByteBufferFactory.allocate(1024);

        RowCollector rc;
        rc = store.newRowCollector(session, td.defX.getRowDefId(), td.defX
                .getPKIndexDef().getId(), 0, td.rowX, td.rowX, columnBitMap);
        payload.clear();
        assertTrue(rc.collectNextRow(payload));
        payload.flip();
        RowData oldRowData = new RowData(payload.array(), payload.position(),
                payload.limit());
        oldRowData.prepareRow(oldRowData.getBufferStart());

        RowData newRowData = new RowData(new byte[256]);
        newRowData.createRow(td.defX, new Object[] { iid, xid, 4, 424242,
                "Description_424242" });
        store.updateRow(session, oldRowData, newRowData, null);

        rc = store.newRowCollector(session, td.defX.getRowDefId(), td.defX
                .getPKIndexDef().getId(), 0, td.rowX, td.rowX, columnBitMap);
        payload.clear();
        assertTrue(rc.collectNextRow(payload));
        payload.flip();

        RowData updateRowData = new RowData(payload.array(),
                payload.position(), payload.limit());
        updateRowData.prepareRow(updateRowData.getBufferStart());
        System.out.println(updateRowData.toString(store.getRowDefCache()));
        //
        // Now attempt to update a leaf table's PK field.
        //
        newRowData = new RowData(new byte[256]);
        newRowData.createRow(td.defX, new Object[] { iid, -xid, 4, 545454,
                "Description_545454" });

        store.updateRow(session, updateRowData, newRowData, null);

        rc = store.newRowCollector(session, td.defX.getRowDefId(), td.defX
                .getPKIndexDef().getId(), 0, updateRowData, updateRowData,
                columnBitMap);
        payload.clear();
        assertTrue(!rc.collectNextRow(payload));

        rc = store.newRowCollector(session, td.defX.getRowDefId(), td.defX
                .getPKIndexDef().getId(), 0, newRowData, newRowData,
                columnBitMap);

        assertTrue(rc.collectNextRow(payload));
        payload.flip();

        updateRowData = new RowData(payload.array(), payload.position(),
                payload.limit());
        updateRowData.prepareRow(updateRowData.getBufferStart());
        System.out.println(updateRowData.toString(store.getRowDefCache()));

        // TODO:
        // Hand-checked the index tables. Need SELECT on secondary indexes to
        // verify them automatically.
    }

    @Test
    public void testDeleteRows() throws Exception {
        final TestData td = new TestData(5, 5, 5, 5);
        td.insertTestRows();
        td.count = 0;
        final RowVisitor visitor = new RowVisitor() {
            public void visit(final int depth) throws Exception {
                ErrorCode expectedError = null;
                ErrorCode actualError = null;
                try {
                    switch (depth) {
                        case 0:
                        case 1:
                        case 2:
                            break;
/*
                    case 0:
                        // TODO - for now we can't do cascading DELETE so we
                        // expect an error
                        expectedError = ErrorCode.FK_CONSTRAINT_VIOLATION;
                        store.deleteRow(session, td.rowC);
                        break;
                    case 1:
                        // TODO - for now we can't do cascading DELETE so we
                        // expect an error
                        expectedError = ErrorCode.FK_CONSTRAINT_VIOLATION;
                        store.deleteRow(session, td.rowO);
                        break;
                    case 2:
                        // TODO - for now we can't do cascading DELETE so we
                        // expect an error
                        expectedError = ErrorCode.FK_CONSTRAINT_VIOLATION;
                        store.deleteRow(session, td.rowI);
                        break;
*/
                    case 3:
                        expectedError = null;
                        if (td.xid % 2 == 0) {
                            store.deleteRow(session, td.rowX);
                            td.count++;
                        }
                        break;
                    default:
                        throw new Exception("depth = " + depth);
                    }
                } catch (InvalidOperationException e) {
                    actualError = e.getCode();
                }
                assertEquals("at depth " + depth, expectedError, actualError);
            }
        };
        td.visitTestRows(visitor);

        int scanCount = 0;
        td.rowX.createRow(td.defX, new Object[0]);
        final byte[] columnBitMap = new byte[] { (byte) 0x1F };
        final RowCollector rc = store.newRowCollector(session,
                td.defX.getRowDefId(), td.defX.getPKIndexDef().getId(), 0,
                td.rowX, td.rowX, columnBitMap);
        final ByteBuffer payload = ByteBufferFactory.allocate(256);

        while (rc.hasMore()) {
            payload.clear();
            while (rc.collectNextRow(payload))
                ;
            payload.flip();
            RowData rowData = new RowData(payload.array(), payload.position(),
                    payload.limit());
            for (int p = rowData.getBufferStart(); p < rowData.getBufferEnd();) {
                rowData.prepareRow(p);
                p = rowData.getRowEnd();
                scanCount++;
            }
        }
        assertEquals(td.totalComponentRows() - td.count, scanCount);
        // TODO:
        // Hand-checked the index tables. Need SELECT on secondary indexes to
        // verify them automatically.
    }

    @Test
    public void testFetchRows() throws Exception {
        final TestData td = new TestData(5, 5, 5, 5);
        td.insertTestRows();
        {
            final List<RowData> list = store.fetchRows(session,
                    "data_dictionary_test", "item", "part_id", 1001001,
                    1001005, "item");
            assertEquals(5, list.size());
        }

        {
            final List<RowData> list = store.fetchRows(session,
                    "data_dictionary_test", "customer", "customer_id", 1, 1,
                    "item");
            assertEquals(31, list.size());
            dump("c.cid = 1", list);
        }

        {
            final List<RowData> list = store.fetchRows(session,
                    "data_dictionary_test", "order", "customer_id", 1, 1,
                    "item");
            dump("o.cid = 1", list);
            assertEquals(30, list.size());
        }

        {
            final List<RowData> list = store.fetchRows(session,
                    "data_dictionary_test", "customer", "customer_id", 1, 2,
                    "address");
            assertEquals(5, list.size());
        }

        {
            final List<RowData> list = store.fetchRows(session,
                    "data_dictionary_test", "customer", "customer_id", 1, 1,
                    null);
            for (final RowData rowData : list) {
                System.out.println(rowData.toString(rowDefCache));
            }
            assertEquals(157, list.size());
        }
    }

    private void dump(String label, List<RowData> rows) {
        System.out.println(label + ":");
        for (RowData row : rows) {
            System.out.println(row.toString(rowDefCache));
        }
    }

    @Test
    public void testCommittedUpdateListener() throws Exception {
        final Map<Integer, AtomicInteger> counts = new HashMap<Integer, AtomicInteger>();
        final CommittedUpdateListener listener = new CommittedUpdateListener() {

            @Override
            public void updated(KeyState keyState, RowDef rowDef,
                    RowData oldRowData, RowData newRowData) {
                ai(rowDef).addAndGet(1000000);
            }

            @Override
            public void inserted(KeyState keyState, RowDef rowDef,
                    RowData rowData) {
                ai(rowDef).addAndGet(1);
            }

            @Override
            public void deleted(KeyState keyState, RowDef rowDef,
                    RowData rowData) {
                ai(rowDef).addAndGet(1000);
            }

            AtomicInteger ai(final RowDef rowDef) {
                AtomicInteger ai = counts.get(rowDef.getRowDefId());
                if (ai == null) {
                    ai = new AtomicInteger();
                    counts.put(rowDef.getRowDefId(), ai);
                }
                return ai;
            }
        };

        store.addCommittedUpdateListener(listener);
        final TestData td = new TestData(5, 5, 5, 5);
        td.insertTestRows();
        assertEquals(5, counts.get(td.defC.getRowDefId()).intValue());
        assertEquals(25, counts.get(td.defO.getRowDefId()).intValue());
        assertEquals(125, counts.get(td.defI.getRowDefId()).intValue());
        assertEquals(625, counts.get(td.defX.getRowDefId()).intValue());
        //
        // Now delete or change every other X rows
        //
        int scanCount = 0;
        td.rowX.createRow(td.defX, new Object[0]);
        final byte[] columnBitMap = new byte[] { (byte) 0x1F };
        final RowCollector rc = store.newRowCollector(session,
                td.defX.getRowDefId(), td.defX.getPKIndexDef().getId(), 0,
                td.rowX, td.rowX, columnBitMap);
        final ByteBuffer payload = ByteBufferFactory.allocate(256);

        while (rc.hasMore()) {
            payload.clear();
            while (rc.collectNextRow(payload))
                ;
            payload.flip();
            RowData rowData = new RowData(payload.array(), payload.position(),
                    payload.limit());
            for (int p = rowData.getBufferStart(); p < rowData.getBufferEnd();) {
                rowData.prepareRow(p);
                if (scanCount++ % 2 == 0) {
                    store.deleteRow(session, rowData);
                } else {
                    store.updateRow(session, rowData, rowData, null);
                }
                p = rowData.getRowEnd();
            }
        }

        assertEquals(5, counts.get(td.defC.getRowDefId()).intValue());
        assertEquals(25, counts.get(td.defO.getRowDefId()).intValue());
        assertEquals(125, counts.get(td.defI.getRowDefId()).intValue());
        assertEquals(312313625, counts.get(td.defX.getRowDefId()).intValue());

    }

    @Test
    public void testDeferIndex() throws Exception {
        final TestData td = new TestData(3, 3, 0, 0);
        store.setDeferIndexes(true);
        td.insertTestRows();
        final StringWriter a, b, c, d;
        dumpIndexes(new PrintWriter(a = new StringWriter()));
        store.flushIndexes(session);
        dumpIndexes(new PrintWriter(b = new StringWriter()));
        store.deleteIndexes(new SessionImpl(), "");
        dumpIndexes(new PrintWriter(c = new StringWriter()));
        store.buildIndexes(session, "");
        dumpIndexes(new PrintWriter(d = new StringWriter()));
        assertTrue(!a.toString().equals(b.toString()));
        assertEquals(a.toString(), c.toString());
        assertEquals(b.toString(), d.toString());
    }

    @Test
    public void testRebuildIndex() throws Exception {
        final TestData td = new TestData(3, 3, 3, 3);
        td.insertTestRows();
        final StringWriter a, b, c;
        dumpIndexes(new PrintWriter(a = new StringWriter()));
        store.deleteIndexes(new SessionImpl(), "");
        dumpIndexes(new PrintWriter(b = new StringWriter()));
        store.buildIndexes(session, "");
        dumpIndexes(new PrintWriter(c = new StringWriter()));
        assertTrue(!a.toString().equals(b.toString()));
        assertEquals(a.toString(), c.toString());
    }

    // // Disabled pending Persistit commit
    // //
    // @Test
    // public void testBug283() throws Exception {
    // //
    // // Creates the index tables ahead of the h-table. This
    // // is contrived to affect the Transaction commit order.
    // //
    // //
    // store.getDb().getTransaction().run(new TransactionRunnable() {
    // public void runTransaction() throws RollbackException {
    // for (int index = 1; index < 13; index++) {
    // final String treeName = "_akiban_customer$$" + index;
    // try {
    // final Exchange exchange = store.getExchange(treeName);
    // exchange.to("testBug283").store();
    // store.releaseExchange(exchange);
    // } catch (Exception e) {
    // throw new RollbackException(e);
    // }
    // }
    // }
    // });
    // final TestData td = new TestData(1, 1, 1, 1);
    // td.insertTestRows();
    // final AtomicBoolean broken = new AtomicBoolean(false);
    // final long expires = System.nanoTime() + 10000000000L; // 10 seconds
    // final AtomicInteger lastInserted = new AtomicInteger();
    // final AtomicInteger scanCount = new AtomicInteger();
    // final Thread thread1 = new Thread(new Runnable() {
    // public void run() {
    // for (int xid = 1001001002; System.nanoTime() < expires
    // && !broken.get(); xid++) {
    // td.rowX.createRow(td.defX, new Object[] { 1001001, xid,
    // 123, xid - 100100100, "part " + xid });
    // try {
    // store.writeRow(td.rowX);
    // lastInserted.set(xid);
    // } catch (Exception e) {
    // e.printStackTrace();
    // broken.set(true);
    // break;
    // }
    // }
    // }
    // }, "INSERTER");
    //
    // final Thread thread2 = new Thread(new Runnable() {
    // public void run() {
    // final RowData start = new RowData(new byte[256]);
    // final RowData end = new RowData(new byte[256]);
    // final byte[] columnBitMap = new byte[] { (byte) 0xF };
    // final ByteBuffer payload = ByteBufferFactory.allocate(100000);
    // while (System.nanoTime() < expires && !broken.get()) {
    // int xid = lastInserted.get();
    // start.createRow(td.defX, new Object[] { 1001001, xid, null,
    // null });
    // end.createRow(td.defX, new Object[] { 1001001, xid + 10000,
    // null, null });
    //
    // final int indexId = td.defX.getPKIndexDef().getId();
    //
    // try {
    // final RowCollector rc = store.newRowCollector(
    // td.defX.getRowDefId(), indexId, 0, start, end,
    // columnBitMap);
    // while (rc.hasMore()) {
    // payload.clear();
    // while (rc.collectNextRow(payload))
    // ;
    // payload.flip();
    // RowData rowData = new RowData(payload.array(),
    // payload.position(), payload.limit());
    // for (int p = rowData.getBufferStart(); p < rowData.getBufferEnd();) {
    // rowData.prepareRow(p);
    // p = rowData.getRowEnd();
    // scanCount.incrementAndGet();
    // }
    // }
    // // } catch (InvalidOperationException ioe) {
    // // broken.set(true);
    // // break;
    // } catch (Exception e) {
    // e.printStackTrace();
    // broken.set(true);
    // break;
    // }
    //
    // }
    // }
    // }, "SCANNER");
    //
    // thread1.start();
    // thread2.start();
    // thread1.join();
    // thread2.join();
    // // For some reason the @Ignore above isn't preventing this test from
    // failing.
    // // Am commenting out the assertTrue until but 283 is fixed.
    // assertTrue(!broken.get());
    //
    // }

    private void dumpIndexes(final PrintWriter pw) throws Exception {
        for (final RowDef rowDef : rowDefCache.getRowDefs()) {
            pw.println(rowDef);
            for (final IndexDef indexDef : rowDef.getIndexDefs()) {
                pw.println(indexDef);
                dumpIndex(indexDef, pw);
            }
        }
        pw.flush();

    }

    private void dumpIndex(final IndexDef indexDef, final PrintWriter pw)
            throws Exception {
        final Exchange ex = getPersistitStore().getExchange(new SessionImpl(),
                indexDef.getRowDef(), indexDef);
        ex.clear();
        while (ex.next(true)) {
            pw.println(ex.getKey());
        }
        pw.flush();
    }

    private boolean isGone(final TreeLink link) throws Exception {
        Volume volume = getDefaultVolume();
        final Tree tree = volume.getTree(link.getTreeName(), false);
        if (tree == null) {
            return true;
        }
        final Exchange exchange = getTreeService().getExchange(session, link);
        exchange.clear();
        return !exchange.hasChildren();
    }

}