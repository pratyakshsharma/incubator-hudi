/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.cli.commands;

import org.apache.hudi.cli.HoodieCLI;
import org.apache.hudi.cli.HoodiePrintHelper;
import org.apache.hudi.cli.utils.InputStreamConsumer;
import org.apache.hudi.cli.utils.SparkUtil;
import org.apache.hudi.common.fs.FSUtils;
import org.apache.hudi.common.model.HoodiePartitionMetadata;
import org.apache.hudi.common.table.HoodieTableConfig;
import org.apache.hudi.common.table.HoodieTableMetaClient;
import org.apache.hudi.common.table.timeline.HoodieActiveTimeline;
import org.apache.hudi.common.util.CleanerUtils;

import org.apache.hadoop.fs.Path;
import org.apache.log4j.Logger;
import org.apache.spark.launcher.SparkLauncher;
import org.springframework.shell.core.CommandMarker;
import org.springframework.shell.core.annotation.CliCommand;
import org.springframework.shell.core.annotation.CliOption;
import org.springframework.stereotype.Component;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.apache.hudi.common.table.HoodieTableMetaClient.METAFOLDER_NAME;

/**
 * CLI command to display and trigger repair options.
 */
@Component
public class RepairsCommand implements CommandMarker {

  private static final Logger LOG = Logger.getLogger(RepairsCommand.class);

  @CliCommand(value = "repair deduplicate",
      help = "De-duplicate a partition path contains duplicates & produce repaired files to replace with")
  public String deduplicate(
      @CliOption(key = {"duplicatedPartitionPath"}, help = "Partition Path containing the duplicates",
          mandatory = true) final String duplicatedPartitionPath,
      @CliOption(key = {"repairedOutputPath"}, help = "Location to place the repaired files",
          mandatory = true) final String repairedOutputPath,
      @CliOption(key = {"sparkProperties"}, help = "Spark Properties File Path",
          mandatory = true) final String sparkPropertiesPath,
      @CliOption(key = {"dedupeType"}, help = "Check DeDupeType.scala for valid values",
        unspecifiedDefaultValue = "insertType") final String dedupeType,
      @CliOption(key = {"dryrun"}, help = "Should we actually remove duplicates or just run and store result to repairedOutputPath",
        unspecifiedDefaultValue = "true") final boolean dryRun)
      throws Exception {
    SparkLauncher sparkLauncher = SparkUtil.initLauncher(sparkPropertiesPath);
    sparkLauncher.addAppArgs(SparkMain.SparkCommand.DEDUPLICATE.toString(), duplicatedPartitionPath, repairedOutputPath,
        HoodieCLI.getTableMetaClient().getBasePath(), dedupeType, String.valueOf(dryRun));
    Process process = sparkLauncher.launch();
    InputStreamConsumer.captureOutput(process);
    int exitCode = process.waitFor();

    if (exitCode != 0) {
      return "Deduplicated files placed in:  " + repairedOutputPath;
    }
    return "Deduplication failed ";
  }

  @CliCommand(value = "repair addpartitionmeta", help = "Add partition metadata to a table, if not present")
  public String addPartitionMeta(
      @CliOption(key = {"dryrun"}, help = "Should we actually add or just print what would be done",
          unspecifiedDefaultValue = "true") final boolean dryRun)
      throws IOException {

    HoodieTableMetaClient client = HoodieCLI.getTableMetaClient();
    String latestCommit =
        client.getActiveTimeline().getCommitTimeline().lastInstant().get().getTimestamp();
    List<String> partitionPaths =
        FSUtils.getAllPartitionFoldersThreeLevelsDown(HoodieCLI.fs, client.getBasePath());
    Path basePath = new Path(client.getBasePath());
    String[][] rows = new String[partitionPaths.size()][];

    int ind = 0;
    for (String partition : partitionPaths) {
      Path partitionPath = FSUtils.getPartitionPath(basePath, partition);
      String[] row = new String[3];
      row[0] = partition;
      row[1] = "Yes";
      row[2] = "None";
      if (!HoodiePartitionMetadata.hasPartitionMetadata(HoodieCLI.fs, partitionPath)) {
        row[1] = "No";
        if (!dryRun) {
          HoodiePartitionMetadata partitionMetadata =
              new HoodiePartitionMetadata(HoodieCLI.fs, latestCommit, basePath, partitionPath);
          partitionMetadata.trySave(0);
        }
      }
      rows[ind++] = row;
    }

    return HoodiePrintHelper.print(new String[] {"Partition Path", "Metadata Present?", "Action"}, rows);
  }

  @CliCommand(value = "repair overwrite-hoodie-props", help = "Overwrite hoodie.properties with provided file. Risky operation. Proceed with caution!")
  public String overwriteHoodieProperties(
      @CliOption(key = {"new-props-file"}, help = "Path to a properties file on local filesystem to overwrite the table's hoodie.properties with")
      final String overwriteFilePath) throws IOException {

    HoodieTableMetaClient client = HoodieCLI.getTableMetaClient();
    Properties newProps = new Properties();
    newProps.load(new FileInputStream(new File(overwriteFilePath)));
    Map<String, String> oldProps = client.getTableConfig().getProps();
    Path metaPathDir = new Path(client.getBasePath(), METAFOLDER_NAME);
    HoodieTableConfig.createHoodieProperties(client.getFs(), metaPathDir, newProps);

    TreeSet<String> allPropKeys = new TreeSet<>();
    allPropKeys.addAll(newProps.keySet().stream().map(Object::toString).collect(Collectors.toSet()));
    allPropKeys.addAll(oldProps.keySet());

    String[][] rows = new String[allPropKeys.size()][];
    int ind = 0;
    for (String propKey : allPropKeys) {
      String[] row = new String[]{
          propKey,
          oldProps.getOrDefault(propKey, "null"),
          newProps.getOrDefault(propKey, "null").toString()
      };
      rows[ind++] = row;
    }
    return HoodiePrintHelper.print(new String[] {"Property", "Old Value", "New Value"}, rows);
  }

  @CliCommand(value = "repair corrupted clean files", help = "repair corrupted clean files")
  public void removeCorruptedPendingCleanAction() {

    HoodieTableMetaClient client = HoodieCLI.getTableMetaClient();
    HoodieActiveTimeline activeTimeline = HoodieCLI.getTableMetaClient().getActiveTimeline();

    activeTimeline.filterInflightsAndRequested().getInstants().forEach(instant -> {
      try {
        CleanerUtils.getCleanerPlan(client, instant);
      } catch (IOException e) {
        LOG.warn("try to remove corrupted instant file: " + instant);
        FSUtils.deleteInstantFile(client.getFs(), client.getMetaPath(), instant);
      }
    });
  }
}
