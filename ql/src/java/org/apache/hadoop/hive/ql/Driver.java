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

package org.apache.hadoop.hive.ql;

import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.collect.ImmutableMap;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.hive.common.ValidTxnList;
import org.apache.hadoop.hive.common.metrics.common.Metrics;
import org.apache.hadoop.hive.common.metrics.common.MetricsFactory;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hadoop.hive.conf.HiveVariableSource;
import org.apache.hadoop.hive.conf.VariableSubstitution;
import org.apache.hadoop.hive.metastore.MetaStoreUtils;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.Schema;
import org.apache.hadoop.hive.ql.exec.ConditionalTask;
import org.apache.hadoop.hive.ql.exec.ExplainTask;
import org.apache.hadoop.hive.ql.exec.FetchTask;
import org.apache.hadoop.hive.ql.exec.Operator;
import org.apache.hadoop.hive.ql.exec.TableScanOperator;
import org.apache.hadoop.hive.ql.exec.Task;
import org.apache.hadoop.hive.ql.exec.TaskFactory;
import org.apache.hadoop.hive.ql.exec.TaskResult;
import org.apache.hadoop.hive.ql.exec.TaskRunner;
import org.apache.hadoop.hive.ql.exec.Utilities;
import org.apache.hadoop.hive.ql.history.HiveHistory.Keys;
import org.apache.hadoop.hive.ql.hooks.Entity;
import org.apache.hadoop.hive.ql.hooks.ExecuteWithHookContext;
import org.apache.hadoop.hive.ql.hooks.Hook;
import org.apache.hadoop.hive.ql.hooks.HookContext;
import org.apache.hadoop.hive.ql.hooks.HookUtils;
import org.apache.hadoop.hive.ql.hooks.HooksLoader;
import org.apache.hadoop.hive.ql.hooks.PostExecute;
import org.apache.hadoop.hive.ql.hooks.PreExecute;
import org.apache.hadoop.hive.ql.hooks.QueryLifeTimeHook;
import org.apache.hadoop.hive.ql.hooks.ReadEntity;
import org.apache.hadoop.hive.ql.hooks.WriteEntity;
import org.apache.hadoop.hive.ql.lockmgr.HiveLock;
import org.apache.hadoop.hive.ql.lockmgr.HiveLockMode;
import org.apache.hadoop.hive.ql.lockmgr.HiveLockObj;
import org.apache.hadoop.hive.ql.lockmgr.HiveLockObject;
import org.apache.hadoop.hive.ql.lockmgr.HiveLockObject.HiveLockObjectData;
import org.apache.hadoop.hive.ql.lockmgr.HiveTxnManager;
import org.apache.hadoop.hive.ql.lockmgr.LockException;
import org.apache.hadoop.hive.ql.log.PerfLogger;
import org.apache.hadoop.hive.ql.metadata.AuthorizationException;
import org.apache.hadoop.hive.ql.metadata.DummyPartition;
import org.apache.hadoop.hive.ql.metadata.Hive;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.metadata.Partition;
import org.apache.hadoop.hive.ql.metadata.Table;
import org.apache.hadoop.hive.ql.metadata.formatting.JsonMetaDataFormatter;
import org.apache.hadoop.hive.ql.metadata.formatting.MetaDataFormatUtils;
import org.apache.hadoop.hive.ql.metadata.formatting.MetaDataFormatter;
import org.apache.hadoop.hive.ql.optimizer.ppr.PartitionPruner;
import org.apache.hadoop.hive.ql.parse.ASTNode;
import org.apache.hadoop.hive.ql.parse.BaseSemanticAnalyzer;
import org.apache.hadoop.hive.ql.parse.ColumnAccessInfo;
import org.apache.hadoop.hive.ql.parse.HiveSemanticAnalyzerHook;
import org.apache.hadoop.hive.ql.parse.HiveSemanticAnalyzerHookContext;
import org.apache.hadoop.hive.ql.parse.HiveSemanticAnalyzerHookContextImpl;
import org.apache.hadoop.hive.ql.parse.ImportSemanticAnalyzer;
import org.apache.hadoop.hive.ql.parse.ParseContext;
import org.apache.hadoop.hive.ql.parse.ParseDriver;
import org.apache.hadoop.hive.ql.parse.ParseException;
import org.apache.hadoop.hive.ql.parse.ParseUtils;
import org.apache.hadoop.hive.ql.parse.PrunedPartitionList;
import org.apache.hadoop.hive.ql.parse.SemanticAnalyzer;
import org.apache.hadoop.hive.ql.parse.SemanticAnalyzerFactory;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.plan.FileSinkDesc;
import org.apache.hadoop.hive.ql.plan.HiveOperation;
import org.apache.hadoop.hive.ql.plan.OperatorDesc;
import org.apache.hadoop.hive.ql.plan.TableDesc;
import org.apache.hadoop.hive.ql.processors.CommandProcessor;
import org.apache.hadoop.hive.ql.processors.CommandProcessorResponse;
import org.apache.hadoop.hive.ql.security.authorization.AuthorizationUtils;
import org.apache.hadoop.hive.ql.security.authorization.HiveAuthorizationProvider;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HiveAuthzContext;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HiveOperationType;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HivePrivilegeObject;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HivePrivilegeObject.HivePrivObjectActionType;
import org.apache.hadoop.hive.ql.security.authorization.plugin.HivePrivilegeObject.HivePrivilegeObjectType;
import org.apache.hadoop.hive.ql.session.OperationLog;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hadoop.hive.ql.session.SessionState.LogHelper;
import org.apache.hadoop.hive.ql.session.YarnFairScheduling;
import org.apache.hadoop.hive.serde2.ByteStream;
import org.apache.hadoop.hive.shims.Utils;
import org.apache.hadoop.mapred.ClusterStatus;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;


public class Driver implements CommandProcessor {

  static final private String CLASS_NAME = Driver.class.getName();
  static final private Log LOG = LogFactory.getLog(CLASS_NAME);
  static final private LogHelper console = new LogHelper(LOG);
  private final QueryInfo queryInfo;

  private int maxRows = 100;
  ByteStream.Output bos = new ByteStream.Output();

  private HiveConf conf;
  private DataInput resStream;
  private Context ctx;
  private DriverContext driverCxt;
  private QueryPlan plan;
  private Schema schema;
  private String errorMessage;
  private String SQLState;
  private Throwable downstreamError;

  private FetchTask fetchTask;
  List<HiveLock> hiveLocks = new ArrayList<HiveLock>();

  // A list of FileSinkOperators writing in an ACID compliant manner
  private Set<FileSinkDesc> acidSinks;

  // A limit on the number of threads that can be launched
  private int maxthreads;
  private int tryCount = Integer.MAX_VALUE;

  private String userName;

  // For WebUI.  Kept alive after queryPlan is freed.
  private final QueryDisplay queryDisplay = new QueryDisplay();
  private LockedDriverState lDrvState = new LockedDriverState();

  // Query hooks that execute before compilation and after execution
  private QueryLifeTimeHookRunner queryLifeTimeHookRunner;
  private final HooksLoader hooksLoader;

  public enum DriverState {
    INITIALIZED,
    COMPILING,
    COMPILED,
    EXECUTING,
    EXECUTED,
    // a state that the driver enters after close() has been called to interrupt its running
    // query in the query cancellation
    INTERRUPT,
    // a state that the driver enters after close() has been called to clean the query results
    // and release the resources after the query has been executed
    CLOSED,
    // a state that the driver enters after destroy() is called and it is the end of driver life cycle
    DESTROYED,
    ERROR
  }

  public static class LockedDriverState {
    // a lock is used for synchronizing the state transition and its associated
    // resource releases
    public final ReentrantLock stateLock = new ReentrantLock();
    public DriverState driverState = DriverState.INITIALIZED;
    private static ThreadLocal<LockedDriverState> lds = new ThreadLocal<LockedDriverState>() {
      @Override
      protected LockedDriverState initialValue() {
        return new LockedDriverState();
      }
    };

    public static void setLockedDriverState(LockedDriverState lDrv) {
      lds.set(lDrv);
    }

    public static LockedDriverState getLockedDriverState() {
      return lds.get();
    }

    public static void removeLockedDriverState() {
      if (lds != null)
        lds.remove();
    }
  }

  // Query hooks that execute before compilation and after execution
  List<QueryLifeTimeHook> queryHooks;

  private boolean checkConcurrency() {
    boolean supportConcurrency = conf.getBoolVar(HiveConf.ConfVars.HIVE_SUPPORT_CONCURRENCY);
    if (!supportConcurrency) {
      LOG.info("Concurrency mode is disabled, not creating a lock manager");
      return false;
    }
    return true;
  }

  @Override
  public void init() {
    Operator.resetId();
  }

  /**
   * Return the status information about the Map-Reduce cluster
   */
  public ClusterStatus getClusterStatus() throws Exception {
    ClusterStatus cs;
    try {
      JobConf job = new JobConf(conf);
      JobClient jc = new JobClient(job);
      cs = jc.getClusterStatus();
    } catch (Exception e) {
      e.printStackTrace();
      throw e;
    }
    LOG.info("Returning cluster status: " + cs.toString());
    return cs;
  }


  public Schema getSchema() {
    return schema;
  }

  /**
   * Get a Schema with fields represented with native Hive types
   */
  public static Schema getSchema(BaseSemanticAnalyzer sem, HiveConf conf) {
    Schema schema = null;

    // If we have a plan, prefer its logical result schema if it's
    // available; otherwise, try digging out a fetch task; failing that,
    // give up.
    if (sem == null) {
      // can't get any info without a plan
    } else if (sem.getResultSchema() != null) {
      List<FieldSchema> lst = sem.getResultSchema();
      schema = new Schema(lst, null);
    } else if (sem.getFetchTask() != null) {
      FetchTask ft = sem.getFetchTask();
      TableDesc td = ft.getTblDesc();
      // partitioned tables don't have tableDesc set on the FetchTask. Instead
      // they have a list of PartitionDesc objects, each with a table desc.
      // Let's
      // try to fetch the desc for the first partition and use it's
      // deserializer.
      if (td == null && ft.getWork() != null && ft.getWork().getPartDesc() != null) {
        if (ft.getWork().getPartDesc().size() > 0) {
          td = ft.getWork().getPartDesc().get(0).getTableDesc();
        }
      }

      if (td == null) {
        LOG.info("No returning schema.");
      } else {
        String tableName = "result";
        List<FieldSchema> lst = null;
        try {
          lst = MetaStoreUtils.getFieldsFromDeserializer(tableName, td.getDeserializer(conf));
        } catch (Exception e) {
          LOG.warn("Error getting schema: "
              + org.apache.hadoop.util.StringUtils.stringifyException(e));
        }
        if (lst != null) {
          schema = new Schema(lst, null);
        }
      }
    }
    if (schema == null) {
      schema = new Schema();
    }
    LOG.info("Returning Hive schema: " + schema);
    return schema;
  }

  /**
   * Get a Schema with fields represented with Thrift DDL types
   */
  public Schema getThriftSchema() throws Exception {
    Schema schema;
    try {
      schema = getSchema();
      if (schema != null) {
        List<FieldSchema> lst = schema.getFieldSchemas();
        // Go over the schema and convert type to thrift type
        if (lst != null) {
          for (FieldSchema f : lst) {
            f.setType(MetaStoreUtils.typeToThriftType(f.getType()));
          }
        }
      }
    } catch (Exception e) {
      e.printStackTrace();
      throw e;
    }
    LOG.info("Returning Thrift schema: " + schema);
    return schema;
  }

  /**
   * Return the maximum number of rows returned by getResults
   */
  public int getMaxRows() {
    return maxRows;
  }

  /**
   * Set the maximum number of rows returned by getResults
   */
  public void setMaxRows(int maxRows) {
    this.maxRows = maxRows;
  }

  /**
   * for backwards compatibility with current tests
   */
  public Driver(HiveConf conf) {
    this(conf, new HooksLoader(conf));
  }

  public Driver(HiveConf conf, String userName) {
    this(conf, userName, new HooksLoader(conf), null);
  }

  public Driver() {
    this((SessionState.get() != null) ? SessionState.get().getConf() : null);
  }

  public Driver(HiveConf conf, HooksLoader hooksLoader) {
    this(conf, null, hooksLoader, null);
  }

  public Driver(HiveConf conf, String userName, QueryInfo queryInfo) {
     this(conf, userName, new HooksLoader(conf), queryInfo);
  }

  public Driver(HiveConf conf, String userName, HooksLoader hooksLoader, QueryInfo queryInfo) {
    this.conf = conf;
    this.userName = userName;
    this.hooksLoader = hooksLoader;
    this.queryLifeTimeHookRunner = new QueryLifeTimeHookRunner(conf, hooksLoader, console);
    this.queryInfo = queryInfo;
  }

  /**
   * Compile a new query. Any currently-planned query associated with this Driver is discarded.
   * Do not reset id for inner queries(index, etc). Task ids are used for task identity check.
   *
   * @param command
   *          The SQL query to compile.
   */
  public int compile(String command) {
    return compile(command, true);
  }

  /**
   * Hold state variables specific to each query being executed, that may not
   * be consistent in the overall SessionState
   */
  private static class QueryState {
    private HiveOperation op;
    private String cmd;
    private boolean init = false;

    /**
     * Initialize the queryState with the query state variables
     */
    public void init(HiveOperation op, String cmd) {
      this.op = op;
      this.cmd = cmd;
      this.init = true;
    }

    public boolean isInitialized() {
      return this.init;
    }

    public HiveOperation getOp() {
      return this.op;
    }

    public String getCmd() {
      return this.cmd;
    }
  }

  public void saveSession(QueryState qs) {
    SessionState oldss = SessionState.get();
    if (oldss != null && oldss.getHiveOperation() != null) {
      qs.init(oldss.getHiveOperation(), oldss.getCmd());
    }
  }

  public void restoreSession(QueryState qs) {
    SessionState ss = SessionState.get();
    if (ss != null && qs != null && qs.isInitialized()) {
      ss.setCmd(qs.getCmd());
      ss.setCommandType(qs.getOp());
    }
  }

  /**
   * Compile a new query, but potentially reset taskID counter.  Not resetting task counter
   * is useful for generating re-entrant QL queries.
   * @param command  The HiveQL query to compile
   * @param resetTaskIds Resets taskID counter if true.
   * @return 0 for ok
   */
  public int compile(String command, boolean resetTaskIds) {
    return compile(command, resetTaskIds, false);
  }

  // deferClose indicates if the close/destroy should be deferred when the process has been
  // interrupted, it should be set to true if the compile is called within another method like
  // runInternal, which defers the close to the called in that method.
  public int compile(String command, boolean resetTaskIds, boolean deferClose) {
    PerfLogger perfLogger = SessionState.getPerfLogger();
    perfLogger.PerfLogBegin(CLASS_NAME, PerfLogger.COMPILE);
    lDrvState.stateLock.lock();
    try {
      lDrvState.driverState = DriverState.COMPILING;
    } finally {
      lDrvState.stateLock.unlock();
    }

    command = new VariableSubstitution(new HiveVariableSource() {
      @Override
      public Map<String, String> getHiveVariable() {
        return SessionState.get().getHiveVariables();
      }
    }).substitute(conf, command);

    String queryStr = command;

    try {
      // command should be redacted to avoid to logging sensitive data
      queryStr = HookUtils.redactLogString(conf, command);
    } catch (Exception e) {
      LOG.warn("WARNING! Query command could not be redacted." + e);
    }

    if (isInterrupted()) {
        return handleInterruption("at beginning of compilation."); //indicate if need clean resource
    }

    //holder for parent command type/string when executing reentrant queries
    QueryState queryState = new QueryState();

    if (ctx != null) {
      closeInProcess(false);
    }

    if (resetTaskIds) {
      TaskFactory.resetId();
    }
    saveSession(queryState);

    LockedDriverState.setLockedDriverState(lDrvState);

    String queryId = conf.getVar(HiveConf.ConfVars.HIVEQUERYID);

    //save some info for webUI for use after plan is freed
    this.queryDisplay.setQueryStr(queryStr);
    this.queryDisplay.setQueryId(queryId);

    LOG.info("Compiling command(queryId=" + queryId + "): " + queryStr);

    SessionState.get().setupQueryCurrentTimestamp();

    // Whether any error occurred during query compilation. Used for query lifetime hook.
    boolean compileError = false;
    boolean parseError = false;

    try {
      if (isInterrupted()) {
        return handleInterruption("before parsing and analysing the query");
      }

      ctx = new Context(conf);
      ctx.setTryCount(getTryCount());
      ctx.setCmd(command);
      ctx.setHDFSCleanup(true);

      perfLogger.PerfLogBegin(CLASS_NAME, PerfLogger.PARSE);

      queryLifeTimeHookRunner.runBeforeParseHook(command);

      ASTNode tree;
      try {
        ParseDriver pd = new ParseDriver();
        tree = pd.parse(command, ctx);
        tree = ParseUtils.findRootNonNullToken(tree);
      } catch (ParseException e) {
        parseError = true;
        throw e;
      } finally {
        queryLifeTimeHookRunner.runAfterParseHook(command, parseError);
      }
      perfLogger.PerfLogEnd(CLASS_NAME, PerfLogger.PARSE);

      // Initialize the transaction manager.  This must be done before analyze is called.  Also
      // record the valid transactions for this query.  We have to do this at compile time
      // because we use the information in planning the query.  Also,
      // we want to record it at this point so that users see data valid at the point that they
      // submit the query.
      SessionState.get().initTxnMgr(conf);
      recordValidTxns();

      // Trigger query hook before compilation
      queryLifeTimeHookRunner.runBeforeCompileHook(command);

      perfLogger.PerfLogBegin(CLASS_NAME, PerfLogger.ANALYZE);
      BaseSemanticAnalyzer sem = SemanticAnalyzerFactory.get(conf, tree);
      List<HiveSemanticAnalyzerHook> saHooks =
          hooksLoader.getHooks(HiveConf.ConfVars.SEMANTIC_ANALYZER_HOOK, console);

      // Do semantic analysis and plan generation
      if (saHooks != null) {
        HiveSemanticAnalyzerHookContext hookCtx = new HiveSemanticAnalyzerHookContextImpl();
        hookCtx.setConf(conf);
        hookCtx.setUserName(userName);
        hookCtx.setIpAddress(SessionState.get().getUserIpAddress());
        hookCtx.setCommand(command);
        for (HiveSemanticAnalyzerHook hook : saHooks) {
          tree = hook.preAnalyze(hookCtx, tree);
        }
        sem.analyze(tree, ctx);
        hookCtx.update(sem);
        for (HiveSemanticAnalyzerHook hook : saHooks) {
          hook.postAnalyze(hookCtx, sem.getRootTasks());
        }
      } else {
        sem.analyze(tree, ctx);
      }
      // Record any ACID compliant FileSinkOperators we saw so we can add our transaction ID to
      // them later.
      acidSinks = sem.getAcidFileSinks();

      LOG.info("Semantic Analysis Completed");

      // validate the plan
      sem.validate();
      perfLogger.PerfLogEnd(CLASS_NAME, PerfLogger.ANALYZE);

      if (isInterrupted()) {
        return handleInterruption("after analyzing query.");
      }

      // get the output schema
      schema = getSchema(sem, conf);
      plan = new QueryPlan(queryStr, sem, perfLogger.getStartTime(PerfLogger.DRIVER_RUN), queryId,
        SessionState.get().getHiveOperation(), schema, queryDisplay);

      conf.setVar(HiveConf.ConfVars.HIVEQUERYSTRING, queryStr);

      conf.set("mapreduce.workflow.id", "hive_" + queryId);
      conf.set("mapreduce.workflow.name", queryStr);

      // initialize FetchTask right here
      if (plan.getFetchTask() != null) {
        plan.getFetchTask().initialize(conf, plan, null);
      }

      configureScheduling(conf, userName);

      //do the authorization check
      if (!sem.skipAuthorization() &&
          HiveConf.getBoolVar(conf, HiveConf.ConfVars.HIVE_AUTHORIZATION_ENABLED)) {

        try {
          perfLogger.PerfLogBegin(CLASS_NAME, PerfLogger.DO_AUTHORIZATION);
          doAuthorization(sem, command);
        } catch (AuthorizationException authExp) {
          console.printError("Authorization failed:" + authExp.getMessage()
              + ". Use SHOW GRANT to get more details.");
          errorMessage = authExp.getMessage();
          SQLState = "42000";
          return 403;
        } finally {
          perfLogger.PerfLogEnd(CLASS_NAME, PerfLogger.DO_AUTHORIZATION);
        }
      }

      if (conf.getBoolVar(ConfVars.HIVE_LOG_EXPLAIN_OUTPUT)) {
        String explainOutput = getExplainOutput(sem, plan, tree.dump());
        if (explainOutput != null) {
          if (conf.getBoolVar(ConfVars.HIVE_LOG_EXPLAIN_OUTPUT)) {
            LOG.info("EXPLAIN output for queryid " + queryId + " : "
              + explainOutput);
          }
          if (conf.isWebUiQueryInfoCacheEnabled()) {
            queryDisplay.setExplainPlan(explainOutput);
          }
        }
      }

      return 0;
    } catch (Exception e) {
      if (isInterrupted()) {
        return handleInterruption("during query compilation: " + e.getMessage());
      }

      compileError = true;

      ErrorMsg error = ErrorMsg.getErrorMsg(e.getMessage());
      errorMessage = "FAILED: " + e.getClass().getSimpleName();
      if (error != ErrorMsg.GENERIC_ERROR) {
        errorMessage += " [Error "  + error.getErrorCode()  + "]:";
      }

      // HIVE-4889
      if ((e instanceof IllegalArgumentException) && e.getMessage() == null && e.getCause() != null) {
        errorMessage += " " + e.getCause().getMessage();
      } else {
        errorMessage += " " + e.getMessage();
      }

      SQLState = error.getSQLState();
      downstreamError = e;
      console.printError(errorMessage, "\n"
          + org.apache.hadoop.util.StringUtils.stringifyException(e));
      return error.getErrorCode();
    } finally {

      // Trigger post compilation hook. Note that if the compilation fails here then
      // before/after execution hook will never be executed.
      if (!parseError) {
        try {
          queryLifeTimeHookRunner.runAfterCompilationHook(command, compileError);
        } catch (Exception e) {
          LOG.warn("Failed when invoking query after-compilation hook.", e);
        }
      }

      double duration = perfLogger.PerfLogEnd(CLASS_NAME, PerfLogger.COMPILE)/1000.00;
      perfLogger.PerfLogEnd(CLASS_NAME, PerfLogger.COMPILE);
      ImmutableMap<String, Long> compileHMSTimings = dumpMetaCallTimingWithoutEx("compilation");
      queryDisplay.setHmsTimings(QueryDisplay.Phase.COMPILATION, compileHMSTimings);
      restoreSession(queryState);

      boolean isInterrupted = isInterrupted();
      if (isInterrupted && !deferClose) {
        closeInProcess(true);
      }
      lDrvState.stateLock.lock();
      try {
        if (isInterrupted) {
          lDrvState.driverState = deferClose ? DriverState.EXECUTING : DriverState.ERROR;
        } else {
          lDrvState.driverState = compileError ? DriverState.ERROR : DriverState.COMPILED;
        }
      } finally {
        lDrvState.stateLock.unlock();
      }

      if (isInterrupted) {
        LOG.info("Compiling command(queryId=" + queryId + ") has been interrupted after " + duration + " seconds");
      } else {
        LOG.info("Completed compiling command(queryId=" + queryId + "); Time taken: " + duration + " seconds");
      }
    }
  }

  private int handleInterruption(String msg) {
    SQLState = "HY008";  //SQLState for cancel operation
    errorMessage = "FAILED: command has been interrupted: " + msg;
    console.printError(errorMessage);
    return 1000;
  }

  private boolean isInterrupted() {
    lDrvState.stateLock.lock();
    try {
      if (lDrvState.driverState == DriverState.INTERRUPT) {
        return true;
      } else {
        return false;
      }
    } finally {
      lDrvState.stateLock.unlock();
    }
  }

  private HiveConf configureScheduling(HiveConf configuration, String forUser) throws IOException, HiveException {
    if (configuration == null || StringUtils.isEmpty(forUser)) return configuration;
    if (YarnFairScheduling.usingNonImpersonationModeWithFairScheduling(configuration)) {
        YarnFairScheduling.validateYarnQueue(configuration, forUser);
    }

    return configuration;
  }

  private ImmutableMap<String, Long> dumpMetaCallTimingWithoutEx(String phase) {
    try {
      return Hive.get().dumpAndClearMetaCallTiming(phase);
    } catch (HiveException he) {
      LOG.warn("Caught exception attempting to write metadata call information " + he, he);
    }
    return null;
  }

  /**
   * Returns EXPLAIN EXTENDED output for a semantically
   * analyzed query.
   *
   * @param sem semantic analyzer for analyzed query
   * @param plan query plan
   * @param astStringTree AST tree dump
   * @throws java.io.IOException
   */
  private String getExplainOutput(BaseSemanticAnalyzer sem, QueryPlan plan,
      String astStringTree) throws IOException {
    String ret = null;
    ExplainTask task = new ExplainTask();
    task.initialize(conf, plan, null);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    PrintStream ps = new PrintStream(baos);
    try {
      List<Task<?>> rootTasks = sem.getRootTasks();
      task.getJSONPlan(ps, astStringTree, rootTasks, sem.getFetchTask(), false, true, true);
      ret = baos.toString();
    } catch (Exception e) {
      LOG.warn("Exception generating explain output: " + e, e);
    }

    return ret;
  }

  /**
   * Do authorization using post semantic analysis information in the semantic analyzer
   * The original command is also passed so that authorization interface can provide
   * more useful information in logs.
   * @param sem SemanticAnalyzer used to parse input query
   * @param command input query
   * @throws HiveException
   * @throws AuthorizationException
   */
  public static void doAuthorization(BaseSemanticAnalyzer sem, String command)
      throws HiveException, AuthorizationException {
    HashSet<ReadEntity> inputs = sem.getInputs();
    HashSet<WriteEntity> outputs = sem.getOutputs();
    SessionState ss = SessionState.get();
    HiveOperation op = ss.getHiveOperation();
    Hive db = sem.getDb();

    if (ss.isAuthorizationModeV2()) {
      // get mapping of tables to columns used
      ColumnAccessInfo colAccessInfo = sem.getColumnAccessInfo();
      // colAccessInfo is set only in case of SemanticAnalyzer
      Map<String, List<String>> selectTab2Cols = colAccessInfo != null ? colAccessInfo
          .getTableToColumnAccessMap() : null;
      Map<String, List<String>> updateTab2Cols = sem.getUpdateColumnAccessInfo() != null ?
          sem.getUpdateColumnAccessInfo().getTableToColumnAccessMap() : null;
      doAuthorizationV2(ss, op, inputs, outputs, command, selectTab2Cols, updateTab2Cols);
     return;
    }
    if (op == null) {
      throw new HiveException("Operation should not be null");
    }
    HiveAuthorizationProvider authorizer = ss.getAuthorizer();
    if (op.equals(HiveOperation.CREATEDATABASE)) {
      authorizer.authorize(
          op.getInputRequiredPrivileges(), op.getOutputRequiredPrivileges());
    } else if (op.equals(HiveOperation.CREATETABLE_AS_SELECT)
        || op.equals(HiveOperation.CREATETABLE)) {
      authorizer.authorize(
          db.getDatabase(SessionState.get().getCurrentDatabase()), null,
          HiveOperation.CREATETABLE_AS_SELECT.getOutputRequiredPrivileges());
    } else {
      if (op.equals(HiveOperation.IMPORT)) {
        ImportSemanticAnalyzer isa = (ImportSemanticAnalyzer) sem;
        if (!isa.existsTable()) {
          authorizer.authorize(
              db.getDatabase(SessionState.get().getCurrentDatabase()), null,
              HiveOperation.CREATETABLE_AS_SELECT.getOutputRequiredPrivileges());
        }
      }
    }
    if (outputs != null && outputs.size() > 0) {
      for (WriteEntity write : outputs) {
        if (write.isDummy() || write.isPathType()) {
          continue;
        }
        if (write.getType() == Entity.Type.DATABASE) {
          authorizer.authorize(write.getDatabase(),
              null, op.getOutputRequiredPrivileges());
          continue;
        }

        if (write.getType() == WriteEntity.Type.PARTITION) {
          Partition part = db.getPartition(write.getTable(), write
              .getPartition().getSpec(), false);
          if (part != null) {
            authorizer.authorize(write.getPartition(), null,
                    op.getOutputRequiredPrivileges());
            continue;
          }
        }

        if (write.getTable() != null) {
          authorizer.authorize(write.getTable(), null,
                  op.getOutputRequiredPrivileges());
        }
      }
    }

    if (inputs != null && inputs.size() > 0) {
      Map<Table, List<String>> tab2Cols = new HashMap<Table, List<String>>();
      Map<Partition, List<String>> part2Cols = new HashMap<Partition, List<String>>();

      //determine if partition level privileges should be checked for input tables
      Map<String, Boolean> tableUsePartLevelAuth = new HashMap<String, Boolean>();
      for (ReadEntity read : inputs) {
        if (read.isDummy() || read.isPathType() || read.getType() == Entity.Type.DATABASE) {
          continue;
        }
        Table tbl = read.getTable();
        if ((read.getPartition() != null) || (tbl != null && tbl.isPartitioned())) {
          String tblName = tbl.getTableName();
          if (tableUsePartLevelAuth.get(tblName) == null) {
            boolean usePartLevelPriv = (tbl.getParameters().get(
                "PARTITION_LEVEL_PRIVILEGE") != null && ("TRUE"
                .equalsIgnoreCase(tbl.getParameters().get(
                    "PARTITION_LEVEL_PRIVILEGE"))));
            if (usePartLevelPriv) {
              tableUsePartLevelAuth.put(tblName, Boolean.TRUE);
            } else {
              tableUsePartLevelAuth.put(tblName, Boolean.FALSE);
            }
          }
        }
      }

      // column authorization is checked through table scan operators.
      getTablePartitionUsedColumns(op, sem, tab2Cols, part2Cols, tableUsePartLevelAuth);

      // cache the results for table authorization
      Set<String> tableAuthChecked = new HashSet<String>();
      for (ReadEntity read : inputs) {
        // if read is not direct, we do not need to check its autho.
        if (read.isDummy() || read.isPathType() || !read.isDirect()) {
          continue;
        }
        if (read.getType() == Entity.Type.DATABASE) {
          authorizer.authorize(read.getDatabase(), op.getInputRequiredPrivileges(), null);
          continue;
        }
        Table tbl = read.getTable();
        if (read.getPartition() != null) {
          Partition partition = read.getPartition();
          tbl = partition.getTable();
          // use partition level authorization
          if (Boolean.TRUE.equals(tableUsePartLevelAuth.get(tbl.getTableName()))) {
            List<String> cols = part2Cols.get(partition);
            if (cols != null && cols.size() > 0) {
              authorizer.authorize(partition.getTable(),
                  partition, cols, op.getInputRequiredPrivileges(),
                  null);
            } else {
              authorizer.authorize(partition,
                  op.getInputRequiredPrivileges(), null);
            }
            continue;
          }
        }

        // if we reach here, it means it needs to do a table authorization
        // check, and the table authorization may already happened because of other
        // partitions
        if (tbl != null && !tableAuthChecked.contains(tbl.getTableName()) &&
            !(Boolean.TRUE.equals(tableUsePartLevelAuth.get(tbl.getTableName())))) {
          List<String> cols = tab2Cols.get(tbl);
          if (cols != null && cols.size() > 0) {
            authorizer.authorize(tbl, null, cols,
                op.getInputRequiredPrivileges(), null);
          } else {
            authorizer.authorize(tbl, op.getInputRequiredPrivileges(),
                null);
          }
          tableAuthChecked.add(tbl.getTableName());
        }
      }

    }
  }

  private static void getTablePartitionUsedColumns(HiveOperation op, BaseSemanticAnalyzer sem,
      Map<Table, List<String>> tab2Cols, Map<Partition, List<String>> part2Cols,
      Map<String, Boolean> tableUsePartLevelAuth) throws HiveException {
    // for a select or create-as-select query, populate the partition to column
    // (par2Cols) or
    // table to columns mapping (tab2Cols)
    if (op.equals(HiveOperation.CREATETABLE_AS_SELECT) || op.equals(HiveOperation.QUERY)) {
      SemanticAnalyzer querySem = (SemanticAnalyzer) sem;
      ParseContext parseCtx = querySem.getParseContext();

      for (Map.Entry<String, Operator<? extends OperatorDesc>> topOpMap : querySem
          .getParseContext().getTopOps().entrySet()) {
        Operator<? extends OperatorDesc> topOp = topOpMap.getValue();
        if (topOp instanceof TableScanOperator) {
          TableScanOperator tableScanOp = (TableScanOperator) topOp;
          if (!tableScanOp.isInsideView()) {
            Table tbl = tableScanOp.getConf().getTableMetadata();
            List<Integer> neededColumnIds = tableScanOp.getNeededColumnIDs();
            List<FieldSchema> columns = tbl.getCols();
            List<String> cols = new ArrayList<String>();
            for (int i = 0; i < neededColumnIds.size(); i++) {
              cols.add(columns.get(neededColumnIds.get(i)).getName());
            }
            //map may not contain all sources, since input list may have been optimized out
            //or non-existent tho such sources may still be referenced by the TableScanOperator
            //if it's null then the partition probably doesn't exist so let's use table permission
            if (tbl.isPartitioned() &&
                Boolean.TRUE.equals(tableUsePartLevelAuth.get(tbl.getTableName()))) {
              String alias_id = topOpMap.getKey();

              PrunedPartitionList partsList = PartitionPruner.prune(tableScanOp,
                  parseCtx, alias_id);
              Set<Partition> parts = partsList.getPartitions();
              for (Partition part : parts) {
                List<String> existingCols = part2Cols.get(part);
                if (existingCols == null) {
                  existingCols = new ArrayList<String>();
                }
                existingCols.addAll(cols);
                part2Cols.put(part, existingCols);
              }
            } else {
              List<String> existingCols = tab2Cols.get(tbl);
              if (existingCols == null) {
                existingCols = new ArrayList<String>();
              }
              existingCols.addAll(cols);
              tab2Cols.put(tbl, existingCols);
            }
          }
        }
      }
    }
  }

  private static void doAuthorizationV2(SessionState ss, HiveOperation op, HashSet<ReadEntity> inputs,
      HashSet<WriteEntity> outputs, String command, Map<String, List<String>> tab2cols,
      Map<String, List<String>> updateTab2Cols) throws HiveException {

    /* comment for reviewers -> updateTab2Cols needed to be separate from tab2cols because if I
    pass tab2cols to getHivePrivObjects for the output case it will trip up insert/selects,
    since the insert will get passed the columns from the select.
     */

    HiveAuthzContext.Builder authzContextBuilder = new HiveAuthzContext.Builder();
    authzContextBuilder.setUserIpAddress(ss.getUserIpAddress());
    authzContextBuilder.setCommandString(command);

    HiveOperationType hiveOpType = getHiveOperationType(op);
    List<HivePrivilegeObject> inputsHObjs = getHivePrivObjects(inputs, tab2cols);
    List<HivePrivilegeObject> outputHObjs = getHivePrivObjects(outputs, updateTab2Cols);

    ss.getAuthorizerV2().checkPrivileges(hiveOpType, inputsHObjs, outputHObjs, authzContextBuilder.build());
  }

  private static List<HivePrivilegeObject> getHivePrivObjects(
      HashSet<? extends Entity> privObjects, Map<String, List<String>> tableName2Cols) {
    List<HivePrivilegeObject> hivePrivobjs = new ArrayList<HivePrivilegeObject>();
    if(privObjects == null){
      return hivePrivobjs;
    }
    for(Entity privObject : privObjects){
      HivePrivilegeObjectType privObjType =
          AuthorizationUtils.getHivePrivilegeObjectType(privObject.getType());

      if(privObject instanceof ReadEntity && !((ReadEntity)privObject).isDirect()){
        // In case of views, the underlying views or tables are not direct dependencies
        // and are not used for authorization checks.
        // This ReadEntity represents one of the underlying tables/views, so skip it.
        // See description of the isDirect in ReadEntity
        continue;
      }
      if(privObject instanceof WriteEntity && ((WriteEntity)privObject).isTempURI()){
        //do not authorize temporary uris
        continue;
      }
      //support for authorization on partitions needs to be added
      String dbname = null;
      String objName = null;
      List<String> partKeys = null;
      List<String> columns = null;
      switch(privObject.getType()){
      case DATABASE:
        dbname = privObject.getDatabase().getName();
        break;
      case TABLE:
        dbname = privObject.getTable().getDbName();
        objName = privObject.getTable().getTableName();
        columns = tableName2Cols == null ? null :
            tableName2Cols.get(Table.getCompleteName(dbname, objName));
        break;
      case DFS_DIR:
      case LOCAL_DIR:
        objName = privObject.getD().toString();
        break;
      case FUNCTION:
        if(privObject.getDatabase() != null) {
          dbname = privObject.getDatabase().getName();
        }
        objName = privObject.getFunctionName();
        break;
      case DUMMYPARTITION:
      case PARTITION:
        // not currently handled
        continue;
        default:
          throw new AssertionError("Unexpected object type");
      }
      HivePrivObjectActionType actionType = AuthorizationUtils.getActionType(privObject);
      HivePrivilegeObject hPrivObject = new HivePrivilegeObject(privObjType, dbname, objName,
          partKeys, columns, actionType, null);
      hivePrivobjs.add(hPrivObject);
    }
    return hivePrivobjs;
  }

  private static HiveOperationType getHiveOperationType(HiveOperation op) {
    return HiveOperationType.valueOf(op.name());
  }

  /**
   * @return The current query plan associated with this Driver, if any.
   */
  public QueryPlan getPlan() {
    return plan;
  }

  /**
   * @param d
   *          The database to be locked
   * @param t
   *          The table to be locked
   * @param p
   *          The partition to be locked
   * @param mode
   *          The mode of the lock (SHARED/EXCLUSIVE) Get the list of objects to be locked. If a
   *          partition needs to be locked (in any mode), all its parents should also be locked in
   *          SHARED mode.
   */
  private List<HiveLockObj> getLockObjects(Database d, Table t, Partition p, HiveLockMode mode)
      throws SemanticException {
    List<HiveLockObj> locks = new LinkedList<HiveLockObj>();

    HiveLockObjectData lockData =
      new HiveLockObjectData(plan.getQueryId(),
                             String.valueOf(System.currentTimeMillis()),
                             "IMPLICIT",
                             plan.getQueryStr(),
                             conf);
    if (d != null) {
      locks.add(new HiveLockObj(new HiveLockObject(d.getName(), lockData), mode));
      return locks;
    }

    if (t != null) {
      locks.add(new HiveLockObj(new HiveLockObject(t.getDbName(), lockData), mode));
      locks.add(new HiveLockObj(new HiveLockObject(t, lockData), mode));
      mode = HiveLockMode.SHARED;
      locks.add(new HiveLockObj(new HiveLockObject(t.getDbName(), lockData), mode));
      return locks;
    }

    if (p != null) {
      locks.add(new HiveLockObj(new HiveLockObject(p.getTable().getDbName(), lockData), mode));
      if (!(p instanceof DummyPartition)) {
        locks.add(new HiveLockObj(new HiveLockObject(p, lockData), mode));
      }

      // All the parents are locked in shared mode
      mode = HiveLockMode.SHARED;

      // For dummy partitions, only partition name is needed
      String name = p.getName();

      if (p instanceof DummyPartition) {
        name = p.getName().split("@")[2];
      }

      String partialName = "";
      String[] partns = name.split("/");
      int len = p instanceof DummyPartition ? partns.length : partns.length - 1;
      Map<String, String> partialSpec = new LinkedHashMap<String, String>();
      for (int idx = 0; idx < len; idx++) {
        String partn = partns[idx];
        partialName += partn;
        String[] nameValue = partn.split("=");
        assert(nameValue.length == 2);
        partialSpec.put(nameValue[0], nameValue[1]);
        try {
          locks.add(new HiveLockObj(
                      new HiveLockObject(new DummyPartition(p.getTable(), p.getTable().getDbName()
                                                            + "/" + p.getTable().getTableName()
                                                            + "/" + partialName,
                                                              partialSpec), lockData), mode));
          partialName += "/";
        } catch (HiveException e) {
          throw new SemanticException(e.getMessage());
        }
      }

      locks.add(new HiveLockObj(new HiveLockObject(p.getTable(), lockData), mode));
      locks.add(new HiveLockObj(new HiveLockObject(p.getTable().getDbName(), lockData), mode));
    }

    return locks;
  }

  // Write the current set of valid transactions into the conf file so that it can be read by
  // the input format.
  private void recordValidTxns() throws LockException {
    ValidTxnList txns = SessionState.get().getTxnMgr().getValidTxns();
    String txnStr = txns.toString();
    conf.set(ValidTxnList.VALID_TXNS_KEY, txnStr);
    LOG.debug("Encoding valid txns info " + txnStr);
    // TODO I think when we switch to cross query transactions we need to keep this list in
    // session state rather than agressively encoding it in the conf like this.  We can let the
    // TableScanOperators then encode it in the conf before calling the input formats.
  }

  /**
   * Acquire read and write locks needed by the statement. The list of objects to be locked are
   * obtained from the inputs and outputs populated by the compiler. The lock acuisition scheme is
   * pretty simple. If all the locks cannot be obtained, error out. Deadlock is avoided by making
   * sure that the locks are lexicographically sorted.
   *
   * This method also records the list of valid transactions.  This must be done after any
   * transactions have been opened and locks acquired.
   **/
  private int acquireLocksAndOpenTxn() {
    PerfLogger perfLogger = SessionState.getPerfLogger();
    perfLogger.PerfLogBegin(CLASS_NAME, PerfLogger.ACQUIRE_READ_WRITE_LOCKS);

    SessionState ss = SessionState.get();
    HiveTxnManager txnMgr = ss.getTxnMgr();

    try {
      // Don't use the userName member, as it may or may not have been set.  Get the value from
      // conf, which calls into getUGI to figure out who the process is running as.
      String userFromUGI;
      try {
        userFromUGI = conf.getUser();
      } catch (IOException e) {
        errorMessage = "FAILED: Error in determining user while acquiring locks: " + e.getMessage();
        SQLState = ErrorMsg.findSQLState(e.getMessage());
        downstreamError = e;
        console.printError(errorMessage,
            "\n" + org.apache.hadoop.util.StringUtils.stringifyException(e));
        return 10;
      }
      if (acidSinks != null && acidSinks.size() > 0) {
        // We are writing to tables in an ACID compliant way, so we need to open a transaction
        long txnId = ss.getCurrentTxn();
        if (txnId == SessionState.NO_CURRENT_TXN) {
          txnId = txnMgr.openTxn(userFromUGI);
          ss.setCurrentTxn(txnId);
        }
        // Set the transaction id in all of the acid file sinks
        if (acidSinks != null) {
          for (FileSinkDesc desc : acidSinks) {
            desc.setTransactionId(txnId);
          }
        }

        // TODO Once we move to cross query transactions we need to add the open transaction to
        // our list of valid transactions.  We don't have a way to do that right now.
      }

      txnMgr.acquireLocks(plan, ctx, userFromUGI, lDrvState);
      return 0;
    } catch (Exception e) {
      errorMessage = "FAILED: Error in acquiring locks: " + e.getMessage();
      SQLState = ErrorMsg.findSQLState(e.getMessage());
      downstreamError = e;
      console.printError(errorMessage, "\n"
          + org.apache.hadoop.util.StringUtils.stringifyException(e));
      return 10;
    } finally {
      perfLogger.PerfLogEnd(CLASS_NAME, PerfLogger.ACQUIRE_READ_WRITE_LOCKS);
    }
  }

  /**
   * @param commit if there is an open transaction and if true, commit,
   *               if false rollback.  If there is no open transaction this parameter is ignored.
   *
   **/
  private void releaseLocksAndCommitOrRollback(boolean commit)
      throws LockException {
    PerfLogger perfLogger = SessionState.getPerfLogger();
    perfLogger.PerfLogBegin(CLASS_NAME, PerfLogger.RELEASE_LOCKS);

    SessionState ss = SessionState.get();
    HiveTxnManager txnMgr = ss.getTxnMgr();
    // If we've opened a transaction we need to commit or rollback rather than explicitly
    // releasing the locks.
    if (ss.getCurrentTxn() != SessionState.NO_CURRENT_TXN && ss.isAutoCommit()) {
      try {
        if (commit) {
          txnMgr.commitTxn();
        } else {
          txnMgr.rollbackTxn();
        }
      } finally {
        ss.setCurrentTxn(SessionState.NO_CURRENT_TXN);
      }
    } else {
      //since there is no tx, we only have locks for current query (if any)
      if (ctx != null && ctx.getHiveLocks() != null) {
        hiveLocks.addAll(ctx.getHiveLocks());
      }
      if (!hiveLocks.isEmpty()) {
        txnMgr.getLockManager().releaseLocks(hiveLocks);
      }
    }
    hiveLocks.clear();
    if (ctx != null) {
      ctx.setHiveLocks(null);
    }

    perfLogger.PerfLogEnd(CLASS_NAME, PerfLogger.RELEASE_LOCKS);
  }

  /**
   * Release some resources after a query is executed
   * while keeping the result around.
   */
  private void releaseResources() {
    releasePlan();
    releaseDriverContext();
    if (SessionState.get() != null) {
      SessionState.get().getLineageState().clear();
    }
  }

  @Override
  public CommandProcessorResponse run(String command)
      throws CommandNeedRetryException {
    return run(command, false);
  }

  public CommandProcessorResponse run()
      throws CommandNeedRetryException {
    return run(null, true);
  }

  public CommandProcessorResponse run(String command, boolean alreadyCompiled)
        throws CommandNeedRetryException {
    CommandProcessorResponse cpr = runInternal(command, alreadyCompiled);

    if(cpr.getResponseCode() == 0) {
      return cpr;
    }
    SessionState ss = SessionState.get();
    if(ss == null) {
      return cpr;
    }
    MetaDataFormatter mdf = MetaDataFormatUtils.getFormatter(ss.getConf());
    if(!(mdf instanceof JsonMetaDataFormatter)) {
      return cpr;
    }
    /*Here we want to encode the error in machine readable way (e.g. JSON)
     * Ideally, errorCode would always be set to a canonical error defined in ErrorMsg.
     * In practice that is rarely the case, so the messy logic below tries to tease
     * out canonical error code if it can.  Exclude stack trace from output when
     * the error is a specific/expected one.
     * It's written to stdout for backward compatibility (WebHCat consumes it).*/
    try {
      if(downstreamError == null) {
        mdf.error(ss.out, errorMessage, cpr.getResponseCode(), SQLState);
        return cpr;
      }
      ErrorMsg canonicalErr = ErrorMsg.getErrorMsg(cpr.getResponseCode());
      if(canonicalErr != null && canonicalErr != ErrorMsg.GENERIC_ERROR) {
        /*Some HiveExceptions (e.g. SemanticException) don't set
          canonical ErrorMsg explicitly, but there is logic
          (e.g. #compile()) to find an appropriate canonical error and
          return its code as error code. In this case we want to
          preserve it for downstream code to interpret*/
        mdf.error(ss.out, errorMessage, cpr.getResponseCode(), SQLState, null);
        return cpr;
      }
      if(downstreamError instanceof HiveException) {
        HiveException rc = (HiveException) downstreamError;
        mdf.error(ss.out, errorMessage,
                rc.getCanonicalErrorMsg().getErrorCode(), SQLState,
                rc.getCanonicalErrorMsg() == ErrorMsg.GENERIC_ERROR ?
                        org.apache.hadoop.util.StringUtils.stringifyException(rc)
                        : null);
      }
      else {
        ErrorMsg canonicalMsg =
                ErrorMsg.getErrorMsg(downstreamError.getMessage());
        mdf.error(ss.out, errorMessage, canonicalMsg.getErrorCode(),
                SQLState, org.apache.hadoop.util.StringUtils.
                stringifyException(downstreamError));
      }
    }
    catch(HiveException ex) {
      console.printError("Unable to JSON-encode the error",
              org.apache.hadoop.util.StringUtils.stringifyException(ex));
    }
    return cpr;
  }

  public CommandProcessorResponse compileAndRespond(String command) {
    return createProcessorResponse(compileInternal(command, false));
  }

  private static final ReentrantLock globalCompileLock = new ReentrantLock();
  private int compileInternal(String command, boolean deferClose) {
    int ret;
    LOG.debug("Acquire a monitor for compiling query");
    final ReentrantLock compileLock = tryAcquireCompileLock(command);
    if (compileLock == null) {
      return ErrorMsg.COMPILE_LOCK_TIMED_OUT.getErrorCode();
    }

    try {
      ret = compile(command, true, deferClose);
    } finally {
      compileLock.unlock();
    }

    if (ret != 0) {
      try {
        releaseLocksAndCommitOrRollback(false);
      } catch (LockException e) {
        LOG.warn("Exception in releasing locks. "
            + org.apache.hadoop.util.StringUtils.stringifyException(e));
      }
    }

    //Save compile-time PerfLogging for WebUI.
    //Execution-time Perf logs are done by either another thread's PerfLogger
    //or a reset PerfLogger.
    PerfLogger perfLogger = SessionState.getPerfLogger();
    queryDisplay.setPerfLogStarts(QueryDisplay.Phase.COMPILATION, perfLogger.getStartTimes());
    queryDisplay.setPerfLogEnds(QueryDisplay.Phase.COMPILATION, perfLogger.getEndTimes());
    return ret;
  }

  /**
   * Acquires the compile lock. If the compile lock wait timeout is configured,
   * it will acquire the lock if it is not held by another thread within the given
   * waiting time.
   * @return the ReentrantLock object if the lock was successfully acquired,
   *         or {@code null} if compile lock wait timeout is configured and
   *         either the waiting time elapsed before the lock could be acquired
   *         or if the current thread is interrupted.
   */
  private ReentrantLock tryAcquireCompileLock(String command) {
    long maxCompileLockWaitTime = HiveConf.getTimeVar(
      this.conf, ConfVars.HIVE_SERVER2_COMPILE_LOCK_TIMEOUT,
      TimeUnit.SECONDS);
    if (maxCompileLockWaitTime > 0) {
      try {
        if (LOG.isDebugEnabled()) {
          LOG.debug("Waiting to acquire compile lock: " + command);
        }
        if(!globalCompileLock.tryLock(maxCompileLockWaitTime, TimeUnit.SECONDS)) {
          errorMessage = ErrorMsg.COMPILE_LOCK_TIMED_OUT.getErrorCodedMsg();
          LOG.error(errorMessage + ": " + command);
          return null;
        }
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        if (LOG.isDebugEnabled()) {
          LOG.debug("Interrupted Exception ignored", e);
        }
        return null;
      }
    } else {
      globalCompileLock.lock();
    }

    return globalCompileLock;
  }

  private CommandProcessorResponse runInternal(String command, boolean alreadyCompiled)
      throws CommandNeedRetryException {
    errorMessage = null;
    SQLState = null;
    downstreamError = null;
    if (!validateConfVariables()) {
      return createProcessorResponse(12);
    }
    LockedDriverState.setLockedDriverState(lDrvState);

    lDrvState.stateLock.lock();
    try {
      if (alreadyCompiled) {
        if (lDrvState.driverState == DriverState.COMPILED) {
          lDrvState.driverState = DriverState.EXECUTING;
        } else {
          errorMessage = "FAILED: Precompiled query has been cancelled or closed.";
          console.printError(errorMessage);
          return createProcessorResponse(12);
        }
      } else {
        lDrvState.driverState = DriverState.COMPILING;
      }
    } finally {
      lDrvState.stateLock.unlock();
    }

    // a flag that helps to set the correct driver state in finally block by tracking if
    // the method has been returned by an error or not.
    boolean isFinishedWithError = true;
    try {
      HiveDriverRunHookContext hookContext = new HiveDriverRunHookContextImpl(conf,
          alreadyCompiled ? ctx.getCmd() : command);
      // Get all the driver run hooks and pre-execute them.
      List<HiveDriverRunHook> driverRunHooks;
      try {
        driverRunHooks = hooksLoader.getHooks(HiveConf.ConfVars.HIVE_DRIVER_RUN_HOOKS, console);
        for (HiveDriverRunHook driverRunHook : driverRunHooks) {
            driverRunHook.preDriverRun(hookContext);
        }
      } catch (Exception e) {
        errorMessage = "FAILED: Hive Internal Error: " + Utilities.getNameMessage(e);
        SQLState = ErrorMsg.findSQLState(e.getMessage());
        downstreamError = e;
        console.printError(errorMessage + "\n"
            + org.apache.hadoop.util.StringUtils.stringifyException(e));
        return createProcessorResponse(12);
      }

      // Reset the perf logger
      PerfLogger perfLogger = SessionState.getPerfLogger(true);
      perfLogger.PerfLogBegin(CLASS_NAME, PerfLogger.DRIVER_RUN);
      perfLogger.PerfLogBegin(CLASS_NAME, PerfLogger.TIME_TO_SUBMIT);

      int ret;
      if (!alreadyCompiled) {
        // compile internal will automatically reset the perf logger
        ret = compileInternal(command, true);
        if (ret != 0) {
          return createProcessorResponse(ret);
        }
      }

      // the reason that we set the txn manager for the cxt here is because each
      // query has its own ctx object. The txn mgr is shared across the
      // same instance of Driver, which can run multiple queries.
      ctx.setHiveTxnManager(SessionState.get().getTxnMgr());

      if (requiresLock()) {
        // a checkpoint to see if the thread is interrupted or not before an expensive operation
        if (isInterrupted()) {
          ret = handleInterruption("at acquiring the lock.");
        } else {
          ret = acquireLocksAndOpenTxn();
        }
        if (ret != 0) {
          try {
            releaseLocksAndCommitOrRollback(false);
          } catch (LockException e) {
            // Not much to do here
          }
          return createProcessorResponse(ret);
        }
      }

      ret = execute(true);
      if (ret != 0) {
	    //if needRequireLock is false, the release here will do nothing because there is no lock
	    try {
	      releaseLocksAndCommitOrRollback(false);
	    } catch (LockException e) {
	      // Nothing to do here
	    }
	    return createProcessorResponse(ret);
      }

      //if needRequireLock is false, the release here will do nothing because there is no lock
      try {
        releaseLocksAndCommitOrRollback(true);
      } catch (LockException e) {
        errorMessage = "FAILED: Hive Internal Error: " + Utilities.getNameMessage(e);
        SQLState = ErrorMsg.findSQLState(e.getMessage());
        downstreamError = e;
        console.printError(errorMessage + "\n"
            + org.apache.hadoop.util.StringUtils.stringifyException(e));
        return createProcessorResponse(12);
      }

      perfLogger.PerfLogEnd(CLASS_NAME, PerfLogger.DRIVER_RUN);
      queryDisplay.setPerfLogStarts(QueryDisplay.Phase.EXECUTION, perfLogger.getStartTimes());
      queryDisplay.setPerfLogEnds(QueryDisplay.Phase.EXECUTION, perfLogger.getEndTimes());

      // Take all the driver run hooks and post-execute them.
      try {
        for (HiveDriverRunHook driverRunHook : driverRunHooks) {
            driverRunHook.postDriverRun(hookContext);
        }
      } catch (Exception e) {
        errorMessage = "FAILED: Hive Internal Error: " + Utilities.getNameMessage(e);
        SQLState = ErrorMsg.findSQLState(e.getMessage());
        downstreamError = e;
        console.printError(errorMessage + "\n"
            + org.apache.hadoop.util.StringUtils.stringifyException(e));
        return createProcessorResponse(12);
      }
      isFinishedWithError = false;
      return createProcessorResponse(ret);
    } finally {
      if (isInterrupted()) {
        closeInProcess(true);
      } else {
        // only release the related resources ctx, driverContext as normal
        releaseResources();
      }
      lDrvState.stateLock.lock();
      try {
        if (lDrvState.driverState == DriverState.INTERRUPT) {
          lDrvState.driverState = DriverState.ERROR;
        } else {
          lDrvState.driverState = isFinishedWithError ? DriverState.ERROR : DriverState.EXECUTED;
        }
      } finally {
        lDrvState.stateLock.unlock();
      }
    }
  }

  private boolean requiresLock() {
    if (!checkConcurrency()) {
      return false;
    }
    if (!HiveConf.getBoolVar(conf, ConfVars.HIVE_LOCK_MAPRED_ONLY)) {
      return true;
    }
    Queue<Task<? extends Serializable>> taskQueue = new LinkedList<Task<? extends Serializable>>();
    taskQueue.addAll(plan.getRootTasks());
    while (taskQueue.peek() != null) {
      Task<? extends Serializable> tsk = taskQueue.remove();
      if (tsk.requireLock()) {
        return true;
      }
      if (tsk instanceof ConditionalTask) {
        taskQueue.addAll(((ConditionalTask)tsk).getListTasks());
      }
      if (tsk.getChildTasks()!= null) {
        taskQueue.addAll(tsk.getChildTasks());
      }
      // does not add back up task here, because back up task should be the same
      // type of the original task.
    }
    return false;
  }

  private CommandProcessorResponse createProcessorResponse(int ret) {
    SessionState.getPerfLogger().cleanupPerfLogMetrics();
    queryDisplay.setErrorMessage(errorMessage);
    return new CommandProcessorResponse(ret, errorMessage, SQLState, downstreamError);
  }

  /**
   * Validate configuration variables.
   *
   * @return
   */
  private boolean validateConfVariables() {
    boolean valid = true;
    if ((!conf.getBoolVar(HiveConf.ConfVars.HIVE_HADOOP_SUPPORTS_SUBDIRECTORIES))
        && ((conf.getBoolVar(HiveConf.ConfVars.HADOOPMAPREDINPUTDIRRECURSIVE)) || (conf
              .getBoolVar(HiveConf.ConfVars.HIVEOPTLISTBUCKETING)) || ((conf
                  .getBoolVar(HiveConf.ConfVars.HIVE_OPTIMIZE_UNION_REMOVE))))) {
      errorMessage = "FAILED: Hive Internal Error: "
          + ErrorMsg.SUPPORT_DIR_MUST_TRUE_FOR_LIST_BUCKETING.getMsg();
      SQLState = ErrorMsg.SUPPORT_DIR_MUST_TRUE_FOR_LIST_BUCKETING.getSQLState();
      console.printError(errorMessage + "\n");
      valid = false;
    }
    return valid;
  }

  public int execute() throws CommandNeedRetryException {
    return execute(false);
  }

  public int execute(boolean deferClose) throws CommandNeedRetryException {
    PerfLogger perfLogger = SessionState.getPerfLogger();
    perfLogger.PerfLogBegin(CLASS_NAME, PerfLogger.DRIVER_EXECUTE);
    boolean noName = StringUtils.isEmpty(conf.getVar(HiveConf.ConfVars.HADOOPJOBNAME));
    int maxlen = conf.getIntVar(HiveConf.ConfVars.HIVEJOBNAMELENGTH);

    String queryId = conf.getVar(HiveConf.ConfVars.HIVEQUERYID);
    // Get the query string from the conf file as the compileInternal() method might
    // hide sensitive information during query redaction.
    String queryStr = HiveConf.getVar(conf, HiveConf.ConfVars.HIVEQUERYSTRING);

    lDrvState.stateLock.lock();
    try {
      // if query is not in compiled state, or executing state which is carried over from
      // a combined compile/execute in runInternal, throws the error
      if (lDrvState.driverState != DriverState.COMPILED &&
          lDrvState.driverState != DriverState.EXECUTING) {
        SQLState = "HY008";
        errorMessage = "FAILED: query " + queryStr + " has " +
            (lDrvState.driverState == DriverState.INTERRUPT ? "been cancelled" : "not been compiled.");
        console.printError(errorMessage);
        return 1000;
      } else {
        lDrvState.driverState = DriverState.EXECUTING;
      }
    } finally {
      lDrvState.stateLock.unlock();
    }

    maxthreads = HiveConf.getIntVar(conf, HiveConf.ConfVars.EXECPARALLETHREADNUMBER);

    HookContext hookContext = null;

    // Whether there's any error occurred during query execution. Used for query lifetime hook.
    boolean executionError = false;

    try {
      LOG.info("Executing command(queryId=" + queryId + "): " + queryStr);
      // compile and execute can get called from different threads in case of HS2
      // so clear timing in this thread's Hive object before proceeding.
      Hive.get().clearMetaCallTiming();

      plan.setStarted();

      if (SessionState.get() != null) {
        SessionState.get().getHiveHistory().startQuery(queryStr,
            conf.getVar(HiveConf.ConfVars.HIVEQUERYID));
        SessionState.get().getHiveHistory().logPlanProgress(plan);
      }
      resStream = null;

      SessionState ss = SessionState.get();
      hookContext = new HookContext(plan, conf, ctx.getPathToCS(), ss.getUserName(), ss.getUserIpAddress(), queryInfo);
      hookContext.setHookType(HookContext.HookType.PRE_EXEC_HOOK);

      for (Hook peh : hooksLoader.getHooks(HiveConf.ConfVars.PREEXECHOOKS, console)) {
        if (peh instanceof ExecuteWithHookContext) {
          perfLogger.PerfLogBegin(CLASS_NAME, PerfLogger.PRE_HOOK + peh.getClass().getName());

          ((ExecuteWithHookContext) peh).run(hookContext);

          perfLogger.PerfLogEnd(CLASS_NAME, PerfLogger.PRE_HOOK + peh.getClass().getName());
        } else if (peh instanceof PreExecute) {
          perfLogger.PerfLogBegin(CLASS_NAME, PerfLogger.PRE_HOOK + peh.getClass().getName());

          ((PreExecute) peh).run(SessionState.get(), plan.getInputs(), plan.getOutputs(),
              Utils.getUGI());

          perfLogger.PerfLogEnd(CLASS_NAME, PerfLogger.PRE_HOOK + peh.getClass().getName());
        }
      }

      // Trigger query hooks before query execution.
      queryLifeTimeHookRunner.runBeforeExecutionHook(queryStr, hookContext);

      int jobs = Utilities.getMRTasks(plan.getRootTasks()).size()
        + Utilities.getTezTasks(plan.getRootTasks()).size()
        + Utilities.getSparkTasks(plan.getRootTasks()).size();
      if (jobs > 0) {
        console.printInfo("Query ID = " + plan.getQueryId());
        console.printInfo("Total jobs = " + jobs);
      }
      if (SessionState.get() != null) {
        SessionState.get().getHiveHistory().setQueryProperty(queryId, Keys.QUERY_NUM_TASKS,
            String.valueOf(jobs));
        SessionState.get().getHiveHistory().setIdToTableMap(plan.getIdToTableNameMap());
      }
      String jobname = Utilities.abbreviate(queryStr, maxlen - 6);

      // A runtime that launches runnable tasks as separate Threads through
      // TaskRunners
      // As soon as a task isRunnable, it is put in a queue
      // At any time, at most maxthreads tasks can be running
      // The main thread polls the TaskRunners to check if they have finished.

      if (isInterrupted()) {
        return handleInterruption("before running tasks.");
      }
      DriverContext driverCxt = new DriverContext(ctx);
      driverCxt.prepare(plan);

      ctx.setHDFSCleanup(true);
      this.driverCxt = driverCxt; // for canceling the query (should be bound to session?)

      SessionState.get().setMapRedStats(new LinkedHashMap<String, MapRedStats>());
      SessionState.get().setStackTraces(new HashMap<String, List<List<String>>>());
      SessionState.get().setLocalMapRedErrors(new HashMap<String, List<String>>());

      // Add root Tasks to runnable
      for (Task<? extends Serializable> tsk : plan.getRootTasks()) {
        // This should never happen, if it does, it's a bug with the potential to produce
        // incorrect results.
        assert tsk.getParentTasks() == null || tsk.getParentTasks().isEmpty();
        driverCxt.addToRunnable(tsk);

        Metrics metrics = MetricsFactory.getInstance();
        if (metrics != null) {
          tsk.updateTaskMetrics(metrics);
        }
      }

      perfLogger.PerfLogEnd(CLASS_NAME, PerfLogger.TIME_TO_SUBMIT);
      perfLogger.PerfLogBegin(CLASS_NAME, PerfLogger.RUN_TASKS);
      // Loop while you either have tasks running, or tasks queued up
      while (driverCxt.isRunning()) {

        // Launch upto maxthreads tasks
        Task<? extends Serializable> task;
        while ((task = driverCxt.getRunnable(maxthreads)) != null) {
          TaskRunner runner = launchTask(task, queryId, noName, jobname, jobs, driverCxt);
          if (!runner.isRunning()) {
            break;
          }
        }

        // poll the Tasks to see which one completed
        TaskRunner tskRun = driverCxt.pollFinished();
        if (tskRun == null) {
          continue;
        }
        hookContext.addCompleteTask(tskRun);
        queryDisplay.setTaskResult(tskRun.getTask().getId(), tskRun.getTaskResult());

        Task<? extends Serializable> tsk = tskRun.getTask();
        TaskResult result = tskRun.getTaskResult();

        int exitVal = result.getExitVal();
        if (isInterrupted()) {
          return handleInterruption("when checking the execution result.");
        }
        if (exitVal != 0) {
          if (tsk.ifRetryCmdWhenFail()) {
            driverCxt.shutdown();
            // in case we decided to run everything in local mode, restore the
            // the jobtracker setting to its initial value
            ctx.restoreOriginalTracker();
            throw new CommandNeedRetryException();
          }
          Task<? extends Serializable> backupTask = tsk.getAndInitBackupTask();
          if (backupTask != null) {
            setErrorMsgAndDetail(exitVal, result.getTaskError(), tsk);
            console.printError(errorMessage);
            errorMessage = "ATTEMPT: Execute BackupTask: " + backupTask.getClass().getName();
            console.printError(errorMessage);

            // add backup task to runnable
            if (DriverContext.isLaunchable(backupTask)) {
              driverCxt.addToRunnable(backupTask);
            }
            continue;

          } else {
            hookContext.setHookType(HookContext.HookType.ON_FAILURE_HOOK);
            // Get all the failure execution hooks and execute them.
            for (Hook ofh : hooksLoader.getHooks(HiveConf.ConfVars.ONFAILUREHOOKS)) {
              perfLogger.PerfLogBegin(CLASS_NAME, PerfLogger.FAILURE_HOOK + ofh.getClass().getName());

              ((ExecuteWithHookContext) ofh).run(hookContext);

              perfLogger.PerfLogEnd(CLASS_NAME, PerfLogger.FAILURE_HOOK + ofh.getClass().getName());
            }
            setErrorMsgAndDetail(exitVal, result.getTaskError(), tsk);
            SQLState = "08S01";
            console.printError(errorMessage);
            driverCxt.shutdown();
            // in case we decided to run everything in local mode, restore the
            // the jobtracker setting to its initial value
            ctx.restoreOriginalTracker();
            return exitVal;
          }
        }

        driverCxt.finished(tskRun);

        if (SessionState.get() != null) {
          SessionState.get().getHiveHistory().setTaskProperty(queryId, tsk.getId(),
              Keys.TASK_RET_CODE, String.valueOf(exitVal));
          SessionState.get().getHiveHistory().endTask(queryId, tsk);
        }

        if (tsk.getChildTasks() != null) {
          for (Task<? extends Serializable> child : tsk.getChildTasks()) {
            if (DriverContext.isLaunchable(child)) {
              driverCxt.addToRunnable(child);
            }
          }
        }
      }
      perfLogger.PerfLogEnd(CLASS_NAME, PerfLogger.RUN_TASKS);

      // in case we decided to run everything in local mode, restore the
      // the jobtracker setting to its initial value
      ctx.restoreOriginalTracker();

      if (driverCxt.isShutdown()) {
        SQLState = "HY008";
        errorMessage = "FAILED: Operation cancelled";
        console.printError(errorMessage);
        return 1000;
      }

      // remove incomplete outputs.
      // Some incomplete outputs may be added at the beginning, for eg: for dynamic partitions.
      // remove them
      HashSet<WriteEntity> remOutputs = new LinkedHashSet<WriteEntity>();
      for (WriteEntity output : plan.getOutputs()) {
        if (!output.isComplete()) {
          remOutputs.add(output);
        }
      }

      for (WriteEntity output : remOutputs) {
        plan.getOutputs().remove(output);
      }

      hookContext.setHookType(HookContext.HookType.POST_EXEC_HOOK);
      // Get all the post execution hooks and execute them.
      for (Hook peh : hooksLoader.getHooks(HiveConf.ConfVars.POSTEXECHOOKS, console)) {
        if (peh instanceof ExecuteWithHookContext) {
          perfLogger.PerfLogBegin(CLASS_NAME, PerfLogger.POST_HOOK + peh.getClass().getName());

          ((ExecuteWithHookContext) peh).run(hookContext);

          perfLogger.PerfLogEnd(CLASS_NAME, PerfLogger.POST_HOOK + peh.getClass().getName());
        } else if (peh instanceof PostExecute) {
          perfLogger.PerfLogBegin(CLASS_NAME, PerfLogger.POST_HOOK + peh.getClass().getName());

          ((PostExecute) peh).run(SessionState.get(), plan.getInputs(), plan.getOutputs(),
              (SessionState.get() != null ? SessionState.get().getLineageState().getLineageInfo()
                  : null), Utils.getUGI());

          perfLogger.PerfLogEnd(CLASS_NAME, PerfLogger.POST_HOOK + peh.getClass().getName());
        }
      }

      if (SessionState.get() != null) {
        SessionState.get().getHiveHistory().setQueryProperty(queryId, Keys.QUERY_RET_CODE,
            String.valueOf(0));
        SessionState.get().getHiveHistory().printRowCount(queryId);
      }
      releasePlan(plan);
    } catch (CommandNeedRetryException e) {
      executionError = true;
      throw e;
    } catch (Exception e) {
      executionError = true;
      if (isInterrupted()) {
        return handleInterruption("during query execution: \n" + e.getMessage());
      }
      ctx.restoreOriginalTracker();
      if (SessionState.get() != null) {
        SessionState.get().getHiveHistory().setQueryProperty(queryId, Keys.QUERY_RET_CODE,
            String.valueOf(12));
      }
      // TODO: do better with handling types of Exception here
      errorMessage = "FAILED: Hive Internal Error: " + Utilities.getNameMessage(e);
      SQLState = "08S01";
      downstreamError = e;
      console.printError(errorMessage + "\n"
          + org.apache.hadoop.util.StringUtils.stringifyException(e));
      return (12);
    } finally {
      // Trigger query hooks after query completes its execution.
      try {
        queryLifeTimeHookRunner.runAfterExecutionHook(queryStr, hookContext, executionError);
      } catch (Exception e) {
        LOG.warn("Failed when invoking query after execution hook", e);
      }

      if (SessionState.get() != null) {
        SessionState.get().getHiveHistory().endQuery(queryId);
      }
      if (noName) {
        conf.setVar(HiveConf.ConfVars.HADOOPJOBNAME, "");
      }
      double duration = perfLogger.PerfLogEnd(CLASS_NAME, PerfLogger.DRIVER_EXECUTE)/1000.00;
      ImmutableMap<String, Long> executionHMSTimings = dumpMetaCallTimingWithoutEx("execution");
      queryDisplay.setHmsTimings(QueryDisplay.Phase.EXECUTION, executionHMSTimings);

      Map<String, MapRedStats> stats = SessionState.get().getMapRedStats();
      if (stats != null && !stats.isEmpty()) {
        long totalCpu = 0;
        console.printInfo("MapReduce Jobs Launched: ");
        for (Map.Entry<String, MapRedStats> entry : stats.entrySet()) {
          console.printInfo("Stage-" + entry.getKey() + ": " + entry.getValue());
          totalCpu += entry.getValue().getCpuMSec();
        }
        console.printInfo("Total MapReduce CPU Time Spent: " + Utilities.formatMsecToStr(totalCpu));
      }
      boolean isInterrupted = isInterrupted();
      if (isInterrupted && !deferClose) {
        closeInProcess(true);
      }
      lDrvState.stateLock.lock();
      try {
        if (isInterrupted) {
          if (!deferClose) {
            lDrvState.driverState = DriverState.ERROR;
          }
        } else {
          lDrvState.driverState = executionError ? DriverState.ERROR : DriverState.EXECUTED;
        }
      } finally {
        lDrvState.stateLock.unlock();
      }
      if (isInterrupted) {
        LOG.info("Executing command(queryId=" + queryId + ") has been interrupted after " + duration + " seconds");
      } else {
        LOG.info("Completed executing command(queryId=" + queryId + "); Time taken: " + duration + " seconds");
      }
    }

    if (console != null) {
      console.printInfo("OK");
    }

    return (0);
  }

  private void releasePlan(QueryPlan plan) {
    // Plan maybe null if Driver.close is called in another thread for the same Driver object
    lDrvState.stateLock.lock();
    try {
      if (plan != null) {
        plan.setDone();
        if (SessionState.get() != null) {
          try {
            SessionState.get().getHiveHistory().logPlanProgress(plan);
          } catch (Exception e) {
            // Log and ignore
            LOG.warn("Could not log query plan progress", e);
          }
        }
      }
    } finally {
      lDrvState.stateLock.unlock();
    }
  }

  private void setErrorMsgAndDetail(int exitVal, Throwable downstreamError, Task tsk) {
    this.downstreamError = downstreamError;
    errorMessage = "FAILED: Execution Error, return code " + exitVal + " from " + tsk.getClass().getName();
    if(downstreamError != null) {
      //here we assume that upstream code may have parametrized the msg from ErrorMsg
      //so we want to keep it
      errorMessage += ". " + downstreamError.getMessage();
    }
    else {
      ErrorMsg em = ErrorMsg.getErrorMsg(exitVal);
      if (em != null) {
        errorMessage += ". " +  em.getMsg();
      }
    }
  }

  /**
   * Launches a new task
   *
   * @param tsk
   *          task being launched
   * @param queryId
   *          Id of the query containing the task
   * @param noName
   *          whether the task has a name set
   * @param jobname
   *          name of the task, if it is a map-reduce job
   * @param jobs
   *          number of map-reduce jobs
   * @param cxt
   *          the driver context
   */
  private TaskRunner launchTask(Task<? extends Serializable> tsk, String queryId, boolean noName,
      String jobname, int jobs, DriverContext cxt) throws HiveException {
    if (SessionState.get() != null) {
      SessionState.get().getHiveHistory().startTask(queryId, tsk, tsk.getClass().getName());
    }
    if (tsk.isMapRedTask() && !(tsk instanceof ConditionalTask)) {
      if (noName) {
        conf.setVar(HiveConf.ConfVars.HADOOPJOBNAME, jobname + "(" + tsk.getId() + ")");
      }
      conf.set("mapreduce.workflow.node.name", tsk.getId());
      Utilities.setWorkflowAdjacencies(conf, plan);
      cxt.incCurJobNo(1);
      console.printInfo("Launching Job " + cxt.getCurJobNo() + " out of " + jobs);
    }
    tsk.initialize(conf, plan, cxt);
    TaskResult tskRes = new TaskResult();
    TaskRunner tskRun = new TaskRunner(tsk, tskRes);

    cxt.launching(tskRun);
    // Launch Task
    if (HiveConf.getBoolVar(conf, HiveConf.ConfVars.EXECPARALLEL) && tsk.isMapRedTask()) {
      // Launch it in the parallel mode, as a separate thread only for MR tasks
      if (LOG.isInfoEnabled()){
        LOG.info("Starting task [" + tsk + "] in parallel");
      }
      tskRun.setOperationLog(OperationLog.getCurrentOperationLog());
      tskRun.start();
    } else {
      if (LOG.isInfoEnabled()){
        LOG.info("Starting task [" + tsk + "] in serial mode");
      }
      tskRun.runSequential();
    }
    return tskRun;
  }

  public boolean isFetchingTable() {
    return fetchTask != null;
  }

  @SuppressWarnings("unchecked")
  public boolean getResults(List res) throws IOException, CommandNeedRetryException {
    if (lDrvState.driverState == DriverState.DESTROYED || lDrvState.driverState == DriverState.CLOSED) {
      throw new IOException("FAILED: query has been cancelled, closed, or destroyed.");
    }

    if (isFetchingTable()) {
      fetchTask.setMaxRows(maxRows);
      return fetchTask.fetch(res);
    }

    if (resStream == null) {
      resStream = ctx.getStream();
    }
    if (resStream == null) {
      return false;
    }

    int numRows = 0;
    String row = null;

    while (numRows < maxRows) {
      if (resStream == null) {
        if (numRows > 0) {
          return true;
        } else {
          return false;
        }
      }

      bos.reset();
      Utilities.StreamStatus ss;
      try {
        ss = Utilities.readColumn(resStream, bos);
        if (bos.getLength() > 0) {
          row = new String(bos.getData(), 0, bos.getLength(), "UTF-8");
        } else if (ss == Utilities.StreamStatus.TERMINATED) {
          row = new String();
        }

        if (row != null) {
          numRows++;
          res.add(row);
        }
        row = null;
      } catch (IOException e) {
        console.printError("FAILED: Unexpected IO exception : " + e.getMessage());
        return false;
      }

      if (ss == Utilities.StreamStatus.EOF) {
        resStream = ctx.getStream();
      }
    }
    return true;
  }

  public void resetFetch() throws IOException {
    if (lDrvState.driverState == DriverState.DESTROYED || lDrvState.driverState == DriverState.CLOSED) {
      throw new IOException("FAILED: driver has been cancelled, closed or destroyed.");
    }
    if (isFetchingTable()) {
      try {
        fetchTask.clearFetch();
      } catch (Exception e) {
        throw new IOException("Error closing the current fetch task", e);
      }
      // FetchTask should not depend on the plan.
      fetchTask.initialize(conf, null, null);
    } else {
      ctx.resetStream();
      resStream = null;
    }
  }

  public int getTryCount() {
    return tryCount;
  }

  public void setTryCount(int tryCount) {
    this.tryCount = tryCount;
  }

  // DriverContext could be released in the query and close processes at same
  // time, which needs to be thread protected.
  private void releaseDriverContext() {
    lDrvState.stateLock.lock();
    try {
      if (driverCxt != null) {
        driverCxt.shutdown();
        driverCxt = null;
      }
    } catch (Exception e) {
      LOG.debug("Exception while shutting down the task runner", e);
    } finally {
      lDrvState.stateLock.unlock();
    }
  }

  private void releasePlan() {
    try {
      if (plan != null) {
        fetchTask = plan.getFetchTask();
        if (fetchTask != null) {
          fetchTask.setDriverContext(null);
          fetchTask.setQueryPlan(null);
        }
      }
      plan = null;
    } catch (Exception e) {
      LOG.debug("Exception while clearing the Fetch task", e);
    }
  }

  private void releaseContext() {
    try {
      if (ctx != null) {
        ctx.clear();
        if (ctx.getHiveLocks() != null) {
          hiveLocks.addAll(ctx.getHiveLocks());
          ctx.setHiveLocks(null);
        }
        ctx = null;
      }
    } catch (Exception e) {
      LOG.debug("Exception while clearing the context ", e);
    }
  }

  private void releaseResStream() {
    try {
      if (resStream != null) {
        ((FSDataInputStream) resStream).close();
        resStream = null;
      }
    } catch (Exception e) {
      LOG.debug(" Exception while closing the resStream ", e);
    }
  }

  private void releaseFetchTask() {
    try {
      if (fetchTask != null) {
        fetchTask.clearFetch();
        fetchTask = null;
      }
    } catch (Exception e) {
      LOG.debug(" Exception while clearing the FetchTask ", e);
    }
  }
  // Close and release resources within a running query process. Since it runs under
  // driver state COMPILING, EXECUTING or INTERRUPT, it would not have race condition
  // with the releases probably running in the other closing thread.
  private int closeInProcess(boolean destroyed) {
    releaseDriverContext();
    releasePlan();
    releaseFetchTask();
    releaseResStream();
    releaseContext();
    if (SessionState.get() != null) {
      SessionState.get().getLineageState().clear();
    }
    if(destroyed) {
      if (!hiveLocks.isEmpty()) {
        try {
          releaseLocksAndCommitOrRollback(false);
        } catch (LockException e) {
          LOG.warn("Exception when releasing locking in destroy: " +
              e.getMessage());
        }
      }
    }
    return 0;
  }

  // is called to stop the query if it is running, clean query results, and release resources.
  public int close() {
    lDrvState.stateLock.lock();
    try {
      releaseDriverContext();
      if (lDrvState.driverState == DriverState.COMPILING ||
          lDrvState.driverState == DriverState.EXECUTING ||
          lDrvState.driverState == DriverState.INTERRUPT) {
        lDrvState.driverState = DriverState.INTERRUPT;
        return 0;
      }
      releasePlan();
      releaseFetchTask();
      releaseResStream();
      releaseContext();
      lDrvState.driverState = DriverState.CLOSED;
    } finally {
      lDrvState.stateLock.unlock();
      LockedDriverState.removeLockedDriverState();
    }
    if (SessionState.get() != null) {
      SessionState.get().getLineageState().clear();
    }
    return 0;
  }

  // is usually called after close() to commit or rollback a query and end the driver life cycle.
  // do not understand why it is needed and wonder if it could be combined with close.
  public void destroy() {
    lDrvState.stateLock.lock();
    try {
      // in the cancel case where the driver state is INTERRUPTED, destroy will be deferred to
      // the query process
      if (lDrvState.driverState == DriverState.DESTROYED ||
          lDrvState.driverState == DriverState.INTERRUPT) {
        return;
      } else {
        lDrvState.driverState = DriverState.DESTROYED;
      }
    } finally {
      lDrvState.stateLock.unlock();
    }
    if (!hiveLocks.isEmpty()) {
      try {
        releaseLocksAndCommitOrRollback(false);
      } catch (LockException e) {
        LOG.warn("Exception when releasing locking in destroy: " +
            e.getMessage());
      }
    }
  }


  public org.apache.hadoop.hive.ql.plan.api.Query getQueryPlan() throws IOException {
    return plan.getQueryPlan();
  }

  public String getErrorMsg() {
    return errorMessage;
  }


  public QueryDisplay getQueryDisplay() {
    return queryDisplay;
  }
}
