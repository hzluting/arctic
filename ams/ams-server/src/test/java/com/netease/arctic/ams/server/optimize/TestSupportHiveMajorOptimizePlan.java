/*
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

package com.netease.arctic.ams.server.optimize;

import com.netease.arctic.ams.api.OptimizeType;
import com.netease.arctic.ams.server.model.BaseOptimizeTask;
import com.netease.arctic.ams.server.model.TableOptimizeRuntime;
import com.netease.arctic.ams.server.util.DataFileInfoUtils;
import com.netease.arctic.data.DataTreeNode;
import com.netease.arctic.table.TableProperties;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.Snapshot;
import org.apache.iceberg.util.Pair;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class TestSupportHiveMajorOptimizePlan extends TestSupportHiveBase {
  @Test
  public void testKeyedTableMajorOptimizeSupportHive() throws IOException {
    Pair<Snapshot, List<DataFile>> insertBaseResult = insertTableBaseDataFiles(testKeyedHiveTable, 1L);
    List<DataFile> baseDataFiles = insertBaseResult.second();
    baseDataFilesInfo.addAll(baseDataFiles.stream()
        .map(dataFile -> DataFileInfoUtils.convertToDatafileInfo(dataFile, insertBaseResult.first(), testKeyedHiveTable))
        .collect(Collectors.toList()));

    SupportHiveMajorOptimizePlan supportHiveMajorOptimizePlan = new SupportHiveMajorOptimizePlan(testKeyedHiveTable,
        new TableOptimizeRuntime(testKeyedHiveTable.id()), baseDataFilesInfo, posDeleteFilesInfo,
        new HashMap<>(), 1, System.currentTimeMillis(), snapshotId -> true);
    List<BaseOptimizeTask> tasks = supportHiveMajorOptimizePlan.plan();
    Assert.assertEquals(4, tasks.size());
    Assert.assertEquals(OptimizeType.Major, tasks.get(0).getTaskId().getType());
    Assert.assertEquals(10, tasks.get(0).getBaseFiles().size());
    Assert.assertEquals(0, tasks.get(0).getPosDeleteFiles().size());
    Assert.assertEquals(0, tasks.get(0).getInsertFileCnt());
    Assert.assertEquals(0, tasks.get(0).getDeleteFileCnt());
  }

  @Test
  public void testKeyedTableFullMajorOptimizeSupportHive() throws IOException {
    testKeyedHiveTable.updateProperties()
        .set(TableProperties.FULL_OPTIMIZE_TRIGGER_MAX_INTERVAL, "86400000")
        .commit();
    Pair<Snapshot, List<DataFile>> insertBaseResult = insertTableBaseDataFiles(testKeyedHiveTable, 1L);
    List<DataFile> baseDataFiles = insertBaseResult.second();
    baseDataFilesInfo.addAll(baseDataFiles.stream()
        .map(dataFile ->
            DataFileInfoUtils.convertToDatafileInfo(dataFile, insertBaseResult.first(), testKeyedHiveTable))
        .collect(Collectors.toList()));

    Set<DataTreeNode> targetNodes = baseDataFilesInfo.stream()
        .map(dataFileInfo -> DataTreeNode.of(dataFileInfo.getMask(), dataFileInfo.getIndex())).collect(Collectors.toSet());
    Pair<Snapshot, List<DeleteFile>> deleteResult =
        insertBasePosDeleteFiles(testKeyedHiveTable, 2L, baseDataFiles, targetNodes);
    List<DeleteFile> deleteFiles = deleteResult.second();
    posDeleteFilesInfo.addAll(deleteFiles.stream()
        .map(deleteFile ->
            DataFileInfoUtils.convertToDatafileInfo(deleteFile, deleteResult.first(), testKeyedHiveTable.asKeyedTable()))
        .collect(Collectors.toList()));

    SupportHiveFullOptimizePlan supportHiveFullOptimizePlan = new SupportHiveFullOptimizePlan(testKeyedHiveTable,
        new TableOptimizeRuntime(testKeyedHiveTable.id()), baseDataFilesInfo, posDeleteFilesInfo,
        new HashMap<>(), 1, System.currentTimeMillis(), snapshotId -> true);
    List<BaseOptimizeTask> tasks = supportHiveFullOptimizePlan.plan();
    Assert.assertEquals(4, tasks.size());
    Assert.assertEquals(OptimizeType.FullMajor, tasks.get(0).getTaskId().getType());
    Assert.assertEquals(10, tasks.get(0).getBaseFiles().size());
    Assert.assertEquals(1, tasks.get(0).getPosDeleteFiles().size());
    Assert.assertEquals(0, tasks.get(0).getInsertFileCnt());
    Assert.assertEquals(0, tasks.get(0).getDeleteFileCnt());
  }

  @Test
  public void testKeyedTableFullMajorOptimizeSupportHiveNotAllHavePosDelete() throws IOException {
    testKeyedHiveTable.updateProperties()
        .set(TableProperties.FULL_OPTIMIZE_TRIGGER_MAX_INTERVAL, "86400000")
        .commit();
    Pair<Snapshot, List<DataFile>> insertBaseResult = insertTableBaseDataFiles(testKeyedHiveTable, 1L);
    List<DataFile> baseDataFiles = insertBaseResult.second();
    baseDataFilesInfo.addAll(baseDataFiles.stream()
        .map(dataFile ->
            DataFileInfoUtils.convertToDatafileInfo(dataFile, insertBaseResult.first(), testKeyedHiveTable))
        .collect(Collectors.toList()));

    Set<DataTreeNode> targetNodes = baseDataFilesInfo.stream()
        .map(dataFileInfo -> DataTreeNode.of(dataFileInfo.getMask(), dataFileInfo.getIndex())).collect(Collectors.toSet());
    targetNodes.remove(DataTreeNode.ofId(4));
    Pair<Snapshot, List<DeleteFile>> deleteResult =
        insertBasePosDeleteFiles(testKeyedHiveTable, 2L, baseDataFiles, targetNodes);
    List<DeleteFile> deleteFiles = deleteResult.second();
    posDeleteFilesInfo.addAll(deleteFiles.stream()
        .map(deleteFile ->
            DataFileInfoUtils.convertToDatafileInfo(deleteFile, deleteResult.first(), testKeyedHiveTable.asKeyedTable()))
        .collect(Collectors.toList()));

    SupportHiveFullOptimizePlan supportHiveFullOptimizePlan = new SupportHiveFullOptimizePlan(testKeyedHiveTable,
        new TableOptimizeRuntime(testKeyedHiveTable.id()), baseDataFilesInfo, posDeleteFilesInfo,
        new HashMap<>(), 1, System.currentTimeMillis(), snapshotId -> true);
    List<BaseOptimizeTask> tasks = supportHiveFullOptimizePlan.plan();
    Assert.assertEquals(4, tasks.size());
    Assert.assertEquals(OptimizeType.FullMajor, tasks.get(0).getTaskId().getType());
    Assert.assertEquals(10, tasks.get(0).getBaseFiles().size());
    Assert.assertEquals(0, tasks.get(0).getPosDeleteFiles().size());
    Assert.assertEquals(0, tasks.get(0).getInsertFileCnt());
    Assert.assertEquals(0, tasks.get(0).getDeleteFileCnt());
  }

  @Test
  public void testUnKeyedTableMajorOptimizeSupportHive() throws IOException {
    Pair<Snapshot, List<DataFile>> insertBaseResult = insertTableBaseDataFiles(testHiveTable, null);
    List<DataFile> baseDataFiles = insertBaseResult.second();
    baseDataFilesInfo.addAll(baseDataFiles.stream()
        .map(dataFile -> DataFileInfoUtils.convertToDatafileInfo(dataFile, insertBaseResult.first(), testHiveTable))
        .collect(Collectors.toList()));

    SupportHiveMajorOptimizePlan supportHiveMajorOptimizePlan = new SupportHiveMajorOptimizePlan(testHiveTable,
        new TableOptimizeRuntime(testHiveTable.id()), baseDataFilesInfo, posDeleteFilesInfo,
        new HashMap<>(), 1, System.currentTimeMillis(), snapshotId -> true);
    List<BaseOptimizeTask> tasks = supportHiveMajorOptimizePlan.plan();
    Assert.assertEquals(1, tasks.size());
    Assert.assertEquals(OptimizeType.Major, tasks.get(0).getTaskId().getType());
    Assert.assertEquals(10, tasks.get(0).getBaseFiles().size());
    Assert.assertEquals(0, tasks.get(0).getPosDeleteFiles().size());
    Assert.assertEquals(0, tasks.get(0).getInsertFileCnt());
    Assert.assertEquals(0, tasks.get(0).getDeleteFileCnt());
  }

  @Test
  public void testUnKeyedTableFullMajorOptimizeSupportHive() throws IOException {
    testHiveTable.updateProperties()
        .set(TableProperties.FULL_OPTIMIZE_TRIGGER_MAX_INTERVAL, "86400000")
        .commit();
    Pair<Snapshot, List<DataFile>> insertBaseResult = insertTableBaseDataFiles(testHiveTable, null);
    List<DataFile> baseDataFiles = insertBaseResult.second();
    baseDataFilesInfo.addAll(baseDataFiles.stream()
        .map(dataFile -> DataFileInfoUtils.convertToDatafileInfo(dataFile, insertBaseResult.first(), testHiveTable))
        .collect(Collectors.toList()));

    SupportHiveFullOptimizePlan supportHiveMajorOptimizePlan = new SupportHiveFullOptimizePlan(testHiveTable,
        new TableOptimizeRuntime(testHiveTable.id()), baseDataFilesInfo, posDeleteFilesInfo,
        new HashMap<>(), 1, System.currentTimeMillis(), snapshotId -> true);
    List<BaseOptimizeTask> tasks = supportHiveMajorOptimizePlan.plan();
    Assert.assertEquals(1, tasks.size());
    Assert.assertEquals(OptimizeType.FullMajor, tasks.get(0).getTaskId().getType());
    Assert.assertEquals(10, tasks.get(0).getBaseFiles().size());
    Assert.assertEquals(0, tasks.get(0).getPosDeleteFiles().size());
    Assert.assertEquals(0, tasks.get(0).getInsertFileCnt());
    Assert.assertEquals(0, tasks.get(0).getDeleteFileCnt());
  }

  @Test
  public void testNoPartitionTableMajorOptimizeSupportHive() throws IOException {
    Pair<Snapshot, List<DataFile>> insertBaseResult = insertTableBaseDataFiles(testUnPartitionKeyedHiveTable, 1L);
    List<DataFile> baseDataFiles = insertBaseResult.second();
    baseDataFilesInfo.addAll(baseDataFiles.stream()
        .map(dataFile ->
            DataFileInfoUtils.convertToDatafileInfo(dataFile, insertBaseResult.first(), testUnPartitionKeyedHiveTable))
        .collect(Collectors.toList()));

    SupportHiveMajorOptimizePlan supportHiveMajorOptimizePlan = new SupportHiveMajorOptimizePlan(testUnPartitionKeyedHiveTable,
        new TableOptimizeRuntime(testUnPartitionKeyedHiveTable.id()), baseDataFilesInfo, posDeleteFilesInfo,
        new HashMap<>(), 1, System.currentTimeMillis(), snapshotId -> true);
    List<BaseOptimizeTask> tasks = supportHiveMajorOptimizePlan.plan();
    Assert.assertEquals(4, tasks.size());
    Assert.assertEquals(OptimizeType.Major, tasks.get(0).getTaskId().getType());
    Assert.assertEquals(10, tasks.get(0).getBaseFiles().size());
    Assert.assertEquals(0, tasks.get(0).getPosDeleteFiles().size());
    Assert.assertEquals(0, tasks.get(0).getInsertFileCnt());
    Assert.assertEquals(0, tasks.get(0).getDeleteFileCnt());
  }

  @Test
  public void testNoPartitionTableFullMajorOptimizeSupportHive() throws IOException {
    testUnPartitionKeyedHiveTable.updateProperties()
        .set(TableProperties.FULL_OPTIMIZE_TRIGGER_MAX_INTERVAL, "86400000")
        .commit();
    Pair<Snapshot, List<DataFile>> insertBaseResult = insertTableBaseDataFiles(testUnPartitionKeyedHiveTable, 1L);
    List<DataFile> baseDataFiles = insertBaseResult.second();
    baseDataFilesInfo.addAll(baseDataFiles.stream()
        .map(dataFile ->
            DataFileInfoUtils.convertToDatafileInfo(dataFile, insertBaseResult.first(), testUnPartitionKeyedHiveTable))
        .collect(Collectors.toList()));

    SupportHiveFullOptimizePlan supportHiveMajorOptimizePlan = new SupportHiveFullOptimizePlan(testUnPartitionKeyedHiveTable,
        new TableOptimizeRuntime(testUnPartitionKeyedHiveTable.id()), baseDataFilesInfo, posDeleteFilesInfo,
        new HashMap<>(), 1, System.currentTimeMillis(), snapshotId -> true);
    List<BaseOptimizeTask> tasks = supportHiveMajorOptimizePlan.plan();
    Assert.assertEquals(4, tasks.size());
    Assert.assertEquals(OptimizeType.FullMajor, tasks.get(0).getTaskId().getType());
    Assert.assertEquals(10, tasks.get(0).getBaseFiles().size());
    Assert.assertEquals(0, tasks.get(0).getPosDeleteFiles().size());
    Assert.assertEquals(0, tasks.get(0).getInsertFileCnt());
    Assert.assertEquals(0, tasks.get(0).getDeleteFileCnt());
  }
}
