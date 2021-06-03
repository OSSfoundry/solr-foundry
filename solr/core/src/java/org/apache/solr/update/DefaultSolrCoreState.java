/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.update;

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.index.IndexDeletionPolicy;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.search.Sort;
import org.apache.lucene.store.Directory;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.request.CoreAdminRequest;
import org.apache.solr.client.solrj.util.AsyncListener;
import org.apache.solr.client.solrj.util.Cancellable;
import org.apache.solr.cloud.*;
import org.apache.solr.cloud.overseer.OverseerAction;
import org.apache.solr.common.AlreadyClosedException;
import org.apache.solr.common.ParWork;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.SolrException.ErrorCode;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.ZkNodeProps;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.util.IOUtils;
import org.apache.solr.common.util.NamedList;
import org.apache.solr.core.CoreContainer;
import org.apache.solr.core.CoreDescriptor;
import org.apache.solr.core.DirectoryFactory;
import org.apache.solr.core.SolrCore;
import org.apache.solr.handler.admin.CoreAdminHandler;
import org.apache.solr.index.SortingMergePolicy;
import org.apache.solr.logging.MDCLoggingContext;
import org.apache.solr.schema.IndexSchema;
import org.apache.solr.util.RefCounted;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class DefaultSolrCoreState extends SolrCoreState implements RecoveryStrategy.RecoveryListener {
  public static final String SKIPPING_RECOVERY_BECAUSE_SOLR_IS_SHUTDOWN = "Skipping recovery because Solr is shutdown";
  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  public static final String RESTART_OF_PREP_RECOVERY_FAILED = "Restart of Prep recovery failed";

  private final AtomicReference<Boolean> needsRecovery = new AtomicReference<>();

  private volatile boolean recoveryRunning = false;

  private final boolean SKIP_AUTO_RECOVERY = Boolean.getBoolean("solrcloud.skip.autorecovery");


  private final ActionThrottle recoveryThrottle = new ActionThrottle("recovery", Integer.getInteger("solr.recoveryThrottle", 0));

  // Use the readLock to retrieve the current IndexWriter (may be lazily opened)
  // Use the writeLock for changing index writers
  private final ReentrantReadWriteLock iwLock = new ReentrantReadWriteLock(true);
  private final ReentrantLock iwOpenLock = new ReentrantLock(true);

  private volatile SolrIndexWriter indexWriter = null;
  private final DirectoryFactory directoryFactory;
  private final RecoveryStrategy.Builder recoveryStrategyBuilder;

  private volatile RecoveryStrategy recoveryStrat;


  private volatile boolean lastReplicationSuccess = true;

  // will we attempt recovery as if we just started up (i.e. use starting versions rather than recent versions for peersync
  // so we aren't looking at update versions that have started buffering since we came up.
  private volatile boolean recoveringAfterStartup = true;

  private volatile RefCounted<IndexWriter> refCntWriter;

  protected final ReentrantLock commitLock = new ReentrantLock();

  private final AtomicBoolean cdcrRunning = new AtomicBoolean();

  private volatile Future<Boolean> cdcrBootstrapFuture;

  private volatile Callable cdcrBootstrapCallable;

  private volatile boolean prepForClose;
  private volatile Cancellable prevSendPreRecoveryRequest;

  @Deprecated
  public DefaultSolrCoreState(DirectoryFactory directoryFactory) {
    this(directoryFactory, new RecoveryStrategy.Builder());
  }

  public DefaultSolrCoreState(DirectoryFactory directoryFactory,
                              RecoveryStrategy.Builder recoveryStrategyBuilder) {
    this.directoryFactory = directoryFactory;
    this.recoveryStrategyBuilder = recoveryStrategyBuilder;
  }

  private void closeIndexWriter(IndexWriterCloser closer) {
    try {
      if (log.isDebugEnabled()) log.debug("SolrCoreState ref count has reached 0 - closing IndexWriter");
      if (closer != null) {
        if (log.isDebugEnabled()) log.debug("closing IndexWriter with IndexWriterCloser");

        // indexWriter may be null if there was a failure in opening the search during core init,
        // such as from an index corruption issue (see TestCoreAdmin#testReloadCoreAfterFailure)
        if (indexWriter != null) {
          closer.closeWriter(indexWriter);
        }
      } else if (indexWriter != null) {
        log.debug("closing IndexWriter...");
        indexWriter.commit();
        indexWriter.close();
      }
      indexWriter = null;
    } catch (Exception e) {
      ParWork.propagateInterrupt("Error during close of writer.", e);
      throw new SolrException(ErrorCode.SERVER_ERROR, e);
    }
  }

  @Override
  public RefCounted<IndexWriter> getIndexWriter(SolrCore core) throws IOException {
    return getIndexWriter(core, false);
  }

  @Override
  public RefCounted<IndexWriter> getIndexWriter(SolrCore core, boolean createIndex)
          throws IOException {
    if (core != null && (!core.indexEnabled || core.readOnly)) {
      throw new SolrException(SolrException.ErrorCode.SERVICE_UNAVAILABLE,
              "Indexing is temporarily disabled");
    }

//    if (core != null && core.getCoreContainer().isShutDown()) {
//      throw new AlreadyClosedException("CoreContainer is already closed");
//    }

    boolean succeeded = false;
    lock(iwLock.readLock());
    try {
      // Multiple readers may be executing this, but we only want one to open the writer on demand.
      iwOpenLock.lock();
      try {
        if (core == null) {
          // core == null is a signal to just return the current writer, or null if none.
          initRefCntWriter();
          if (refCntWriter == null) return null;
        } else {
          if (indexWriter == null) {
            if (core.getCoreContainer().isShutDown()) {
              throw new AlreadyClosedException("CoreContainer is already closed");
            }

            indexWriter = createMainIndexWriter(core, createIndex,"DirectUpdateHandler2");
          }
          initRefCntWriter();
        }

        refCntWriter.incref();
        succeeded = true;  // the returned RefCounted<IndexWriter> will release the readLock on a decref()
        return refCntWriter;
      } finally {
        iwOpenLock.unlock();
      }

    } finally {
      // if we failed to return the IW for some other reason, we should unlock.
      if (!succeeded) {
        iwLock.readLock().unlock();
      }
    }

  }

  private void initRefCntWriter() {
    // TODO: since we moved to a read-write lock, and don't rely on the count to close the writer, we don't really
    // need this class any more.  It could also be a singleton created at the same time as SolrCoreState
    // or we could change the API of SolrCoreState to just return the writer and then add a releaseWriter() call.
    if (refCntWriter == null && indexWriter != null) {
      refCntWriter = new RefCounted<IndexWriter>(indexWriter) {

        @Override
        public void decref() {
          iwLock.readLock().unlock();
          super.decref();  // This is now redundant (since we switched to read-write locks), we don't really need to maintain our own reference count.
        }

        @Override
        public void close() {
          //  We rely on other code to actually close the IndexWriter, and there's nothing special to do when the ref count hits 0
        }
      };
    }
  }

  // acquires the lock or throws an exception if the CoreState has been closed.
  private static void lock(Lock lock) {
    boolean acquired = false;
    do {
      try {
        acquired = lock.tryLock() || lock.tryLock(100, TimeUnit.MILLISECONDS);
      } catch (InterruptedException e) {
        log.warn("WARNING - Dangerous interrupt", e);
      }
    } while (!acquired);
  }

  // closes and opens index writers without any locking
  private void changeWriter(SolrCore core, boolean rollback, boolean createIndex, boolean openNewWriter) throws IOException {
    String coreName = core.getName();

    // We need to null this so it picks up the new writer next get call.
    // We do this before anything else in case we hit an exception.
    refCntWriter = null;
    IndexWriter iw = indexWriter; // temp reference just for closing
    indexWriter = null; // null this out now in case we fail, so we won't use the writer again

    if (iw != null) {
      if (!rollback) {
        try {
          log.debug("Closing old IndexWriter... core={}", coreName);
          iw.commit();
          iw.rollback();
        } catch (Exception e) {
          ParWork.propagateInterrupt("Error closing old IndexWriter. core=" + coreName, e);
        }
      } else {
        try {
          log.debug("Rollback old IndexWriter... core={}", coreName);
          iw.rollback();
        } catch (Exception e) {
          ParWork.propagateInterrupt("Error rolling back old IndexWriter. core=" + coreName, e);
        }
      }
    }

    if (openNewWriter) {
      indexWriter = createMainIndexWriter(core, createIndex, "DirectUpdateHandler2");
      log.info("New IndexWriter is ready to be used.");
    }
  }

  @Override
  public void newIndexWriter(SolrCore core, boolean rollback) throws IOException {
    lock(iwLock.writeLock());
    try {
      changeWriter(core, rollback, false, true);
    } finally {
      iwLock.writeLock().unlock();
    }
  }

  @Override
  public void newIndexWriter(SolrCore core, boolean rollback, boolean createIndex) throws IOException {
    lock(iwLock.writeLock());
    try {
      changeWriter(core, rollback, createIndex, true);
    } finally {
      iwLock.writeLock().unlock();
    }
  }

  @Override
  public void closeIndexWriter(SolrCore core, boolean rollback) throws IOException {
    lock(iwLock.writeLock());
    changeWriter(core, rollback, false,false);
    // Do not unlock the writeLock in this method.  It will be unlocked by the openIndexWriter call (see base class javadoc)
  }

  @Override
  public void openIndexWriter(SolrCore core) throws IOException {
    try {
      changeWriter(core, false, false, true);
    } finally {
      iwLock.writeLock().unlock();  //unlock even if we failed
    }
  }

  @Override
  public void rollbackIndexWriter(SolrCore core) throws IOException {
    iwLock.writeLock().lock();
    try {
      changeWriter(core, true, false, true);
    } finally {
      iwLock.writeLock().unlock();
    }
  }

  protected static SolrIndexWriter createMainIndexWriter(SolrCore core, boolean createIndex, String name) throws IOException {
    SolrIndexWriter iw;
    try {
      iw = buildIndexWriter(core, name, core.getNewIndexDir(), core.getDirectoryFactory(), createIndex, core.getLatestSchema(),
              core.getSolrConfig().indexConfig, core.getDeletionPolicy(), core.getCodec(), false);
    } catch (Exception e) {
      ParWork.propagateInterrupt(e);
      throw new SolrException(ErrorCode.SERVER_ERROR, e);
    }

    return iw;
  }

  public static SolrIndexWriter buildIndexWriter(SolrCore core, String name, String path, DirectoryFactory directoryFactory, boolean create, IndexSchema schema,
      SolrIndexConfig config, IndexDeletionPolicy delPolicy, Codec codec, boolean commitOnClose) {
    SolrIndexWriter iw;
    Directory dir = null;
    try {
      dir = getDir(directoryFactory, path, config);
      iw = new SolrIndexWriter(core, name, directoryFactory, dir, create, schema, config, delPolicy, codec, commitOnClose);
    } catch (Throwable e) {
      ParWork.propagateInterrupt(e);
      SolrException exp = new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);

      if (dir != null) {
//          try {
//            directoryFactory.release(dir);
//          } catch (IOException e1) {
//            exp.addSuppressed(e1);
//          }
      }
      if (e instanceof  Error) {
        log.error("Exception constructing SolrIndexWriter", exp);
        throw (Error) e;
      }
      throw exp;
    }

    return iw;
  }

  public static Directory getDir(DirectoryFactory directoryFactory, String path, SolrIndexConfig config) {
    Directory dir;
    try {
      dir = directoryFactory.get(path, DirectoryFactory.DirContext.DEFAULT, config.lockType);
    } catch (Exception e) {
      ParWork.propagateInterrupt(e);
      SolrException exp = new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
      throw exp;
    }
    return dir;
  }

  public Sort getMergePolicySort() throws IOException {
    lock(iwLock.readLock());
    try {
      if (indexWriter != null) {
        final var mergePolicy = indexWriter.getConfig().getMergePolicy();
        if (mergePolicy instanceof SortingMergePolicy) {
          return ((SortingMergePolicy)mergePolicy).getSort();
        }
      }
    } finally {
      iwLock.readLock().unlock();
    }
    return null;
  }

  @Override
  public DirectoryFactory getDirectoryFactory() {
    return directoryFactory;
  }

  @Override
  public RecoveryStrategy.Builder getRecoveryStrategyBuilder() {
    return recoveryStrategyBuilder;
  }

  @Override
  public void doRecovery(CoreContainer cc, CoreDescriptor cd, String source, Replica leader) {
    try (SolrCore core = cc.getCore(cd.getName())) {
      doRecovery(core, source, leader);
    }
  }

  @Override
  public void doRecovery(SolrCore core, String source, Replica leader) {

    log.info("Do recovery for core {} source={}", core.getName(), source);
    var coreContainer = core.getCoreContainer();
    if (prepForClose || closed || coreContainer.isShutDown()) {
      log.warn(SKIPPING_RECOVERY_BECAUSE_SOLR_IS_SHUTDOWN);
      return;

    }

    try {
      // we make recovery requests async - that async request may
      // have to 'wait in line' a bit or bail if a recovery is
      // already queued up - the recovery execution itself is run
      // in another thread on another 'recovery' executor.
      //
      if (log.isDebugEnabled()) log.debug("Submit recovery for {}", core.getName());

      needsRecovery.getAndUpdate(aBoolean -> {
        if (recoveryRunning) {
          log.info("Recovery already running core {}", core.getName());
          cancelRecovery();
          return true;
        }
        recoveryRunning = true;
        log.info("Running recovery for core {}", core.getName());
        var recoveryTask = new RecoveryTask(core, coreContainer, needsRecovery);

        try {
          sendPrepRecoveryCmd(core, core.getCoreDescriptor(), recoveryTask, leader);
        } catch (AlreadyClosedException e) {
          log.info("Already closed while starting recovery");
          return true;
        } catch (Exception e) {
          log.error("Exception starting recovery", e);
          return true; // nocommit - we should wait ane retry ...
        }
        return false;
      });

    } catch (RejectedExecutionException e) {
      // fine, we are shutting down
      log.warn("Skipping recovery because we are closed");
    }
  }

  final private void sendPrepRecoveryCmd(SolrCore core, CoreDescriptor coreDescriptor, RecoveryTask recoveryTask, Replica leaderReplica) {

    if (core.getCoreContainer().isShutDown()) {
      throw new AlreadyClosedException();
    }

    CompletableFuture.runAsync(() -> {

      if (core.getCoreContainer().isShutDown()) {
        throw new AlreadyClosedException();
      }

      try {
        LeaderElector leaderElector = core.getCoreContainer().getZkController().getLeaderElector(core.getName());

        if (leaderElector != null && leaderElector.isLeader()) {
          log.warn("We are the leader, STOP recovery", new SolrException(ErrorCode.INVALID_STATE, "Leader in recovery"));
          var zkNodes = ZkNodeProps
                  .fromKeyVals(StatePublisher.OPERATION, OverseerAction.STATE.toLower(), ZkStateReader.CORE_NAME_PROP, core.getName(), "id",
                          core.getCoreDescriptor().getCoreProperty("collId", null) + "-" + core.getCoreDescriptor().getCoreProperty("id", null), ZkStateReader.COLLECTION_PROP, core.getCoreDescriptor().getCollectionName(), ZkStateReader.STATE_PROP, Replica.State.LEADER);
          core.getCoreContainer().getZkController().publish(zkNodes);
          return;
        }

        if (leaderReplica == null) {
          var foundLeader = new AtomicBoolean();
          String shardId = coreDescriptor.getCloudDescriptor().getShardId();
          core.getCoreContainer().getZkController().zkStateReader.waitForState(coreDescriptor.getCollectionName(), Integer.getInteger("solr.waitForLeaderInZkRegSec", 60), TimeUnit.SECONDS, (n, c) -> {
            if (log.isDebugEnabled()) {
              log.debug("wait for leader notified collection={}", c == null ? "(null)" : c);
            }

            if (c == null) return false;
            Slice slice = c.getSlice(shardId);
            if (slice == null) return false;
            Replica fleader = slice.getLeader(n);

            if (fleader != null && fleader.getState() == Replica.State.ACTIVE) {
              boolean success = foundLeader.compareAndSet(false, true);
              if (success) {
                if (log.isDebugEnabled()) {
                  log.debug("Found ACTIVE leader for slice={} leader={}", slice.getName(), fleader);
                }
                core.getCoreContainer().getUpdateShardHandler().getRecoveryExecutor().submit(() -> sendCmd(core, fleader, recoveryTask));
              }
              return true;
            }

            return false;
          });
        } else {
          core.getCoreContainer().getUpdateShardHandler().getRecoveryExecutor().submit(() -> sendCmd(core, leaderReplica, recoveryTask));
        }
      } catch (Exception e) {
        ParWork.propagateInterrupt(e);

        try {
          sendPrepRecoveryCmd(core, core.getCoreDescriptor(), recoveryTask, null);
        } catch (Exception e2) {
          log.error(RESTART_OF_PREP_RECOVERY_FAILED, e2);
        }
      }

    }, core.getCoreContainer().getUpdateShardHandler().getRecoveryExecutor());

  }

  private void sendCmd(SolrCore core, Replica leader, RecoveryTask recoveryTask) {
    try {
    log.debug("Publishing state of core [{}] as buffering {}", core.getName(), "doSyncOrReplicateRecovery");

    core.getCoreContainer().getZkController().publish(core.getCoreDescriptor(), Replica.State.BUFFERING);

    recoveryTask.setFistLeader(leader);

    String leaderCoreName = leader.getName();

    if (leader.getNodeName().equals(core.getCoreContainer().getZkController().getNodeName()) &&
            !core.getCoreContainer().getZkController().zkStateReader.isLocalLeader.isLocalLeader(leaderCoreName)) {
      try {
        sendPrepRecoveryCmd(core, core.getCoreDescriptor(), recoveryTask, null);
      } catch (Exception e) {
        log.error(RESTART_OF_PREP_RECOVERY_FAILED, e);
      }
      return;
    }
    String leaderBaseUrl = leader.getBaseUrl();
    var prepCmd = new CoreAdminRequest.WaitForState();
    prepCmd.setCoreName(core.getName());
    prepCmd.setLeaderName(leaderCoreName);
    prepCmd.setState(Replica.State.BUFFERING);
    prepCmd.setCoresCollection(core.getCoreDescriptor().getCollectionName());
    prepCmd.setCheckIsLeader(true);

    if (prepForClose || closed || core.getCoreContainer().isShutDown()) {
      log.warn(SKIPPING_RECOVERY_BECAUSE_SOLR_IS_SHUTDOWN);
      return;
    }
    log.info("Sending prep recovery command to {} for leader={} params={}", leaderBaseUrl, leaderCoreName, prepCmd.getParams());

    try {
      Http2SolrClient client = core.getCoreContainer().getUpdateShardHandler().getRecoveryOnlyClient();
      prepCmd.setBasePath(leaderBaseUrl);
      AtomicReference<NamedList<Object>> results = new AtomicReference<>();
      AtomicReference<Throwable> exp = new AtomicReference<>();
      prevSendPreRecoveryRequest = client.asyncRequest(prepCmd, null, new PrepRecoveryAsyncListener(results, exp, recoveryTask, core));
    } catch (SolrException e) {
      Throwable cause = e.getRootCause();
      if (cause instanceof AlreadyClosedException) {
        return;
      }
      log.info("failed in prep recovery", e);
    } catch (Exception e) {
      log.info("failed in prep recovery", e);
    }
  } catch (Exception e) {
      ParWork.propagateInterrupt(e);

      try {
        sendPrepRecoveryCmd(core, core.getCoreDescriptor(), recoveryTask, null);
      } catch (Exception e2) {
        log.error(RESTART_OF_PREP_RECOVERY_FAILED, e2);
      }
    }

}

  private class PrepRecoveryAsyncListener implements AsyncListener<NamedList<Object>> {
    private final AtomicReference<NamedList<Object>> results;

    private final RecoveryTask recoveryTask;
    private final SolrCore core;

    public PrepRecoveryAsyncListener(AtomicReference<NamedList<Object>> results, AtomicReference<Throwable> exp, RecoveryTask recoveryTask, SolrCore core) {
      this.results = results;

      this.recoveryTask = recoveryTask;
      this.core = core;
    }

    @Override public void onSuccess(NamedList<Object> entries, int code, Object context) {
      results.set(entries);
      String prepSuccess = entries._getStr(CoreAdminHandler.RESPONSE_STATUS, null);
      if (prepSuccess != null) {
        boolean success = Integer.parseInt(prepSuccess) == 0;
        if (!success) {
          log.info("Not the valid leader according to prep recovery");
          onFailure(new IllegalStateException("Not the valid leader"), 503, context);
          return;
        }
      }
      CompletableFuture.runAsync(recoveryTask, core.getCoreContainer().getUpdateShardHandler().getRecoveryExecutor());
    }

    @Override public void onFailure(Throwable throwable, int code, Object context) {
      log.info("Prep recovery exception", throwable);

      if (code == 200) {
        CompletableFuture.runAsync(recoveryTask, core.getCoreContainer().getUpdateShardHandler().getRecoveryExecutor());
      } else {
        try {
          Thread.sleep(250);
          sendPrepRecoveryCmd(core, core.getCoreDescriptor(), recoveryTask, null);
        } catch (Exception e) {
          log.error(RESTART_OF_PREP_RECOVERY_FAILED, e);
        }
      }
    }
  }

  @Override
  public void cancelRecovery() {
    cancelRecovery(false, false);
  }

  @Override
  public void cancelRecovery(boolean wait, boolean prepForClose) {
    if (log.isDebugEnabled()) log.debug("Cancel recovery");
    
    if (prepForClose) {
      this.prepForClose = true;
    }

    if (recoveryStrat != null) {
      try {
        recoveryStrat.close();
      } catch (NullPointerException e) {
        // okay
      }
    }

    recoveryStrat = null;
  }

  /** called from recoveryStrat on a successful recovery */
  @Override
  public void recovered() {
    recoveringAfterStartup = false;  // once we have successfully recovered, we no longer need to act as if we are recovering after startup
  }

  public boolean isRecoverying() {
    return recoveryRunning;
  }


  /** called from recoveryStrat on a failed recovery */
  @Override
  public void failed() {

  }

  @Override
  public void close(IndexWriterCloser closer) {

    // we can't lock here without
    // a blocking race, we should not need to
    // though
    // iwLock.writeLock().lock();

    log.debug("Closing SolrCoreState refCnt={}", solrCoreStateRefCnt.get());

    cancelRecovery(false, true);

    try {
      if (prevSendPreRecoveryRequest != null) {
        prevSendPreRecoveryRequest.cancel();
      }
    } catch (NullPointerException e) {

    }

    try {
      closeIndexWriter(closer);
    } finally {
      // iwLock.writeLock().unlock();
      IOUtils.closeQuietly(directoryFactory);
    }

  }

  @Override
  public Lock getCommitLock() {
    return commitLock;
  }

  @Override
  public boolean getLastReplicateIndexSuccess() {
    return lastReplicationSuccess;
  }

  @Override
  public void setLastReplicateIndexSuccess(boolean success) {
    this.lastReplicationSuccess = success;
  }

  private class RecoveryTask implements Runnable {
    private final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final SolrCore core;
    private final CoreContainer corecontainer;
    private final AtomicReference<Boolean> needsRecovery;
    private Replica firstLeader;

    public RecoveryTask(SolrCore core, CoreContainer corecontainer, AtomicReference<Boolean> needsRecoveryRef) {
      this.core = core;
      this.corecontainer = corecontainer;
      this.needsRecovery = needsRecoveryRef;
    }

    @Override public void run() {
      var coreDescriptor = core.getCoreDescriptor();
      MDCLoggingContext.setCoreName(core.getName());
      MDCLoggingContext.setNode(corecontainer.getZkController().getNodeName());
      try {
        if (SKIP_AUTO_RECOVERY) {
          log.warn("Skipping recovery according to sys prop solrcloud.skip.autorecovery");
          return;
        }

        if (log.isDebugEnabled()) log.debug("Going to create and run RecoveryStrategy");

        // check before we grab the lock
        if (prepForClose || closed || corecontainer.isShutDown()) {
          log.warn(SKIPPING_RECOVERY_BECAUSE_SOLR_IS_SHUTDOWN);
          return;
        }

        recoveryThrottle.minimumWaitBetweenActions();
        recoveryThrottle.markAttemptingAction();

        recoveryStrat = recoveryStrategyBuilder.create(corecontainer, coreDescriptor, DefaultSolrCoreState.this);
        recoveryStrat.setRecoveringAfterStartup(recoveringAfterStartup);
        recoveryStrat.setFirstLeader(firstLeader);

        recoveryStrat.run();

        if (log.isDebugEnabled()) log.debug("Running recovery");


      } catch (AlreadyClosedException e) {
        log.warn("Skipping recovery because we are closed");
      } catch (Exception e) {
        log.error("Exception starting recovery", e);
      } finally {

        needsRecovery.getAndUpdate(aBoolean -> {
          if (!Boolean.TRUE.equals(aBoolean)) {
            return false;
          }
          recoveryRunning = true;
          recoveryStrat.unclose();
          CompletableFuture.runAsync(this, corecontainer.getUpdateShardHandler().getRecoveryExecutor());
          return false;
        });

        MDCLoggingContext.clear();
      }
    }

    public final void setFistLeader(Replica leader) {
      this.firstLeader = leader;
    }
  }
}
