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
package org.cloudifysource.cosmo.cep;

import org.cloudifysource.cosmo.messaging.consumer.MessageConsumer;
import org.cloudifysource.cosmo.messaging.consumer.MessageConsumerConfiguration;
import org.cloudifysource.cosmo.messaging.producer.MessageProducer;
import org.cloudifysource.cosmo.messaging.producer.MessageProducerConfiguration;
import org.drools.io.Resource;
import org.drools.io.ResourceFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import javax.inject.Inject;
import java.net.URI;


/**
 * Configuration for {@link ResourceMonitorServer}.
 * @author itaif
 * @since 0.1
 */
@Configuration
@Import({MessageProducerConfiguration.class, MessageConsumerConfiguration.class })
public class ResourceMonitorServerConfiguration {

    @Value("${resource-monitor.topic")
    private URI resourceMonitorTopic;

    @Value("${state-cache.topic")
    private URI stateCacheTopic;

    @Value("${agent.topic")
    private URI agentTopic;

    @Value("${pseudo.clock}")
    private boolean pseudoClock;

    @Value("${resource-monitor.rule.file}")
    private String droolsResourcePath;

    @Inject
    private MessageProducer producer;

    @Inject
    private MessageConsumer consumer;

    @Bean
    public ResourceMonitorServer resourceMonitorServer() {
        Resource droolsResource = ResourceFactory.newClassPathResource(droolsResourcePath, this.getClass());
        return new ResourceMonitorServer(
                resourceMonitorTopic,
                stateCacheTopic,
                agentTopic,
                pseudoClock,
                droolsResource,
                producer,
                consumer);
    }
}
