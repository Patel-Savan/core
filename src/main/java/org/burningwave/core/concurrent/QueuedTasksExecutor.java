/*
 * This file is part of Burningwave Core.
 *
 * Author: Roberto Gentili
 *
 * Hosted at: https://github.com/burningwave/core
 *
 * --
 *
 * The MIT License (MIT)
 *
 * Copyright (c) 2019-2021 Roberto Gentili
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without
 * limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of
 * the Software, and to permit persons to whom the Software is furnished to do so, subject to the following
 * conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO
 * EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN
 * AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE
 * OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.burningwave.core.concurrent;

import static org.burningwave.core.assembler.StaticComponentContainer.Driver;
import static org.burningwave.core.assembler.StaticComponentContainer.IterableObjectHelper;
import static org.burningwave.core.assembler.StaticComponentContainer.ManagedLoggersRepository;
import static org.burningwave.core.assembler.StaticComponentContainer.Methods;
import static org.burningwave.core.assembler.StaticComponentContainer.Objects;
import static org.burningwave.core.assembler.StaticComponentContainer.Strings;
import static org.burningwave.core.assembler.StaticComponentContainer.Synchronizer;
import static org.burningwave.core.assembler.StaticComponentContainer.ThreadHolder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.burningwave.core.Closeable;
import org.burningwave.core.ManagedLogger;
import org.burningwave.core.function.ThrowingBiPredicate;
import org.burningwave.core.function.ThrowingConsumer;
import org.burningwave.core.function.ThrowingFunction;
import org.burningwave.core.iterable.IterableObjectHelper.ResolveConfig;

@SuppressWarnings({"unchecked", "resource"})
public class QueuedTasksExecutor implements Closeable, ManagedLogger {
	private final static Map<String, TaskAbst<?,?>> runOnlyOnceTasksToBeExecuted;
	Thread.Supplier threadSupplier;
	String name;
	java.lang.Thread tasksLauncher;
	List<TaskAbst<?, ?>> tasksQueue;
	Set<TaskAbst<?, ?>> tasksInExecution;
	Boolean supended;
	volatile int defaultPriority;
	long executedTasksCount;
	volatile long executorsIndex;
	boolean isDaemon;
	Boolean terminated;
	Runnable initializer;
	boolean taskCreationTrackingEnabled;
	Object resumeCallerMutex;
	Object executingFinishedWaiterMutex;
	Object suspensionCallerMutex;
	Object executableCollectionFillerMutex;
	Object terminatingMutex;

	static {
		runOnlyOnceTasksToBeExecuted = new ConcurrentHashMap<>();
	}

	QueuedTasksExecutor(String name, Thread.Supplier threadSupplier, int defaultPriority, boolean isDaemon) {
		initializer = () -> {
			this.threadSupplier = threadSupplier;
			tasksQueue = new CopyOnWriteArrayList<>();
			tasksInExecution = ConcurrentHashMap.newKeySet();
			this.resumeCallerMutex = new Object();
			this.executingFinishedWaiterMutex = new Object();
			this.suspensionCallerMutex = new Object();
			this.executableCollectionFillerMutex = new Object();
			this.terminatingMutex = new Object();
			this.name = name;
			this.defaultPriority = defaultPriority;
			this.isDaemon = isDaemon;
			init0();
		};
		init();
	}

	void init() {
		initializer.run();
	}

	void init0() {
		supended = Boolean.FALSE;
		terminated = Boolean.FALSE;
		executedTasksCount = 0;
		tasksLauncher = threadSupplier.createDetachedThread().setExecutable(thread -> {
			while (!terminated) {
				if (checkAndNotifySuspension()) {
					continue;
				}
				if (!tasksQueue.isEmpty()) {
					Iterator<TaskAbst<?, ?>> taskIterator = tasksQueue.iterator();
					while (taskIterator.hasNext()) {
						if (checkAndNotifySuspension() || terminated) {
							break;
						}
						TaskAbst<?, ?> task = taskIterator.next();
						synchronized (task) {
							if (!tasksQueue.remove(task)) {
								continue;
							}
						}
						task.setExecutor(threadSupplier.getOrCreate()).start();
					}
				} else {
					synchronized(executableCollectionFillerMutex) {
						if (tasksQueue.isEmpty()) {
							try {
								synchronized(executingFinishedWaiterMutex) {
									executingFinishedWaiterMutex.notifyAll();
								}
								if (!supended) {
									executableCollectionFillerMutex.wait();
								}
							} catch (InterruptedException exc) {
								ManagedLoggersRepository.logError(getClass()::getName, exc);
							}
						}
					}
				}
			}
			synchronized(terminatingMutex) {
				tasksLauncher = null;
				terminatingMutex.notifyAll();
			}
		});
		tasksLauncher.setName(name + " launcher");
		tasksLauncher.setPriority(this.defaultPriority);
		tasksLauncher.setDaemon(isDaemon);
		tasksLauncher.start();
	}

	private boolean checkAndNotifySuspension() {
		if (supended) {
			synchronized(resumeCallerMutex) {
				synchronized (suspensionCallerMutex) {
					suspensionCallerMutex.notifyAll();
				}
				try {
					resumeCallerMutex.wait();
					return true;
				} catch (InterruptedException exc) {
					ManagedLoggersRepository.logError(getClass()::getName, exc);
				}
			}
		}
		return false;
	}

	public static QueuedTasksExecutor create(String executorName, Thread.Supplier threadSupplier, int initialPriority) {
		return create(executorName, threadSupplier, initialPriority, false, false);
	}

	public static QueuedTasksExecutor create(String executorName, Thread.Supplier threadSupplier, int initialPriority, boolean daemon, boolean undestroyable) {
		if (undestroyable) {
			return new QueuedTasksExecutor(executorName, threadSupplier, initialPriority, daemon) {
				StackTraceElement[] stackTraceOnCreation = Thread.currentThread().getStackTrace();
				@Override
				public boolean shutDown(boolean waitForTasksTermination) {
					if (Methods.retrieveExternalCallerInfo().getClassName().equals(Methods.retrieveExternalCallerInfo(stackTraceOnCreation).getClassName())) {
						return super.shutDown(waitForTasksTermination);
					}
					return false;
				}

			};
		} else {
			return new QueuedTasksExecutor(executorName, threadSupplier, initialPriority, daemon);
		}
	}

	public QueuedTasksExecutor setTasksCreationTrackingFlag(boolean flag) {
		this.taskCreationTrackingEnabled = flag;
		return this;
	}

	public <T> ProducerTask<T> createProducerTask(ThrowingFunction<ProducerTask<T>, T, ? extends Throwable> executable) {
		Function<ThrowingFunction<ProducerTask<T>, T, ? extends Throwable>, ProducerTask<T>> taskCreator = getProducerTaskSupplier();
		ProducerTask<T> task = taskCreator.apply(executable);
		task.priority = this.defaultPriority;
		return task;
	}

	<T> Function<ThrowingFunction<ProducerTask<T>, T, ? extends Throwable>, ProducerTask<T>> getProducerTaskSupplier() {
		return executable -> new ProducerTask<>(executable, taskCreationTrackingEnabled) {

			@Override
			QueuedTasksExecutor getQueuedTasksExecutor() {
				return QueuedTasksExecutor.this;
			}

			@Override
			QueuedTasksExecutor retrieveQueuedTasksExecutorOf(java.lang.Thread thread) {
				return QueuedTasksExecutor.this;
			}

		};
	}

	public Task createTask(ThrowingConsumer<QueuedTasksExecutor.Task, ? extends Throwable> executable) {
		Task task = getTaskSupplier().apply(executable);
		task.priority = this.defaultPriority;
		return task;
	}

	<T> Function<ThrowingConsumer<QueuedTasksExecutor.Task, ? extends Throwable>, Task> getTaskSupplier() {
		return executable -> new Task(executable, taskCreationTrackingEnabled) {

			@Override
			QueuedTasksExecutor getQueuedTasksExecutor() {
				return QueuedTasksExecutor.this;
			}

			@Override
			QueuedTasksExecutor retrieveQueuedTasksExecutorOf(java.lang.Thread thread) {
				return QueuedTasksExecutor.this;
			}

		};
	}

	<E, T extends TaskAbst<E, T>> T addToQueue(T task, boolean skipCheck) {
		Object[] canBeExecutedBag = null;
		if (skipCheck || (Boolean)(canBeExecutedBag = canBeExecuted(task))[1]) {
			try {
				tasksQueue.add(task);
				synchronized(executableCollectionFillerMutex) {
					executableCollectionFillerMutex.notifyAll();
				}
			} catch (Throwable exc) {
				ManagedLoggersRepository.logError(getClass()::getName, exc);
			}
		}
		return canBeExecutedBag != null ? (T)canBeExecutedBag[0] : task;
	}

	<E, T extends TaskAbst<E, T>> Object[] canBeExecuted(T task) {
		Object[] bag = new Object[]{task, true};
		if (task.runOnlyOnce) {
			bag[1] =(!task.hasBeenExecutedChecker.get() &&
				Optional.ofNullable(runOnlyOnceTasksToBeExecuted.putIfAbsent(
					task.id, task
				)).map(taskk -> {
					bag[0] = taskk;
					return false;
				}).orElseGet(() -> true)
			);
		}
		return bag;
	}

	public <E, T extends TaskAbst<E, T>> QueuedTasksExecutor waitFor(T task) {
		return waitFor(task, java.lang.Thread.currentThread().getPriority(), false);
	}

	public <E, T extends TaskAbst<E, T>> QueuedTasksExecutor waitFor(T task, boolean ignoreDeadLocked) {
		return waitFor(task, java.lang.Thread.currentThread().getPriority(), ignoreDeadLocked);
	}

	public <E, T extends TaskAbst<E, T>> QueuedTasksExecutor waitFor(T task, int priority, boolean ignoreDeadLocked) {
		changePriorityToAllTaskBeforeAndWaitThem(task, priority, ignoreDeadLocked);
		task.waitForFinish(ignoreDeadLocked);
		return this;
	}

	public QueuedTasksExecutor waitForTasksEnding() {
		return waitForTasksEnding(java.lang.Thread.currentThread().getPriority(), false);
	}

	public <E, T extends TaskAbst<E, T>> boolean abort(T task) {
		synchronized (task) {
			if (!task.isSubmitted()) {
				task.aborted = true;
				task.clear();
			}
		}
		if (!task.isStarted()) {
			if (task.runOnlyOnce) {
				for (TaskAbst<?, ?> queuedTask : tasksQueue) {
					if (task.id.equals(queuedTask.id)) {
						synchronized (queuedTask) {
							if (tasksQueue.remove(queuedTask)) {
								if (!queuedTask.isStarted()) {
									task.aborted = queuedTask.aborted = true;
									queuedTask.clear();
									task.clear();
									queuedTask.notifyAll();
									synchronized(task) {
										task.notifyAll();
									}
									runOnlyOnceTasksToBeExecuted.remove(queuedTask.id);
									return task.aborted;
								}
							}
						}
					}
				}
				return task.aborted;
			}
			synchronized (task) {
				if (task.aborted = tasksQueue.remove(task)) {
					task.notifyAll();
					task.clear();
					return task.aborted;
				}
			}
		}
		return task.aborted;
	}

	public QueuedTasksExecutor waitForTasksEnding(int priority, boolean waitForNewAddedTasks, boolean ignoreDeadLocked) {
		waitForTasksEnding(priority, ignoreDeadLocked);
		if (waitForNewAddedTasks) {
			while (!tasksInExecution.isEmpty() || !tasksQueue.isEmpty()) {
				waitForTasksEnding(priority, ignoreDeadLocked);
			}
		}
		return this;
	}

	public QueuedTasksExecutor waitForTasksEnding(int priority, boolean ignoreDeadLocked) {
		tasksLauncher.setPriority(priority);
		tasksQueue.stream().forEach(executable -> executable.changePriority(priority));
		if (!tasksQueue.isEmpty()) {
			synchronized(executingFinishedWaiterMutex) {
				if (!tasksQueue.isEmpty()) {
					try {
						executingFinishedWaiterMutex.wait();
					} catch (InterruptedException exc) {
						ManagedLoggersRepository.logError(getClass()::getName, exc);
					}
				}
			}
		}
		waitForTasksInExecutionEnding(priority, ignoreDeadLocked);
		tasksLauncher.setPriority(this.defaultPriority);
		return this;
	}

	public QueuedTasksExecutor changePriority(int priority) {
		this.defaultPriority = priority;
		tasksLauncher.setPriority(priority);
		tasksQueue.stream().forEach(executable -> executable.changePriority(priority));
		return this;
	}

	public QueuedTasksExecutor suspend(boolean immediately, boolean ignoreDeadLocked) {
		return suspend0(immediately, java.lang.Thread.currentThread().getPriority(), ignoreDeadLocked);
	}

	public QueuedTasksExecutor suspend(boolean immediately, int priority, boolean ignoreDeadLocked) {
		return suspend0(immediately, priority, ignoreDeadLocked);
	}

	QueuedTasksExecutor suspend0(boolean immediately, int priority, boolean ignoreDeadLocked) {
		tasksLauncher.setPriority(priority);
		if (immediately) {
			synchronized (suspensionCallerMutex) {
				supended = Boolean.TRUE;
				waitForTasksInExecutionEnding(priority, ignoreDeadLocked);
				try {
					synchronized(executableCollectionFillerMutex) {
						if (this.tasksLauncher.getState().equals(Thread.State.WAITING)) {
							executableCollectionFillerMutex.notifyAll();
						}
					}
					suspensionCallerMutex.wait();
				} catch (InterruptedException exc) {
					ManagedLoggersRepository.logError(getClass()::getName, exc);
				}
			}
		} else {
			waitForTasksInExecutionEnding(priority, ignoreDeadLocked);
			Task supendingTask = createSuspendingTask(priority);
			changePriorityToAllTaskBeforeAndWaitThem(supendingTask.addToQueue(), priority, ignoreDeadLocked);
			supendingTask.waitForFinish(ignoreDeadLocked);
		}
		tasksLauncher.setPriority(this.defaultPriority);
		return this;
	}

	Task createSuspendingTask(int priority) {
		return createTask(
			(ThrowingConsumer<QueuedTasksExecutor.Task, ? extends Throwable>)task ->
				supended = Boolean.TRUE
		).runOnlyOnce(getOperationId("suspend"),	() -> supended).changePriority(priority);
	}

	void waitForTasksInExecutionEnding(int priority, boolean ignoreDeadLocked) {
		tasksInExecution.stream().forEach(task -> {
			Thread taskExecutor = task.executor;
			if (taskExecutor != null) {
				taskExecutor.setPriority(priority);
			}
			//logInfo("{}", queueConsumer);
			//task.logInfo();
			task.waitForFinish(ignoreDeadLocked);
		});
	}

	<E, T extends TaskAbst<E, T>> void changePriorityToAllTaskBeforeAndWaitThem(T task, int priority, boolean ignoreDeadLocked) {
		int taskIndex = tasksQueue.indexOf(task);
		if (taskIndex != -1) {
			Iterator<TaskAbst<?, ?>> taskIterator = tasksQueue.iterator();
			int idx = 0;
			while (taskIterator.hasNext()) {
				TaskAbst<?, ?> currentIterated = taskIterator.next();
				if (idx < taskIndex) {
					if (currentIterated != task) {
						task.changePriority(priority);
					} else {
						break;
					}
				}
				idx++;
			}
		}
		waitForTasksInExecutionEnding(priority, ignoreDeadLocked);
	}

	public QueuedTasksExecutor resumeFromSuspension() {
		synchronized(resumeCallerMutex) {
			try {
				supended = Boolean.FALSE;
				resumeCallerMutex.notifyAll();
			} catch (Throwable exc) {
				ManagedLoggersRepository.logError(getClass()::getName, exc);
			}
		}
		return this;
	}

	public boolean shutDown(boolean waitForTasksTermination) {
		Collection<TaskAbst<?, ?>> executables = this.tasksQueue;
		if (waitForTasksTermination) {
			suspend(false, true);
		} else {
			suspend(true, true);
		}
		this.terminated = Boolean.TRUE;
		logStatus();
		executables.clear();
		tasksInExecution.clear();
		resumeFromSuspension();
		if (tasksLauncher != null) {
			synchronized (terminatingMutex) {
				if (tasksLauncher != null) {
					try {
						terminatingMutex.wait();
					} catch (InterruptedException exc) {
						ManagedLoggersRepository.logError(getClass()::getName, exc);
					}
				}
			}
		}
		closeResources();
		return true;
	}

	public void logStatus() {
		List<TaskAbst<?, ?>> tasks = new ArrayList<>(tasksQueue);
		tasks.addAll(this.tasksInExecution);
		logStatus(this.executedTasksCount, tasks);
	}

	private void logStatus(Long executedTasksCount, Collection<TaskAbst<?, ?>> executables) {
		Collection<String> executablesLog = executables.stream().map(task -> {
			Object executable = task.executable;
			if (executable != null) {
				return "\t" + executable;
			}
			return null;
		}).filter(threadInfo -> threadInfo != null).collect(Collectors.toList());
		StringBuffer log = new StringBuffer(this.tasksLauncher.getName() + " - launched tasks: ")
			.append(executedTasksCount).append(", not launched tasks: ")
			.append(executablesLog.size());

		if (executablesLog.size() > 0) {
			log.append(":\n\t")
			.append(String.join("\n\t", executablesLog));
		}
		ManagedLoggersRepository.logInfo(getClass()::getName, log.toString());
	}

	public String getInfoAsString() {
		StringBuffer log = new StringBuffer("");
		Collection<TaskAbst<?, ?>> tasksQueue = this.tasksQueue;
		if (!tasksQueue.isEmpty()) {
			log.append("\n\n");
			log.append(Strings.compile("{} - Tasks to be executed:", tasksLauncher));
			for (TaskAbst<?,?> task : tasksQueue) {
				log.append("\n" + task.getInfoAsString());
			}
		}
		tasksQueue = this.tasksInExecution;
		if (!tasksQueue.isEmpty()) {
			log.append("\n\n");
			log.append(Strings.compile("{} - Tasks in execution:", tasksLauncher));
			for (TaskAbst<?,?> task : tasksQueue) {
				log.append("\n" + task.getInfoAsString());
			}
		}
		return log.toString();
	}

	public void logInfo() {
		String message = getInfoAsString();
		if (!message.isEmpty()) {
			ManagedLoggersRepository.logInfo(getClass()::getName, message);
		}
	}

	@Override
	public void close() {
		shutDown(true);
	}

	void closeResources() {
		//queueConsumer = null;
		threadSupplier = null;
		tasksQueue = null;
		tasksInExecution = null;
		initializer = null;
		terminated = null;
		supended = null;
		resumeCallerMutex = null;
		executingFinishedWaiterMutex = null;
		suspensionCallerMutex = null;
		executableCollectionFillerMutex = null;
		ManagedLoggersRepository.logInfo(getClass()::getName, "All resources of '{}' have been closed", name);
		name = null;
	}

	public static abstract class TaskAbst<E, T extends TaskAbst<E, T>> implements ManagedLogger {

		String name;
		Long executorIndex;
		StackTraceElement[] stackTraceOnCreation;
		List<StackTraceElement> creatorInfos;
		Supplier<Boolean> hasBeenExecutedChecker;
		volatile boolean probablyDeadLocked;
		volatile boolean runOnlyOnce;
		volatile String id;
		volatile int priority;
		volatile Long startTime;
		volatile boolean submitted;
		volatile boolean aborted;
		volatile boolean finished;
		volatile boolean queueConsumerUnlockingRequested;
		E executable;
		Thread executor;
		Throwable exc;
		ThrowingBiPredicate<T, Throwable, Throwable> exceptionHandler;
		QueuedTasksExecutor queuedTasksExecutor;

		public TaskAbst(E executable, boolean creationTracking) {
			this.executable = executable;
			if (creationTracking) {
				stackTraceOnCreation = java.lang.Thread.currentThread().getStackTrace();
			}
		}

		T start() {
			executor.start();
			return (T)this;
		}

		public List<StackTraceElement> getCreatorInfos() {
			if (this.creatorInfos == null) {
				if (stackTraceOnCreation != null) {
					this.creatorInfos = Collections.unmodifiableList(
						Methods.retrieveExternalCallersInfo(
							this.stackTraceOnCreation,
							(clientMethodSTE, currentIteratedSTE) -> !currentIteratedSTE.getClassName().startsWith(QueuedTasksExecutor.class.getName()),
							-1
						)
					);
				} else {
					ManagedLoggersRepository.logWarn(getClass()::getName, "Tasks creation tracking was disabled when {} was created", this);
				}
			}
			return creatorInfos;
		}

		public Long getStartTime() {
			return startTime;
		}

		public T setName(String name) {
			this.name = name;
			return (T)this;
		}

		public T setExceptionHandler(ThrowingBiPredicate<T, Throwable, Throwable> exceptionHandler) {
			this.exceptionHandler = exceptionHandler;
			return (T)this;
		}

		public boolean isStarted() {
			return startTime != null;
		}

		public boolean hasFinished() {
			return finished;
		}

		public T runOnlyOnce(String id, Supplier<Boolean> hasBeenExecutedChecker) {
			if (isSubmitted()) {
				Driver.throwException(new TaskStateException(this, "is submitted"));
			}
			runOnlyOnce = true;
			this.id = id;
			this.hasBeenExecutedChecker = hasBeenExecutedChecker;
			return (T)this;
		}

		public boolean isAborted() {
			return aborted;
		}

		public boolean isSubmitted() {
			return submitted;
		}

		public boolean isProbablyDeadLocked() {
			return probablyDeadLocked;
		}

		synchronized void markAsProbablyDeadLocked() {
			probablyDeadLocked = true;
		}

		private void remove() {
			QueuedTasksExecutor queuedTasksExecutor = getQueuedTasksExecutor();
			queuedTasksExecutor.tasksInExecution.remove(this);
			if (runOnlyOnce) {
				runOnlyOnceTasksToBeExecuted.remove(id);
			}
			if (executorIndex != null) {
				executorIndex = null;
				--queuedTasksExecutor.executorsIndex;
			}
		}

		public T waitForStarting() {
			return waitForStarting(false, 0);
		}

		public T waitForStarting(long timeout) {
			return waitForStarting(false, timeout);
		}

		public T waitForStarting(boolean ignoreDeadLocked) {
			return waitForStarting(ignoreDeadLocked, 0);
		}

		public T waitForStarting(boolean ignoreDeadLocked, long timeout) {
			if (timeout <= 0) {
				while(waitForStarting0(ignoreDeadLocked, 0));
				return (T)this;
			}
			long timeAtStartWaiting = System.currentTimeMillis();
			while(waitForStarting0(ignoreDeadLocked, timeout) &&
				System.currentTimeMillis() - timeAtStartWaiting < timeout
			) {}
			return (T)this;
		}

		private boolean waitForStarting0(boolean ignoreDeadLocked, long timeout) {
			java.lang.Thread currentThread = java.lang.Thread.currentThread();
			if (currentThread == this.executor) {
				return false;
			}
			if (isSubmitted()) {
				if (!isStarted()) {
					synchronized (this) {
						if (!isStarted()) {
							try {
								if (probablyDeadLocked) {
									if (ignoreDeadLocked) {
										return false;
									}
									Driver.throwException(new TaskStateException(this, "could be dead locked"));
								}
								if (isAborted()) {
									Driver.throwException(new TaskStateException(this, "is aborted"));
								}
								wait(timeout);
								return true;
							} catch (InterruptedException exc) {
								Driver.throwException(exc);
							}
						}
					}
				}
			} else {
				Driver.throwException(new TaskStateException(this, "is not submitted"));
			}
			return false;
		}

		public T waitForFinish() {
			return waitForFinish(false);
		}

		public T waitForFinish(long timeout) {
			return waitForFinish(false, timeout);
		}

		public T waitForFinish(boolean ignoreDeadLocked) {
			return waitForFinish(ignoreDeadLocked, 0);
		}

		public T waitForFinish(boolean ignoreDeadLocked, long timeout) {
			if (timeout <= 0) {
				while(waitForFinish0(ignoreDeadLocked, 0));
				return (T)this;
			}
			long timeAtStartWaiting = System.currentTimeMillis();
			while(waitForFinish0(ignoreDeadLocked, timeout) &&
				System.currentTimeMillis() - timeAtStartWaiting < timeout
			) {}
			return (T)this;
		}

		private boolean waitForFinish0(boolean ignoreDeadLocked, long timeout) {
			java.lang.Thread currentThread = java.lang.Thread.currentThread();
			if (currentThread == this.executor) {
				return false;
			}
			if (isSubmitted()) {
				if (!hasFinished()) {
					synchronized (this) {
						if (!hasFinished()) {
							try {
								if (probablyDeadLocked) {
									if (ignoreDeadLocked) {
										return false;
									}
									Driver.throwException(new TaskStateException(this, "could be dead locked"));
								}
								if (isAborted()) {
									Driver.throwException(new TaskStateException(this, "is aborted"));
								}
								wait(timeout);
								return true;
							} catch (InterruptedException exc) {
								Driver.throwException(exc);
							}
						}
					}
				}
			} else {
				Driver.throwException(new TaskStateException(this, "is not submitted"));
			}
			return false;
		}

		void execute() {
			synchronized (this) {
				if (aborted) {
					notifyAll();
					clear();
					return;
				}

			}
			preparingToExecute();
			synchronized (this) {
				notifyAll();
			}
			try {
				try {
					execute0();
				} catch (Throwable exc) {
					this.exc = exc;
					if (exceptionHandler == null || !exceptionHandler.test((T)this, exc)) {
						throw exc;
					}
				}
			} catch (Throwable exc) {
				logException(exc);
			} finally {
				markAsFinished();
			}
		}

		String getInfoAsString() {
			if (this.getCreatorInfos() != null) {
				Thread executor = this.executor;
				return Strings.compile("\n\tTask status: {} {} \n\tcreated by: {}",
					Strings.compile("\n\t\tpriority: {}\n\t\tstarted: {}\n\t\taborted: {}\n\t\tfinished: {}", priority, isStarted(), isAborted(), hasFinished()),
					executor != null ? "\n\t" + executor + Strings.from(executor.getStackTrace(),2) : "",
					Strings.from(this.getCreatorInfos(), 2)
				);
			}
			return "";
		}

		void logInfo() {
			if (this.getCreatorInfos() != null) {
				ManagedLoggersRepository.logInfo(getClass()::getName, getInfoAsString());
			}
		}

		private void logException(Throwable exc) {
			ManagedLoggersRepository.logError(getClass()::getName, Strings.compile(
				"Exception occurred while executing {}: \n{}: {}{}{}",
				this,
				exc.toString(),
				exc.getMessage(),
				Strings.from(exc.getStackTrace()),
				this.getCreatorInfos() != null ?
					"\nthat was created:" + Strings.from(this.getCreatorInfos())
					: ""
			));
		}

		void clear() {
			remove();
			executable = null;
			executor = null;
			queuedTasksExecutor = null;
		}

		void markAsFinished() {
			finished = true;
			synchronized(this) {
				queuedTasksExecutor.tasksInExecution.remove(this);
				++queuedTasksExecutor.executedTasksCount;
				notifyAll();
			}
			clear();
		}

		abstract void execute0() throws Throwable;

		T setExecutor(Thread thread) {
			executor = thread.setExecutable(thr -> this.execute());
			executor.setPriority(this.priority);
			QueuedTasksExecutor queuedTasksExecutor = getQueuedTasksExecutor();
			if (name != null) {
				executor.setName(queuedTasksExecutor.name + " - " + name);
			} else {
				executor.setName(queuedTasksExecutor.name + " executor " + (executorIndex = ++queuedTasksExecutor.executorsIndex));
			}
			return (T)this;
		}

		public T changePriority(int priority) {
			this.priority = priority;
			if (executor != null) {
				executor.setPriority(this.priority);
			}
			return (T)this;
		}

		public T setPriorityToCurrentThreadPriority() {
			return changePriority(java.lang.Thread.currentThread().getPriority());
		}

		public int getPriority() {
			return priority;
		}

		public Throwable getException() {
			return exc;
		}

		public final T submit() {
			if (aborted) {
				Driver.throwException(new TaskStateException(this, "is aborted"));
			}
			if (!submitted) {
				synchronized(this) {
					if (!submitted) {
						submitted = true;
					} else {
						Driver.throwException(new TaskStateException(this, "is already submitted"));
					}
				}
			} else {
				Driver.throwException(new TaskStateException(this, "is already submitted"));
			}
			return addToQueue();
		}

		T addToQueue() {
			return getQueuedTasksExecutor().addToQueue((T)this, false);
		}

		void preparingToExecute() {
			queuedTasksExecutor = getQueuedTasksExecutor();
			startTime = System.currentTimeMillis();
			queuedTasksExecutor.tasksInExecution.add(this);
		}

		public T abortOrWaitForFinish() {
			return abortOrWaitForFinish(false);
		}

		public T abortOrWaitForFinish(boolean ignoreDeadLocked) {
			if (!abort().isAborted() && isStarted()) {
				waitForFinish(ignoreDeadLocked);
			}
			return (T)this;
		}

		public T abort() {
			getQueuedTasksExecutor().abort((T)this);
			return (T)this;
		}

		abstract QueuedTasksExecutor getQueuedTasksExecutor();

		abstract QueuedTasksExecutor retrieveQueuedTasksExecutorOf(java.lang.Thread thread);
	}

	public static abstract class Task extends TaskAbst<ThrowingConsumer<QueuedTasksExecutor.Task, ? extends Throwable>, Task> {

		Task(ThrowingConsumer<Task, ? extends Throwable> executable, boolean creationTracking) {
			super(executable, creationTracking);
		}

		@Override
		void execute0() throws Throwable {
			this.executable.accept(this);
		}

	}

	public static abstract class ProducerTask<T> extends TaskAbst<ThrowingFunction<QueuedTasksExecutor.ProducerTask<T>, T, ? extends Throwable>, ProducerTask<T>> {
		private T result;

		ProducerTask(ThrowingFunction<QueuedTasksExecutor.ProducerTask<T>, T, ? extends Throwable> executable, boolean creationTracking) {
			super(executable, creationTracking);
		}

		@Override
		void execute0() throws Throwable {
			result = executable.apply(this);
		}

		public T join() {
			return join(false);
		}


		public T join(boolean ignoreDeadLocked) {
			waitForFinish(ignoreDeadLocked);
			return result;
		}

		public T get() {
			return result;
		}

	}

	public static class Group {
		String name;
		Map<Integer, QueuedTasksExecutor> queuedTasksExecutors;
		TasksMonitorer allTasksMonitorer;
		Consumer<Group> initializator;
		Integer[] definedPriorites;

		Group(Map<String, Object> configuration) {
			//Implemented deferred initialization (since 10.0.0, the previous version is 9.5.2)
			initializator = queuedTasksExecutorGroup -> {
				String name = IterableObjectHelper.resolveStringValue(
					ResolveConfig.forNamedKey("name")
					.on(configuration)
				);
				Thread.Supplier mainThreadSupplier = (Thread.Supplier)configuration.get("thread-supplier");
				Boolean isDaemon = Objects.toBoolean(
					IterableObjectHelper.resolveValue(
						ResolveConfig.forNamedKey("daemon")
						.on(configuration)
					)
				);
				queuedTasksExecutorGroup.name = name;
				queuedTasksExecutorGroup.queuedTasksExecutors = new HashMap<>();
				for (int i = 0;  i < java.lang.Thread.MAX_PRIORITY; i++) {
					Object priorityAsObject = IterableObjectHelper.resolveValue(
						ResolveConfig.forNamedKey("queue-task-executor[" + i + "].priority")
						.on(configuration)
					);
					if (priorityAsObject != null) {
						int priority = Objects.toInt(priorityAsObject);
						if (priority < java.lang.Thread.MIN_PRIORITY || priority > java.lang.Thread.MAX_PRIORITY) {
							throw new IllegalArgumentException(
								Strings.compile(
									"Value of '{}' is not correct: it must be between {} and {}",
									"queue-task-executor[" + i + "].priority",
									java.lang.Thread.MIN_PRIORITY, java.lang.Thread.MAX_PRIORITY
								)
							);
						}
						String queuedTasksExecutorName =
							IterableObjectHelper.resolveStringValue(
								ResolveConfig.forNamedKey("queue-task-executor[" + i + "].name")
								.on(configuration)
							);
						Thread.Supplier queuedTasksExecutorThreadSupplier =
							IterableObjectHelper.resolveValue(
								ResolveConfig.forNamedKey("queue-task-executor[" + i + "].thread-supplier")
								.on(configuration)
						);
						if (queuedTasksExecutorThreadSupplier == null) {
							queuedTasksExecutorThreadSupplier = mainThreadSupplier;
						}
						Object isQueuedTasksExecutorDaemonAsObject =
							IterableObjectHelper.resolveValue(
								ResolveConfig.forNamedKey("queue-task-executor[" + i + "].daemon")
								.on(configuration)
							);
						Boolean isQueuedTasksExecutorDaemon = isDaemon;
						if (isQueuedTasksExecutorDaemonAsObject != null) {
							isQueuedTasksExecutorDaemon = Objects.toBoolean(
								isQueuedTasksExecutorDaemonAsObject
							);
						}
						queuedTasksExecutorGroup.queuedTasksExecutors.put(
							priority,
							createQueuedTasksExecutor(
								name + " - " + queuedTasksExecutorName,
								queuedTasksExecutorThreadSupplier,
								priority,
								isQueuedTasksExecutorDaemon
							)
						);
					}
				}
				definedPriorites = queuedTasksExecutorGroup.queuedTasksExecutors.keySet().toArray(
					definedPriorites = new Integer[queuedTasksExecutorGroup.queuedTasksExecutors.size()]
				);
			};
		}

		public Group setTasksCreationTrackingFlag(boolean flag) {
			if (initializator == null) {
				setTasksCreationTrackingFlag(this, flag);
			} else {
				initializator = initializator.andThen(queuedTasksExecutorGroup -> {
					setTasksCreationTrackingFlag(queuedTasksExecutorGroup, flag);
				});
			}
			return this;
		}

		private void setTasksCreationTrackingFlag(Group queuedTasksExecutorGroup, boolean flag) {
			for (Entry<Integer, QueuedTasksExecutor> queuedTasksExecutorBox : queuedTasksExecutorGroup.queuedTasksExecutors.entrySet()) {
				queuedTasksExecutorBox.getValue().setTasksCreationTrackingFlag(flag);
			}
		}

		public Group startAllTasksMonitoring(TasksMonitorer.Config config) {
			if (initializator == null) {
				startAllTasksMonitoring(this, config);
			} else {
				initializator = initializator.andThen(queuedTasksExecutorGroup -> {
					startAllTasksMonitoring(this, config);
				});
			}
			return this;
		}

		void startAllTasksMonitoring(Group queuedTasksExecutorGroup, TasksMonitorer.Config config) {
			TasksMonitorer allTasksMonitorer = queuedTasksExecutorGroup.allTasksMonitorer;
			if (allTasksMonitorer != null) {
				allTasksMonitorer.close();
			}
			queuedTasksExecutorGroup.allTasksMonitorer = new TasksMonitorer(queuedTasksExecutorGroup, config).start();
		}

		public static Group create(
			String keyPrefix,
			Map<String, Object> configuration
		) {
			configuration = IterableObjectHelper.resolveValues(
				ResolveConfig.forAllKeysThat((Predicate<String>)(key) ->
					key.startsWith(keyPrefix + "."))
				.on(configuration)
			);
			Map<String, Object> finalConfiguration = new HashMap<>();
			boolean undestroyableFromExternal = Objects.toBoolean(
				IterableObjectHelper.resolveValue(
					ResolveConfig.forNamedKey(keyPrefix + ".undestroyable-from-external")
					.on(configuration)
				)
			);
			for (Entry<String, Object> entry : configuration.entrySet()) {
				Object value = entry.getValue();
				if (value instanceof Collection && ((Collection<Object>)value).size() == 1) {
					value = ((Collection<Object>)value).iterator().next();
				}
				finalConfiguration.put(entry.getKey().replace(keyPrefix +".", ""), value);
			}
			if (!undestroyableFromExternal) {
				return new Group(
					finalConfiguration
				);
			} else {
				return new Group(
					finalConfiguration
				) {
					StackTraceElement[] stackTraceOnCreation = Thread.currentThread().getStackTrace();

					@Override
					public boolean shutDown(boolean waitForTasksTermination) {
						if (Methods.retrieveExternalCallerInfo().getClassName().equals(Methods.retrieveExternalCallerInfo(stackTraceOnCreation).getClassName())) {
							return super.shutDown(waitForTasksTermination);
						}
						return false;
					}
				};
			}
		}

		public <T> ProducerTask<T> createProducerTask(ThrowingFunction<ProducerTask<T>, T, ? extends Throwable> executable) {
			return createProducerTask(executable, java.lang.Thread.currentThread().getPriority());
		}

		public <T> ProducerTask<T> createProducerTask(ThrowingFunction<ProducerTask<T>, T, ? extends Throwable> executable, int priority) {
			return getByPriority(priority).createProducerTask(executable);
		}

		QueuedTasksExecutor getByPriority(int priority) {
			QueuedTasksExecutor queuedTasksExecutor = null;
			//Implemented deferred initialization (since 10.0.0, the previous version is 9.5.2)
			try {
				queuedTasksExecutor = queuedTasksExecutors.get(priority);
			} catch (Exception e) {
				if (queuedTasksExecutor == null) {
					if (initializator != null) {
						Synchronizer.execute("queuedTasksExecutorGroup.initialization", () -> {
							if (initializator != null) {
								initializator.accept(this);
								initializator = null;
							}
						});
					}
				}
				queuedTasksExecutor = queuedTasksExecutors.get(priority);
			}
			if (queuedTasksExecutor == null) {
				queuedTasksExecutor = queuedTasksExecutors.get(checkAndCorrectPriority(priority));
			}
			return queuedTasksExecutor;
		}

		int checkAndCorrectPriority(int priority) {
			if (queuedTasksExecutors.get(priority) != null) {
				return priority;
			}
			if (priority < java.lang.Thread.MIN_PRIORITY || priority > java.lang.Thread.MAX_PRIORITY) {
				throw new IllegalArgumentException(
					Strings.compile(
						"Priority value must be between {} and {}",
						java.lang.Thread.MIN_PRIORITY, java.lang.Thread.MAX_PRIORITY
					)
				);
			}
			Integer currentMaxPriorityHandled = definedPriorites[definedPriorites.length -1];
			if (priority > currentMaxPriorityHandled) {
				return currentMaxPriorityHandled;
			}
			for (Integer definedPriorite : definedPriorites) {
				if (priority < definedPriorite) {
					return definedPriorite;
				}
			}
			return definedPriorites[definedPriorites.length -1];
		}

		public Task createTask(ThrowingConsumer<QueuedTasksExecutor.Task, ? extends Throwable> executable) {
			return createTask(executable, java.lang.Thread.currentThread().getPriority());
		}

		public Task createTask(ThrowingConsumer<QueuedTasksExecutor.Task, ? extends Throwable> executable, int priority) {
			return getByPriority(priority).createTask(executable);
		}

		QueuedTasksExecutor createQueuedTasksExecutor(String executorName, Thread.Supplier threadSupplier, int priority, boolean isDaemon) {
			return new QueuedTasksExecutor(executorName, threadSupplier, priority, isDaemon) {

				@Override
				<T> Function<ThrowingFunction<QueuedTasksExecutor.ProducerTask<T>, T, ? extends Throwable>, QueuedTasksExecutor.ProducerTask<T>> getProducerTaskSupplier() {
					return executable -> new QueuedTasksExecutor.ProducerTask<>(executable, taskCreationTrackingEnabled) {

						@Override
						QueuedTasksExecutor getQueuedTasksExecutor() {
							return this.queuedTasksExecutor != null?
								this.queuedTasksExecutor : Group.this.getByPriority(this.priority);
						}

						@Override
						public QueuedTasksExecutor.ProducerTask<T> changePriority(int priority) {
							Group.this.changePriority(this, priority);
							return this;
						}

						@Override
						QueuedTasksExecutor retrieveQueuedTasksExecutorOf(java.lang.Thread thread) {
							return Group.this.getByPriority(thread.getPriority());
						}

					};
				}

				@Override
				<T> Function<ThrowingConsumer<QueuedTasksExecutor.Task, ? extends Throwable> , QueuedTasksExecutor.Task> getTaskSupplier() {
					return executable -> new QueuedTasksExecutor.Task(executable, taskCreationTrackingEnabled) {

						@Override
						QueuedTasksExecutor getQueuedTasksExecutor() {
							return this.queuedTasksExecutor != null?
								this.queuedTasksExecutor : Group.this.getByPriority(this.priority);
						}

						@Override
						public QueuedTasksExecutor.Task changePriority(int priority) {
							Group.this.changePriority(this, priority);
							return this;
						}

						@Override
						QueuedTasksExecutor retrieveQueuedTasksExecutorOf(java.lang.Thread thread) {
							return Group.this.getByPriority(thread.getPriority());
						}
					};
				}

				@Override
				public QueuedTasksExecutor waitForTasksEnding(int priority, boolean ignoreDeadLocked) {
					if (priority == defaultPriority) {
						if (!tasksQueue.isEmpty()) {
							synchronized(executingFinishedWaiterMutex) {
								if (!tasksQueue.isEmpty()) {
									try {
										executingFinishedWaiterMutex.wait();
									} catch (InterruptedException exc) {
										ManagedLoggersRepository.logError(getClass()::getName, exc);
									}
								}
							}
						}
						tasksInExecution.stream().forEach(task -> {
							//logInfo("{}", queueConsumer);
							//task.logInfo();
							task.waitForFinish(ignoreDeadLocked);
						});
					} else {
						tasksQueue.stream().forEach(executable ->
							executable.changePriority(priority)
						);
						waitForTasksInExecutionEnding(priority, ignoreDeadLocked);
					}
					return this;
				}

				@Override
				public <E, T extends TaskAbst<E, T>> QueuedTasksExecutor waitFor(T task, int priority, boolean ignoreDeadLocked) {
					task.waitForFinish(ignoreDeadLocked);
					return this;
				}

				@Override
				Task createSuspendingTask(int priority) {
					return createTask((ThrowingConsumer<QueuedTasksExecutor.Task, ? extends Throwable>)task ->
						supended = Boolean.TRUE
					);
				}
			};
		}

		<E, T extends TaskAbst<E, T>> Group changePriority(T task, int priority) {
			int oldPriority = task.priority;
			int newPriority = checkAndCorrectPriority(priority);
			if (oldPriority != priority) {
				synchronized (task) {
					if (getByPriority(oldPriority).tasksQueue.remove(task)) {
						task.priority = newPriority;
						QueuedTasksExecutor queuedTasksExecutor = getByPriority(newPriority);
						task.queuedTasksExecutor = null;
						task.executor = null;
						queuedTasksExecutor.addToQueue(task, true);
					}
				}
			}
			return this;
		}

		public boolean isClosed() {
			return queuedTasksExecutors == null;
		}

		public Group waitForTasksEnding() {
			return waitForTasksEnding(java.lang.Thread.currentThread().getPriority(), false, false);
		}

		public Group waitForTasksEnding(boolean ignoreDeadLocked) {
			return waitForTasksEnding(java.lang.Thread.currentThread().getPriority(), false, ignoreDeadLocked);
		}

		public Group waitForTasksEnding(boolean waitForNewAddedTasks, boolean ignoreDeadLocked) {
			return waitForTasksEnding(java.lang.Thread.currentThread().getPriority(), waitForNewAddedTasks, ignoreDeadLocked);
		}

		public Group waitForTasksEnding(int priority, boolean waitForNewAddedTasks, boolean ignoreDeadLocked) {
			//Implemented deferred initialization (since 10.0.0, the previous version is 9.5.2)
			Synchronizer.execute("queuedTasksExecutorGroup.initialization", () -> {
				if (initializator != null) {
					return;
				}
				QueuedTasksExecutor lastToBeWaitedFor = getByPriority(priority);
				for (Entry<Integer, QueuedTasksExecutor> queuedTasksExecutorBox : queuedTasksExecutors.entrySet()) {
					QueuedTasksExecutor queuedTasksExecutor = queuedTasksExecutorBox.getValue();
					if (queuedTasksExecutor != lastToBeWaitedFor) {
						queuedTasksExecutor.waitForTasksEnding(priority, waitForNewAddedTasks, ignoreDeadLocked);
					}
				}
				lastToBeWaitedFor.waitForTasksEnding(priority, waitForNewAddedTasks);
				for (Entry<Integer, QueuedTasksExecutor> queuedTasksExecutorBox : queuedTasksExecutors.entrySet()) {
					QueuedTasksExecutor queuedTasksExecutor = queuedTasksExecutorBox.getValue();
					if (waitForNewAddedTasks && (!queuedTasksExecutor.tasksQueue.isEmpty() || !queuedTasksExecutor.tasksInExecution.isEmpty())) {
						waitForTasksEnding(priority, waitForNewAddedTasks, ignoreDeadLocked);
						break;
					}
				}
			});
			return this;
		}

		public <E, T extends TaskAbst<E, T>> Group waitFor(T task, boolean ignoreDeadLocked) {
			return waitFor(task, java.lang.Thread.currentThread().getPriority(), ignoreDeadLocked);
		}

		public <E, T extends TaskAbst<E, T>> Group waitFor(T task, int priority, boolean ignoreDeadLocked) {
			if (task.getPriority() != priority) {
				task.changePriority(priority);
			}
			task.waitForFinish(ignoreDeadLocked);
			return this;
		}

		public Group logInfo() {
			String loggableMessage = getInfoAsString();
			loggableMessage = getInfoAsString();
			if (!loggableMessage.isEmpty()) {
				ManagedLoggersRepository.logInfo(getClass()::getName, loggableMessage);
			}
			return this;
		}

		public String getInfoAsString() {
			StringBuffer loggableMessage = new StringBuffer("");
			for (Entry<Integer, QueuedTasksExecutor> queuedTasksExecutorBox : queuedTasksExecutors.entrySet()) {
				loggableMessage.append(queuedTasksExecutorBox.getValue().getInfoAsString());
			}
			return loggableMessage.toString();
		}

		public <E, T extends TaskAbst<E, T>> boolean abort(T task) {
			for (Entry<Integer, QueuedTasksExecutor> queuedTasksExecutorBox : queuedTasksExecutors.entrySet()) {
				if (queuedTasksExecutorBox.getValue().abort(task)) {
					return true;
				}
			}
			return false;
		}

		public Collection<TaskAbst<?, ?>> getAllTasksInExecution() {
			Collection<TaskAbst<?, ?>> tasksInExecution = new HashSet<>();
			for (Entry<Integer, QueuedTasksExecutor> queuedTasksExecutorBox : queuedTasksExecutors.entrySet()) {
				tasksInExecution.addAll(
					queuedTasksExecutorBox.getValue().tasksInExecution
				);
			}
			return tasksInExecution;
		}

		public Group startAllTasksMonitoring() {
			TasksMonitorer allTasksMonitorer = this.allTasksMonitorer;
			if (allTasksMonitorer != null) {
				allTasksMonitorer.start();
				return this;
			}
			return Driver.throwException("All tasks monitorer has not been configured");
		}

		public Group stopAllTasksMonitoring() {
			TasksMonitorer allTasksMonitorer = this.allTasksMonitorer;
			if (allTasksMonitorer != null) {
				allTasksMonitorer.stop();
			}
			return this;
		}

		public boolean shutDown(boolean waitForTasksTermination) {
			//Implemented deferred initialization (since 10.0.0, the previous version is 9.5.2)
			Synchronizer.execute("queuedTasksExecutorGroup.initialization", () -> {
				if (initializator != null) {
					initializator = null;
					return;
				}
				QueuedTasksExecutor lastToBeWaitedFor = getByPriority(java.lang.Thread.currentThread().getPriority());
				for (Entry<Integer, QueuedTasksExecutor> queuedTasksExecutorBox : queuedTasksExecutors.entrySet()) {
					QueuedTasksExecutor queuedTasksExecutor = queuedTasksExecutorBox.getValue();
					if (queuedTasksExecutor != lastToBeWaitedFor) {
						queuedTasksExecutor.shutDown(waitForTasksTermination);
					}
				}
				lastToBeWaitedFor.shutDown(waitForTasksTermination);
				allTasksMonitorer.close(waitForTasksTermination);
				allTasksMonitorer = null;
				queuedTasksExecutors.clear();
				queuedTasksExecutors = null;
			});
			return true;
		}

		public static class TasksMonitorer implements Closeable, ManagedLogger {
			Map<TaskAbst<?, ?>, StackTraceElement[]> waitingTasksAndLastStackTrace;
			QueuedTasksExecutor.Group queuedTasksExecutorGroup;
			TasksMonitorer.Config config;

			TasksMonitorer(QueuedTasksExecutor.Group queuedTasksExecutorGroup, TasksMonitorer.Config config) {
				waitingTasksAndLastStackTrace = new HashMap<>();
				this.queuedTasksExecutorGroup = queuedTasksExecutorGroup;
				this.config = config;
			}

			void checkAndHandleProbableDeadLockedTasks(
				long minimumElapsedTimeToConsiderATaskAsProbablyDeadLocked,
				boolean markAsProbableDeadLocked,
				boolean killProbableDeadLockedTasks
			) {
				Iterator<Entry<TaskAbst<?, ?>, StackTraceElement[]>> tasksAndStackTracesIterator = waitingTasksAndLastStackTrace.entrySet().iterator();
				while (tasksAndStackTracesIterator.hasNext()) {
					TaskAbst<?, ?> task = tasksAndStackTracesIterator.next().getKey();
					if(task.hasFinished() || task.isAborted()) {
						tasksAndStackTracesIterator.remove();
					}
				}
				long currentTime = System.currentTimeMillis();
				for (TaskAbst<?, ?> task : queuedTasksExecutorGroup.getAllTasksInExecution()) {
					if (currentTime - task.startTime > minimumElapsedTimeToConsiderATaskAsProbablyDeadLocked) {
						java.lang.Thread taskThread = task.executor;
						if (taskThread != null &&
						(taskThread.getState().equals(Thread.State.BLOCKED) ||
						taskThread.getState().equals(Thread.State.WAITING) ||
						taskThread.getState().equals(Thread.State.TIMED_WAITING))) {
							StackTraceElement[] previousRegisteredStackTrace = waitingTasksAndLastStackTrace.get(task);
							StackTraceElement[] currentStackTrace = taskThread.getStackTrace();
							if (previousRegisteredStackTrace != null) {
								if (areStrackTracesEquals(previousRegisteredStackTrace, currentStackTrace)) {
									if (!task.hasFinished() && !task.isAborted()) {
										if (markAsProbableDeadLocked) {
											task.markAsProbablyDeadLocked();
											taskThread.setName("PROBABLE DEAD-LOCKED THREAD -> " + taskThread.getName());
										}
										if (markAsProbableDeadLocked || killProbableDeadLockedTasks) {
											task.remove();
										}
										if (killProbableDeadLockedTasks && task.hasFinished()) {
											task.aborted = true;
											taskThread.interrupt();
										}
										if (markAsProbableDeadLocked || killProbableDeadLockedTasks) {
											synchronized(task) {
												task.notifyAll();
											}
										}
										ManagedLoggersRepository.logWarn(
											() -> this.getClass().getName(),
											"Possible deadlock detected for task:{}\n\t{}",
											task.getInfoAsString(),
											Synchronizer.getAllThreadsInfoAsString(true)
										);
										Synchronizer.logAllThreadsState(true);
									}
								} else {
									waitingTasksAndLastStackTrace.put(task, currentStackTrace);
								}
							} else {
								waitingTasksAndLastStackTrace.put(task, currentStackTrace);
							}
						}
					}
				}
			}

			private boolean areStrackTracesEquals(StackTraceElement[] stackTraceOne, StackTraceElement[] stackTraceTwo) {
				if (stackTraceOne.length == stackTraceTwo.length) {
					for (int i = 0; i < stackTraceOne.length; i++) {
						if (!stackTraceOne[i].toString().equals(stackTraceTwo[i].toString()) ) {
							return false;
						}
					}
					return true;
				}
				return false;
			}

			private String getName() {
				return Optional.ofNullable(queuedTasksExecutorGroup.name).map(nm -> nm + " - ").orElseGet(() -> "") + "All tasks monitorer";
			}

			public TasksMonitorer start() {
				ManagedLoggersRepository.logInfo(
					() -> this.getClass().getName(),
					"Starting {}", getName()
				);
				ThreadHolder.startLooping(getName(), true, java.lang.Thread.MIN_PRIORITY, thread -> {
					thread.waitFor(config.getInterval());
					if (thread.isLooping()) {
						if (config.isAllTasksLoggerEnabled()) {
							queuedTasksExecutorGroup.logInfo();
						}
						try {
							checkAndHandleProbableDeadLockedTasks(
								config.getMinimumElapsedTimeToConsiderATaskAsProbablyDeadLocked(),
								config.isMarkAsProablyDeadLockedEnabled(),
								config.isKillProablyDeadLockedTasksEnabled()
							);
						} catch (Throwable exc) {
							ManagedLoggersRepository.logError(
								() -> this.getClass().getName(),
								"Exception occurred while checking dead locked tasks", exc
							);
						}
					}
				});
				return this;
			}

			public void stop() {
				stop(false);
			}

			public void stop(boolean waitThreadToFinish) {
				ManagedLoggersRepository.logInfo(
					() -> this.getClass().getName(),
					"Starting {}", getName()
				);
				ThreadHolder.stop(getName());
			}

			@Override
			public void close() {
				close(false);
			}

			public void close(boolean waitForTasksTermination) {
				stop(waitForTasksTermination);
				this.queuedTasksExecutorGroup = null;
				this.waitingTasksAndLastStackTrace.clear();
				this.waitingTasksAndLastStackTrace = null;
			}

			public static class Config {
				private long interval;
				private long minimumElapsedTimeToConsiderATaskAsProbablyDeadLocked;
				private boolean markAsProbableDeadLocked;
				private boolean killProbableDeadLockedTasks;
				private boolean allTasksLoggerEnabled;

				public long getInterval() {
					return interval;
				}

				public TasksMonitorer.Config setInterval(long interval) {
					this.interval = interval;
					return this;
				}

				public long getMinimumElapsedTimeToConsiderATaskAsProbablyDeadLocked() {
					return minimumElapsedTimeToConsiderATaskAsProbablyDeadLocked;
				}

				public TasksMonitorer.Config setMinimumElapsedTimeToConsiderATaskAsProbablyDeadLocked(
						long minimumElapsedTimeToConsiderATaskAsProbablyDeadLocked) {
					this.minimumElapsedTimeToConsiderATaskAsProbablyDeadLocked = minimumElapsedTimeToConsiderATaskAsProbablyDeadLocked;
					return this;
				}

				public boolean isMarkAsProablyDeadLockedEnabled() {
					return markAsProbableDeadLocked;
				}

				public TasksMonitorer.Config setMarkAsProbableDeadLocked(boolean markAsProablyDeadLocked) {
					this.markAsProbableDeadLocked = markAsProablyDeadLocked;
					return this;
				}

				public boolean isKillProablyDeadLockedTasksEnabled() {
					return killProbableDeadLockedTasks;
				}

				public TasksMonitorer.Config setKillProbableDeadLockedTasks(boolean killProablyDeadLockedTasks) {
					this.killProbableDeadLockedTasks = killProablyDeadLockedTasks;
					return this;
				}

				public boolean isAllTasksLoggerEnabled() {
					return allTasksLoggerEnabled;
				}

				public TasksMonitorer.Config setAllTasksLoggerEnabled(boolean allTasksLoggerEnabled) {
					this.allTasksLoggerEnabled = allTasksLoggerEnabled;
					return this;
				}
			}
		}
	}

}
