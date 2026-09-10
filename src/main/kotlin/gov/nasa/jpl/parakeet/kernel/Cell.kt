package gov.nasa.jpl.parakeet.kernel

import kotlin.reflect.KType
import kotlin.time.Duration
import kotlin.time.Instant

typealias Effect<T> = (T) -> T

// Cell is class, not data class, because we want to use object-identity equality
interface Cell<T> {
    val name: Name
    val valueType: KType
    val stepBy: (T, Duration) -> T
    // TODO: Replace this with an "applyConcurrentEffects" function that takes a list of effects and the starting value.
    //   Doing so will avoid building the intermediate effect object. If such an object is needed, a closure can be built instead.
    val mergeConcurrentEffects: (List<Effect<T>>) -> Effect<T>
}

class CellImpl<T> internal constructor(
    override val name: Name,
    internal var value: T,
    override val valueType: KType,
    override val stepBy: (T, Duration) -> T,
    override val mergeConcurrentEffects: (List<Effect<T>>) -> Effect<T>,
    /** Internal bookkeeping: a unique ID for this cell, used to accelerate equality checks and hashing */
    private val id: Int,
    /** Internal bookkeeping: the value this cell had the last time it was written to */
    internal var lastWrittenValue: T = value,
    /** Internal bookkeeping: the absolute time this cell was last written to */
    internal var lastWrittenTime: Instant,
    /** Internal bookkeeping: the value this cell had before being modified on this branch */
    internal var trunkValue: T? = null,
    /** Internal bookkeeping: the net effect of all branches in this batch */
    internal var batchNetEffect: NetEffect<T>? = null,
    /** Internal bookkeeping: the effects applied so far on this branch */
    internal var branchEffects: MutableList<Effect<T>>? = null,
) : Cell<T> {
    override fun toString() = "$name = $value"

    // Object identity for equality and hashing, but implemented via unique ID for performance.
    // Adding cells to hash sets is a hot path in simulation, so keeping this performant is important.
    override fun hashCode(): Int = id
    override fun equals(other: Any?): Boolean = other is CellImpl<*> && other.id == id
}

/** Internal bookkeeping class used by the simulator itself. */
internal class NetEffect<T>(
    internal var value: T?,
    internal var effects: MutableList<List<Effect<T>>>,
)
