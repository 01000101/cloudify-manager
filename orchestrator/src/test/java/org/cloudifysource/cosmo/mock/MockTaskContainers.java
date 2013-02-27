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
package org.cloudifysource.cosmo.mock;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import org.cloudifysource.cosmo.Task;
import org.cloudifysource.cosmo.TaskProducerTask;
import org.cloudifysource.cosmo.agent.tasks.PingAgentTask;
import org.cloudifysource.cosmo.time.MockCurrentTimeProvider;
import org.testng.Assert;

import java.net.URI;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aggregates one or more {@link MockTaskContainer} into one.
 * @author itaif
 * @since 0.1
 */
public class MockTaskContainers implements Iterable<MockTaskContainer> {

    private Set<MockTaskContainer> containers;
    private final MockManagement management;

    public MockTaskContainers(MockManagement management) {
        this.management = management;
        containers =  Sets.newSetFromMap(new ConcurrentHashMap<MockTaskContainer, Boolean>());
    }

    public MockManagement getManagement() {
        return this.management;
    }

    public void addContainer(MockTaskContainer container) {
        Preconditions.checkState(
                findContainserById(container.getTaskConsumerId()) == null,
                "Container %s was already added",
                container.getTaskConsumerId());
        containers.add(container);
    }

    private MockTaskContainer findContainserById(final URI id) {
        return Iterables.find(containers, new Predicate<MockTaskContainer>() {

            @Override
            public boolean apply(MockTaskContainer container) {
                return id.equals(container.getTaskConsumerId());
            }
        }, null);
    }

    public MockTaskContainer findContainer(final URI agentId) {
        MockTaskContainer container = Iterables.tryFind(containers, new Predicate<MockTaskContainer>() {

            @Override
            public boolean apply(MockTaskContainer container) {
                return agentId.equals(container.getTaskConsumerId());
            }
        }).orNull();

        Preconditions.checkNotNull(container, "Cannot find container for %s", agentId);
        return container;
    }

    @Override
    public Iterator<MockTaskContainer> iterator() {
        return containers.iterator();
    }

    public boolean isEmpty() {
        return containers.isEmpty();
    }

    public Iterable<URI> getContainerIds() {
        final Function<MockTaskContainer, URI> getContainerIdFunc = new Function<MockTaskContainer, URI>() {

            @Override
            public URI apply(MockTaskContainer input) {
                return input.getTaskConsumerId();
            }
        };
        return Iterables.transform(containers, getContainerIdFunc);
    }

    public void clear() {
        containers.clear();
    }

    public void execute(URI ... taskProducers) {

        int consecutiveEmptyCycles = 0;
        final MockCurrentTimeProvider timeProvider = getManagement().getTimeProvider();
        for (;
             timeProvider.currentTimeMillis() < timeProvider.getResetTimeMillis() + 1000000;
             timeProvider.increaseBy(1000 - (timeProvider.currentTimeMillis() % 1000))) {

            boolean emptyCycle = true;

            for (final URI taskProducer : taskProducers) {
                submitTaskProducerTask(taskProducer);
                timeProvider.increaseBy(1);
            }

            for (MockTaskContainer container : containers) {
                Preconditions.checkNotNull(findContainer(container.getTaskConsumerId()));
                Assert.assertEquals(container.getTaskConsumerId().getHost(), "localhost");
                for (Task task = container.consumeNextTask();
                     task != null;
                     task = container.consumeNextTask(), timeProvider.increaseBy(1)) {
                    if (!(task instanceof TaskProducerTask) && !(task instanceof PingAgentTask)) {
                        emptyCycle = false;
                    }
                }
            }

            if (emptyCycle) {
                consecutiveEmptyCycles++;
            } else {
                consecutiveEmptyCycles = 0;
            }

            if (consecutiveEmptyCycles > 60) {
                return;
            }
        }

        Assert.fail("Executing too many cycles progress.");
    }

    private void submitTaskProducerTask(final URI taskProducerId) {
        final TaskProducerTask producerTask = new TaskProducerTask();
        producerTask.setMaxNumberOfSteps(100);
        getManagement().submitTask(taskProducerId, producerTask);
    }

    public boolean removeContainer(MockTaskContainer mockTaskContainer) {
        return containers.remove(mockTaskContainer);
    }
}
