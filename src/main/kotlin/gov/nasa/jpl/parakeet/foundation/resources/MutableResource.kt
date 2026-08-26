package gov.nasa.jpl.parakeet.foundation.resources

import gov.nasa.jpl.parakeet.utilities.Reflection.withArg
import gov.nasa.jpl.parakeet.utilities.andThen
import gov.nasa.jpl.parakeet.utilities.named
import gov.nasa.jpl.parakeet.kernel.*
import gov.nasa.jpl.parakeet.foundation.resources.AutoEffect.Companion.autoMerge
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope.Companion.allocate
import gov.nasa.jpl.parakeet.foundation.tasks.ResourceScope
import gov.nasa.jpl.parakeet.foundation.tasks.SimulationScope
import gov.nasa.jpl.parakeet.foundation.tasks.TaskScope
import gov.nasa.jpl.parakeet.kernel.NameOperations.div
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.time.Duration

/**
 * A [Resource] which can be mutated with a [ResourceEffect].
 * Usually constructed with [resource] or a wrapper around it.
 *
 * A [MutableResource] may be "faulted" if applying an effect throws an exception.
 * If the resource is faulted, [MutableResource.getDynamics] will through a [FaultedResourceException].
 * Faults can be cleared by using [MutableResource.set] to write a new value to it.
 */
interface MutableResource<D> : Resource<D> {
    context (scope: TaskScope)
    fun emit(effect: ResourceEffect<D>)
}
typealias ResourceEffect<D> = Effect<Result<FullDynamics<D>>>
typealias MergeResourceEffect<D> = (ResourceEffect<D>, ResourceEffect<D>) -> ResourceEffect<D>

class FaultedResourceException(
    message: String,
    cause: Throwable,
    val expiry: Duration = Duration.INFINITE,
) : RuntimeException(message, cause) {
    fun expiringAt(newExpiry: Duration) =
        FaultedResourceException(checkNotNull(message), checkNotNull(cause), expiry or newExpiry)
}


/**
 * Emit a general effect, which operates on the dynamics stored in this resource.
 * If this resource is faulted, that fault will propagate forward.
 * If applying this effect throws an exception, this resource will fault.
 */
context (scope: TaskScope)
fun <D> MutableResource<D>.emit(effect: (D) -> D) = this.emit({ r: Result<FullDynamics<D>> ->
    // In general, any effect on a faulted cell preserves that fault.
    // Additionally, if this effect throws an exception, that puts this cell in a faulted state.
    r.mapCatching { Expiring(effect(it.data), Duration.INFINITE) }
}.named(effect::toString))

/**
 * Set the dynamics (and expiry) stored in this resource.
 * If this resource is faulted, this will clear the fault.
 */
context (scope: TaskScope)
fun <D> MutableResource<D>.set(newDynamics: Expiring<D>) =
    emit({ _: Result<FullDynamics<D>> -> Result.success(newDynamics) }
        .named { "Set $this to $newDynamics" })

/**
 * Set the dynamics stored in this resource.
 * If this resource is faulted, this will clear the fault.
 */
context (scope: TaskScope)
fun <D> MutableResource<D>.set(newDynamics: D) = set(Expiring(newDynamics, Duration.INFINITE))

context (scope: InitScope)
inline fun <V, reified D : Dynamics<V, D>> resource(
    name: String,
    initialDynamics: D,
    noinline mergeConcurrentEffects: MergeResourceEffect<D> = ::autoMerge,
) = resource(name, initialDynamics, typeOf<D>(), mergeConcurrentEffects)

context (scope: InitScope)
fun <V, D : Dynamics<V, D>> resource(
    name: String,
    initialDynamics: D,
    dynamicsType: KType,
    mergeConcurrentEffects: MergeResourceEffect<D> = ::autoMerge,
) = resource(name, DynamicsMonad.pure(initialDynamics), FullDynamics::class.withArg(dynamicsType), mergeConcurrentEffects)

context (scope: InitScope)
fun <V, D : Dynamics<V, D>> resource(
    name: String,
    // TODO: Should this actually remove the Expiring<> layer?
    //   Rationale - if a cell's dynamics expire, so what? There's nothing it can intrinsically do about it.
    //   OTOH, if a task writes to the cell no later than that expiry, that triggers re-evaluations already.
    initialDynamics: FullDynamics<D>,
    fullDynamicsType: KType,
    mergeConcurrentEffects: MergeResourceEffect<D> = ::autoMerge,
): MutableResource<D> {
    val cell = allocate(
        Name(name),
        Result.success(initialDynamics),
        Result::class.withArg(fullDynamicsType),
        { d, t -> d.mapCatching { it.step(t) } },
        mergeConcurrentEffects,
    )

    return object : MutableResource<D> {
        context(scope: TaskScope)
        override fun emit(effect: ResourceEffect<D>) = scope.emit(cell, effect)

        context(scope: ResourceScope)
        override fun getDynamics(): FullDynamics<D> = scope.read(cell).getOrElse {
            throw FaultedResourceException("Fault in resource $this", it)
        }

        override val name: Name = scope.contextName / name
        override fun toString() = name
    }
}

fun <D> commutingEffects(): MergeResourceEffect<D> = { left, right -> left andThen right }

fun <D> noncommutingEffects(): MergeResourceEffect<D> = { left, right ->
    throw IllegalArgumentException("Non-commuting concurrent effects: $left vs. $right - Cell does not support concurrent effects.")
}

context (scope: SimulationScope)
fun <D> MutableResource<D>.named(nameFn: () -> String) = fullyNamed { scope.contextName / nameFn() }

fun <D> MutableResource<D>.fullyNamed(nameFn: () -> Name) = object : MutableResource<D> by this {
    override val name: Name get() = nameFn()
    override fun toString() = name.simpleName
}
