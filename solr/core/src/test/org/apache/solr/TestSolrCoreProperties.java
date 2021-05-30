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
package org.apache.solr;

import org.apache.commons.io.FileUtils;
import org.apache.lucene.util.IOUtils;
import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.embedded.JettySolrRunner;
import org.apache.solr.client.solrj.impl.Http2SolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.common.params.SolrParams;
import org.apache.solr.common.util.NamedList;
import org.junit.AfterClass;
import org.junit.BeforeClass;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

/**
 * <p> Test for Loading core properties from a properties file </p>
 *
 *
 * @since solr 1.4
 */
public class TestSolrCoreProperties extends SolrJettyTestBase {
  private static JettySolrRunner jetty;
  private static int port;

  // TODO these properties files don't work with configsets

  @BeforeClass
  public static void beforeTestSolrCoreProperties() throws Exception {
    File homeDir = SolrTestUtil.createTempDir().toFile();

    File collDir = new File(homeDir, "collection1");
    File dataDir = new File(collDir, "data");
    File confDir = new File(collDir, "conf");

    homeDir.mkdirs();
    collDir.mkdirs();
    dataDir.mkdirs();
    confDir.mkdirs();

    FileUtils.copyFile(new File(SolrTestUtil.TEST_HOME(), "solr.xml"), new File(homeDir, "solr.xml"));
    String src_dir = SolrTestUtil.TEST_HOME() + "/collection1/conf";
    FileUtils.copyFile(new File(src_dir, "schema-tiny.xml"), 
                       new File(confDir, "schema.xml"));
    FileUtils.copyFile(new File(src_dir, "solrconfig-solcoreproperties.xml"), 
                       new File(confDir, "solrconfig.xml"));
    FileUtils.copyFile(new File(src_dir, "solrconfig.snippet.randomindexconfig.xml"), 
                       new File(confDir, "solrconfig.snippet.randomindexconfig.xml"));

    Properties p = new Properties();
    p.setProperty("foo.foo1", "f1");
    p.setProperty("foo.foo2", "f2");
    Writer fos = new OutputStreamWriter(new FileOutputStream(new File(confDir, "solrcore.properties")), StandardCharsets.UTF_8);
    p.store(fos, null);
    IOUtils.close(fos);

    Files.createFile(collDir.toPath().resolve("core.properties"));


    Properties nodeProperties = new Properties();
    // this sets the property for jetty starting SolrDispatchFilter
    if (System.getProperty("solr.data.dir") == null && System.getProperty("solr.hdfs.home") == null) {
      nodeProperties.setProperty("solr.data.dir", SolrTestUtil.createTempDir().toFile().getCanonicalPath());
    }
    jetty = new JettySolrRunner(homeDir.getAbsolutePath(), nodeProperties, buildJettyConfig("/solr"));

    jetty.start();
    port = jetty.getLocalPort();

    //createJetty(homeDir.getAbsolutePath(), null, null);
  }

  @AfterClass
  public static void afterTestSolrCoreProperties() throws Exception {
    jetty.stop();
    jetty = null;
  }

  public void testSimple() throws Exception {
    SolrParams params = params("q", "*:*",
                               "echoParams", "all");
    QueryResponse res;
    try (SolrClient client = getSolrClient(jetty)) {
      res = client.query(params);
      assertEquals(0, res.getResults().getNumFound());
    }

    NamedList echoedParams = (NamedList) res.getHeader().get("params");
    assertEquals(res.toString(), "all", echoedParams.get("echoParams"));
   // assertEquals("f2", echoedParams.get("p2"));
  }

  public synchronized SolrClient getSolrClient(JettySolrRunner jetty) {

    return createNewSolrClient(jetty);
  }

  /**
   * Create a new solr client.
   * If createJetty was called, an http implementation will be created,
   * otherwise an embedded implementation will be created.
   * Subclasses should override for other options.
   */
  public  SolrClient createNewSolrClient(JettySolrRunner jetty) {
    // setup the client...
    final String url = jetty.getBaseUrl().toString() + '/' + "collection1";
    try {
      Http2SolrClient client = getHttpSolrClient(url, DEFAULT_CONNECTION_TIMEOUT);
      return client;
    } catch (final Exception ex) {
      throw new RuntimeException(ex);
    }
  }

}
