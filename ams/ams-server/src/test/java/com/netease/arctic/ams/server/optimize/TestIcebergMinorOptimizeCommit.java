package com.netease.arctic.ams.server.optimize;

import com.netease.arctic.ams.api.OptimizeStatus;
import com.netease.arctic.ams.server.model.BaseOptimizeTask;
import com.netease.arctic.ams.server.model.BaseOptimizeTaskRuntime;
import com.netease.arctic.ams.server.model.TableOptimizeRuntime;
import com.netease.arctic.ams.server.utils.JDBCSqlSessionFactoryProvider;
import com.netease.arctic.utils.SerializationUtil;
import org.apache.commons.collections.IteratorUtils;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.FileScanTask;
import org.junit.Assert;
import org.junit.Test;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@PrepareForTest({
    JDBCSqlSessionFactoryProvider.class
})
@PowerMockIgnore({"org.apache.logging.log4j.*", "javax.management.*", "org.apache.http.conn.ssl.*",
    "com.amazonaws.http.conn.ssl.*",
    "javax.net.ssl.*", "org.apache.hadoop.*", "javax.*", "com.sun.org.apache.*", "org.apache.xerces.*"})
public class TestIcebergMinorOptimizeCommit extends TestIcebergBase {
  @Test
  public void testNoPartitionTableMinorOptimizeCommit() throws Exception {
    icebergTable.asUnkeyedTable().updateProperties()
        .set(com.netease.arctic.table.TableProperties.OPTIMIZE_SMALL_FILE_SIZE_BYTES_THRESHOLD, "1000")
        .commit();
    List<DataFile> dataFiles = insertDataFiles(icebergTable.asUnkeyedTable(), 10);
    insertEqDeleteFiles(icebergTable.asUnkeyedTable(), 5);
    insertPosDeleteFiles(icebergTable.asUnkeyedTable(), dataFiles);
    List<FileScanTask> fileScanTasks = IteratorUtils.toList(icebergTable.asUnkeyedTable().newScan().planFiles().iterator());
    Set<String> oldDataFilesPath = new HashSet<>();
    Set<String> oldDeleteFilesPath = new HashSet<>();
    icebergTable.asUnkeyedTable().newScan().planFiles()
        .forEach(fileScanTask -> {
          if (fileScanTask.file().fileSizeInBytes() <= 1000) {
            oldDataFilesPath.add((String) fileScanTask.file().path());
            fileScanTask.deletes().forEach(deleteFile -> oldDeleteFilesPath.add((String) deleteFile.path()));
          }
        });

    IcebergMinorOptimizePlan optimizePlan = new IcebergMinorOptimizePlan(icebergTable,
        new TableOptimizeRuntime(icebergTable.id()), fileScanTasks,
        new HashMap<>(), 1, System.currentTimeMillis());
    List<BaseOptimizeTask> tasks = optimizePlan.plan();

    List<DataFile> resultFiles = insertOptimizeTargetDataFiles(icebergTable.asUnkeyedTable(), 10);
    List<OptimizeTaskItem> taskItems = tasks.stream().map(task -> {
      BaseOptimizeTaskRuntime optimizeRuntime = new BaseOptimizeTaskRuntime(task.getTaskId());
      optimizeRuntime.setPreparedTime(System.currentTimeMillis());
      optimizeRuntime.setStatus(OptimizeStatus.Prepared);
      optimizeRuntime.setReportTime(System.currentTimeMillis());
      if (resultFiles != null) {
        optimizeRuntime.setNewFileSize(resultFiles.get(0).fileSizeInBytes());
        optimizeRuntime.setTargetFiles(resultFiles.stream().map(SerializationUtil::toByteBuffer).collect(Collectors.toList()));
      }
      List<ByteBuffer> finalTargetFiles = optimizeRuntime.getTargetFiles();
      finalTargetFiles.addAll(task.getInsertFiles());
      optimizeRuntime.setTargetFiles(finalTargetFiles);
      optimizeRuntime.setNewFileCnt(finalTargetFiles.size());
      // 1min
      optimizeRuntime.setCostTime(60 * 1000);
      return new OptimizeTaskItem(task, optimizeRuntime);
    }).collect(Collectors.toList());
    Map<String, List<OptimizeTaskItem>> partitionTasks = taskItems.stream()
        .collect(Collectors.groupingBy(taskItem -> taskItem.getOptimizeTask().getPartition()));

    IcebergOptimizeCommit optimizeCommit = new IcebergOptimizeCommit(icebergTable, partitionTasks);
    optimizeCommit.commit(icebergTable.asUnkeyedTable().currentSnapshot().snapshotId());

    Set<String> newDataFilesPath = new HashSet<>();
    Assert.assertNotEquals(oldDataFilesPath, newDataFilesPath);
  }
}
