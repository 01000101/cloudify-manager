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

import org.cloudifysource.cosmo.Task;
import org.cloudifysource.cosmo.service.state.ServiceGridDeploymentPlannerState;

import java.net.URI;

/**
 *  A task sent to the deployment planner to change the planned number of service instances.
 *  @author Itai Frenkel
 *  @since 0.1
 */
public class ScaleServiceTask extends Task {

    public ScaleServiceTask() {
        super(ServiceGridDeploymentPlannerState.class);
    }

    private int plannedNumberOfInstances;
    private URI serviceId;

    public void setPlannedNumberOfInstances(int plannedNumberOfInstances) {
        this.plannedNumberOfInstances = plannedNumberOfInstances;
    }

    public int getPlannedNumberOfInstances() {
        return plannedNumberOfInstances;
    }

    public URI getServiceId() {
        return serviceId;
    }

    public void setServiceId(URI serviceId) {
        this.serviceId = serviceId;
    }
}
