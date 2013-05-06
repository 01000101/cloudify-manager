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

package org.cloudifysource.cosmo.messaging.configuration;

import org.cloudifysource.cosmo.messaging.broker.MessageBrokerServerConfiguration;
import org.cloudifysource.cosmo.messaging.consumer.MessageConsumerConfiguration;
import org.cloudifysource.cosmo.messaging.consumer.config.MessageConsumerTestConfig;
import org.cloudifysource.cosmo.messaging.producer.MessageProducerConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;

/**
 * Message client test configuration.
 *
 * @author Dan Kilman
 * @since 0.1
 */
@Configuration
@PropertySource("org/cloudifysource/cosmo/messaging/configuration/test.properties")
@Import({
        MessageConsumerTestConfig.class,
        MessageProducerConfiguration.class,
        MessageBrokerServerConfiguration.class
})
public class MessageClientTestConfiguration {

    @Bean
    public static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
        return new PropertySourcesPlaceholderConfigurer();
    }

    //SUPPRESS CHECKSTYLE HideUtilityClassConstructor
    public void foo() { }
}
