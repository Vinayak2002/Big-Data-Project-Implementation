/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.scheduler

import java.io.NotSerializableException
import java.nio.ByteBuffer
import java.util.Arrays
import java.util.concurrent.ConcurrentLinkedQueue

import scala.collection.mutable.ArrayBuffer
import scala.collection.mutable.HashMap
import scala.collection.mutable.HashSet
import scala.math.{min, max}
import scala.util.control.NonFatal

import org.apache.spark._
import org.apache.spark.executor.TaskMetrics
import org.apache.spark.scheduler.SchedulingMode._
import org.apache.spark.TaskState.TaskState
import org.apache.spark.util.{Clock, SystemClock, Utils}

/**
 * Schedules the tasks within a single TaskSet in the TaskSchedulerImpl. This class keeps track of
 * each task, retries tasks if they fail (up to a limited number of times), and
 * handles locality-aware scheduling for this TaskSet via delay scheduling. The main interfaces
 * to it are resourceOffer, which asks the TaskSet whether it wants to run a task on one node,
 * and statusUpdate, which tells it that one of its tasks changed state (e.g. finished).
 *
 * THREADING: This class is designed to only be called from code with a lock on the
 * TaskScheduler (e.g. its event handlers). It should not be called from other threads.
 *
 * @param sched           the TaskSchedulerImpl associated with the TaskSetManager
 * @param taskSet         the TaskSet to manage scheduling for
 * @param maxTaskFailures if any particular task fails this number of times, the entire
 *                        task set will be aborted
 */
private[spark] class TaskSetManager(
    sched: TaskSchedulerImpl,
    val taskSet: TaskSet,
    val maxTaskFailures: Int,
    clock: Clock = new SystemClock())
  extends Schedulable with Logging {

  val conf = sched.sc.conf

  // add by cc
  var targetAlloc: Double = 0.0
  //
  var isFirst = 0
  val isFinal = taskSet.isFinal
  val jobId = taskSet.jobId
  val priority = taskSet.priority
  val isolationType = taskSet.isolationType
  val threadId = taskSet.threadId
  val estimatedRT = taskSet.estimatedRT
  val alpha = taskSet.alpha
  val Probability = taskSet.probability
  //
  var lastTaskStartTime = 0L
  var lastTaskFinishTime = 0L
  var firstTaskStartTime = 0L
  var firstTaskFinishTime = 0L
  var firstTaskRunTime = 0L
  var slotReserveDeadline = Long.MaxValue
  //
  if (isolationType == 2){
    slotReserveDeadline = 0L
  }
  //
  var currentPendingTasksNum = 0
  //
  var tidToFinishTime = new HashMap[Long, Long]

  /*
   * Sometimes if an executor is dead or in an otherwise invalid state, the driver
   * does not realize right away leading to repeated task failures. If enabled,
   * this temporarily prevents a task from re-launching on an executor where
   * it just failed.
   */
  private val EXECUTOR_TASK_BLACKLIST_TIMEOUT =
    conf.getLong("spark.scheduler.executorTaskBlacklistTime", 0L)

  // Quantile of tasks at which to start speculation
  val SPECULATION_QUANTILE = conf.getDouble("spark.speculation.quantile", 0.75)
  val SPECULATION_MULTIPLIER = conf.getDouble("spark.speculation.multiplier", 1.5)

  // Limit of bytes for total size of results (default is 1GB)
  val maxResultSize = Utils.getMaxResultSize(conf)

  // Serializer for closures and tasks.
  val env = SparkEnv.get
  val ser = env.closureSerializer.newInstance()

  val tasks = taskSet.tasks
  val numTasks = tasks.length
  val copiesRunning = new Array[Int](numTasks)
  val successful = new Array[Boolean](numTasks)
  private val numFailures = new Array[Int](numTasks)
  // key is taskId, value is a Map of executor id to when it failed
  private val failedExecutors = new HashMap[Int, HashMap[String, Long]]()

  val taskAttempts = Array.fill[List[TaskInfo]](numTasks)(Nil)
  var tasksSuccessful = 0

  var weight : Double = 1.0
  var minShare = 0
//  var priority = taskSet.priority
  var stageId = taskSet.stageId
  var name = "TaskSet_" + taskSet.stageId.toString
  var parent: Pool = null
  var totalResultSize = 0L
  var calculatedTasks = 0

  val runningTasksSet = new HashSet[Long]

  override def runningTasks: Int = runningTasksSet.size

  // True once no more tasks should be launched for this task set manager. TaskSetManagers enter
  // the zombie state once at least one attempt of each task has completed successfully, or if the
  // task set is aborted (for example, because it was killed).  TaskSetManagers remain in the zombie
  // state until all tasks have finished running; we keep TaskSetManagers that are in the zombie
  // state in order to continue to track and account for the running tasks.
  // TODO: We should kill any running task attempts when the task set manager becomes a zombie.
  var isZombie = false

  // Set of pending tasks for each executor. These collections are actually
  // treated as stacks, in which new tasks are added to the end of the
  // ArrayBuffer and removed from the end. This makes it faster to detect
  // tasks that repeatedly fail because whenever a task failed, it is put
  // back at the head of the stack. These collections may contain duplicates
  // for two reasons:
  // (1): Tasks are only removed lazily; when a task is launched, it remains
  // in all the pending lists except the one that it was launched from.
  // (2): Tasks may be re-added to these lists multiple times as a result
  // of failures.
  // Duplicates are handled in dequeueTaskFromList, which ensures that a
  // task hasn't already started running before launching it.
  private val pendingTasksForExecutor = new HashMap[String, ArrayBuffer[Int]]

  // Set of pending tasks for each host. Similar to pendingTasksForExecutor,
  // but at host level.
  private val pendingTasksForHost = new HashMap[String, ArrayBuffer[Int]]

  // Set of pending tasks for each rack -- similar to the above.
  private val pendingTasksForRack = new HashMap[String, ArrayBuffer[Int]]

  // Set containing pending tasks with no locality preferences.
  var pendingTasksWithNoPrefs = new ArrayBuffer[Int]

  // Set containing all pending tasks (also used as a stack, as above).
  val allPendingTasks = new ArrayBuffer[Int]

  // Tasks that can be speculated. Since these will be a small fraction of total
  // tasks, we'll just hold them in a HashSet.
  val speculatableTasks = new HashSet[Int]

  // Task index, start and finish time for each task attempt (indexed by task ID)
  val taskInfos = new HashMap[Long, TaskInfo]

  // How frequently to reprint duplicate exceptions in full, in milliseconds
  val EXCEPTION_PRINT_INTERVAL =
    conf.getLong("spark.logging.exceptionPrintInterval", 10000)

  // Map of recent exceptions (identified by string representation and top stack frame) to
  // duplicate count (how many times the same exception has appeared) and time the full exception
  // was printed. This should ideally be an LRU map that can drop old exceptions automatically.
  val recentExceptions = HashMap[String, (Int, Long)]()

  // Figure out the current map output tracker epoch and set it on all tasks
  val epoch = sched.mapOutputTracker.getEpoch
  logDebug("Epoch for " + taskSet + ": " + epoch)
  for (t <- tasks) {
    t.epoch = epoch
  }

  // Add all our tasks to the pending lists. We do this in reverse order
  // of task index so that tasks with low indices get launched first.
  for (i <- (0 until numTasks).reverse) {
    addPendingTask(i)
  }

  // Figure out which locality levels we have in our TaskSet, so we can do delay scheduling
  var myLocalityLevels = computeValidLocalityLevels()
  var localityWaits = myLocalityLevels.map(getLocalityWait) // Time to wait at each level

  // Delay scheduling variables: we keep track of our current locality level and the time we
  // last launched a task at that level, and move up a level when localityWaits[curLevel] expires.
  // We then move down if we manage to launch a "more local" task.
  var currentLocalityIndex = 0    // Index of our current locality level in validLocalityLevels
  var lastLaunchTime = clock.getTimeMillis()  // Time we last launched a task at this level

  override def schedulableQueue: ConcurrentLinkedQueue[Schedulable] = null

  override def schedulingMode: SchedulingMode = SchedulingMode.NONE

  var emittedTaskSizeWarning = false

  /** Add a task to all the pending-task lists that it should be on. */
  private def addPendingTask(index: Int) {
    for (loc <- tasks(index).preferredLocations) {
      loc match {
        case e: ExecutorCacheTaskLocation =>
          pendingTasksForExecutor.getOrElseUpdate(e.executorId, new ArrayBuffer) += index
        case e: HDFSCacheTaskLocation => {
          val exe = sched.getExecutorsAliveOnHost(loc.host)
          exe match {
            case Some(set) => {
              for (e <- set) {
                pendingTasksForExecutor.getOrElseUpdate(e, new ArrayBuffer) += index
              }
              logInfo(s"Pending task $index has a cached location at ${e.host} " +
                ", where there are executors " + set.mkString(","))
            }
            case None => logDebug(s"Pending task $index has a cached location at ${e.host} " +
                ", but there are no executors alive there.")
          }
        }
        case _ => Unit
      }
      pendingTasksForHost.getOrElseUpdate(loc.host, new ArrayBuffer) += index
      for (rack <- sched.getRackForHost(loc.host)) {
        pendingTasksForRack.getOrElseUpdate(rack, new ArrayBuffer) += index
      }
    }

    if (tasks(index).preferredLocations == Nil) {
      pendingTasksWithNoPrefs += index
    }

    allPendingTasks += index  // No point scanning this whole list to find the old task there

    // add by cc
    currentPendingTasksNum += 1
  }

  /**
   * Return the pending tasks list for a given executor ID, or an empty list if
   * there is no map entry for that host
   */
  private def getPendingTasksForExecutor(executorId: String): ArrayBuffer[Int] = {
    pendingTasksForExecutor.getOrElse(executorId, ArrayBuffer())
  }

  /**
   * Return the pending tasks list for a given host, or an empty list if
   * there is no map entry for that host
   */
  private def getPendingTasksForHost(host: String): ArrayBuffer[Int] = {
    pendingTasksForHost.getOrElse(host, ArrayBuffer())
  }

  /**
   * Return the pending rack-local task list for a given rack, or an empty list if
   * there is no map entry for that rack
   */
  private def getPendingTasksForRack(rack: String): ArrayBuffer[Int] = {
    pendingTasksForRack.getOrElse(rack, ArrayBuffer())
  }

  /**
   * Dequeue a pending task from the given list and return its index.
   * Return None if the list is empty.
   * This method also cleans up any tasks in the list that have already
   * been launched, since we want that to happen lazily.
   */
  private def dequeueTaskFromList(execId: String, list: ArrayBuffer[Int]): Option[Int] = {
    var indexOffset = list.size
    while (indexOffset > 0) {
      indexOffset -= 1
      val index = list(indexOffset)
      if (!executorIsBlacklisted(execId, index)) {
        // This should almost always be list.trimEnd(1) to remove tail
        list.remove(indexOffset)


        if (copiesRunning(index) == 0 && !successful(index)) {
          return Some(index)
        }
      }
    }
    None
  }

  /** Check whether a task is currently running an attempt on a given host */
  private def hasAttemptOnHost(taskIndex: Int, host: String): Boolean = {
    taskAttempts(taskIndex).exists(_.host == host)
  }

  /**
   * Is this re-execution of a failed task on an executor it already failed in before
   * EXECUTOR_TASK_BLACKLIST_TIMEOUT has elapsed ?
   */
  private def executorIsBlacklisted(execId: String, taskId: Int): Boolean = {
    if (failedExecutors.contains(taskId)) {
      val failed = failedExecutors.get(taskId).get

      return failed.contains(execId) &&
        clock.getTimeMillis() - failed.get(execId).get < EXECUTOR_TASK_BLACKLIST_TIMEOUT
    }

    false
  }

  /**
   * Return a speculative task for a given executor if any are available. The task should not have
   * an attempt running on this host, in case the host is slow. In addition, the task should meet
   * the given locality constraint.
   */
  // Labeled as protected to allow tests to override providing speculative tasks if necessary
  protected def dequeueSpeculativeTask(execId: String, host: String, locality: TaskLocality.Value)
    : Option[(Int, TaskLocality.Value)] =
  {
    speculatableTasks.retain(index => !successful(index)) // Remove finished tasks from set

    def canRunOnHost(index: Int): Boolean =
      !hasAttemptOnHost(index, host) && !executorIsBlacklisted(execId, index)

    if (!speculatableTasks.isEmpty) {
      // Check for process-local tasks; note that tasks can be process-local
      // on multiple nodes when we replicate cached blocks, as in Spark Streaming
      for (index <- speculatableTasks if canRunOnHost(index)) {
        val prefs = tasks(index).preferredLocations
        val executors = prefs.flatMap(_ match {
          case e: ExecutorCacheTaskLocation => Some(e.executorId)
          case _ => None
        });
        if (executors.contains(execId)) {
          speculatableTasks -= index
          return Some((index, TaskLocality.PROCESS_LOCAL))
        }
      }

      // Check for node-local tasks
      if (TaskLocality.isAllowed(locality, TaskLocality.NODE_LOCAL)) {
        for (index <- speculatableTasks if canRunOnHost(index)) {
          val locations = tasks(index).preferredLocations.map(_.host)
          if (locations.contains(host)) {
            speculatableTasks -= index
            return Some((index, TaskLocality.NODE_LOCAL))
          }
        }
      }

      // Check for no-preference tasks
      if (TaskLocality.isAllowed(locality, TaskLocality.NO_PREF)) {
        for (index <- speculatableTasks if canRunOnHost(index)) {
          val locations = tasks(index).preferredLocations
          if (locations.size == 0) {
            speculatableTasks -= index
            return Some((index, TaskLocality.PROCESS_LOCAL))
          }
        }
      }

      // Check for rack-local tasks
      if (TaskLocality.isAllowed(locality, TaskLocality.RACK_LOCAL)) {
        for (rack <- sched.getRackForHost(host)) {
          for (index <- speculatableTasks if canRunOnHost(index)) {
            val racks = tasks(index).preferredLocations.map(_.host).flatMap(sched.getRackForHost)
            if (racks.contains(rack)) {
              speculatableTasks -= index
              return Some((index, TaskLocality.RACK_LOCAL))
            }
          }
        }
      }

      // Check for non-local tasks
      if (TaskLocality.isAllowed(locality, TaskLocality.ANY)) {
        for (index <- speculatableTasks if canRunOnHost(index)) {
          speculatableTasks -= index
          return Some((index, TaskLocality.ANY))
        }
      }
    }

    None
  }

  /**
   * Dequeue a pending task for a given node and return its index and locality level.
   * Only search for tasks matching the given locality constraint.
   *
   * @return An option containing (task index within the task set, locality, is speculative?)
   */
  private def dequeueTask(execId: String, host: String, maxLocality: TaskLocality.Value)
    : Option[(Int, TaskLocality.Value, Boolean)] =
  {
    for (index <- dequeueTaskFromList(execId, getPendingTasksForExecutor(execId))) {
      return Some((index, TaskLocality.PROCESS_LOCAL, false))
    }

    if (TaskLocality.isAllowed(maxLocality, TaskLocality.NODE_LOCAL)) {
      for (index <- dequeueTaskFromList(execId, getPendingTasksForHost(host))) {
        return Some((index, TaskLocality.NODE_LOCAL, false))
      }
    }

    if (TaskLocality.isAllowed(maxLocality, TaskLocality.NO_PREF)) {
      // Look for noPref tasks after NODE_LOCAL for minimize cross-rack traffic
      for (index <- dequeueTaskFromList(execId, pendingTasksWithNoPrefs)) {
        return Some((index, TaskLocality.PROCESS_LOCAL, false))
      }
    }

    if (TaskLocality.isAllowed(maxLocality, TaskLocality.RACK_LOCAL)) {
      for {
        rack <- sched.getRackForHost(host)
        index <- dequeueTaskFromList(execId, getPendingTasksForRack(rack))
      } {
        return Some((index, TaskLocality.RACK_LOCAL, false))
      }
    }

    if (TaskLocality.isAllowed(maxLocality, TaskLocality.ANY)) {
      for (index <- dequeueTaskFromList(execId, allPendingTasks)) {
        return Some((index, TaskLocality.ANY, false))
      }
    }

    // find a speculative task if all others tasks have been scheduled
    dequeueSpeculativeTask(execId, host, maxLocality).map {
      case (taskIndex, allowedLocality) => (taskIndex, allowedLocality, true)}
  }

  /**
   * Respond to an offer of a single executor from the scheduler by finding a task
   *
   * NOTE: this function is either called with a maxLocality which
   * would be adjusted by delay scheduling algorithm or it will be with a special
   * NO_PREF locality which will be not modified
   *
   * @param execId the executor Id of the offered resource
   * @param host  the host Id of the offered resource
   * @param maxLocality the maximum locality we want to schedule the tasks at
   */
  @throws[TaskNotSerializableException]
  def resourceOffer(
      execId: String,
      host: String,
      maxLocality: TaskLocality.TaskLocality)
    : Option[TaskDescription] =
  {
    if (!isZombie) {
      val curTime = clock.getTimeMillis()

      var allowedLocality = maxLocality

      // change this part: don't consider locality for the first stage

      if (maxLocality != TaskLocality.NO_PREF) {
        allowedLocality = getAllowedLocalityLevel(curTime)
        if (allowedLocality > maxLocality) {
          // We're not allowed to search for farther-away tasks
          allowedLocality = maxLocality
        }
      }

      if (isFirst == 1 && isolationType == 0){
        allowedLocality = TaskLocality.ANY
        logInfo("##### set locality of first stage to ANY")
      }

      dequeueTask(execId, host, allowedLocality) match {
        case Some((index, taskLocality, speculative)) => {
          // Found a task; do some bookkeeping and return a task description
          val task = tasks(index)
          val taskId = sched.newTaskId()
          // Do various bookkeeping
          copiesRunning(index) += 1
          val attemptNum = taskAttempts(index).size
          val info = new TaskInfo(taskId, index, attemptNum, curTime,
            execId, host, taskLocality, speculative)
          taskInfos(taskId) = info
          taskAttempts(index) = info :: taskAttempts(index)
          // Update our locality level for delay scheduling
          // NO_PREF will not affect the variables related to delay scheduling
          if (maxLocality != TaskLocality.NO_PREF) {
            currentLocalityIndex = getLocalityIndex(taskLocality)
            lastLaunchTime = curTime
          }
          // Serialize and return the task
          val startTime = clock.getTimeMillis()
          val serializedTask: ByteBuffer = try {
            Task.serializeWithDependencies(task, sched.sc.addedFiles, sched.sc.addedJars, ser)
          } catch {
            // If the task cannot be serialized, then there's no point to re-attempt the task,
            // as it will always fail. So just abort the whole task-set.
            case NonFatal(e) =>
              val msg = s"Failed to serialize task $taskId, not attempting to retry it."
              logError(msg, e)
              abort(s"$msg Exception during serialization: $e")
              throw new TaskNotSerializableException(e)
          }
          if (serializedTask.limit > TaskSetManager.TASK_SIZE_TO_WARN_KB * 1024 &&
              !emittedTaskSizeWarning) {
            emittedTaskSizeWarning = true
            logWarning(s"Stage ${task.stageId} contains a task of very large size " +
              s"(${serializedTask.limit / 1024} KB). The maximum recommended task size is " +
              s"${TaskSetManager.TASK_SIZE_TO_WARN_KB} KB.")
          }
          addRunningTask(taskId)

          // We used to log the time it takes to serialize the task, but task size is already
          // a good proxy to task serialization time.
          // val timeTaken = clock.getTime() - startTime
          val taskName = s"task ${info.id} in stage ${taskSet.id}"
          logInfo(s"Starting $taskName (TID $taskId, $host, partition ${task.partitionId}," +
            s"$taskLocality, ${serializedTask.limit} bytes)")


          // add by cc, currently speculation tasks are not considered.
          if (currentPendingTasksNum == numTasks) {
            firstTaskStartTime = System.currentTimeMillis
//            logInfo("############# firstTaskStartTime: %d".format(firstTaskStartTime))
          }

          currentPendingTasksNum -= 1
          if (currentPendingTasksNum == 0) {
            lastTaskStartTime = System.currentTimeMillis
//            logInfo("############# lastTaskStartTime: %d".format(lastTaskStartTime))
            if (firstTaskFinishTime != 0){
             genDeadline()
            }
          }

          sched.dagScheduler.taskStarted(task, info)
          return Some(new TaskDescription(taskId = taskId, attemptNumber = attemptNum, execId,
            taskName, index, serializedTask))
        }
        case _ =>
      }
    }
    None
  }

  private def maybeFinishTaskSet() {
    if (isZombie && runningTasks == 0) {
      sched.taskSetFinished(this)
    }
  }

  /**
   * Get the level we can launch tasks according to delay scheduling, based on current wait time.
   */
  private def getAllowedLocalityLevel(curTime: Long): TaskLocality.TaskLocality = {
    // Remove the scheduled or finished tasks lazily
    def tasksNeedToBeScheduledFrom(pendingTaskIds: ArrayBuffer[Int]): Boolean = {
      var indexOffset = pendingTaskIds.size
      while (indexOffset > 0) {
        indexOffset -= 1
        val index = pendingTaskIds(indexOffset)
        if (copiesRunning(index) == 0 && !successful(index)) {
          return true
        } else {
          pendingTaskIds.remove(indexOffset)
        }
      }
      false
    }
    // Walk through the list of tasks that can be scheduled at each location and returns true
    // if there are any tasks that still need to be scheduled. Lazily cleans up tasks that have
    // already been scheduled.
    def moreTasksToRunIn(pendingTasks: HashMap[String, ArrayBuffer[Int]]): Boolean = {
      val emptyKeys = new ArrayBuffer[String]
      val hasTasks = pendingTasks.exists {
        case (id: String, tasks: ArrayBuffer[Int]) =>
          if (tasksNeedToBeScheduledFrom(tasks)) {
            true
          } else {
            emptyKeys += id
            false
          }
      }
      // The key could be executorId, host or rackId
      emptyKeys.foreach(id => pendingTasks.remove(id))
      hasTasks
    }

    while (currentLocalityIndex < myLocalityLevels.length - 1) {
      val moreTasks = myLocalityLevels(currentLocalityIndex) match {
        case TaskLocality.PROCESS_LOCAL => moreTasksToRunIn(pendingTasksForExecutor)
        case TaskLocality.NODE_LOCAL => moreTasksToRunIn(pendingTasksForHost)
        case TaskLocality.NO_PREF => pendingTasksWithNoPrefs.nonEmpty
        case TaskLocality.RACK_LOCAL => moreTasksToRunIn(pendingTasksForRack)
      }
      if (!moreTasks) {
        // This is a performance optimization: if there are no more tasks that can
        // be scheduled at a particular locality level, there is no point in waiting
        // for the locality wait timeout (SPARK-4939).
        lastLaunchTime = curTime
        logDebug(s"No tasks for locality level ${myLocalityLevels(currentLocalityIndex)}, " +
          s"so moving to locality level ${myLocalityLevels(currentLocalityIndex + 1)}")
        currentLocalityIndex += 1
      } else if (curTime - lastLaunchTime >= localityWaits(currentLocalityIndex)) {
        // Jump to the next locality level, and reset lastLaunchTime so that the next locality
        // wait timer doesn't immediately expire
        lastLaunchTime += localityWaits(currentLocalityIndex)
        currentLocalityIndex += 1
        logDebug(s"Moving to ${myLocalityLevels(currentLocalityIndex)} after waiting for " +
          s"${localityWaits(currentLocalityIndex)}ms")
      } else {
        return myLocalityLevels(currentLocalityIndex)
      }
    }
    myLocalityLevels(currentLocalityIndex)
  }

  /**
   * Find the index in myLocalityLevels for a given locality. This is also designed to work with
   * localities that are not in myLocalityLevels (in case we somehow get those) by returning the
   * next-biggest level we have. Uses the fact that the last value in myLocalityLevels is ANY.
   */
  def getLocalityIndex(locality: TaskLocality.TaskLocality): Int = {
    var index = 0
    while (locality > myLocalityLevels(index)) {
      index += 1
    }
    index
  }

  /**
   * Marks the task as getting result and notifies the DAG Scheduler
   */
  def handleTaskGettingResult(tid: Long): Unit = {
    val info = taskInfos(tid)
    info.markGettingResult()
    sched.dagScheduler.taskGettingResult(info)
  }

  /**
   * Check whether has enough quota to fetch the result with `size` bytes
   */
  def canFetchMoreResults(size: Long): Boolean = sched.synchronized {
    totalResultSize += size
    calculatedTasks += 1
    if (maxResultSize > 0 && totalResultSize > maxResultSize) {
      val msg = s"Total size of serialized results of ${calculatedTasks} tasks " +
        s"(${Utils.bytesToString(totalResultSize)}) is bigger than spark.driver.maxResultSize " +
        s"(${Utils.bytesToString(maxResultSize)})"
      logError(msg)
      abort(msg)
      false
    } else {
      true
    }
  }


  // add by cc
  def genDeadline() : Unit = {
    val estimatedPhaseInterval = estimatedRT
    if (lastTaskFinishTime != 0){
      if (isolationType != 2){
        slotReserveDeadline = lastTaskFinishTime + estimatedPhaseInterval
        sched.backend.reviveOffers()
//        logInfo("##### ##### ##### set slotRD from estimatedInterval: %d"
//          .format(slotReserveDeadline))
     }
    } else {
      if (isolationType == 0) {
        slotReserveDeadline = Long.MaxValue
        logInfo("##### <ssr> make reservation - stageId: " + stageId)
      } else {
        if (isolationType == 2) {
          slotReserveDeadline = 0
        } else {
          val firstTaskRunTime = firstTaskFinishTime - firstTaskStartTime
          var v1 = Math.pow(1 - Math.pow(Probability, 1.toFloat / numTasks), -1 / alpha)
//          logInfo("AAAAA AAAAA AAAAA: fTStartT: %d, lTStartT: %d, fTFinishT: %d, fTFinishT: %d"
//            .format(firstTaskStartTime%1000000, lastTaskStartTime%1000000,
//              firstTaskFinishTime%1000000, lastTaskFinishTime%1000000))
//          logInfo("AAAAA AAAAA AAAAA v1: %.2f".format(v1.toFloat))
          slotReserveDeadline = lastTaskStartTime + (firstTaskRunTime * v1).toLong
        }
      }
    }
//    slotReserveDeadline = Long.MaxValue
//    logInfo("############ get slotReserveDeadline: %d".format(slotReserveDeadline))
  }

  /**
   * Marks the task as successful and notifies the DAGScheduler that a task has ended.
   */
  def handleSuccessfulTask(tid: Long, result: DirectTaskResult[_]): Long = {
    val info = taskInfos(tid)
    val index = info.index
    info.markSuccessful()
    removeRunningTask(tid)
    // This method is called by "TaskSchedulerImpl.handleSuccessfulTask" which holds the
    // "TaskSchedulerImpl" lock until exiting. To avoid the SPARK-7655 issue, we should not
    // "deserialize" the value when holding a lock to avoid blocking other threads. So we call
    // "result.value()" in "TaskResultGetter.enqueueSuccessfulTask" before reaching here.
    // Note: "result.value()" only deserializes the value when it's called at the first time, so
    // here "result.value()" just returns the value and won't block other threads.

    // add by cc
    val currentTime = System.currentTimeMillis
    tidToFinishTime(tid) = currentTime
    var wasteInThisTaskSet = 0L

    sched.dagScheduler.taskEnded(
      tasks(index), Success, result.value(), result.accumUpdates, info, result.metrics)
    if (!successful(index)) {
      tasksSuccessful += 1

      // add by cc
      if (tasksSuccessful == 1){
        firstTaskFinishTime = currentTime
//        logInfo("############# firstTaskFinishTime: %d".format(System.currentTimeMillis))
        if (lastTaskStartTime != 0) {
          genDeadline()
        }
      }

      logInfo("Finished task %s in stage %s (TID %d) in %d ms on %s (%d/%d)".format(
        info.id, taskSet.id, info.taskId, info.duration, info.host, tasksSuccessful, numTasks))
      // Mark successful and stop if all the tasks have succeeded.
      successful(index) = true
      if (tasksSuccessful == numTasks) {
        isZombie = true
        // add by cc
        lastTaskFinishTime = currentTime
        for (keyValue <- tidToFinishTime){
          if (keyValue._2 < slotReserveDeadline){
            wasteInThisTaskSet += min(slotReserveDeadline, lastTaskFinishTime) - keyValue._2
          }
        }
        genDeadline()
      }
    } else {
      logInfo("Ignoring task-finished event for " + info.id + " in stage " + taskSet.id +
        " because task " + index + " has already completed successfully")
    }
    failedExecutors.remove(index)
    maybeFinishTaskSet()
    wasteInThisTaskSet
  }

  /**
   * Marks the task as failed, re-adds it to the list of pending tasks, and notifies the
   * DAG Scheduler.
   */
  def handleFailedTask(tid: Long, state: TaskState, reason: TaskEndReason) {
    val info = taskInfos(tid)
    if (info.failed) {
      return
    }
    removeRunningTask(tid)
    info.markFailed()
    val index = info.index
    copiesRunning(index) -= 1
    var taskMetrics : TaskMetrics = null

    val failureReason = s"Lost task ${info.id} in stage ${taskSet.id} (TID $tid, ${info.host}): " +
      reason.asInstanceOf[TaskFailedReason].toErrorString
    val failureException: Option[Throwable] = reason match {
      case fetchFailed: FetchFailed =>
        logWarning(failureReason)
        if (!successful(index)) {
          successful(index) = true
          tasksSuccessful += 1
        }
        // Not adding to failed executors for FetchFailed.
        isZombie = true
        None

      case ef: ExceptionFailure =>
        taskMetrics = ef.metrics.orNull
        if (ef.className == classOf[NotSerializableException].getName) {
          // If the task result wasn't serializable, there's no point in trying to re-execute it.
          logError("Task %s in stage %s (TID %d) had a not serializable result: %s; not retrying"
            .format(info.id, taskSet.id, tid, ef.description))
          abort("Task %s in stage %s (TID %d) had a not serializable result: %s".format(
            info.id, taskSet.id, tid, ef.description))
          return
        }
        val key = ef.description
        val now = clock.getTimeMillis()
        val (printFull, dupCount) = {
          if (recentExceptions.contains(key)) {
            val (dupCount, printTime) = recentExceptions(key)
            if (now - printTime > EXCEPTION_PRINT_INTERVAL) {
              recentExceptions(key) = (0, now)
              (true, 0)
            } else {
              recentExceptions(key) = (dupCount + 1, printTime)
              (false, dupCount + 1)
            }
          } else {
            recentExceptions(key) = (0, now)
            (true, 0)
          }
        }
        if (printFull) {
          logWarning(failureReason)
        } else {
          logInfo(
            s"Lost task ${info.id} in stage ${taskSet.id} (TID $tid) on executor ${info.host}: " +
            s"${ef.className} (${ef.description}) [duplicate $dupCount]")
        }
        ef.exception

      case e: ExecutorLostFailure if !e.exitCausedByApp =>
        logInfo(s"Task $tid failed because while it was being computed, its executor" +
          "exited for a reason unrelated to the task. Not counting this failure towards the " +
          "maximum number of failures for the task.")
        None

      case e: TaskFailedReason =>  // TaskResultLost, TaskKilled, and others
        logWarning(failureReason)
        None

      case e: TaskEndReason =>
        logError("Unknown TaskEndReason: " + e)
        None
    }
    // always add to failed executors
    failedExecutors.getOrElseUpdate(index, new HashMap[String, Long]()).
      put(info.executorId, clock.getTimeMillis())
    sched.dagScheduler.taskEnded(tasks(index), reason, null, null, info, taskMetrics)
    addPendingTask(index)
    if (!isZombie && state != TaskState.KILLED
        && reason.isInstanceOf[TaskFailedReason]
        && reason.asInstanceOf[TaskFailedReason].countTowardsTaskFailures) {
      assert (null != failureReason)
      numFailures(index) += 1
      if (numFailures(index) >= maxTaskFailures) {
        logError("Task %d in stage %s failed %d times; aborting job".format(
          index, taskSet.id, maxTaskFailures))
        abort("Task %d in stage %s failed %d times, most recent failure: %s\nDriver stacktrace:"
          .format(index, taskSet.id, maxTaskFailures, failureReason), failureException)
        return
      }
    }
    maybeFinishTaskSet()
  }

  def abort(message: String, exception: Option[Throwable] = None): Unit = sched.synchronized {
    // TODO: Kill running tasks if we were not terminated due to a Mesos error
    sched.dagScheduler.taskSetFailed(taskSet, message, exception)
    isZombie = true
    maybeFinishTaskSet()
  }

  /** If the given task ID is not in the set of running tasks, adds it.
   *
   * Used to keep track of the number of running tasks, for enforcing scheduling policies.
   */
  def addRunningTask(tid: Long) {
    if (runningTasksSet.add(tid) && parent != null) {
      parent.increaseRunningTasks(1)
    }
  }

  /** If the given task ID is in the set of running tasks, removes it. */
  def removeRunningTask(tid: Long) {
    if (runningTasksSet.remove(tid) && parent != null) {
      parent.decreaseRunningTasks(1)
    }
  }

  override def getSchedulableByName(name: String): Schedulable = {
    null
  }

  override def addSchedulable(schedulable: Schedulable) {}

  override def removeSchedulable(schedulable: Schedulable) {}

  override def getSortedTaskSetQueue(): ArrayBuffer[TaskSetManager] = {
    var sortedTaskSetQueue = new ArrayBuffer[TaskSetManager]()
    sortedTaskSetQueue += this
    sortedTaskSetQueue
  }

  /** Called by TaskScheduler when an executor is lost so we can re-enqueue our tasks */
  override def executorLost(execId: String, host: String, reason: ExecutorLossReason) {
    // Re-enqueue any tasks that ran on the failed executor if this is a shuffle map stage,
    // and we are not using an external shuffle server which could serve the shuffle outputs.
    // The reason is the next stage wouldn't be able to fetch the data from this dead executor
    // so we would need to rerun these tasks on other executors.
    if (tasks(0).isInstanceOf[ShuffleMapTask] && !env.blockManager.externalShuffleServiceEnabled) {
      for ((tid, info) <- taskInfos if info.executorId == execId) {
        val index = taskInfos(tid).index
        if (successful(index)) {
          successful(index) = false
          copiesRunning(index) -= 1
          tasksSuccessful -= 1
          addPendingTask(index)
          // Tell the DAGScheduler that this task was resubmitted so that it doesn't think our
          // stage finishes when a total of tasks.size tasks finish.
          sched.dagScheduler.taskEnded(tasks(index), Resubmitted, null, null, info, null)
        }
      }
    }
    for ((tid, info) <- taskInfos if info.running && info.executorId == execId) {
      val exitCausedByApp: Boolean = reason match {
        case exited: ExecutorExited => exited.exitCausedByApp
        case ExecutorKilled => false
        case _ => true
      }
      handleFailedTask(tid, TaskState.FAILED, ExecutorLostFailure(info.executorId, exitCausedByApp,
        Some(reason.toString)))
    }
    // recalculate valid locality levels and waits when executor is lost
    recomputeLocality()
  }

  /**
   * Check for tasks to be speculated and return true if there are any. This is called periodically
   * by the TaskScheduler.
   *
   * TODO: To make this scale to large jobs, we need to maintain a list of running tasks, so that
   * we don't scan the whole task set. It might also help to make this sorted by launch time.
   */
  override def checkSpeculatableTasks(): Boolean = {
    // Can't speculate if we only have one task, and no need to speculate if the task set is a
    // zombie.
    if (isZombie || numTasks == 1) {
      return false
    }
    var foundTasks = false
    val minFinishedForSpeculation = (SPECULATION_QUANTILE * numTasks).floor.toInt
    logDebug("Checking for speculative tasks: minFinished = " + minFinishedForSpeculation)
    if (tasksSuccessful >= minFinishedForSpeculation && tasksSuccessful > 0) {
      val time = clock.getTimeMillis()
      val durations = taskInfos.values.filter(_.successful).map(_.duration).toArray
      Arrays.sort(durations)
      val medianDuration = durations(min((0.5 * tasksSuccessful).round.toInt, durations.size - 1))
      val threshold = max(SPECULATION_MULTIPLIER * medianDuration, 100)
      // TODO: Threshold should also look at standard deviation of task durations and have a lower
      // bound based on that.
      logDebug("Task length threshold for speculation: " + threshold)
      for ((tid, info) <- taskInfos) {
        val index = info.index
        if (!successful(index) && copiesRunning(index) == 1 && info.timeRunning(time) > threshold &&
          !speculatableTasks.contains(index)) {
          logInfo(
            "Marking task %d in stage %s (on %s) as speculatable because it ran more than %.0f ms"
              .format(index, taskSet.id, info.host, threshold))
          speculatableTasks += index
          foundTasks = true
        }
      }
    }
    foundTasks
  }

  private def getLocalityWait(level: TaskLocality.TaskLocality): Long = {
    val defaultWait = conf.get("spark.locality.wait", "3s")
    val localityWaitKey = level match {
      case TaskLocality.PROCESS_LOCAL => "spark.locality.wait.process"
      case TaskLocality.NODE_LOCAL => "spark.locality.wait.node"
      case TaskLocality.RACK_LOCAL => "spark.locality.wait.rack"
      case _ => null
    }

    if (localityWaitKey != null) {
      conf.getTimeAsMs(localityWaitKey, defaultWait)
    } else {
      0L
    }
  }

  /**
   * Compute the locality levels used in this TaskSet. Assumes that all tasks have already been
   * added to queues using addPendingTask.
   *
   */
  private def computeValidLocalityLevels(): Array[TaskLocality.TaskLocality] = {
    import TaskLocality.{PROCESS_LOCAL, NODE_LOCAL, NO_PREF, RACK_LOCAL, ANY}
    val levels = new ArrayBuffer[TaskLocality.TaskLocality]
    if (!pendingTasksForExecutor.isEmpty && getLocalityWait(PROCESS_LOCAL) != 0 &&
        pendingTasksForExecutor.keySet.exists(sched.isExecutorAlive(_))) {
      levels += PROCESS_LOCAL
    }
    if (!pendingTasksForHost.isEmpty && getLocalityWait(NODE_LOCAL) != 0 &&
        pendingTasksForHost.keySet.exists(sched.hasExecutorsAliveOnHost(_))) {
      levels += NODE_LOCAL
    }
    if (!pendingTasksWithNoPrefs.isEmpty) {
      levels += NO_PREF
    }
    if (!pendingTasksForRack.isEmpty && getLocalityWait(RACK_LOCAL) != 0 &&
        pendingTasksForRack.keySet.exists(sched.hasHostAliveOnRack(_))) {
      levels += RACK_LOCAL
    }
    levels += ANY
    logDebug("Valid locality levels for " + taskSet + ": " + levels.mkString(", "))
    levels.toArray
  }

  def recomputeLocality() {
    val previousLocalityLevel = myLocalityLevels(currentLocalityIndex)
    myLocalityLevels = computeValidLocalityLevels()
    localityWaits = myLocalityLevels.map(getLocalityWait)
    currentLocalityIndex = getLocalityIndex(previousLocalityLevel)
  }

  def executorAdded() {
    recomputeLocality()
  }
}

private[spark] object TaskSetManager {
  // The user will be warned if any stages contain a task that has a serialized size greater than
  // this.
  val TASK_SIZE_TO_WARN_KB = 100
}
