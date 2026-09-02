package gov.nasa.jpl.parakeet.kernel

import gov.nasa.jpl.parakeet.kernel.tasks.*
import gov.nasa.jpl.parakeet.kernel.tasks.TaskStepResult.*
import gov.nasa.jpl.parakeet.utilities.andThen
import java.util.*
import java.util.Comparator.comparing
import kotlin.reflect.KType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Instant

typealias ReportHandler = (Any?) -> Unit

// TODO: Standardize constructor between this and PlanSimulation and incremental simulator
class KernelSimulator(
    private val reportHandler: ReportHandler,
    initialize: context (BasicInitScope) () -> Unit,
    incon: KernelCheckpoint? = null,
    startTime: Instant? = null,
) {
    // Use a class, not a data class, for performance.
    // Adding and removing entries from the task queue is faster when the entry uses object identity
    // rather than deep equality.
    private class TaskEntry(val time: Instant, val task: Task) {
        operator fun component1() = time
        operator fun component2() = task
        override fun toString(): String = "$task @ $time"
    }

    private var time: Instant = if (incon != null) {
        require(startTime == null || startTime == incon.time) {
            "Specified start time $startTime does not agree with incon time ${incon.time}"
        }
        incon.time
    } else {
        requireNotNull(startTime) {
            "Either a start time or an incon must be specified"
        }
    }
    private var cells: MutableList<Cell<*>> = mutableListOf()
    private val tasks: PriorityQueue<TaskEntry> = PriorityQueue(comparing(TaskEntry::time))
    private val cellListeners: MutableMap<Cell<*>, MutableSet<AwaitingTask>> = mutableMapOf()
    private val listeningTasks: MutableMap<AwaitingTask, Set<Cell<*>>> = mutableMapOf()
    private val modifiedCells: MutableSet<Cell<*>> = mutableSetOf()
    private val daemonNames: Set<Name>

    private class AwaitingTask(val await: Await) {
        var scheduledTask: TaskEntry? = null
        override fun toString(): String = "${await.rewait} -- $await"

        // Putting AwaitingTask objects into the awaitingTasks hash set is a very hot path in the simulator.
        // While it would be correct to use object identity for equality and hash code,
        // using a unique ID is faster than object identity hash functions.
        private val id = nextAwaitingTaskId++
        override fun hashCode(): Int = id
        override fun equals(other: Any?): Boolean = other is AwaitingTask && other.id == id

        companion object {
            private var nextAwaitingTaskId = 0
        }
    }
    private val awaitingTasks: MutableSet<AwaitingTask> = mutableSetOf()

    init {
        // Construct the init scope and give it back to the initializer
        val rootTasks = mutableMapOf<Name, Task>()
        val daemonsWithoutInconTasks = mutableSetOf<Name>()
        initialize(object : BasicInitScope {
            override fun <T : Any> allocate(
                name: Name,
                value: T,
                valueType: KType,
                stepBy: (T, Duration) -> T,
                mergeConcurrentEffects: (Effect<T>, Effect<T>) -> Effect<T>
            ): Cell<T> = CellImpl(
                name,
                incon?.cells?.get(name, valueType) ?: value,
                valueType,
                stepBy,
                mergeConcurrentEffects,
                lastWrittenTime = time,
            ).also<CellImpl<T>> { cells += it }

            override fun <T> read(cell: Cell<T>): T = (cell as CellImpl<T>).value

            override fun spawn(name: Name, step: PureTaskStep) {
                require(name !in rootTasks) {
                    "A task named $name has already been constructed! Please choose a unique name for every task."
                }
                val rootTask = PureTask(name, step)
                rootTasks[name] = rootTask
                daemonsWithoutInconTasks += name
            }

            override fun <T> report(value: T) = reportHandler(value)
        })
        // Save off the set of daemon names before we start trimming this set back down
        daemonNames = daemonsWithoutInconTasks.toSet()

        // Now that we've collected all the root tasks, restore all the tasks from the incon.
        incon?.tasks?.forEach { taskCheckpoint ->
            // Regardless of whether this task is complete or active, it exists in the incon.
            daemonsWithoutInconTasks -= taskCheckpoint.name
            if (taskCheckpoint.history != null) {
                // The task has history, so it's still running
                requireNotNull(taskCheckpoint.time) {
                    "Malformed task checkpoint: 'time' is missing from ${taskCheckpoint.name} but 'history' is present"
                }
                rootTasks[taskCheckpoint.root]?.restoreFrom(taskCheckpoint)?.let {
                    daemonsWithoutInconTasks -= taskCheckpoint.root
                    tasks += TaskEntry(taskCheckpoint.time, it)
                }
                // TODO: Should we warn that a saved task is being dropped if the line above produces null?
                // This happens when an incon has task data for a daemon that isn't restarted.
                // This is abnormal but not catastrophic - model updates may do this.
                // However, the remaining effects of the dropped task will not be observed, which may or may not be intentional.
            }
        }

        // If there are any tasks that didn't have an incon, they must be new daemons created by an update to the model.
        // Tolerate this and start those daemons.
        for (daemonName in daemonsWithoutInconTasks) {
            tasks += TaskEntry(time, rootTasks.getValue(daemonName))
        }
    }

    /**
     * Add a task to this simulation.
     *
     * This task will be included by [save] automatically.
     * To restore it, the caller must provide this task as part of initialization.
     */
    fun addTask(task: KernelTask) {
        tasks += TaskEntry(task.time, PureTask(task.name, task.step))
    }

    fun save(): KernelCheckpoint {
        // Tasks which are the scheduled re-evaluation or continuation of a condition should not be directly restored.
        // Instead, we should restore the listening task, and it will generate the appropriate task when it first runs.
        val excludedTasks: Set<TaskEntry> = listeningTasks.keys.mapNotNullTo(mutableSetOf()) { it.scheduledTask }
        val tasksToSave = tasks.filter { it !in excludedTasks } + listeningTasks.keys.map { TaskEntry(time, it.await.rewait) }
        val activeTaskNames: Set<Name> = tasksToSave.mapTo(mutableSetOf()) { it.task.name }
        val completedDaemonNames = daemonNames.filter { it !in activeTaskNames }
        return KernelCheckpoint(
            time,
            MutableDependentMap().also {
                for (cell in cells) {
                    it.put(cell.name, (cell as CellImpl<*>).value, cell.valueType)
                }
            },
            // Add the time for each active task we save
            (tasksToSave.map { (time, task) -> task.save().copy(time = time) }
                    // And include a marker for every completed daemon, so we don't restart them
                    + completedDaemonNames.map { KernelTaskCheckpoint(it) })
                .sortedBy { it.name }
        )
    }

    fun time() = time

    fun stepTo(endTime: Instant) {
        val batchTime = tasks.peek()?.time
        if (batchTime != null && batchTime == time && batchTime <= endTime) {
            // Collect a batch of tasks, prior to running any of those tasks,
            // since running the tasks may add new tasks that should logically come after this batch.
            val taskBatch = mutableListOf<Task>()
            while (tasks.peek()?.time == batchTime) taskBatch += tasks.remove().task
            runTaskBatch(taskBatch)
        } else {
            val stepTime = minOf(batchTime ?: endTime, endTime)
            if (time != stepTime) {
                require(stepTime >= time) {
                    "Requested step time $stepTime is earlier than current time $time"
                }
                time = stepTime
                cells.forEach { it.stepUp() }
            }
        }
    }

    private fun <T> Cell<T>.stepUp() {
        (this as CellImpl<T>).value = stepBy(lastWrittenValue, time - lastWrittenTime)
        // Stepping is *not* writing. Preserve lastWrittenValue and lastWrittenTime
        // If we step up again without an intervening write, we'll step up from lastWrittenValue.
        // This avoids accumulating numerical precision errors for complex dynamics when stepping up repeatedly.
    }

    private fun runTaskBatch(tasks: MutableList<Task>) {
        for (task in tasks) {
            runTask(task)
        }

        fun <T> Cell<T>.applyTrunkNetEffect() {
            (this as CellImpl<T>).value = trunkNetEffect!!.value ?: trunkNetEffect!!.effect(value)
            trunkNetEffect = null
            // Record the merged value as the last-written value to step up from later
            lastWrittenValue = value
            // No need to update lastWrittenTime - we're merging at least one branch at this time,
            // those would have set lastWrittenTime already
        }

        // Join the tasks' effects by applying the net effect on every modified cell.
        // Also collect all the reactions to effects made by this batch.
        // Since awaitingTasks is a set, it de-duplicates reactions automatically.
        modifiedCells.flatMapTo(awaitingTasks) {
            it.applyTrunkNetEffect()
            cellListeners[it] ?: emptySet()
        }
        modifiedCells.clear()

        // Now, evaluate all the awaiting tasks.
        // Running conditions all at once at the end of the batch avoids double-evaluation when a task
        // affects a cell and awaits a condition on that cell, as opposed to evaluating the condition when running the task.
        for (await in awaitingTasks) {
            evaluateAwaitingTask(await)
        }
        awaitingTasks.clear()
    }

    private fun runTask(task: Task) {
        val cellsModifiedByThisTask: MutableSet<Cell<*>> = mutableSetOf()

        val actions = object : BasicTaskActions {
            override fun <V> read(cell: Cell<V>): V = (cell as CellImpl<V>).value

            override fun <V> emit(cell: Cell<V>, effect: Effect<V>) {
                // Store the trunk value if this is the first write to this cell
                (cell as CellImpl<V>).trunkValue = cell.trunkValue ?: cell.value
                // Update the value by directly applying the effect
                cell.value = effect(cell.value)
                // Record the new net effect of this branch, composing with prior effects if present
                cell.branchNetEffect = cell.branchNetEffect?.andThen(effect) ?: effect
                // Record that this is the last written value, and when it was written
                cell.lastWrittenValue = cell.value
                cell.lastWrittenTime = time
                // Record that we modified this cell, so we can revert it back to trunk state later
                cellsModifiedByThisTask += cell
                // We mark the cell as modified, instead of directly adding listeners, to keep the simulation deterministic.
                // This is because a task T may await this cell in parallel with this.
                // If T is ahead of this in the batch, adding listeners would add it;
                // but if T were behind this in the batch, adding listeners would miss it.
                // By deferring the resolution from cell to listener to the end of the batch,
                // T will always be added, which is conservative and deterministic.
                modifiedCells += cell
            }

            override fun <V> report(value: V) = reportHandler(value)
        }

        // "trampoline" through the continuations of task
        // That is, iterate on nextTask - each iteration runs one step of task, then updates nextTask with the next step.
        // When there are no more steps to run now, break out of the loop and quit.
        // While a little less natural than just recurring on runTask, this avoids stack overflow on "intensive tasks",
        // tasks which run many steps without yielding.
        var nextTask = task

        while (true) {
            val stepResult = try {
                nextTask.runStep(actions)
            } catch (e: Throwable) {
                System.err.println("Error while running ${nextTask.name}: $e")
                throw e
            }
            when (stepResult) {
                is Complete -> break // Nothing to do
                is Await -> {
                    awaitingTasks += AwaitingTask(stepResult)
                    break
                }
                is Spawn -> {
                    // The spawned child will start in the next batch of tasks.
                    tasks += TaskEntry(time, stepResult.child)
                    // Spawns are handled without yielding. This allows a task to spawn exactly-concurrent sub-tasks.
                    nextTask = stepResult.continuation
                }
                is Restart -> {
                    nextTask = stepResult.continuation
                }
            }
        }

        fun <T> Cell<T>.revertBranch() {
            // Merge the branch net effect into the trunk net effect:
            (this as CellImpl<T>).trunkNetEffect = if (trunkNetEffect == null) {
                // This is the only branch to modify this cell; adopt the branch net effect and record our net value
                NetEffect(value, branchNetEffect!!)
            } else {
                // Otherwise, concurrent-merge the effects from trunk and branch, and throw away the net value.
                NetEffect(null, mergeConcurrentEffects(trunkNetEffect?.effect!!, branchNetEffect!!))
            }
            // Revert the cell value to the trunk value
            value = trunkValue!!
            // Finally, reset the branch bookkeeping variables to mark this cell in "trunk" state
            trunkValue = null
            branchNetEffect = null
        }

        // Collect the net effects of this task, and reset all cells to "trunk" state.
        for (cell in cellsModifiedByThisTask) {
            cell.revertBranch()
        }
    }

    private fun evaluateAwaitingTask(awaitingTask: AwaitingTask) {
        // Reset any listeners from any prior evaluation of this task
        resetListeners(awaitingTask)

        // Evaluate the condition, recording the cells we read along the way
        val cellsRead: MutableSet<Cell<*>> = mutableSetOf()
        val result = awaitingTask.await.condition(object : ReadActions {
            override fun <V> read(cell: Cell<V>): V {
                cellsRead += cell
                return (cell as CellImpl<V>).value
            }
        })

        when (result) {
            is SatisfiedAt -> if (result.time == ZERO) {
                // If a condition is satisfied immediately, don't bother adding listeners or listener cleanup
                tasks += TaskEntry(time, awaitingTask.await.continuation)
                // Since we're discarding the awaiting task immediately, no need to set scheduledTask either.
            } else {
                // Otherwise, schedule the task with listeners to cancel and re-eval as needed
                val entry = TaskEntry(time + result.time, conditionalTask(awaitingTask, awaitingTask.await.continuation))
                tasks += entry
                // Also register this scheduled run to be canceled, if an effect re-evaluates the condition sooner
                awaitingTask.scheduledTask = entry
                setListeners(awaitingTask, cellsRead)
            }
            is UnsatisfiedUntil -> {
                // Set listeners to re-evaluate the condition if any read cell changes
                setListeners(awaitingTask, cellsRead)
                if (result.time != INFINITE) {
                    // Schedule the rewait task to re-evaluate the condition once this unsatisfied result expires
                    val entry = TaskEntry(time + result.time, conditionalTask(awaitingTask, awaitingTask.await.rewait))
                    tasks += entry
                    // Also register this scheduled run to be canceled, if an effect re-evaluates the condition sooner
                    awaitingTask.scheduledTask = entry
                }
            }
        }
    }

    private fun conditionalTask(awaitingTask: AwaitingTask, task: Task) = object : Task by task {
        override fun runStep(actions: BasicTaskActions): TaskStepResult {
            // Remove all listeners before continuing so we don't re-trigger the condition
            resetListeners(awaitingTask)
            return task.runStep(actions)
        }
    }

    private fun setListeners(awaitingTask: AwaitingTask, cellsRead: Set<Cell<*>>) {
        // Schedule listeners to re-evaluate condition if cells change
        for (cell in cellsRead) {
            cellListeners.getOrPut(cell) { mutableSetOf() } += awaitingTask
        }
        listeningTasks[awaitingTask] = cellsRead
    }

    private fun resetListeners(awaitingTask: AwaitingTask) {
        // Reset the cells we're listening to
        listeningTasks.remove(awaitingTask)?.forEach { cellListeners[it]?.remove(awaitingTask) }
        // Remove the scheduled rewait or continuation, if it's there.
        awaitingTask.scheduledTask?.let(tasks::remove)
    }

    /**
     * Dump the state of the simulation to standard out.
     * This is triggered when the simulation is unhealthy, so normal outputs may not happen.
     * The exact format of this dump is subject to change over time.
     */
    fun dump() {
        println("${this::class.simpleName} dump:")
        println("  Simulation time: $time")
        println("  Active tasks:")
        tasks.sortedBy { it.time }.forEach { (time, task) -> println("    $time - ${task.name}") }
        println("  Waiting tasks:")
        val waitingTasks = awaitingTasks.toMutableSet()
        waitingTasks += listeningTasks.keys
        waitingTasks.forEach { println("    ${it.await.rewait.name}") }
    }
}
