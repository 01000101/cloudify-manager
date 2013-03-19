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
 ******************************************************************************/
package org.cloudifysource.cosmo;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import org.cloudifysource.cosmo.mock.MockAgent;
import org.cloudifysource.cosmo.mock.MockManagement;
import org.cloudifysource.cosmo.mock.MockTaskContainer;
import org.cloudifysource.cosmo.mock.MockTaskContainers;
import org.cloudifysource.cosmo.mock.TaskConsumerRegistrar;
import org.cloudifysource.cosmo.mock.ssh.MockSSHAgent;
import org.cloudifysource.cosmo.service.id.AliasGroupId;
import org.cloudifysource.cosmo.service.id.AliasId;
import org.cloudifysource.cosmo.service.lifecycle.LifecycleName;
import org.cloudifysource.cosmo.time.MockCurrentTimeProvider;
import org.junit.AfterClass;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.log.TextFormatter;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.logging.Logger;

import static org.cloudifysource.cosmo.service.tasks.UpdateDeploymentCommandlineTask.cli;

/**
 * Base class for Unit Tests that require management mock.
 * @param <T> - The type of management mock used.
 *
 * @author itaif
 * @since 0.1
 */
public abstract class AbstractServiceGridTest<T extends MockManagement> {

    protected final AliasGroupId web = new AliasGroupId("web");
    protected final AliasGroupId db = new AliasGroupId("db");
    protected final LifecycleName tomcat = new LifecycleName("tomcat");
    protected final LifecycleName cassandra = new LifecycleName("cassandra");

    private MockTaskContainers containers;
    private final Logger logger;
    private T management;
    private MockCurrentTimeProvider timeProvider;
    private TaskConsumerRegistrar taskConsumerRegistrar;

    public AbstractServiceGridTest() {
        logger = Logger.getLogger(this.getClass().getName());
        setSimpleLoggerFormatter(logger);
    }

    @BeforeClass
    public void beforeClass() {

        timeProvider = new MockCurrentTimeProvider(0);
        taskConsumerRegistrar = new TaskConsumerRegistrar() {

            @Override
            public void registerTaskConsumer(
                    final Object taskConsumer, final URI taskConsumerId) {

                MockTaskContainer container = getManagement().newContainer(taskConsumerId, taskConsumer);
                containers.addContainer(container);
            }

            @Override
            public Optional<Object> unregisterTaskConsumer(final URI taskConsumerId) {
                Optional<MockTaskContainer> mockTaskContainer = containers.findContainer(taskConsumerId);
                if (mockTaskContainer.isPresent()) {
                    containers.removeContainer(mockTaskContainer.get());
                    return Optional.of(mockTaskContainer.get().getTaskConsumer());
                }
                return Optional.absent();
            }
        };

        management = createMockManagement();
        management.setTaskConsumerRegistrar(taskConsumerRegistrar);
        management.setTimeProvider(timeProvider);
        containers = new MockTaskContainers(management);
    }

    protected abstract T createMockManagement();

    @BeforeMethod
    public void beforeMethod(Method method) {
        containers.clear();
        timeProvider.reset(0);
        management.start();
        logger.info("Before " + method.getName());
    }


    @AfterMethod(alwaysRun = false)
    public void afterMethod(Method method) {
        logger.info("After " + method.getName());
        try {
            if (management != null) {
                management.stop();
            }
            if (containers != null) {
                Assert.assertTrue(containers.isEmpty(),
                        "Cleanup failure in test " + method.getName() + ":" +
                                Iterables.toString(containers.getContainerIds()));
            }
        } finally {
            if (containers != null) {
                containers.clear();
            }
        }
    }

    @AfterClass
    public void afterClass() {
        management.close();
    }

    private static void setSimpleLoggerFormatter(final Logger logger) {
        Logger parentLogger = logger;
        while (parentLogger.getHandlers().length == 0) {
            parentLogger = logger.getParent();
        }

        parentLogger.getHandlers()[0].setFormatter(new TextFormatter());
    }

    protected T getManagement() {
        return this.management;
    }

    protected void execute(URI ... taskProducers) {
        containers.execute(taskProducers);
    }

    protected long currentTimeMillis() {
        return timeProvider.currentTimeMillis();
    }

    /**
     * This method simulates failure of the agent, and immediate restart by a reliable watchdog,
     * running on the same machine.
     */
    protected void restartAgent(URI agentId) {

        Optional<Object> oldAgentOptional = getTaskConsumerRegistrar().unregisterTaskConsumer(agentId);
        Preconditions.checkState(oldAgentOptional.isPresent());
        Object oldAgent = oldAgentOptional.get();
        final Object restartedAgent;
        if (oldAgent instanceof MockAgent) {
            restartedAgent = MockAgent.newRestartedAgentOnSameMachine((MockAgent) oldAgent);
        } else {
            restartedAgent = MockSSHAgent.newRestartedAgentOnSameMachine((MockSSHAgent) oldAgent);
        }
        getTaskConsumerRegistrar().registerTaskConsumer(restartedAgent, agentId);
    }

    public TaskConsumerRegistrar getTaskConsumerRegistrar() {
        return taskConsumerRegistrar;
    }

    /**
     * This method simulates an unexpected crash of a machine.
     */
    protected void killMachine(URI agentId) {
        final Optional<MockTaskContainer> container = containers.findContainer(agentId);
        if (container.isPresent()) {
            container.get().killMachine();
        }
    }


    protected void submitOrchestratorTask(Task task) {
        getManagement().submitTask(getManagement().getOrchestratorId(), task);
    }

    protected void cos(String... args) {
        submitOrchestratorTask(cli(args));
    }

    protected void cos(AliasGroupId aliasGroupId, String... args) {
        submitOrchestratorTask(cli(aliasGroupId, args));
    }

    protected void cos(AliasId aliasId, String... args) {
        submitOrchestratorTask(cli(aliasId, args));
    }


    public void startZombieAgentAndThenHealthyAgent() {
        getManagement().startZombieAgentAndThenHealthyAgent();
    }
}
