package org.openspaces.servicegrid.mock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.util.Map;
import java.util.Set;

import junit.framework.Assert;

import org.openspaces.servicegrid.ImpersonatingTask;
import org.openspaces.servicegrid.ImpersonatingTaskConsumer;
import org.openspaces.servicegrid.Task;
import org.openspaces.servicegrid.TaskConsumer;
import org.openspaces.servicegrid.TaskConsumerState;
import org.openspaces.servicegrid.TaskExecutorStateModifier;
import org.openspaces.servicegrid.TaskProducer;
import org.openspaces.servicegrid.TaskProducerTask;
import org.openspaces.servicegrid.streams.StreamReader;
import org.openspaces.servicegrid.streams.StreamWriter;
import org.openspaces.servicegrid.time.CurrentTimeProvider;

import com.beust.jcommander.internal.Sets;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

public class MockTaskContainer {

	private final StreamWriter<TaskConsumerState> stateWriter;
	private final Object taskConsumer;
	private final URI taskConsumerId;
	private final StreamReader<Task> taskReader;
	private final StreamWriter<Task> taskWriter;
	private final StreamWriter<Task> persistentTaskWriter;
	private final StreamReader<TaskConsumerState> stateReader;
	private boolean killed;
	private final Method getStateMethod;
	private final Map<Class<? extends ImpersonatingTask>,Method> impersonatedTaskConsumerMethodByType;
	private final Map<Class<? extends Task>,Method> taskConsumerMethodByType;
	private final Set<Method> taskConsumersToPersist;
	private final Method taskProducerMethod;
	private final CurrentTimeProvider timeProvider;
	
	public MockTaskContainer(MockTaskContainerParameter parameterObject) {
		this.taskConsumerId = parameterObject.getExecutorId();
		this.stateReader = parameterObject.getStateReader();
		this.stateWriter = parameterObject.getStateWriter();
		this.taskReader = parameterObject.getTaskReader();
		this.taskWriter = parameterObject.getTaskWriter();
		this.taskConsumer = parameterObject.getTaskConsumer();
		this.killed = false;
		this.getStateMethod = getMethodByName("getState");
		this.timeProvider = parameterObject.getTimeProvider();
		this.taskConsumerMethodByType = Maps.newHashMap();
		this.impersonatedTaskConsumerMethodByType = Maps.newHashMap();
		this.taskConsumersToPersist = Sets.newHashSet();
		this.persistentTaskWriter = parameterObject.getPersistentTaskWriter();
		
		//Reflect on @TaskProducer and @TaskConsumer methods
		Method taskProducerMethod = null;
		for (Method method : taskConsumer.getClass().getMethods()) {
			Class<?>[] parameterTypes = method.getParameterTypes();
			TaskConsumer taskConsumerAnnotation = method.getAnnotation(TaskConsumer.class);
			ImpersonatingTaskConsumer impersonatingTaskConsumerAnnotation = method.getAnnotation(ImpersonatingTaskConsumer.class);
			TaskProducer taskProducerAnnotation = method.getAnnotation(TaskProducer.class);
			if (taskConsumerAnnotation != null) {
				Preconditions.checkArgument(method.getReturnType().equals(Void.TYPE), method + " return type must be void");
				Preconditions.checkArgument(parameterTypes.length >= 1 && !ImpersonatingTask.class.isAssignableFrom(parameterTypes[0]), "execute method parameter " + parameterTypes[0] + " is an impersonating task. Use " + ImpersonatingTask.class.getSimpleName() + " annotation instead, in " + taskConsumer.getClass());
				Preconditions.checkArgument(parameterTypes.length == 1, "method must have one parameter");
				Preconditions.checkArgument(Task.class.isAssignableFrom(parameterTypes[0]), "method parameter " + parameterTypes[0] + " is not a task in " + taskConsumer.getClass());
				Class<? extends Task> taskType = (Class<? extends Task>) parameterTypes[0];
				taskConsumerMethodByType.put(taskType, method);
				
				if (taskConsumerAnnotation.persistTask()) {
					taskConsumersToPersist.add(method);
				}
			} else {
				if (impersonatingTaskConsumerAnnotation != null) {
					Preconditions.checkArgument(method.getReturnType().equals(Void.TYPE), method + " return type must be void");
					Preconditions.checkArgument(parameterTypes.length == 2, "Impersonating task executor method must have two parameters");
					Preconditions.checkArgument(ImpersonatingTask.class.isAssignableFrom(parameterTypes[0]), "method first parameter %s is not an impersonating task in %s",parameterTypes[0], taskConsumer.getClass());
					Class<? extends ImpersonatingTask> taskType = (Class<? extends ImpersonatingTask>) parameterTypes[0];
					Preconditions.checkArgument(TaskExecutorStateModifier.class.equals(parameterTypes[1]),"method second parameter type must be " + TaskExecutorStateModifier.class);
					impersonatedTaskConsumerMethodByType.put(taskType, method);
					
					if (impersonatingTaskConsumerAnnotation.persistTask()) {
						taskConsumersToPersist.add(method);
					}
				} else {
					if (taskProducerAnnotation != null) {
						Preconditions.checkArgument(Iterable.class.equals(method.getReturnType()), "%s return type must be Iterable<Task>",method);
						Preconditions.checkArgument(parameterTypes.length == 0, "%s method must not have any parameters", method);				
						Preconditions.checkArgument(taskProducerMethod == null, "%s can have at most one @" + TaskProducer.class.getSimpleName()+" method", taskConsumer.getClass());
						taskProducerMethod = method;
					}
				}
			}
		}
		this.taskProducerMethod = taskProducerMethod;
		
		//recover persisted tasks
		StreamReader<Task> persistentTaskReader = parameterObject.getPersistentTaskReader();
		for (URI recoveredTaskId = persistentTaskReader.getFirstElementId(taskConsumerId);
			 recoveredTaskId != null;
			 recoveredTaskId = persistentTaskReader.getNextElementId(recoveredTaskId)) {
			
			Task task = persistentTaskReader.getElement(recoveredTaskId, Task.class);
			Preconditions.checkNotNull(task);
			taskWriter.addElement(taskConsumerId, task);
		}
	}

	private Method getMethodByName(String methodName, Class<?> ... parameterTypes) {
		Method method;
		try {
			method = taskConsumer.getClass().getMethod(methodName,parameterTypes);
		} catch (final NoSuchMethodException e) {
			return null;
		} catch (final SecurityException e) {
			throw Throwables.propagate(e);
		}
		return method;
	}

	private void afterExecute(URI taskId, Task task) {

		final TaskConsumerState state = getTaskConsumerState();
		state.completeExecutingTask(taskId);
		stateWriter.addElement(getExecutorId(), state);
	}

	private TaskConsumerState getTaskConsumerState() {
		return (TaskConsumerState) invokeMethod(getStateMethod);
	}

	private Object invokeMethod(Method method, Object ... args) {
		try {
			return method.invoke(taskConsumer, args);
		} catch (final IllegalAccessException e) {
			throw Throwables.propagate(e);
		} catch (final IllegalArgumentException e) {
			throw Throwables.propagate(e);
		} catch (final InvocationTargetException e) {
			throw Throwables.propagate(e.getCause());
		}
	}

	private void beforeExecute(Task task) {
		Preconditions.checkNotNull(task.getTarget());
		Preconditions.checkArgument(
				task.getTarget().equals(getExecutorId()),
				"Expected task target is %s instead found %s", getExecutorId() , task.getTarget());
		
		final TaskConsumerState state = getTaskConsumerState();
		stateWriter.addElement(getExecutorId(), state);
	}

	/**
	 * @return the processed task
	 */
	public Task consumeNextTask() {
		
		Task task = null;
		if (!killed) {
			
			URI taskId;
			TaskConsumerState state = getTaskConsumerState();
			try {
				taskId = getNextTaskId(state);
			}
			catch (IndexOutOfBoundsException e) {
				// remote state had failover 
				state.getCompletedTasks().clear();
				Preconditions.checkState(state.getExecutingTasks().isEmpty());
				stateWriter.addElement(getExecutorId(), state);
				taskId = getNextTaskId(state);
			}
			
			if (taskId != null) {
				task = taskReader.getElement(taskId, Task.class);
				state.executeTask(taskId);
				beforeExecute(task);
				execute(task);
				afterExecute(taskId, task);
			}
		}
		return task;
	}

	private URI getNextTaskId(final TaskConsumerState state) {
		Preconditions.checkNotNull(state);
		final URI lastTaskId = getLastTaskIdOrNull(state);
		URI taskId;
		if (lastTaskId == null) {
			taskId = taskReader.getFirstElementId(taskConsumerId);
		}
		else {
			taskId = taskReader.getNextElementId(lastTaskId);
		}
		return taskId;
	}

	private URI getLastTaskIdOrNull(final TaskConsumerState state) {
		return Iterables.getLast(Iterables.concat(state.getCompletedTasks(),state.getExecutingTasks()), null);
	}

	private void submitTasks(long nowTimestamp,
			Iterable<? extends Task> newTasks) {
		for (final Task newTask : newTasks) {
			newTask.setSource(taskConsumerId);
			newTask.setSourceTimestamp(nowTimestamp);
			Preconditions.checkNotNull(newTask.getTarget());
			taskWriter.addElement(newTask.getTarget(), newTask);
		}
	}

	private void execute(final Task task) {
		if (task instanceof TaskProducerTask) {
			Preconditions.checkArgument(
					taskProducerMethod != null, 
					"%s cannot consume task %s", taskConsumer.getClass(), task);
			
			TaskProducerTask taskProducerTask = (TaskProducerTask) task;
			long nowTimestamp = timeProvider.currentTimeMillis();
			for (int i = 0 ; i < taskProducerTask.getMaxNumberOfSteps(); i++) {
				final Iterable<? extends Task> newTasks = (Iterable<? extends Task>) invokeMethod(taskProducerMethod);
				if (Iterables.isEmpty(newTasks)) {
					break;
				}
				submitTasks(nowTimestamp, newTasks);
			}		
		}
		else if (!(task instanceof ImpersonatingTask)) {
			Method executorMethod = taskConsumerMethodByType.get(task.getClass());
			Preconditions.checkArgument(
					executorMethod != null, 
					"%s cannot consume task %s", taskConsumer.getClass(), task.getClass());
			invokeMethod(executorMethod, task);
			if (taskConsumersToPersist.contains(executorMethod)) {
				persistentTaskWriter.addElement(taskConsumerId, task);
			}
		} else {
			Method executorMethod = impersonatedTaskConsumerMethodByType.get(task.getClass());
			Preconditions.checkArgument(
					executorMethod != null, 
					"%s cannot consume impersonating task %s", taskConsumer.getClass(), task.getClass());
			final TaskExecutorStateModifier impersonatedStateModifier = new TaskExecutorStateModifier() {
				
				@Override
				public void updateState(final TaskConsumerState impersonatedState) {
					URI impersonatedTargetId = ((ImpersonatingTask)task).getImpersonatedTarget();
					Preconditions.checkNotNull(impersonatedTargetId);
					Assert.assertEquals(impersonatedTargetId.getHost(), "localhost");
					stateWriter.addElement(impersonatedTargetId, impersonatedState);
				}

				@Override
				public TaskConsumerState getState() {
					URI impersonatedTargetId = ((ImpersonatingTask)task).getImpersonatedTarget();
					Preconditions.checkNotNull(impersonatedTargetId);
					Assert.assertEquals(impersonatedTargetId.getHost(), "localhost");
					URI lastElementId = stateReader.getLastElementId(impersonatedTargetId);
					if (lastElementId != null) {
						return stateReader.getElement(lastElementId, TaskConsumerState.class);
					}
					return null;
				}

			};
			invokeMethod(executorMethod, task, impersonatedStateModifier);
			if (taskConsumersToPersist.contains(executorMethod)) {
				persistentTaskWriter.addElement(taskConsumerId, task);
			}
		}
	}
	
	public URI getExecutorId() {
		return taskConsumerId;
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result
				+ ((taskConsumerId == null) ? 0 : taskConsumerId.hashCode());
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
		if (taskConsumerId == null) {
			if (other.taskConsumerId != null)
				return false;
		} else if (!taskConsumerId.equals(other.taskConsumerId))
			return false;
		return true;
	}
	
	@Override
	public String toString() {
		return taskConsumer.toString();
	}

	public void kill() {
		this.killed = true;
	}
}