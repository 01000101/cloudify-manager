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

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import org.cloudifysource.cosmo.Task;
import org.cloudifysource.cosmo.TaskConsumer;
import org.cloudifysource.cosmo.TaskConsumerStateHolder;
import org.cloudifysource.cosmo.TaskProducer;
import org.cloudifysource.cosmo.TaskReader;
import org.cloudifysource.cosmo.service.state.ServiceConfig;
import org.cloudifysource.cosmo.service.state.ServiceGridCapacityPlannerState;
import org.cloudifysource.cosmo.service.state.ServiceInstanceState;
import org.cloudifysource.cosmo.service.state.ServiceScalingRule;
import org.cloudifysource.cosmo.service.state.ServiceState;
import org.cloudifysource.cosmo.service.tasks.ScaleServiceTask;
import org.cloudifysource.cosmo.service.tasks.ScalingRulesTask;
import org.cloudifysource.cosmo.state.StateReader;

import java.net.URI;
import java.util.List;

/**
 * Monitors service statistics, and decides how many instances each service requires (scaling rules).
 * @author Itai Frenkel
 * @since 0.1
 */
public class ServiceGridCapacityPlanner {

    private final ServiceGridCapacityPlannerState state;
    private final TaskReader taskReader;
    private final URI deploymentPlannerId;
    private final StateReader stateReader;

    public ServiceGridCapacityPlanner(ServiceGridCapacityPlannerParameter parameterObject) {
        this.deploymentPlannerId = parameterObject.getDeploymentPlannerId();
        this.stateReader = parameterObject.getStateReader();
        this.taskReader = parameterObject.getTaskReader();
        this.state = new ServiceGridCapacityPlannerState();
        state.setTasksHistory(ServiceUtils.toTasksHistoryId(parameterObject.getCapacityPlannerId()));
    }

    @TaskConsumerStateHolder
    public ServiceGridCapacityPlannerState getState() {
        return this.state;
    }

    @TaskConsumer
    public void scalingRules(ScalingRulesTask task) {
        state.addServiceScalingRule(task.getScalingRule());
    }

    @TaskProducer
    public Iterable<Task> enforceScalingRules() {

        final List<Task> newTasks = Lists.newArrayList();

        for (ServiceScalingRule scalingRule : state.getScalingRules()) {
            enforceScalingRule(newTasks, scalingRule);
        }
        return newTasks;
    }

    private void enforceScalingRule(List<Task> newTasks, ServiceScalingRule scalingRule) {
        final URI serviceId = scalingRule.getServiceId();
        final ServiceState serviceState = getServiceState(serviceId);
        if (shouldScaleOut(scalingRule, serviceState)) {

            final int plannedNumberOfInstances = serviceState.getInstanceIds().size() + 1;
            scale(newTasks, serviceState.getServiceConfig(), plannedNumberOfInstances);
        } else if (shouldScaleIn(scalingRule, serviceState)) {
            final int plannedNumberOfInstances = serviceState.getInstanceIds().size() - 1;
            scale(newTasks, serviceState.getServiceConfig(), plannedNumberOfInstances);
        }
    }

    private void scale(List<Task> newTasks,
            ServiceConfig serviceConfig, final int plannedNumberOfInstances) {

        if (plannedNumberOfInstances >= serviceConfig.getMinNumberOfInstances()
            && plannedNumberOfInstances <= serviceConfig.getMaxNumberOfInstances()) {

            final ScaleServiceTask task = new ScaleServiceTask();
            task.setServiceId(serviceConfig.getServiceId());
            task.setPlannedNumberOfInstances(plannedNumberOfInstances);
            task.setConsumerId(deploymentPlannerId);
            addNewTaskIfNotExists(newTasks, task);
        }
    }

    /**
     * Scale out if any of the instances property value is above threshold.
     */
    private boolean shouldScaleOut(
            final ServiceScalingRule scalingRule,
            final ServiceState serviceState) {

        boolean scaleOut = false;

        if (isServiceInstalled(serviceState)) {

            Object highThreshold = scalingRule.getHighThreshold();
            if (highThreshold != null) {
                for (URI instanceId : serviceState.getInstanceIds()) {
                    final Object value = getServiceInstanceState(instanceId).getProperty(scalingRule.getPropertyName());
                    if (value != null && isAboveThreshold(highThreshold, value)) {
                        scaleOut = true;
                        break;
                    }
                }
            }
        }
        return scaleOut;
    }

    /**
     * Scale in if all instances property value is below threshold.
     */
    private boolean shouldScaleIn(
            final ServiceScalingRule scalingRule,
            final ServiceState serviceState) {

        boolean scaleIn = false;

        if (isServiceInstalled(serviceState)) {
            Object lowThreshold = scalingRule.getLowThreshold();
            if (lowThreshold != null) {
                scaleIn = true;
                for (URI instanceId : serviceState.getInstanceIds()) {
                    final Object value = getServiceInstanceState(instanceId).getProperty(scalingRule.getPropertyName());
                    if (value == null) {
                        scaleIn = false;
                        break;
                    } else if (!isBelowThreshold(lowThreshold, value)) {
                        scaleIn = false;
                        break;
                    }
                }
            }
        }
        return scaleIn;
    }

    private boolean isServiceInstalled(final ServiceState serviceState) {
        return serviceState != null
               && serviceState.isProgress(ServiceState.Progress.SERVICE_INSTALLED);
    }

    private boolean isAboveThreshold(Object threshold, Object value) {
        return compare(threshold, value) < 0;
    }

    private boolean isBelowThreshold(Object threshold, Object value) {
        return compare(threshold, value) > 0;
    }

    private ServiceState getServiceState(final URI serviceId) {
        return ServiceUtils.getServiceState(stateReader, serviceId);
    }

    private ServiceInstanceState getServiceInstanceState(URI instanceId) {
        return ServiceUtils.getServiceInstanceState(stateReader, instanceId);
    }

    /**
     * Adds a new task, but it is actually submitted only if it has not been added recently.
     */
    public void addNewTaskIfNotExists(
            final List<Task> newTasks,
            final Task newTask) {

        newTasks.add(newTask);
    }

    @SuppressWarnings("unchecked")
    public static int compare(Object left, Object right) {

        Preconditions.checkNotNull(left);
        Preconditions.checkNotNull(right);

        if (left.getClass().equals(right.getClass())
            && left instanceof Comparable<?>) {
            return ((Comparable<Object>) left).compareTo(right);
        }

        return toDouble(left).compareTo(toDouble(right));
    }

    private static Double toDouble(Object x) {
        if (x instanceof Number) {
            return ((Number) x).doubleValue();
        }
        return Double.valueOf(x.toString());
    }
}
