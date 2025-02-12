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

package com.netease.arctic.op;

import com.netease.arctic.scan.CombinedScanTask;
import com.netease.arctic.table.KeyedTable;
import com.netease.arctic.table.TableProperties;
import com.netease.arctic.table.UnkeyedTable;
import com.netease.arctic.utils.TablePropertyUtil;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.OverwriteFiles;
import org.apache.iceberg.RewriteFiles;
import org.apache.iceberg.RowDelta;
import org.apache.iceberg.StructLike;
import org.apache.iceberg.Transaction;
import org.apache.iceberg.expressions.Expression;
import org.apache.iceberg.expressions.Expressions;
import org.apache.iceberg.io.CloseableIterable;
import org.apache.iceberg.relocated.com.google.common.base.Preconditions;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.util.StructLikeMap;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * Overwrite {@link com.netease.arctic.table.BaseTable} and change max transaction id map
 */
public class OverwriteBaseFiles extends PartitionTransactionOperation {

  public static final String PROPERTIES_TRANSACTION_ID = "txId";

  private final List<DataFile> deleteFiles;
  private final List<DataFile> addFiles;
  private final List<DeleteFile> deleteDeleteFiles;
  private final List<DeleteFile> addDeleteFiles;
  private Expression deleteExpression = Expressions.alwaysFalse();
  private final StructLikeMap<Long> partitionTransactionId;

  private Long transactionId;
  private Expression conflictDetectionFilter = null;

  public OverwriteBaseFiles(KeyedTable table) {
    super(table);
    this.deleteFiles = Lists.newArrayList();
    this.addFiles = Lists.newArrayList();
    this.deleteDeleteFiles = Lists.newArrayList();
    this.addDeleteFiles = Lists.newArrayList();
    this.partitionTransactionId = StructLikeMap.create(table.spec().partitionType());
  }

  public OverwriteBaseFiles overwriteByRowFilter(Expression expr) {
    if (expr != null) {
      deleteExpression = Expressions.or(deleteExpression, expr);
    }
    return this;
  }

  public OverwriteBaseFiles addFile(DataFile file) {
    addFiles.add(file);
    return this;
  }

  public OverwriteBaseFiles addFile(DeleteFile file) {
    addDeleteFiles.add(file);
    return this;
  }

  public OverwriteBaseFiles deleteFile(DataFile file) {
    deleteFiles.add(file);
    return this;
  }

  public OverwriteBaseFiles deleteFile(DeleteFile file) {
    deleteDeleteFiles.add(file);
    return this;
  }

  public OverwriteBaseFiles withTransactionId(StructLike partitionData, long transactionId) {
    this.partitionTransactionId.put(partitionData, transactionId);
    return this;
  }

  public OverwriteBaseFiles withTransactionIdForChangedPartition(long transactionId) {
    this.transactionId = transactionId;
    return this;
  }

  public OverwriteBaseFiles validateNoConflictingAppends(Expression newConflictDetectionFilter) {
    Preconditions.checkArgument(newConflictDetectionFilter != null, "Conflict detection filter cannot be null");
    this.conflictDetectionFilter = newConflictDetectionFilter;
    return this;
  }

  @Override
  protected StructLikeMap<Long> apply(Transaction transaction, StructLikeMap<Long> partitionMaxTxId) {
    applyDeleteExpression();

    StructLike partitionData = TablePropertyUtil.EMPTY_STRUCT;
    UnkeyedTable baseTable = keyedTable.baseTable();

    // step1: overwrite data files
    if (!this.addFiles.isEmpty() || !this.deleteFiles.isEmpty()) {
      OverwriteFiles overwriteFiles = transaction.newOverwrite();

      if (conflictDetectionFilter != null && baseTable.currentSnapshot() != null) {
        overwriteFiles.validateNoConflictingAppends(conflictDetectionFilter);
        overwriteFiles.validateFromSnapshot(baseTable.currentSnapshot().snapshotId());
      }

      for (DataFile d : this.addFiles) {
        overwriteFiles.addFile(d);
        partitionData = keyedTable.spec().isUnpartitioned() ? TablePropertyUtil.EMPTY_STRUCT : d.partition();
        partitionMaxTxId.put(partitionData, getPartitionMaxTxId(partitionData));
      }

      for (DataFile d : this.deleteFiles) {
        overwriteFiles.deleteFile(d);
        partitionData = keyedTable.spec().isUnpartitioned() ? TablePropertyUtil.EMPTY_STRUCT : d.partition();
        partitionMaxTxId.put(partitionData, getPartitionMaxTxId(partitionData));
      }
      if (transactionId != null && transactionId > 0) {
        overwriteFiles.set(PROPERTIES_TRANSACTION_ID, transactionId + "");
      }

      if (MapUtils.isNotEmpty(properties)) {
        properties.forEach(overwriteFiles::set);
      }
      overwriteFiles.commit();
    }

    // step2: RowDelta/Rewrite pos-delete files
    if (CollectionUtils.isNotEmpty(addDeleteFiles) || CollectionUtils.isNotEmpty(deleteDeleteFiles)) {
      if (CollectionUtils.isEmpty(deleteDeleteFiles)) {
        RowDelta rowDelta = transaction.newRowDelta();
        if (baseTable.currentSnapshot() != null) {
          rowDelta.validateFromSnapshot(baseTable.currentSnapshot().snapshotId());
        }

        for (DeleteFile d : this.addDeleteFiles) {
          partitionData = keyedTable.spec().isUnpartitioned() ? TablePropertyUtil.EMPTY_STRUCT : d.partition();
          partitionMaxTxId.put(partitionData, getPartitionMaxTxId(partitionData));
        }

        for (DataFile d : this.deleteFiles) {
          partitionData = keyedTable.spec().isUnpartitioned() ? TablePropertyUtil.EMPTY_STRUCT : d.partition();
          partitionMaxTxId.put(partitionData, getPartitionMaxTxId(partitionData));
        }
        addDeleteFiles.forEach(rowDelta::addDeletes);
        if (MapUtils.isNotEmpty(properties)) {
          properties.forEach(rowDelta::set);
        }
        rowDelta.commit();
      } else {
        RewriteFiles rewriteFiles = transaction.newRewrite();
        if (baseTable.currentSnapshot() != null) {
          rewriteFiles.validateFromSnapshot(baseTable.currentSnapshot().snapshotId());
        }

        for (DeleteFile d : this.addDeleteFiles) {
          partitionData = keyedTable.spec().isUnpartitioned() ? TablePropertyUtil.EMPTY_STRUCT : d.partition();
          partitionMaxTxId.put(partitionData, getPartitionMaxTxId(partitionData));
        }

        for (DataFile d : this.deleteFiles) {
          partitionData = keyedTable.spec().isUnpartitioned() ? TablePropertyUtil.EMPTY_STRUCT : d.partition();
          partitionMaxTxId.put(partitionData, getPartitionMaxTxId(partitionData));
        }
        rewriteFiles.rewriteFiles(Collections.emptySet(), new HashSet<>(deleteDeleteFiles),
            Collections.emptySet(), new HashSet<>(addDeleteFiles));
        if (MapUtils.isNotEmpty(properties)) {
          properties.forEach(rewriteFiles::set);
        }
        rewriteFiles.commit();
      }
    }

    // step3: set max transaction id
    if (keyedTable.spec().isUnpartitioned()) {
      long maxTransactionId = partitionMaxTxId.get(partitionData) == null ?
          TableProperties.PARTITION_MAX_TRANSACTION_ID_DEFAULT : partitionMaxTxId.get(partitionData);
      partitionMaxTxId.put(partitionData, Math.max(maxTransactionId,
          this.partitionTransactionId.getOrDefault(partitionData,
              TableProperties.PARTITION_MAX_TRANSACTION_ID_DEFAULT)));
    } else {
      this.partitionTransactionId.forEach((pd, txId) -> {
        if (partitionMaxTxId.containsKey(pd)) {
          long maxTransactionId = partitionMaxTxId.get(pd);
          partitionMaxTxId.put(pd, Math.max(maxTransactionId, txId));
        }
      });
    }

    return partitionMaxTxId;
  }

  private void applyDeleteExpression() {
    if (this.deleteExpression == null) {
      return;
    }
    try (CloseableIterable<CombinedScanTask> combinedScanTasks
             = keyedTable.newScan().filter(deleteExpression).planTasks()) {
      combinedScanTasks.forEach(combinedTask -> combinedTask.tasks().forEach(
          t -> {
            t.dataTasks().forEach(ft -> deleteFiles.add(ft.file()));
            t.arcticEquityDeletes().forEach(ft -> deleteFiles.add(ft.file()));
          }
      ));
    } catch (IOException e) {
      throw new IllegalStateException("failed when apply delete expression when overwrite files", e);
    }
  }

  private long getPartitionMaxTxId(StructLike partitionData) {
    long txId =
        partitionTransactionId.getOrDefault(partitionData, TableProperties.PARTITION_MAX_TRANSACTION_ID_DEFAULT);
    if (this.transactionId != null) {
      txId = Math.max(txId, this.transactionId);
    }
    return txId;
  }
}
