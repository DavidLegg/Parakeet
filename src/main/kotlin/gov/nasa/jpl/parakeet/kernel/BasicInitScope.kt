package gov.nasa.jpl.parakeet.kernel

import gov.nasa.jpl.parakeet.kernel.tasks.PureTaskStep
import kotlin.reflect.KType
import kotlin.time.Duration

/**
 * These are the actions allowed during "initialization", before the simulation starts running.
 * Note that this is the only time we're allowed to allocate cells.
 */
interface BasicInitScope {
    fun <T: Any> allocate(
        name: Name,
        value: T,
        valueType: KType,
        stepBy: (T, Duration) -> T,
        mergeConcurrentEffects: (List<Effect<T>>) -> Effect<T>,
    ): Cell<T>
    fun spawn(name: Name, step: PureTaskStep)
    fun <T> read(cell: Cell<T>): T
    fun <T> report(value: T)

    companion object {
        context (scope: BasicInitScope)
        fun <T: Any> allocate(
            name: Name,
            value: T,
            valueType: KType,
            stepBy: (T, Duration) -> T,
            mergeConcurrentEffects: (List<Effect<T>>) -> Effect<T>,
        ): Cell<T> = scope.allocate(name, value, valueType, stepBy, mergeConcurrentEffects)

        context (scope: BasicInitScope)
        fun spawn(name: Name, step: PureTaskStep) = scope.spawn(name, step)

        context (scope: BasicInitScope)
        fun <T> read(cell: Cell<T>): T = scope.read(cell)

        context (scope: BasicInitScope)
        fun <T> report(value: T) = scope.report(value)
    }
}
