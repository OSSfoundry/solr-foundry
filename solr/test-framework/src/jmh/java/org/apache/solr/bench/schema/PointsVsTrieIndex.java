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
package org.apache.solr.bench.schema;

import org.apache.solr.SolrTestUtil;
import org.apache.solr.bench.index.CloudIndexing;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.embedded.JettySolrRunner;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.request.CollectionAdminRequest;
import org.apache.solr.client.solrj.request.UpdateRequest;
import org.apache.solr.client.solrj.util.AsyncListener;
import org.apache.solr.cloud.MiniSolrCloudCluster;
import org.apache.solr.cloud.SolrCloudTestCase;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.util.NamedList;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Timeout;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Threads(6)
@Warmup(iterations = 8)
@Measurement(iterations = 5)
@Fork(value = 1, jvmArgs = {"-Xmx4g", "-Dorg.apache.xml.dtm.DTMManager=org.apache.xml.dtm.ref.DTMManager", "-Dlog4j2.is.webapp=false", "-Dlog4j2.garbagefreeThreadContextMap=true", "-Dlog4j2.enableDirectEncoders=true", "-Dlog4j2.enable.threadlocals=true",
    "-Dzookeeper.jmx.log4j.disable=true", "-Dlog4j2.disable.jmx=true", "-XX:ConcGCThreads=2",
     "-XX:ParallelGCThreads=3", "-XX:+UseG1GC", "-Djetty.insecurerandom=1", "-Djava.security.egd=file:/dev/./urandom", "-XX:-UseBiasedLocking",
    "-XX:+UseG1GC", "-XX:+PerfDisableSharedMem", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=250", "-Dsolr.enableMetrics=false", "-Dsolr.perThreadPoolSize=6", "-Dsolr.maxHttp2ClientThreads=64", "-Dsolr.jettyRunnerThreadPoolMaxSize=200",
    "-Dsolr.enablePublicKeyHandler=false", "-Dzookeeper.nio.numSelectorThreads=6", "-Dzookeeper.nio.numWorkerThreads=6", "-Dzookeeper.commitProcessor.numWorkerThreads=4", "-Dzookeeper.nio.shutdownTimeout=1000",
    "-Dsolr.rootSharedThreadPoolCoreSize=120", "-Dlucene.cms.override_spins=false", "-Dsolr.enablePublicKeyHandler=false", "-Dsolr.tests.ramBufferSizeMB=100",
    "-Dlog4j.configurationFile=logconf/log4j2-std.xml", "-Dsolr.asyncDispatchFilter=true", "-Dsolr.asyncIO=true", "-Djava.util.concurrent.ForkJoinPool.common.threadFactory=org.apache.commons.SolrForkJoinThreadFactory"
  //  "-XX:+FlightRecorder", "-XX:StartFlightRecording=filename=jfr_results/,dumponexit=true,settings=profile,path-to-gc-roots=true"})
    })
@Timeout(time = 60)
public class PointsVsTrieIndex {

  @State(Scope.Benchmark)
  public static class BenchState {
    String collectionName = "testCollection";

    @Param({"point", "trie"})
    String fieldType;

    @Param({"disabled", "enabled", "useAsStored"})
    String docValues;

    int nodeCount = 5;

    int numShards = 9;
    int numReplicas = 3;

    List<String> nodes;
    static Random random = new Random(313);
    MiniSolrCloudCluster cluster;
    Http2SolrClient client;

    static AtomicInteger id = new AtomicInteger();

    private static class RequestAsyncListener implements AsyncListener<NamedList<Object>> {
      @Override public void onSuccess(NamedList<Object> objectNamedList, int code) {

      }

      @Override public void onFailure(Throwable throwable, int code) {
        System.err.println("Request call failed! " + throwable);
      }
    }

    @Setup(Level.Iteration)
    public void doSetup() throws Exception {
      Path currentRelativePath = Paths.get("");
      String s = currentRelativePath.toAbsolutePath().toString();
      System.out.println("Current relative path is: " + s);

      if (docValues.equals("disabled")) {
        System.setProperty("solr.tests.dv.as.stored", "false");
        System.setProperty("solr.tests.numeric.dv", "false");
      } else if (docValues.equals("enabled")) {
        System.setProperty("solr.tests.dv.as.stored", "false");
        System.setProperty("solr.tests.numeric.dv", "true");
      } else if (docValues.equals("useAsStored")) {
        System.setProperty("solr.tests.dv.as.stored", "true");
        System.setProperty("solr.tests.numeric.dv", "true");
      }

      if (fieldType.equals("point")) {
        System.setProperty("solr.tests.IntegerFieldType", "org.apache.solr.schema.IntPointField");
        System.setProperty("solr.tests.FloatFieldType", "org.apache.solr.schema.FloatPointField");
        System.setProperty("solr.tests.LongFieldType", "org.apache.solr.schema.LongPointField");
        System.setProperty("solr.tests.DoubleFieldType", "org.apache.solr.schema.DoublePointField");
        System.setProperty("solr.tests.DateFieldType", "org.apache.solr.schema.DatePointField");
      } else if (fieldType.equals("trie")) {
        System.setProperty("solr.tests.IntegerFieldType", "org.apache.solr.schema.TrieIntField");
        System.setProperty("solr.tests.FloatFieldType", "org.apache.solr.schema.TrieFloatField");
        System.setProperty("solr.tests.LongFieldType", "org.apache.solr.schema.TrieLongField");
        System.setProperty("solr.tests.DoubleFieldType", "org.apache.solr.schema.TrieDoubleField");
        System.setProperty("solr.tests.DateFieldType", "org.apache.solr.schema.TrieDateField");

      } else {
        throw new IllegalStateException();
      }

      cluster = new SolrCloudTestCase.Builder(nodeCount, SolrTestUtil.createTempDir()).
          addConfig("conf", Paths.get("solr/test-framework/src/resources/configs/number-fields/conf")).formatZk(true).configure();
      System.out.println("cluster base path=" + cluster.getBaseDir());
      client = cluster.getSolrClient().getHttpClient();
      nodes = new ArrayList<>(nodeCount);
      List<JettySolrRunner> jetties = cluster.getJettySolrRunners();
      for (JettySolrRunner runner : jetties) {
        nodes.add(runner.getBaseUrl());
      }

      CollectionAdminRequest.Create request = CollectionAdminRequest.createCollection(collectionName, "conf", numShards, numReplicas);
      request.setBasePath(nodes.get(random.nextInt(nodeCount)));

      client.asyncRequest(request, null, new RequestAsyncListener());

      cluster.waitForActiveCollection(collectionName, 15, TimeUnit.SECONDS, false, numShards, numShards * numReplicas, true, false);
    }

    @State(Scope.Thread)
    public static class Doc {
      public SolrInputDocument doc;

      public int cnt;

      @Setup(Level.Invocation) public void setupDoc() throws Exception {
        doc = new SolrInputDocument();
        doc.addField("id", PointsVsTrieIndex.BenchState.id.incrementAndGet());
        doc.addField("number_i", cnt++);
      }
    }

    @TearDown(Level.Iteration)
    public void doTearDown() throws Exception {
      cluster.shutdown();
    }

  }

  @Benchmark
  @Timeout(time = 300)
  public static void indexSmallDoc(PointsVsTrieIndex.BenchState state, PointsVsTrieIndex.BenchState.Doc docState) throws Exception {
    UpdateRequest updateRequest = new UpdateRequest();
    updateRequest.setBasePath(state.nodes.get(state.random.nextInt(state.nodeCount)) + "/" + state.collectionName);
    SolrInputDocument doc = docState.doc;

    updateRequest.add(doc);

    state.client.request(updateRequest, state.collectionName);
  }

  public static void main(String[] args) throws RunnerException {
    Options opt = new OptionsBuilder()
        .include(PointsVsTrieIndex.class.getSimpleName())
        .build();

    new Runner(opt).run();
  }

}