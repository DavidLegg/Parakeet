package gov.nasa.jpl.parakeet.general.resources.auto_recovery

import gov.nasa.jpl.parakeet.foundation.resources.FaultedResourceException
import gov.nasa.jpl.parakeet.foundation.resources.MutableResource
import gov.nasa.jpl.parakeet.foundation.resources.ResourceEffect
import gov.nasa.jpl.parakeet.foundation.resources.set
import gov.nasa.jpl.parakeet.foundation.tasks.TaskScope
import gov.nasa.jpl.parakeet.kernel.Cell
import gov.nasa.jpl.parakeet.kernel.Effect
import gov.nasa.jpl.parakeet.utilities.named
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.ZERO

object AutoRecovery {
    /**
     * Emit [effect] on [this] resource, but if it faults the resource, roll the resource effect back and re-throw that exception in this task instead.
     *
     * Note: This method is designed to work on resources using [gov.nasa.jpl.parakeet.foundation.resources.safelyApplyConcurrentEffects]
     * and works best when all emitters to the resource use [commit] instead of [emit].
     * Using a combination of [commit] and [emit] may break the rollback capability.
     */
    context (_: TaskScope)
    suspend fun <D> MutableResource<D>.commit(effect: ResourceEffect<D>) {
        val initialDynamics = getDynamics()
        emit(effect)
        delay(ZERO)
        try {
            getDynamics()
            // No fault, the effect worked!
        } catch (e: FaultedResourceException) {
            // The effect we emitted just caused a fault!
            // Roll the value back to recover the resource
            set(initialDynamics)
            // Then re-throw whatever faulted the resource, to crash this task instead
            throw e.cause
        }
    }

    context (scope: TaskScope)
    suspend fun <R> transaction(block: suspend context (TaskScope) () -> R): R {
        // TODO: call-once contract on block
        val cellInitialValues = mutableMapOf<Cell<*>, Any?>()
        val transactionTaskScope = object : TaskScope by scope {
            override fun <V> emit(cell: Cell<V>, effect: Effect<V>) {
                // Use computeIfAbsent to record the initial value, in case we emit to the same cell multiple times
                cellInitialValues.computeIfAbsent(cell) { scope.read(it) }
                scope.emit(cell, effect)
            }
        }

        try {
            val result = block(transactionTaskScope)
            delay(ZERO)
            // Now check all the cells
            for (cell in cellInitialValues.keys) {
                val finalValue = scope.read(cell)
                // In the common case that these are resource-style cells holding a result,
                // re-throw any exception contained in the result
                if (finalValue is Result<*>) {
                    finalValue.getOrThrow()
                }
            }
            return result
        } catch (e: Throwable) {
            @Suppress("UNCHECKED_CAST")
            fun <T> Cell<T>.revertTo(value: Any?) =
                scope.emit(this, { _: T -> value as T }.named { "Revert to $value" })
            for ((cell, initialValue) in cellInitialValues) {
                cell.revertTo(initialValue)
            }
            throw e
        }
    }
}