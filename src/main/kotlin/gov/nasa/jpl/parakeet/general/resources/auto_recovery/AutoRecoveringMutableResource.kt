package gov.nasa.jpl.parakeet.general.resources.auto_recovery

import gov.nasa.jpl.parakeet.foundation.resources.FaultedResourceException
import gov.nasa.jpl.parakeet.foundation.resources.MutableResource
import gov.nasa.jpl.parakeet.foundation.resources.Resource
import gov.nasa.jpl.parakeet.foundation.resources.ResourceEffect
import gov.nasa.jpl.parakeet.foundation.resources.set
import gov.nasa.jpl.parakeet.foundation.tasks.TaskOperations.delay
import gov.nasa.jpl.parakeet.foundation.tasks.TaskScope
import kotlin.time.Duration.Companion.ZERO

/**
 * A variation on a [MutableResource] which propagates effect errors back to the task, rather than faulting.
 *
 * Note that this doesn't conform to the usual [MutableResource] interface, because the implementation of [emit]
 * requires a [delay] to see if the effect will fault the resource.
 * As such, this type is often best used within a tightly-controlled model, to limit the inconvenience of this change in interface.
 */
interface AutoRecoveringMutableResource<D> : Resource<D> {
    /**
     * Emit [effect] on this resource. If it faults, roll back the effect and re-throw the exception in the calling task.
     *
     * @return the new dynamics
     */
    context (_: TaskScope)
    suspend fun emit(effect: ResourceEffect<D>): D
}

/**
 * Construct an auto-recovering resource around [this].
 * For best results, do not directly emit effects to [this]; only emit effects through the returned wrapper.
 * See [AutoRecoveringMutableResource] for details.
 */
fun <D> MutableResource<D>.autoRecovering(): AutoRecoveringMutableResource<D> =
    object : AutoRecoveringMutableResource<D>, Resource<D> by this {
        context(_: TaskScope)
        override suspend fun emit(effect: ResourceEffect<D>): D {
            val initialDynamics = getDynamics()
            this@autoRecovering.emit(effect)
            delay(ZERO)
            try {
                return getDynamics().data
                // No fault, exit cleanly
            } catch (e: FaultedResourceException) {
                // Fault! Roll back and rethrow
                this@autoRecovering.set(initialDynamics)
                throw e.cause
            }
        }
    }
