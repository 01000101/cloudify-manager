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

package org.cloudifysource.cosmo.orchestrator.integration.config;

import org.cloudifysource.cosmo.cep.config.MockAgentConfig;
import org.cloudifysource.cosmo.cep.config.ResourceMonitorServerConfig;
import org.cloudifysource.cosmo.messaging.config.MessageBrokerServerConfig;
import org.cloudifysource.cosmo.messaging.config.MessageConsumerTestConfig;
import org.cloudifysource.cosmo.messaging.config.MessageProducerConfig;
import org.cloudifysource.cosmo.statecache.config.RealTimeStateCacheConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

/**
 * TODO: Write a short summary of this type's roles and responsibilities.
 *
 * @author Dan Kilman
 * @since 0.1
 */
@Configuration
@Import({
        RealTimeStateCacheConfig.class,
        ResourceMonitorServerConfig.class,
        MessageBrokerServerConfig.class,
        MessageConsumerTestConfig.class,
        MessageProducerConfig.class,
        MockAgentConfig.class
})
@PropertySource("org/cloudifysource/cosmo/orchestrator/integration/config/test.properties")
public class BaseOrchestratorIntegrationTestConfig {

    @Bean
    public static PropertySourcesPlaceholderConfigurer stub() {
        return new PropertySourcesPlaceholderConfigurer();
    }

    // SUPRESS CHECKSTYLE
    public void fixMe() { }

}
