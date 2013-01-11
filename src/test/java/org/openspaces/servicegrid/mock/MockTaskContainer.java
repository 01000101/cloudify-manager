package org.openspaces.servicegrid.mock;

import java.net.URI;

import junit.framework.Assert;

import org.openspaces.servicegrid.ImpersonatingTaskExecutor;
import org.openspaces.servicegrid.Task;
import org.openspaces.servicegrid.TaskExecutor;
import org.openspaces.servicegrid.TaskExecutorState;
import org.openspaces.servicegrid.TaskExecutorStateModifier;
import org.openspaces.servicegrid.streams.StreamConsumer;
import org.openspaces.servicegrid.streams.StreamProducer;

import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;

public class MockTaskContainer {

	private final StreamProducer<TaskExecutorState> stateWriter;
	private final Object taskExecutor;
	private final URI executorId;
	private final StreamConsumer<Task> taskConsumer;
	private final StreamConsumer<TaskExecutorState> stateReader;
	private boolean killed;
	
	public MockTaskContainer(MockTaskContainerParameter parameterObject) {
		this.executorId = parameterObject.getExecutorId();
		this.stateReader = parameterObject.getStateReader();
		this.stateWriter = parameterObject.getStateWriter();
		this.taskConsumer = parameterObject.getTaskConsumer();
		this.taskExecutor = parameterObject.getTaskExecutor();
		this.killed = false;
	}

	private void afterExecute(URI taskId, Task task) {

		final TaskExecutorState state = getTaskExecutorState();
		state.completeExecutingTask(taskId);
		stateWriter.addElement(getExecutorId(), state);
	}

	

	private TaskExecutorState getTaskExecutorState() {
		if (taskExecutor instanceof TaskExecutor<?>) {
			return ((TaskExecutor<?>) taskExecutor).getState();
		}
		else if (taskExecutor instanceof ImpersonatingTaskExecutor<?>) {
			return ((ImpersonatingTaskExecutor<?>) taskExecutor).getState();
		}
		else {
			throw new IllegalStateException("taskExecutor illegal type - " + taskExecutor);
		}
	}

	private void beforeExecute(Task task) {
		Preconditions.checkNotNull(task.getTarget());
		Preconditions.checkArgument(
				task.getTarget().equals(getExecutorId()),
				"Expected task target is %s instead found %s", getExecutorId() , task.getTarget());
		
		final TaskExecutorState state = getTaskExecutorState();
		stateWriter.addElement(getExecutorId(), state);
	}

	/**
	 * @return true - if need to be called again
	 */
	public boolean stepTaskExecutor() {
		
		boolean needAnotherStep = false;
		if (!killed) {
			
			URI taskId = getNextTaskId();
			
			if (taskId != null) {
				final Task task = taskConsumer.getElement(taskId, Task.class);
				getTaskExecutorState().executeTask(taskId);
				beforeExecute(task);
				execute(task);
				afterExecute(taskId, task);
			}
			
			URI nextTaskId = getNextTaskId();
			needAnotherStep = (nextTaskId != null);
		}
		return needAnotherStep;
	}

	private URI getNextTaskId() {
		return getNextTaskId(getTaskExecutorState());
	}

	private URI getNextTaskId(final TaskExecutorState state) {
		Preconditions.checkNotNull(state);
		final URI lastTaskId = getLastTaskIdOrNull(state);
		URI taskId;
		if (lastTaskId == null) {
			taskId = taskConsumer.getFirstElementId(executorId);
		}
		else {
			taskId = taskConsumer.getNextElementId(lastTaskId);
		}
		return taskId;
	}

	private URI getLastTaskIdOrNull(final TaskExecutorState state) {
		return Iterables.getLast(Iterables.concat(state.getCompletedTasks(),state.getExecutingTasks()), null);
	}

	private void execute(final Task task) {
		if (task.getImpersonatedTarget() != null) {
			Preconditions.checkArgument(
					taskExecutor instanceof ImpersonatingTaskExecutor, 
					getExecutorId() + " cannot handle task, since it requires impersonation");
			final ImpersonatingTaskExecutor<?> impersonatingTaskExecutor = (ImpersonatingTaskExecutor<?>) taskExecutor;
			final TaskExecutorStateModifier impersonatedStateModifier = new TaskExecutorStateModifier() {
				
				@Override
				public void updateState(final TaskExecutorState impersonatedState) {
					URI impersonatedTargetId = task.getImpersonatedTarget();
					Preconditions.checkNotNull(impersonatedTargetId);
					Assert.assertEquals(impersonatedTargetId.getHost(), "localhost");
					stateWriter.addElement(impersonatedTargetId, impersonatedState);
				}

				@Override
				public TaskExecutorState getState() {
					URI impersonatedTargetId = task.getImpersonatedTarget();
					Preconditions.checkNotNull(impersonatedTargetId);
					Assert.assertEquals(impersonatedTargetId.getHost(), "localhost");
					URI lastElementId = stateReader.getLastElementId(impersonatedTargetId);
					if (lastElementId != null) {
						return stateReader.getElement(lastElementId, TaskExecutorState.class);
					}
					return null;
				}

			};
			impersonatingTaskExecutor.execute(task, impersonatedStateModifier);
		}
		else {
			((TaskExecutor<?>) taskExecutor).execute(task);
		}
	}
	
	public URI getExecutorId() {
		return executorId;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result
				+ ((executorId == null) ? 0 : executorId.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		MockTaskContainer other = (MockTaskContainer) obj;
		if (executorId == null) {
			if (other.executorId != null)
				return false;
		} else if (!executorId.equals(other.executorId))
			return false;
		return true;
	}
	
	@Override
	public String toString() {
		return taskExecutor.toString();
	}

	public void kill() {
		this.killed = true;
	}
}