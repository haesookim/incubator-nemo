/*
 * Copyright (C) 2017 Seoul National University
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.snu.vortex.runtime.master.scheduler;

import edu.snu.vortex.client.JobConf;
import edu.snu.vortex.runtime.common.RuntimeAttribute;
import edu.snu.vortex.runtime.common.plan.physical.ScheduledTaskGroup;
import edu.snu.vortex.runtime.exception.SchedulingException;
import edu.snu.vortex.runtime.master.resource.ContainerManager;
import edu.snu.vortex.runtime.master.resource.ExecutorRepresenter;
import org.apache.reef.tang.annotations.Parameter;

import javax.annotation.concurrent.ThreadSafe;
import javax.inject.Inject;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * {@inheritDoc}
 * A Round-Robin implementation used by {@link BatchScheduler}.
 *
 * This policy keeps a list of available {@link ExecutorRepresenter} for each type of container.
 * The RR policy is used for each container type when trying to schedule a task group.
 */
@ThreadSafe
public final class RoundRobinSchedulingPolicy implements SchedulingPolicy {
  private static final Logger LOG = Logger.getLogger(RoundRobinSchedulingPolicy.class.getName());

  private final ContainerManager containerManager;

  private final int scheduleTimeoutMs;

  /**
   * Thread safety is provided by this lock as multiple threads can call the methods in this class concurrently.
   */
  private final Lock lock;

  /**
   * Executor allocation is achieved by putting conditions for each container type.
   * The condition blocks when there is no executor of the container type available,
   * and is released when such an executor becomes available (either by an extra executor, or a task group completion).
   */
  private final Map<RuntimeAttribute, Condition> conditionByContainerType;

  /**
   * The pool of executors available for each container type.
   */
  private final Map<RuntimeAttribute, List<String>> executorIdByContainerType;

  /**
   * A copy of {@link ContainerManager#executorRepresenterMap}.
   * This cached copy is updated when an executor is added or removed.
   */
  private final Map<String, ExecutorRepresenter> executorRepresenterMap;

  /**
   * The index of the next executor to be assigned for each container type.
   * This map allows the executor index computation of the RR scheduling.
   */
  private final Map<RuntimeAttribute, Integer> nextExecutorIndexByContainerType;

  @Inject
  public RoundRobinSchedulingPolicy(final ContainerManager containerManager,
                                    @Parameter(JobConf.SchedulerTimeoutMs.class) final int scheduleTimeoutMs) {
    this.containerManager = containerManager;
    this.scheduleTimeoutMs = scheduleTimeoutMs;
    this.lock = new ReentrantLock();
    this.executorIdByContainerType = new HashMap<>();
    this.executorRepresenterMap = new HashMap<>();
    this.conditionByContainerType = new HashMap<>();
    this.nextExecutorIndexByContainerType = new HashMap<>();
    initializeContainerTypeIfAbsent(RuntimeAttribute.None); // Need this to avoid potential null errors
  }

  public long getScheduleTimeoutMs() {
    return scheduleTimeoutMs;
  }

  @Override
  public Optional<String> attemptSchedule(final ScheduledTaskGroup scheduledTaskGroup) {
    lock.lock();
    try {
      final RuntimeAttribute containerType = scheduledTaskGroup.getTaskGroup().getContainerType();
      initializeContainerTypeIfAbsent(containerType);

      final Optional<String> executorId = selectExecutorByRR(containerType);
      if (!executorId.isPresent()) { // If there is no available executor to schedule this task group now,
        final boolean executorAvailable =
            conditionByContainerType.get(containerType).await(scheduleTimeoutMs, TimeUnit.MILLISECONDS);
        if (executorAvailable) { // if an executor has become available before scheduleTimeoutMs,
          return selectExecutorByRR(containerType);
        } else {
          return Optional.empty();
        }
      } else {
        return executorId;
      }
    } catch (final Exception e) {
      throw new SchedulingException(e);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Sticks to the RR policy to select an executor for the next task group.
   * It checks the task groups running (as compared to each executor's capacity).
   * @param containerType to select an executor for.
   * @return (optionally) the selected executor.
   */
  private Optional<String> selectExecutorByRR(final RuntimeAttribute containerType) {
    final List<String> candidateExecutorIds = (containerType == RuntimeAttribute.None)
        ? getAllContainers() // all containers
        : executorIdByContainerType.get(containerType); // containers of a particular type

    if (candidateExecutorIds != null && !candidateExecutorIds.isEmpty()) {
      final int numExecutors = candidateExecutorIds.size();
      int nextExecutorIndex = nextExecutorIndexByContainerType.get(containerType);
      for (int i = 0; i < numExecutors; i++) {
        final int index = (nextExecutorIndex + i) % numExecutors;
        final String selectedExecutorId = candidateExecutorIds.get(index);

        final ExecutorRepresenter executor = executorRepresenterMap.get(selectedExecutorId);
        if (hasFreeSlot(executor)) {
          nextExecutorIndex = (index + 1) % numExecutors;
          nextExecutorIndexByContainerType.put(containerType, nextExecutorIndex);
          return Optional.of(selectedExecutorId);
        }
      }
    }

    return Optional.empty();
  }

  private List<String> getAllContainers() {
    return executorIdByContainerType.values().stream()
        .flatMap(List::stream) // flatten the list of lists to a flat stream
        .collect(Collectors.toList()); // convert the stream to a list
  }

  private boolean hasFreeSlot(final ExecutorRepresenter executor) {
    return executor.getRunningTaskGroups().size() < executor.getExecutorCapacity();
  }

  private void initializeContainerTypeIfAbsent(final RuntimeAttribute containerType) {
    executorIdByContainerType.putIfAbsent(containerType, new ArrayList<>());
    nextExecutorIndexByContainerType.putIfAbsent(containerType, 0);
    conditionByContainerType.putIfAbsent(containerType, lock.newCondition());
  }

  private void signalPossiblyWaitingScheduler(final RuntimeAttribute typeOfContainerWithNewFreeSlot) {
    conditionByContainerType.get(typeOfContainerWithNewFreeSlot).signal();
    if (typeOfContainerWithNewFreeSlot != RuntimeAttribute.None) {
      conditionByContainerType.get(RuntimeAttribute.None).signal();
    }
  }

  @Override
  public void onExecutorAdded(final String executorId) {
    lock.lock();
    try {
      updateCachedExecutorRepresenterMap();
      final ExecutorRepresenter executor = executorRepresenterMap.get(executorId);
      final RuntimeAttribute containerType = executor.getContainerType();
      initializeContainerTypeIfAbsent(containerType);

      executorIdByContainerType.get(containerType)
          .add(nextExecutorIndexByContainerType.get(containerType), executorId);
      signalPossiblyWaitingScheduler(containerType);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public Set<String> onExecutorRemoved(final String executorId) {
    lock.lock();
    try {
      final ExecutorRepresenter executor = executorRepresenterMap.get(executorId);
      final RuntimeAttribute containerType = executor.getContainerType();

      final List<String> executorIdList = executorIdByContainerType.get(containerType);
      int nextExecutorIndex = nextExecutorIndexByContainerType.get(containerType);

      final int executorAssignmentLocation = executorIdList.indexOf(executorId);
      if (executorAssignmentLocation < nextExecutorIndex) {
        nextExecutorIndexByContainerType.put(containerType, nextExecutorIndex - 1);
      } else if (executorAssignmentLocation == nextExecutorIndex) {
        nextExecutorIndexByContainerType.put(containerType, 0);
      }
      executorIdList.remove(executorId);

      updateCachedExecutorRepresenterMap();

      return executor.getRunningTaskGroups();
    } finally {
      lock.unlock();
    }
  }

  private void updateCachedExecutorRepresenterMap() {
    executorRepresenterMap.clear();
    executorRepresenterMap.putAll(containerManager.getExecutorRepresenterMap());
  }

  @Override
  public void onTaskGroupScheduled(final String executorId, final ScheduledTaskGroup scheduledTaskGroup) {
    lock.lock();
    try {
      final ExecutorRepresenter executor = executorRepresenterMap.get(executorId);
      LOG.log(Level.INFO, "Scheduling {0} to {1}",
          new Object[]{scheduledTaskGroup.getTaskGroup().getTaskGroupId(), executorId});
      executor.onTaskGroupScheduled(scheduledTaskGroup);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void onTaskGroupExecutionComplete(final String executorId, final String taskGroupId) {
    lock.lock();
    try {
      final ExecutorRepresenter executor = executorRepresenterMap.get(executorId);
      executor.onTaskGroupExecutionComplete(taskGroupId);
      LOG.log(Level.INFO, "Completed {" + taskGroupId + "} in [" + executorId + "]");

      // the scheduler thread may be waiting for a free slot...
      final RuntimeAttribute containerType = executor.getContainerType();
      signalPossiblyWaitingScheduler(containerType);
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void onTaskGroupExecutionFailed(final String executorId, final String taskGroupId) {
    // TODO #163: Handle Fault Tolerance
  }
}