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
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import org.cloudifysource.cosmo.ImpersonatingTaskConsumer;
import org.cloudifysource.cosmo.LifecycleStateMachine;
import org.cloudifysource.cosmo.Task;
import org.cloudifysource.cosmo.TaskConsumer;
import org.cloudifysource.cosmo.TaskConsumerStateHolder;
import org.cloudifysource.cosmo.TaskConsumerStateModifier;
import org.cloudifysource.cosmo.TaskProducer;
import org.cloudifysource.cosmo.TaskReader;
import org.cloudifysource.cosmo.agent.state.AgentState;
import org.cloudifysource.cosmo.agent.tasks.MachineLifecycleTask;
import org.cloudifysource.cosmo.agent.tasks.PingAgentTask;
import org.cloudifysource.cosmo.agent.tasks.PlanAgentTask;
import org.cloudifysource.cosmo.service.state.ServiceDeploymentPlan;
import org.cloudifysource.cosmo.service.state.ServiceGridDeploymentPlan;
import org.cloudifysource.cosmo.service.state.ServiceGridOrchestratorState;
import org.cloudifysource.cosmo.service.state.ServiceInstanceState;
import org.cloudifysource.cosmo.service.state.ServiceState;
import org.cloudifysource.cosmo.service.tasks.PlanServiceInstanceTask;
import org.cloudifysource.cosmo.service.tasks.PlanServiceTask;
import org.cloudifysource.cosmo.service.tasks.RecoverServiceInstanceStateTask;
import org.cloudifysource.cosmo.service.tasks.RemoveServiceInstanceFromAgentTask;
import org.cloudifysource.cosmo.service.tasks.RemoveServiceInstanceFromServiceTask;
import org.cloudifysource.cosmo.service.tasks.ServiceInstalledTask;
import org.cloudifysource.cosmo.service.tasks.ServiceInstallingTask;
import org.cloudifysource.cosmo.service.tasks.ServiceInstanceFollowsMachineLifecycleTask;
import org.cloudifysource.cosmo.service.tasks.ServiceInstanceTask;
import org.cloudifysource.cosmo.service.tasks.ServiceUninstalledTask;
import org.cloudifysource.cosmo.service.tasks.ServiceUninstallingTask;
import org.cloudifysource.cosmo.service.tasks.UpdateDeploymentPlanTask;
import org.cloudifysource.cosmo.state.StateReader;
import org.cloudifysource.cosmo.time.CurrentTimeProvider;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Consumes task from the planner, and orchestrates their execution
 * by producing tasks to agents and machine provisioning.
 * @author Itai Frenkel
 * @since 0.1
 */
public class ServiceGridOrchestrator {

    private static final long AGENT_UNREACHABLE_MILLISECONDS = TimeUnit.SECONDS.toMillis(30);

    private static final long AGENT_REACHABLE_RENEW_MILLISECONDS = AGENT_UNREACHABLE_MILLISECONDS / 2;

    private final ServiceGridOrchestratorState state;

    private final TaskReader taskReader;
    private final URI machineProvisionerId;
    private final URI orchestratorId;
    private final StateReader stateReader;

    private CurrentTimeProvider timeProvider;

    public ServiceGridOrchestrator(ServiceGridOrchestratorParameter parameterObject) {
        this.orchestratorId = parameterObject.getOrchestratorId();
        this.taskReader = parameterObject.getTaskReader();
        this.machineProvisionerId = parameterObject.getMachineProvisionerId();
        this.stateReader = parameterObject.getStateReader();
        this.timeProvider = parameterObject.getTimeProvider();
        this.state = new ServiceGridOrchestratorState();
        this.state.setTasksHistory(ServiceUtils.toTasksHistoryId(orchestratorId));
    }

    @TaskProducer
    public Iterable<Task> orchestrate() {

        final List<Task> newTasks = Lists.newArrayList();

        if (state.getDeploymentPlan() != null) {

            boolean ready = syncStateWithDeploymentPlan(newTasks);

            if (ready) {
                //start orchestrating according to current state
                orchestrateAgents(newTasks);
                orchestrateServices(newTasks);
            }

            pingAgents(newTasks);
        }
        return newTasks;
    }

    @TaskConsumer
    public void updateDeploymentPlan(UpdateDeploymentPlanTask task) {
        ServiceGridDeploymentPlan deploymentPlan = task.getDeploymentPlan();
        for (ServiceDeploymentPlan servicePlan : deploymentPlan.getServices()) {
            int plannedInstancesSize = Iterables.size(servicePlan.getInstanceIds());
            int numberOfPlannedInstances = servicePlan.getServiceConfig().getPlannedNumberOfInstances();
            Preconditions.checkArgument(numberOfPlannedInstances == plannedInstancesSize);
        }
        if (state.getDeploymentPlan() == null) {
            state.setDeploymentPlan(deploymentPlan);
        } else {
            final Iterable<URI> oldServiceIds = getPlannedServiceIds();
            final Iterable<URI> oldAgentIds = getPlannedAgentIds();
            state.setDeploymentPlan(deploymentPlan);
            final Iterable<URI> newServiceIds = getPlannedServiceIds();
            final Iterable<URI> newAgentIds = getPlannedAgentIds();
            state.addServiceIdsToUninstall(subtract(oldServiceIds, newServiceIds));
            state.addAgentIdsToTerminate(subtract(oldAgentIds, newAgentIds));
        }
    }

    @ImpersonatingTaskConsumer
    public void planAgent(PlanAgentTask task,
                          TaskConsumerStateModifier<AgentState> impersonatedStateModifier) {
        int numberOfMachineRestarts = 0;
        AgentState impersonatedAgentState = new AgentState();
        impersonatedAgentState.setLifecycle(AgentState.Progress.MACHINE_TERMINATED);
        impersonatedAgentState.setServiceInstanceIds(task.getServiceInstanceIds());
        impersonatedAgentState.setNumberOfMachineStarts(numberOfMachineRestarts);
        impersonatedAgentState.setTasksHistory(ServiceUtils.toTasksHistoryId(task.getStateId()));
        impersonatedStateModifier.put(impersonatedAgentState);
    }

    @ImpersonatingTaskConsumer
    public void planService(PlanServiceTask task,
                            TaskConsumerStateModifier<ServiceState> impersonatedStateModifier) {

        ServiceState serviceState = impersonatedStateModifier.get();
        if (serviceState == null) {
            serviceState = new ServiceState();
        }
        serviceState.setServiceConfig(task.getServiceConfig());
        serviceState.setInstanceIds(task.getServiceInstanceIds());
        serviceState.setProgress(ServiceState.Progress.INSTALLING_SERVICE);
        serviceState.setTasksHistory(ServiceUtils.toTasksHistoryId(task.getStateId()));
        impersonatedStateModifier.put(serviceState);
    }

    @ImpersonatingTaskConsumer
    public void serviceUninstalling(ServiceUninstallingTask task,
                                    TaskConsumerStateModifier<ServiceState> impersonatedStateModifier) {
        ServiceState serviceState = impersonatedStateModifier.get();
        serviceState.setProgress(ServiceState.Progress.UNINSTALLING_SERVICE);
        impersonatedStateModifier.put(serviceState);
    }

    @ImpersonatingTaskConsumer
    public void serviceInstalling(ServiceInstallingTask task,
                                  TaskConsumerStateModifier<ServiceState> impersonatedStateModifier) {
        ServiceState serviceState = impersonatedStateModifier.get();
        serviceState.setProgress(ServiceState.Progress.INSTALLING_SERVICE);
        impersonatedStateModifier.put(serviceState);
    }

    @ImpersonatingTaskConsumer
    public void serviceUninstalled(ServiceUninstalledTask task,
                                   TaskConsumerStateModifier<ServiceState> impersonatedStateModifier) {
        ServiceState serviceState = impersonatedStateModifier.get();
        serviceState.setProgress(ServiceState.Progress.SERVICE_UNINSTALLED);
        impersonatedStateModifier.put(serviceState);

        final URI serviceId = serviceState.getServiceConfig().getServiceId();
        state.removeServiceIdToUninstall(serviceId);
    }

    @ImpersonatingTaskConsumer
    public void planServiceInstance(PlanServiceInstanceTask task,
                                    TaskConsumerStateModifier impersonatedStateModifier) {
        PlanServiceInstanceTask planInstanceTask = (PlanServiceInstanceTask) task;
        ServiceInstanceState instanceState = new ServiceInstanceState();
        instanceState.setAgentId(planInstanceTask.getAgentId());
        instanceState.setServiceId(planInstanceTask.getServiceId());
        instanceState.setLifecycle(planInstanceTask.getStateMachine().getInitialLifecycle());
        instanceState.setTasksHistory(ServiceUtils.toTasksHistoryId(task.getStateId()));
        instanceState.setStateMachine(planInstanceTask.getStateMachine());
        impersonatedStateModifier.put(instanceState);
    }

    @ImpersonatingTaskConsumer
    public void removeServiceInstanceFromService(
            final RemoveServiceInstanceFromServiceTask task,
            final TaskConsumerStateModifier<ServiceState> impersonatedStateModifier) {

        final ServiceState serviceState = impersonatedStateModifier.get();
        serviceState.removeInstance(task.getInstanceId());
        impersonatedStateModifier.put(serviceState);
    }

    @ImpersonatingTaskConsumer
    public void serviceInstalled(final ServiceInstalledTask task,
                                 final TaskConsumerStateModifier<ServiceState> impersonatedStateModifier) {
        ServiceState serviceState = impersonatedStateModifier.get();
        serviceState.setProgress(ServiceState.Progress.SERVICE_INSTALLED);
        impersonatedStateModifier.put(serviceState);
    }

    @ImpersonatingTaskConsumer
    public void serviceInstanceFollowsMachineLifecycle(final ServiceInstanceFollowsMachineLifecycleTask task,
                                                       final TaskConsumerStateModifier<ServiceInstanceState> impersonatedStateModifier) {
        ServiceInstanceState serviceState = impersonatedStateModifier.get();
        final String lifecycle = getAgentState(serviceState.getAgentId()).getLifecycle();
        serviceState.setLifecycle(lifecycle);
        impersonatedStateModifier.put(serviceState);
    }

    @ImpersonatingTaskConsumer
    public void removeServiceInstanceFromAgent(final RemoveServiceInstanceFromAgentTask task,
                                               final TaskConsumerStateModifier<AgentState> impersonatedStateModifier) {

        final AgentState agentState = impersonatedStateModifier.get();
        agentState.removeServiceInstanceId(task.getInstanceId());
        impersonatedStateModifier.put(agentState);
    }

    private boolean syncStateWithDeploymentPlan(final List<Task> newTasks) {
        boolean syncComplete = true;
        final long nowTimestamp = timeProvider.currentTimeMillis();
        for (final URI agentId : getPlannedAgentIds()) {
            AgentPingHealth pingHealth = getAgentPingHealth(agentId, nowTimestamp);
            AgentState agentState = getAgentState(agentId);
            boolean agentNotStarted =
                    (agentState == null
                            || !agentState.getLifecycle().equals(AgentState.Progress.AGENT_STARTED));
            if (agentNotStarted
                    && state.isSyncedStateWithDeploymentBefore()
                    && pingHealth == AgentPingHealth.UNDETERMINED) {
                //If this agent were started, we would have resolved it as AGENT_STARTED in the previous sync
                //The agent probably never even started
                pingHealth = AgentPingHealth.AGENT_UNREACHABLE;
            }
            if (pingHealth == AgentPingHealth.AGENT_REACHABLE) {
                Preconditions.checkState(agentState != null, "Responding agent cannot have null state");
                for (URI instanceId : state.getDeploymentPlan().getInstanceIdsByAgentId(agentId)) {
                    ServiceInstanceState instanceState = getServiceInstanceState(instanceId);
                    if (instanceState == null
                            || instanceState.isLifecycle(AgentState.Progress.MACHINE_UNREACHABLE)) {

                        syncComplete = false;
                        final URI serviceId = state.getDeploymentPlan().getServiceIdByInstanceId(instanceId);
                        final ServiceState serviceState = getServiceState(serviceId);
                        final RecoverServiceInstanceStateTask recoverInstanceStateTask =
                                new RecoverServiceInstanceStateTask();
                        recoverInstanceStateTask.setStateId(instanceId);
                        recoverInstanceStateTask.setConsumerId(agentId);
                        recoverInstanceStateTask.setServiceId(serviceId);
                        recoverInstanceStateTask.setStateMachine(
                                serviceState.getServiceConfig().getInstanceLifecycleStateMachine());
                        addNewTaskIfNotExists(newTasks, recoverInstanceStateTask);
                    }
                }
            } else if (pingHealth == AgentPingHealth.AGENT_UNREACHABLE) {
                Iterable<URI> plannedInstanceIds = state.getDeploymentPlan().getInstanceIdsByAgentId(agentId);
                if (agentState == null) {
                    syncComplete = false;
                    final PlanAgentTask planAgentTask = new PlanAgentTask();
                    planAgentTask.setStateId(agentId);
                    planAgentTask.setConsumerId(orchestratorId);
                    planAgentTask.setServiceInstanceIds(Lists.newArrayList(plannedInstanceIds));
                    addNewTaskIfNotExists(newTasks, planAgentTask);
                }
                for (URI instanceId : state.getDeploymentPlan().getInstanceIdsByAgentId(agentId)) {
                    if (getServiceInstanceState(instanceId) == null) {
                        syncComplete = false;
                        final URI serviceId = state.getDeploymentPlan().getServiceIdByInstanceId(instanceId);
                        final ServiceState serviceState = getServiceState(serviceId);
                        final PlanServiceInstanceTask planInstanceTask = new PlanServiceInstanceTask();
                        planInstanceTask.setStateId(instanceId);
                        planInstanceTask.setAgentId(agentId);
                        planInstanceTask.setServiceId(serviceId);
                        planInstanceTask.setConsumerId(orchestratorId);
                        planInstanceTask.setStateMachine(serviceState.getServiceConfig()
                                .getInstanceLifecycleStateMachine());
                        addNewTaskIfNotExists(newTasks, planInstanceTask);
                    }
                }
            } else {
                Preconditions.checkState(pingHealth == AgentPingHealth.UNDETERMINED);
                syncComplete = false;
                //better luck next time. wait until agent health is determined.
            }
        }

        for (final ServiceDeploymentPlan servicePlan : state.getDeploymentPlan().getServices()) {
            final URI serviceId = servicePlan.getServiceConfig().getServiceId();
            final ServiceState serviceState = getServiceState(serviceId);
            final Iterable<URI> plannedInstanceIds = state.getDeploymentPlan().getInstanceIdsByServiceId(serviceId);
            Iterable<URI> actualInstanceIds =
                    (serviceState == null ? Lists.<URI>newArrayList() : serviceState.getInstanceIds());
            final Iterable<URI> allInstanceIds =
                    ImmutableSet.copyOf(Iterables.concat(actualInstanceIds, plannedInstanceIds));
            if (serviceState == null
                    || !Iterables.elementsEqual(actualInstanceIds, allInstanceIds)) {

                syncComplete = false;
                final PlanServiceTask planServiceTask = new PlanServiceTask();
                planServiceTask.setStateId(serviceId);
                planServiceTask.setConsumerId(orchestratorId);
                // when scaling out, the service state should include the new planned instances.
                // when scaling in,  the service state should still include the old instances until they are removed.
                planServiceTask.setServiceInstanceIds(Lists.newArrayList(allInstanceIds));
                planServiceTask.setServiceConfig(servicePlan.getServiceConfig());
                addNewTaskIfNotExists(newTasks, planServiceTask);
            }
        }

        if (syncComplete) {
            state.setSyncedStateWithDeploymentBefore(true);
        }
        return syncComplete;
    }

    private Iterable<URI> getAgentIdsToTerminate() {

        return state.getAgentIdsToTerminate();
    }

    public Iterable<URI> getAllAgentIds() {
        return ImmutableSet.copyOf(Iterables.concat(getPlannedAgentIds(), getAgentIdsToTerminate()));
    }

    private Iterable<URI> getPlannedAgentIds() {
        return state.getDeploymentPlan().getAgentIds();
    }

    private void orchestrateServices(
            final List<Task> newTasks) {

        for (final URI serviceId : Iterables.concat(getPlannedServiceIds(), state.getServiceIdsToUninstall())) {
            orchestrateServiceInstancesInstallation(newTasks, serviceId);
            orchestrateServiceInstancesUninstall(newTasks, serviceId);
            orchestrateServiceProgress(newTasks, serviceId);
        }
    }

    private void orchestrateServiceProgress(List<Task> newTasks, URI serviceId) {
        final ServiceState serviceState = getServiceState(serviceId);
        final Predicate<URI> findInstanceNotStartedPredicate = new Predicate<URI>() {

            @Override
            public boolean apply(final URI instanceId) {
                String lifecycle = getServiceInstanceState(instanceId).getLifecycle();
                String instanceStartedLifecycle =
                        serviceState
                                .getServiceConfig()
                                .getInstanceLifecycleStateMachine()
                                .getFinalLifecycle();
                return !lifecycle.equals(instanceStartedLifecycle);
            }
        };

        Set<URI> serviceIdsToUninstall = state.getServiceIdsToUninstall();

        if (serviceIdsToUninstall.contains(serviceId)
                && serviceState.isProgress(
                ServiceState.Progress.INSTALLING_SERVICE,
                ServiceState.Progress.SERVICE_INSTALLED)) {
            ServiceUninstallingTask task = new ServiceUninstallingTask();
            task.setStateId(serviceId);
            task.setConsumerId(orchestratorId);
            addNewTaskIfNotExists(newTasks, task);
        } else if (serviceState.isProgress(ServiceState.Progress.INSTALLING_SERVICE)) {
            final boolean isServiceInstalling =
                    Iterables.tryFind(serviceState.getInstanceIds(), findInstanceNotStartedPredicate)
                            .isPresent();
            if (!isServiceInstalling) {
                ServiceInstalledTask task = new ServiceInstalledTask();
                task.setConsumerId(orchestratorId);
                task.setStateId(serviceId);
                addNewTaskIfNotExists(newTasks, task);
            }
        } else if (serviceState.isProgress(ServiceState.Progress.SERVICE_INSTALLED)) {
            final boolean isServiceInstalling =
                    Iterables.tryFind(serviceState.getInstanceIds(), findInstanceNotStartedPredicate)
                            .isPresent();
            if (isServiceInstalling) {
                ServiceInstallingTask task = new ServiceInstallingTask();
                task.setConsumerId(orchestratorId);
                task.setStateId(serviceId);
                addNewTaskIfNotExists(newTasks, task);
            }
        } else if (serviceState.isProgress(ServiceState.Progress.UNINSTALLING_SERVICE)) {
            if (serviceState.getInstanceIds().isEmpty()) {
                ServiceUninstalledTask task = new ServiceUninstalledTask();
                task.setConsumerId(orchestratorId);
                task.setStateId(serviceId);
                addNewTaskIfNotExists(newTasks, task);
            }
        } else if (serviceState.isProgress(ServiceState.Progress.SERVICE_UNINSTALLED)) {
            // do nothing
        } else {
            Preconditions.checkState(false, "Unknown service state" + serviceState.getProgress());
        }
    }

    private void orchestrateServiceInstancesInstallation(
            List<Task> newTasks,
            final URI serviceId) {

        final Iterable<URI> plannedInstanceIds = state.getDeploymentPlan().getInstanceIdsByServiceId(serviceId);
        for (final URI instanceId : plannedInstanceIds) {

            final URI agentId = state.getDeploymentPlan().getAgentIdByInstanceId(instanceId);
            final AgentState agentState = getAgentState(agentId);
            final ServiceInstanceState instanceState = getServiceInstanceState(instanceId);
            final String currentLifecycle = instanceState.getLifecycle();
            Preconditions.checkNotNull(currentLifecycle);
            final LifecycleStateMachine stateMachine = instanceState.getStateMachine();
            final String desiredLifecycle = state.getDeploymentPlan().getInstanceDesiredLifecycle(instanceId);
            final String nextLifecycle =
                    stateMachine.getNextInstanceLifecycle(
                            currentLifecycle,
                            desiredLifecycle);
            final boolean isAgentStarted = agentState.isProgress(AgentState.Progress.AGENT_STARTED);
            final boolean isInstanceLifecycle = nextLifecycle != null;

            if (isAgentStarted && isInstanceLifecycle) {
                if (!instanceState.isLifecycle(nextLifecycle)) {
                    //step to the next lifecycle state
                    final ServiceInstanceTask task = new ServiceInstanceTask();
                    task.setLifecycle(nextLifecycle);
                    task.setStateId(instanceId);
                    task.setConsumerId(instanceState.getAgentId());
                    addNewTaskIfNotExists(newTasks, task);
                }
                else {
                    // do nothing, we're done
                }
            } else {
                //follow the machine lifecycle as it is being started
                if (!instanceState.isLifecycle(agentState.getLifecycle())) {
                    final ServiceInstanceFollowsMachineLifecycleTask
                            unreachableInstanceTask = new ServiceInstanceFollowsMachineLifecycleTask();
                    unreachableInstanceTask.setConsumerId(orchestratorId);
                    unreachableInstanceTask.setStateId(instanceId);
                    addNewTaskIfNotExists(newTasks, unreachableInstanceTask);
                }
            }
        }
    }

    private void orchestrateServiceInstancesUninstall(
            List<Task> newTasks,
            final URI serviceId) {

        final Iterable<URI> plannedInstanceIds = state.getDeploymentPlan().getInstanceIdsByServiceId(serviceId);
        final ServiceState serviceState = getServiceState(serviceId);
        final List<URI> existingInstanceIds = serviceState.getInstanceIds();
        for (URI instanceId : subtract(existingInstanceIds, plannedInstanceIds)) {
            final ServiceInstanceState instanceState = getServiceInstanceState(instanceId);
            final URI agentId = instanceState.getAgentId();
            final AgentState agentState = getAgentState(agentId);

            final String currentLifecycle = instanceState.getLifecycle();
            Preconditions.checkNotNull(currentLifecycle);
            final LifecycleStateMachine stateMachine = instanceState.getStateMachine();
            final String nextLifecycle =
                    stateMachine.getNextInstanceLifecycle(
                            currentLifecycle,
                            stateMachine.getInitialLifecycle());

            if (instanceState.isLifecycle(stateMachine.getInitialLifecycle()) ||
                    instanceState.isLifecycle(agentState.getLifecycle())) {

                // remove instance from agent
                if (agentState.getServiceInstanceIds().contains(instanceId)) {
                    RemoveServiceInstanceFromAgentTask removeFromAgentTask = new RemoveServiceInstanceFromAgentTask();
                    if (isAgentProgress(agentState, AgentState.Progress.AGENT_STARTED)) {
                        removeFromAgentTask.setConsumerId(agentId);
                    }
                    else {
                        removeFromAgentTask.setConsumerId(orchestratorId);
                    }
                    removeFromAgentTask.setStateId(agentId);
                    removeFromAgentTask.setInstanceId(instanceId);
                    addNewTaskIfNotExists(newTasks, removeFromAgentTask);
                }

                // remove instance from service
                if (serviceState.getInstanceIds().contains(instanceId)) {
                    final RemoveServiceInstanceFromServiceTask task = new RemoveServiceInstanceFromServiceTask();
                    task.setConsumerId(orchestratorId);
                    task.setStateId(serviceId);
                    task.setInstanceId(instanceId);
                    addNewTaskIfNotExists(newTasks, task);
                }
            } else if (isAgentProgress(agentState, AgentState.Progress.AGENT_STARTED)) {
                Preconditions.checkNotNull(nextLifecycle);
                if (!instanceState.isLifecycle(nextLifecycle)) {
                    //step to the previous lifecycle step
                    final ServiceInstanceTask task = new ServiceInstanceTask();
                    task.setLifecycle(nextLifecycle);
                    task.setConsumerId(agentId);
                    task.setStateId(instanceId);
                    addNewTaskIfNotExists(newTasks, task);
                }
            } else {
                if (!instanceState.isLifecycle(agentState.getLifecycle())) {
                    //machine is not reachable, follow the machine lifecycle as it is going down.
                    final ServiceInstanceFollowsMachineLifecycleTask
                            unreachableInstanceTask = new ServiceInstanceFollowsMachineLifecycleTask();
                    unreachableInstanceTask.setConsumerId(orchestratorId);
                    unreachableInstanceTask.setStateId(instanceId);
                    addNewTaskIfNotExists(newTasks, unreachableInstanceTask);
                }
            }
        }
    }

    private boolean isAgentProgress(AgentState agentState,
                                    String ... expectedProgresses) {
        return agentState != null && agentState.isProgress(expectedProgresses);
    }

    private ServiceState getServiceState(final URI serviceId) {
        return ServiceUtils.getServiceState(stateReader, serviceId);
    }

    private ServiceInstanceState getServiceInstanceState(URI instanceId) {
        return ServiceUtils.getServiceInstanceState(stateReader, instanceId);
    }

    /**
     * Ping all agents that are not doing anything.
     */
    private void pingAgents(List<Task> newTasks) {

        long nowTimestamp = timeProvider.currentTimeMillis();
        for (final URI agentId : getAllAgentIds()) {

            final AgentState agentState = getAgentState(agentId);

            AgentPingHealth agentPingHealth = getAgentPingHealth(agentId, nowTimestamp);
            if (agentPingHealth.equals(AgentPingHealth.AGENT_REACHABLE)) {
                final long taskTimestamp = agentState.getLastPingSourceTimestamp();
                final long sincePingMilliseconds = nowTimestamp - taskTimestamp;
                if (sincePingMilliseconds < AGENT_REACHABLE_RENEW_MILLISECONDS) {
                    continue;
                }
            }

            final PingAgentTask pingTask = new PingAgentTask();
            pingTask.setConsumerId(agentId);
            if (isAgentProgress(agentState,
                    AgentState.Progress.AGENT_STARTED)) {
                pingTask.setExpectedNumberOfAgentRestartsInAgentState(agentState.getNumberOfAgentStarts());
                pingTask.setExpectedNumberOfMachineRestartsInAgentState(agentState.getNumberOfMachineStarts());
            }
            addNewTaskIfNotExists(newTasks, pingTask);
        }
    }

    private void orchestrateAgents(List<Task> newTasks) {
        final long nowTimestamp = timeProvider.currentTimeMillis();

        for (final URI agentId : getPlannedAgentIds()) {

            final String desiredLifecycle = AgentState.Progress.AGENT_STARTED;
            boolean reachedDesiredLifecycle = orchestrateDesiredLifecycle(newTasks, nowTimestamp, agentId, desiredLifecycle);
            if (reachedDesiredLifecycle) {
                // do nothing.
            }
        }

        for (URI agentId : ImmutableList.copyOf(getAgentIdsToTerminate())) {
            final String desiredLifecycle = AgentState.Progress.MACHINE_TERMINATED;
            boolean reachedDesiredLifecycle = orchestrateDesiredLifecycle(newTasks, nowTimestamp, agentId, desiredLifecycle);
            if (reachedDesiredLifecycle) {
                state.removeAgentIdToTerminate(agentId);
            }
        }
    }

    private boolean orchestrateDesiredLifecycle(List<Task> newTasks, long nowTimestamp, URI agentId,
                                                String desiredLifecycle) {
        boolean reachedState = false;
        AgentState agentState = getAgentState(agentId);

        if (isUnreachable(nowTimestamp, agentId, agentState)) {
            final MachineLifecycleTask task = new MachineLifecycleTask();
            task.setLifecycle(AgentState.Progress.MACHINE_UNREACHABLE);
            task.setStateId(agentId);
            task.setConsumerId(machineProvisionerId);
            addNewTaskIfNotExists(newTasks, task);
        } else if (!agentState.getLifecycle().equals(desiredLifecycle)) {
            final String nextAgentLifecycle = agentState.getNextAgentLifecycle(desiredLifecycle);
            if (isInstancesLifecycleEqualsAgentLifecycle(agentState)) {
                final MachineLifecycleTask task = new MachineLifecycleTask();
                task.setLifecycle(nextAgentLifecycle);
                task.setStateId(agentId);
                task.setConsumerId(machineProvisionerId);
                addNewTaskIfNotExists(newTasks, task);
            }
        } else {
            reachedState = true;
        }
        return reachedState;
    }

    private boolean isUnreachable(long nowTimestamp, URI agentId, AgentState agentState) {
        Preconditions.checkNotNull(agentState);
        final AgentPingHealth pingHealth = getAgentPingHealth(agentId, nowTimestamp);

        return isAgentProgress(agentState,AgentState.Progress.AGENT_STARTED) &&
                pingHealth == AgentPingHealth.AGENT_UNREACHABLE;
    }

    private boolean isInstancesLifecycleEqualsAgentLifecycle(final AgentState agentState) {
        final Iterable<URI> instanceIds = agentState.getServiceInstanceIds();
        final String instanceLifecycle = agentState.getLifecycle();
        boolean foundNotEquals =
                Iterables.tryFind(instanceIds, new Predicate<URI>() {
                    @Override
                    public boolean apply(URI instanceId) {
                        return !getServiceInstanceState(instanceId).isLifecycle(instanceLifecycle);
                    }
                }).isPresent();

        return !foundNotEquals;
    }

    private AgentPingHealth getAgentPingHealth(URI agentId, long nowTimestamp) {

        AgentPingHealth health = AgentPingHealth.UNDETERMINED;

        // look for ping that should have been consumed by now --> AGENT_NOT_RESPONDING
        AgentState agentState = getAgentState(agentId);

        // look for ping that was consumed just recently --> AGENT_REACHABLE
        if (agentState != null) {
            final long taskTimestamp = agentState.getLastPingSourceTimestamp();
            final long sincePingMilliseconds = nowTimestamp - taskTimestamp;
            if (sincePingMilliseconds <= AGENT_UNREACHABLE_MILLISECONDS) {
                // ping was consumed just recently
                health = AgentPingHealth.AGENT_REACHABLE;
            }
        }

        if (health == AgentPingHealth.UNDETERMINED) {

            Iterable<Task> pendingTasks = taskReader.getPendingTasks(agentId);
            for (final Task task : pendingTasks) {
                Preconditions.checkState(
                        task.getProducerId().equals(orchestratorId),
                        "All agent tasks are assumed to be from this orchestrator");
                if (task instanceof PingAgentTask) {
                    PingAgentTask pingAgentTask = (PingAgentTask) task;
                    Integer expectedNumberOfAgentRestartsInAgentState =
                            pingAgentTask.getExpectedNumberOfAgentRestartsInAgentState();
                    Integer expectedNumberOfMachineRestartsInAgentState =
                            pingAgentTask.getExpectedNumberOfMachineRestartsInAgentState();
                    if (expectedNumberOfAgentRestartsInAgentState == null && agentState != null) {
                        Preconditions.checkState(expectedNumberOfMachineRestartsInAgentState == null);
                        if (agentState.isProgress(AgentState.Progress.AGENT_STARTED)) {
                            // agent started after ping sent. Wait for next ping
                        } else {
                            // agent not reachable because it was not started yet
                            health = AgentPingHealth.AGENT_UNREACHABLE;
                        }
                    } else if (expectedNumberOfMachineRestartsInAgentState != null
                            && agentState != null
                            && expectedNumberOfMachineRestartsInAgentState != agentState.getNumberOfMachineStarts()) {
                        Preconditions.checkState(
                                expectedNumberOfMachineRestartsInAgentState < agentState.getNumberOfMachineStarts(),
                                "Could not have sent ping to a machine that was not restarted yet");
                        // machine restarted after ping sent. Wait for next ping
                    } else if (expectedNumberOfAgentRestartsInAgentState != null
                            && agentState != null
                            && expectedNumberOfAgentRestartsInAgentState != agentState.getNumberOfAgentStarts()) {
                        Preconditions.checkState(
                                expectedNumberOfAgentRestartsInAgentState < agentState.getNumberOfAgentStarts(),
                                "Could not have sent ping to an agent that was not restarted yet");
                        // agent restarted after ping sent. Wait for next ping
                    } else {
                        final long taskTimestamp = task.getProducerTimestamp();
                        final long notRespondingMilliseconds = nowTimestamp - taskTimestamp;
                        if (notRespondingMilliseconds > AGENT_UNREACHABLE_MILLISECONDS) {
                            // ping should have been consumed by now
                            health = AgentPingHealth.AGENT_UNREACHABLE;
                        }
                    }
                }
            }
        }

        return health;
    }

    private AgentState getAgentState(URI agentId) {
        return ServiceUtils.getAgentState(stateReader, agentId);
    }

    /**
     * A three state enum that determines if the agent can process tasks.
     */
    public enum AgentPingHealth {
        UNDETERMINED, AGENT_UNREACHABLE, AGENT_REACHABLE
    }

    /**
     * Adds a new task only if it has not been added recently.
     */
    public void addNewTaskIfNotExists(
            final List<Task> newTasks,
            final Task newTask) {

        addNewTask(newTasks, newTask);
    }

    private static void addNewTask(List<Task> newTasks, final Task task) {
        newTasks.add(task);
    }

    @TaskConsumerStateHolder
    public ServiceGridOrchestratorState getState() {
        return state;
    }

    private Iterable<URI> getPlannedServiceIds() {
        return state.getDeploymentPlan().getServiceIds();
    }

    /**
     * @return old ids that are not in the newIds, maintaining order, removing duplicates.
     */
    public static Iterable<URI> subtract(final Iterable<URI> oldIds, final Iterable<URI> newIds) {
        final Set<URI> idsToFilter = Sets.newHashSet(newIds);
        final Iterable<URI> diffWithDuplicates =
                Iterables.filter(oldIds, new Predicate<URI>() {

                    @Override
                    public boolean apply(URI id) {
                        return !idsToFilter.contains(id);
                    }
                });
        return ImmutableSet.copyOf(diffWithDuplicates);
    }
}
