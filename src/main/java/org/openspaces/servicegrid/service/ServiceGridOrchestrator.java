package org.openspaces.servicegrid.service;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.openspaces.servicegrid.ImpersonatingTaskConsumer;
import org.openspaces.servicegrid.Task;
import org.openspaces.servicegrid.TaskConsumer;
import org.openspaces.servicegrid.TaskConsumerStateHolder;
import org.openspaces.servicegrid.TaskConsumerStateModifier;
import org.openspaces.servicegrid.TaskProducer;
import org.openspaces.servicegrid.TaskReader;
import org.openspaces.servicegrid.agent.state.AgentState;
import org.openspaces.servicegrid.agent.tasks.PingAgentTask;
import org.openspaces.servicegrid.agent.tasks.PlanAgentTask;
import org.openspaces.servicegrid.agent.tasks.StartAgentTask;
import org.openspaces.servicegrid.agent.tasks.StartMachineTask;
import org.openspaces.servicegrid.agent.tasks.TerminateMachineOfNonResponsiveAgentTask;
import org.openspaces.servicegrid.agent.tasks.TerminateMachineTask;
import org.openspaces.servicegrid.service.state.ServiceConfig;
import org.openspaces.servicegrid.service.state.ServiceGridOrchestratorState;
import org.openspaces.servicegrid.service.state.ServiceInstanceState;
import org.openspaces.servicegrid.service.state.ServiceState;
import org.openspaces.servicegrid.service.tasks.InstallServiceInstanceTask;
import org.openspaces.servicegrid.service.tasks.MarkAgentAsStoppingTask;
import org.openspaces.servicegrid.service.tasks.PlanServiceInstanceTask;
import org.openspaces.servicegrid.service.tasks.PlanServiceTask;
import org.openspaces.servicegrid.service.tasks.RecoverServiceInstanceStateTask;
import org.openspaces.servicegrid.service.tasks.RemoveServiceInstanceTask;
import org.openspaces.servicegrid.service.tasks.ServiceInstalledTask;
import org.openspaces.servicegrid.service.tasks.ServiceInstallingTask;
import org.openspaces.servicegrid.service.tasks.ServiceInstanceUnreachableTask;
import org.openspaces.servicegrid.service.tasks.ServiceUninstalledTask;
import org.openspaces.servicegrid.service.tasks.ServiceUninstallingTask;
import org.openspaces.servicegrid.service.tasks.StartServiceInstanceTask;
import org.openspaces.servicegrid.service.tasks.StopServiceInstanceTask;
import org.openspaces.servicegrid.service.tasks.UpdateDeploymentPlanTask;
import org.openspaces.servicegrid.state.StateReader;
import org.openspaces.servicegrid.streams.StreamUtils;
import org.openspaces.servicegrid.time.CurrentTimeProvider;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

public class ServiceGridOrchestrator {

	private static final long AGENT_UNREACHABLE_MILLISECONDS = TimeUnit.SECONDS.toMillis(30);

	private static final long AGENT_REACHABLE_RENEW_MILLISECONDS = AGENT_UNREACHABLE_MILLISECONDS /2;

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
	}

	@TaskProducer
	public Iterable<Task> orchestrate() {
	
		final List<Task> newTasks = Lists.newArrayList();
		
		if (state.getDeploymentPlan() != null) {
			
			boolean ready = syncStateWithDeploymentPlan(newTasks);
			
			if (ready) {
				//start orchestrating according to current state
				final Iterable<URI> healthyAgents = orchestrateAgents(newTasks);
				orchestrateServices(newTasks, healthyAgents);
			}
	
			pingAgents(newTasks);
		}
		return newTasks;
	}

	@TaskConsumer
	public void updateDeploymentPlan(UpdateDeploymentPlanTask task) {
		if (state.getDeploymentPlan() == null) {
			state.setDeploymentPlan(task.getDeploymentPlan());
		}
		else {
			final Iterable<URI> oldServiceIds = getPlannedServiceIds();
			final Iterable<URI> oldAgentIds = getPlannedAgentIds();
			state.setDeploymentPlan(task.getDeploymentPlan());
			final Iterable<URI> newServiceIds = getPlannedServiceIds();
			final Iterable<URI> newAgentIds = getPlannedAgentIds();
			state.addServiceIdsToUninstall(StreamUtils.diff(oldServiceIds, newServiceIds));
			state.addAgentIdsToTerminate(StreamUtils.diff(oldAgentIds, newAgentIds));
		}
	}

	@ImpersonatingTaskConsumer
	public void planAgent(PlanAgentTask task,
			TaskConsumerStateModifier<AgentState> impersonatedStateModifier) {
		AgentState oldState = impersonatedStateModifier.get();
		int numberOfMachineRestarts = 0;
		if (oldState != null) {
			numberOfMachineRestarts = oldState.getNumberOfMachineRestarts() + 1;
		}
		AgentState impersonatedAgentState = new AgentState();
		impersonatedAgentState.setProgress(AgentState.Progress.PLANNED);
		impersonatedAgentState.setServiceInstanceIds(task.getServiceInstanceIds());
		impersonatedAgentState.setNumberOfMachineRestarts(numberOfMachineRestarts);
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
		instanceState.setProgress(ServiceInstanceState.Progress.PLANNED);
		instanceState.setAgentId(planInstanceTask.getAgentId());
		instanceState.setServiceId(planInstanceTask.getServiceId());
		impersonatedStateModifier.put(instanceState);
	}
	
	@ImpersonatingTaskConsumer
	public void removeServiceInstanceFromService(
			final RemoveServiceInstanceTask task,
			final TaskConsumerStateModifier<ServiceState> impersonatedStateModifier) {
		
		final ServiceState serviceState = (ServiceState) impersonatedStateModifier.get();
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
	public void serviceInstanceUnreachable(final ServiceInstanceUnreachableTask task,
			final TaskConsumerStateModifier<ServiceInstanceState> impersonatedStateModifier) {
		ServiceInstanceState serviceState = impersonatedStateModifier.get();
		serviceState.setProgress(ServiceInstanceState.Progress.INSTANCE_UNREACHABLE);
		impersonatedStateModifier.put(serviceState);
	}
	
	private boolean syncStateWithDeploymentPlan(final List<Task> newTasks) {	
		boolean syncComplete = true;
		final long nowTimestamp = timeProvider.currentTimeMillis();
		for (final URI agentId : getPlannedAgentIds()) {
			AgentPingHealth pingHealth = getAgentPingHealth(agentId, nowTimestamp);
			AgentState agentState = getAgentState(agentId);
			boolean agentNotStarted = (agentState == null || !agentState.getProgress().equals(AgentState.Progress.AGENT_STARTED));
			if (agentNotStarted && state.isSyncedStateWithDeploymentBefore() && pingHealth == AgentPingHealth.UNDETERMINED) {
				//If this agent were started, we would have resolved it as AGENT_STARTED in the previous sync
				//The agent probably never even started
				pingHealth = AgentPingHealth.AGENT_UNREACHABLE;
			}
			if (pingHealth == AgentPingHealth.AGENT_REACHABLE) {
				Preconditions.checkState(agentState != null, "Responding agent cannot have null state");
				for (URI instanceId : state.getServiceInstanceIdsOfAgent(agentId)) {
					if (getServiceInstanceState(instanceId) == null) {
						syncComplete = false;
						final URI serviceId = state.getServiceIdOfServiceInstance(instanceId);
						final RecoverServiceInstanceStateTask recoverInstanceStateTask = new RecoverServiceInstanceStateTask();
						recoverInstanceStateTask.setStateId(instanceId);	
						recoverInstanceStateTask.setConsumerId(agentId);
						recoverInstanceStateTask.setServiceId(serviceId);
						addNewTaskIfNotExists(newTasks, recoverInstanceStateTask);
					}
				}
			}
			else if (pingHealth == AgentPingHealth.AGENT_UNREACHABLE) {
				Iterable<URI> plannedInstanceIds = state.getServiceInstanceIdsOfAgent(agentId);
				if (agentState == null ||
					agentState.getProgress().equals(AgentState.Progress.MACHINE_TERMINATED)) {
					syncComplete = false;
					final PlanAgentTask planAgentTask = new PlanAgentTask();
					planAgentTask.setStateId(agentId);	
					planAgentTask.setConsumerId(orchestratorId);
					planAgentTask.setServiceInstanceIds(Lists.newArrayList(plannedInstanceIds));
					addNewTaskIfNotExists(newTasks, planAgentTask);
				}				
				for (URI instanceId : state.getServiceInstanceIdsOfAgent(agentId)) {
					if (getServiceInstanceState(instanceId) == null) {
						syncComplete = false;
						final URI serviceId = state.getServiceIdOfServiceInstance(instanceId);
						final PlanServiceInstanceTask planInstanceTask = new PlanServiceInstanceTask();
						planInstanceTask.setStateId(instanceId);
						planInstanceTask.setAgentId(agentId);
						planInstanceTask.setServiceId(serviceId);
						planInstanceTask.setConsumerId(orchestratorId);
						addNewTaskIfNotExists(newTasks, planInstanceTask);
					}
				}
			}
			else {
				Preconditions.checkState(pingHealth == AgentPingHealth.UNDETERMINED);
				syncComplete = false;
				//better luck next time. wait until agent health is determined.
			}
		}
		
		for (final ServiceConfig service : state.getServices()) {
			final URI serviceId = service.getServiceId();
			final ServiceState serviceState = getServiceState(serviceId);
			final Iterable<URI> plannedInstanceIds = state.getServiceInstanceIdsOfService(serviceId);
			Iterable<URI> actualInstanceIds = (serviceState == null ? Lists.<URI>newArrayList() : serviceState.getInstanceIds());
			final Iterable<URI> allInstanceIds = StreamUtils.concat(actualInstanceIds, plannedInstanceIds);
			if (serviceState == null ||
				!Iterables.elementsEqual(actualInstanceIds, allInstanceIds)) {
				
				syncComplete = false;
				final PlanServiceTask planServiceTask = new PlanServiceTask();
				planServiceTask.setStateId(serviceId);
				planServiceTask.setConsumerId(orchestratorId);
				// when scaling out, the service state should include the new planned instances.
				// when scaling in,  the service state should still include the old instances until they are removed.
				planServiceTask.setServiceInstanceIds(Lists.newArrayList(allInstanceIds));
				planServiceTask.setServiceConfig(service);
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
		return StreamUtils.concat(getPlannedAgentIds(), getAgentIdsToTerminate());
	}

	private Iterable<URI> getPlannedAgentIds() {
		return state.getAgentIds();
	}

	private void orchestrateServices(
			final List<Task> newTasks,
			final Iterable<URI> healthyAgents) {
		
		for (final URI serviceId : Iterables.concat(getPlannedServiceIds(), state.getServiceIdsToUninstall())) {
			orchestrateServiceInstancesInstallation(newTasks, healthyAgents, serviceId);
			orchestrateServiceInstancesUninstall(newTasks, healthyAgents, serviceId);
			orchestrateServiceProgress(newTasks, serviceId);
		}
	}

	private void orchestrateServiceProgress(List<Task> newTasks, URI serviceId) {
		final ServiceState serviceState = getServiceState(serviceId);
		final String serviceProgress = serviceState.getProgress();
		Preconditions.checkNotNull(serviceProgress);
		final Predicate<URI> findInstanceNotStartedPredicate = new Predicate<URI>(){

			@Override
			public boolean apply(final URI instanceId) {
				return !getServiceInstanceState(instanceId).getProgress().equals(ServiceInstanceState.Progress.INSTANCE_STARTED);
			}
		};
		
		Set<URI> serviceIdsToUninstall = state.getServiceIdsToUninstall();
			
		if (serviceIdsToUninstall.contains(serviceId) &&
			!serviceProgress.equals(ServiceState.Progress.UNINSTALLING_SERVICE) && 
			!serviceProgress.equals(ServiceState.Progress.SERVICE_UNINSTALLED)) {
			ServiceUninstallingTask task = new ServiceUninstallingTask();
			task.setStateId(serviceId);
			task.setConsumerId(orchestratorId);
			addNewTaskIfNotExists(newTasks, task);
		}
		else if (serviceProgress.equals(ServiceState.Progress.INSTALLING_SERVICE)) {
			final boolean foundNonStartedInstance = 
				Iterables.tryFind(serviceState.getInstanceIds(), findInstanceNotStartedPredicate).isPresent();
			final boolean foundNonStoppedInstance = false; //TODO: scaleIn use case
			final boolean isServiceInstalling = foundNonStartedInstance || foundNonStoppedInstance;
			if (!isServiceInstalling) {
				ServiceInstalledTask task = new ServiceInstalledTask();
				task.setConsumerId(orchestratorId);
				task.setStateId(serviceId);
				addNewTaskIfNotExists(newTasks, task);
			}
		}
		else if (serviceProgress.equals(ServiceState.Progress.SERVICE_INSTALLED)) {
			final boolean foundNonStartedInstance = 
					Iterables.tryFind(serviceState.getInstanceIds(), findInstanceNotStartedPredicate).isPresent();
			final boolean foundNonStoppedInstance = false; //TODO: scaleIn use case
			final boolean isServiceInstalling = foundNonStartedInstance || foundNonStoppedInstance;
			if (isServiceInstalling) {
				ServiceInstallingTask task = new ServiceInstallingTask();
				task.setConsumerId(orchestratorId);
				task.setStateId(serviceId);
				addNewTaskIfNotExists(newTasks, task);
			}
		}
		else if (serviceProgress.equals(ServiceState.Progress.UNINSTALLING_SERVICE)) {
			if (serviceState.getInstanceIds().isEmpty()) {
				ServiceUninstalledTask task = new ServiceUninstalledTask();
				task.setConsumerId(orchestratorId);
				task.setStateId(serviceId);
				addNewTaskIfNotExists(newTasks, task);
			}
		}
		else if (serviceProgress.equals(ServiceState.Progress.SERVICE_UNINSTALLED)) {
			// do nothing
		}
		else {
			Preconditions.checkState(false, "Unknown service state " + serviceProgress);
		}
	}

	private void orchestrateServiceInstancesInstallation(
			List<Task> newTasks,
			final Iterable<URI> healthyAgents, 
			final URI serviceId) {
		
		final Iterable<URI> plannedInstanceIds = state.getServiceInstanceIdsOfService(serviceId);
		for (final URI instanceId : plannedInstanceIds) {
			
			final URI agentId = state.getAgentIdOfServiceInstance(instanceId);
			if (!Iterables.contains(healthyAgents,agentId)) {
				//no agent yet
				continue;
			}
			
			orchestrateServiceInstanceInstallation(newTasks, instanceId, agentId);
		}
	}
	
	private void orchestrateServiceInstancesUninstall(
			List<Task> newTasks,
			final Iterable<URI> healthyAgents, 
			final URI serviceId) {
	
		final Iterable<URI> plannedInstanceIds = state.getServiceInstanceIdsOfService(serviceId);
		final List<URI> existingInstanceIds = getServiceState(serviceId).getInstanceIds();
		final Iterable<URI> instanceIdsToStop = StreamUtils.diff(existingInstanceIds, plannedInstanceIds);
		for (URI instanceIdToStop : instanceIdsToStop) {
			final ServiceInstanceState instanceState = getServiceInstanceState(instanceIdToStop);
			final URI agentId = instanceState.getAgentId();
			if (Iterables.contains(healthyAgents, agentId)) {
				orchestrateServiceInstanceUninstallation(newTasks, instanceIdToStop, agentId);
			}
			else {
				{
				final ServiceInstanceUnreachableTask task = new ServiceInstanceUnreachableTask();
				task.setConsumerId(orchestratorId);
				task.setStateId(instanceIdToStop);
				addNewTaskIfNotExists(newTasks, task);
				}
				{
				final RemoveServiceInstanceTask task = new RemoveServiceInstanceTask();
				task.setConsumerId(orchestratorId);
				task.setStateId(instanceState.getServiceId());
				task.setInstanceId(instanceIdToStop);
				addNewTaskIfNotExists(newTasks, task);
				}
			}
		}
	}

	private ServiceState getServiceState(final URI serviceId) {
		return ServiceUtils.getServiceState(stateReader, serviceId);
	}

	private void orchestrateServiceInstanceUninstallation(
			final List<Task> newTasks,
			final URI instanceIdToStop, 
			final URI agentId) {
		
		ServiceInstanceState instanceState = getServiceInstanceState(instanceIdToStop);
		final String instanceProgress = instanceState.getProgress();
		Preconditions.checkNotNull(instanceProgress);
		if (!instanceProgress.equals(ServiceInstanceState.Progress.STOPPING_INSTANCE) &&
			!instanceProgress.equals(ServiceInstanceState.Progress.INSTANCE_STOPPED)) {
			
			final StopServiceInstanceTask task = new StopServiceInstanceTask();
			task.setConsumerId(agentId);
			task.setStateId(instanceIdToStop);
			addNewTaskIfNotExists(newTasks, task);
		}
		else if (instanceProgress.equals(ServiceInstanceState.Progress.INSTANCE_STOPPED)) {
			
			final RemoveServiceInstanceTask task = new RemoveServiceInstanceTask();
			task.setConsumerId(orchestratorId);
			task.setStateId(instanceState.getServiceId());
			task.setInstanceId(instanceIdToStop);
			addNewTaskIfNotExists(newTasks, task);
		}
	}

	private ServiceInstanceState getServiceInstanceState(URI instanceId) {
		return ServiceUtils.getServiceInstanceState(stateReader, instanceId);
	}
	
	/**
	 * Ping all agents that are not doing anything
	 */
	private void pingAgents(List<Task> newTasks) {
		
		long nowTimestamp = timeProvider.currentTimeMillis();
		for (final URI agentId : getAllAgentIds()) {

			final AgentState agentState = getAgentState(agentId);
			
			AgentPingHealth agentPingHealth = getAgentPingHealth(agentId, nowTimestamp);
			if (agentPingHealth.equals(AgentPingHealth.AGENT_REACHABLE)) {
				final long taskTimestamp = agentState.getLastPingSourceTimestamp();
				final long sincePingMilliseconds = nowTimestamp - taskTimestamp;
				if ( sincePingMilliseconds < AGENT_REACHABLE_RENEW_MILLISECONDS ) {
					continue;
				}
			}
			
			final PingAgentTask pingTask = new PingAgentTask();
			pingTask.setConsumerId(agentId);
			if (isAgentProgress(agentState, AgentState.Progress.AGENT_STARTED, AgentState.Progress.STOPPING_AGENT)) {
				pingTask.setExpectedNumberOfAgentRestartsInAgentState(agentState.getNumberOfAgentRestarts());
				pingTask.setExpectedNumberOfMachineRestartsInAgentState(agentState.getNumberOfMachineRestarts());
			}
			addNewTaskIfNotExists(newTasks, pingTask);
		}
	}

	/**
	 * @return true if the specified agentState.progress matches any of the specified progresses options.
	 */
	private boolean isAgentProgress(AgentState agentState, String ... expectedProgresses) {
		if (agentState != null) {
			for (String expectedProgress : expectedProgresses) {
				String agentProgress = agentState.getProgress();
				if (agentProgress != null && agentProgress.equals(expectedProgress)) {
					return true;
				}
			}
		}
		return false;
	}
	private void orchestrateServiceInstanceInstallation(List<Task> newTasks, URI instanceId, URI agentId) {
		ServiceInstanceState instanceState = getServiceInstanceState(instanceId);
		final String instanceProgress = instanceState.getProgress();
		Preconditions.checkNotNull(instanceProgress);
		
		if (instanceProgress.equals(ServiceInstanceState.Progress.PLANNED)) {
			
				final InstallServiceInstanceTask task = new InstallServiceInstanceTask();
				task.setStateId(instanceId);	
				task.setConsumerId(agentId);
				addNewTaskIfNotExists(newTasks, task);
		}
		else if (instanceProgress.equals(ServiceInstanceState.Progress.INSTANCE_INSTALLED)) {
			//Ask for start service instance
			final StartServiceInstanceTask task = new StartServiceInstanceTask();
			task.setStateId(instanceId);	
			task.setConsumerId(agentId);
			addNewTaskIfNotExists(newTasks, task);
		}
		else if (instanceProgress.equals(ServiceInstanceState.Progress.INSTANCE_STARTED)){
			//Do nothing, instance is installed
		}
		else {
			Preconditions.checkState(false, "Unknown service instance progress " + instanceProgress);
		}
	}

	private Iterable<URI> orchestrateAgents(List<Task> newTasks) {
		final long nowTimestamp = timeProvider.currentTimeMillis();
		final Set<URI> healthyAgents = Sets.newHashSet();
		for (final URI agentId : getAllAgentIds()) {
			final AgentPingHealth pingHealth = getAgentPingHealth(agentId, nowTimestamp);
			AgentState agentState = getAgentState(agentId);
			Preconditions.checkNotNull(agentState);
			if (pingHealth == AgentPingHealth.AGENT_REACHABLE) {
				healthyAgents.add(agentId);
				continue;
			}
			
			Preconditions.checkNotNull(agentState);
			if (isAgentProgress(agentState, AgentState.Progress.PLANNED)){
				final StartMachineTask task = new StartMachineTask();
				task.setStateId(agentId);	
				task.setConsumerId(machineProvisionerId);
				addNewTaskIfNotExists(newTasks, task);
			}
			else if (isAgentProgress(agentState, AgentState.Progress.MACHINE_STARTED)) {
				final StartAgentTask task = new StartAgentTask();
				task.setStateId(agentId);	
				task.setConsumerId(machineProvisionerId);
				task.setIpAddress(agentState.getIpAddress());
				addNewTaskIfNotExists(newTasks, task);
			}
			else if (isAgentProgress(agentState, AgentState.Progress.AGENT_STARTED)) {
				if (pingHealth == AgentPingHealth.AGENT_UNREACHABLE) {
					final TerminateMachineOfNonResponsiveAgentTask task = new TerminateMachineOfNonResponsiveAgentTask();
					task.setStateId(agentId);	
					task.setConsumerId(machineProvisionerId);
					addNewTaskIfNotExists(newTasks, task);
				}
			}
			else if (isAgentProgress(agentState, AgentState.Progress.MACHINE_TERMINATED)) {
				// move along. nothing to see here.
			}
			else {
				Preconditions.checkState(false, "Unrecognized agent state " + agentState.getProgress());
			}
		}
		
		for (URI agentId : getAgentIdsToTerminate()) {
			final AgentState agentState = getAgentState(agentId);
			
			if (isAgentProgress(agentState, AgentState.Progress.AGENT_STARTED)) {	
				MarkAgentAsStoppingTask task = new MarkAgentAsStoppingTask();
				task.setConsumerId(agentId);
				addNewTaskIfNotExists(newTasks, task);
			}
			else if (isAgentProgress(agentState, 
					AgentState.Progress.STOPPING_AGENT,
					AgentState.Progress.STARTING_MACHINE, 
					AgentState.Progress.MACHINE_STARTED, 
					AgentState.Progress.PLANNED)) {
				boolean isAllInstancesStopped = Iterables.isEmpty(agentState.getServiceInstanceIds());
				if (isAllInstancesStopped) {			
					TerminateMachineTask task = new TerminateMachineTask();
					task.setStateId(agentId);
					task.setConsumerId(machineProvisionerId);
					addNewTaskIfNotExists(newTasks, task);
				}
			}
			else if (isAgentProgress(agentState, AgentState.Progress.MACHINE_TERMINATED)) {
				if (state.getAgentIdsToTerminate().contains(agentId)) {
					state.removeAgentIdToTerminate(agentId);
				}
			}
			else {
				Preconditions.checkState(false, "Unknwon agent progress " + agentState.getProgress());
			}
		}
		
		return Iterables.unmodifiableIterable(healthyAgents);
	}

	private AgentPingHealth getAgentPingHealth(URI agentId, long nowTimestamp) {
		
		AgentPingHealth health = AgentPingHealth.UNDETERMINED;
		
		// look for ping that should have been consumed by now --> AGENT_NOT_RESPONDING
		AgentState agentState = getAgentState(agentId);
		Iterable<Task> pendingTasks = taskReader.getPendingTasks(agentId);
		for (final Task task : pendingTasks) {
			Preconditions.checkState(task.getProducerId().equals(orchestratorId), "All agent tasks are assumed to be from this orchestrator");
			if (task instanceof PingAgentTask) {
				PingAgentTask pingAgentTask = (PingAgentTask) task;
				Integer expectedNumberOfAgentRestartsInAgentState = pingAgentTask.getExpectedNumberOfAgentRestartsInAgentState();
				Integer expectedNumberOfMachineRestartsInAgentState = pingAgentTask.getExpectedNumberOfMachineRestartsInAgentState();
				if (expectedNumberOfAgentRestartsInAgentState == null && agentState != null) {
					Preconditions.checkState(expectedNumberOfMachineRestartsInAgentState == null);
					if (agentState.getProgress().equals(AgentState.Progress.AGENT_STARTED)) {
						// agent started after ping sent. Wait for next ping
					}
					else {
						// agent not reachable because it was not started yet
						health = AgentPingHealth.AGENT_UNREACHABLE;
					}
				}
				else if (expectedNumberOfAgentRestartsInAgentState != null && 
						 agentState != null && 
						 expectedNumberOfAgentRestartsInAgentState != agentState.getNumberOfAgentRestarts()) {
					Preconditions.checkState(expectedNumberOfAgentRestartsInAgentState < agentState.getNumberOfAgentRestarts(), "Could not have sent ping to an agent that was not restarted yet");
					// agent restarted after ping sent. Wait for next ping
				}
				else if (expectedNumberOfMachineRestartsInAgentState != null && 
						 agentState != null && 
						 expectedNumberOfMachineRestartsInAgentState != agentState.getNumberOfMachineRestarts()) {
					Preconditions.checkState(expectedNumberOfMachineRestartsInAgentState < agentState.getNumberOfMachineRestarts(), "Could not have sent ping to a machine that was not restarted yet");
					// machine restarted after ping sent. Wait for next ping
				}
				else {
					final long taskTimestamp = task.getProducerTimestamp();
					final long notRespondingMilliseconds = nowTimestamp - taskTimestamp;
					if ( notRespondingMilliseconds > AGENT_UNREACHABLE_MILLISECONDS ) {
						// ping should have been consumed by now
						health = AgentPingHealth.AGENT_UNREACHABLE;
					}
				}
			}
		}
		
		if (health == AgentPingHealth.UNDETERMINED) {
			// look for ping that was consumed just recently --> AGENT_RESPONDING
			if (agentState != null) {
				final long taskTimestamp = agentState.getLastPingSourceTimestamp();
				final long sincePingMilliseconds = nowTimestamp - taskTimestamp;
				if ( sincePingMilliseconds <= AGENT_UNREACHABLE_MILLISECONDS ) {
					// ping was consumed just recently
					health = AgentPingHealth.AGENT_REACHABLE;
				}
			}
		}
		
		return health;
	}

	private AgentState getAgentState(URI agentId) {
		return ServiceUtils.getAgentState(stateReader, agentId);
	}
	public enum AgentPingHealth {
		UNDETERMINED, AGENT_UNREACHABLE, AGENT_REACHABLE
	}

	/**
	 * Adds a new task only if it has not been added recently.
	 */
	public void addNewTaskIfNotExists(
			final List<Task> newTasks,
			final Task newTask) {
	
		if (newTask.getStateId() == null) {
			newTask.setStateId(newTask.getConsumerId());
		}
		
		if (!ServiceUtils.isTaskExecutingOrPending(stateReader, taskReader, newTask)) {
			addNewTask(newTasks, newTask);
		}
	}	
	
	private static void addNewTask(List<Task> newTasks, final Task task) {
		newTasks.add(task);
	}
	
	@TaskConsumerStateHolder
	public ServiceGridOrchestratorState getState() {
		return state;
	}


	private Iterable<URI> getPlannedServiceIds() {
		return state.getServiceIds();
	}
}
