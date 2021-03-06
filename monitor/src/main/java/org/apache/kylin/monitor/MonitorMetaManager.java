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


package org.apache.kylin.monitor;

import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.*;
import org.apache.hadoop.hbase.client.*;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.log4j.Logger;
import java.io.File;
import java.io.IOException;

/**
 * Created by jiazhong on 2015/5/25.
 */
public class MonitorMetaManager {

    private static ConfigUtils monitorConfig = ConfigUtils.getInstance();

    static String TABLE_NAME = "kylin_metadata";
    final static String COLUMN_FAMILY = "f";
    final static String COLUMN = "c";
    final static String ROW_KEY_QUERY_READ_FILES = "/performance/query_log_files_already_read";
    final static String ROW_KEY_QUERY_READING_FILE = "/performance/query_log_file_reading";
    final static String ROW_KEY_QUERY_READING_FILE_LINE = "/performance/query_log_file_reading";


    final static String ROW_KEY_API_REQ_LOG_READ_FILES = "/performance/api_req_log_files_already_read";


    final static Logger logger = Logger.getLogger(MonitorMetaManager.class);

    static Configuration conf = null;

    static {
        try {
            monitorConfig.loadMonitorParam();
        } catch (IOException e) {
            e.printStackTrace();
        }
        conf = HBaseConfiguration.create();
    }


    /*
     * meta data initialize
     * @unused
     */
    public static void init() throws Exception {
        MonitorMetaManager.TABLE_NAME = MonitorMetaManager.getMetadataUrlPrefix();
        logger.info("Monitor Metadata Table :"+MonitorMetaManager.TABLE_NAME);
        logger.info("init monitor metadata,create table if not exist");
        MonitorMetaManager.creatTable(TABLE_NAME, new String[]{COLUMN_FAMILY});
    }

    public static String getMetadataUrlPrefix() {
        String hbaseMetadataUrl = monitorConfig.getMetadataUrl();
        String defaultPrefix = "kylin_metadata";
        int cut = hbaseMetadataUrl.indexOf('@');
        String tmp = cut < 0 ? defaultPrefix : hbaseMetadataUrl.substring(0, cut);
        return tmp;
    }


    /*
     * mark query file as read after parsing
     */
    public static void markQueryFileAsRead(String filename) throws IOException {
        String read_query_log_file = MonitorMetaManager.getReadQueryLogFiles();
        if(StringUtils.isEmpty(read_query_log_file)){
            read_query_log_file =  filename;
        }else{
            read_query_log_file = read_query_log_file.concat(",").concat(filename);
        }
        MonitorMetaManager.updateData(TABLE_NAME, ROW_KEY_QUERY_READ_FILES, COLUMN_FAMILY,COLUMN, read_query_log_file);
    }

    /*
     * mark reading file for tracking
     */
    public static void markQueryReadingFile(String query_reading_file) throws IOException {
        MonitorMetaManager.updateData(TABLE_NAME, ROW_KEY_QUERY_READING_FILE, COLUMN_FAMILY,COLUMN, query_reading_file);
    }

    /*
     * mark reading line for tracking
     */
    public static void markQueryReadingLine(String line_num) throws IOException {
        MonitorMetaManager.updateData(TABLE_NAME, ROW_KEY_QUERY_READING_FILE_LINE, COLUMN_FAMILY,COLUMN, line_num);
    }


    /*
     * get has been read file name list
     */
    public static String[] getReadQueryLogFileList() throws IOException {
        String fileList = MonitorMetaManager.getReadQueryLogFiles();
        return fileList.split(",");
    }

    /*
     * get has been read query log file
     */
    public static String getReadQueryLogFiles() throws IOException {
        return getListWithRowkey(TABLE_NAME, ROW_KEY_QUERY_READ_FILES);
    }


    /*
     * get has been read file
    */
    public static String getListWithRowkey(String table, String rowkey) throws IOException {
        Result result = getResultByRowKey(table, rowkey);
        String fileList = null;
        if (result.list() != null) {
            for (KeyValue kv : result.list()) {
                fileList = Bytes.toString(kv.getValue());
            }

        }
        fileList = fileList==null?"":fileList;
        return fileList;
    }


    /*
     * mark api req log file as read after parsing
     */
    public static void markApiReqLogFileAsRead(String filename) throws IOException {
        String read_api_req_log_files = MonitorMetaManager.getReadApiReqLogFiles();
        if (StringUtils.isEmpty(read_api_req_log_files)) {
            read_api_req_log_files = filename;
        } else {
            read_api_req_log_files = read_api_req_log_files.concat(",").concat(filename);
        }
        MonitorMetaManager.updateData(TABLE_NAME, ROW_KEY_API_REQ_LOG_READ_FILES, COLUMN_FAMILY,COLUMN, read_api_req_log_files);
    }


    /*
     * get has been read log file name list
     */
    public static String[] getReadApiReqLogFileList() throws IOException {
        String fileList = MonitorMetaManager.getReadApiReqLogFiles();
        return fileList.split(",");
    }

    /*
    * get has been read api request log file
    */
    public static String getReadApiReqLogFiles() throws IOException {
        return getListWithRowkey(TABLE_NAME, ROW_KEY_API_REQ_LOG_READ_FILES);
    }


    /*
     * create table in hbase
     */
    public static void creatTable(String tableName, String[] family) throws Exception {
        HBaseAdmin admin = new HBaseAdmin(conf);
        HTableDescriptor desc = new HTableDescriptor(tableName);
        for (int i = 0; i < family.length; i++) {
            desc.addFamily(new HColumnDescriptor(family[i]));
        }
        if (admin.tableExists(tableName)) {
            logger.info("table Exists!");
        } else {
            admin.createTable(desc);
            logger.info("create table Success!");
        }
    }

    /*
     * update cell in hbase
     */
    public static void updateData(String tableName, String rowKey, String family,String column, String value) throws IOException {
        HTable table = new HTable(conf, Bytes.toBytes(tableName));
        Put put = new Put(rowKey.getBytes());
        put.add(family.getBytes(), column.getBytes(), value.getBytes());
        try {
            table.put(put);
        } catch (IOException e) {
            e.printStackTrace();
        }
        logger.info("end insert data ......");
    }

    /*
     * get result by rowkey
     */
    public static Result getResultByRowKey(String tableName, String rowKey) throws IOException {
        HTable table = new HTable(conf, Bytes.toBytes(tableName));
        Get get = new Get(Bytes.toBytes(rowKey));
        Result result = table.get(get);
        return result;
    }


}
