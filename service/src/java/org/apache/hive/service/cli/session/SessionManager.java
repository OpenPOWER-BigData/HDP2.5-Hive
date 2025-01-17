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

package org.apache.hive.service.cli.session;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hive.common.metrics.common.Metrics;
import org.apache.hadoop.hive.common.metrics.common.MetricsConstant;
import org.apache.hadoop.hive.common.metrics.common.MetricsFactory;
import org.apache.hadoop.hive.common.metrics.common.MetricsVariable;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.conf.HiveConf.ConfVars;
import org.apache.hadoop.hive.ql.hooks.HookUtils;
import org.apache.hive.service.CompositeService;
import org.apache.hive.service.cli.HiveSQLException;
import org.apache.hive.service.cli.SessionHandle;
import org.apache.hive.service.cli.operation.OperationManager;
import org.apache.hive.service.cli.thrift.TProtocolVersion;
import org.apache.hive.service.server.HiveServer2;
import org.apache.hive.service.server.ThreadFactoryWithGarbageCleanup;

/**
 * SessionManager.
 *
 */
public class SessionManager extends CompositeService {

  private static final Log LOG = LogFactory.getLog(CompositeService.class);
  public static final String HIVERCFILE = ".hiverc";
  private HiveConf hiveConf;
  private final Map<SessionHandle, HiveSession> handleToSession =
      new ConcurrentHashMap<SessionHandle, HiveSession>();
  private final OperationManager operationManager = new OperationManager();
  private ThreadPoolExecutor backgroundOperationPool;
  private boolean isOperationLogEnabled;
  private File operationLogRootDir;

  private long checkInterval;
  private long sessionTimeout;
  private boolean checkOperation;

  private volatile boolean shutdown;
  // The HiveServer2 instance running this service
  private final HiveServer2 hiveServer2;

  public SessionManager(HiveServer2 hiveServer2) {
    super(SessionManager.class.getSimpleName());
    this.hiveServer2 = hiveServer2;
  }

  @Override
  public synchronized void init(HiveConf hiveConf) {
    this.hiveConf = hiveConf;
    //Create operation log root directory, if operation logging is enabled
    if (hiveConf.getBoolVar(ConfVars.HIVE_SERVER2_LOGGING_OPERATION_ENABLED)) {
      initOperationLogRootDir();
    }
    createBackgroundOperationPool();
    addService(operationManager);
    super.init(hiveConf);
  }

  private void createBackgroundOperationPool() {
    int poolSize = hiveConf.getIntVar(ConfVars.HIVE_SERVER2_ASYNC_EXEC_THREADS);
    LOG.info("HiveServer2: Background operation thread pool size: " + poolSize);
    int poolQueueSize = hiveConf.getIntVar(ConfVars.HIVE_SERVER2_ASYNC_EXEC_WAIT_QUEUE_SIZE);
    LOG.info("HiveServer2: Background operation thread wait queue size: " + poolQueueSize);
    long keepAliveTime = HiveConf.getTimeVar(
        hiveConf, ConfVars.HIVE_SERVER2_ASYNC_EXEC_KEEPALIVE_TIME, TimeUnit.SECONDS);
    LOG.info(
        "HiveServer2: Background operation thread keepalive time: " + keepAliveTime + " seconds");

    // Create a thread pool with #poolSize threads
    // Threads terminate when they are idle for more than the keepAliveTime
    // A bounded blocking queue is used to queue incoming operations, if #operations > poolSize
    String threadPoolName = "HiveServer2-Background-Pool";
    final BlockingQueue queue = new LinkedBlockingQueue<Runnable>(poolQueueSize);
    backgroundOperationPool = new ThreadPoolExecutor(poolSize, poolSize,
        keepAliveTime, TimeUnit.SECONDS, queue,
        new ThreadFactoryWithGarbageCleanup(threadPoolName));
    backgroundOperationPool.allowCoreThreadTimeOut(true);

    checkInterval = HiveConf.getTimeVar(
        hiveConf, ConfVars.HIVE_SERVER2_SESSION_CHECK_INTERVAL, TimeUnit.MILLISECONDS);
    sessionTimeout = HiveConf.getTimeVar(
        hiveConf, ConfVars.HIVE_SERVER2_IDLE_SESSION_TIMEOUT, TimeUnit.MILLISECONDS);
    checkOperation = HiveConf.getBoolVar(hiveConf,
        ConfVars.HIVE_SERVER2_IDLE_SESSION_CHECK_OPERATION);

    Metrics m = MetricsFactory.getInstance();
    if (m != null) {
      m.addGauge(MetricsConstant.EXEC_ASYNC_QUEUE_SIZE, new MetricsVariable() {
        @Override
        public Object getValue() {
          return queue.size();
        }
      });
      m.addGauge(MetricsConstant.EXEC_ASYNC_POOL_SIZE, new MetricsVariable() {
        @Override
        public Object getValue() {
          return backgroundOperationPool.getPoolSize();
        }
      });
    }
  }

  private void initOperationLogRootDir() {
    operationLogRootDir = new File(
        hiveConf.getVar(ConfVars.HIVE_SERVER2_LOGGING_OPERATION_LOG_LOCATION));
    isOperationLogEnabled = true;

    if (operationLogRootDir.exists() && !operationLogRootDir.isDirectory()) {
      LOG.warn("The operation log root directory exists, but it is not a directory: " +
          operationLogRootDir.getAbsolutePath());
      isOperationLogEnabled = false;
    }

    if (!operationLogRootDir.exists()) {
      if (!operationLogRootDir.mkdirs()) {
        LOG.warn("Unable to create operation log root directory: " +
            operationLogRootDir.getAbsolutePath());
        isOperationLogEnabled = false;
      }
    }

    if (isOperationLogEnabled) {
      LOG.info("Operation log root directory is created: " + operationLogRootDir.getAbsolutePath());
      try {
        FileUtils.forceDeleteOnExit(operationLogRootDir);
      } catch (IOException e) {
        LOG.warn("Failed to schedule cleanup HS2 operation logging root dir: " +
            operationLogRootDir.getAbsolutePath(), e);
      }
    }
  }

  @Override
  public synchronized void start() {
    super.start();
    if (checkInterval > 0) {
      startTimeoutChecker();
    }
  }

  private void startTimeoutChecker() {
    final long interval = Math.max(checkInterval, 3000l);  // minimum 3 seconds
    Runnable timeoutChecker = new Runnable() {
      @Override
      public void run() {
        for (sleepInterval(interval); !shutdown; sleepInterval(interval)) {
          long current = System.currentTimeMillis();
          for (HiveSession session : new ArrayList<HiveSession>(handleToSession.values())) {
            if (sessionTimeout > 0 && session.getLastAccessTime() + sessionTimeout <= current
                && (!checkOperation || session.getNoOperationTime() > sessionTimeout)) {
              SessionHandle handle = session.getSessionHandle();
              LOG.warn("Session " + handle + " is Timed-out (last access : " +
                  new Date(session.getLastAccessTime()) + ") and will be closed");
              try {
                closeSession(handle);
              } catch (HiveSQLException e) {
                LOG.warn("Exception is thrown closing session " + handle, e);
              }
            } else {
              session.closeExpiredOperations();
            }
          }
        }
      }

      private void sleepInterval(long interval) {
        try {
          Thread.sleep(interval);
        } catch (InterruptedException e) {
          // ignore
        }
      }
    };
    backgroundOperationPool.execute(timeoutChecker);
  }

  @Override
  public synchronized void stop() {
    super.stop();
    shutdown = true;
    if (backgroundOperationPool != null) {
      backgroundOperationPool.shutdown();
      long timeout = hiveConf.getTimeVar(
          ConfVars.HIVE_SERVER2_ASYNC_EXEC_SHUTDOWN_TIMEOUT, TimeUnit.SECONDS);
      try {
        backgroundOperationPool.awaitTermination(timeout, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        LOG.warn("HIVE_SERVER2_ASYNC_EXEC_SHUTDOWN_TIMEOUT = " + timeout +
            " seconds has been exceeded. RUNNING background operations will be shut down", e);
      }
      backgroundOperationPool = null;
    }
    cleanupLoggingRootDir();
  }

  private void cleanupLoggingRootDir() {
    if (isOperationLogEnabled) {
      try {
        FileUtils.forceDelete(operationLogRootDir);
      } catch (Exception e) {
        LOG.warn("Failed to cleanup root dir of HS2 logging: " + operationLogRootDir
            .getAbsolutePath(), e);
      }
    }
  }

  public SessionHandle openSession(TProtocolVersion protocol, String username, String password, String ipAddress,
      Map<String, String> sessionConf) throws HiveSQLException {
    return openSession(protocol, username, password, ipAddress, sessionConf, false, null);
  }

  /**
   * Opens a new session and creates a session handle.
   * The username passed to this method is the effective username.
   * If withImpersonation is true (==doAs true) we wrap all the calls in HiveSession
   * within a UGI.doAs, where UGI corresponds to the effective user.
   * @see org.apache.hive.service.cli.thrift.ThriftCLIService#getUserName()
   *
   * @param protocol
   * @param username
   * @param password
   * @param ipAddress
   * @param sessionConf
   * @param withImpersonation
   * @param delegationToken
   * @return
   * @throws HiveSQLException
   */
  public SessionHandle openSession(TProtocolVersion protocol, String username, String password, String ipAddress,
      Map<String, String> sessionConf, boolean withImpersonation, String delegationToken)
          throws HiveSQLException {
    HiveSession session;
    // If doAs is set to true for HiveServer2, we will create a proxy object for the session impl.
    // Within the proxy object, we wrap the method call in a UserGroupInformation#doAs
    if (withImpersonation) {
      HiveSessionImplwithUGI sessionWithUGI = new HiveSessionImplwithUGI(protocol, username, password,
          hiveConf, ipAddress, delegationToken);
      session = HiveSessionProxy.getProxy(sessionWithUGI, sessionWithUGI.getSessionUgi());
      sessionWithUGI.setProxySession(session);
    } else {
      session = new HiveSessionImpl(protocol, username, password, hiveConf, ipAddress);
    }
    session.setSessionManager(this);
    session.setOperationManager(operationManager);
    try {
      session.open(sessionConf);
    } catch (Exception e) {
      try {
        session.close();
      } catch (Throwable t) {
        LOG.warn("Error closing session", t);
      }
      session = null;
      throw new HiveSQLException("Failed to open new session: " + e, e);
    }
    if (isOperationLogEnabled) {
      session.setOperationLogSessionDir(operationLogRootDir);
    }
    try {
      executeSessionHooks(session);
    } catch (Exception e) {
      try {
        session.close();
      } catch (Throwable t) {
        LOG.warn("Error closing session", t);
      }
      session = null;
      throw new HiveSQLException("Failed to execute session hooks", e);
    }
    handleToSession.put(session.getSessionHandle(), session);
    return session.getSessionHandle();
  }

  public void closeSession(SessionHandle sessionHandle) throws HiveSQLException {
    HiveSession session = handleToSession.remove(sessionHandle);
    if (session == null) {
      throw new HiveSQLException("Session does not exist!");
    }
    try {
      session.close();
    } finally {
      // Shutdown HiveServer2 if it has been deregistered from ZooKeeper and has no active sessions
      if (!(hiveServer2 == null) && (hiveConf.getBoolVar(ConfVars.HIVE_SERVER2_SUPPORT_DYNAMIC_SERVICE_DISCOVERY))
          && (!hiveServer2.isRegisteredWithZooKeeper())) {
        // Asynchronously shutdown this instance of HiveServer2,
        // if there are no active client sessions
        if (getOpenSessionCount() == 0) {
          LOG.info("This instance of HiveServer2 has been removed from the list of server "
              + "instances available for dynamic service discovery. "
              + "The last client session has ended - will shutdown now.");
          Thread shutdownThread = new Thread() {
            @Override
            public void run() {
              hiveServer2.stop();
            }
          };
          shutdownThread.start();
        }
      }
    }
  }

  public HiveSession getSession(SessionHandle sessionHandle) throws HiveSQLException {
    HiveSession session = handleToSession.get(sessionHandle);
    if (session == null) {
      throw new HiveSQLException("Invalid SessionHandle: " + sessionHandle);
    }
    return session;
  }

  public OperationManager getOperationManager() {
    return operationManager;
  }

  private static ThreadLocal<String> threadLocalIpAddress = new ThreadLocal<String>();

  public static void setIpAddress(String ipAddress) {
    threadLocalIpAddress.set(ipAddress);
  }

  public static void clearIpAddress() {
    threadLocalIpAddress.remove();
  }

  public static String getIpAddress() {
    return threadLocalIpAddress.get();
  }

  private static ThreadLocal<List<String>> threadLocalForwardedAddresses = new ThreadLocal<List<String>>();

  public static void setForwardedAddresses(List<String> ipAddress) {
    threadLocalForwardedAddresses.set(ipAddress);
  }

  public static void clearForwardedAddresses() {
    threadLocalForwardedAddresses.remove();
  }

  public static List<String> getForwardedAddresses() {
    return threadLocalForwardedAddresses.get();
  }


  private static ThreadLocal<String> threadLocalUserName = new ThreadLocal<String>(){
    @Override
    protected synchronized String initialValue() {
      return null;
    }
  };

  public static void setUserName(String userName) {
    threadLocalUserName.set(userName);
  }

  public static void clearUserName() {
    threadLocalUserName.remove();
  }

  public static String getUserName() {
    return threadLocalUserName.get();
  }

  private static ThreadLocal<String> threadLocalProxyUserName = new ThreadLocal<String>(){
    @Override
    protected synchronized String initialValue() {
      return null;
    }
  };

  public static void setProxyUserName(String userName) {
    LOG.debug("setting proxy user name based on query param to: " + userName);
    threadLocalProxyUserName.set(userName);
  }

  public static String getProxyUserName() {
    return threadLocalProxyUserName.get();
  }

  public static void clearProxyUserName() {
    threadLocalProxyUserName.remove();
  }

  // execute session hooks
  private void executeSessionHooks(HiveSession session) throws Exception {
    List<HiveSessionHook> sessionHooks = HookUtils.getHooks(hiveConf,
        HiveConf.ConfVars.HIVE_SERVER2_SESSION_HOOK, HiveSessionHook.class);
    for (HiveSessionHook sessionHook : sessionHooks) {
      sessionHook.run(new HiveSessionHookContextImpl(session));
    }
  }

  public Future<?> submitBackgroundOperation(Runnable r) {
    return backgroundOperationPool.submit(r);
  }

  public int getOpenSessionCount() {
    return handleToSession.size();
  }
}

