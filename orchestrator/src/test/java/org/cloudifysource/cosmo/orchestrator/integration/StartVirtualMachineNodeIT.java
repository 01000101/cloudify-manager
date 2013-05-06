/*******************************************************************************
 * Copyright (c) 2013 GigaSpaces Technologies Ltd. All rights reserved
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package org.cloudifysource.cosmo.orchestrator.integration;

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import org.cloudifysource.cosmo.cep.ResourceMonitorServer;
import org.cloudifysource.cosmo.cep.ResourceMonitorServerConfiguration;
import org.cloudifysource.cosmo.cloud.driver.CloudDriver;
import org.cloudifysource.cosmo.cloud.driver.vagrant.VagrantCloudDriver;
import org.cloudifysource.cosmo.messaging.broker.MessageBrokerServer;
import org.cloudifysource.cosmo.messaging.producer.MessageProducer;
import org.cloudifysource.cosmo.orchestrator.recipe.JsonRecipe;
import org.cloudifysource.cosmo.orchestrator.workflow.RuoteRuntime;
import org.cloudifysource.cosmo.orchestrator.workflow.RuoteWorkflow;
import org.cloudifysource.cosmo.resource.CloudResourceProvisioner;
import org.cloudifysource.cosmo.statecache.RealTimeStateCache;
import org.cloudifysource.cosmo.statecache.RealTimeStateCacheConfiguration;
import org.drools.io.Resource;
import org.drools.io.ResourceFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Optional;
import org.testng.annotations.Parameters;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * An integration test combining the following components:
 *  1. JSON recipe.
 *  2. Ruote workflow.
 *  3. Vagrant cloud driver.
 *  4. Message Broker.
 *  5. Real time state cache.
 *  6. Drools.
 *
 * @author Idan Moyal
 * @since 0.1
 */
public class StartVirtualMachineNodeIT {

    private static final String RULE_FILE = "/org/cloudifysource/cosmo/cep/AgentFailureDetector.drl";
    private static final String RECIPE_PATH = "recipes/json/tomcat/vm_node_recipe.json";

    // message broker that isolates server
    private MessageBrokerServer broker;
    private URI inputUri;
    private RealTimeStateCache cache;
    private RuoteRuntime runtime;
    private CloudResourceProvisioner provisioner;
    private URI resourceProvisionerTopic;
    private URI stateCacheTopic;
    private URI resourceMonitorTopic;
    private URI agentTopic;
    private ResourceMonitorServer resourceMonitor;
    private CloudDriver cloudDriver;
    private File tempDirectory;


    @Test(groups = "vagrant")
    public void testStartVirtualMachine() throws ExecutionException, InterruptedException {
        final JsonRecipe recipe = JsonRecipe.load(readRecipe());
        final List<String> nodes = recipe.getNodes();
        final String resourceId = nodes.get(0);
        final Map<String, Object> resource = recipe.get(resourceId).get();
        final List<?> workflows = (List<?>) resource.get("workflows");
        final String workflowName = workflows.get(0).toString();
        final String workflow = loadWorkflow(resourceId, workflowName);
        final RuoteWorkflow ruoteWorkflow = RuoteWorkflow.createFromString(workflow, runtime);

        // Execute workflow
        final Map<String, Object> workitem = Maps.newHashMap();
        workitem.put("resource_id", resourceId);
        ruoteWorkflow.execute(workitem);
    }

    private String loadWorkflow(String resourceId, String workflowName) {
        final String workflowFileName = String.format("%s_%s.radial", resourceId, workflowName);
        final String parentDirectory = new File(Resources.getResource(RECIPE_PATH).getPath()).getParent();
        final File file = new File(parentDirectory, workflowFileName);
        Preconditions.checkArgument(file.exists());
        try {
            return Files.toString(file, Charsets.UTF_8);
        } catch (IOException e) {
            throw Throwables.propagate(e);
        }
    }

    private String readRecipe() {
        final URL resource = Resources.getResource(RECIPE_PATH);
        try {
            return Resources.toString(resource, Charsets.UTF_8);
        } catch (IOException e) {
            throw Throwables.propagate(e);
        }
    }


    @BeforeMethod
    @Parameters({ "port" })
    public void startServer(@Optional("8080") int port) {
        tempDirectory = Files.createTempDir();
        startMessagingBroker(port);
        inputUri = URI.create("http://localhost:" + port + "/input/");
        resourceProvisionerTopic = inputUri.resolve("resource-manager");
        stateCacheTopic = inputUri.resolve("state-cache");
        resourceMonitorTopic = inputUri.resolve("resource-monitor");
        agentTopic = inputUri.resolve("agent");
        RealTimeStateCacheConfiguration config = new RealTimeStateCacheConfiguration();
        config.setMessageTopic(stateCacheTopic);
        startRealTimeStateCache(config);

        Map<String, Object> runtimeProperties = Maps.newHashMap();
        runtimeProperties.put("state_cache", cache);
        runtimeProperties.put("broker_uri", inputUri);
        runtimeProperties.put("message_producer", new MessageProducer());
        runtime = RuoteRuntime.createRuntime(runtimeProperties);
        startCloudResourceProvisioner();
        startResourceMonitor();
    }

    private void startRealTimeStateCache(RealTimeStateCacheConfiguration config) {
        cache = new RealTimeStateCache(config);
        cache.start();
    }

    private void startCloudResourceProvisioner() {
        cloudDriver = new VagrantCloudDriver(tempDirectory);
        provisioner = new CloudResourceProvisioner(new VagrantCloudDriver(tempDirectory), resourceProvisionerTopic);
        provisioner.start();
    }

    @AfterMethod(alwaysRun = true)
    public void stopServer() {
        cloudDriver.terminateMachines();
        tempDirectory.delete();
        stopResourceMonitor();
        stopRealTimeStateCache();
        stopCloudResourceProvisioner();
        stopMessageBroker();
    }

    private void stopRealTimeStateCache() {
        if (cache != null) {
            cache.stop();
        }
    }

    private void stopCloudResourceProvisioner() {
        if (provisioner != null) {
            provisioner.stop();
        }
    }

    private void startMessagingBroker(int port) {
        broker = new MessageBrokerServer();
        broker.start(port);
    }

    private void stopMessageBroker() {
        if (broker != null) {
            broker.stop();
        }
    }

    private void startResourceMonitor() {
        ResourceMonitorServerConfiguration config =
                new ResourceMonitorServerConfiguration();
        final Resource resource = ResourceFactory.newClassPathResource(RULE_FILE, this.getClass());
        config.setDroolsResource(resource);
        config.setResourceMonitorTopic(resourceMonitorTopic);
        config.setStateCacheTopic(stateCacheTopic);
        config.setAgentTopic(agentTopic);
        resourceMonitor = new ResourceMonitorServer(config);
        resourceMonitor.start();
    }

    private void stopResourceMonitor() {
        if (resourceMonitor != null) {
            resourceMonitor.stop();
        }
    }

}
