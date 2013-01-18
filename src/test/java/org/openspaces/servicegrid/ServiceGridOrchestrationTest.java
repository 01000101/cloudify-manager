package org.openspaces.servicegrid;

import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.openspaces.servicegrid.agent.state.AgentState;
import org.openspaces.servicegrid.agent.tasks.PingAgentTask;
import org.openspaces.servicegrid.mock.MockAgent;
import org.openspaces.servicegrid.mock.MockManagement;
import org.openspaces.servicegrid.mock.MockStreams;
import org.openspaces.servicegrid.mock.MockTaskContainer;
import org.openspaces.servicegrid.mock.MockTaskContainerParameter;
import org.openspaces.servicegrid.mock.TaskConsumerRegistrar;
import org.openspaces.servicegrid.service.state.ServiceConfig;
import org.openspaces.servicegrid.service.state.ServiceGridDeploymentPlan;
import org.openspaces.servicegrid.service.state.ServiceGridPlannerState;
import org.openspaces.servicegrid.service.state.ServiceInstanceState;
import org.openspaces.servicegrid.service.state.ServiceState;
import org.openspaces.servicegrid.service.tasks.InstallServiceTask;
import org.openspaces.servicegrid.service.tasks.ScaleOutServiceTask;
import org.openspaces.servicegrid.streams.StreamReader;
import org.openspaces.servicegrid.streams.StreamUtils;
import org.openspaces.servicegrid.time.MockCurrentTimeProvider;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.testng.log.TextFormatter;

import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;

public class ServiceGridOrchestrationTest {
	
	private final Logger logger;
	private MockManagement management;
	private Set<MockTaskContainer> containers;
	private MockCurrentTimeProvider timeProvider;
	private TaskConsumerRegistrar taskConsumerRegistrar;
	
	public ServiceGridOrchestrationTest() {
		logger = Logger.getLogger(this.getClass().getName());
		setSimpleLoggerFormatter(logger);
	}

	@BeforeMethod
	public void before(Method method) {
		
		timeProvider = new MockCurrentTimeProvider();
		containers = Sets.newCopyOnWriteArraySet();
		taskConsumerRegistrar = new TaskConsumerRegistrar() {
			
			@Override
			public void registerTaskConsumer(
					final Object taskConsumer, final URI taskConsumerId) {
				
				MockTaskContainer container = newContainer(taskConsumerId, taskConsumer);
				addContainer(container);
			}

			@Override
			public Object unregisterTaskConsumer(final URI taskConsumerId) {
				MockTaskContainer mockTaskContainer = findContainer(taskConsumerId);
				boolean removed = containers.remove(mockTaskContainer);
				Preconditions.checkState(removed, "Failed to remove container " + taskConsumerId);
				return mockTaskContainer.getTaskConsumer();
			}

		};
		
		management = new MockManagement(taskConsumerRegistrar, timeProvider);
		management.registerTaskConsumers();
		logger.info("Starting " + method.getName());
	}
	
	@AfterMethod
	public void after() {
		logAllTasks();
	}
	
	/**
	 * Tests deployment of 1 instance
	 */
	@Test
	public void installSingleInstanceServiceTest() {
		installService("tomcat", 1);
		execute();
		assertSingleServiceInstance();
	}

	/**
	 * Tests deployment of 2 instances
	 */
	@Test
	public void installMultipleInstanceServiceTest() {
		installService("tomcat", 2);
		execute();
		assertTwoTomcatInstances();
	}
	
	
	/**
	 * Tests machine failover, and restart by the orchestrator
	 */
	@Test
	public void machineFailoverTest() {
		installService("tomcat", 1);
		execute();
		killOnlyMachine();
		execute();
		final int numberOfAgentRestarts = 0;
		final int numberOfMachineRestarts = 1;
		assertSingleServiceInstance("tomcat", numberOfAgentRestarts, numberOfMachineRestarts);
	}
	
	/**
	 * Test agent process failed, and restarted automatically by 
	 * reliable watchdog running on the same machine
	 */
	@Test
	public void agentRestartTest() {
		installService("tomcat", 1);
		execute();
		restartOnlyAgent();
		execute();
		final int numberOfAgentRestarts = 1;
		final int numberOfMachineRestarts = 0;
		assertSingleServiceInstance("tomcat", numberOfAgentRestarts,numberOfMachineRestarts);
	}
	
	/**
	 * Tests change in plan from 1 instance to 2 instances
	 */
	@Test
	public void scaleOutServiceTest() {
		installService("tomcat", 1);
		execute();
		scaleOutService("tomcat",2);
		execute();
		assertTwoTomcatInstances();
	}
	
	/**
	 * Tests management state recovery from crash
	 */
	@Test
	public void managementRestartTest() {
		installService("tomcat", 1);
		execute();
		logAllTasks();
		restartManagement();
		execute();
		assertSingleServiceInstance();
	}
	
	/**
	 * Tests management state recovery from crash when one of the agents also failed.
	 * This test is similar to scaleOut test. Since there is one agent, and the plan is two agents.
	 */
	@Test
	public void managementRestartAndOneAgentRestartTest() {
		installService("tomcat", 2);
		execute();
		logAllTasks();
		restartAgent(newURI("http://localhost/agent/1/"));
		restartManagement();
		execute();
		 
		assertTwoTomcatInstances(expectedAgentZeroNotRestartedAgentOneRestarted(), expectedBothMachinesNotRestarted());
	}

	private void assertSingleServiceInstance() {
		final int numberOfAgentRestarts = 0;
		final int numberOfMachineRestarts = 0;
		assertSingleServiceInstance("tomcat", numberOfAgentRestarts,numberOfMachineRestarts);
	}
	
	private void assertSingleServiceInstance(String serviceName, int numberOfAgentRestarts, int numberOfMachineRestarts) {
		assertServiceInstalledWithOneInstance(serviceName, numberOfAgentRestarts, numberOfMachineRestarts);
		
		Assert.assertEquals(getDeploymentPlannerState().getDeploymentPlan().getServices().size(), 1);
		Assert.assertEquals(Iterables.size(getAgentIds()), 1);
	}

	private void assertServiceInstalledWithOneInstance(
			String serviceName, int numberOfAgentRestarts, int numberOfMachineRestarts) {
		final URI serviceId = getServiceId(serviceName);
		final ServiceState serviceState = getServiceState(serviceId);
		final URI instanceId = Iterables.getOnlyElement(serviceState.getInstanceIds());
		final ServiceInstanceState instanceState = getServiceInstanceState(instanceId);
		Assert.assertEquals(getOnlyServiceInstanceId(serviceName), instanceId);
		final URI agentId = instanceState.getAgentId();
		Assert.assertEquals(instanceState.getServiceId(), serviceId);
		Assert.assertEquals(instanceState.getProgress(), ServiceInstanceState.Progress.INSTANCE_STARTED);
		
		final AgentState agentState = getAgentState(agentId);
		Assert.assertEquals(Iterables.getOnlyElement(agentState.getServiceInstanceIds()),instanceId);
		Assert.assertEquals(agentState.getProgress(), AgentState.Progress.AGENT_STARTED);
		Assert.assertEquals(agentState.getNumberOfAgentRestarts(), numberOfAgentRestarts);
		Assert.assertEquals(agentState.getNumberOfMachineRestarts(), numberOfMachineRestarts);
		
		final ServiceGridPlannerState plannerState = getDeploymentPlannerState();
		final ServiceGridDeploymentPlan deploymentPlan = plannerState.getDeploymentPlan();
		Assert.assertEquals(Iterables.getOnlyElement(deploymentPlan.getInstanceIdsByAgentId().get(agentId)), instanceId);
		Assert.assertEquals(Iterables.getOnlyElement(deploymentPlan.getInstanceIdsByServiceId().get(serviceId)), instanceId);
		final ServiceConfig serviceConfig = deploymentPlan.getServiceById(serviceId); 
		Assert.assertEquals(serviceConfig.getServiceId(), serviceId);
	}

	private ServiceGridPlannerState getDeploymentPlannerState() {
		return StreamUtils.getLastElement(getStateReader(), management.getDeploymentPlannerId(), ServiceGridPlannerState.class);
	}

	private URI getOnlyAgentId() {
		return Iterables.getOnlyElement(getAgentIds());
	}
	
	private void assertTwoTomcatInstances() {
		
		assertTwoTomcatInstances(expectedBothAgentsNotRestarted(), expectedBothMachinesNotRestarted());
	}
	
	private void assertTwoTomcatInstances(Map<URI,Integer> numberOfAgentRestartsPerAgent, Map<URI,Integer> numberOfMachineRestartsPerAgent) {
		final URI serviceId = getServiceId("tomcat");
		final ServiceState serviceState = getServiceState(serviceId);
		Assert.assertEquals(Iterables.size(serviceState.getInstanceIds()),2);
		//logger.info("URIs: " + state.getElementIdsStartingWith(new URI("http://localhost/")));
		Iterable<URI> instanceIds = getStateIdsStartingWith(newURI("http://localhost/services/tomcat/instances/"));
		Assert.assertEquals(Iterables.size(instanceIds),2);
		
		final ServiceGridPlannerState plannerState = getDeploymentPlannerState();
		final ServiceGridDeploymentPlan deploymentPlan = plannerState.getDeploymentPlan();
		Assert.assertEquals(Iterables.getOnlyElement(deploymentPlan.getServices()).getServiceId(), serviceId);
		Assert.assertEquals(Iterables.size(deploymentPlan.getInstanceIdsByServiceId().get(serviceId)), 2);
		
		Iterable<URI> agentIds = getAgentIds();
		int numberOfAgents = Iterables.size(agentIds);
		Assert.assertEquals(numberOfAgents, 2);
		for (int i = 0 ; i < numberOfAgents; i++) {
			
			URI agentId = Iterables.get(agentIds, i);
			AgentState agentState = getAgentState(agentId);
			Assert.assertEquals(agentState.getProgress(), AgentState.Progress.AGENT_STARTED);
			Assert.assertEquals(agentState.getNumberOfAgentRestarts(), (int) numberOfAgentRestartsPerAgent.get(agentId));
			Assert.assertEquals(agentState.getNumberOfMachineRestarts(), (int) numberOfMachineRestartsPerAgent.get(agentId));
			URI instanceId = Iterables.getOnlyElement(agentState.getServiceInstanceIds());
			Assert.assertTrue(Iterables.contains(instanceIds, instanceId));
			ServiceInstanceState instanceState = StreamUtils.getLastElement(getStateReader(), instanceId, ServiceInstanceState.class);
			Assert.assertEquals(instanceState.getServiceId(), serviceId);
			Assert.assertEquals(instanceState.getAgentId(), agentId);
			Assert.assertEquals(instanceState.getProgress(), ServiceInstanceState.Progress.INSTANCE_STARTED);
			Assert.assertEquals(Iterables.getOnlyElement(deploymentPlan.getInstanceIdsByAgentId().get(agentId)), instanceId);
		}
		
	}

	private ServiceState getServiceState(final URI serviceId) {
		ServiceState serviceState = StreamUtils.getLastElement(getStateReader(), serviceId, ServiceState.class);
		Assert.assertNotNull(serviceState, "No state for " + serviceId);
		return serviceState;
	}
		
	@Test
	public void installTwoSingleInstanceServicesTest(){
		installService("tomcat", 1);
		installService("cassandra", 1);
		execute();
		int numberOfMachineRestarts = 0;
		int numberOfAgentRestarts = 0;
		assertServiceInstalledWithOneInstance("tomcat", numberOfAgentRestarts, numberOfMachineRestarts);
		assertServiceInstalledWithOneInstance("cassandra", numberOfAgentRestarts, numberOfMachineRestarts);
	}
	
//	public void uninstallSingleInstanceServiceTest(){
//		installService(1);
//		execute();
//		
//	}

	private AgentState getAgentState(URI agentId) {
		return getLastState(agentId, AgentState.class);
	}
	
	private ServiceInstanceState getServiceInstanceState(URI instanceId) {
		return getLastState(instanceId, ServiceInstanceState.class);
	}
	
	private <T extends TaskConsumerState> T getLastState(URI executorId, Class<T> stateClass) {
		T lastState = StreamUtils.getLastElement(getStateReader(), executorId, stateClass);
		Assert.assertNotNull(lastState);
		return lastState;
	}

	private void installService(String name, int numberOfInstances) {
		ServiceConfig serviceConfig = new ServiceConfig();
		serviceConfig.setDisplayName(name);
		serviceConfig.setPlannedNumberOfInstances(numberOfInstances);
		serviceConfig.setServiceId(getServiceId(name));
		final InstallServiceTask installServiceTask = new InstallServiceTask();
		installServiceTask.setServiceConfig(serviceConfig);
		submitTask(management.getDeploymentPlannerId(), installServiceTask);
	}

	private void submitTask(final URI target, final Task installServiceTask) {
		installServiceTask.setSourceTimestamp(timeProvider.currentTimeMillis());
		Preconditions.checkNotNull(target);
		Preconditions.checkNotNull(installServiceTask);
		installServiceTask.setTarget(target);
		((MockStreams<Task>)management.getTaskReader()).addElement(target, installServiceTask);
	}

	private void scaleOutService(String serviceName, int plannedNumberOfInstances) {
		final ScaleOutServiceTask scaleOutServiceTask = new ScaleOutServiceTask();
		URI serviceId = getServiceId(serviceName);
		scaleOutServiceTask.setServiceId(serviceId);
		scaleOutServiceTask.setPlannedNumberOfInstances(plannedNumberOfInstances);
		scaleOutServiceTask.setSourceTimestamp(timeProvider.currentTimeMillis());
		submitTask(management.getDeploymentPlannerId(), scaleOutServiceTask);
	}

	
	private URI getServiceId(String name) {
		return newURI("http://localhost/services/" + name + "/");
	}
	
	private void execute() {
		
		int consecutiveEmptyCycles = 0;
		for (; timeProvider.currentTimeMillis() < 1000000; timeProvider.increaseBy(1000)) {

			boolean emptyCycle = true;
			{
			TaskProducerTask producerTask = new TaskProducerTask();
			producerTask.setMaxNumberOfSteps(100);
			submitTask(management.getDeploymentPlannerId(), producerTask);
			}
			{
			TaskProducerTask producerTask = new TaskProducerTask();
			producerTask.setMaxNumberOfSteps(100);
			submitTask(management.getOrchestratorId(), producerTask);
			}
			
			for (MockTaskContainer container : containers) {
				Assert.assertEquals(container.getTaskConsumerId().getHost(),"localhost");
				Task task = null;
				while ((task = container.consumeNextTask()) != null) {
					if (!(task instanceof TaskProducerTask) && !(task instanceof PingAgentTask)) {
						emptyCycle = false;
					}
				}
			}

			if (emptyCycle) {
				consecutiveEmptyCycles++;
			}
			else {
				consecutiveEmptyCycles = 0;
			}

			if (consecutiveEmptyCycles > 60) {
				return;
			}
		}
		StringBuilder sb = new StringBuilder();
		Iterable<URI> servicesIds;
		try {
			servicesIds = getStateIdsStartingWith(new URI("http://services/"));
		} catch (URISyntaxException e) {
			throw Throwables.propagate(e);
		}
		for (URI serviceId : servicesIds) {
			ServiceState serviceState = getServiceState(serviceId);
			sb.append("service: " + serviceState.getServiceConfig().getDisplayName());
			sb.append(" - ");
			for (URI instanceURI : serviceState.getInstanceIds()) {
				ServiceInstanceState instanceState = StreamUtils.getLastElement(getStateReader(), instanceURI, ServiceInstanceState.class);
				sb.append(instanceURI).append("[").append(instanceState.getProgress()).append("] ");
			}
			
		}
		
		Assert.fail("Executing too many cycles progress=" + sb);
	}
	
	private URI getOnlyServiceInstanceId(String serviceName) {
		final Iterable<URI> instanceIds = getStateIdsStartingWith(newURI("http://localhost/services/"+serviceName+"/instances/"));
		Assert.assertEquals(Iterables.size(instanceIds),1);
		
		final URI serviceInstanceId = Iterables.getOnlyElement(instanceIds);
		return serviceInstanceId;
	}

	private Iterable<URI> getStateIdsStartingWith(URI uri) {
		final Iterable<URI> instanceIds = ((MockStreams<TaskConsumerState>)getStateReader()).getElementIdsStartingWith(uri);
		return instanceIds;
	}
	
	private Iterable<URI> getTaskIdsStartingWith(URI uri) {
		final Iterable<URI> instanceIds = ((MockStreams<Task>)management.getTaskReader()).getElementIdsStartingWith(uri);
		return instanceIds;
	}

	private Iterable<URI> getAgentIds() {
		return getStateIdsStartingWith(newURI("http://localhost/agent/"));
	}

	private void killOnlyMachine() {
		killMachine(getOnlyAgentId());
	}
	
	private void restartOnlyAgent() {
		restartAgent(getOnlyAgentId());
	}
	
	/**
	 * This method simulates failure of the agent, and immediate restart by a reliable watchdog
	 * running on the same machine
	 */
	private void restartAgent(URI agentId) {
		
		MockAgent agent = (MockAgent) taskConsumerRegistrar.unregisterTaskConsumer(agentId);
		AgentState agentState = agent.getState();
		Preconditions.checkState(agentState.getProgress().equals(AgentState.Progress.AGENT_STARTED));
		agentState.setNumberOfAgentRestarts(agentState.getNumberOfAgentRestarts() +1);
		taskConsumerRegistrar.registerTaskConsumer(new MockAgent(agentState), agentId);
	}

	/**
	 * This method simulates an unexpected crash of a machine 
	 */
	private void killMachine(URI agentId) {
		findContainer(agentId).killMachine();
	}
	
	/**
	 * This method simulates the crash of all management processes
	 * and their automatic start by a reliable watchdog running on the same machine
	 */
	private void restartManagement() {
		management.restart();
	}
	
	private MockTaskContainer findContainer(final URI agentId) {
		return Iterables.find(containers, new Predicate<MockTaskContainer>() {

			@Override
			public boolean apply(MockTaskContainer container) {
				return agentId.equals(container.getTaskConsumerId());
			}
		});
	}

	private Iterable<Task> getSortedTasks() {
	
		List<Task> tasks = Lists.newArrayList(); 
		final Iterable<URI> taskConsumerIds = getTaskIdsStartingWith(newURI("http://localhost/"));
		StreamReader<Task> taskReader = management.getTaskReader();
		for (final URI executorId : taskConsumerIds) {
			for (URI taskId = taskReader.getFirstElementId(executorId); taskId != null ; taskId = taskReader.getNextElementId(taskId)) {
				final Task task = taskReader.getElement(taskId, Task.class);
				tasks.add(task);
			}
		}

		Ordering<Task> ordering = new Ordering<Task>() {
			@Override
			public int compare(Task o1, Task o2) {
				int c;
				if (o1.getSourceTimestamp() == null) c = 1;
				else if (o2.getSourceTimestamp() == null) c = -1;
				else {
					c = o1.getSourceTimestamp().compareTo(o2.getSourceTimestamp());
				}
				return c;
			}
		};

		return ordering.sortedCopy(tasks);
	}
	
	private URI newURI(String uri) {
		try {
			return new URI(uri);
		} catch (URISyntaxException e) {
			throw Throwables.propagate(e);
		}
	}
	
	private void addContainer(MockTaskContainer container) {
		//logger.info("Adding container for " + container.getExecutorId());
		Preconditions.checkState(findContainserById(container.getTaskConsumerId()) == null, "Container " + container.getTaskConsumerId() + " was already added");
		containers.add(container);
	}
	
	private MockTaskContainer findContainserById(final URI id) {
		return Iterables.find(containers, new Predicate<MockTaskContainer>(){

			@Override
			public boolean apply(MockTaskContainer container) {
				return id.equals(container.getTaskConsumerId());
			}}, null);
	}

	private void logAllTasks() {
		final Iterable<Task> tasks = getSortedTasks();
		for (final Task task : tasks) {
			final DecimalFormat timestampFormatter = new DecimalFormat("###,###");
			final Long sourceTimestamp = task.getSourceTimestamp();
			String timestamp = "";
			if (sourceTimestamp != null) {
				timestamp = timestampFormatter.format(sourceTimestamp);
			}
			if (logger.isLoggable(Level.INFO)) {
				String impersonatedTarget = "";
				if (task instanceof ImpersonatingTask) {
					ImpersonatingTask impersonatingTask = (ImpersonatingTask) task;
					impersonatedTarget = "impersonated: " + impersonatingTask.getImpersonatedTarget();
				}
				logger.info(String.format("%-8s%-32starget: %-50s%-50s",timestamp,task.getClass().getSimpleName(),task.getTarget(), impersonatedTarget));
			}
		}
	}
	
	private static void setSimpleLoggerFormatter(final Logger logger) {
		Logger parentLogger = logger;
		while (parentLogger.getHandlers().length == 0) {
			parentLogger = logger.getParent();
		}
		
		parentLogger.getHandlers()[0].setFormatter(new TextFormatter());
	}
	
	private MockTaskContainer newContainer(
			URI executorId,
			Object taskConsumer) {
		MockTaskContainerParameter containerParameter = new MockTaskContainerParameter();
		containerParameter.setExecutorId(executorId);
		containerParameter.setTaskConsumer(taskConsumer);
		containerParameter.setStateReader(management.getStateReader());
		containerParameter.setStateWriter(management.getStateWriter());
		containerParameter.setTaskReader(management.getTaskReader());
		containerParameter.setTaskWriter(management.getTaskWriter());
		containerParameter.setPersistentTaskReader(management.getPersistentTaskReader());
		containerParameter.setPersistentTaskWriter(management.getPersistentTaskWriter());
		containerParameter.setTimeProvider(timeProvider);
		return new MockTaskContainer(containerParameter);
	}

	public StreamReader<TaskConsumerState> getStateReader() {
		return management.getStateReader();
	}

	private ImmutableMap<URI, Integer> expectedBothAgentsNotRestarted() {
		return ImmutableMap.<URI,Integer>builder()
				 .put(newURI("http://localhost/agent/0/"), 0)
				 .put(newURI("http://localhost/agent/1/"), 0)
				 .build();
	}
	
	private ImmutableMap<URI, Integer> expectedBothMachinesNotRestarted() {
		return ImmutableMap.<URI,Integer>builder()
				 .put(newURI("http://localhost/agent/0/"), 0)
				 .put(newURI("http://localhost/agent/1/"), 0)
				 .build();
	}

	private ImmutableMap<URI, Integer> expectedAgentZeroNotRestartedAgentOneRestarted() {
		return ImmutableMap.<URI,Integer>builder()
		 .put(newURI("http://localhost/agent/0/"), 0)
		 .put(newURI("http://localhost/agent/1/"), 1)
		 .build();
	}
}
