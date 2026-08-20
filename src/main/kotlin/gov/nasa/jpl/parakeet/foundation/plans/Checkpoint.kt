package gov.nasa.jpl.parakeet.foundation.plans

import gov.nasa.jpl.parakeet.kernel.DependentMap
import gov.nasa.jpl.parakeet.kernel.KernelTaskCheckpoint
import gov.nasa.jpl.parakeet.kernel.Name
import gov.nasa.jpl.parakeet.kernel.tasks.TaskHistory
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * A checkpoint of [gov.nasa.jpl.parakeet.foundation.Simulator] which supports serialization to disk.
 *
 * Unlike [gov.nasa.jpl.parakeet.kernel.KernelCheckpoint], this separates tasks explicitly into [daemons] and [activities].
 * [daemons] are tasks which originate from the model. The model constructor facilitates resuming these.
 * [activities] are tasks which originate outside the model. They carry a deserializable root task with them.
 */
@Serializable
data class Checkpoint<M>(
    val time: Instant,
    val cells: DependentMap,
    val daemons: List<KernelTaskCheckpoint>,
    val activities: List<ActivityTaskCheckpoint<M>>,
)

/**
 * The checkpoint for an activity that was loaded into the simulator.
 * Like a [KernelTaskCheckpoint], this encodes a task.
 * Unlike a regular [KernelTaskCheckpoint], the task encoded does not originate from the model itself.
 * Instead, it carries the [activity] with it, which can be deserialized and started independent from the model.
 */
@Serializable
data class ActivityTaskCheckpoint<M>(
    /** The time when this task is scheduled to resume, or null if this task is complete. */
    val time: Instant? = null,
    /** The name of this task */
    val name: Name,
    /** The name of the root task from which this task is descended. */
    val activity: GroundedActivity<M>,
    /** The steps taken by this task, or null if this task is complete. */
    val history: TaskHistory? = null,
)
