/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hdfs.server.blockmanagement;

import static org.apache.hadoop.test.MetricsAsserts.getMetrics;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.CreateFlag;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hdfs.DFSConfigKeys;
import org.apache.hadoop.hdfs.DFSOutputStream;
import org.apache.hadoop.hdfs.DFSTestUtil;
import org.apache.hadoop.hdfs.DistributedFileSystem;
import org.apache.hadoop.hdfs.HdfsConfiguration;
import org.apache.hadoop.hdfs.StorageType;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.apache.hadoop.hdfs.protocol.Block;
import org.apache.hadoop.hdfs.protocol.BlockListAsLongs;
import org.apache.hadoop.hdfs.protocol.ExtendedBlock;
import org.apache.hadoop.hdfs.protocol.HdfsConstants;
import org.apache.hadoop.hdfs.protocol.LocatedBlock;
import org.apache.hadoop.hdfs.protocol.LocatedBlocks;
import org.apache.hadoop.hdfs.server.blockmanagement.DatanodeDescriptor.BlockTargetPair;
import org.apache.hadoop.hdfs.server.datanode.DataNode;
import org.apache.hadoop.hdfs.server.datanode.InternalDataNodeTestUtils;
import org.apache.hadoop.hdfs.server.datanode.FinalizedReplica;
import org.apache.hadoop.hdfs.server.datanode.ReplicaBeingWritten;
import org.apache.hadoop.hdfs.server.namenode.CacheManager;
import org.apache.hadoop.hdfs.server.namenode.CachedBlock;
import org.apache.hadoop.hdfs.server.namenode.FSNamesystem;
import org.apache.hadoop.hdfs.server.namenode.NameNodeAdapter;
import org.apache.hadoop.hdfs.server.namenode.ha.HAContext;
import org.apache.hadoop.hdfs.server.namenode.ha.HAState;
import org.apache.hadoop.hdfs.server.protocol.BlockReportContext;
import org.apache.hadoop.hdfs.server.protocol.DatanodeRegistration;
import org.apache.hadoop.hdfs.server.protocol.DatanodeStorage;
import org.apache.hadoop.hdfs.server.protocol.NamenodeProtocols;
import org.apache.hadoop.hdfs.server.protocol.ReceivedDeletedBlockInfo;
import org.apache.hadoop.hdfs.server.protocol.StorageReceivedDeletedBlocks;
import org.apache.hadoop.hdfs.server.protocol.StorageReport;
import org.apache.hadoop.io.EnumSetWritable;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.ipc.RemoteException;
import org.apache.hadoop.metrics2.MetricsRecordBuilder;
import org.apache.hadoop.net.NetworkTopology;
import org.apache.hadoop.test.GenericTestUtils;
import org.apache.hadoop.test.MetricsAsserts;
import org.apache.hadoop.util.GSet;
import org.apache.hadoop.util.LightWeightGSet;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.junit.Assert;
import org.apache.hadoop.test.GenericTestUtils;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import java.util.Collections;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Lists;

public class TestBlockManager {
  private DatanodeStorageInfo[] storages;
  private List<DatanodeDescriptor> nodes;
  private List<DatanodeDescriptor> rackA;
  private List<DatanodeDescriptor> rackB;

  /**
   * Some of these tests exercise code which has some randomness involved -
   * ie even if there's a bug, they may pass because the random node selection
   * chooses the correct result.
   * 
   * Since they're true unit tests and run quickly, we loop them a number
   * of times trying to trigger the incorrect behavior.
   */
  private static final int NUM_TEST_ITERS = 30;
  
  private static final int BLOCK_SIZE = 64*1024;
  
  private Configuration conf;
  private FSNamesystem fsn;
  private BlockManager bm;

  @Before
  public void setupMockCluster() throws IOException {
    conf = new HdfsConfiguration();
    conf.set(DFSConfigKeys.NET_TOPOLOGY_SCRIPT_FILE_NAME_KEY,
        "need to set a dummy value here so it assumes a multi-rack cluster");
    fsn = Mockito.mock(FSNamesystem.class);
    Mockito.doReturn(true).when(fsn).hasWriteLock();
    bm = new BlockManager(fsn, fsn, conf);
    //Make shouldPopulaeReplQueues return true
    HAContext haContext = Mockito.mock(HAContext.class);
    HAState haState = Mockito.mock(HAState.class);
    Mockito.when(haContext.getState()).thenReturn(haState);
    Mockito.when(haState.shouldPopulateReplQueues()).thenReturn(true);
    Mockito.when(fsn.getHAContext()).thenReturn(haContext);
    fsn.setInitializedReplQueues(true);
    CacheManager cm = Mockito.mock(CacheManager.class);
    Mockito.doReturn(cm).when(fsn).getCacheManager();
    GSet<CachedBlock, CachedBlock> cb =
        new LightWeightGSet<CachedBlock, CachedBlock>(1);
    Mockito.when(cm.getCachedBlocks()).thenReturn(cb);

    final String[] racks = {
        "/rackA",
        "/rackA",
        "/rackA",
        "/rackB",
        "/rackB",
        "/rackB"};
    storages = DFSTestUtil.createDatanodeStorageInfos(racks);
    nodes = Arrays.asList(DFSTestUtil.toDatanodeDescriptor(storages));
    rackA = nodes.subList(0, 3);
    rackB = nodes.subList(3, 6);
  }

  private void addNodes(Iterable<DatanodeDescriptor> nodesToAdd) {
    NetworkTopology cluster = bm.getDatanodeManager().getNetworkTopology();
    // construct network topology
    for (DatanodeDescriptor dn : nodesToAdd) {
      cluster.add(dn);
      dn.getStorageInfos()[0].setUtilizationForTesting(
          2 * HdfsConstants.MIN_BLOCKS_FOR_WRITE*BLOCK_SIZE, 0L,
          2 * HdfsConstants.MIN_BLOCKS_FOR_WRITE*BLOCK_SIZE, 0L);
      dn.updateHeartbeat(
          BlockManagerTestUtil.getStorageReportsForDatanode(dn), 0L, 0L, 0, 0,
          null);
      bm.getDatanodeManager().checkIfClusterIsNowMultiRack(dn);
    }
  }

  private void removeNode(DatanodeDescriptor deadNode) {
    NetworkTopology cluster = bm.getDatanodeManager().getNetworkTopology();
    cluster.remove(deadNode);
    bm.removeBlocksAssociatedTo(deadNode);
  }


  /**
   * Test that replication of under-replicated blocks is detected
   * and basically works
   */
  @Test
  public void testBasicReplication() throws Exception {
    addNodes(nodes);
    for (int i = 0; i < NUM_TEST_ITERS; i++) {
      doBasicTest(i);
    }
  }
  
  private void doBasicTest(int testIndex) {
    List<DatanodeStorageInfo> origStorages = getStorages(0, 1);
    List<DatanodeDescriptor> origNodes = getNodes(origStorages);
    BlockInfo blockInfo = addBlockOnNodes(testIndex, origNodes);

    DatanodeStorageInfo[] pipeline = scheduleSingleReplication(blockInfo);
    assertEquals(2, pipeline.length);
    assertTrue("Source of replication should be one of the nodes the block " +
        "was on. Was: " + pipeline[0],
        origStorages.contains(pipeline[0]));
    assertTrue("Destination of replication should be on the other rack. " +
        "Was: " + pipeline[1],
        rackB.contains(pipeline[1].getDatanodeDescriptor()));
  }
  

  /**
   * Regression test for HDFS-1480
   * - Cluster has 2 racks, A and B, each with three nodes.
   * - Block initially written on A1, A2, B1
   * - Admin decommissions two of these nodes (let's say A1 and A2 but it doesn't matter)
   * - Re-replication should respect rack policy
   */
  @Test
  public void testTwoOfThreeNodesDecommissioned() throws Exception {
    addNodes(nodes);
    for (int i = 0; i < NUM_TEST_ITERS; i++) {
      doTestTwoOfThreeNodesDecommissioned(i);
    }
  }
  
  private void doTestTwoOfThreeNodesDecommissioned(int testIndex) throws Exception {
    // Block originally on A1, A2, B1
    List<DatanodeStorageInfo> origStorages = getStorages(0, 1, 3);
    List<DatanodeDescriptor> origNodes = getNodes(origStorages);
    BlockInfo blockInfo = addBlockOnNodes(testIndex, origNodes);
    
    // Decommission two of the nodes (A1, A2)
    List<DatanodeDescriptor> decomNodes = startDecommission(0, 1);
    
    DatanodeStorageInfo[] pipeline = scheduleSingleReplication(blockInfo);
    assertTrue("Source of replication should be one of the nodes the block " +
        "was on. Was: " + pipeline[0],
        origStorages.contains(pipeline[0]));
    assertEquals("Should have three targets", 3, pipeline.length);
    
    boolean foundOneOnRackA = false;
    for (int i = 1; i < pipeline.length; i++) {
      DatanodeDescriptor target = pipeline[i].getDatanodeDescriptor();
      if (rackA.contains(target)) {
        foundOneOnRackA = true;
      }
      assertFalse(decomNodes.contains(target));
      assertFalse(origNodes.contains(target));
    }
    
    assertTrue("Should have at least one target on rack A. Pipeline: " +
        Joiner.on(",").join(pipeline),
        foundOneOnRackA);
  }
  

  /**
   * Test what happens when a block is on three nodes, and all three of those
   * nodes are decommissioned. It should properly re-replicate to three new
   * nodes. 
   */
  @Test
  public void testAllNodesHoldingReplicasDecommissioned() throws Exception {
    addNodes(nodes);
    for (int i = 0; i < NUM_TEST_ITERS; i++) {
      doTestAllNodesHoldingReplicasDecommissioned(i);
    }
  }

  private void doTestAllNodesHoldingReplicasDecommissioned(int testIndex) throws Exception {
    // Block originally on A1, A2, B1
    List<DatanodeStorageInfo> origStorages = getStorages(0, 1, 3);
    List<DatanodeDescriptor> origNodes = getNodes(origStorages);
    BlockInfo blockInfo = addBlockOnNodes(testIndex, origNodes);
    
    // Decommission all of the nodes
    List<DatanodeDescriptor> decomNodes = startDecommission(0, 1, 3);
    
    DatanodeStorageInfo[] pipeline = scheduleSingleReplication(blockInfo);
    assertTrue("Source of replication should be one of the nodes the block " +
        "was on. Was: " + pipeline[0],
        origStorages.contains(pipeline[0]));
    assertEquals("Should have three targets", 4, pipeline.length);
    
    boolean foundOneOnRackA = false;
    boolean foundOneOnRackB = false;
    for (int i = 1; i < pipeline.length; i++) {
      DatanodeDescriptor target = pipeline[i].getDatanodeDescriptor();
      if (rackA.contains(target)) {
        foundOneOnRackA = true;
      } else if (rackB.contains(target)) {
        foundOneOnRackB = true;
      }
      assertFalse(decomNodes.contains(target));
      assertFalse(origNodes.contains(target));
    }
    
    assertTrue("Should have at least one target on rack A. Pipeline: " +
        Joiner.on(",").join(pipeline),
        foundOneOnRackA);
    assertTrue("Should have at least one target on rack B. Pipeline: " +
        Joiner.on(",").join(pipeline),
        foundOneOnRackB);
  }

  /**
   * Test what happens when there are two racks, and an entire rack is
   * decommissioned.
   * 
   * Since the cluster is multi-rack, it will consider the block
   * under-replicated rather than create a third replica on the
   * same rack. Adding a new node on a third rack should cause re-replication
   * to that node.
   */
  @Test
  public void testOneOfTwoRacksDecommissioned() throws Exception {
    addNodes(nodes);
    for (int i = 0; i < NUM_TEST_ITERS; i++) {
      doTestOneOfTwoRacksDecommissioned(i);
    }
  }
  
  private void doTestOneOfTwoRacksDecommissioned(int testIndex) throws Exception {
    // Block originally on A1, A2, B1
    List<DatanodeStorageInfo> origStorages = getStorages(0, 1, 3);
    List<DatanodeDescriptor> origNodes = getNodes(origStorages);
    BlockInfo blockInfo = addBlockOnNodes(testIndex, origNodes);
    
    // Decommission all of the nodes in rack A
    List<DatanodeDescriptor> decomNodes = startDecommission(0, 1, 2);
    
    DatanodeStorageInfo[] pipeline = scheduleSingleReplication(blockInfo);
    assertTrue("Source of replication should be one of the nodes the block " +
        "was on. Was: " + pipeline[0],
        origStorages.contains(pipeline[0]));
    // Only up to two nodes can be picked per rack when there are two racks.
    assertEquals("Should have two targets", 2, pipeline.length);
    
    boolean foundOneOnRackB = false;
    for (int i = 1; i < pipeline.length; i++) {
      DatanodeDescriptor target = pipeline[i].getDatanodeDescriptor();
      if (rackB.contains(target)) {
        foundOneOnRackB = true;
      }
      assertFalse(decomNodes.contains(target));
      assertFalse(origNodes.contains(target));
    }
    
    assertTrue("Should have at least one target on rack B. Pipeline: " +
        Joiner.on(",").join(pipeline),
        foundOneOnRackB);
    
    // Mark the block as received on the target nodes in the pipeline
    fulfillPipeline(blockInfo, pipeline);

    // the block is still under-replicated. Add a new node. This should allow
    // the third off-rack replica.
    DatanodeDescriptor rackCNode =
      DFSTestUtil.getDatanodeDescriptor("7.7.7.7", "/rackC");
    rackCNode.updateStorage(new DatanodeStorage(DatanodeStorage.generateUuid()));
    addNodes(ImmutableList.of(rackCNode));
    try {
      DatanodeStorageInfo[] pipeline2 = scheduleSingleReplication(blockInfo);
      assertEquals(2, pipeline2.length);
      assertEquals(rackCNode, pipeline2[1].getDatanodeDescriptor());
    } finally {
      removeNode(rackCNode);
    }
  }

  /**
   * Unit test version of testSufficientlyReplBlocksUsesNewRack from
   * {@link TestBlocksWithNotEnoughRacks}.
   **/
  @Test
  public void testSufficientlyReplBlocksUsesNewRack() throws Exception {
    addNodes(nodes);
    for (int i = 0; i < NUM_TEST_ITERS; i++) {
      doTestSufficientlyReplBlocksUsesNewRack(i);
    }
  }

  private void doTestSufficientlyReplBlocksUsesNewRack(int testIndex) {
    // Originally on only nodes in rack A.
    List<DatanodeDescriptor> origNodes = rackA;
    BlockInfo blockInfo = addBlockOnNodes(testIndex, origNodes);
    DatanodeStorageInfo pipeline[] = scheduleSingleReplication(blockInfo);
    
    assertEquals(2, pipeline.length); // single new copy
    assertTrue("Source of replication should be one of the nodes the block " +
        "was on. Was: " + pipeline[0],
        origNodes.contains(pipeline[0].getDatanodeDescriptor()));
    assertTrue("Destination of replication should be on the other rack. " +
        "Was: " + pipeline[1],
        rackB.contains(pipeline[1].getDatanodeDescriptor()));
  }
  
  @Test
  public void testBlocksAreNotUnderreplicatedInSingleRack() throws Exception {
    List<DatanodeDescriptor> nodes = ImmutableList.of(
        BlockManagerTestUtil.getDatanodeDescriptor("1.1.1.1", "/rackA", true),
        BlockManagerTestUtil.getDatanodeDescriptor("2.2.2.2", "/rackA", true),
        BlockManagerTestUtil.getDatanodeDescriptor("3.3.3.3", "/rackA", true),
        BlockManagerTestUtil.getDatanodeDescriptor("4.4.4.4", "/rackA", true),
        BlockManagerTestUtil.getDatanodeDescriptor("5.5.5.5", "/rackA", true),
        BlockManagerTestUtil.getDatanodeDescriptor("6.6.6.6", "/rackA", true)
      );
    addNodes(nodes);
    List<DatanodeDescriptor> origNodes = nodes.subList(0, 3);
    for (int i = 0; i < NUM_TEST_ITERS; i++) {
      doTestSingleRackClusterIsSufficientlyReplicated(i, origNodes);
    }
  }
  
  private void doTestSingleRackClusterIsSufficientlyReplicated(int testIndex,
      List<DatanodeDescriptor> origNodes)
      throws Exception {
    assertEquals(0, bm.numOfUnderReplicatedBlocks());
    BlockInfo block = addBlockOnNodes(testIndex, origNodes);
    bm.processMisReplicatedBlocks();
    assertFalse(bm.isNeededReplication(block, bm.countNodes(block)));
  }

  @Test(timeout = 60000)
  public void testNeededReplicationWhileAppending() throws IOException {
    Configuration conf = new HdfsConfiguration();
    String src = "/test-file";
    Path file = new Path(src);
    MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf).build();
    cluster.waitActive();
    try {
      BlockManager bm = cluster.getNamesystem().getBlockManager();
      FileSystem fs = cluster.getFileSystem();
      NamenodeProtocols namenode = cluster.getNameNodeRpc();
      DFSOutputStream out = null;
      try {
        out = (DFSOutputStream) (fs.create(file).
            getWrappedStream());
        out.write(1);
        out.hflush();
        out.close();
        FSDataInputStream in = null;
        ExtendedBlock oldBlock = null;
        LocatedBlock oldLoactedBlock = null;
        try {
          in = fs.open(file);
          oldLoactedBlock = DFSTestUtil.getAllBlocks(in).get(0);
          oldBlock = oldLoactedBlock.getBlock();
        } finally {
          IOUtils.closeStream(in);
        }
        String clientName =
            ((DistributedFileSystem) fs).getClient().getClientName();
        namenode.append(src, clientName);
        LocatedBlock newLocatedBlock =
            namenode.updateBlockForPipeline(oldBlock, clientName);
        ExtendedBlock newBlock =
            new ExtendedBlock(oldBlock.getBlockPoolId(), oldBlock.getBlockId(),
                oldBlock.getNumBytes(),
                newLocatedBlock.getBlock().getGenerationStamp());
        namenode.updatePipeline(clientName, oldBlock, newBlock,
            oldLoactedBlock.getLocations(), oldLoactedBlock.getStorageIDs());
        BlockInfo bi = bm.getStoredBlock(newBlock.getLocalBlock());
        assertFalse(bm.isNeededReplication(bi, bm.countNodes(bi)));
      } finally {
        IOUtils.closeStream(out);
      }
    } finally {
      cluster.shutdown();
    }
  }


  /**
   * Tell the block manager that replication is completed for the given
   * pipeline.
   */
  private void fulfillPipeline(BlockInfo blockInfo,
      DatanodeStorageInfo[] pipeline) throws IOException {
    for (int i = 1; i < pipeline.length; i++) {
      DatanodeStorageInfo storage = pipeline[i];
      bm.addBlock(storage, blockInfo, null);
      blockInfo.addStorage(storage);
    }
  }

  private BlockInfo blockOnNodes(long blkId, List<DatanodeDescriptor> nodes) {
    Block block = new Block(blkId);
    BlockInfo blockInfo = new BlockInfo(block, (short) 3);

    for (DatanodeDescriptor dn : nodes) {
      for (DatanodeStorageInfo storage : dn.getStorageInfos()) {
        blockInfo.addStorage(storage);
      }
    }
    return blockInfo;
  }

  private List<DatanodeDescriptor> getNodes(int ... indexes) {
    List<DatanodeDescriptor> ret = Lists.newArrayList();
    for (int idx : indexes) {
      ret.add(nodes.get(idx));
    }
    return ret;
  }

  private List<DatanodeDescriptor> getNodes(List<DatanodeStorageInfo> storages) {
    List<DatanodeDescriptor> ret = Lists.newArrayList();
    for (DatanodeStorageInfo s : storages) {
      ret.add(s.getDatanodeDescriptor());
    }
    return ret;
  }

  private List<DatanodeStorageInfo> getStorages(int ... indexes) {
    List<DatanodeStorageInfo> ret = Lists.newArrayList();
    for (int idx : indexes) {
      ret.add(storages[idx]);
    }
    return ret;
  }

  private List<DatanodeDescriptor> startDecommission(int ... indexes) {
    List<DatanodeDescriptor> nodes = getNodes(indexes);
    for (DatanodeDescriptor node : nodes) {
      node.startDecommission();
    }
    return nodes;
  }
  
  private BlockInfo addBlockOnNodes(long blockId, List<DatanodeDescriptor> nodes) {
    BlockCollection bc = Mockito.mock(BlockCollection.class);
    Mockito.doReturn((short)3).when(bc).getBlockReplication();
    BlockInfo blockInfo = blockOnNodes(blockId, nodes);

    bm.blocksMap.addBlockCollection(blockInfo, bc);
    return blockInfo;
  }

  private DatanodeStorageInfo[] scheduleSingleReplication(BlockInfo block) {
    // list for priority 1
    List<BlockInfo> list_p1 = new ArrayList<>();
    list_p1.add(block);

    // list of lists for each priority
    List<List<BlockInfo>> list_all = new ArrayList<>();
    list_all.add(new ArrayList<BlockInfo>()); // for priority 0
    list_all.add(list_p1); // for priority 1

    assertEquals("Block not initially pending replication", 0,
        bm.pendingReplications.getNumReplicas(block));
    assertEquals(
        "computeReplicationWork should indicate replication is needed", 1,
        bm.computeReplicationWorkForBlocks(list_all));
    assertTrue("replication is pending after work is computed",
        bm.pendingReplications.getNumReplicas(block) > 0);

    LinkedListMultimap<DatanodeStorageInfo, BlockTargetPair> repls = getAllPendingReplications();
    assertEquals(1, repls.size());
    Entry<DatanodeStorageInfo, BlockTargetPair> repl =
      repls.entries().iterator().next();
        
    DatanodeStorageInfo[] targets = repl.getValue().targets;

    DatanodeStorageInfo[] pipeline = new DatanodeStorageInfo[1 + targets.length];
    pipeline[0] = repl.getKey();
    System.arraycopy(targets, 0, pipeline, 1, targets.length);

    return pipeline;
  }

  private LinkedListMultimap<DatanodeStorageInfo, BlockTargetPair> getAllPendingReplications() {
    LinkedListMultimap<DatanodeStorageInfo, BlockTargetPair> repls =
      LinkedListMultimap.create();
    for (DatanodeDescriptor dn : nodes) {
      List<BlockTargetPair> thisRepls = dn.getReplicationCommand(10);
      if (thisRepls != null) {
        for(DatanodeStorageInfo storage : dn.getStorageInfos()) {
          repls.putAll(storage, thisRepls);
        }
      }
    }
    return repls;
  }

  /**
   * Test that a source node for a highest-priority replication is chosen even if all available
   * source nodes have reached their replication limits.
   */
  @Test
  public void testHighestPriReplSrcChosenDespiteMaxReplLimit() throws Exception {
    bm.maxReplicationStreams = 0;
    bm.replicationStreamsHardLimit = 1;

    long blockId = 42;         // arbitrary
    Block aBlock = new Block(blockId, 0, 0);

    List<DatanodeDescriptor> origNodes = getNodes(0, 1);
    // Add the block to the first node.
    addBlockOnNodes(blockId,origNodes.subList(0,1));

    List<DatanodeDescriptor> cntNodes = new LinkedList<DatanodeDescriptor>();
    List<DatanodeStorageInfo> liveNodes = new LinkedList<DatanodeStorageInfo>();

    assertNotNull("Chooses source node for a highest-priority replication"
        + " even if all available source nodes have reached their replication"
        + " limits below the hard limit.",
        bm.chooseSourceDatanode(
            aBlock,
            cntNodes,
            liveNodes,
            new NumberReplicas(),
            UnderReplicatedBlocks.QUEUE_HIGHEST_PRIORITY));

    assertNull("Does not choose a source node for a less-than-highest-priority"
        + " replication since all available source nodes have reached"
        + " their replication limits.",
        bm.chooseSourceDatanode(
            aBlock,
            cntNodes,
            liveNodes,
            new NumberReplicas(),
            UnderReplicatedBlocks.QUEUE_VERY_UNDER_REPLICATED));

    // Increase the replication count to test replication count > hard limit
    DatanodeStorageInfo targets[] = { origNodes.get(1).getStorageInfos()[0] };
    origNodes.get(0).addBlockToBeReplicated(aBlock, targets);

    assertNull("Does not choose a source node for a highest-priority"
        + " replication when all available nodes exceed the hard limit.",
        bm.chooseSourceDatanode(
            aBlock,
            cntNodes,
            liveNodes,
            new NumberReplicas(),
            UnderReplicatedBlocks.QUEUE_HIGHEST_PRIORITY));
  }

  @Test
  public void testFavorDecomUntilHardLimit() throws Exception {
    bm.maxReplicationStreams = 0;
    bm.replicationStreamsHardLimit = 1;

    long blockId = 42;         // arbitrary
    Block aBlock = new Block(blockId, 0, 0);
    List<DatanodeDescriptor> origNodes = getNodes(0, 1);
    // Add the block to the first node.
    addBlockOnNodes(blockId,origNodes.subList(0,1));
    origNodes.get(0).startDecommission();

    List<DatanodeDescriptor> cntNodes = new LinkedList<DatanodeDescriptor>();
    List<DatanodeStorageInfo> liveNodes = new LinkedList<DatanodeStorageInfo>();

    assertNotNull("Chooses decommissioning source node for a normal replication"
        + " if all available source nodes have reached their replication"
        + " limits below the hard limit.",
        bm.chooseSourceDatanode(
            aBlock,
            cntNodes,
            liveNodes,
            new NumberReplicas(),
            UnderReplicatedBlocks.QUEUE_UNDER_REPLICATED));


    // Increase the replication count to test replication count > hard limit
    DatanodeStorageInfo targets[] = { origNodes.get(1).getStorageInfos()[0] };
    origNodes.get(0).addBlockToBeReplicated(aBlock, targets);

    assertNull("Does not choose a source decommissioning node for a normal"
        + " replication when all available nodes exceed the hard limit.",
        bm.chooseSourceDatanode(
            aBlock,
            cntNodes,
            liveNodes,
            new NumberReplicas(),
            UnderReplicatedBlocks.QUEUE_UNDER_REPLICATED));
  }



  @Test
  public void testSafeModeIBR() throws Exception {
    DatanodeDescriptor node = spy(nodes.get(0));
    DatanodeStorageInfo ds = node.getStorageInfos()[0];
    node.setAlive(true);

    DatanodeRegistration nodeReg =
        new DatanodeRegistration(node, null, null, "");

    // pretend to be in safemode
    doReturn(true).when(fsn).isInStartupSafeMode();
    
    // register new node
    bm.getDatanodeManager().registerDatanode(nodeReg);
    bm.getDatanodeManager().addDatanode(node); // swap in spy    
    assertEquals(node, bm.getDatanodeManager().getDatanode(node));
    assertEquals(0, ds.getBlockReportCount());
    // send block report, should be processed
    reset(node);
    
    bm.processReport(node, new DatanodeStorage(ds.getStorageID()),
        BlockListAsLongs.EMPTY, null);
    assertEquals(1, ds.getBlockReportCount());
    // send block report again, should NOT be processed
    reset(node);
    bm.processReport(node, new DatanodeStorage(ds.getStorageID()),
        BlockListAsLongs.EMPTY, null);
    assertEquals(1, ds.getBlockReportCount());

    // re-register as if node restarted, should update existing node
    bm.getDatanodeManager().removeDatanode(node);
    reset(node);
    bm.getDatanodeManager().registerDatanode(nodeReg);
    verify(node).updateRegInfo(nodeReg);
    // send block report, should be processed after restart
    reset(node);
    bm.processReport(node, new DatanodeStorage(ds.getStorageID()),
                     BlockListAsLongs.EMPTY, null);
    // Reinitialize as registration with empty storage list pruned
    // node.storageMap.
    ds = node.getStorageInfos()[0];
    assertEquals(1, ds.getBlockReportCount());
  }
  
  @Test
  public void testSafeModeIBRAfterIncremental() throws Exception {
    DatanodeDescriptor node = spy(nodes.get(0));
    DatanodeStorageInfo ds = node.getStorageInfos()[0];

    node.setAlive(true);

    DatanodeRegistration nodeReg =
        new DatanodeRegistration(node, null, null, "");

    // pretend to be in safemode
    doReturn(true).when(fsn).isInStartupSafeMode();

    // register new node
    bm.getDatanodeManager().registerDatanode(nodeReg);
    bm.getDatanodeManager().addDatanode(node); // swap in spy    
    assertEquals(node, bm.getDatanodeManager().getDatanode(node));
    assertEquals(0, ds.getBlockReportCount());
    // send block report while pretending to already have blocks
    reset(node);
    doReturn(1).when(node).numBlocks();
    bm.processReport(node, new DatanodeStorage(ds.getStorageID()),
        BlockListAsLongs.EMPTY, null);
    assertEquals(1, ds.getBlockReportCount());
  }

  /**
   * test when NN starts and in same mode, it receives an incremental blockReport
   * firstly. Then receives first full block report.
   */
  @Test
  public void testSafeModeIBRBeforeFirstFullBR() throws Exception {
    // pretend to be in safemode
    doReturn(true).when(fsn).isInStartupSafeMode();

    DatanodeDescriptor node = nodes.get(0);
    DatanodeStorageInfo ds = node.getStorageInfos()[0];
    node.setAlive(true);
    DatanodeRegistration nodeReg =  new DatanodeRegistration(node, null, null, "");

    // register new node
    bm.getDatanodeManager().registerDatanode(nodeReg);
    bm.getDatanodeManager().addDatanode(node);
    assertEquals(node, bm.getDatanodeManager().getDatanode(node));
    assertEquals(0, ds.getBlockReportCount());
    // Build a incremental report
    List<ReceivedDeletedBlockInfo> rdbiList = new ArrayList<>();
    // Build a full report
    BlockListAsLongs.Builder builder = BlockListAsLongs.builder();

    // blk_42 is finalized.
    long receivedBlockId = 42;  // arbitrary
    BlockInfo receivedBlock = addBlockToBM(receivedBlockId);
    rdbiList.add(new ReceivedDeletedBlockInfo(new Block(receivedBlock),
        ReceivedDeletedBlockInfo.BlockStatus.RECEIVED_BLOCK, null));
    builder.add(new FinalizedReplica(receivedBlock, null, null));

    // blk_43 is under construction.
    long receivingBlockId = 43;
    BlockInfo receivingBlock = addUcBlockToBM(receivingBlockId);
    rdbiList.add(new ReceivedDeletedBlockInfo(new Block(receivingBlock),
        ReceivedDeletedBlockInfo.BlockStatus.RECEIVING_BLOCK, null));
    builder.add(new ReplicaBeingWritten(receivingBlock, null, null, null));

    // blk_44 has 2 records in IBR. It's finalized. So full BR has 1 record.
    long receivingReceivedBlockId = 44;
    BlockInfo receivingReceivedBlock = addBlockToBM(receivingReceivedBlockId);
    rdbiList.add(new ReceivedDeletedBlockInfo(new Block(receivingReceivedBlock),
        ReceivedDeletedBlockInfo.BlockStatus.RECEIVING_BLOCK, null));
    rdbiList.add(new ReceivedDeletedBlockInfo(new Block(receivingReceivedBlock),
        ReceivedDeletedBlockInfo.BlockStatus.RECEIVED_BLOCK, null));
    builder.add(new FinalizedReplica(receivingReceivedBlock, null, null));

    // blk_45 is not in full BR, because it's deleted.
    long ReceivedDeletedBlockId = 45;
    rdbiList.add(new ReceivedDeletedBlockInfo(
        new Block(ReceivedDeletedBlockId),
        ReceivedDeletedBlockInfo.BlockStatus.RECEIVED_BLOCK, null));
    rdbiList.add(new ReceivedDeletedBlockInfo(
        new Block(ReceivedDeletedBlockId),
        ReceivedDeletedBlockInfo.BlockStatus.DELETED_BLOCK, null));

    // blk_46 exists in DN for a long time, so it's in full BR, but not in IBR.
    long existedBlockId = 46;
    BlockInfo existedBlock = addBlockToBM(existedBlockId);
    builder.add(new FinalizedReplica(existedBlock, null, null));

    // process IBR and full BR
    StorageReceivedDeletedBlocks srdb =
        new StorageReceivedDeletedBlocks(new DatanodeStorage(ds.getStorageID()),
            rdbiList.toArray(new ReceivedDeletedBlockInfo[rdbiList.size()]));
    bm.processIncrementalBlockReport(node, srdb);
    // Make sure it's the first full report
    assertEquals(0, ds.getBlockReportCount());
    bm.processReport(node, new DatanodeStorage(ds.getStorageID()),
        builder.build(),
        new BlockReportContext(1, 0, System.nanoTime(), 0, true));
    assertEquals(1, ds.getBlockReportCount());

    // verify the storage info is correct
    assertTrue(bm.getStoredBlock(new Block(receivedBlockId)).findStorageInfo
        (ds) >= 0);
    assertTrue(((BlockInfoUnderConstruction) bm.
        getStoredBlock(new Block(receivingBlockId))).getNumExpectedLocations() > 0);
    assertTrue(bm.getStoredBlock(new Block(receivingReceivedBlockId))
        .findStorageInfo(ds) >= 0);
    assertNull(bm.getStoredBlock(new Block(ReceivedDeletedBlockId)));
    assertTrue(bm.getStoredBlock(new Block(existedBlock)).findStorageInfo
        (ds) >= 0);
  }

  @Test
  public void testFullBR() throws Exception {
    doReturn(true).when(fsn).isRunning();

    DatanodeDescriptor node = nodes.get(0);
    DatanodeStorageInfo ds = node.getStorageInfos()[0];
    node.setAlive(true);
    DatanodeRegistration nodeReg =  new DatanodeRegistration(node, null, null, "");

    // register new node
    bm.getDatanodeManager().registerDatanode(nodeReg);
    bm.getDatanodeManager().addDatanode(node);
    assertEquals(node, bm.getDatanodeManager().getDatanode(node));
    assertEquals(0, ds.getBlockReportCount());

    ArrayList<BlockInfo> blocks = new ArrayList<>();
    for (int id = 24; id > 0; id--) {
      blocks.add(addBlockToBM(id));
    }

    // Make sure it's the first full report
    assertEquals(0, ds.getBlockReportCount());
    bm.processReport(node, new DatanodeStorage(ds.getStorageID()),
        generateReport(blocks),
        new BlockReportContext(1, 0, System.nanoTime(), 0, false)
        );
    assertEquals(1, ds.getBlockReportCount());
    // verify the storage info is correct
    for (BlockInfo block : blocks) {
      assertTrue(bm.getStoredBlock(block).findStorageInfo(ds) >= 0);
    }

    // Send unsorted report
    bm.processReport(node, new DatanodeStorage(ds.getStorageID()),
        generateReport(blocks),
        new BlockReportContext(1, 0, System.nanoTime(), 0, false));
    assertEquals(2, ds.getBlockReportCount());
    // verify the storage info is correct
    for (BlockInfo block : blocks) {
      assertTrue(bm.getStoredBlock(block).findStorageInfo(ds) >= 0);
    }

    // Sort list and send a sorted report
    Collections.sort(blocks);
    bm.processReport(node, new DatanodeStorage(ds.getStorageID()),
        generateReport(blocks),
        new BlockReportContext(1, 0, System.nanoTime(), 0, true));
    assertEquals(3, ds.getBlockReportCount());
    // verify the storage info is correct
    for (BlockInfo block : blocks) {
      assertTrue(bm.getStoredBlock(block).findStorageInfo(ds) >= 0);
    }
  }

  private BlockListAsLongs generateReport(List<BlockInfo> blocks) {
    BlockListAsLongs.Builder builder = BlockListAsLongs.builder();
    for (BlockInfo block : blocks) {
      builder.add(new FinalizedReplica(block, null, null));
    }
    return builder.build();
  }

  @Test
  public void testUCBlockNotConsideredMissing() throws Exception {
    DatanodeDescriptor node = nodes.get(0);
    DatanodeStorageInfo ds = node.getStorageInfos()[0];
    node.setAlive(true);
    DatanodeRegistration nodeReg =
        new DatanodeRegistration(node, null, null, "");

    // register new node
    bm.getDatanodeManager().registerDatanode(nodeReg);
    bm.getDatanodeManager().addDatanode(node);

    // Build an incremental report
    List<ReceivedDeletedBlockInfo> rdbiList = new ArrayList<>();

    // blk_42 is under construction, finalizes on one node and is
    // immediately deleted on same node
    long blockId = 42;  // arbitrary
    BlockInfo receivedBlock = addUcBlockToBM(blockId);

    rdbiList.add(new ReceivedDeletedBlockInfo(new Block(receivedBlock),
        ReceivedDeletedBlockInfo.BlockStatus.RECEIVED_BLOCK, null));
    rdbiList.add(new ReceivedDeletedBlockInfo(new Block(blockId),
        ReceivedDeletedBlockInfo.BlockStatus.DELETED_BLOCK, null));

    // process IBR
    StorageReceivedDeletedBlocks srdb =
        new StorageReceivedDeletedBlocks(new DatanodeStorage(ds.getStorageID()),
            rdbiList.toArray(new ReceivedDeletedBlockInfo[rdbiList.size()]));
    bm.processIncrementalBlockReport(node, srdb);
    // Needed replications should still be 0.
    assertEquals("UC block was incorrectly added to needed Replications", 0,
        bm.neededReplications.size());
  }

  private BlockInfo addBlockToBM(long blkId) {
    Block block = new Block(blkId);
    BlockInfo blockInfo =
        new BlockInfo(block, (short) 3);
    BlockCollection bc = Mockito.mock(BlockCollection.class);
    Mockito.doReturn((short) 3).when(bc).getBlockReplication();
    bm.blocksMap.addBlockCollection(blockInfo, bc);
    return blockInfo;
  }

  private BlockInfo addUcBlockToBM(long blkId) {
    Block block = new Block(blkId);
    BlockInfoUnderConstruction blockInfo =
        new BlockInfoUnderConstruction(block, (short) 3);
    BlockCollection bc = Mockito.mock(BlockCollection.class);
    Mockito.doReturn((short) 3).when(bc).getBlockReplication();
    bm.blocksMap.addBlockCollection(blockInfo, bc);
    return blockInfo;
  }
  
  /**
   * Tests that a namenode doesn't choose a datanode with full disks to 
   * store blocks.
   * @throws Exception
   */
  @Test
  public void testStorageWithRemainingCapacity() throws Exception {
    final Configuration conf = new HdfsConfiguration();
    final MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf).build();
    FileSystem fs = FileSystem.get(conf);
    Path file1 = null;
    try {
      cluster.waitActive();
      final FSNamesystem namesystem = cluster.getNamesystem();
      final String poolId = namesystem.getBlockPoolId();
      final DatanodeRegistration nodeReg =
        InternalDataNodeTestUtils.getDNRegistrationForBP(cluster.getDataNodes().
        		get(0), poolId);
      final DatanodeDescriptor dd = NameNodeAdapter.getDatanode(namesystem,
    		  nodeReg);
      // By default, MiniDFSCluster will create 1 datanode with 2 storages.
      // Assigning 64k for remaining storage capacity and will 
      //create a file with 100k.
      for(DatanodeStorageInfo storage:  dd.getStorageInfos()) { 
    	  storage.setUtilizationForTesting(65536, 0, 65536, 0);
      }
      //sum of the remaining capacity of both the storages
      dd.setRemaining(131072);
      file1 = new Path("testRemainingStorage.dat");
      try {
        DFSTestUtil.createFile(fs, file1, 102400, 102400, 102400, (short)1,
        		0x1BAD5EED);
      }
      catch (RemoteException re) {
    	  GenericTestUtils.assertExceptionContains("nodes instead of "
    	  		+ "minReplication", re);
      }
    }
    finally {
      // Clean up
      assertTrue(fs.exists(file1));
      fs.delete(file1, true);
      assertTrue(!fs.exists(file1));
      cluster.shutdown();
    }
  }

  @Test
  public void testUseDelHint() {
    DatanodeStorageInfo delHint = new DatanodeStorageInfo(
        DFSTestUtil.getLocalDatanodeDescriptor(), new DatanodeStorage("id"));
    List<DatanodeStorageInfo> moreThan1Racks = Arrays.asList(delHint);
    List<StorageType> excessTypes = new ArrayList<StorageType>();

    excessTypes.add(StorageType.DEFAULT);
    Assert.assertTrue(BlockPlacementPolicyDefault.useDelHint(true, delHint,
        null, moreThan1Racks, excessTypes));
    excessTypes.remove(0);
    excessTypes.add(StorageType.SSD);
    Assert.assertFalse(BlockPlacementPolicyDefault.useDelHint(true, delHint,
        null, moreThan1Racks, excessTypes));
  }

  /**
   * {@link BlockManager#isPlacementPolicySatisfied(Block)} should return false
   * if all the replicas are on the same rack and shouldn't be dependent on
   * CommonConfigurationKeysPublic.NET_TOPOLOGY_SCRIPT_FILE_NAME_KEY
   * @throws Exception
   */
  @Test
  public void testAllReplicasOnSameRack() throws Exception {
    Configuration conf = new HdfsConfiguration();
    conf.unset(DFSConfigKeys.NET_TOPOLOGY_SCRIPT_FILE_NAME_KEY);
    fsn = Mockito.mock(FSNamesystem.class);
    Mockito.doReturn(true).when(fsn).hasWriteLock();
    Mockito.doReturn(true).when(fsn).hasReadLock();
    bm = new BlockManager(fsn, fsn, conf);
    // Add nodes on two racks
    addNodes(nodes);
    // Added a new block in blocksMap and all the replicas are on the same rack
    BlockInfo blockInfo = addBlockOnNodes(1, rackA);
    // Since the network toppolgy is multi-rack, the blockHasEnoughRacks
    // should return false.
    assertFalse("Replicas for block is not stored on enough racks",
        bm.isPlacementPolicySatisfied(blockInfo));
  }

  @Test
  public void testBlockReportQueueing() throws Exception {
    Configuration conf = new HdfsConfiguration();
    final MiniDFSCluster cluster = new MiniDFSCluster.Builder(conf).build();
    try {
      cluster.waitActive();
      final FSNamesystem fsn = cluster.getNamesystem();
      final BlockManager bm = fsn.getBlockManager();
      final ExecutorService executor = Executors.newCachedThreadPool();

      final CyclicBarrier startBarrier = new CyclicBarrier(2);
      final CountDownLatch endLatch = new CountDownLatch(3);
      final CountDownLatch doneLatch = new CountDownLatch(1);

      // create a task intended to block while processing, thus causing
      // the queue to backup.  simulates how a full BR is processed.
      FutureTask<?> blockingOp = new FutureTask<Void>(
          new Callable<Void>(){
            @Override
            public Void call() throws IOException {
              bm.runBlockOp(new Callable<Void>() {
                @Override
                public Void call()
                    throws InterruptedException, BrokenBarrierException {
                  // use a barrier to control the blocking.
                  startBarrier.await();
                  endLatch.countDown();
                  return null;
                }
              });
              // signal that runBlockOp returned
              doneLatch.countDown();
              return null;
            }
          });

      // create an async task.  simulates how an IBR is processed.
      Callable<?> asyncOp = new Callable<Void>(){
        @Override
        public Void call() throws IOException {
          bm.enqueueBlockOp(new Runnable() {
            @Override
            public void run() {
              // use the latch to signal if the op has run.
              endLatch.countDown();
            }
          });
          return null;
        }
      };

      // calling get forces its execution so we can test if it's blocked.
      Future<?> blockedFuture = executor.submit(blockingOp);
      boolean isBlocked = false;
      try {
        // wait 1s for the future to block.  it should run instantaneously.
        blockedFuture.get(1, TimeUnit.SECONDS);
      } catch (TimeoutException te) {
        isBlocked = true;
      }
      assertTrue(isBlocked);

      // should effectively return immediately since calls are queued.
      // however they should be backed up in the queue behind the blocking
      // operation.
      executor.submit(asyncOp).get(1, TimeUnit.SECONDS);
      executor.submit(asyncOp).get(1, TimeUnit.SECONDS);

      // check the async calls are queued, and first is still blocked.
      assertEquals(2, bm.getBlockOpQueueLength());
      assertFalse(blockedFuture.isDone());

      // unblock the queue, wait for last op to complete, check the blocked
      // call has returned
      startBarrier.await(1, TimeUnit.SECONDS);
      assertTrue(endLatch.await(1, TimeUnit.SECONDS));
      assertEquals(0, bm.getBlockOpQueueLength());
      assertTrue(doneLatch.await(1, TimeUnit.SECONDS));
    } finally {
      cluster.shutdown();
    }
  }

  // spam the block manager with IBRs to verify queuing is occurring.
  @Test
  public void testAsyncIBR() throws Exception {
    Logger.getRootLogger().setLevel(Level.WARN);

    // will create files with many small blocks.
    final int blkSize = 4*1024;
    final int fileSize = blkSize * 100;
    final byte[] buf = new byte[2*blkSize];
    final int numWriters = 4;
    final int repl = 3;

    final CyclicBarrier barrier = new CyclicBarrier(numWriters);
    final CountDownLatch writeLatch = new CountDownLatch(numWriters);
    final AtomicBoolean failure = new AtomicBoolean();

    final Configuration conf = new HdfsConfiguration();
    conf.getLong(DFSConfigKeys.DFS_NAMENODE_MIN_BLOCK_SIZE_KEY, blkSize);
    final MiniDFSCluster cluster =
        new MiniDFSCluster.Builder(conf).numDataNodes(8).build();

    try {
      cluster.waitActive();
      // create multiple writer threads to create a file with many blocks.
      // will test that concurrent writing causes IBR batching in the NN
      Thread[] writers = new Thread[numWriters];
      for (int i=0; i < writers.length; i++) {
        final Path p = new Path("/writer"+i);
        writers[i] = new Thread(new Runnable() {
          @Override
          public void run() {
            try {
              FileSystem fs = cluster.getFileSystem();
              FSDataOutputStream os =
                  fs.create(p, true, buf.length, (short)repl, blkSize);
              // align writers for maximum chance of IBR batching.
              barrier.await();
              int remaining = fileSize;
              while (remaining > 0) {
                os.write(buf);
                remaining -= buf.length;
              }
              os.close();
            } catch (Exception e) {
              e.printStackTrace();
              failure.set(true);
            }
            // let main thread know we are done.
            writeLatch.countDown();
          }
        });
        writers[i].start();
      }

      // when and how many IBRs are queued is indeterminate, so just watch
      // the metrics and verify something was queued at during execution.
      boolean sawQueued = false;
      while (!writeLatch.await(10, TimeUnit.MILLISECONDS)) {
        assertFalse(failure.get());
        MetricsRecordBuilder rb = getMetrics("NameNodeActivity");
        long queued = MetricsAsserts.getIntGauge("BlockOpsQueued", rb);
        sawQueued |= (queued > 0);
      }
      assertFalse(failure.get());
      assertTrue(sawQueued);

      // verify that batching of the IBRs occurred.
      MetricsRecordBuilder rb = getMetrics("NameNodeActivity");
      long batched = MetricsAsserts.getLongCounter("BlockOpsBatched", rb);
      assertTrue(batched > 0);
    } finally {
      cluster.shutdown();
    }
  }

  @Test(timeout = 60000)
  public void testBlockManagerMachinesArray() throws Exception {
    final Configuration conf = new HdfsConfiguration();
    final MiniDFSCluster cluster =
            new MiniDFSCluster.Builder(conf).numDataNodes(4).build();
    cluster.waitActive();
    BlockManager blockManager = cluster.getNamesystem().getBlockManager();
    FileSystem fs = cluster.getFileSystem();
    final Path filePath = new Path("/tmp.txt");
    final long fileLen = 1L;
    DFSTestUtil.createFile(fs, filePath, fileLen, (short) 3, 1L);
    DFSTestUtil.waitForReplication((DistributedFileSystem)fs,
        filePath, (short) 3, 60000);
    ArrayList<DataNode> datanodes = cluster.getDataNodes();
    assertEquals(datanodes.size(), 4);
    FSNamesystem ns = cluster.getNamesystem();
    // get the block
    final String bpid = cluster.getNamesystem().getBlockPoolId();
    File storageDir = cluster.getInstanceStorageDir(0, 0);
    File dataDir = MiniDFSCluster.getFinalizedDir(storageDir, bpid);
    assertTrue("Data directory does not exist", dataDir.exists());
    BlockInfo blockInfo = blockManager.blocksMap.getBlocks().iterator().next();
    ExtendedBlock blk = new ExtendedBlock(bpid, blockInfo.getBlockId(),
            blockInfo.getNumBytes(), blockInfo.getGenerationStamp());
    DatanodeDescriptor failedStorageDataNode =
            blockManager.getStoredBlock(blockInfo).getDatanode(0);
    DatanodeDescriptor corruptStorageDataNode =
            blockManager.getStoredBlock(blockInfo).getDatanode(1);

    ArrayList<StorageReport> reports = new ArrayList<StorageReport>();
    for(int i=0; i<failedStorageDataNode.getStorageInfos().length; i++) {
      DatanodeStorageInfo storageInfo = failedStorageDataNode
              .getStorageInfos()[i];
      DatanodeStorage dns = new DatanodeStorage(
              failedStorageDataNode.getStorageInfos()[i].getStorageID(),
              DatanodeStorage.State.FAILED,
              failedStorageDataNode.getStorageInfos()[i].getStorageType());
      while(storageInfo.getBlockIterator().hasNext()) {
        BlockInfo blockInfo1 = storageInfo.getBlockIterator().next();
        if(blockInfo1.equals(blockInfo)) {
          StorageReport report = new StorageReport(
                  dns, true, storageInfo.getCapacity(),
                  storageInfo.getDfsUsed(), storageInfo.getRemaining(),
                  storageInfo.getBlockPoolUsed(), 0L);
          reports.add(report);
          break;
        }
      }
    }
    failedStorageDataNode.updateHeartbeat(reports.toArray(StorageReport
            .EMPTY_ARRAY), 0L, 0L, 0, 0, null);
    ns.writeLock();
    DatanodeStorageInfo corruptStorageInfo= null;
    for(int i=0; i<corruptStorageDataNode.getStorageInfos().length; i++) {
      corruptStorageInfo = corruptStorageDataNode.getStorageInfos()[i];
      while(corruptStorageInfo.getBlockIterator().hasNext()) {
        BlockInfo blockInfo1 = corruptStorageInfo.getBlockIterator().next();
        if (blockInfo1.equals(blockInfo)) {
          break;
        }
      }
    }
    blockManager.findAndMarkBlockAsCorrupt(blk, corruptStorageDataNode,
            corruptStorageInfo.getStorageID(),
            CorruptReplicasMap.Reason.ANY.toString());
    ns.writeUnlock();
    BlockInfo[] blockInfos = new BlockInfo[] {blockInfo};
    ns.readLock();
    LocatedBlocks locatedBlocks =
            blockManager.createLocatedBlocks(blockInfos, 3L, false, 0L, 3L,
                    false, false, null);
    assertTrue("Located Blocks should exclude corrupt" +
                    "replicas and failed storages",
            locatedBlocks.getLocatedBlocks().size() == 1);
    ns.readUnlock();
  }
}
