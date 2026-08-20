package gov.nasa.jpl.parakeet.kernel

import gov.nasa.jpl.parakeet.kernel.tasks.TaskHistory
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * A checkpoint of the state of [KernelSimulator] which supports serialization to disk.
 */
@Serializable
data class KernelCheckpoint(
    val time: Instant,
    val cells: DependentMap,
    val tasks: List<KernelTaskCheckpoint>,
)

@Serializable
data class KernelTaskCheckpoint(
    /** The name of this task */
    val name: Name,
    /** The name of the root task from which this task is descended. */
    val root: Name = name,
    /** The time when this task is scheduled to resume, or null if this task is complete. */
    val time: Instant? = null,
    /** The steps taken by this task, or null if this task is complete. */
    val history: TaskHistory? = null,
)
