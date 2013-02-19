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
package org.cloudifysource.cosmo.service;

import org.cloudifysource.cosmo.agent.state.AgentState;
import org.cloudifysource.cosmo.service.state.ServiceInstanceState;
import org.cloudifysource.cosmo.service.state.ServiceState;
import org.cloudifysource.cosmo.state.EtagState;
import org.cloudifysource.cosmo.state.StateReader;
import org.cloudifysource.cosmo.streams.StreamUtils;

import java.net.URI;

public class ServiceUtils {
	
	public static AgentState getAgentState(
			final StateReader stateReader, 
			final URI agentId) {
		EtagState<AgentState> etagState = stateReader.get(agentId, AgentState.class);
		return etagState == null ? null : etagState.getState();
	}

	public static ServiceState getServiceState(
			final StateReader stateReader,
			final URI serviceId) {
		EtagState<ServiceState> etagState = stateReader.get(serviceId, ServiceState.class);
		return etagState == null ? null : etagState.getState();
	}
	
	public static ServiceInstanceState getServiceInstanceState(
			final StateReader stateReader, 
			final URI instanceId) {
		EtagState<ServiceInstanceState> etagState = stateReader.get(instanceId, ServiceInstanceState.class);
		return etagState == null ? null : etagState.getState();
	}
	
	public static URI toTasksHistoryId(URI stateId) {
		return StreamUtils.newURI(stateId.toString()+"_tasks_history");
	}
}
