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

package org.cloudifysource.cosmo.orchestrator.workflow;

import com.google.common.base.Charsets;
import com.google.common.base.Objects;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Resources;
import org.cloudifysource.cosmo.config.TestConfig;
import org.cloudifysource.cosmo.dsl.packaging.DSLPackage;
import org.cloudifysource.cosmo.dsl.packaging.DSLPackageProcessor;
import org.cloudifysource.cosmo.dsl.packaging.ExtractedDSLPackageDetails;
import org.cloudifysource.cosmo.messaging.config.MockMessageConsumerConfig;
import org.cloudifysource.cosmo.messaging.config.MockMessageProducerConfig;
import org.cloudifysource.cosmo.messaging.producer.MessageProducer;
import org.cloudifysource.cosmo.orchestrator.integration.config.RuoteRuntimeConfig;
import org.cloudifysource.cosmo.orchestrator.integration.config.TemporaryDirectoryConfig;
import org.cloudifysource.cosmo.orchestrator.workflow.config.DefaultRuoteWorkflowConfig;
import org.cloudifysource.cosmo.statecache.RealTimeStateCache;
import org.cloudifysource.cosmo.statecache.config.RealTimeStateCacheConfig;
import org.cloudifysource.cosmo.statecache.messages.StateChangedMessage;
import org.cloudifysource.cosmo.tasks.MockCeleryTaskWorker;
import org.cloudifysource.cosmo.tasks.TaskReceivedListener;
import org.cloudifysource.cosmo.tasks.config.MockCeleryTaskWorkerConfig;
import org.cloudifysource.cosmo.tasks.config.MockTaskExecutorConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.testng.AbstractTestNGSpringContextTests;
import org.testng.annotations.Test;

import javax.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

import static org.fest.assertions.api.Assertions.assertThat;

/**
 * @author Idan Moyal
 * @since 0.1
 */
@ContextConfiguration(classes = { RuoteExecutePlanTest.Config.class })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public class RuoteExecutePlanTest extends AbstractTestNGSpringContextTests {

    private static final String CLOUDIFY_MANAGEMENT = "cloudify.management";

    /**
     */
    @Configuration
    @Import({
            MockMessageConsumerConfig.class,
            MockMessageProducerConfig.class,
            RealTimeStateCacheConfig.class,
            DefaultRuoteWorkflowConfig.class,
            RuoteRuntimeConfig.class,
            TemporaryDirectoryConfig.class,
            MockTaskExecutorConfig.class,
            MockCeleryTaskWorkerConfig.class
    })
    @PropertySource("org/cloudifysource/cosmo/orchestrator/integration/config/test.properties")
    static class Config extends TestConfig {
    }

    @Inject
    private RuoteRuntime ruoteRuntime;

    @Inject
    private RuoteWorkflow ruoteWorkflow;

    @Inject
    private RealTimeStateCache stateCache;

    @Inject
    private MessageProducer messageProducer;

    @Value("${cosmo.state-cache.topic}")
    private URI stateCacheTopic;

    @Inject
    private TemporaryDirectoryConfig.TemporaryDirectory temporaryDirectory;

    @Inject
    private MockCeleryTaskWorker worker;


    private void testPlanExecutionDsl(String dslFile) throws IOException, InterruptedException {
        String machineId = "mysql_template.mysql_machine";
        String databaseId = "mysql_template.mysql_database_server";
        OperationsDescriptor[] descriptors = {
            new OperationsDescriptor(CLOUDIFY_MANAGEMENT, "provisioner_plugin", new String[] {"create", "start"}),
            new OperationsDescriptor(machineId, "configurer_plugin", new String[] {"install", "start"}),
            new OperationsDescriptor(machineId, "schema_configurer_plugin", new String[] {"create"})
        };

        testPlanExecution(dslFile, new String[]{machineId, databaseId}, descriptors);
    }

    @Test(timeOut = 30000)
    public void testPlanExecutionDslWithBaseImports() throws IOException, InterruptedException {
        String dslFile = "org/cloudifysource/cosmo/dsl/dsl-with-base-imports.yaml";
        String machineId = "mysql_template.mysql_host";
        String databaseId = "mysql_template.mysql_database_server";
        OperationsDescriptor[] descriptors = {
            new OperationsDescriptor(
                CLOUDIFY_MANAGEMENT,
                "cloudify.tosca.artifacts.plugin.host.provisioner",
                new String[]{"provision", "start"}),
            new OperationsDescriptor(
                machineId,
                "cloudify.tosca.artifacts.plugin.middleware_component.installer",
                new String[]{"install", "start"}),
            new OperationsDescriptor(
                machineId,
                "cloudify.tosca.artifacts.plugin.app_module.installer",
                new String[]{"deploy", "start"})
        };
        testPlanExecution(dslFile, new String[] {machineId, databaseId}, descriptors);
    }

    @Test(timeOut = 30000)
    public void testPlanExecutionDslWithBaseImportsDependencies() throws IOException, InterruptedException {
        String dslFile = "org/cloudifysource/cosmo/dsl/dsl-with-base-imports.yaml";
        String machineId = "mysql_template.mysql_host";
        String databaseId = "mysql_template.mysql_database_server";
        OperationsDescriptor[] descriptors = {
            new OperationsDescriptor(
                    CLOUDIFY_MANAGEMENT,
                    "cloudify.tosca.artifacts.plugin.host.provisioner",
                    new String[]{"provision", "start"}),
            new OperationsDescriptor(
                    machineId,
                    "cloudify.tosca.artifacts.plugin.middleware_component.installer",
                    new String[]{"install", "start"}),
            new OperationsDescriptor(
                    machineId,
                    "cloudify.tosca.artifacts.plugin.app_module.installer",
                    new String[]{"deploy", "start"})
        };
        testPlanExecution(dslFile, new String[] {machineId, databaseId}, descriptors, true);
    }

    /**
     * For POC purposes - this test should be disabled.
     */
    @Test(timeOut = 60000, enabled = false)
    public void testPlanExecutionPoc() throws IOException, InterruptedException {
        String dslFile = "org/cloudifysource/cosmo/dsl/unit/poc/poc-dsl1.yaml";
        String machineId = "mysql_template.mysql_host";
        String databaseId = "mysql_template.mysql_database_server";
        String schemaId = "mysql_template.mysql_schema";

        final Map<String, Object> fields = Maps.newHashMap();
        String dslLocation;
        if (Files.exists(Paths.get(dslFile))) {
            dslLocation = dslFile;
        } else {
            dslLocation = Resources.getResource(dslFile).getFile();
        }
        fields.put("dsl", dslLocation);

        final Object wfid = ruoteWorkflow.asyncExecute(fields);

        Thread.sleep(10000);
        messageProducer.send(stateCacheTopic, createReachableStateCacheMessage(machineId));
        Thread.sleep(10000);
        messageProducer.send(stateCacheTopic, createReachableStateCacheMessage(databaseId));
        Thread.sleep(5000);
        messageProducer.send(stateCacheTopic, createReachableStateCacheMessage(schemaId));

        ruoteRuntime.waitForWorkflow(wfid);
    }


    @Test(timeOut = 30000)
    public void testPlanExecutionFromPackage() throws IOException, InterruptedException {
        URL resource = Resources.getResource("org/cloudifysource/cosmo/dsl/unit/packaging/basic-packaging.yaml");
        String dsl = Resources.toString(
                resource, Charsets.UTF_8);
        DSLPackage dslPackage = new DSLPackage.DSLPackageBuilder()
                .addFile("app.yaml", dsl)
                .build();
        final Path packagePath = Paths.get(temporaryDirectory.get().getCanonicalPath(), "app.zip");
        dslPackage.write(packagePath.toFile());

        final ExtractedDSLPackageDetails processed =
                DSLPackageProcessor.process(packagePath.toFile(), temporaryDirectory.get());

        OperationsDescriptor[] descriptors = {
            new OperationsDescriptor(
                    CLOUDIFY_MANAGEMENT,
                    "provisioner_plugin",
                    new String[] {"start"})
        };

        testPlanExecution(processed.getDslPath().toString(), null, descriptors);
    }

    @Test(timeOut = 30000)
    public void testPlanExecutionFromPackageWithImports() throws IOException, InterruptedException {
        final String root = "org/cloudifysource/cosmo/dsl/unit/packaging/imports/";
        DSLPackage dslPackage = new DSLPackage.DSLPackageBuilder()
                .addFile("app.yaml", getResourceAsString(root + "packaging-with-imports.yaml"))
                .addFile("definitions/packaging-with-imports0.yaml",
                        getResourceAsString(root + "packaging-with-imports0.yaml"))
                .addFile("definitions/relative_from_base/packaging-with-imports1.yaml",
                        getResourceAsString(root + "packaging-with-imports1.yaml"))
                .addFile("definitions/fixed_from_base/packaging-with-imports2.yaml",
                        getResourceAsString(root + "packaging-with-imports2.yaml"))
                .addFile("definitions/packaging-with-imports3.yaml",
                        getResourceAsString(root + "packaging-with-imports3.yaml"))
                .addFile("definitions/relative_path/packaging-with-imports4.yaml",
                        getResourceAsString(root + "packaging-with-imports4.yaml"))
                .addFile("definitions/fixed_path/packaging-with-imports5.yaml",
                        getResourceAsString(root + "packaging-with-imports5.yaml"))
                .build();
        final Path packagePath = Paths.get(temporaryDirectory.get().getCanonicalPath(), "app-imports.zip");
        dslPackage.write(packagePath.toFile());

        final ExtractedDSLPackageDetails processed =
                DSLPackageProcessor.process(packagePath.toFile(), temporaryDirectory.get());

        String[] operations =
                new String[]{"operation0", "operation1", "operation2", "operation3", "operation4", "operation5"};
        OperationsDescriptor[] descriptors = {new OperationsDescriptor(
                CLOUDIFY_MANAGEMENT,
                "provisioner_plugin",
                operations)};

        testPlanExecution(processed.getDslPath().toString(), null, descriptors);
    }

    @Test(timeOut = 30000)
    public void testPlanExecutionWithOverriddenGlobalPlan() throws IOException, InterruptedException {
        String dslFile = "org/cloudifysource/cosmo/dsl/unit/global_plan/dsl-with-with-full-global-plan.yaml";
        OperationsDescriptor descriptor = new OperationsDescriptor(
                CLOUDIFY_MANAGEMENT,
                "cloudify.tosca.artifacts.plugin.host.provisioner",
                new String[]{"provision", "start", "provision", "start"});
        OperationsDescriptor[] descriptors = {descriptor};
        testPlanExecution(dslFile, null, descriptors);
    }

    private void testPlanExecution(
            String dslFile,
            String[] reachableIds,
            OperationsDescriptor[] expectedOperations) throws IOException, InterruptedException {
        testPlanExecution(dslFile, reachableIds, expectedOperations, false);
    }

    private void testPlanExecution(
            String dslFile,
            String[] reachableIds,
            final OperationsDescriptor[] expectedOperations,
            final boolean assertExecutionOrder) throws IOException,
            InterruptedException {

        String dslLocation;
        if (Files.exists(Paths.get(dslFile))) {
            dslLocation = dslFile;
        } else {
            dslLocation = Resources.getResource(dslFile).getFile();
        }
        Map<String, Object> fields = Maps.newHashMap();
        fields.put("dsl", dslLocation);

        final List<String> expectedTasks = Lists.newArrayList();
        final List<String> expectedTasksWithSeparator = Lists.newArrayList();
        for (OperationsDescriptor pluginOperations : expectedOperations) {
            for (String operationName : pluginOperations.operations) {
                String expectedTask = String.format("cosmo.%s.tasks.%s", pluginOperations.pluginName, operationName);
                expectedTasks.add(expectedTask);
                expectedTasksWithSeparator.add(expectedTask);
            }
            expectedTasksWithSeparator.add("");
        }

        final List<String> executions = Lists.newArrayList();
        TaskReceivedListener listener = new TaskReceivedListener() {
            @Override
            public synchronized void onTaskReceived(String target, String taskName, Map<String, Object> kwargs) {
                System.out.println(" -- received: [target=" + target + ", task=" + taskName + "]");
                if (assertExecutionOrder) {
                    executions.add(taskName);
                    if (expectedTasksWithSeparator.isEmpty()) {
                        return;
                    }
                    if (Objects.equal(expectedTasksWithSeparator.get(0), taskName)) {
                        expectedTasksWithSeparator.remove(0);
                        if (expectedTasksWithSeparator.get(0).length() == 0) {
                            String nodeId = kwargs.get("__cloudify_id").toString();
                            messageProducer.send(stateCacheTopic, createReachableStateCacheMessage(nodeId));
                            expectedTasksWithSeparator.remove(0);
                        }
                    }
                }
            }
        };

        int latchCount = 0;
        for (OperationsDescriptor descriptor : expectedOperations) {
            latchCount += descriptor.operations.length;
        }
        final CountDownLatch latch = new CountDownLatch(latchCount);
        for (OperationsDescriptor descriptor : expectedOperations) {
            worker.addListener(descriptor.target,
                    new PluginExecutionMessageConsumerListener(latch, descriptor.operations, !assertExecutionOrder));
            worker.addListener(descriptor.target, listener);
        }

        final Object wfid = ruoteWorkflow.asyncExecute(fields);

        Thread.sleep(100);
        if (reachableIds != null && !assertExecutionOrder) {
            for (String reachableId : reachableIds) {
                messageProducer.send(stateCacheTopic, createReachableStateCacheMessage(reachableId));
            }
        }

        ruoteRuntime.waitForWorkflow(wfid);
        latch.await();

        if (assertExecutionOrder) {
            assertThat(executions).isEqualTo(expectedTasks);
            assertThat(expectedTasksWithSeparator).isEmpty();
        }
    }

    @Test(timeOut = 30000)
    public void testRuntimePropertiesInjection() throws IOException, InterruptedException {
        final String dslFile = "org/cloudifysource/cosmo/dsl/dsl-with-base-imports.yaml";
        final String machineId = "mysql_template.mysql_host";
        final String ip = "10.0.0.1";

        Map<String, Object> fields = Maps.newHashMap();
        fields.put("dsl", Resources.getResource(dslFile).getFile());
        ruoteWorkflow.asyncExecute(fields);

        final CountDownLatch latch = new CountDownLatch(1);

        worker.addListener(machineId, new TaskReceivedListener() {
            @Override
            public void onTaskReceived(String target, String taskName, Map<String, Object> kwargs) {
                Map<?, ?> runtimeProperties = (Map<?, ?>) kwargs.get("cloudify_runtime");
                if (runtimeProperties.containsKey(machineId)) {
                    Map<?, ?> machineProperties = (Map<?, ?>) runtimeProperties.get(machineId);
                    if (Objects.equal(ip, machineProperties.get("ip"))) {
                        latch.countDown();
                    }
                }
            }
        });

        StateChangedMessage message = createReachableStateCacheMessage(machineId);
        message.getState().put("ip", ip);
        messageProducer.send(stateCacheTopic, message);

        latch.await();
    }

    @Test(timeOut = 30000)
    public void testExecuteOperation() throws InterruptedException {
        final String dslFile = "org/cloudifysource/cosmo/dsl/unit/plugins/target/plugin-targets.yaml";
        final String machineId = "plugins_template.host_template";
        final String remotePluginTarget = "cloudify.management";
        final String agentTaskPrefix = "cosmo.cloudify.tosca.artifacts.plugin.middleware_component.installer.tasks";
        final String remoteTaskPrefix = "cosmo.cloudify.tosca.artifacts.plugin.host.provisioner.tasks";

        Map<String, Object> fields = Maps.newHashMap();
        fields.put("dsl", Resources.getResource(dslFile).getFile());
        ruoteWorkflow.asyncExecute(fields);

        final CountDownLatch latch = new CountDownLatch(3);

        worker.addListener(remotePluginTarget, new TaskReceivedListener() {
            @Override
            public void onTaskReceived(String target, String taskName, Map<String, Object> kwargs) {
                if (taskName.startsWith(remoteTaskPrefix) && taskName.endsWith("provision")) {
                    latch.countDown();
                }
            }
        });
        worker.addListener(machineId, new TaskReceivedListener() {
            @Override
            public void onTaskReceived(String target, String taskName, Map<String, Object> kwargs) {
                if (taskName.startsWith(agentTaskPrefix) &&
                        (taskName.endsWith("install") || taskName.endsWith("start"))) {
                    latch.countDown();
                }
            }
        });

        StateChangedMessage message = createReachableStateCacheMessage(machineId);
        messageProducer.send(stateCacheTopic, message);

        latch.await();
    }

    private String getResourceAsString(String resourceName) {
        final URL resource = Resources.getResource(resourceName);
        try {
            return Resources.toString(resource, Charsets.UTF_8);
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }
    }

    private StateChangedMessage createReachableStateCacheMessage(String resourceId) {
        final StateChangedMessage message = new StateChangedMessage();
        message.setResourceId(resourceId);
        final Map<String, Object> state = Maps.newHashMap();
        state.put("reachable", "true");
        message.setState(state);
        return message;
    }

    /**
     */
    private static class OperationsDescriptor {
        final String target;
        final String pluginName;
        final String[] operations;
        private OperationsDescriptor(String target, String pluginName, String[] operations) {
            this.target = target;
            this.pluginName = pluginName;
            this.operations = operations;
        }
    }

    /**
     */
    private static class PluginExecutionMessageConsumerListener implements TaskReceivedListener {
        private CountDownLatch latch;
        private boolean removeExecutedOperations;
        private List<String> operations;
        PluginExecutionMessageConsumerListener(CountDownLatch latch,
                                               String[] operations,
                                               boolean removeExecutedOperations) {
            this.latch = latch;
            this.removeExecutedOperations = removeExecutedOperations;
            this.operations = Lists.newLinkedList(Arrays.asList(operations));
        }

        @Override
        public void onTaskReceived(String target, String taskName, Map<String, Object> kwargs) {
            String operationName = extractOperationName(taskName);
            for (Iterator<String> iterator = operations.iterator(); iterator.hasNext();) {
                String expectedOperation =  iterator.next();
                if (operationName.equals(expectedOperation)) {
                    latch.countDown();
                    if (removeExecutedOperations) {
                        iterator.remove();
                    }
                    break;
                }
            }
        }
    }

    private static String extractOperationName(String taskName) {
        String[] splitName = taskName.split("\\.");
        return splitName[splitName.length - 1];
    }

}
