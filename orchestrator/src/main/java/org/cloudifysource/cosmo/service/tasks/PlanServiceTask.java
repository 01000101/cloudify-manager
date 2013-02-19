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
package org.cloudifysource.cosmo.service.tasks;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import org.cloudifysource.cosmo.Task;
import org.cloudifysource.cosmo.service.state.ServiceConfig;
import org.cloudifysource.cosmo.service.state.ServiceState;

import java.net.URI;
import java.util.List;

/**
 * A task to initialize service state (planned).
 * @author Itai Frenkel
 * @since 0.1
 */
public class PlanServiceTask extends Task {

    public PlanServiceTask() {
        super(ServiceState.class);
    }

    private ServiceConfig serviceConfig;
    private List<URI> serviceInstanceIds;

    public List<URI> getServiceInstanceIds() {
        return this.serviceInstanceIds;
    }

    public void setServiceInstanceIds(List<URI> serviceInstanceIds) {
        this.serviceInstanceIds = serviceInstanceIds;
    }

    @JsonUnwrapped
    public ServiceConfig getServiceConfig() {
        return serviceConfig;
    }

    public void setServiceConfig(ServiceConfig serviceConfig) {
        this.serviceConfig = serviceConfig;
    }
}
