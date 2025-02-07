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

package com.netease.arctic.spark.writer;

import org.apache.iceberg.DataFile;
import org.apache.iceberg.DeleteFile;
import org.apache.iceberg.relocated.com.google.common.collect.ImmutableList;
import org.apache.iceberg.relocated.com.google.common.collect.Iterables;
import org.apache.spark.sql.connector.write.WriterCommitMessage;

import java.util.Arrays;

/**
 * A commit message for a write task that includes the data files and delete files that were
 * written by the task.
 */
public class WriteTaskDeleteFilesCommit implements WriterCommitMessage {
  private final DeleteFile[] taskFiles;
  private final DataFile[] dataFiles;

  WriteTaskDeleteFilesCommit(DeleteFile[] taskFiles, DataFile[] dataFiles) {
    this.taskFiles = taskFiles;
    this.dataFiles = dataFiles;
  }

  DeleteFile[] deleteFiles() {
    return taskFiles;
  }

  public static Iterable<DeleteFile> deleteFiles(WriterCommitMessage[] messages) {
    if (messages.length > 0) {
      return Iterables.concat(Iterables.transform(Arrays.asList(messages), message -> message != null ?
          ImmutableList.copyOf(((WriteTaskDeleteFilesCommit) message).deleteFiles()) :
          ImmutableList.of()));
    }
    return ImmutableList.of();
  }

  DataFile[] dataFiles() {
    return dataFiles;
  }

  public static Iterable<DataFile> dataFiles(WriterCommitMessage[] messages) {
    if (messages.length > 0) {
      return Iterables.concat(Iterables.transform(Arrays.asList(messages), message -> message != null ?
          ImmutableList.copyOf(((WriteTaskDeleteFilesCommit) message).dataFiles()) :
          ImmutableList.of()));
    }
    return ImmutableList.of();
  }
}
