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
import org.apache.slider.agent.AgentMiniClusterTestBase
import org.apache.slider.client.SliderClient
import org.apache.slider.common.SliderExitCodes
import org.apache.slider.common.params.SliderActions
import org.apache.slider.core.exceptions.SliderException
import org.apache.slider.core.main.LauncherExitCodes
import org.apache.slider.core.main.ServiceLauncher
import org.junit.Test

@CompileStatic
@Slf4j

class TestBuildStandaloneAM extends AgentMiniClusterTestBase {

  @Test
  public void testBuildCluster() throws Throwable {
    String clustername = "test_build_cluster"
    createMiniCluster(clustername, configuration, 1, true)

    describe "verify that a build cluster is created but not started"

    ServiceLauncher<SliderClient> launcher = createOrBuildCluster(
        SliderActions.ACTION_BUILD,
        clustername,
        [:],
        [],
        true,
        false,
        agentDefOptions)
    SliderClient sliderClient = launcher.service
    addToTeardown(sliderClient);

    //verify that exists(live) is now false
    assert LauncherExitCodes.EXIT_FALSE ==
           sliderClient.actionExists(clustername, true)

    //but the cluster is still there for the default
    assert 0 == sliderClient.actionExists(clustername, false)

    def serviceRegistryClient = sliderClient.YARNRegistryClient
    ApplicationReport report = serviceRegistryClient.findInstance(clustername)
    assert report == null;

    //and a second attempt will fail as the cluster now exists
    try {
      ServiceLauncher<SliderClient> cluster2 = createOrBuildCluster(
          SliderActions.ACTION_BUILD,
          clustername,
          [:],
          [],
          false,
          false,
          agentDefOptions)
      fail("expected an exception, got $cluster2.service")
    } catch (SliderException e) {
      assertExceptionDetails(e, SliderExitCodes.EXIT_INSTANCE_EXISTS, "")
    }

    //thaw time
    ServiceLauncher<SliderClient> l2 = thawCluster(clustername, [], true)
    SliderClient thawed = l2.service
    addToTeardown(thawed);
    waitForClusterLive(thawed)
  }

}