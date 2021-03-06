package com.yahoo.omid.transaction;



import static org.testng.AssertJUnit.assertNull;
import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertTrue;
import org.testng.annotations.Test;
import com.yahoo.omid.committable.CommitTable;
import com.yahoo.omid.transaction.TTable;
import com.yahoo.omid.transaction.Transaction;
import com.yahoo.omid.transaction.TransactionManager;
import com.yahoo.omid.tsoclient.TSOClient;

import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.client.ResultScanner;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.filter.BinaryComparator;
import org.apache.hadoop.hbase.filter.ColumnPrefixFilter;
import org.apache.hadoop.hbase.filter.CompareFilter.CompareOp;
import org.apache.hadoop.hbase.filter.CompareFilter;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.ValueFilter;
import org.apache.hadoop.hbase.util.Bytes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Tests to verify that Get and Scan filters still work
 * with transactions tables
 */
public class TestFilters extends OmidTestBase {
    private static final Logger LOG = LoggerFactory.getLogger(TestFilters.class);
    byte[] family = Bytes.toBytes(TEST_FAMILY);
    byte[] row1 = Bytes.toBytes("row1");
    byte[] row2 = Bytes.toBytes("row2");
    byte[] row3 = Bytes.toBytes("row3");
    byte[] prefix = Bytes.toBytes("foo");
    byte[] col1 = Bytes.toBytes("foobar");
    byte[] col2 = Bytes.toBytes("boofar");

    @Test(timeOut=60000)
    public void testGetWithColumnPrefixFilter() throws Exception {
        testGet(new ColumnPrefixFilter(prefix));
    }

    @Test(timeOut=60000)
    public void testGetWithValueFilter() throws Exception {
        testGet(new ValueFilter(CompareFilter.CompareOp.EQUAL, new BinaryComparator(col1)));
    }

    void testGet(Filter f) throws Exception {
        CommitTable.Client commitTableClient = spy(getTSO().getCommitTable().getClient().get());

        TSOClient client = TSOClient.newBuilder().withConfiguration(getTSO().getClientConfiguration())
                .build();

        TTable table = new TTable(hbaseConf, TEST_TABLE);
        AbstractTransactionManager tm = spy((AbstractTransactionManager) HBaseTransactionManager.newBuilder()
                .withConfiguration(hbaseConf)
                .withCommitTableClient(commitTableClient)
                .withTSOClient(client).build());

        writeRows(table, tm);

        Transaction t = tm.begin();
        Get g = new Get(row1);
        g.setFilter(f);

        Result r = table.get(t, g);
        assertEquals("should exist in result", 1, r.getColumnCells(family, col1).size());
        assertEquals("shouldn't exist in result", 0, r.getColumnCells(family, col2).size());

        g = new Get(row2);
        g.setFilter(f);
        r = table.get(t, g);
        assertEquals("should exist in result", 1, r.getColumnCells(family, col1).size());
        assertEquals("shouldn't exist in result", 0, r.getColumnCells(family, col2).size());

        g = new Get(row3);
        g.setFilter(f);
        r = table.get(t, g);
        assertEquals("shouldn't exist in result", 0, r.getColumnCells(family, col2).size());
    }

    @Test(timeOut=60000)
    public void testScanWithColumnPrefixFilter() throws Exception {
        testScan(new ColumnPrefixFilter(prefix));
    }

    @Test(timeOut=60000)
    public void testScanWithValueFilter() throws Exception {
        testScan(new ValueFilter(CompareFilter.CompareOp.EQUAL, new BinaryComparator(col1)));
    }

    private void testScan(Filter f) throws Exception {
        CommitTable.Client commitTableClient = spy(getTSO().getCommitTable().getClient().get());
        TSOClient client = TSOClient.newBuilder().withConfiguration(getTSO().getClientConfiguration())
                .build();
        TTable table = new TTable(hbaseConf, TEST_TABLE);
        AbstractTransactionManager tm = spy((AbstractTransactionManager) HBaseTransactionManager.newBuilder()
                .withConfiguration(hbaseConf)
                .withCommitTableClient(commitTableClient)
                .withTSOClient(client).build());

        writeRows(table, tm);

        Transaction t = tm.begin();
        Scan s = new Scan().setFilter(f);

        ResultScanner rs = table.getScanner(t, s);

        Result r = rs.next();
        assertEquals("should exist in result", 1, r.getColumnCells(family, col1).size());
        assertEquals("shouldn't exist in result", 0, r.getColumnCells(family, col2).size());

        r = rs.next();
        assertEquals("should exist in result", 1, r.getColumnCells(family, col1).size());
        assertEquals("shouldn't exist in result", 0, r.getColumnCells(family, col2).size());

        r = rs.next();
        assertNull("Last row shouldn't exist", r);
    }


    private void writeRows(TTable table, TransactionManager tm) throws Exception {
        // create normal row with both cells
        Transaction t = tm.begin();
        Put p = new Put(row1);
        p.add(family, col1, col1);
        p.add(family, col2, col2);
        table.put(t, p);
        tm.commit(t);

        // create normal row, but fail to update shadow cells
        doThrow(new TransactionManagerException("fail"))
            .when((HBaseTransactionManager)tm)
            .updateShadowCells(any(HBaseTransaction.class));

        t = tm.begin();
        p = new Put(row2);
        p.add(family, col1, col1);
        p.add(family, col2, col2);
        table.put(t, p);
        tm.commit(t);

        // create normal row with only one cell
        t = tm.begin();
        p = new Put(row3);
        p.add(family, col2, col2);
        table.put(t, p);
        tm.commit(t);
    }
}
