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
package org.apache.solr.core;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.apache.solr.cloud.SolrZkServer;
import org.apache.solr.cloud.ZkController;
import org.apache.solr.common.ParWork;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.cloud.ClusterProperties;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkConfigManager;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.cloud.ZooKeeperException;
import org.apache.solr.common.util.IOUtils;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Used by {@link CoreContainer} to hold ZooKeeper / SolrCloud info, especially {@link ZkController}.
 * Mainly it does some ZK initialization, and ensures a loading core registers in ZK.
 * Even when in standalone mode, perhaps surprisingly, an instance of this class exists.
 * If {@link #getZkController()} returns null then we're in standalone mode.
 */
public class ZkContainer implements Closeable {
  // NOTE DWS: It's debatable if this in-between class is needed instead of folding it all into ZkController.
  //  ZKC is huge though.

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  private final SolrZkClient zkClient;

  protected volatile ZkController zkController;
  private volatile SolrZkServer zkServer;
  
  // see ZkController.zkRunOnly
  private boolean zkRunOnly = Boolean.getBoolean("zkRunOnly"); // expert
  private volatile String confDir;
  private boolean boostrapConf;

  public ZkContainer(SolrZkClient zkClient) {
    this.zkClient = zkClient;
  }

  public void initZooKeeper(final CoreContainer cc, CloudConfig config) {
    String zkRun = System.getProperty("zkRun");

    if (zkRun != null && config == null)
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, "Cannot start Solr in cloud mode - no cloud config provided");
    
    if (config == null)
        return;  // not in zk mode

    String zookeeperHost = System.getProperty("zkHost");
    if (zookeeperHost == null) {
      zookeeperHost = config.getZkHost();
    }

    // zookeeper in quorum mode currently causes a failure when trying to
    // register log4j mbeans.  See SOLR-2369
    // TODO: remove after updating to an slf4j based zookeeper
    System.setProperty("zookeeper.jmx.log4j.disable", "true");

    String solrHome = cc.getSolrHome();
    if (zkRun != null) {
      String zkDataHome = System.getProperty("zkServerDataDir", Paths.get(solrHome).resolve("zoo_data").toString());
      String zkConfHome = System.getProperty("zkServerConfDir", solrHome);
      zkServer = new SolrZkServer(stripChroot(zkRun), stripChroot(config.getZkHost()), new File(zkDataHome), zkConfHome, config.getSolrHostPort());
      zkServer.parseConfig();
      zkServer.start();
      
      // set client from server config if not already set
      if (zookeeperHost == null) {
        zookeeperHost = zkServer.getClientString();
      }
    }

    int zkClientConnectTimeout = 30000;

    if (zookeeperHost != null) {

      // we are ZooKeeper enabled
      try {
        // If this is an ensemble, allow for a long connect time for other servers to come up
        if (zkRun != null && zkServer.getServers().size() > 1) {
          zkClientConnectTimeout = 24 * 60 * 60 * 1000;  // 1 day for embedded ensemble
          log.info("Zookeeper client={}  Waiting for a quorum.", zookeeperHost);
        } else {
          log.info("Zookeeper client={}", zookeeperHost);
        }
        confDir = System.getProperty("bootstrap_confdir");
        boostrapConf = Boolean.getBoolean("bootstrap_conf");  

        // We may have already loaded NodeConfig from zookeeper with same connect string, so no need to recheck chroot
        boolean alreadyUsedChroot = cc.getConfig().isFromZookeeper() && zookeeperHost.equals(System.getProperty("zkHost"));
        if(!alreadyUsedChroot && !ZkController.checkChrootPath(zookeeperHost, (confDir!=null) || boostrapConf || zkRunOnly)) {
          log.error("A chroot was specified in ZkHost but the znode doesn't exist. {}", zookeeperHost);
          throw new ZooKeeperException(SolrException.ErrorCode.SERVER_ERROR,
              "A chroot was specified in ZkHost but the znode doesn't exist. " + zookeeperHost);
        }

        Supplier<List<CoreDescriptor>> descriptorsSupplier = () -> {
          List<CoreDescriptor> descriptors = new ArrayList<>(cc.getLoadedCoreNames().size());
          Collection<SolrCore> cores = cc.getCores();
          for (SolrCore core : cores) {
            descriptors.add(core.getCoreDescriptor());
          }
          return descriptors;
        };

        if (zkRun != null) {
          if (StringUtils.isNotEmpty(System.getProperty("solr.jetty.https.port"))) {
            // Embedded ZK and probably running with SSL
            new ClusterProperties(zkController.getZkClient()).setClusterProperty(ZkStateReader.URL_SCHEME, "https");
          }
          if (zkServer.getServers().size() > 1 && confDir == null && boostrapConf == false) {
            // we are part of an ensemble and we are not uploading the config - pause to give the config time
            // to get up
            Thread.sleep(10000);
          }
        }
        if (log.isDebugEnabled()) {
          log.debug("create zkController");
        }
        zkController = new ZkController(cc, zkClient, config);

        if (log.isDebugEnabled()) log.debug("done zkController create");
      } catch (InterruptedException e) {
        ParWork.propagateInterrupt(e);
        throw new ZooKeeperException(SolrException.ErrorCode.SERVER_ERROR,
            "", e);
      } catch (TimeoutException e) {
        log.error("Could not connect to ZooKeeper", e);
        throw new ZooKeeperException(SolrException.ErrorCode.SERVER_ERROR,
            "", e);
      } catch (IOException | KeeperException e) {
        log.error("", e);
        throw new ZooKeeperException(SolrException.ErrorCode.SERVER_ERROR,
            "", e);
      }
    }
  }

  public void start(CoreContainer cc) throws IOException, KeeperException {
    zkController.start();
    if(confDir != null) {
      log.info("none null conf dir");
      Path configPath = Paths.get(confDir);
      if (!Files.isDirectory(configPath))
        throw new IllegalArgumentException("bootstrap_confdir must be a directory of configuration files");

      String confName = System.getProperty(ZkController.COLLECTION_PARAM_PREFIX+ZkController.CONFIGNAME_PROP, "configuration1");
      ZkConfigManager configManager = new ZkConfigManager(zkController.getZkClient());
      log.info("upload conf");
      configManager.uploadConfigDir(configPath, confName);
    }

    if (boostrapConf) {
      log.info("bootstrap conf");
      ZkController.bootstrapConf(zkController.getZkClient(), cc);
    }
  }
  
  private static String stripChroot(String zkRun) {
    if (zkRun == null || StringUtils.isBlank(zkRun) || zkRun.lastIndexOf('/') < 0) return zkRun;
    return zkRun.substring(0, zkRun.lastIndexOf('/'));
  }
  
  public ZkController getZkController() {
    return zkController;
  }

  public void close() {
    IOUtils.closeQuietly(zkController);
    if (zkClient != null) {
      zkClient.disableCloseLock();
    }
    IOUtils.closeQuietly(zkClient);
    IOUtils.closeQuietly(zkServer);
  }
}
