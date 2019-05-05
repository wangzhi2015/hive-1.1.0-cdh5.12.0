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

package org.apache.hive.jdbc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hadoop.hive.ql.exec.FunctionRegistry;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.hive.jdbc.miniHS2.MiniHS2;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestJdbcWithMiniHS2 {
  private static MiniHS2 miniHS2 = null;
  private static String dataFileDir;
  private static Path kvDataFilePath;
  private static final String tmpDir = System.getProperty("test.tmp.dir");
  private static final String testDbName = "testjdbcminihs2";
  private static final String defaultDbName = "default";
  private static final String tableName = "testjdbcminihs2tbl";
  private static final String tableComment = "Simple table";
  private static Connection conDefault = null;
  private static Connection conTestDb = null;
  private static String testUdfClassName =
      "org.apache.hadoop.hive.contrib.udf.example.UDFExampleAdd";

  @BeforeClass
  public static void setupBeforeClass() throws Exception {
    HiveConf conf = new HiveConf();
    dataFileDir = conf.get("test.data.files").replace('\\', '/').replace("c:", "");
    kvDataFilePath = new Path(dataFileDir, "kv1.txt");
    try {
      startMiniHS2(conf);
    } catch (Exception e) {
     System.out.println("Unable to start MiniHS2: " + e);
     throw e;
    }
    // Open default connections which will be used throughout the tests
    try {
      openDefaultConnections();
    } catch (Exception e) {
      System.out.println("Unable to open default connections to MiniHS2: " + e);
      throw e;
    }
    Statement stmt = conDefault.createStatement();
    stmt.execute("drop database if exists " + testDbName + " cascade");
    stmt.execute("create database " + testDbName);
    stmt.close();
    // tables in test db
    createTestTables(conTestDb, testDbName);
  }

  private static Connection getConnection() throws Exception {
    return getConnection(miniHS2.getJdbcURL(), System.getProperty("user.name"), "bar");
  }

  private static Connection getConnection(String dbName) throws Exception {
    return getConnection(miniHS2.getJdbcURL(dbName), System.getProperty("user.name"), "bar");
  }

  private static Connection getConnection(String jdbcURL, String user, String pwd)
      throws SQLException {
    Connection conn = DriverManager.getConnection(jdbcURL, user, pwd);
    assertNotNull(conn);
    return conn;
  }

  private static void createTestTables(Connection conn, String dbName) throws SQLException {
    Statement stmt = conn.createStatement();
    Path dataFilePath = new Path(dataFileDir, "kv1.txt");
    // We've already dropped testDbName in constructor & we also drop it in tearDownAfterClass
    String prefix = dbName + ".";
    String tableName = prefix + TestJdbcWithMiniHS2.tableName;

    // create a table
    stmt.execute("create table " + tableName
        + " (int_col int comment 'the int column', value string) comment '" + tableComment + "'");
    // load data
    stmt.execute("load data local inpath '" + dataFilePath.toString() + "' into table " + tableName);

    stmt.close();
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    // drop test db and its tables and views
    Statement stmt = conDefault.createStatement();
    stmt.execute("set hive.support.concurrency = false");
    stmt.execute("drop database if exists " + testDbName + " cascade");
    stmt.close();
    if (conTestDb != null) {
      conTestDb.close();
    }
    if (conDefault != null) {
      conDefault.close();
    }
    stopMiniHS2();
    cleanupMiniHS2();
  }

  private static void restoreMiniHS2AndConnections()  throws Exception {
    if (conTestDb != null) {
      try {
        conTestDb.close();
      } catch (SQLException e) {
        // Do nothing
      }
    }
    if (conDefault != null) {
      try {
        conDefault.close();
      } catch (SQLException e) {
        // Do nothing
      }
    }
    stopMiniHS2();
    HiveConf conf = new HiveConf();
    startMiniHS2(conf);
    openDefaultConnections();
  }

  private static void startMiniHS2(HiveConf conf) throws Exception {
    conf.setBoolVar(ConfVars.HIVE_SUPPORT_CONCURRENCY, false);
    conf.setBoolVar(ConfVars.HIVE_SERVER2_LOGGING_OPERATION_ENABLED, false);
    miniHS2 = new MiniHS2(conf);
    Map<String, String> confOverlay = new HashMap<String, String>();
    miniHS2.start(confOverlay);
  }

  private static void stopMiniHS2() {
    if ((miniHS2 != null) && (miniHS2.isStarted())) {
      miniHS2.stop();
    }
  }

  private static void cleanupMiniHS2() {
    if (miniHS2 != null) {
      miniHS2.cleanup();
    }
  }

  private static void openDefaultConnections() throws Exception {
    conDefault = getConnection();
    conTestDb = getConnection(testDbName);
  }

  @Test
  public void testConnection() throws Exception {
    Statement stmt = conTestDb.createStatement();
    ResultSet res = stmt.executeQuery("select * from " + tableName + " limit 5");
    assertTrue(res.next());
    res.close();
    stmt.close();
  }

  @Test
  public void testConcurrentStatements() throws Exception {
    startConcurrencyTest(conTestDb, tableName, 50);
  }

  private static void startConcurrencyTest(Connection conn, String tableName, int numTasks) {
    // Start concurrent testing
    int POOL_SIZE = 100;
    int TASK_COUNT = numTasks;

    SynchronousQueue<Runnable> executorQueue = new SynchronousQueue<Runnable>();
    ExecutorService workers =
        new ThreadPoolExecutor(1, POOL_SIZE, 20, TimeUnit.SECONDS, executorQueue);
    List<Future<Boolean>> list = new ArrayList<Future<Boolean>>();
    int i = 0;
    while (i < TASK_COUNT) {
      try {
        Future<Boolean> future = workers.submit(new JDBCTask(conn, i, tableName));
        list.add(future);
        i++;
      } catch (RejectedExecutionException ree) {
        try {
          TimeUnit.MILLISECONDS.sleep(100);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    }

    for (Future<Boolean> future : list) {
      try {
        Boolean result = future.get(30, TimeUnit.SECONDS);
        assertTrue(result);
      } catch (ExecutionException ee) {
        fail("Concurrent Statement failed: " + ee.getCause());
      } catch (TimeoutException te) {
        System.out.println("Task was timeout after 30 second: " + te);
      } catch (CancellationException ce) {
        System.out.println("Task was interrupted: " + ce);
      } catch (InterruptedException ie) {
        System.out.println("Thread was interrupted: " + ie);
      }
    }
    workers.shutdown();
  }

  static class JDBCTask implements Callable<Boolean> {
    private String showsql = "show tables";
    private String querysql;
    private int seq = 0;
    Connection con = null;
    Statement stmt = null;
    ResultSet res = null;

    JDBCTask(Connection con, int seq, String tblName) {
      this.con = con;
      this.seq = seq;
      querysql = "SELECT count(value) FROM " + tblName;
    }

    public Boolean call() throws SQLException {
      int mod = seq%10;
      try {
        if (mod < 2) {
          String name = con.getMetaData().getDatabaseProductName();
        } else if (mod < 5) {
          stmt = con.createStatement();
          res = stmt.executeQuery(querysql);
          while (res.next()) {
            res.getInt(1);
          }
        } else if (mod < 7) {
          res = con.getMetaData().getSchemas();
          if (res.next()) {
            res.getString(1);
          }
        } else {
          stmt = con.createStatement();
          res = stmt.executeQuery(showsql);
          if (res.next()) {
            res.getString(1);
          }
        }
        return new Boolean(true);
      } finally {
        try {
          if (res != null) {
            res.close();
            res = null;
          }
          if (stmt != null) {
            stmt.close();
            stmt = null;
          }
        } catch (SQLException sqle1) {
        }
      }
    }
  }

  /**   This test is to connect to any database without using the command "Use <<DB>>"
   *  1) connect to default database.
   *  2) Create a new DB test_default.
   *  3) Connect to test_default database.
   *  4) Connect and create table under test_default_test.
   *  5) Connect and display all tables.
   *  6) Connect to default database and shouldn't find table test_default_test.
   *  7) Connect and drop test_default_test.
   *  8) drop test_default database.
   */

  @Test
  public void testURIDatabaseName() throws Exception{

    String  jdbcUri  = miniHS2.getJdbcURL().substring(0, miniHS2.getJdbcURL().indexOf("default"));

    Connection conn= getConnection(jdbcUri+"default", System.getProperty("user.name"),"bar");
    String dbName="test_connection_non_default_db";
    String tableInNonDefaultSchema="table_in_non_default_schema";
    Statement stmt = conn.createStatement();
    stmt.execute("create database  if not exists "+dbName);
    stmt.close();
    conn.close();

    conn = getConnection(jdbcUri+dbName,System.getProperty("user.name"),"bar");
    stmt = conn .createStatement();
    boolean expected = stmt.execute(" create table "+tableInNonDefaultSchema +" (x int)");
    stmt.close();
    conn .close();

    conn  = getConnection(jdbcUri+dbName,System.getProperty("user.name"),"bar");
    stmt = conn .createStatement();
    ResultSet res = stmt.executeQuery("show tables");
    boolean testTableExists = false;
    while (res.next()) {
      assertNotNull("table name is null in result set", res.getString(1));
      if (tableInNonDefaultSchema.equalsIgnoreCase(res.getString(1))) {
        testTableExists = true;
      }
    }
    assertTrue("table name  "+tableInNonDefaultSchema
        + "   found in SHOW TABLES result set", testTableExists);
    stmt.close();
    conn .close();

    conn  = getConnection(jdbcUri+"default",System.getProperty("user.name"),"bar");
    stmt = conn .createStatement();
    res = stmt.executeQuery("show tables");
    testTableExists = false;
    while (res.next()) {
      assertNotNull("table name is null in result set", res.getString(1));
      if (tableInNonDefaultSchema.equalsIgnoreCase(res.getString(1))) {
        testTableExists = true;
      }
    }

    assertFalse("table name "+tableInNonDefaultSchema
        + "  NOT  found in SHOW TABLES result set", testTableExists);
    stmt.close();
    conn .close();

    conn  = getConnection(jdbcUri+dbName,System.getProperty("user.name"),"bar");
    stmt = conn .createStatement();
    stmt.execute("set hive.support.concurrency = false");
    res = stmt.executeQuery("show tables");

    stmt.execute(" drop table if exists table_in_non_default_schema");
    expected = stmt.execute("DROP DATABASE "+ dbName);
    stmt.close();
    conn.close();

    conn  = getConnection(jdbcUri+"default",System.getProperty("user.name"),"bar");
    stmt = conn .createStatement();
    res = stmt.executeQuery("show tables");
    testTableExists = false;
    while (res.next()) {
      assertNotNull("table name is null in result set", res.getString(1));
      if (tableInNonDefaultSchema.equalsIgnoreCase(res.getString(1))) {
        testTableExists = true;
      }
    }

    // test URI with no dbName
    conn  = getConnection(jdbcUri, System.getProperty("user.name"),"bar");
    verifyCurrentDB("default", conn);
    conn.close();

    conn  = getConnection(jdbcUri + ";", System.getProperty("user.name"),"bar");
    verifyCurrentDB("default", conn);
    conn.close();

    conn  = getConnection(jdbcUri + ";/foo=bar;foo1=bar1", System.getProperty("user.name"),"bar");
    verifyCurrentDB("default", conn);
    conn.close();
  }

  /**
   * verify that the current db is the one expected. first create table as <db>.tab and then
   * describe that table to check if <db> is the current database
   * @param expectedDbName
   * @param hs2Conn
   * @throws Exception
   */
  private void verifyCurrentDB(String expectedDbName, Connection hs2Conn) throws Exception {
    String verifyTab = "miniHS2DbVerificationTable";
    Statement stmt = hs2Conn.createStatement();
    stmt.execute("DROP TABLE IF EXISTS " + expectedDbName + "." + verifyTab);
    stmt.execute("CREATE TABLE " + expectedDbName + "." + verifyTab + "(id INT)");
    stmt.execute("DESCRIBE " + verifyTab);
    stmt.execute("DROP TABLE IF EXISTS " + expectedDbName + "." + verifyTab);
    stmt.close();
  }

  @Test
  public void testConnectionSchemaAPIs() throws Exception {
    /**
     * get/set Schema are new in JDK7 and not available in java.sql.Connection in JDK6. Hence the
     * test uses HiveConnection object to call these methods so that test will run with older JDKs
     */
    HiveConnection hiveConn = (HiveConnection) conDefault;
    assertEquals(defaultDbName, hiveConn.getSchema());
    Statement stmt = conDefault.createStatement();

    stmt.execute("USE " + testDbName);
    assertEquals(testDbName, hiveConn.getSchema());

    stmt.execute("USE " + defaultDbName);
    assertEquals(defaultDbName, hiveConn.getSchema());

    hiveConn.setSchema(defaultDbName);
    assertEquals(defaultDbName, hiveConn.getSchema());
    hiveConn.setSchema(defaultDbName);
    assertEquals(defaultDbName, hiveConn.getSchema());

    assertTrue(hiveConn.getCatalog().isEmpty());
    hiveConn.setCatalog("foo");
    assertTrue(hiveConn.getCatalog().isEmpty());
  }

  /**
   * This method tests whether while creating a new connection, the config
   * variables specified in the JDBC URI are properly set for the connection.
   * This is a test for HiveConnection#configureConnection.
   *
   * @throws Exception
   */
  @Test
  public void testNewConnectionConfiguration() throws Exception {
    // Set some conf parameters
    String hiveConf =
        "hive.cli.print.header=true;hive.server2.async.exec.shutdown.timeout=20;"
            + "hive.server2.async.exec.threads=30;hive.server2.thrift.max.worker.threads=15";
    // Set some conf vars
    String hiveVar = "stab=salesTable;icol=customerID";
    String jdbcUri = miniHS2.getJdbcURL() + "?" + hiveConf + "#" + hiveVar;
    // Open a new connection with these conf & vars
    Connection con1 = DriverManager.getConnection(jdbcUri);
    // Execute "set" command and retrieve values for the conf & vars specified above
    // Assert values retrieved
    Statement stmt = con1.createStatement();
    // Verify that the property has been properly set while creating the
    // connection above
    verifyConfProperty(stmt, "hive.cli.print.header", "true");
    verifyConfProperty(stmt, "hive.server2.async.exec.shutdown.timeout", "20");
    verifyConfProperty(stmt, "hive.server2.async.exec.threads", "30");
    verifyConfProperty(stmt, "hive.server2.thrift.max.worker.threads", "15");
    verifyConfProperty(stmt, "stab", "salesTable");
    verifyConfProperty(stmt, "icol", "customerID");
    stmt.close();
    con1.close();
  }

  private void verifyConfProperty(Statement stmt, String property,
      String expectedValue) throws Exception {
    ResultSet res = stmt.executeQuery("set " + property);
    while (res.next()) {
      String resultValues[] = res.getString(1).split("=");
      assertEquals(resultValues[1], expectedValue);
    }
  }

  /**
   * Tests the creation of the 3 scratch dirs: hdfs, local, downloaded resources (which is also local).
   * 1. Test with doAs=false: open a new JDBC session and verify the presence of directories/permissions
   * 2. Test with doAs=true: open a new JDBC session and verify the presence of directories/permissions
   * @throws Exception
   */
  @Test
  public void testSessionScratchDirs() throws Exception {
    // Stop HiveServer2
    stopMiniHS2();
    HiveConf conf = new HiveConf();
    String userName;
    Path scratchDirPath;
    // Set a custom prefix for hdfs scratch dir path
    conf.set("hive.exec.scratchdir", tmpDir + "/hs2");
    // Set a scratch dir permission
    String fsPermissionStr = "700";
    conf.set("hive.scratch.dir.permission", fsPermissionStr);
    // Start an instance of HiveServer2 which uses miniMR
    startMiniHS2(conf);
    // 1. Test with doAs=false
    String sessionConf="hive.server2.enable.doAs=false";
    userName = System.getProperty("user.name");
    Connection conn = getConnection(miniHS2.getJdbcURL(testDbName, sessionConf), userName, "password");
    // FS
    FileSystem fs = miniHS2.getLocalFS();
    FsPermission expectedFSPermission = new FsPermission(HiveConf.getVar(conf,
        HiveConf.ConfVars.SCRATCHDIRPERMISSION));

    // Verify scratch dir paths and permission
    // HDFS scratch dir
    scratchDirPath = new Path(HiveConf.getVar(conf, HiveConf.ConfVars.SCRATCHDIR) + "/" + userName);
    verifyScratchDir(conf, fs, scratchDirPath, expectedFSPermission, userName, false);

    // Local scratch dir
    scratchDirPath = new Path(HiveConf.getVar(conf, HiveConf.ConfVars.LOCALSCRATCHDIR));
    verifyScratchDir(conf, fs, scratchDirPath, expectedFSPermission, userName, true);

    // Downloaded resources dir
    scratchDirPath = new Path(HiveConf.getVar(conf, HiveConf.ConfVars.DOWNLOADED_RESOURCES_DIR));
    verifyScratchDir(conf, fs, scratchDirPath, expectedFSPermission, userName, true);
    conn.close();

    // 2. Test with doAs=true
    sessionConf="hive.server2.enable.doAs=true";
    // Test for user "neo"
    userName = "neo";
    conn = getConnection(miniHS2.getJdbcURL(testDbName, sessionConf), userName, "the-one");

    // Verify scratch dir paths and permission
    // HDFS scratch dir
    scratchDirPath = new Path(HiveConf.getVar(conf, HiveConf.ConfVars.SCRATCHDIR) + "/" + userName);
    verifyScratchDir(conf, fs, scratchDirPath, expectedFSPermission, userName, false);

    // Local scratch dir
    scratchDirPath = new Path(HiveConf.getVar(conf, HiveConf.ConfVars.LOCALSCRATCHDIR));
    verifyScratchDir(conf, fs, scratchDirPath, expectedFSPermission, userName, true);

    // Downloaded resources dir
    scratchDirPath = new Path(HiveConf.getVar(conf, HiveConf.ConfVars.DOWNLOADED_RESOURCES_DIR));
    verifyScratchDir(conf, fs, scratchDirPath, expectedFSPermission, userName, true);
    conn.close();

    // Restore original state
    restoreMiniHS2AndConnections();
  }


  /**
   * Test UDF whitelist
   * - verify default value
   * - verify udf allowed with default whitelist
   * - verify udf allowed with specific whitelist
   * - verify udf disallowed when not in whitelist
   * @throws Exception
   */
  @Test
  public void testUdfWhiteBlackList() throws Exception {
    HiveConf testConf = new HiveConf();
    assertTrue(testConf.getVar(ConfVars.HIVE_SERVER2_BUILTIN_UDF_WHITELIST).isEmpty());
    // verify that udf in default whitelist can be executed
    Statement stmt = conDefault.createStatement();
    stmt.executeQuery("SELECT substr('foobar', 4) ");
    stmt.close();

    // setup whitelist
    stopMiniHS2();
    Set<String> funcNames = FunctionRegistry.getFunctionNames();
    funcNames.remove("reflect");
    String funcNameStr = "";
    for (String funcName : funcNames) {
      funcNameStr += "," + funcName;
    }
    funcNameStr = funcNameStr.substring(1); // remove ',' at begining
    testConf.setVar(ConfVars.HIVE_SERVER2_BUILTIN_UDF_WHITELIST, funcNameStr);
    startMiniHS2(testConf);
    Connection conn =
        getConnection(miniHS2.getJdbcURL(testDbName), System.getProperty("user.name"), "bar");
    stmt = conn.createStatement();
    // verify that udf in whitelist can be executed
    stmt.executeQuery("SELECT substr('foobar', 3) ");
    // verify that udf not in whitelist fails
    try {
      stmt.executeQuery("SELECT reflect('java.lang.String', 'valueOf', 1) ");
      fail("reflect() udf invocation should fail");
    } catch (SQLException e) {
      // expected
    }
    conn.close();

    // Restore original state
    restoreMiniHS2AndConnections();
  }

  /** Test UDF blacklist
   *   - verify default value
   *   - verify udfs allowed with default blacklist
   *   - verify udf disallowed when in blacklist
   * @throws Exception
   */
  @Test
  public void testUdfBlackList() throws Exception {
    HiveConf testConf = new HiveConf();
    assertTrue(testConf.getVar(ConfVars.HIVE_SERVER2_BUILTIN_UDF_BLACKLIST).isEmpty());
    Statement stmt = conDefault.createStatement();
    // verify that udf in default whitelist can be executed
    stmt.executeQuery("SELECT substr('foobar', 4) ");

    stopMiniHS2();
    testConf.setVar(ConfVars.HIVE_SERVER2_BUILTIN_UDF_BLACKLIST, "reflect");
    startMiniHS2(testConf);
    Connection conn =
        getConnection(miniHS2.getJdbcURL(testDbName), System.getProperty("user.name"), "bar");
    stmt = conn.createStatement();

    try {
      stmt.executeQuery("SELECT reflect('java.lang.String', 'valueOf', 1) ");
      fail("reflect() udf invocation should fail");
    } catch (SQLException e) {
      // expected
    }
    conn.close();
    // Restore original state
    restoreMiniHS2AndConnections();
  }

  /** Test UDF blacklist overrides whitelist
   * @throws Exception
   */
  @Test
  public void testUdfBlackListOverride() throws Exception {
    stopMiniHS2();
    // setup whitelist
    HiveConf testConf = new HiveConf();

    Set<String> funcNames = FunctionRegistry.getFunctionNames();
    String funcNameStr = "";
    for (String funcName : funcNames) {
      funcNameStr += "," + funcName;
    }
    funcNameStr = funcNameStr.substring(1); // remove ',' at begining
    testConf.setVar(ConfVars.HIVE_SERVER2_BUILTIN_UDF_WHITELIST, funcNameStr);
    testConf.setVar(ConfVars.HIVE_SERVER2_BUILTIN_UDF_BLACKLIST, "reflect");
    startMiniHS2(testConf);
    Connection conn =
        getConnection(miniHS2.getJdbcURL(testDbName), System.getProperty("user.name"), "bar");
    Statement stmt = conn.createStatement();

    // verify that udf in black list fails even though it's included in whitelist
    try {
      stmt.executeQuery("SELECT reflect('java.lang.String', 'valueOf', 1) ");
      fail("reflect() udf invocation should fail");
    } catch (SQLException e) {
      // expected
    }
    conn.close();
    // Restore original state
    restoreMiniHS2AndConnections();
  }

  /**
   * Tests the creation of the root hdfs scratch dir, which should be writable by all.
   *
   * @throws Exception
   */
  @Test
  public void testRootScratchDir() throws Exception {
    // Stop HiveServer2
    stopMiniHS2();
    HiveConf conf = new HiveConf();
    String userName;
    Path scratchDirPath;
    conf.set("hive.exec.scratchdir", tmpDir + "/hs2");
    // Start an instance of HiveServer2 which uses miniMR
    startMiniHS2(conf);
    userName = System.getProperty("user.name");
    Connection conn = getConnection(miniHS2.getJdbcURL(testDbName), userName, "password");
    // FS
    FileSystem fs = miniHS2.getLocalFS();
    FsPermission expectedFSPermission = new FsPermission((short)00733);
    // Verify scratch dir paths and permission
    // HDFS scratch dir
    scratchDirPath = new Path(HiveConf.getVar(conf, HiveConf.ConfVars.SCRATCHDIR));
    verifyScratchDir(conf, fs, scratchDirPath, expectedFSPermission, userName, false);
    conn.close();

    // Test with multi-level scratch dir path
    // Stop HiveServer2
    stopMiniHS2();
    conf.set("hive.exec.scratchdir", tmpDir + "/level1/level2/level3");
    startMiniHS2(conf);
    conn = getConnection(miniHS2.getJdbcURL(testDbName), userName, "password");
    scratchDirPath = new Path(HiveConf.getVar(conf, HiveConf.ConfVars.SCRATCHDIR));
    verifyScratchDir(conf, fs, scratchDirPath, expectedFSPermission, userName, false);
    conn.close();

    // Restore original state
    restoreMiniHS2AndConnections();
  }

  private void verifyScratchDir(HiveConf conf, FileSystem fs, Path scratchDirPath,
      FsPermission expectedFSPermission, String userName, boolean isLocal) throws Exception {
    String dirType = isLocal ? "Local" : "DFS";
    assertTrue("The expected " + dirType + " scratch dir does not exist for the user: " +
        userName, fs.exists(scratchDirPath));
    if (fs.exists(scratchDirPath) && !isLocal) {
      assertEquals("DFS scratch dir permissions don't match", expectedFSPermission,
          fs.getFileStatus(scratchDirPath).getPermission());
    }
  }

  /**
   * Tests ADD JAR uses Hives ReflectionUtil.CONSTRUCTOR_CACHE
   *
   * @throws Exception
   */
  @Test
  public void testAddJarConstructorUnCaching() throws Exception {
    // This test assumes the hive-contrib JAR has been built as part of the Hive build.
    // Also dependent on the UDFExampleAdd class within that JAR.
    setReflectionUtilCache();
    Path jarFilePath = getHiveContribJarPath();
    long cacheBeforeAddJar, cacheAfterClose;
    // Force the cache clear so we know its empty
    invalidateReflectionUtlCache();
    cacheBeforeAddJar = getReflectionUtilCacheSize();
    System.out.println("CONSTRUCTOR_CACHE size before add jar: " + cacheBeforeAddJar);
    System.out.println("CONSTRUCTOR_CACHE as map before add jar:" + getReflectionUtilCache().asMap());
    Assert.assertTrue("FAILED: CONSTRUCTOR_CACHE size before add jar: " + cacheBeforeAddJar,
            cacheBeforeAddJar == 0);

    // Add the jar file
    Statement stmt = conTestDb.createStatement();
    stmt.execute("ADD JAR " + jarFilePath.toString());
    // Create a temporary function using the jar
    stmt.execute("CREATE TEMPORARY FUNCTION add_func AS '" + testUdfClassName + "'");
    // Execute the UDF
    ResultSet res = stmt.executeQuery("SELECT add_func(int_col, 1) from " + tableName + " limit 1");
    assertTrue(res.next());
    TimeUnit.SECONDS.sleep(7);
    // Have to force a cleanup of all expired entries here because its possible that the
    // expired entries will still be counted in Cache.size().
    // Taken from:
    // http://docs.guava-libraries.googlecode.com/git/javadoc/com/google/common/cache/CacheBuilder.html
    cleanUpReflectionUtlCache();
    cacheAfterClose = getReflectionUtilCacheSize();
    System.out.println("CONSTRUCTOR_CACHE size after connection close: " + cacheAfterClose);
    Assert.assertTrue("FAILED: CONSTRUCTOR_CACHE size after connection close: " + cacheAfterClose,
            cacheAfterClose == 0);
    stmt.execute("DROP TEMPORARY FUNCTION IF EXISTS add_func");
    stmt.close();
  }

  private void setReflectionUtilCache() {
    Field constructorCacheField;
    Cache<Class<?>, Constructor<?>> tmp;
    try {
      constructorCacheField = ReflectionUtils.class.getDeclaredField("CONSTRUCTOR_CACHE");
      if (constructorCacheField != null) {
        constructorCacheField.setAccessible(true);
        Field modifiersField = Field.class.getDeclaredField("modifiers");
        modifiersField.setAccessible(true);
        modifiersField.setInt(constructorCacheField, constructorCacheField.getModifiers()
            & ~Modifier.FINAL);
        tmp =
            CacheBuilder.newBuilder().expireAfterAccess(5, TimeUnit.SECONDS).concurrencyLevel(64)
                .weakKeys().weakValues().build();
        constructorCacheField.set(tmp.getClass(), tmp);
      }
    } catch (Exception e) {
      System.out.println("Error when setting the CONSTRUCTOR_CACHE to expire: " + e);
    }
  }

  private Cache getReflectionUtilCache() {
    Field constructorCacheField;
    try {
      constructorCacheField = ReflectionUtils.class.getDeclaredField("CONSTRUCTOR_CACHE");
      if (constructorCacheField != null) {
        constructorCacheField.setAccessible(true);
        return (Cache) constructorCacheField.get(null);
      }
    } catch (Exception e) {
      System.out.println("Error when getting the CONSTRUCTOR_CACHE var: " + e);
    }
    return null;
  }

  private void invalidateReflectionUtlCache() {
    try {
      Cache constructorCache = getReflectionUtilCache();
      if (constructorCache != null) {
        constructorCache.invalidateAll();
      }
    } catch (Exception e) {
      System.out.println("Error when trying to invalidate the cache: " + e);
    }
  }

  private void cleanUpReflectionUtlCache() {
    try {
      Cache constructorCache = getReflectionUtilCache();
      if (constructorCache != null) {
        constructorCache.cleanUp();
      }
    } catch (Exception e) {
      System.out.println("Error when trying to cleanUp the cache: " + e);
    }
  }

  private long getReflectionUtilCacheSize() {
    try {
      Cache constructorCache = getReflectionUtilCache();
      if (constructorCache != null) {
        return constructorCache.size();
      }
    } catch (Exception e) {
      System.out.println(e);
    }
    return -1;
  }

  @Test
  public void testPermFunc() throws Exception {
    // This test assumes the hive-contrib JAR has been built as part of the Hive build.
    // Also dependent on the UDFExampleAdd class within that JAR.
    Path jarFilePath = getHiveContribJarPath();
    Statement stmt = conTestDb.createStatement();
    ResultSet res;
    // Add the jar file
    stmt.execute("ADD JAR " + jarFilePath.toString());

    // Register function
    String queryStr =
        "CREATE FUNCTION example_add AS '" + testUdfClassName + "' USING JAR '" + jarFilePath + "'";
    stmt.execute(queryStr);

    // Call describe
    res = stmt.executeQuery("DESCRIBE FUNCTION " + testDbName + ".example_add");
    checkForNotExist(res);

    // Use UDF in query
    res = stmt.executeQuery("SELECT example_add(1, 2) FROM " + tableName + " LIMIT 1");
    assertTrue("query has results", res.next());
    assertEquals(3, res.getInt(1));
    assertFalse("no more results", res.next());

    // A new connection should be able to call describe/use function without issue
    Connection conn2 = getConnection(testDbName);
    Statement stmt2 = conn2.createStatement();
    stmt2.execute("USE " + testDbName);
    res = stmt2.executeQuery("DESCRIBE FUNCTION " + testDbName + ".example_add");
    checkForNotExist(res);

    res =
        stmt2.executeQuery("SELECT " + testDbName + ".example_add(1, 1) FROM " + tableName
            + " LIMIT 1");
    assertTrue("query has results", res.next());
    assertEquals(2, res.getInt(1));
    assertFalse("no more results", res.next());
    conn2.close();
    stmt.execute("DROP FUNCTION IF EXISTS " + testDbName + ".example_add");
    stmt.close();
  }

  private Path getHiveContribJarPath() {
    String mvnRepo = System.getProperty("maven.local.repository");
    String hiveVersion = System.getProperty("hive.version");
    String jarFileName = "hive-contrib-" + hiveVersion + ".jar";
    String[] pathParts = {
        "org", "apache", "hive",
        "hive-contrib", hiveVersion, jarFileName
    };

    // Create path to hive-contrib JAR on local filesystem
    Path jarFilePath = new Path(mvnRepo);
    for (String pathPart : pathParts) {
      jarFilePath = new Path(jarFilePath, pathPart);
    }
    return jarFilePath;
  }

  @Test
  public void testTempTable() throws Exception {
    // Create temp table with current connection
    String tempTableName = "tmp1";
    Statement stmt = conTestDb.createStatement();
    stmt.execute("CREATE TEMPORARY TABLE " + tempTableName + " (key string, value string)");
    stmt.execute("load data local inpath '" + kvDataFilePath.toString() + "' into table "
        + tempTableName);

    String resultVal = "val_238";
    String queryStr = "SELECT * FROM " + tempTableName + " where value = '" + resultVal + "'";

    ResultSet res = stmt.executeQuery(queryStr);
    assertTrue(res.next());
    assertEquals(resultVal, res.getString(2));
    res.close();
    stmt.close();

    // Test getTables()
    DatabaseMetaData md = conTestDb.getMetaData();
    assertTrue(md.getConnection() == conTestDb);

    ResultSet rs = md.getTables(null, null, tempTableName, null);
    boolean foundTable = false;
    while (rs.next()) {
      String tableName = rs.getString(3);
      if (tableName.equalsIgnoreCase(tempTableName)) {
        assertFalse("Table not found yet", foundTable);
        foundTable = true;
      }
    }
    assertTrue("Found temp table", foundTable);

    // Test getTables() with no table name pattern
    rs = md.getTables(null, null, null, null);
    foundTable = false;
    while (rs.next()) {
      String tableName = rs.getString(3);
      if (tableName.equalsIgnoreCase(tempTableName)) {
        assertFalse("Table not found yet", foundTable);
        foundTable = true;
      }
    }
    assertTrue("Found temp table", foundTable);

    // Test getColumns()
    rs = md.getColumns(null, null, tempTableName, null);
    assertTrue("First row", rs.next());
    assertTrue(rs.getString(3).equalsIgnoreCase(tempTableName));
    assertTrue(rs.getString(4).equalsIgnoreCase("key"));
    assertEquals(Types.VARCHAR, rs.getInt(5));

    assertTrue("Second row", rs.next());
    assertTrue(rs.getString(3).equalsIgnoreCase(tempTableName));
    assertTrue(rs.getString(4).equalsIgnoreCase("value"));
    assertEquals(Types.VARCHAR, rs.getInt(5));

    // A second connection should not be able to see the table
    Connection conn2 =
        DriverManager.getConnection(miniHS2.getJdbcURL(testDbName),
            System.getProperty("user.name"), "bar");
    Statement stmt2 = conn2.createStatement();
    stmt2.execute("USE " + testDbName);
    boolean gotException = false;
    try {
      res = stmt2.executeQuery(queryStr);
    } catch (SQLException err) {
      // This is expected to fail.
      assertTrue("Expecting table not found error, instead got: " + err,
          err.getMessage().contains("Table not found"));
      gotException = true;
    }
    assertTrue("Exception while querying non-existing temp table", gotException);
    conn2.close();
  }

  private void checkForNotExist(ResultSet res) throws Exception {
    int numRows = 0;
    while (res.next()) {
      numRows++;
      String strVal = res.getString(1);
      assertEquals("Should not find 'not exist'", -1, strVal.toLowerCase().indexOf("not exist"));
    }
    assertTrue("Rows returned from describe function", numRows > 0);
  }
}
