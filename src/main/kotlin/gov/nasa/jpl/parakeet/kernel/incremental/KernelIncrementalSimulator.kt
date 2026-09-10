package gov.nasa.jpl.parakeet.kernel.incremental

import gov.nasa.jpl.parakeet.kernel.incremental.IncSimNode.*
import gov.nasa.jpl.parakeet.kernel.BasicInitScope
import gov.nasa.jpl.parakeet.kernel.Cell
import gov.nasa.jpl.parakeet.kernel.Effect
import gov.nasa.jpl.parakeet.kernel.KernelCheckpoint
import gov.nasa.jpl.parakeet.kernel.KernelTaskCheckpoint
import gov.nasa.jpl.parakeet.kernel.MutableDependentMap
import gov.nasa.jpl.parakeet.kernel.Name
import gov.nasa.jpl.parakeet.kernel.ReadActions
import gov.nasa.jpl.parakeet.kernel.SatisfiedAt
import gov.nasa.jpl.parakeet.kernel.incremental.IncSimNodeOperations.awaitGroup
import gov.nasa.jpl.parakeet.kernel.incremental.IncSimNodeOperations.priorNodes
import gov.nasa.jpl.parakeet.kernel.incremental.IncSimNodeOperations.thisAndNextNodes
import gov.nasa.jpl.parakeet.kernel.incremental.IncSimNodeOperations.thisAndPriorNodes
import gov.nasa.jpl.parakeet.kernel.incremental.KernelIncrementalSimulator.FrontierAction.*
import gov.nasa.jpl.parakeet.kernel.incremental.SimulationTimeOperations.batchStart
import gov.nasa.jpl.parakeet.kernel.incremental.SimulationTimeOperations.cellSteppingBatch
import gov.nasa.jpl.parakeet.kernel.incremental.SimulationTimeOperations.isCausallyAfter
import gov.nasa.jpl.parakeet.kernel.incremental.SimulationTimeOperations.isCausallyBefore
import gov.nasa.jpl.parakeet.kernel.incremental.SimulationTimeOperations.isConcurrentWith
import gov.nasa.jpl.parakeet.kernel.incremental.SimulationTimeOperations.nextCellBatch
import gov.nasa.jpl.parakeet.kernel.incremental.SimulationTimeOperations.nextStep
import gov.nasa.jpl.parakeet.kernel.incremental.SimulationTimeOperations.nextTaskBatch
import gov.nasa.jpl.parakeet.kernel.incremental.SimulationTimeOperations.sameBatchAs
import gov.nasa.jpl.parakeet.kernel.incremental.SimulationTimeOperations.sameBranchAs
import gov.nasa.jpl.parakeet.kernel.tasks.BasicTaskActions
import gov.nasa.jpl.parakeet.kernel.tasks.KernelTask
import gov.nasa.jpl.parakeet.kernel.tasks.PureTask
import gov.nasa.jpl.parakeet.kernel.tasks.PureTaskStep
import gov.nasa.jpl.parakeet.kernel.tasks.Task
import gov.nasa.jpl.parakeet.kernel.tasks.TaskStepResult
import gov.nasa.jpl.parakeet.utilities.identity
import java.io.File
import java.util.PriorityQueue
import java.util.TreeMap
import java.util.TreeSet
import kotlin.collections.iterator
import kotlin.collections.minusAssign
import kotlin.collections.plusAssign
import kotlin.collections.set
import kotlin.reflect.KType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Instant
import kotlin.time.Instant.Companion.DISTANT_FUTURE
import kotlin.time.Instant.Companion.DISTANT_PAST

/**
 * Provides kernel-level incremental simulation with parity to [gov.nasa.jpl.parakeet.kernel.KernelSimulator]
 */
class KernelIncrementalSimulator(
    private val planStart: Instant,
    // TODO: Everything is ready to make planEnd mutable, I think. We just need a changeEndTime() method.
    private val planEnd: Instant,
    constructPlan: context (BasicInitScope) () -> List<KernelTask>,
    private val reportHandler: IncrementalReportHandler,
    incon: KernelCheckpoint? = null,
) {
    private var nextNodeId: Int = 0
    /** All cell nodes allocated in the DAG */
    private val cellNodes: MutableMap<Cell<*>, TreeMap<SimulationTime, CellNode<*>>> = mutableMapOf()
    /** The root of each task branch, keyed by time. */
    private val branchRoots: TreeMap<SimulationTime, TaskBranch> = TreeMap()
    // TODO: Add bookkeeping info like the end time for the branch, to improve performance
    private class TaskBranch(val start: StartTaskNode)
    /** A list of branch numbers which are no longer being used, and can safely be re-used. */
    private val recycledBranchNumbers: MutableList<Int> = mutableListOf()
    /** A list of daemons that were complete in the incon, tracked only to report them as complete when saving. */
    private val initiallyCompletedDaemons: MutableList<KernelTaskCheckpoint> = mutableListOf()
    /** The work list of actions to resolve the DAG. */
    private val frontier: TreeSet<FrontierAction> = TreeSet(
            // Primarily sort frontier actions by time, as incremental re-work is most efficient when done in time order.
        compareBy<FrontierAction>(FrontierAction::time)
            // Secondarily assign a priority to each kind of action, to allow different actions on a single node,
            // while de-duplicating instances of the same action on the same node.
            .thenBy {
                when (it) {
                    // RevokeMergeOpportunity is highest priority.
                    // When this action is present on a node, any other action would not be sensible.
                    is RevokeMergeOpportunity -> 1
                    // The relative priority of CheckCell, CheckCondition, and RunTask shouldn't matter,
                    // as these should be attached to mutually exclusive kinds of nodes: cell, await, and non-await tasks, respectively.
                    is CheckCell -> 2
                    is CheckCondition -> 3
                    is RunTask -> 4
                }
            }
            // Finally, use the serial ID to permit the same action at the same time on different nodes.
            // This doesn't happen in a well-formed DAG, but has been observed ephemerally while rewriting the DAG.
            .thenBy { it.node.serialId }
    )

    /** Root task nodes corresponding to activities in the plan, recorded to facilitate revoking tasks. */
    private val planTaskNodes: MutableMap<KernelTask, StartTaskNode> = mutableMapOf()
    /** Root nodes with which we may merge restart requests, rather than re-running. */
    private val rootMergeOpportunities: MutableMap<Name, StartTaskNode> = mutableMapOf()

    private enum class DebugLevel { NONE, MAJOR, MINOR, ALL }
    companion object {
        private val DEBUG = DebugLevel.NONE
        // Put step variables in companion object, so the numbers keep incrementing through save/restore cycles
        private var debugMajorStep = 0
        private var debugMinorStep = 0
    }
    private fun dumpDotToFile(debugLevel: DebugLevel, highlightNode: IncSimNode? = null, checkIntegrity: Boolean = true) {
        if (DEBUG >= debugLevel) {
            if (debugLevel <= DebugLevel.MAJOR) {
                ++debugMajorStep
                debugMinorStep = 0
            } else if (debugLevel <= DebugLevel.MINOR) {
                ++debugMinorStep
            }

            File("inc-sim-debug/tmp" +
                    debugMajorStep.toString().padStart(4, '0') +
                    "." +
                    debugMinorStep.toString().padStart(4, '0') +
                    ".dot"
            ).writeText(dumpDot(highlightNode = highlightNode, checkIntegrity = checkIntegrity))
        }
    }

    init {
        // Init happens before any tasks, at plan start.
        var cellAllocTime = SimulationTime(planStart).cellSteppingBatch()
        // Put initial reports on a different branch from cell allocation.
        // This shouldn't be strictly necessary, but it makes debugging easier by giving everything a unique time.
        val firstReportTime = cellAllocTime.copy(branch = 1)
        val startTime = cellAllocTime.nextTaskBatch()
        var lastReport: ReportNode<*>? = null
        val rootTasks = mutableMapOf<Name, Task>()

        val basicInitScope = object : BasicInitScope {
            override fun <T : Any> allocate(
                name: Name,
                value: T,
                valueType: KType,
                stepBy: (T, Duration) -> T,
                applyConcurrentEffects: (List<Effect<T>>, T) -> T,
            ): Cell<T> =
                // Create an incremental cell, and add its initial value to the graph
                IncrementalCellImpl(name, valueType, stepBy, applyConcurrentEffects).also {
                    cellNodes[it] = TreeMap<SimulationTime, CellNode<*>>().apply {
                        put(cellAllocTime, CellWriteNode(
                            nextNodeId++,
                            cellAllocTime,
                            it,
                            incon?.cells?.get(name, valueType) ?: value,
                            null,
                            identity()))
                        // Advance the cell allocation time so that each initial cell write node has a unique time
                        cellAllocTime = cellAllocTime.nextStep()
                    }
                }

            override fun spawn(name: Name, step: PureTaskStep) {
                // Record this task, but defer adding it to the graph until later.
                // We may want to replay tasks from the incon instead, and we should wait until the model is fully constructed
                // to do so, lest the replay access something not-yet-initialized and error out.
                rootTasks[name] = PureTask(name, step)
            }

            override fun <T> read(cell: Cell<T>): T {
                // Since there cannot be effects during init,
                // we may safely assume the first node to be the write node added during allocation
                return (getCellNodes(cell).firstEntry().value as CellWriteNode<T>).value
            }

            override fun <T> report(value: T) {
                lastReport = ReportNode(
                    nextNodeId++,
                    // There's not really a task associated with this report, so just choose a name arbitrarily.
                    Name("initialization"),
                    lastReport?.time?.nextStep() ?: firstReportTime,
                    lastReport,
                    value
                ).also { lastReport?.next = it }
                reportHandler.report(lastReport)
            }
        }
        val activities = constructPlan(basicInitScope)
        incon?.tasks?.groupBy { it.root }?.forEach { (rootName, taskCheckpoints) ->
            // Remove rootTasks that appear in the incon.
            val rootTask = rootTasks.remove(rootName)
            if (rootTask != null) {
                for (taskCheckpoint in taskCheckpoints) {
                    if (taskCheckpoint.history != null) {
                        // The task has history, so it's still running
                        requireNotNull(taskCheckpoint.time) {
                            "Malformed task checkpoint: 'time' is missing from ${taskCheckpoint.name} but 'history' is present"
                        }
                        // Use the rootTask to restore the checkpoint, then schedule it at the appropriate time.
                        // In order to make the StartTaskNode re-runnable, we have to build a Task wrapper
                        // which can manage loading and unloading the restored task as its used.
                        val restoringRoot = object : Task {
                            private var _base: Task? = null
                            private val base: Task get() {
                                if (_base == null) _base = rootTask.restoreFrom(taskCheckpoint)
                                return _base!!
                            }

                            // Name and rootTask can just be saved from the first time we restore the base
                            override val name: Name = base.name
                            override val rootTaskName: Name = rootName
                            // Running the task unloads base. The next time we do something with it, we'll re-restore it.
                            override fun runStep(actions: BasicTaskActions) =
                                base.runStep(actions).also { _base = null }
                            // Using the base to save a checkpoint does not require unloading it.
                            override fun save() = base.save()
                            // Using it to restore from a checkpoint might require unloading it?
                            // This isn't a workflow that can happen in single-shot sim, so I'm not sure how this works.
                            override fun restoreFrom(checkpoint: KernelTaskCheckpoint) =
                                base.restoreFrom(checkpoint).also { _base = null }
                        }
                        frontier += RunTask(restoringRoot.branchAt(SimulationTime(taskCheckpoint.time)))
                    } else {
                        // The task does not have history, it is stopped.
                        // Record it so we can save it later; otherwise there's nothing to do with it.
                        initiallyCompletedDaemons += taskCheckpoint
                    }
                }
            }
            // TODO: Should there be warning of dropping incon tasks without corresponding model tasks to restore them?
        }
        // Root tasks not accounted for by the incon should be started fresh.
        // These are presumably daemons added by a model update or something.
        for (task in rootTasks.values) {
            frontier += RunTask(task.branchAt(startTime))
        }
        dumpDotToFile(DebugLevel.MAJOR)
        run(KernelPlanEdits(additions = activities))
    }

    fun run(planEdits: KernelPlanEdits) {
        planEdits.removals.forEach {
            val rootTaskNode = requireNotNull(planTaskNodes.remove(it)) {
                "Activity $it not found in the plan."
            }
            fullyRevokeTask(rootTaskNode)
        }
        for (activity in planEdits.additions) {
            // We can't go into the past
            require(activity.time >= planStart) {
                "Cannot add activity $activity before plan starts at $planStart"
            }
            // Adding an activity in the future, after this sim ends, is fine. It'll just be a task we don't start (yet).
            val task = PureTask(activity.name, activity.step)
            frontier += RunTask(
                task.branchAt(SimulationTime(activity.time)).also {
                    planTaskNodes[activity] = it
                }
            )
        }
        try {
            resolve()
        } finally {
            // Disable the integrity check on this run, so we can get a final graph even if it's invalid.
            dumpDotToFile(DebugLevel.MAJOR, checkIntegrity = false)
        }
    }

    fun save(time: Instant): KernelCheckpoint {
        require(time in planStart..planEnd) {
            "Cannot save checkpoint at $time outside of plan bounds $planStart to $planEnd"
        }
        val simulationTime = SimulationTime(time)

        // Save the cell values by looking up an appropriate cell node for each cell
        val cellCheckpoint = MutableDependentMap()
        @Suppress("UNCHECKED_CAST")
        fun <T> CellNode<T>.save() {
            // Stepping many small increments can accrue numerical error in complex dynamics.
            // Walk back to our first non-step-node ancestor and step from there instead.
            var baseNode = this
            while (baseNode is CellStepNode) baseNode = baseNode.prior
            val value = cell.stepBy(baseNode.value, time - baseNode.time.instant)
            cellCheckpoint.put(cell.name, value, cell.valueType)
        }
        cellNodes.values.forEach {
            it.floorEntry(simulationTime).value.save()
        }

        // Save running tasks by finding the tip of that branch and saving an appropriate checkpoint
        val runningTaskCheckpoints = branchRoots.subMap(SimulationTime(DISTANT_PAST), simulationTime).values.mapNotNull { branch ->
            // branch starts before simulationTime, so it must have a last node before simulationTime.
            val tip = branch.start.thisAndNextNodes().takeWhile { it.time < simulationTime }.last()
            if (tip is TaskCompleteNode) {
                // This branch has completed.
                val branchStart = branch.start.loadContinuation()
                if (branchStart.name == branchStart.rootTaskName) {
                    // This task is its own root task. That makes it a daemon spawned directly from the model.
                    // We must save a "completed" checkpoint for it, to indicate not to restart it when restoring.
                    KernelTaskCheckpoint(branchStart.name, branchStart.rootTaskName)
                } else {
                    // This task was spawned by another; no task checkpoint is required.
                    null
                }
            } else {
                // This branch has not completed. It must have yielded instead.
                check(tip is YieldingStepNode) {
                    "Internal error! Task node for ${tip.taskName} at $simulationTime is not yielding."
                }
                if (tip is AwaitNode) {
                    // AwaitNodes use the rewait, not the continuation, to save history.
                    // Additionally, we use the sim checkpoint time, not the node time.
                    tip.rewait.save().copy(time = time)
                } else {
                    // In general, load the continuation, and ask it to save a task checkpoint.
                    tip.loadContinuation().save().copy(time = tip.time.instant)
                }
            }
        }
        // Also save root tasks which haven't started yet
        val notStartedTaskCheckpoints = branchRoots.subMap(simulationTime, SimulationTime(DISTANT_FUTURE))
            .values
            // Filter down to root tasks - branches that are spawned in the future by other tasks shouldn't be directly saved.
            .filter { it.start.prior == null }
            .map { it.start.loadContinuation().save().copy(time = it.start.time.instant) }
        // Finally, include root tasks which were already complete in the incon
        return KernelCheckpoint(time, cellCheckpoint, runningTaskCheckpoints + notStartedTaskCheckpoints + initiallyCompletedDaemons)
    }

    private fun resolve() {
        // Only work up to the end of this simulation
        while (frontier.firstOrNull()?.time?.instant?.let { it < planEnd } ?: false) {
            dumpDotToFile(DebugLevel.MAJOR, frontier.firstOrNull()?.node)
            when (val action = frontier.removeFirst()) {
                is RunTask -> {
                    // We're about to run the task starting from action.node.
                    // If it has a next, revoke next to re-do it.
                    action.node.next?.let { revokeWithMergeOpportunity(it) }
                    // Invariant: All non-root task nodes are, or are preceded by, a yielding task node.
                    val startFrom = action.node.thisAndPriorNodes().first { it is YieldingStepNode } as YieldingStepNode
                    // Continue the task graph from task tip
                    action.node.continueWith { realActions ->
                        // But do so by running from the most recent yielding action.
                        // Use replaying actions from startFrom through taskTip, then switch to realActions
                        // to start constructing graph nodes from that point forward.
                        startFrom.runContinuation(startFrom.replayingTaskActions(realActions))
                    }
                }
                is RevokeMergeOpportunity -> {
                    // We use the RevokeMergeOpportunity frontier action instead of directly calling fullyRevokeTask
                    // when we're doing incremental resimulation, hoping to merge an existing RootTaskNode
                    // with a re-simulated one.
                    // For example, the reporting task for a resource repeats, placing a new root exactly where the old
                    // root was, and that can be merged instead of re-done.
                    // If we merge, we remove the RevokeMergeOpportunity action.
                    // Hence if we run the RevokeMergeOpportunity action, we failed to merge this root.
                    // Remove that root, but optimistically add its downstream repeat as a merge opportunity.
                    rootMergeOpportunities.remove(action.node.taskName)
                    revokeWithMergeOpportunity(action.node)
                }
                is CheckCell -> checkCell(action.node)
                is CheckCondition -> checkCondition(action.node)
            }
        }
    }

    private fun <T> checkCell(node: CellNode<T>) {
        val computedValue = when (node) {
            is CellMergeNode<T> -> {
                // Find the node at the end of the prior batch, which will be the start of this batch
                val firstBranchTip = node.prior.first()
                val batchStartTime = firstBranchTip.time.batchStart()
                val batchStart = generateSequence<CellNode<T>>(firstBranchTip) { (it as CellWriteNode).prior }
                    // It's safe to use lexical order instead of the more expensive causal order here,
                    // because batchStartTime has branch and step set to 0; causal and lexical "less than" agree.
                    // Further, such a node must exist because initial writes cannot participate in a merge.
                    .first { it.time < batchStartTime }
                // Roll up the net effect of each branch, and apply them all together to compute the value this node should have
                node.cell.applyConcurrentEffects(node.prior.map { it.branchNetEffect(batchStart) }, batchStart.value)
            }
            is CellStepNode<T> -> {
                // Stepping many small increments can accrue numerical error in complex dynamics.
                // Walk back to our first non-step-node ancestor and step from there instead.
                var baseNode = node.prior
                while (baseNode is CellStepNode) baseNode = baseNode.prior
                node.cell.stepBy(baseNode.value, node.time.instant - baseNode.time.instant)
            }
            is CellWriteNode<T> -> {
                // If we have a prior node, apply the effect to the prior cell value.
                // Otherwise, this node is initial; just keep its value intact.
                node.prior?.let { node.effect(it.value) } ?: node.value
            }
        }
        // If the value actually changed, update the value and check all next cell nodes to propagate the change
        if (computedValue != node.value) {
            node.value = computedValue
            checkCellsAfter(node)
        }
        // Regardless of whether the value changed, check all reads and awaiters.
        // If the value they last saw for this cell differs from the value it has now, run it.
        for (read in node.reads) {
            if (read.value != node.value) {
                frontier += RunTask(checkNotNull(read.prior) {
                    "Internal error! Task read node not preceded by another task node."
                })
            }
        }
        // Awaiters get a CheckCondition action instead of a RunTask action.
        // That way, if the condition evaluates equivalently for the new cell value,
        // we don't needlessly re-run a task.
        for (awaiter in node.awaiters) {
            if (awaiter.reads.getValue(node) != node.value) {
                frontier += CheckCondition(awaiter)
            }
        }

        // If this cell node is a step node, isn't directly read by anything, and is followed by another step node,
        // then it's redundant. Remove such nodes to simplify the DAG.
        if (node is CellStepNode
            && node.reads.isEmpty()
            && node.awaiters.isEmpty()
            && node.next.all { it is CellStepNode }) {
            revokeCell(node)
        }
    }

    private fun <T> checkCellsAfter(cell: CellNode<T>) {
        // Subtle changes to dynamics may result in identical dynamics at small steps in the future, which then diverge again.
        // For the highest-precision simulation, check all consecutive step nodes and one non-step node after cell.
        val nodesToCheck = cell.next.toMutableList()
        while (true) {
            val nodeToCheck = nodesToCheck.removeLastOrNull() ?: break
            frontier += CheckCell(nodeToCheck)
            if (nodeToCheck is CellStepNode) {
                nodesToCheck += nodeToCheck.next
            }
        }
    }

    private fun checkCondition(awaitNode: AwaitNode) {
        // Clear any read edges from a prior check
        for (read in awaitNode.reads.keys) {
            read.awaiters -= awaitNode
        }
        awaitNode.reads.clear()
        // Then set up a ReadActions to record the new read edges
        val readActions = object : ReadActions {
            override fun <V> read(cell: Cell<V>): V {
                // Get the appropriate cell node to read:
                val cellNode = getCellNode(cell, awaitNode.time)
                // Record bidirectionally that this read happened:
                cellNode.awaiters += awaitNode
                awaitNode.reads[cellNode] = cellNode.value
                // Finally, return the value we read
                return cellNode.value
            }
        }
        // Evaluate the condition
        val result = awaitNode.condition(readActions)
        // Compute when to schedule the next step, assuming no cell writes interrupt the await
        var resultTime = result.time.takeIf { it.isFinite() }?.let {
            if (it > ZERO) {
                SimulationTime(
                    awaitNode.time.instant + it,
                    branch = awaitNode.time.branch)
            } else {
                // The await node is first scheduled in the next task batch from the awaiting task step.
                // If interrupted, it is scheduled in the next task batch after the interrupting write.
                // This mirrors how awaiters are first checked after the batch that set them,
                // and also checked after the batch that interrupts them.
                // If satisfied, these awaits should continue in the next step after they're checked, not the next batch.
                awaitNode.time.nextStep()
            }
        }
        var isSatisfied = result is SatisfiedAt

        // Look for interruptions to this await
        var interruptCell: CellNode<*>? = null
        val frontierCellNodes = PriorityQueue<CellNode<*>>(compareBy { it.time })
        // Start exploring all cell nodes after the read cell nodes.
        awaitNode.reads.keys.flatMapTo(frontierCellNodes) { it.next }
        while (true) {
            // Only explore until resultTime
            val node = frontierCellNodes.poll()
                ?.takeIf { resultTime == null || it.time isCausallyBefore resultTime }
                ?: break
            when (node) {
                is CellStepNode -> {
                    // Step nodes don't interrupt an await, so also explore its next nodes
                    frontierCellNodes += node.next
                }
                is CellMergeNode, is CellWriteNode -> {
                    if (node is CellWriteNode) {
                        val writer = checkNotNull(node.writer) {
                            "Internal error! Cell write node not linked to a task write node"
                        }
                        // Edge case: if this is a write by the awaiting task itself, it doesn't count.
                        // This can happen when a task is being re-evaluated.
                        // A version of this task we're probably about to revoke writes to this cell in the future.
                        // Detect this edge case by scanning back along the task nodes, looking for awaitNode.
                        // Since we have unambiguous times, we only have to look as far back as awaitNode in time, and only to compare the one node.
                        if (writer.priorNodes().firstOrNull { it.time <= awaitNode.time } === awaitNode) {
                            // When we hit this edge case, continue scanning forward as though we haven't hit it.
                            frontierCellNodes += node.next
                            continue
                        }
                    }
                    // In the general case, any write or merge node interrupts the await,
                    // causing re-evaluation in response to the write.
                    // That's equivalent to just being unsatisfied until the time of this write.
                    isSatisfied = false
                    // Set time to the task step reacting to the cell node.
                    // Note that if there are concurrent cell writes, all of them have the same
                    // nextTaskBatch, so it suffices to find any one of them and stop.
                    resultTime = node.time.nextTaskBatch().copy(branch = awaitNode.time.branch)
                    // In case we're recomputing an await that was already interrupted by a merge,
                    // we should fast-forward the interruption to the last cell node causally before resultTime.
                    // (It must be a causal check in case there's a concurrent write on a lower-numbered branch).
                    // That's the cell node any existing await node would have read,
                    // which avoids adding an extraneous read into the interruption await node.
                    // Having two reads of one cell (through different cell nodes) isn't just wasteful.
                    // It can trip up the cell awaiter update logic and cause incorrect results.
                    interruptCell = generateSequence(node) {
                        (it as? CellWriteNode)?.next?.singleOrNull()?.takeIf { it.time isCausallyBefore resultTime }
                    }.last()
                    break
                }
            }
        }

        awaitNode.next?.let {
            // If we have a next node, and we shouldn't, revoke it.
            val correctType = if (isSatisfied) it is AwaitCompleteNode else it is AwaitNode
            if (resultTime == null || !correctType || it.time != resultTime) {
                // TODO: There may be an opportunity to improve performance, through "await-repair"
                //   Instead of fully revoking the rest of this task run, we could check if any await
                //   in the await-chain of awaitNode is correct, and only revoke the await nodes up to that point.
                //   If the condition winds up evaluating identically, we don't need to re-run the task.
                revokeWithMergeOpportunity(it)
            }
        }

        // If we want a next node and still have one, it passed the filter above, so just re-use it.
        // Otherwise, if we want a next node but don't have one, build it.
        if (resultTime != null && awaitNode.next == null) {
            awaitNode.next = if (isSatisfied) {
                AwaitCompleteNode(
                    nextNodeId++,
                    awaitNode.taskName,
                    resultTime,
                    awaitNode,
                    awaitNode.continuation,
                ).also {
                    frontier += RunTask(it)
                }
            } else {
                AwaitNode(
                    nextNodeId++,
                    awaitNode.taskName,
                    resultTime,
                    awaitNode,
                    awaitNode.condition,
                    rewait = awaitNode.rewait,
                    continuation = awaitNode.continuation
                ).also {
                    frontier += CheckCondition(it)
                }
            }
        }

        // If we have an interrupt, record that read edge.
        // If the interrupting cell is revoked, this edge will remind us to re-run the original await.
        if (interruptCell != null) {
            (awaitNode.next as AwaitNode).reads.computeIfAbsent(interruptCell) { it.value }
            interruptCell.awaiters += awaitNode.next as AwaitNode
        }
    }

    /** Construct a new root task node, and assign a new branch number to it. */
    private fun Task.branchAt(time: SimulationTime): StartTaskNode {
        // Grab a recycled branch number if available, or else assign a new branch number.
        // When a branch number is assigned, it exists in either taskBranch or recycledBranchNumbers.
        // If recycledBranchNumbers is empty, all and only 0..<taskBranch.size are assigned branch numbers.
        val branchNumber = recycledBranchNumbers.removeLastOrNull() ?: branchRoots.size
        return StartTaskNode(
            nextNodeId++,
            time.copy(branch = branchNumber),
            this
        ).also {
            branchRoots[it.time] = TaskBranch(it)
        }
    }

    private fun <T> CellWriteNode<T>.branchNetEffect(batchStart: CellNode<T>): Effect<T> {
        // The branch comprises all our prior write nodes until batchStart
        val effects = generateSequence(this) { it.prior?.takeUnless { it === batchStart } as CellWriteNode<T>? }
            .map { it.effect }
            .toList()
            // Branch nodes are initially collected in reverse order, so use a reverse view to apply them
            .asReversed()
        return {
            var result = it
            for (effect in effects) {
                result = effect(result)
            }
            result
        }
    }

    /**
     * Run [continuation], appending nodes after this [TaskNode] to record its actions.
     * Mutates the simulator as needed to record all consequences of continuing this task.
     */
    private fun TaskNode.continueWith(continuation: (BasicTaskActions) -> TaskStepResult) {
        // Expand this task node
        var lastTaskStepNode: TaskNode = this
        // We can merge only if this action is happening on the same branch as the merge opportunity.
        val mergeOpportunity: StartTaskNode? = rootMergeOpportunities[taskName]?.takeIf { it sameBranchAs this }
        val basicTaskActions = object : BasicTaskActions {
            override fun <V> read(cell: Cell<V>): V {
                // Look up the cell node
                val cellNode = getCellNode(cell, lastTaskStepNode.time)
                // Record this read in the graph
                lastTaskStepNode = ReadNode(
                    nextNodeId++,
                    lastTaskStepNode.taskName,
                    lastTaskStepNode.time.nextStep(),
                    lastTaskStepNode,
                    cellNode,
                    cellNode.value
                ).also {
                    lastTaskStepNode.next = it
                    cellNode.reads += it
                }
                dumpDotToFile(DebugLevel.MINOR, lastTaskStepNode)
                // Return the value
                return cellNode.value
            }

            override fun <V> emit(cell: Cell<V>, effect: Effect<V>) {
                // Look up the cell node we're writing to
                val writeTime = lastTaskStepNode.time.nextStep()
                val priorCellNode = getCellNode(cell, writeTime)
                val writeNode = CellWriteNode(
                    nextNodeId++,
                    writeTime,
                    cell,
                    effect(priorCellNode.value),
                    priorCellNode,
                    effect,
                )
                getCellNodes(cell)[writeNode.time] = writeNode
                // Migrate any readers and awaiters that need it, from prior to write
                migrateDependentTasks(priorCellNode, writeNode)
                // Schedule the write node to be checked
                frontier += CheckCell(writeNode)

                // Deal with any branching that may have happened.
                when (priorCellNode.next.size) {
                    0 -> {
                        // No additional links need to be changed, we're at the edge of the graph.
                    }
                    1 -> {
                        val adjacentCellNode = priorCellNode.next.single()
                        if (adjacentCellNode isConcurrentWith writeNode) {
                            // There's exactly one branch, and it's concurrent with this write node.
                            // Follow the concurrent branch to its tip
                            val concurrentTip = generateSequence(adjacentCellNode) { it.next.singleOrNull() }
                                .first { it.next.singleOrNull()?.let { it isCausallyAfter writeNode } ?: true }
                            // Then build and insert a merge node at the tip of that branch
                            val mergeNode = CellMergeNode(
                                nextNodeId++,
                                writeTime.nextCellBatch().copy(branch = 0),
                                cell,
                                // Use the concurrent tip's value, which was the value observed at this time before this insertion.
                                concurrentTip.value,
                                mutableListOf(),
                            )
                            cellNodes.getValue(cell)[mergeNode.time] = mergeNode
                            // If the concurrent branch tip has a next node, link that next node to the merge instead
                            concurrentTip.next.forEach { afterWrite ->
                                mergeNode.next += afterWrite
                                when (afterWrite) {
                                    is CellMergeNode<V> -> check (false) {
                                        "Internal error! A single branch should not end with a merge node."
                                    }
                                    is CellStepNode<V> -> {
                                        afterWrite.prior = mergeNode
                                    }
                                    is CellWriteNode<V> -> {
                                        afterWrite.prior = mergeNode
                                    }
                                }
                            }
                            // Migrate readers and awaiters from branch tips to the merge node, as needed
                            migrateDependentTasks(concurrentTip, mergeNode)
                            migrateDependentTasks(writeNode, mergeNode)
                            // Then, connect both this and the concurrent branch to the merge node
                            concurrentTip.next.clear()
                            concurrentTip.next += mergeNode
                            mergeNode.prior += concurrentTip as CellWriteNode
                            writeNode.next += mergeNode
                            mergeNode.prior += writeNode
                        } else {
                            // We're not adding a concurrent branch, so just insert the write directly.
                            when (adjacentCellNode) {
                                is CellMergeNode -> {
                                    adjacentCellNode.prior -= priorCellNode as CellWriteNode
                                    adjacentCellNode.prior += writeNode
                                }
                                is CellStepNode -> {
                                    adjacentCellNode.prior = writeNode
                                }
                                is CellWriteNode -> {
                                    adjacentCellNode.prior = writeNode
                                }
                            }
                            priorCellNode.next -= adjacentCellNode
                            writeNode.next += adjacentCellNode
                        }
                    }
                    else -> {
                        if (priorCellNode.next.first() sameBatchAs writeNode) {
                            // This write node will join the merge, either as a new branch or a new head to an existing branch.
                            val branchStart = priorCellNode.next.firstOrNull { it sameBranchAs writeNode } as CellWriteNode?
                            if (branchStart != null) {
                                // writeNode is a new start for this branch.
                                branchStart.prior = writeNode
                                priorCellNode.next -= branchStart
                                writeNode.next += branchStart
                            } else {
                                // writeNode is the entirety of a new branch.
                                val merge = generateSequence(priorCellNode.next.first()) { it.next.single() }
                                    .first { it is CellMergeNode } as CellMergeNode
                                // Link write in as a new branch of the merge node
                                writeNode.next += merge
                                merge.prior += writeNode
                            }
                        } else {
                            // This write node is causally before the branches coming off of priorCellNode.
                            // Link all the branches back to writeNode
                            priorCellNode.next.forEach { (it as CellWriteNode).prior = writeNode }
                            writeNode.next += priorCellNode.next
                            priorCellNode.next.clear()
                        }
                    }
                }
                priorCellNode.next += writeNode
                // Schedule anything directly after writeNode to be re-checked, since we just inserted a write before it
                checkCellsAfter(writeNode)

                // Having constructed the cell's write node, now construct the next step node for the task.
                lastTaskStepNode = WriteNode(
                    nextNodeId++,
                    lastTaskStepNode.taskName,
                    writeTime,
                    lastTaskStepNode,
                    writeNode,
                ).also {
                    // Also add the edges from the prior task step and from the cell write node to this.
                    lastTaskStepNode.next = it
                    writeNode.writer = it
                }
                dumpDotToFile(DebugLevel.MINOR, lastTaskStepNode)
            }

            override fun <V> report(value: V) {
                // Record this report in the task graph and issue it to the reportHandler
                lastTaskStepNode = ReportNode(
                    nextNodeId++,
                    lastTaskStepNode.taskName,
                    lastTaskStepNode.time.nextStep(),
                    lastTaskStepNode,
                    value,
                ).also {
                    lastTaskStepNode.next = it
                    reportHandler.report(it)
                }
                dumpDotToFile(DebugLevel.MINOR, lastTaskStepNode)
            }
        }
        when (val result = continuation(basicTaskActions)) {
            is TaskStepResult.Await -> {
                // Create an await node and add it to the frontier, to be checked in the next batch.
                lastTaskStepNode = AwaitNode(
                    nextNodeId++,
                    lastTaskStepNode.taskName,
                    lastTaskStepNode.time.nextTaskBatch(),
                    lastTaskStepNode,
                    result.condition,
                    rewait = result.rewait,
                    continuation = result.continuation,
                ).also {
                    // Having constructed the node, link it to chain of task step nodes
                    lastTaskStepNode.next = it
                    // Schedule the condition to be checked
                    frontier += CheckCondition(it)
                }
            }
            is TaskStepResult.Complete -> {
                lastTaskStepNode = TaskCompleteNode(
                    nextNodeId++,
                    lastTaskStepNode.taskName,
                    lastTaskStepNode.time.nextStep(),
                    lastTaskStepNode,
                ).also {
                    lastTaskStepNode.next = it
                }
                // Note that if there was a merge opportunity, there's also a scheduled FrontierAction
                // to revoke it. We'll let that frontierAction handle things.
            }
            is TaskStepResult.Restart -> {
                if (mergeOpportunity != null) {
                    // Accept the opportunity to merge instead of re-run, by
                    // removing the scheduled action to revoke the merge opportunity.
                    frontier -= RevokeMergeOpportunity(mergeOpportunity)
                    // Link the merge opportunity as the next step of the task
                    mergeOpportunity.prior = lastTaskStepNode
                    lastTaskStepNode.next = mergeOpportunity
                    // Renumber the steps of the merged task to be consistent with the prior task node
                    renumberSteps(mergeOpportunity, lastTaskStepNode.time.step + 1)
                } else {
                    // Add a new root task, from which we can restart the next task at any time.
                    lastTaskStepNode = StartTaskNode(
                        nextNodeId++,
                        // Restarting does not yield to the engine, it's the next step of this task.
                        lastTaskStepNode.time.nextStep(),
                        result.continuation,
                        lastTaskStepNode,
                    ).also {
                        lastTaskStepNode.next = it
                        frontier += RunTask(it)
                    }
                }
            }
            is TaskStepResult.Spawn -> {
                // Spawning is not a yielding action, so add our continuation to the next step.
                // Also add the child task to the next batch, and build a new task branch for it.
                lastTaskStepNode = SpawnNode(
                    nextNodeId++,
                    lastTaskStepNode.taskName,
                    lastTaskStepNode.time.nextStep(),
                    lastTaskStepNode,
                    result.child.branchAt(lastTaskStepNode.time.nextTaskBatch()).also {
                        frontier += RunTask(it)
                    },
                    result.continuation,
                ).also {
                    lastTaskStepNode.next = it
                    it.child.prior = it
                    frontier += RunTask(it)
                }
            }
        }
    }

    /**
     * Migrate dependent tasks (readers and awaiters) from a cell node to another (write or merge) cell node,
     * which is being injected immediately after "from".
     */
    private fun <T> migrateDependentTasks(from: CellNode<T>, to: CellNode<T>) {
        val readIterator = from.reads.iterator()
        while (readIterator.hasNext()) {
            val read = readIterator.next()
            if (read isCausallyAfter to) {
                // Switch the read over to this cell node
                to.reads += read
                read.cell = to
                readIterator.remove()
                // Preserve the read value when switching it over;
                // if the value the reader read disagrees with this write, it'll get re-checked by CheckCell.
            }
        }

        // For awaiters, first migrate any awaiters that should initially read from to instead of from.
        val fromAwaiters = from.awaiters.iterator()
        while (fromAwaiters.hasNext()) {
            val awaiter = fromAwaiters.next()
            if (awaiter isCausallyAfter to) {
                // Switch the awaiter over to this cell node
                // Preserve the read value when switching it over;
                // if the value the awaiter read disagrees with this write, it'll get re-checked by CheckCell.
                awaiter.reads[to] = awaiter.reads.remove(from)
                fromAwaiters.remove()
                to.awaiters += awaiter
            }
        }

        // Then, look for any awaiters that might be interrupted by this write.
        // Collect all the step nodes, plus one non-step node, going backwards from 'from'.
        // If 'from' is not a step node, this is just 'from'.
        // These are the nodes of this cell that an awaiter could have read, which may be interrupted by this write.
        for (awaiterSource in generateSequence(from) { (it as? CellStepNode)?.prior }) {
            for (awaiter in awaiterSource.awaiters) {
                if (awaiter.next?.let { it isCausallyAfter to } ?: true) {
                    // The awaiter is active when 'to' happens, either without a next node or with a next node after 'to'.
                    // Schedule the condition to be re-checked, to discover this possible interruption.
                    frontier += CheckCondition(awaiter)
                }
            }
        }
    }

    private fun renumberSteps(task: TaskNode, stepNumber: Int) {
        var node: TaskNode? = task
        var n = stepNumber
        while (node != null) {
            node.time.step = n++
            node = node.next?.takeIf { it sameBranchAs task }
        }
    }

    /**
     * Load (if necessary), run, and unload the continuation in this node.
     */
    private fun YieldingStepNode.runContinuation(actions: BasicTaskActions): TaskStepResult =
        loadContinuation().runStep(actions).also {
            // Only the continuation in a StartTaskNode may be re-run. Otherwise, unload it immediately.
            if (this !is StartTaskNode) continuation = null
            if (this is AwaitNode || this is AwaitCompleteNode) {
                // For an AwaitCompleteNode, the continuation is shared with all prior Await nodes. Unload all of them.
                awaitGroup().forEach { it.continuation = null }
            }
        }

    /**
     * Return the continuation for this task node.
     * If this.continuation is currently null, construct it by replaying the task from the most recent available continuation.
     */
    private fun YieldingStepNode.loadContinuation(): Task {
        if (continuation == null) {
            continuation = if ((this is AwaitCompleteNode || this is AwaitNode) && prior is AwaitNode) {
                // Special case - continuations are copied across await nodes
                (prior as AwaitNode).loadContinuation()
            } else {
                // General case - continuations are rebuilt by running from the prior yielding step.
                // Run a task from the yielding node prior to this, replaying it to this.
                val priorYieldingStepNode = priorNodes().first { it is YieldingStepNode } as YieldingStepNode
                // When replaying, all actions should be replays. No continuation actions are needed.
                val stepResult = priorYieldingStepNode.runContinuation(priorYieldingStepNode.replayingTaskActions(
                    object : BasicTaskActions {
                        override fun <V> read(cell: Cell<V>): V = throwError()
                        override fun <V> emit(cell: Cell<V>, effect: Effect<V>) = throwError()
                        override fun <V> report(value: V) = throwError()
                        private fun throwError(): Nothing =
                            throw IllegalStateException("Replay of task $taskName did not yield when expected to!")
                    }))
                when (this) {
                    is AwaitNode, is AwaitCompleteNode -> {
                        check(stepResult is TaskStepResult.Await) {
                            "Replay of task $taskName did not await when expected to!"
                        }
                        // The continuation of an await is the stepResult's continuation. The rewait is a separate field on an AwaitNode.
                        stepResult.continuation
                    }
                    is SpawnNode -> {
                        check(stepResult is TaskStepResult.Spawn) {
                            "Replay of task $taskName did not spawn when expected to!"
                        }
                        // The continuation of a spawn is the parent branch; children have their own RootTaskNode.
                        stepResult.continuation
                    }
                    is StartTaskNode -> throw IllegalStateException("StartTaskNode for $taskName had a null continuation")
                }
            }
        }
        return continuation!!
    }

    /**
     * Construct [BasicTaskActions] which will replay the history of starting from [this].
     * If that history is exhausted, switches to [continuationActions] instead.
     */
    private fun TaskNode.replayingTaskActions(continuationActions: BasicTaskActions): BasicTaskActions {
        // Find the root node from which to replay this task
        val root = this
        // Tip optimization - in the common case that we're not actually replaying, don't wrap continuation actions.
        var next: TaskNode? = root.next ?: return continuationActions

        return object : BasicTaskActions {
            override fun <V> read(cell: Cell<V>): V = if (next != null) {
                check(next is ReadNode) {
                    "Internal error! Task replay (read) did not align with history (${next!!::class.simpleName})"
                }
                @Suppress("UNCHECKED_CAST")
                // Replay the value we read last time, and advance next
                val result = ((next as ReadNode).cell as CellNode<V>).value
                next = next?.next
                dumpDotToFile(DebugLevel.MINOR, next, checkIntegrity = false)
                result
            } else {
                continuationActions.read(cell)
            }

            override fun <V> emit(cell: Cell<V>, effect: Effect<V>) = if (next != null) {
                check(next is WriteNode) {
                    "Internal error! Task replay (write) did not align with history (${next!!::class.simpleName})"
                }
                // Ignore the write and advance next
                next = next?.next
                dumpDotToFile(DebugLevel.MINOR, next, checkIntegrity = false)
            } else {
                continuationActions.emit(cell, effect)
            }

            override fun <V> report(value: V) = if (next != null) {
                check(next is ReportNode<*>) {
                    "Internal error! Task replay (report) did not align with history (${next!!::class.simpleName})"
                }
                // Ignore the report and advance next
                next = next?.next
                dumpDotToFile(DebugLevel.MINOR, next, checkIntegrity = false)
            } else {
                continuationActions.report(value)
            }
        }
    }

    /**
     * Revoke all task nodes starting from [task].
     * This includes repetitions and children spawned by this task.
     */
    private fun fullyRevokeTask(task: TaskNode) {
        // If this node is a branch root, remove it and recycle the branch number
        branchRoots.remove(task.time)?.let {
            recycledBranchNumbers += it.start.time.branch
        }
        var next: TaskNode? = task
        while (next != null) {
            next = revokeSingleTask(next)
        }
    }

    private fun revokeWithMergeOpportunity(task: TaskNode): StartTaskNode? = revokeSingleTask(task)?.also {
        // Unlink the merge opportunity from any prior task nodes, so those nodes can be garbage collected...
        it.prior = null
        // Renumber the merge opportunity to exist "late" on the branch,
        // giving any running tasks an opportunity to merge with it before the Revoke happens.
        renumberSteps(it, Int.MAX_VALUE / 2)
        frontier += RevokeMergeOpportunity(it)
        rootMergeOpportunities[it.taskName] = it
    }

    /**
     * Revoke all task nodes starting from [task], up to but not including its repeat (if applicable).
     * Children spawned by [task] will be fully revoked.
     *
     * @return RootTaskNodes following [task]
     */
    private tailrec fun revokeSingleTask(task: TaskNode): StartTaskNode? {
        // Remove any frontier action(s) related to this node
        frontier -= RunTask(task)
        if (task is AwaitNode) frontier -= CheckCondition(task)
        // Unlink this node from its prior
        // The 'if' is in case we're unlinking a root node from a spawn.
        // In that case, task is the spawn's child, not its next.
        // To revoke a root task spawned by a parent task, we must be revoking the parent task too, so no need to unlink the child.
        task.prior?.apply { if (next === task) next = null }
        // Do any special actions based on this node type
        when (task) {
            is ReadNode -> task.cell.reads -= task
            is ReportNode<*> -> reportHandler.revoke(task)
            is WriteNode -> revokeCell(task.cell)
            is StartTaskNode -> { /* Nothing to do */ }
            is AwaitNode -> {
                task.reads.keys.forEach { it.awaiters -= task }
                task.reads.clear()
            }
            is AwaitCompleteNode -> { /* Nothing to do */ }
            // The child of a revoked parent can never be merged.
            // If the root of this parent wasn't merged, it's because this task and any candidate for merging
            // couldn't be proven to be in the same state. Since children (may) inherit state from their parents,
            // any child spawned from possibly-different parents may itself be possibly-different, so cannot be merged.
            is SpawnNode -> fullyRevokeTask(task.child)
            is TaskCompleteNode -> { /* Nothing to do */ }
        }
        // Don't check integrity now, we're purposely breaking the integrity of the next node by removing this node.
        dumpDotToFile(DebugLevel.MINOR, task.next, checkIntegrity = false)
        // Continue revoking the rest of this task using a tail-recursive call.
        // Doing this as tail recursion allows the compiler to rewrite this as a loop, avoiding stack-overflow errors.
        return when (val next = task.next) {
            is StartTaskNode? -> next
            else -> revokeSingleTask(next)
        }
    }

    private fun <T> revokeCell(cell: CellNode<T>) {
        // Remove the cell node from cellNodes
        getCellNodes(cell.cell) -= cell.time
        // Remove any pending actions related to this node
        frontier -= CheckCell(cell)
        // Repair the DAG, connecting nodes before and after this cell node
        val prior: CellNode<T>
        when (cell) {
            is CellWriteNode -> {
                prior = checkNotNull(cell.prior) {
                    "Cannot revoke initial cell write node!"
                }
                prior.next -= cell
                // Regardless of how we're updating cell.next nodes, schedule them (and potentially a block of step nodes) to be re-checked.
                // If we revoke a merge node below, that function will schedule nodes after the merge to be re-checked,
                // and remove the CheckCell action on the merge itself from the frontier.
                checkCellsAfter(cell)
                for (next in cell.next) {
                    when (next) {
                        is CellWriteNode -> {
                            next.prior = prior
                            prior.next += next
                        }
                        is CellStepNode -> {
                            next.prior = prior
                            prior.next += next
                        }
                        is CellMergeNode -> {
                            next.prior -= cell
                            when {
                                prior sameBranchAs cell -> {
                                    // We're shortening this branch without removing it.
                                    // Link prior to next, then schedule next to be re-checked.
                                    next.prior += prior as CellWriteNode
                                    prior.next += next
                                }
                                next.prior.size <= 1 -> {
                                    // We've removed the second-to-last branch, revoke the merge itself too.
                                    revokeCell(next)
                                }
                                else -> {
                                    // We removed a branch, leaving at least two branches. Keep the merge.
                                }
                            }
                        }
                    }
                }
                // A CellWriteNode is revoked only when the writer is revoked. Nothing to do for cell.writer
            }
            is CellStepNode -> {
                check(cell.reads.isEmpty() && cell.awaiters.isEmpty()) {
                    "Cannot revoke step node with reads nor awaiters!"
                }
                // Just stitch together next and prior pointers
                // Removing an unused step node doesn't change the semantics of the DAG
                prior = cell.prior
                prior.next -= cell
                for (next in cell.next) {
                    when (next) {
                        is CellWriteNode -> {
                            next.prior = prior
                            prior.next += next
                        }
                        is CellStepNode -> {
                            next.prior = prior
                            prior.next += next
                        }
                        is CellMergeNode -> throw IllegalStateException("Internal error! Merge nodes cannot follow step nodes!")
                    }
                }
            }
            is CellMergeNode -> {
                prior = checkNotNull(cell.prior.singleOrNull()) {
                    "Internal error! Only single-branch merge nodes can be revoked."
                }
                // Disconnect the branch from this merge
                prior.next -= cell
                // For each successor of this merge, connect it directly to the branch and schedule it to be rechecked.
                for (next in cell.next) {
                    when (next) {
                        is CellWriteNode -> {
                            next.prior = prior
                            prior.next += next
                        }
                        is CellStepNode -> {
                            next.prior = prior
                            prior.next += next
                        }
                        is CellMergeNode -> {
                            check(false) {
                                "Internal error! Merge nodes cannot contain empty branches."
                            }
                        }
                    }
                }
                checkCellsAfter(cell)
            }
        }

        // Only check the prior cell if we're actually re-assigning readers and/or awaiters
        if (cell.reads.isNotEmpty() || cell.awaiters.isNotEmpty()) {
            // Schedule the prior cell node to be re-checked, to check the reads and awaits we're about to re-assign.
            frontier += CheckCell(prior)
        }

        // Any reads on the revoked cell node should instead point at the prior cell node.
        cell.reads.forEach {
            it.cell = prior
            prior.reads += it
        }

        // If cell is a write or merge node, compute the time of a task reacting to this write
        // Otherwise compute null, which will never equal a task time
        val cellReactionTime = cell.takeUnless { it is CellStepNode }?.time?.nextTaskBatch()
        // Anything awaiting this cell should instead await prior
        // Preserve the read value when moving this link, to be checked by CheckCell.
        for (awaiter in cell.awaiters) {
            awaiter.reads[prior] = awaiter.reads.remove(cell)
            prior.awaiters += awaiter
            if (awaiter.time == cellReactionTime?.copy(branch = awaiter.time.branch)) {
                (awaiter.prior as? AwaitNode)?.let { interruptedAwait ->
                    frontier += CheckCondition(interruptedAwait)
                }
            }
        }
        cell.awaiters.clear()
    }

    private class IncrementalCellImpl<T>(
        override val name: Name,
        override val valueType: KType,
        override val stepBy: (T, Duration) -> T,
        override val applyConcurrentEffects: (List<Effect<T>>, T) -> T,
    ) : Cell<T>
    @Suppress("UNCHECKED_CAST")
    private fun <T> getCellNodes(cell: Cell<T>) = cellNodes.getValue(cell) as TreeMap<SimulationTime, CellNode<T>>

    private fun <V> getCellNode(cell: Cell<V>, time: SimulationTime): CellNode<V> {
        val thisCellsNodes = getCellNodes(cell)
        // Get the most recent cell node.
        var (t, cellNode) = checkNotNull(thisCellsNodes.floorEntry(time))
        // This may have come from another task running in this batch though!
        if (t isConcurrentWith time) {
            // If so, set the time to the first possible time of this batch,
            // and ask for the cell node before that. That'll get the last cell node
            // of the batch before this, which must be a merge or step or something.
            cellNode = checkNotNull(thisCellsNodes.lowerEntry(time.batchStart())).value
        }
        // Now, check if we need to step up this cell node to the requested time
        if (cellNode.time.instant < time.instant) {
            // Stepping many small increments can accrue numerical error in complex dynamics.
            // Walk back to our first non-step-node ancestor and step from there instead.
            var baseNode = cellNode
            while (baseNode is CellStepNode) baseNode = baseNode.prior
            val stepSize = time.instant - baseNode.time.instant
            // Build the new step node
            val stepNode = CellStepNode(
                nextNodeId++,
                time.cellSteppingBatch(),
                cell,
                cell.stepBy(baseNode.value, stepSize),
                cellNode,
                cellNode.next.toMutableList(),
                // Leave the reads as-is, they happen before the stepping
            )
            // Fix the edges leading to this step node
            cellNode.next.clear()
            cellNode.next += stepNode
            // Fix the edges leaving from this step node
            for (nextNode in stepNode.next) {
                check(nextNode is CellStepNode<V>) {
                    "Internal error! Node following an injected step node was not itself a step node."
                }
                nextNode.prior = stepNode
            }

            // Let the global cell map know about this node for quick lookup later
            thisCellsNodes[stepNode.time] = stepNode
            return stepNode
        } else {
            // Since no stepping is required, return the node we found
            return cellNode
        }
    }

    private sealed interface FrontierAction {
        val node: IncSimNode
        val time: SimulationTime get() = node.time

        data class RunTask(override val node: TaskNode) : FrontierAction
        data class CheckCell(override val node: CellNode<*>) : FrontierAction
        data class CheckCondition(override val node: AwaitNode) : FrontierAction
        data class RevokeMergeOpportunity(override val node: StartTaskNode) : FrontierAction
    }

    private var previouslyHighlightedNode: IncSimNode? = null
    /**
     * Debugging function which dumps the current simulation DAG as a Graphviz (dot) file
     *
     * @param checkIntegrity Raise an [IllegalStateException] if the graph doesn't meet standard integrity checks.
     */
    private fun dumpDot(
        highlightNode: IncSimNode? = null,
        checkIntegrity: Boolean = true,
    ): String {
        fun checkIntegrity(condition: Boolean, lazyMessage: () -> String) =
            check (!checkIntegrity || condition) { lazyMessage() + " integrity check failed" }

        val graphBuilder = StringBuilder("digraph ${KernelIncrementalSimulator::class.simpleName} {\n")
        graphBuilder.append("  concentrate = true\n")
        var n = 0
        val cellDotId = mutableMapOf<CellNode<*>, String>()
        val taskDotId = mutableMapOf<TaskNode, String>()
        val cells = mutableMapOf<CellNode<*>, Cell<*>>()
        val ranks = TreeMap<SimulationTime, MutableList<IncSimNode>>()
        // The "rank" is just the time ignoring the branch
        fun SimulationTime.rank() = copy(branch = 0)
        // The "file" is the horizontal position within the rank
        fun SimulationTime.file() = branch
        fun collect(node: IncSimNode) = ranks.computeIfAbsent(node.time.rank()) { mutableListOf() }.add(node)
        val frontierModifier = frontier.groupBy { it.node }
            .mapValues { (_, actions) -> actions.joinToString(", ") { it::class.simpleName!! } }
        for ((cell, cellTree) in cellNodes) {
            for (node in cellTree.values) {
                cellDotId[node] = "cell${n++}"
                cells[node] = cell
                collect(node)
            }
        }
        // Walk all the branches to collect all the task nodes, plus the merge opportunities, since those aren't necessarily connected to a branch.
        val regularTaskNodes = branchRoots.values.flatMap { it.start.thisAndNextNodes() }
        val mergeOpNodes = rootMergeOpportunities.values.flatMap { it.thisAndNextNodes() }
        for (node in regularTaskNodes + mergeOpNodes) {
            taskDotId[node] = "task${n++}"
            collect(node)
        }
        val branchRootNodes = branchRoots.values.map { it.start as IncSimNode }.toSet()

        for ((i, rank) in ranks.values.withIndex()) {
            if (i > 0) {
                // Add invisible edges between representative nodes on each rank, to enforce rank order
                graphBuilder.append("  r", i - 1, " -> r", i, " [style = invis]\n")
            }
            graphBuilder
                .append("  {\n")
                .append("    // Rank ", i, "\n")
                .append("    rank = same\n")
                .append("    rankdir = LR\n")
                // Add an invisible representative node, to enforce rank ordering, so edges between them don't interfere
                .append("    r", i, " [style = invis]\n")

            for ((j, node) in rank.sortedBy { it.time.file() }.withIndex()) {
                val fillColor = when {
                    node === highlightNode -> "#69aa7c" // green
                    node === previouslyHighlightedNode -> "#e2dc66" // yellow
                    node in frontierModifier -> "#50a0f4" // blue
                    else -> null
                }
                val penWidthStyling = if (node in branchRootNodes) ", penwidth = 4.0" else ""
                val styling = (fillColor?.let { ", style = filled, fillcolor = \"$it\"" } ?: "") + penWidthStyling
                val labelSuffix = frontierModifier[node]?.let{ "** $it\\l" } ?: ""
                val label: String
                val shape: String
                val id: String
                when (node) {
                    is CellNode<*> -> {
                        val cell = cells.getValue(node)
                        label = when (node) {
                            is CellMergeNode<*> -> "Merge ${cell.name}"
                            is CellStepNode<*> -> "Step ${cell.name}"
                            is CellWriteNode<*> -> "Write ${cell.name}"
                        } + "\\l${node.value}"
                        shape = "ellipse"
                        id = cellDotId.getValue(node)
                    }
                    is TaskNode -> {
                        label = when (node) {
                            is ReadNode -> "Read ${node.cell.cell.name} = ${node.value}"
                            is ReportNode<*> -> "Report ${node.content}"
                            is WriteNode -> "Write '${node.cell.effect}'"
                            is StartTaskNode -> "Root ${node.taskName}"
                            is AwaitNode -> (
                                    "Await ${node.condition}" + (if (node.continuation != null) " ++" else "")
                                    + (node.reads.map { (cell, value) -> "\\l- $cell -> $value" }.joinToString())
                                    )
                            is AwaitCompleteNode -> "AwaitComplete" + (if (node.continuation != null) " ++" else "")
                            is SpawnNode -> "Spawn" + (if (node.continuation != null) " ++" else "")
                            is TaskCompleteNode -> "TaskComplete ${node.taskName}"
                        }
                        shape = "box"
                        id = taskDotId.getValue(node)
                    }
                }
                graphBuilder.append("    ", id, " [shape = $shape$styling, label = \"$label\\l${node.time}\\l$labelSuffix\"]\n")
                if (j > 0) {
                    // Add an invisible edge between the last file and this node to enforce file order
                    graphBuilder.append("    f_", i, "_", j - 1, " -> ", id, " [style = invis]\n")
                }
                // Add an invisible file node, and an invisible edge from the last node to this file node
                // We use invisible nodes instead of drawing edges between the visible nodes so the invisible ordering edges
                // don't merge with and hide the real edges.
                graphBuilder
                    .append("    f_", i, "_", j, " [ style = invis ]\n")
                    .append("    ", id, " -> f_", i, "_", j, " [style = invis]\n")
            }

            graphBuilder
                .append("  }\n")
        }

        // Now fill in the edges
        for ((node, nodeId) in cellDotId) {
            for (next in node.next) {
                graphBuilder.append("  ", nodeId, " -> ", cellDotId.getValue(next), "\n")
                checkIntegrity(when (next) {
                    is CellMergeNode<*> -> next.prior.any { it === node }
                    is CellStepNode<*> -> node === next.prior
                    is CellWriteNode<*> -> node === next.prior
                }) { "Cell prior" }
            }
            for (read in node.reads) {
                graphBuilder.append("  ", nodeId, " -> ", taskDotId.getValue(read), "\n")
                checkIntegrity(read.cell === node) { "Cell read" }
            }
            for (awaiter in node.awaiters) {
                graphBuilder.append("  ", nodeId, " -> ", taskDotId.getValue(awaiter), "\n")
                checkIntegrity(node in awaiter.reads) { "Cell awaiter" }
            }
            when (node) {
                is CellMergeNode<*> -> node.prior.forEach {
                    checkIntegrity(it in cellDotId) { "Cell merge prior" }
                    checkIntegrity(node in it.next) { "Cell merge prior" }
                }
                is CellStepNode<*> -> {
                    checkIntegrity(node.prior in cellDotId) { "Cell step prior" }
                    checkIntegrity(node in node.prior.next) { "Cell step prior" }
                }
                is CellWriteNode<*> -> {
                    node.prior?.let {
                        checkIntegrity(it in cellDotId) { "Cell write prior" }
                        checkIntegrity(node in it.next) { "Cell write prior" }
                    }
                    node.writer?.let {
                        checkIntegrity(it in taskDotId) { "Cell writer" }
                        checkIntegrity(it.cell === node) { "Cell writer" }
                    }
                }
            }
        }
        for ((node, nodeId) in taskDotId) {
            node.next?.let {
                graphBuilder.append("  ", nodeId, " -> ", taskDotId.getValue(it), "\n")
                checkIntegrity(it.prior === node) { "Task next" }
            }
            when (node) {
                is SpawnNode -> {
                    graphBuilder.append("  ", nodeId, " -> ", taskDotId.getValue(node.child), "\n")
                    checkIntegrity(node.child.prior === node) { "Task spawn" }
                }
                is WriteNode -> {
                    graphBuilder.append("  ", nodeId, " -> ", cellDotId.getValue(node.cell), "\n")
                    checkIntegrity(node.cell.writer === node) { "Task write" }
                }
                is ReadNode -> {
                    checkIntegrity(node.cell in cellDotId) { "Task read" }
                    checkIntegrity(node in node.cell.reads) { "Task read" }
                }
                is AwaitNode -> {
                    node.reads.keys.forEach {
                        checkIntegrity(it in cellDotId) { "Task await" }
                        checkIntegrity(node in it.awaiters) { "Task await" }
                    }
                }
                else -> Unit
            }
            node.prior?.let {
                checkIntegrity(it in taskDotId) { "Task prior" }
                checkIntegrity(it.next === node || (it as? SpawnNode)?.child === node) { "Task prior" }
            }
        }
        graphBuilder.append("}\n")
        previouslyHighlightedNode = highlightNode
        return graphBuilder.toString()
    }
}
