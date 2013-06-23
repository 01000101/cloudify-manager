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

package org.cloudifysource.cosmo.orchestrator.integration;

import com.google.common.collect.Maps;
import com.google.common.io.Resources;
import org.cloudifysource.cosmo.config.TestConfig;
import org.cloudifysource.cosmo.messaging.config.MockMessageConsumerConfig;
import org.cloudifysource.cosmo.messaging.config.MockMessageProducerConfig;
import org.cloudifysource.cosmo.messaging.consumer.MessageConsumer;
import org.cloudifysource.cosmo.messaging.producer.MessageProducer;
import org.cloudifysource.cosmo.orchestrator.integration.config.RuoteRuntimeConfig;
import org.cloudifysource.cosmo.orchestrator.workflow.RuoteRuntime;
import org.cloudifysource.cosmo.orchestrator.workflow.RuoteWorkflow;
import org.cloudifysource.cosmo.statecache.RealTimeStateCache;
import org.cloudifysource.cosmo.statecache.config.RealTimeStateCacheConfig;
import org.cloudifysource.cosmo.statecache.messages.StateChangedMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.testng.AbstractTestNGSpringContextTests;
import org.testng.annotations.Test;

import javax.inject.Inject;
import java.net.URI;
import java.util.Map;

/**
 * @author Dan Kilman
 * @since 0.1
 */
@ContextConfiguration(classes = { VagrantAndWebserverServiceIT.Config.class })
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
public class VagrantAndWebserverServiceIT extends AbstractTestNGSpringContextTests {

    /**
     */
    @Configuration
    @Import({
            MockMessageConsumerConfig.class,
            MockMessageProducerConfig.class,
            RealTimeStateCacheConfig.class,
            RuoteRuntimeConfig.class
    })
    @PropertySource("org/cloudifysource/cosmo/orchestrator/integration/config/test.properties")
    static class Config extends TestConfig {
    }

    @Inject
    private RuoteRuntime ruoteRuntime;

    @Inject
    private RealTimeStateCache stateCache;

    @Inject
    private MessageProducer messageProducer;

    @Inject
    private MessageConsumer messageConsumer;

    @Value("${cosmo.state-cache.topic}")
    private URI stateCacheTopic;

    @Test(groups = "vagrant")
    public void testWithVagrantHostProvisionerAndSimpleWebServerInstaller() {

        final String hostId = "simple_web_server.webserver_host";

        final RuoteWorkflow workflow = RuoteWorkflow.createFromResource(
                "ruote/pdefs/execute_plan.radial", ruoteRuntime);

        final String dslLocation = "org/cloudifysource/cosmo/dsl/integration_phase1/integration-phase1.yaml";

        final Map<String, Object> workitemFields = Maps.newHashMap();
        workitemFields.put("dsl", Resources.getResource(dslLocation).getFile());

        final Object wfid = workflow.asyncExecute(workitemFields);

        // This will call will be made by the monitor thread once we actually start
        // something and it becomes clear how to monitor it.
        messageProducer.send(stateCacheTopic, createReachableStateCacheMessage(hostId));

        ruoteRuntime.waitForWorkflow(wfid);

    }

    private StateChangedMessage createReachableStateCacheMessage(String resourceId) {
        final StateChangedMessage message = new StateChangedMessage();
        message.setResourceId(resourceId);
        final Map<String, Object> state = com.google.common.collect.Maps.newHashMap();
        state.put("reachable", "true");
        message.setState(state);
        return message;
    }

}
