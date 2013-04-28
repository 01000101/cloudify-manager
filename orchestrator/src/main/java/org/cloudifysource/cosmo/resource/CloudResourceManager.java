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
package org.cloudifysource.cosmo.resource;

import com.google.common.base.Preconditions;
import org.cloudifysource.cosmo.cloud.driver.CloudDriver;
import org.cloudifysource.cosmo.cloud.driver.MachineConfiguration;
import org.cloudifysource.cosmo.messaging.consumer.MessageConsumer;
import org.cloudifysource.cosmo.messaging.consumer.MessageConsumerListener;

import java.net.URI;

/**
 * TODO: Write a short summary of this type's roles and responsibilities.
 *
 * @author Idan Moyal
 * @since 0.1
 */
public class CloudResourceManager {

    private final CloudDriver driver;
    private volatile boolean machineStarted = false;

    public CloudResourceManager(final CloudDriver driver, URI uri, MessageConsumer consumer) {
        Preconditions.checkNotNull(driver);
        this.driver = driver;
        if (consumer != null && uri != null) {
            consumer.addListener(uri, new MessageConsumerListener<String>() {
                @Override
                public void onMessage(URI uri, String message) {
                    System.out.println("CloudResourceManager message received [uri=" + uri + ", " +
                            "message=" + message + "]");
                    if ("start_machine".equals(message)) {
                        startMachine();
                    }
                }

                @Override
                public void onFailure(Throwable t) {
                }

                @Override
                public Class<? extends String> getMessageClass() {
                    return String.class;
                }
            });
        }
    }

    private void startMachine() {
        driver.startMachine(new MachineConfiguration("tomcat_node", "centos-6.3"));
        machineStarted = true;
    }

    public boolean isMachineStarted() {
        return machineStarted;
    }
}
