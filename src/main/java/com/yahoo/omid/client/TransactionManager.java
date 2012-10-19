/**
 * Copyright (c) 2011 Yahoo! Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. See accompanying LICENSE file.
 */

package com.yahoo.omid.client;

import com.yahoo.omid.tso.RowKey;
import com.yahoo.omid.client.TransactionState.TxnPartitionState;
import com.yahoo.omid.client.TransactionState.TxnGlobalState;
import com.yahoo.omid.tso.messages.MultiCommitRequest;
import com.yahoo.omid.tso.messages.CommitRequest;
import com.yahoo.omid.tso.messages.CommitResponse;
import com.yahoo.omid.tso.messages.PrepareResponse;
import com.yahoo.omid.tso.messages.PrepareCommit;
import com.yahoo.omid.tso.messages.TimestampResponse;
import com.yahoo.omid.Statistics;
import com.yahoo.omid.OmidConfiguration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Properties;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.client.Delete;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.HTable;

/**
 * Provides the methods necessary to create and commit transactions.
 * 
 * @see TransactionalTable
 *
 */
public class TransactionManager {
    private static final Log LOG = LogFactory.getLog(TSOClient.class);

    /**
     * This lock is used to synchronized initialization of shared, static vars
     */
    private static Object lock = new Object();
    private Configuration conf;
    private HashMap<byte[], HTable> tableCache;
    /**
     * A mapping between the key-range of the partitions and the clients that 
     * interface with the corresponding status oracles
     * We use TreeMap structure to ficiliate efficient search for a covering key-range
     */
    private static TreeMap<KeyRange,TSOClient> sortedRangeClientMap = null;
    /**
     * The client that interfaces with the sequencer (i.e., broadcaster).
     * We simply reuse TSOClient class, since the required functionality is
     * already coveered with TSOClient.
     */
    private static TSOClient sequencerClient;
    /**
     * We need sequences to uniqly label our requests.
     * e.g., a timestamp request that is sent to multiple status oracles.
     * The sequence is later used to identify the corresponding responses 
     */
    private static AtomicLong sequenceGenerator = new AtomicLong();

    public TransactionManager(Configuration conf) 
        throws TransactionException, IOException {
        this.conf = conf;
        //read the setup configuration from the zookeeper and connect to the sequencer
        //as well as the status oracles
        synchronized (lock) {
            try {
                if (sortedRangeClientMap == null) {
                    OmidConfiguration omidConf = OmidConfiguration.create();
                    omidConf.loadServerConfs();
                    Properties[] soConfs = omidConf.getStatusOracleConfs();
                    TreeMap<KeyRange,Properties> sortedRangePropMap = new TreeMap();
                    for (int i = 0; i < soConfs.length; i++) {
                        String lower, upper;
                        lower = soConfs[i].getProperty("tso.start");
                        upper = soConfs[i].getProperty("tso.end");
                        KeyRange keyRange = new KeyRange(lower, upper);
                        sortedRangePropMap.put(keyRange, soConfs[i]);
                    }
                    sortedRangeClientMap = new TreeMap<KeyRange,TSOClient>();
                    int i = 0;
                    int id = BasicClient.generateUniqueId();
                    boolean introduceYourself = true;
                    //a client must not send introduction messages to the sequencer
                    //otherwise such message would be broadcasted to all the status oracles
                    sequencerClient = new TSOClient(omidConf.getSequencerConf(), id, !introduceYourself, sequenceGenerator);
                    for (Map.Entry<KeyRange,Properties> entry: sortedRangePropMap.entrySet()) {
                        TSOClient handler = new TSOClient(entry.getValue(), id, introduceYourself, sequenceGenerator);
                        sortedRangeClientMap.put(entry.getKey(), handler);
                        i++;
                    }
                }
            } catch (Exception exp) {
                System.out.println(exp.getMessage());
                exp.printStackTrace();
            }
        }
        tableCache = new HashMap<byte[], HTable>();
    }

    /**
     * Used to make decision about the type of the next transaction: local vs. global
     */
    boolean lastLocalTxnFailed = true;
    void reportFailedPartitioning() {
        lastLocalTxnFailed = true;
    }

    /**
     * used to make decision about the next selected partition for the next txn
     */
    KeyRange lastUsedKeyRange = null;
    TreeMap<KeyRange,Long> usageHistory = new TreeMap<KeyRange,Long>();
    void reportLastUsedPartition(KeyRange keyRange) {
        lastUsedKeyRange = keyRange;
        Long usageCount = usageHistory.get(keyRange);
        usageHistory.put(keyRange, usageCount == null ? 1 : usageCount+1);
    }

    /**
     * @return the most frequently used keyrange
     * useful to implement the policy of selecting the most used partition
     * This policy makes sense if a client sticks to a single partition for most 
     * of its traffic
     */
    protected KeyRange getMostFrequentKeyRange() {
        KeyRange mostFrequentKeyRange = null;
        Long mostFrequentUsage = null;
        for (Map.Entry<KeyRange,Long> entry: usageHistory.entrySet()) {
            if (mostFrequentUsage == null || entry.getValue() > mostFrequentUsage) {
                mostFrequentUsage = entry.getValue();
                mostFrequentKeyRange = entry.getKey();
            }
        }
        return mostFrequentKeyRange;
    }

    /**
     * This method implement the policy to choose a partition for the transaction
     * @return the partition keyrange and the TSOClient that is the interface 
     * to the selected partition
     */
    protected Map.Entry<KeyRange,TSOClient> selectAPartition() {
        Map.Entry<KeyRange,TSOClient> chosen = null;
        if (lastUsedKeyRange == null)
            chosen = sortedRangeClientMap.firstEntry();
        else {
            KeyRange mostFrequentKeyRange = getMostFrequentKeyRange();
            chosen = sortedRangeClientMap.floorEntry(mostFrequentKeyRange);
        }
        //System.out.println("CHOSEN: " + chosen.getKey() + " " + chosen.getValue());
        return chosen;
    }

    /**
     * Starts a new transaction.
     * 
     * This method returns an opaque {@link TransactionState} object, used by {@link TransactionalTable}'s methods
     * for performing operations on a given transaction.
     * 
     * @return Opaque object which identifies one transaction.
     * @throws TransactionException
     */
    public TransactionState beginTransaction() throws TransactionException {
        if (lastLocalTxnFailed) {
            lastLocalTxnFailed = false;
            return beginGlobalTransaction();
        }
        Map.Entry<KeyRange,TSOClient> entry = selectAPartition();
        TSOClient tsoclient = entry.getValue();
        KeyRange keyRange = entry.getKey();
        PingPongCallback<TimestampResponse> cb = new PingPongCallback<TimestampResponse>();
        try {
            tsoclient.getNewTimestamp(cb);
            cb.await();
        } catch (Exception e) {
            throw new TransactionException("Could not get new timestamp", e);
        }
        if (cb.getException() != null) {
            throw new TransactionException("Error retrieving timestamp", cb.getException());
        }

        TimestampResponse pong = cb.getPong();
        //required to efficiently keep track of aborted transactions
        tsoclient.aborted.aTxnStarted(pong.timestamp);
        return new TransactionState(pong.timestamp, tsoclient, keyRange, this);
    }

    /**
     * start a global transaction
     */
    public TransactionState beginGlobalTransaction() throws TransactionException {
        boolean failed = false;
        long sequence;
        PingPongCallback<TimestampResponse>[] tscbs;
        try {
            tscbs = new PingPongCallback[sortedRangeClientMap.size()];
            sequence = sequenceGenerator.getAndIncrement();
            int i = 0;
            for (TSOClient tsoClient: sortedRangeClientMap.values()) {
                tscbs[i] = tsoClient.registerTimestampCallback(sequence);
                i++;
            }
            boolean readonly = false;
            sequencerClient.getNewIndirectTimestamp(sequence, readonly);
            for (i = 0; i < sortedRangeClientMap.size(); i++) {
                tscbs[i].await();
                if (tscbs[i].getException() != null)
                    failed = true;
            }
        } catch (Exception e) {
            throw new TransactionException("Could not get new timestamp", e);
        }
        if (failed)
            throw new TransactionException("Error retrieving timestamp for a global transaction", null);
        //keep track of started transactions
        long[] vts = new long[tscbs.length];
        for (int i = 0; i < tscbs.length; i++)
            vts[i] = tscbs[i].getPong().timestamp;
        int i = 0;
        //required to efficiently keep track of aborted transactions
        for (TSOClient tsoClient: sortedRangeClientMap.values()) {
            tsoClient.aborted.aTxnStarted(vts[i]);
            i++;
        }

        return new TransactionState(sequence, vts, sortedRangeClientMap, this);
    }

    /**
     * Commits a transaction. If the transaction is aborted it automatically rollbacks the changes and
     * throws a {@link CommitUnsuccessfulException}.  
     * 
     * @param transactionState Object identifying the transaction to be committed.
     * @throws CommitUnsuccessfulException
     * @throws TransactionException
     */
    public void tryCommit(TransactionState transactionState)
        throws CommitUnsuccessfulException, TransactionException {
        if (transactionState.txnState.isGlobal()) {
            tryGlobalCommit(transactionState);
            return;
        }
        TxnPartitionState txnState =
            (TxnPartitionState) transactionState.txnState;
        TSOClient tsoclient = txnState.tsoclient;
        Statistics.fullReport(Statistics.Tag.COMMIT, 1);
        if (LOG.isTraceEnabled()) {
            LOG.trace("tryCommit " + txnState.getStartTimestamp());
        }
        PingPongCallback<CommitResponse> cb = new PingPongCallback<CommitResponse>();
        try {
            CommitRequest msg = new CommitRequest(txnState.getStartTimestamp(),
                    txnState.getWrittenRows(),
                    txnState.getReadRows());
            tsoclient.commit(txnState.getStartTimestamp(), msg, cb);
            cb.await();
        } catch (Exception e) {
            throw new TransactionException("Could not commit", e);
        }
        if (cb.getException() != null) {
            throw new TransactionException("Error committing", cb.getException());
        }

        CommitResponse pong = cb.getPong();
        if (LOG.isTraceEnabled()) {
            LOG.trace("doneCommit " + txnState.getStartTimestamp() +
                    " TS_c: " + pong.commitTimestamp +
                    " Success: " + pong.committed);
        }

        tsoclient.aborted.aTxnFinished(txnState.getStartTimestamp());

        if (!pong.committed) {
            cleanup(txnState);
            throw new CommitUnsuccessfulException();
        }
        txnState.setCommitTimestamp(pong.commitTimestamp);
        if (pong.isElder()) {
            reincarnate(txnState, pong.rowsWithWriteWriteConflict);
            try {
                txnState.tsoclient.completeReincarnation(txnState.getStartTimestamp(), PingCallback.DUMMY);
            } catch (IOException e) {
                LOG.error("Couldn't send reincarnation report", e);
            }
        }
        Statistics.println();
    }

    /**
     * commit a global transaction
     */
    public void tryGlobalCommit(TransactionState transactionState)
        throws CommitUnsuccessfulException, TransactionException {
        TxnGlobalState txnState = (TxnGlobalState) transactionState.txnState;
        Statistics.fullReport(Statistics.Tag.COMMIT, 1);
        if (LOG.isTraceEnabled()) {
            LOG.trace("tryGlobalCommit " + txnState.getSequence());
        }

        boolean failed = false;
        int numOfPartitions = sortedRangeClientMap.size();
        PingPongCallback<CommitResponse>[] tccbs = new PingPongCallback[numOfPartitions];
        try {
            //1. send prepare commits: first phase of 2pc
            PingPongCallback<PrepareResponse>[] prcbs;
            prcbs = new PingPongCallback[numOfPartitions];
            int i = 0;
            for (Map.Entry<KeyRange,TxnPartitionState> entry: txnState.getPartitions().entrySet()) {
                prcbs[i] = new PingPongCallback<PrepareResponse>();
                TxnPartitionState txnPartitionState = entry.getValue();
                long ts = txnPartitionState.getStartTimestamp();
                PrepareCommit pcmsg = new PrepareCommit(ts,
                        txnPartitionState.getWrittenRows(),
                        txnPartitionState.getReadRows(),
                        txnState.vts);
                txnPartitionState.tsoclient.prepareCommit(ts, pcmsg, prcbs[i]);
                i++;
            }
            //2. wait for prepare response from all nodes
            boolean success = true;
            for (PingPongCallback<PrepareResponse> prcb: prcbs) {
                prcb.await();
                success = success && prcb.getPong().committed;
            }
            //3. get a vector commit timestamp: second phase of 2pc
            //3.1 first register for the responses
            i = 0;
            for (Map.Entry<KeyRange,TxnPartitionState> entry: txnState.getPartitions().entrySet()) {
                TxnPartitionState txnPartitionState = entry.getValue();
                long ts = txnPartitionState.getStartTimestamp();
                tccbs[i] = txnPartitionState.tsoclient.registerCommitCallback(ts);
                i++;
            }
            //3.2 send the request to the seuqencer
            MultiCommitRequest mcr = new MultiCommitRequest(txnState.vts);
            mcr.successfulPrepared = success;
            sequencerClient.getNewIndirectCommitTimestamp(mcr);
            //3.3 wait for commit responses
            failed = false;
            for (i = 0; i < numOfPartitions; i++) {
                tccbs[i].await();
                if (tccbs[i].getException() != null)
                    failed = true;
            }
        } catch (Exception e) {
            throw new TransactionException("Exception in committing global txn", e);
        } finally {
            for (TxnPartitionState txnPartitionState: txnState.getPartitions().values())
                txnPartitionState.tsoclient.aborted.aTxnFinished(txnPartitionState.getStartTimestamp());
            if (failed)
                throw new TransactionException("Error committing global txn", null);
        }


        boolean aborted = false;
        for (PingPongCallback<CommitResponse> tccb: tccbs)
            aborted = aborted || !tccb.getPong().committed;
        if (LOG.isTraceEnabled()) {
            LOG.trace("doneCommit " + txnState.getSequence() +
                    " Success: " + !aborted);
        }

        //cleanup after abort
        if (aborted) {
            for (TxnPartitionState txnPartitionState: txnState.getPartitions().values())
                cleanup(txnPartitionState);
            throw new CommitUnsuccessfulException();
        }
        //reincarnate if it is necessary
        int i = 0;
        for (TxnPartitionState txnPartitionState: txnState.getPartitions().values()) {
            txnPartitionState.setCommitTimestamp(tccbs[i].getPong().commitTimestamp);
            if (tccbs[i].getPong().isElder()) {
                reincarnate(txnPartitionState, tccbs[i].getPong().rowsWithWriteWriteConflict);
                try {
                    txnPartitionState.tsoclient.completeReincarnation(txnPartitionState.getStartTimestamp(), PingCallback.DUMMY);
                } catch (IOException e) {
                    LOG.error("Couldn't send reincarnation report", e);
                }
            }
            i++;
        }
        Statistics.println();
    }

    /**
     * Aborts a transaction and automatically rollbacks the changes.
     * 
     * @param transactionState Object identifying the transaction to be committed.
     * @throws TransactionException
     */
    public void abort(TransactionState transactionState) throws TransactionException {
        if (transactionState.txnState.isGlobal()) {
            LOG.error("local aborting of a global transaction!");
            //TODO: implement global abort
            //tryGlobalAbort(transactionState);
            return;
        }
        TxnPartitionState txnState =
            (TxnPartitionState) transactionState.txnState;
        TSOClient tsoclient = txnState.tsoclient;
        if (LOG.isTraceEnabled()) {
            LOG.trace("abort " + txnState.getStartTimestamp());
        }
        try {
            tsoclient.abort(txnState.getStartTimestamp());
        } catch (Exception e) {
            throw new TransactionException("Could not abort", e);
        }

        if (LOG.isTraceEnabled()) {
            LOG.trace("doneAbort " + txnState.getStartTimestamp());
        }

        tsoclient.aborted.aTxnFinished(txnState.getStartTimestamp());

        // Make sure its commit timestamp is 0, so the cleanup does the right job
        txnState.setCommitTimestamp(0);
        cleanup(txnState);
    }

    private void reincarnate(final TxnPartitionState txnState, ArrayList<RowKey> rowsWithWriteWriteConflict)
        throws TransactionException {
        Statistics.fullReport(Statistics.Tag.REINCARNATION, 1);
        Map<byte[], List<Put>> putBatches = new HashMap<byte[], List<Put>>();
        for (final RowKeyFamily rowkey : txnState.getWrittenRows()) {
            //TODO: do it only for rowsWithWriteWriteConflict
            List<Put> batch = putBatches.get(rowkey.getTable());
            if (batch == null) {
                batch = new ArrayList<Put>();
                putBatches.put(rowkey.getTable(), batch);
            }
            Put put = new Put(rowkey.getRow(), txnState.getCommitTimestamp());
            for (Entry<byte[], List<KeyValue>> entry : rowkey.getFamilies().entrySet())
                for (KeyValue kv : entry.getValue())
                    try {
                        put.add(new KeyValue(kv.getRow(), kv.getFamily(), kv.getQualifier(), txnState.getCommitTimestamp(), kv.getValue()));
                    } catch (IOException ioe) {
                        throw new TransactionException("Could not add put operation in reincarnation " + entry.getKey(), ioe);
                    }
            batch.add(put);
        }
        for (final Entry<byte[], List<Put>> entry : putBatches.entrySet()) {
            try {
                HTable table = tableCache.get(entry.getKey());
                if (table == null) {
                    table = new HTable(conf, entry.getKey());
                    tableCache.put(entry.getKey(), table);
                }
                table.put(entry.getValue());
            } catch (IOException ioe) {
                throw new TransactionException("Could not reincarnate for table " + entry.getKey(), ioe);
            }
        }
    }

    private void cleanup(final TxnPartitionState txnState)
        throws TransactionException {
        TSOClient tsoclient = txnState.tsoclient;
        Map<byte[], List<Delete>> deleteBatches = new HashMap<byte[], List<Delete>>();
        for (final RowKeyFamily rowkey : txnState.getWrittenRows()) {
            List<Delete> batch = deleteBatches.get(rowkey.getTable());
            if (batch == null) {
                batch = new ArrayList<Delete>();
                deleteBatches.put(rowkey.getTable(), batch);
            }
            Delete delete = new Delete(rowkey.getRow());
            for (Entry<byte[], List<KeyValue>> entry : rowkey.getFamilies().entrySet()) {
                for (KeyValue kv : entry.getValue()) {
                    delete.deleteColumn(entry.getKey(), kv.getQualifier(), txnState.getStartTimestamp());
                }
            }
            batch.add(delete);
        }
        for (final Entry<byte[], List<Delete>> entry : deleteBatches.entrySet()) {
            try {
                HTable table = tableCache.get(entry.getKey());
                if (table == null) {
                    table = new HTable(conf, entry.getKey());
                    tableCache.put(entry.getKey(), table);
                }
                table.delete(entry.getValue());
            } catch (IOException ioe) {
                throw new TransactionException("Could not clean up for table " + entry.getKey(), ioe);
            }
        }
        try {
            tsoclient.completeAbort(txnState.getStartTimestamp(), PingCallback.DUMMY );
        } catch (IOException ioe) {
            throw new TransactionException("Could not notify TSO about cleanup completion for transaction " +
                    txnState.getStartTimestamp(), ioe);
        }
    }
}
