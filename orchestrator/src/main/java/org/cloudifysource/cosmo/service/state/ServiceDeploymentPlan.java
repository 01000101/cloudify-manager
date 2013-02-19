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
package org.cloudifysource.cosmo.service.state;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.google.common.base.*;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

import java.net.URI;
import java.util.List;

public class ServiceDeploymentPlan {

	private ServiceConfig serviceConfig;
	private List<ServiceInstanceDeploymentPlan> instances;
	
	public ServiceDeploymentPlan() {
		instances = Lists.newArrayList();
	}

	@JsonUnwrapped
	public ServiceConfig getServiceConfig() {
		return serviceConfig;
	}

	public void setServiceConfig(ServiceConfig service) {
		this.serviceConfig = service;
	}

	public List<ServiceInstanceDeploymentPlan> getInstances() {
		return instances;
	}

	public void setInstances(List<ServiceInstanceDeploymentPlan> instances) {
		this.instances = instances;
	}

	@JsonIgnore
	public boolean removeInstanceById(URI instanceId) {
		return Iterables.removeIf(instances, findInstanceIdPredicate(instanceId));
	}

	private Predicate<ServiceInstanceDeploymentPlan> findInstanceIdPredicate(
			final URI instanceId) {

		return new Predicate<ServiceInstanceDeploymentPlan>() {

			@Override
			public boolean apply(ServiceInstanceDeploymentPlan instancePlan) {
				return instancePlan.getInstanceId().equals(instanceId);
			}
		};
	}

	@JsonIgnore
	public void addInstance(URI instanceId, URI agentId) {
		Preconditions.checkNotNull(instanceId);
		Preconditions.checkNotNull(agentId);
		Preconditions.checkArgument(!Iterables.tryFind(instances, findInstanceIdPredicate(instanceId)).isPresent());
		ServiceInstanceDeploymentPlan instancePlan = new ServiceInstanceDeploymentPlan();
		instancePlan.setInstanceId(instanceId);
		instancePlan.setAgentId(agentId);
		instances.add(instancePlan);
	}

	@JsonIgnore
	public Iterable<URI> getInstancesByAgentId(final URI agentId) {
		Function<ServiceInstanceDeploymentPlan, URI> toInstanceIdFunction = new Function<ServiceInstanceDeploymentPlan, URI>() {

			@Override
			public URI apply(ServiceInstanceDeploymentPlan instancePlan) {
				if (instancePlan.getAgentId().equals(agentId)) {
					return instancePlan.getInstanceId();
				}
				return null;
			}
		};
		return Iterables.unmodifiableIterable(
				Iterables.filter(
				Iterables.transform(instances, toInstanceIdFunction),
				Predicates.notNull()));
	}

	@JsonIgnore
	public Iterable<URI> getInstanceIds() {
		Function<ServiceInstanceDeploymentPlan, URI> toInstanceIdFunction = new Function<ServiceInstanceDeploymentPlan, URI>() {

			@Override
			public URI apply(ServiceInstanceDeploymentPlan instancePlan) {
				return instancePlan.getInstanceId();
			}
		};
		return Iterables.unmodifiableIterable(Iterables.transform(instances, toInstanceIdFunction));
	}

	@JsonIgnore
	public Iterable<URI> getAgentIds() {
		Function<ServiceInstanceDeploymentPlan, URI> toInstanceIdFunction = new Function<ServiceInstanceDeploymentPlan, URI>() {

			@Override
			public URI apply(ServiceInstanceDeploymentPlan instancePlan) {
				return instancePlan.getAgentId();
			}
		};
		return ImmutableSet.copyOf(Iterables.transform(instances, toInstanceIdFunction));
	}

	@JsonIgnore
	public URI getAgentIdByInstanceId(URI instanceId) {
		Optional<ServiceInstanceDeploymentPlan> instancePlan = Iterables.tryFind(instances, findInstanceIdPredicate(instanceId));
		if (!instancePlan.isPresent()) {
			return null;
		}
		
		return instancePlan.get().getAgentId();
	}

	@JsonIgnore
	public boolean containsInstanceId(URI instanceId) {
		return Iterables.tryFind(instances, findInstanceIdPredicate(instanceId)).isPresent();
	}
	
}
