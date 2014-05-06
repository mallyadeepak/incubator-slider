/*
 * Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.apache.slider.agent.standalone

import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import org.apache.hadoop.yarn.api.records.ApplicationReport
import org.apache.hadoop.yarn.api.records.YarnApplicationState
import org.apache.hadoop.yarn.conf.YarnConfiguration
import org.apache.slider.agent.AgentMiniClusterTestBase
import org.apache.slider.api.ClusterNode
import org.apache.slider.client.SliderClient
import org.apache.slider.common.SliderKeys
import org.apache.slider.common.params.ActionRegistryArgs
import org.apache.slider.core.main.ServiceLauncher
import org.apache.slider.core.persist.JsonSerDeser
import org.apache.slider.core.registry.docstore.ConfigFormat
import org.apache.slider.core.registry.docstore.PublishedConfigSet
import org.apache.slider.core.registry.docstore.PublishedConfiguration
import org.apache.slider.core.registry.info.CustomRegistryConstants
import org.apache.slider.core.registry.info.ServiceInstanceData
import org.apache.slider.core.registry.retrieve.RegistryRetriever
import org.apache.slider.server.appmaster.web.rest.RestPaths
import org.apache.slider.server.services.curator.CuratorServiceInstance
import org.apache.slider.server.services.curator.RegistryBinderService
import org.junit.Test

/**
 * create masterless AMs and work with them. This is faster than
 * bringing up full clusters
 */
@CompileStatic
@Slf4j

class TestStandaloneRegistryAM extends AgentMiniClusterTestBase {


  public static final String YARN_SITE = "yarn-site.xml"

  @Test
  public void testRegistryAM() throws Throwable {
    

    describe "create a masterless AM then perform registry operations on it"

    //launch fake master
    String clustername = "test_standalone_registry_am"
    createMiniCluster(clustername, configuration, 1, true)
    ServiceLauncher<SliderClient> launcher
    launcher = createMasterlessAM(clustername, 0, true, false)
    SliderClient client = launcher.service
    addToTeardown(client);

    ApplicationReport report = waitForClusterLive(client)
    logReport(report)
    List<ApplicationReport> apps = client.applications;

    List<ClusterNode> clusterNodes = client.listClusterNodesInRole(
        SliderKeys.COMPONENT_AM)
    assert clusterNodes.size() == 1

    ClusterNode masterNode = clusterNodes[0]
    log.info("Master node = ${masterNode}");

    List<ClusterNode> nodes
    String[] uuids = client.listNodeUUIDsByRole(SliderKeys.COMPONENT_AM)
    assert uuids.length == 1;
    nodes = client.listClusterNodes(uuids);
    assert nodes.size() == 1;
    describe "AM Node UUID=${uuids[0]}"

    nodes = listNodesInRole(client, SliderKeys.COMPONENT_AM)
    assert nodes.size() == 1;
    nodes = listNodesInRole(client, "")
    assert nodes.size() == 1;
    ClusterNode master = nodes[0]
    assert master.role == SliderKeys.COMPONENT_AM




    String username = client.username
    def serviceRegistryClient = client.YARNRegistryClient
    describe("list of all applications")
    logApplications(apps)
    describe("apps of user $username")
    List<ApplicationReport> userInstances = serviceRegistryClient.listInstances()
    logApplications(userInstances)
    assert userInstances.size() == 1
    describe("named app $clustername")
    ApplicationReport instance = serviceRegistryClient.findInstance(clustername)
    logReport(instance)
    assert instance != null

    //switch to the ZK-based registry

    describe "service registry names"
    RegistryBinderService<ServiceInstanceData> registry = client.registry
    def names = registry.queryForNames();
    dumpRegistryNames(names)

    List<String> instanceIds = client.listRegistryInstanceIDs()


    dumpRegistryInstanceIDs(instanceIds)
    assert instanceIds.size() == 1

    List<CuratorServiceInstance<ServiceInstanceData>> instances = client.listRegistryInstances(
    )
    dumpRegistryInstances(instances)

    assert instances.size() == 1

    def amInstance = instances[0]
    def serviceInstanceData = amInstance.payload

    def externalEndpoints = serviceInstanceData.externalView.endpoints

    // hit the registry web page

    def registryEndpoint = externalEndpoints.get(
        CustomRegistryConstants.REGISTRY_REST_API)
    assert registryEndpoint != null
    def registryURL = registryEndpoint.asURL()
    describe("Registry WADL @ $registryURL")
    
    def publisherEndpoint = externalEndpoints.get(CustomRegistryConstants.PUBLISHER_REST_API)
    assert publisherEndpoint != null
    def publisherURL = publisherEndpoint.asURL()
    def publisher = publisherURL.toString()
    describe("Publisher")

    def publishedJSON = GET(publisherURL)
    log.info(publishedJSON)
    JsonSerDeser< PublishedConfigSet> serDeser= new JsonSerDeser<PublishedConfigSet>(
        PublishedConfigSet)
    def configSet = serDeser.fromJson(publishedJSON)
    assert configSet.size() >= 1
    assert configSet.contains(YARN_SITE)
    PublishedConfiguration publishedYarnSite = configSet.get(YARN_SITE)

    assert publishedYarnSite.empty
    
    //get the full URL
    def yarnSitePublisher = appendToURL(publisher, YARN_SITE)

    String confJSON = GET(yarnSitePublisher)
    log.info(confJSON)
    JsonSerDeser< PublishedConfiguration> confSerDeser =
        new JsonSerDeser<PublishedConfiguration>(PublishedConfiguration)

    publishedYarnSite = confSerDeser.fromJson(confJSON)
    
    assert !publishedYarnSite.empty


    //get the XML
    def yarnSiteXML = appendToURL(yarnSitePublisher, "xml")


    String confXML = GET(yarnSiteXML)
    log.info("Conf XML at $yarnSiteXML = \n $confXML")



    describe("Registry List")
    log.info(GET(registryURL, RestPaths.REGISTRY_SERVICE ))


    describe "Registry Retrieval"
    // retrieval

    RegistryRetriever retriever = new RegistryRetriever(serviceInstanceData)
    log.info retriever.toString()
    
    assert retriever.hasConfigurations(true)
    def externalConf = retriever.getConfigurations(true)
    externalConf.keys().each { String key ->
      def config = externalConf.get(key)
      log.info "$key -- ${config.description}"
    }
    assert externalConf[YARN_SITE]


    def yarnSite = retriever.retrieveConfiguration(YARN_SITE, true)
    assert !yarnSite.empty
    def siteXML = yarnSite.asConfiguration()
    def rmAddr = siteXML.get(YarnConfiguration.RM_ADDRESS)
    assert rmAddr
    
    
    describe "Internal configurations"
    assert !retriever.hasConfigurations(false)
    try {
      retriever.getConfigurations(false)
      fail( "expected a failure")
    } catch (FileNotFoundException fnfe) {
      //expected
    }


    // retrieval via API
    ActionRegistryArgs registryArgs = new ActionRegistryArgs()
    registryArgs.name = serviceInstanceData.id;
    registryArgs.verbose = true

    // list
    registryArgs.list = true;
    describe registryArgs.toString()
    assert 0 == client.actionRegistry(registryArgs)

    // listconf 
    registryArgs.list = false;
    registryArgs.listConf = true
    describe registryArgs.toString() 
    
    assert 0 == client.actionRegistry(registryArgs)

  
    
    
    // listconf --internal
    registryArgs.list = false;
    registryArgs.listConf = true
    registryArgs.internal = true
    describe registryArgs.toString()
    try {
      assert 0 == client.actionRegistry(registryArgs)
      fail("expected a failure")
    } catch (FileNotFoundException fnfe) {
      //expected
    }

    registryArgs.list = false;
    registryArgs.listConf = false
    registryArgs.internal = false
    registryArgs.format = "properties"
    registryArgs.getConf = YARN_SITE
    
    describe registryArgs.toString()
    assert 0 == client.actionRegistry(registryArgs)

    File outputDir = new File("target/test_standalone_registry_am/output")
    outputDir.mkdirs()

    registryArgs.dest = outputDir
    describe registryArgs.toString()
    assert 0 == client.actionRegistry(registryArgs)
    assert new File(outputDir,YARN_SITE + ".properties").exists()

    registryArgs.format = "xml"
    assert 0 == client.actionRegistry(registryArgs)
    assert new File(outputDir,YARN_SITE + ".xml").exists()



    describe "freeze cluster"
    //now kill that cluster
    assert 0 == clusterActionFreeze(client, clustername)
    //list it & See if it is still there
    ApplicationReport oldInstance = serviceRegistryClient.findInstance(
        clustername)
    assert oldInstance != null
    assert oldInstance.yarnApplicationState >= YarnApplicationState.FINISHED


    sleep(20000)

    instances = client.listRegistryInstances()
    assert instances.size() == 0

  }


}