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

package org.cloudifysource.cosmo.cloud.driver.config;

import com.google.common.base.Preconditions;
import org.cloudifysource.cosmo.cloud.driver.vagrant.VagrantCloudDriver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

/**
 * Creates a new {@link org.cloudifysource.cosmo.cloud.driver.vagrant.VagrantCloudDriver}.
 *
 * @author Dan Kilman
 * @since 0.1
 */
@Configuration
public class VagrantCloudDriverConfig {

    @Value("${cosmo.cloud-driver.vagrant.working-directory}")
    private File vagrantWorkingDirectory;

    @Bean
    public VagrantCloudDriver vagrantCloudDriver() {
        return new VagrantCloudDriver(vagrantWorkingDirectory);
    }

}
