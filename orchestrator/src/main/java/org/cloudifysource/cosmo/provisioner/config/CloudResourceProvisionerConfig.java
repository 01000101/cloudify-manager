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

package org.cloudifysource.cosmo.provisioner.config;

import org.cloudifysource.cosmo.cloud.driver.CloudDriver;
import org.cloudifysource.cosmo.messaging.consumer.MessageConsumer;
import org.cloudifysource.cosmo.messaging.producer.MessageProducer;
import org.cloudifysource.cosmo.provisioner.CloudResourceProvisioner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.inject.Inject;
import java.net.URI;

/**
 * Creates a new {@link org.cloudifysource.cosmo.provisioner.CloudResourceProvisioner}.
 *
 * @author Dan Kilman
 * @since 0.1
 */
@Configuration
public class CloudResourceProvisionerConfig {

    @Value("${cosmo.resource-provisioner.topic}")
    private URI resourceProvisionerTopic;

    @Inject
    private CloudDriver cloudDriver;

    @Inject
    private MessageConsumer messageConsumer;

    @Inject
    private MessageProducer messageProducer;

    @Bean(destroyMethod = "close")
    public CloudResourceProvisioner cloudResourceProvisioner() {
        return new CloudResourceProvisioner(cloudDriver, resourceProvisionerTopic, messageProducer, messageConsumer);
    }

}
