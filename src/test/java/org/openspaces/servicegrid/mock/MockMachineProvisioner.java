package org.openspaces.servicegrid.mock;

import java.net.URI;

import org.openspaces.servicegrid.ImpersonatingTaskConsumer;
import org.openspaces.servicegrid.TaskConsumerState;
import org.openspaces.servicegrid.TaskConsumerStateHolder;
import org.openspaces.servicegrid.TaskExecutorStateModifier;
import org.openspaces.servicegrid.agent.state.AgentState;
import org.openspaces.servicegrid.agent.tasks.StartAgentTask;
import org.openspaces.servicegrid.agent.tasks.StartMachineTask;
import org.openspaces.servicegrid.agent.tasks.TerminateMachineOfNonResponsiveAgentTask;

import com.google.common.base.Preconditions;

public class MockMachineProvisioner {

	private final TaskConsumerState state = new TaskConsumerState();
	private final TaskConsumerRegistrar taskConsumerRegistrar;
	
	public MockMachineProvisioner(TaskConsumerRegistrar taskConsumerRegistrar) {
		this.taskConsumerRegistrar = taskConsumerRegistrar;
	}
	
	@ImpersonatingTaskConsumer
	public void startMachine(StartMachineTask task,
			TaskExecutorStateModifier impersonatedStateModifier) {
	
		//Simulate starting machine
		final AgentState impersonatedState = impersonatedStateModifier.getState();
		Preconditions.checkState(impersonatedState.getProgress().equals(AgentState.Progress.PLANNED));
		impersonatedState.setProgress(AgentState.Progress.STARTING_MACHINE);
		impersonatedStateModifier.updateState(impersonatedState);
		//Immediately machine start 
		impersonatedStateModifier.getState();
		impersonatedState.setProgress(AgentState.Progress.MACHINE_STARTED);
		impersonatedStateModifier.updateState(impersonatedState);
	}

	@ImpersonatingTaskConsumer
	public void terminateMachineOfNonResponsiveAgent(TerminateMachineOfNonResponsiveAgentTask task, TaskExecutorStateModifier impersonatedStateModifier) {
		final AgentState impersonatedState = impersonatedStateModifier.getState();
		Preconditions.checkState(impersonatedState.getProgress().equals(AgentState.Progress.AGENT_STARTED));
		final URI agentId = task.getImpersonatedTarget();
		taskConsumerRegistrar.unregisterTaskConsumer(agentId);
		impersonatedState.setProgress(AgentState.Progress.MACHINE_TERMINATED);
		impersonatedStateModifier.updateState(impersonatedState);
	}
	
	@ImpersonatingTaskConsumer
	public void startAgent(StartAgentTask task,
			TaskExecutorStateModifier impersonatedStateModifier) {

		final AgentState agentState = impersonatedStateModifier.getState();
		Preconditions.checkNotNull(agentState);
		Preconditions.checkState(agentState.getProgress().equals(AgentState.Progress.MACHINE_STARTED));
		final URI agentId = task.getImpersonatedTarget();
		Preconditions.checkState(agentId.toString().endsWith("/"));
		agentState.setProgress(AgentState.Progress.AGENT_STARTED);
		taskConsumerRegistrar.registerTaskConsumer(new MockAgent(agentState), agentId);
		impersonatedStateModifier.updateState(agentState);
	}

	@TaskConsumerStateHolder
	public TaskConsumerState getState() {
		return state;
	}

}
