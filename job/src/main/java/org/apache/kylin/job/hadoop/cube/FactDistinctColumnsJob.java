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

package org.apache.kylin.job.hadoop.cube;

import java.io.IOException;
import java.util.List;

import org.apache.commons.cli.Options;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.ShortWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.SequenceFileOutputFormat;
import org.apache.hadoop.util.ToolRunner;
import org.apache.hive.hcatalog.mapreduce.HCatInputFormat;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.util.HadoopUtil;
import org.apache.kylin.cube.CubeInstance;
import org.apache.kylin.cube.CubeManager;
import org.apache.kylin.cube.cuboid.Cuboid;
import org.apache.kylin.cube.model.CubeDesc;
import org.apache.kylin.cube.model.RowKeyDesc;
import org.apache.kylin.dict.DictionaryManager;
import org.apache.kylin.job.constant.BatchConstants;
import org.apache.kylin.job.hadoop.AbstractHadoopJob;
import org.apache.kylin.metadata.model.TblColRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;

/**
 * @author yangli9
 */
public class FactDistinctColumnsJob extends AbstractHadoopJob {
    protected static final Logger log = LoggerFactory.getLogger(FactDistinctColumnsJob.class);
    private static final Joiner joiner = Joiner.on(",");

    @Override
    public int run(String[] args) throws Exception {
        Options options = new Options();

        try {
            options.addOption(OPTION_JOB_NAME);
            options.addOption(OPTION_CUBE_NAME);
            options.addOption(OPTION_OUTPUT_PATH);
            options.addOption(OPTION_TABLE_NAME);
            parseOptions(options, args);

            job = Job.getInstance(getConf(), getOptionValue(OPTION_JOB_NAME));
            Configuration jobConf = job.getConfiguration();

            String cubeName = getOptionValue(OPTION_CUBE_NAME);
            Path output = new Path(getOptionValue(OPTION_OUTPUT_PATH));
            String intermediateTable = getOptionValue(OPTION_TABLE_NAME);

            // ----------------------------------------------------------------------------
            // add metadata to distributed cache
            CubeManager cubeMgr = CubeManager.getInstance(KylinConfig.getInstanceFromEnv());
            CubeInstance cubeInstance = cubeMgr.getCube(cubeName);
            CubeDesc cubeDesc = cubeInstance.getDescriptor();

            jobConf.set(BatchConstants.CFG_CUBE_NAME, cubeName);
            System.out.println("Starting: " + job.getJobName());

            jobConf.set("testConf1", "testConf");
            setJobClasspath(job);

            setupMapper(intermediateTable);
            setupReducer(output);

            jobConf.set("testConf2", "testConf");
            // CubeSegment seg = cubeMgr.getCube(cubeName).getTheOnlySegment();
            attachKylinPropsAndMetadata(cubeInstance, jobConf);

            jobConf.set("testConf3", "testConf");

            // set names and row key indexes of fact table's dictionary columns in Configuration,
            // so that mapper & reducer task can use them
            List<String> factDictColNames = Lists.newArrayList();
            List<Integer> factDictColRowKeyIndexes = Lists.newArrayList();

            Cuboid baseCuboid = Cuboid.findById(cubeDesc, Cuboid.getBaseCuboidId(cubeDesc));
            List<TblColRef> columns = baseCuboid.getColumns();

            RowKeyDesc rowkey = cubeDesc.getRowkey();
            DictionaryManager dictMgr = DictionaryManager.getInstance(KylinConfig.getInstanceFromEnv());
            for (int i = 0; i < columns.size(); i++) {
                TblColRef col = columns.get(i);
                if (!rowkey.isUseDictionary(col))
                    continue;

                String scanTable = (String) dictMgr.decideSourceData(cubeDesc.getModel(), rowkey.getDictionary(col), col, null)[0];
                if (cubeDesc.getModel().isFactTable(scanTable)) {
                    factDictColNames.add(col.getName());
                    factDictColRowKeyIndexes.add(i);
                }
            }
            jobConf.set("testConf4", "testConf");

            String x = joiner.join(factDictColNames);
            log.info("CFG_FACT_DICT_COLUMN_NAMES is " + x);
            jobConf.set(BatchConstants.CFG_FACT_DICT_COLUMN_NAMES, x);
            
            String y = joiner.join(factDictColRowKeyIndexes);
            log.info("CFG_FACT_DICT_COLUMN_ROWKEY_INDEXES is " + y);
            jobConf.set(BatchConstants.CFG_FACT_DICT_COLUMN_ROWKEY_INDEXES, y);

            return waitForCompletion(job);

        } catch (Exception e) {
            logger.error("error in FactDistinctColumnsJob", e);
            printUsage(options);
            throw e;
        } finally {
            if (job != null)
                cleanupTempConfFile(job.getConfiguration());
        }

    }

    private void setupMapper(String intermediateTable) throws IOException {
        //        FileInputFormat.setInputPaths(job, input);

        String[] dbTableNames = HadoopUtil.parseHiveTableName(intermediateTable);
        HCatInputFormat.setInput(job, dbTableNames[0], dbTableNames[1]);

        job.setInputFormatClass(HCatInputFormat.class);
        job.setMapperClass(FactDistinctColumnsMapper.class);
        job.setCombinerClass(FactDistinctColumnsCombiner.class);
        job.setMapOutputKeyClass(ShortWritable.class);
        job.setMapOutputValueClass(Text.class);
    }

    private void setupReducer(Path output) throws IOException {
        job.setReducerClass(FactDistinctColumnsReducer.class);
        job.setOutputFormatClass(SequenceFileOutputFormat.class);
        job.setOutputKeyClass(NullWritable.class);
        job.setOutputValueClass(Text.class);

        FileOutputFormat.setOutputPath(job, output);
        job.getConfiguration().set(BatchConstants.OUTPUT_PATH, output.toString());

        job.setNumReduceTasks(1);

        deletePath(job.getConfiguration(), output);
    }

    public static void main(String[] args) throws Exception {
        FactDistinctColumnsJob job = new FactDistinctColumnsJob();
        int exitCode = ToolRunner.run(job, args);
        System.exit(exitCode);
    }
}
