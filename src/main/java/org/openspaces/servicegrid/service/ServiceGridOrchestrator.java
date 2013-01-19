package org.openspaces.servicegrid.service;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.openspaces.servicegrid.ImpersonatingTaskConsumer;
import org.openspaces.servicegrid.Task;
import org.openspaces.servicegrid.TaskConsumer;
import org.openspaces.servicegrid.TaskConsumerState;
import org.openspaces.servicegrid.TaskConsumerStateHolder;
import org.openspaces.servicegrid.TaskExecutorStateModifier;
import org.openspaces.servicegrid.TaskProducer;
import org.openspaces.servicegrid.agent.state.AgentState;
import org.openspaces.servicegrid.agent.tasks.PingAgentTask;
import org.openspaces.servicegrid.agent.tasks.PlanAgentTask;
import org.openspaces.servicegrid.agent.tasks.StartAgentTask;
import org.openspaces.servicegrid.agent.tasks.StartMachineTask;
import org.openspaces.servicegrid.agent.tasks.StopAgentTask;
import org.openspaces.servicegrid.agent.tasks.TerminateMachineOfNonResponsiveAgentTask;
import org.openspaces.servicegrid.agent.tasks.TerminateMachineTask;
import org.openspaces.servicegrid.service.state.ServiceConfig;
import org.openspaces.servicegrid.service.state.ServiceGridOrchestratorState;
import org.openspaces.servicegrid.service.state.ServiceInstanceState;
import org.openspaces.servicegrid.service.state.ServiceState;
import org.openspaces.servicegrid.service.tasks.InstallServiceInstanceTask;
import org.openspaces.servicegrid.service.tasks.MarkAgentAsStoppingTask;
import org.openspaces.servicegrid.service.tasks.ServiceUninstallingTask;
import org.openspaces.servicegrid.service.tasks.PlanServiceInstanceTask;
import org.openspaces.servicegrid.service.tasks.PlanServiceTask;
import org.openspaces.servicegrid.service.tasks.RecoverServiceInstanceStateTask;
import org.openspaces.servicegrid.service.tasks.RemoveServiceInstanceTask;
import org.openspaces.servicegrid.service.tasks.ServiceInstalledTask;
import org.openspaces.servicegrid.service.tasks.ServiceInstallingTask;
import org.openspaces.servicegrid.service.tasks.ServiceUninstalledTask;
import org.openspaces.servicegrid.service.tasks.StartServiceInstanceTask;
import org.openspaces.servicegrid.service.tasks.StopServiceInstanceTask;
import org.openspaces.servicegrid.service.tasks.UpdateDeploymentPlanTask;
import org.openspaces.servicegrid.streams.StreamReader;
import org.openspaces.servicegrid.streams.StreamUtils;
import org.openspaces.servicegrid.time.CurrentTimeProvider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

public class ServiceGridOrchestrator {

	private static final long NOT_RESPONDING_DETECTION_MILLISECONDS = TimeUnit.SECONDS.toMillis(30);

	private final ServiceGridOrchestratorState state;

	private final ObjectMapper mapper = StreamUtils.newJsonObjectMapper();
	private final StreamReader<Task> taskReader;
	private final URI machineProvisionerId;
	private final URI orchestratorId;
	private final StreamReader<TaskConsumerState> stateReader;

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
			final Iterable<URI> oldServiceIds = state.getServiceIds();
			state.setDeploymentPlan(task.getDeploymentPlan());
			final Iterable<URI> newServiceIds = state.getServiceIds();
			state.addOrphanServiceIds(difference(oldServiceIds, newServiceIds));
		}
	}

	@ImpersonatingTaskConsumer
	public void planAgent(PlanAgentTask task,
			TaskExecutorStateModifier impersonatedStateModifier) {
		AgentState oldState = impersonatedStateModifier.getState();
		int numberOfMachineRestarts = 0;
		if (oldState != null) {
			numberOfMachineRestarts = oldState.getNumberOfMachineRestarts() + 1;
		}
		AgentState impersonatedAgentState = new AgentState();
		impersonatedAgentState.setProgress(AgentState.Progress.PLANNED);
		impersonatedAgentState.setServiceInstanceIds(task.getServiceInstanceIds());
		impersonatedAgentState.setNumberOfMachineRestarts(numberOfMachineRestarts);
		impersonatedStateModifier.updateState(impersonatedAgentState);
	}

	@ImpersonatingTaskConsumer
	public void planService(PlanServiceTask task,
			TaskExecutorStateModifier impersonatedStateModifier) {
		
		ServiceState serviceState = impersonatedStateModifier.getState();
		if (serviceState == null) {
			serviceState = new ServiceState();
		}
		serviceState.setServiceConfig(task.getServiceConfig());	
		serviceState.setInstanceIds(task.getServiceInstanceIds());
		serviceState.setProgress(ServiceState.Progress.INSTALLING_SERVICE);
		impersonatedStateModifier.updateState(serviceState);
	}
	
	@ImpersonatingTaskConsumer
	public void serviceUninstalling(ServiceUninstallingTask task,
			TaskExecutorStateModifier impersonatedStateModifier) {
		ServiceState serviceState = impersonatedStateModifier.getState();
		serviceState.setProgress(ServiceState.Progress.UNINSTALLING_SERVICE);
		impersonatedStateModifier.updateState(serviceState);
	}
	
	@ImpersonatingTaskConsumer
	public void planServiceInstance(PlanServiceInstanceTask task,
			TaskExecutorStateModifier impersonatedStateModifier) {
		PlanServiceInstanceTask planInstanceTask = (PlanServiceInstanceTask) task;
		ServiceInstanceState instanceState = new ServiceInstanceState();
		instanceState.setProgress(ServiceInstanceState.Progress.PLANNED);
		instanceState.setAgentId(planInstanceTask.getAgentId());
		instanceState.setServiceId(planInstanceTask.getServiceId());
		impersonatedStateModifier.updateState(instanceState);
	}
	
	@ImpersonatingTaskConsumer
	public void removeServiceInstanceFromService (
			final RemoveServiceInstanceTask task,
			final TaskExecutorStateModifier impersonatedStateModifier) {
		
		ServiceState serviceState = (ServiceState) impersonatedStateModifier.getState();
		serviceState.removeInstance(task.getInstanceId());
		impersonatedStateModifier.updateState(serviceState);
	}
	
	@ImpersonatingTaskConsumer
	public void serviceInstalled(final ServiceInstalledTask task,
			final TaskExecutorStateModifier impersonatedStateModifier) {
		ServiceState serviceState = impersonatedStateModifier.getState();
		serviceState.setProgress(ServiceState.Progress.SERVICE_INSTALLED);
		impersonatedStateModifier.updateState(serviceState);
	}
	
	private boolean syncStateWithDeploymentPlan(final List<Task> newTasks) {	
		boolean syncComplete = true;
		final long nowTimestamp = timeProvider.currentTimeMillis();
		for (final URI agentId : state.getAgentIds()) {
			AgentPingHealth pingHealth = getAgentPingHealth(agentId, nowTimestamp);
			AgentState agentState = getAgentState(agentId);
			boolean agentNotStarted = (agentState == null || !agentState.getProgress().equals(AgentState.Progress.AGENT_STARTED));
			if (agentNotStarted && state.isSyncedStateWithDeploymentBefore() && pingHealth == AgentPingHealth.UNDETERMINED) {
				//If this agent were started, we would have resolved it as AGENT_STARTED in the previous sync
				//The agent probably never even started
				pingHealth = AgentPingHealth.AGENT_NOT_RESPONDING;
			}
			if (pingHealth == AgentPingHealth.AGENT_RESPONDING) {
				Preconditions.checkState(agentState != null, "Responding agent cannot have null state");
				for (URI instanceId : state.getServiceInstanceIdsOfAgent(agentId)) {
					if (getServiceInstanceState(instanceId) == null) {
						syncComplete = false;
						final URI serviceId = state.getServiceIdOfServiceInstance(instanceId);
						final RecoverServiceInstanceStateTask recoverInstanceStateTask = new RecoverServiceInstanceStateTask();
						recoverInstanceStateTask.setImpersonatedTarget(instanceId);	
						recoverInstanceStateTask.setTarget(agentId);
						recoverInstanceStateTask.setServiceId(serviceId);
						addNewTaskIfNotExists(newTasks, recoverInstanceStateTask);
					}
				}
			}
			else if (pingHealth == AgentPingHealth.AGENT_NOT_RESPONDING) {
				Iterable<URI> plannedInstanceIds = state.getServiceInstanceIdsOfAgent(agentId);
				if (agentState == null ||
					agentState.getProgress().equals(AgentState.Progress.MACHINE_TERMINATED)) {
					syncComplete = false;
					final PlanAgentTask planAgentTask = new PlanAgentTask();
					planAgentTask.setImpersonatedTarget(agentId);	
					planAgentTask.setTarget(orchestratorId);
					planAgentTask.setServiceInstanceIds(Lists.newArrayList(plannedInstanceIds));
					addNewTaskIfNotExists(newTasks, planAgentTask);
				}				
				for (URI instanceId : state.getServiceInstanceIdsOfAgent(agentId)) {
					if (getServiceInstanceState(instanceId) == null) {
						syncComplete = false;
						final URI serviceId = state.getServiceIdOfServiceInstance(instanceId);
						final PlanServiceInstanceTask planInstanceTask = new PlanServiceInstanceTask();
						planInstanceTask.setImpersonatedTarget(instanceId);
						planInstanceTask.setAgentId(agentId);
						planInstanceTask.setServiceId(serviceId);
						planInstanceTask.setTarget(orchestratorId);
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
			URI serviceId = service.getServiceId();
			ServiceState serviceState = getServiceState(serviceId);
			Iterable<URI> plannedInstanceIds = state.getServiceInstanceIdsOfService(serviceId);
			if (serviceState == null ||
				!Iterables.elementsEqual(serviceState.getInstanceIds(),plannedInstanceIds)) {
				syncComplete = false;
				final PlanServiceTask planServiceTask = new PlanServiceTask();
				planServiceTask.setImpersonatedTarget(serviceId);
				planServiceTask.setTarget(orchestratorId);
				planServiceTask.setServiceInstanceIds(Lists.newArrayList(plannedInstanceIds));
				planServiceTask.setServiceConfig(service);
				addNewTaskIfNotExists(newTasks, planServiceTask);
			}
		}
		
		for (final URI serviceId : state.getOrphanServiceIds()) {
			ServiceUninstallingTask task = new ServiceUninstallingTask();
			task.setImpersonatedTarget(serviceId);
			task.setTarget(orchestratorId);
			addNewTaskIfNotExists(newTasks, task);
		}
		
		if (syncComplete) {
			state.setSyncedStateWithDeploymentBefore(true);
		}
		return syncComplete;
	}

	private Iterable<URI> getOrphanAgentIds() {
		
		final Function<URI, URI> instanceIdToAgentIdFunction = new Function<URI, URI>() {

			@Override
			public URI apply(URI instanceId) {
				return getServiceInstanceState(instanceId).getAgentId();
			}
		};
		
		final Function<URI, Iterable<URI>> serviceIdToInstanceIdsFunction = new Function<URI,Iterable<URI>>() {

			@Override
			public Iterable<URI> apply(URI serviceId) {
				return getServiceState(serviceId).getInstanceIds();
			}
		};
		
		final Iterable<URI> instanceIds = 
				Iterables.concat(Iterables.transform(
					state.getServiceIds(), serviceIdToInstanceIdsFunction));
		final Iterable<URI> actualAgentIds = Iterables.transform(instanceIds, instanceIdToAgentIdFunction);
		final Iterable<URI> plannedAgentIds = state.getAgentIds();
		final Iterable<URI> orphanAgentIds = difference(actualAgentIds, plannedAgentIds);
		
		return orphanAgentIds;
	}

	private void orchestrateServices(
			final List<Task> newTasks,
			final Iterable<URI> healthyAgents) {
		
		for (final URI serviceId : state.getServiceIds()) {
			orchestrateServiceInstanceInstallation(newTasks, healthyAgents, serviceId);
			orchestrateServiceInstanceUninstall(newTasks, serviceId);
			orchestrateServiceProgress(newTasks, serviceId);
		}
	}

	private void orchestrateServiceInstanceUninstall(final List<Task> newTasks,
			final URI serviceId) {
		final ServiceState serviceState = getServiceState(serviceId);
		final Iterable<URI> actualInstanceIds = serviceState.getInstanceIds();
		final Iterable<URI> plannedInstanceIds = state.getServiceInstanceIdsOfService(serviceId);
		final Iterable<URI> instanceIdsToStop = difference(actualInstanceIds, plannedInstanceIds);
		for (final URI instanceId : instanceIdsToStop) {
			final ServiceInstanceState instanceState = getServiceInstanceState(instanceId);
			final String instanceProgress = instanceState.getProgress();
			Preconditions.checkNotNull(instanceProgress);
			if (!instanceProgress.equals(ServiceInstanceState.Progress.STOPPING_INSTANCE) &&
				!instanceProgress.equals(ServiceInstanceState.Progress.INSTANCE_STOPPED)) {
				final URI agentId = state.getAgentIdOfServiceInstance(instanceId);
				final StopServiceInstanceTask task = new StopServiceInstanceTask();
				task.setTarget(agentId);
				task.setImpersonatedTarget(instanceId);
				addNewTaskIfNotExists(newTasks, task);					
			}
			else if (instanceProgress.equals(ServiceInstanceState.Progress.INSTANCE_STOPPED)) {
				final RemoveServiceInstanceTask task = new RemoveServiceInstanceTask();
				task.setTarget(orchestratorId);
				task.setImpersonatedTarget(instanceId);
				task.setInstanceId(instanceId);
				addNewTaskIfNotExists(newTasks, task);
			}
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
		final Predicate<URI> findNotStoppedInstanceOrAgentPredicate = new Predicate<URI>() {

			@Override
			public boolean apply(final URI instanceId) {
				final ServiceInstanceState instanceState = getServiceInstanceState(instanceId);
				return !instanceState.getProgress().equals(ServiceInstanceState.Progress.INSTANCE_STOPPED) || 
					   getAgentState(instanceState.getAgentId()).equals(AgentState.Progress.STOPPING_AGENT);
			}
		};

		if (serviceProgress.equals(ServiceState.Progress.INSTALLING_SERVICE)) {
			final boolean foundNonStartedInstance = 
				Iterables.tryFind(serviceState.getInstanceIds(), findInstanceNotStartedPredicate).isPresent();
			final boolean foundNonStoppedInstance = false; //TODO: scaleIn use case
			final boolean isServiceInstalling = foundNonStartedInstance || foundNonStoppedInstance;
			if (!isServiceInstalling) {
				ServiceInstalledTask task = new ServiceInstalledTask();
				task.setTarget(orchestratorId);
				task.setImpersonatedTarget(serviceId);
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
				task.setTarget(orchestratorId);
				task.setImpersonatedTarget(serviceId);
				addNewTaskIfNotExists(newTasks, task);
			}
		}
		else if (serviceProgress.equals(ServiceState.Progress.UNINSTALLING_SERVICE)) {
			final boolean foundNotStoppedInstanceOrAgent =
					Iterables.tryFind(serviceState.getInstanceIds(), findNotStoppedInstanceOrAgentPredicate).isPresent();
			if (!foundNotStoppedInstanceOrAgent) {
				ServiceUninstalledTask task = new ServiceUninstalledTask();
				task.setTarget(orchestratorId);
				task.setImpersonatedTarget(serviceId);
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

	private void orchestrateServiceInstanceInstallation(List<Task> newTasks,
			final Iterable<URI> healthyAgents, final URI serviceId) {
		final Iterable<URI> plannedInstanceIds = state.getServiceInstanceIdsOfService(serviceId);
		for (final URI instanceId : plannedInstanceIds) {
			
			final URI agentId = state.getAgentIdOfServiceInstance(instanceId);
			if (!Iterables.contains(healthyAgents,agentId)) {
				//no agent yet
				continue;
			}
			
			orchestrateServiceInstanceInstallation(newTasks, instanceId, agentId);
		}
		
		final List<URI> existingInstanceIds = getServiceState(serviceId).getInstanceIds();
		final Iterable<URI> instanceIdsToStop = difference(existingInstanceIds, plannedInstanceIds);
		for (URI instanceIdToStop : instanceIdsToStop) {
			ServiceInstanceState serviceInstanceState = getServiceInstanceState(instanceIdToStop);
			if (!serviceInstanceState.getProgress().equals(ServiceInstanceState.Progress.INSTANCE_STOPPED)) {
				final URI agentId = serviceInstanceState.getAgentId();
				Preconditions.checkState(Iterables.contains(healthyAgents, agentId));
				final StopServiceInstanceTask task = new StopServiceInstanceTask();
				task.setTarget(agentId);
				task.setImpersonatedTarget(instanceIdToStop);
				addNewTaskIfNotExists(newTasks, task);
			}
		}
	}

	private ServiceInstanceState getServiceInstanceState(URI instanceId) {
		return StreamUtils.getLastElement(stateReader, instanceId, ServiceInstanceState.class);
	}
	
	/**
	 * Ping all agents that are not doing anything
	 */
	private void pingAgents(List<Task> newTasks) {
		
		for (final URI agentId : state.getAgentIds()) {
					
			final AgentState agentState = getAgentState(agentId);
			final PingAgentTask pingTask = new PingAgentTask();
			pingTask.setTarget(agentId);
			if (agentState != null && agentState.getProgress().equals(AgentState.Progress.AGENT_STARTED)) {
				pingTask.setExpectedNumberOfAgentRestartsInAgentState(agentState.getNumberOfAgentRestarts());
				pingTask.setExpectedNumberOfMachineRestartsInAgentState(agentState.getNumberOfMachineRestarts());
			}
			addNewTaskIfNotExists(newTasks, pingTask);
		}
	
	}

	private void orchestrateServiceInstanceInstallation(List<Task> newTasks, URI instanceId, URI agentId) {
		ServiceInstanceState instanceState = getServiceInstanceState(instanceId);
		final String instanceProgress = instanceState.getProgress();
		Preconditions.checkNotNull(instanceProgress);
		
		if (instanceProgress.equals(ServiceInstanceState.Progress.PLANNED)) {
			
				final InstallServiceInstanceTask task = new InstallServiceInstanceTask();
				task.setImpersonatedTarget(instanceId);	
				task.setTarget(agentId);
				addNewTaskIfNotExists(newTasks, task);
		}
		else if (instanceProgress.equals(ServiceInstanceState.Progress.INSTANCE_INSTALLED)) {
			//Ask for start service instance
			final StartServiceInstanceTask task = new StartServiceInstanceTask();
			task.setImpersonatedTarget(instanceId);	
			task.setTarget(agentId);
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
		Set<URI> healthyAgents = Sets.newHashSet();
		for (URI agentId : state.getAgentIds()) {
			AgentPingHealth pingHealth = getAgentPingHealth(agentId, nowTimestamp);
			if (pingHealth == AgentPingHealth.AGENT_RESPONDING) {
				Preconditions.checkState(getAgentState(agentId).getProgress().equals(AgentState.Progress.AGENT_STARTED));
				healthyAgents.add(agentId);
				continue;
			}
			
			final AgentState agentState = getAgentState(agentId);
			final String agentProgress = agentState.getProgress();
			Preconditions.checkNotNull(agentProgress);
			
			if (agentProgress.equals(AgentState.Progress.PLANNED)){
				final StartMachineTask task = new StartMachineTask();
				task.setImpersonatedTarget(agentId);	
				task.setTarget(machineProvisionerId);
				addNewTaskIfNotExists(newTasks, task);
			}
			else if (agentProgress.equals(AgentState.Progress.MACHINE_STARTED)) {
				final StartAgentTask task = new StartAgentTask();
				task.setImpersonatedTarget(agentId);	
				task.setTarget(machineProvisionerId);
				task.setIpAddress(agentState.getIpAddress());
				addNewTaskIfNotExists(newTasks, task);
			}
			else if (agentProgress.equals(AgentState.Progress.AGENT_STARTED)) {
				if (pingHealth == AgentPingHealth.AGENT_NOT_RESPONDING) {
					final TerminateMachineOfNonResponsiveAgentTask task = new TerminateMachineOfNonResponsiveAgentTask();
					task.setImpersonatedTarget(agentId);	
					task.setTarget(machineProvisionerId);
					addNewTaskIfNotExists(newTasks, task);
				}
			}
			else {
				Preconditions.checkState(false, "Unrecognized agent state " + agentProgress);
			}
		}
		
		for (URI agentId : getOrphanAgentIds()) {
			final AgentState agentState = getAgentState(agentId);
			String agentProgress = agentState.getProgress();
			if (agentProgress.equals(AgentState.Progress.AGENT_STARTED)) {	
				MarkAgentAsStoppingTask task = new MarkAgentAsStoppingTask();
				task.setTarget(agentId);
				addNewTaskIfNotExists(newTasks, task);
			}
			else if (agentProgress.equals(AgentState.Progress.STOPPING_AGENT)) {
				boolean isAllInstancesStopped = Iterables.isEmpty(getNonStoppedInstances(agentState.getServiceInstanceIds()));
				if (isAllInstancesStopped) {			
					StopAgentTask task = new StopAgentTask();
					task.setTarget(agentId);
					addNewTaskIfNotExists(newTasks, task);
				}
			}
			else if (agentProgress.equals(AgentState.Progress.AGENT_STOPPED) ||
					 agentProgress.equals(AgentState.Progress.STARTING_MACHINE) || 
					 agentProgress.equals(AgentState.Progress.MACHINE_STARTED) ||
					 agentProgress.equals(AgentState.Progress.PLANNED)) {
				TerminateMachineTask task = new TerminateMachineTask();
				task.setImpersonatedTarget(agentId);
				task.setTarget(machineProvisionerId);
				addNewTaskIfNotExists(newTasks, task);
			}
			else if (agentProgress.equals(AgentState.Progress.MACHINE_TERMINATED)) {
				//do nothing
			}
			else {
				Preconditions.checkState(false, "Unknwon agent progress " + agentProgress);
			}
		}
		
		return Iterables.unmodifiableIterable(healthyAgents);
	}

	private Iterable<URI> getNonStoppedInstances(Iterable<URI> serviceInstanceIds) {
		
		return Iterables.filter(serviceInstanceIds, new Predicate<URI>(){

			@Override
			public boolean apply(URI instanceId) {
				ServiceInstanceState instanceState = getServiceInstanceState(instanceId);
				final String instanceProgress = instanceState.getProgress();
				Preconditions.checkNotNull(instanceProgress);
				return !instanceProgress.equals(ServiceInstanceState.Progress.INSTANCE_STOPPED);
			}
		});
	}

	private AgentPingHealth getAgentPingHealth(URI agentId, long nowTimestamp) {
		
		AgentPingHealth health = AgentPingHealth.UNDETERMINED;
		
		// look for ping that should have been consumed by now --> AGENT_NOT_RESPONDING
		Iterable<URI> pendingTasks = ServiceUtils.getPendingTasks(stateReader, taskReader, agentId);
		for (final URI nextTaskToConsume : pendingTasks) {
			final Task task = taskReader.getElement(nextTaskToConsume, Task.class);
			Preconditions.checkState(task.getSource().equals(orchestratorId), "All agent tasks are assumed to be from this orchestrator");
			if (task instanceof PingAgentTask) {
				PingAgentTask pingAgentTask = (PingAgentTask) task;
				AgentState agentState = getAgentState(agentId);
				Integer expectedNumberOfAgentRestartsInAgentState = pingAgentTask.getExpectedNumberOfAgentRestartsInAgentState();
				Integer expectedNumberOfMachineRestartsInAgentState = pingAgentTask.getExpectedNumberOfMachineRestartsInAgentState();
				if (expectedNumberOfAgentRestartsInAgentState == null && agentState != null) {
					Preconditions.checkState(expectedNumberOfMachineRestartsInAgentState == null);
					if (agentState.getProgress().equals(AgentState.Progress.AGENT_STARTED)) {
						// agent started after ping sent. Wait for next ping
					}
					else {
						// agent not responding because it was not started yet
						health = AgentPingHealth.AGENT_NOT_RESPONDING;
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
					final long taskTimestamp = task.getSourceTimestamp();
					final long notRespondingMilliseconds = nowTimestamp - taskTimestamp;
					if ( notRespondingMilliseconds > NOT_RESPONDING_DETECTION_MILLISECONDS ) {
						// ping should have been consumed by now
						health = AgentPingHealth.AGENT_NOT_RESPONDING;
					}
				}
			}
		}
		
		if (health == AgentPingHealth.UNDETERMINED) {
			// look for ping that was consumed just recently --> AGENT_RESPONDING
			AgentState agentState = getAgentState(agentId);
			if (agentState != null) {
				List<URI> completedTasks = agentState.getCompletedTasks();
				
				for (URI completedTaskId : completedTasks) {
					Task task = taskReader.getElement(completedTaskId, Task.class);
					if (task instanceof PingAgentTask) {
						PingAgentTask pingAgentTask = (PingAgentTask) task;
						Integer expectedNumberOfRestartsInAgentState = pingAgentTask.getExpectedNumberOfAgentRestartsInAgentState();
						if (expectedNumberOfRestartsInAgentState != null && 
							expectedNumberOfRestartsInAgentState == agentState.getNumberOfAgentRestarts()) {
							final long taskTimestamp = task.getSourceTimestamp();
							final long respondingMilliseconds = nowTimestamp - taskTimestamp;
							if ( respondingMilliseconds <= NOT_RESPONDING_DETECTION_MILLISECONDS ) {
								// ping was consumed just recently
								health = AgentPingHealth.AGENT_RESPONDING;
								break;
							}
						}
					}
				}
			}
		}
		
		return health;
	}
	public enum AgentPingHealth {
		UNDETERMINED, AGENT_NOT_RESPONDING, AGENT_RESPONDING
	}

	/**
	 * Adds a new task only if it has not been added recently.
	 */
	
	private void addNewTaskIfNotExists(
			final List<Task> newTasks,
			final Task newTask) {
		
		if (getExistingTaskId(newTask) == null) {
			addNewTask(newTasks, newTask);
		}
	}

	private URI getExistingTaskId(final Task newTask) {
		final URI agentId = newTask.getTarget();
		final URI existingTaskId = 
			Iterables.find(getExecutingAndPendingTasks(agentId),
				new Predicate<URI>() {
					@Override
					public boolean apply(final URI existingTaskId) {
						final Task existingTask = taskReader.getElement(existingTaskId, Task.class);
						Preconditions.checkArgument(agentId.equals(existingTask.getTarget()),"Expected target " + agentId + " actual target " + existingTask.getTarget());
						return tasksEqualsIgnoreTimestampIgnoreSource(existingTask,newTask);
				}},
				null
			);
		return existingTaskId;
	}
	
	private Iterable<URI> getExecutingAndPendingTasks(URI agentId) {
		return ServiceUtils.getExecutingAndPendingTasks(stateReader, taskReader, agentId);
	}

	private boolean tasksEqualsIgnoreTimestampIgnoreSource(final Task task1, final Task task2) {
		if (!task1.getClass().equals(task2.getClass())) {
			return false;
		}
		final Task task1Clone = StreamUtils.cloneElement(mapper, task1);
		final Task task2Clone = StreamUtils.cloneElement(mapper, task2);
		task1Clone.setSourceTimestamp(null);
		task2Clone.setSourceTimestamp(null);
		task1Clone.setSource(null);
		task2Clone.setSource(null);
		return StreamUtils.elementEquals(mapper, task1Clone, task2Clone);
	
	}

	private static void addNewTask(List<Task> newTasks, final Task task) {
		newTasks.add(task);
	}

	private AgentState getAgentState(URI agentId) {
		return StreamUtils.getLastElement(stateReader, agentId, AgentState.class);
	}

	@TaskConsumerStateHolder
	public ServiceGridOrchestratorState getState() {
		return state;
	}

	private ServiceState getServiceState(URI serviceId) {
		ServiceState serviceState = StreamUtils.getLastElement(stateReader, serviceId, ServiceState.class);
		return serviceState;
	}


	/**
	 * @return old ids that are not in the newIds, maintaining order 
	 */
	private Iterable<URI> difference(final Iterable<URI> oldIds, final Iterable<URI> newIds) {
		final Set<URI> idsToFilter = Sets.newHashSet(newIds);
		final Iterable<URI> diffWithDuplicates =
			Iterables.filter(oldIds, new Predicate<URI>(){
	
				@Override
				public boolean apply(URI id) {
					return !idsToFilter.contains(id);
				}
			});
		return ImmutableSet.copyOf(diffWithDuplicates);
	}
}
