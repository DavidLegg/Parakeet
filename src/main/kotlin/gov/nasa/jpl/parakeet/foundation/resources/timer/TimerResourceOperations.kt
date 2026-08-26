package gov.nasa.jpl.parakeet.foundation.resources.timer

import gov.nasa.jpl.parakeet.foundation.resources.*
import gov.nasa.jpl.parakeet.foundation.resources.ResourceMonad.map
import gov.nasa.jpl.parakeet.foundation.resources.discrete.*
import gov.nasa.jpl.parakeet.foundation.resources.timer.TimerOperations.minus
import gov.nasa.jpl.parakeet.foundation.resources.timer.TimerOperations.plus
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope
import gov.nasa.jpl.parakeet.foundation.tasks.TaskScope
import gov.nasa.jpl.parakeet.kernel.Durations.EPSILON
import gov.nasa.jpl.parakeet.kernel.Name
import gov.nasa.jpl.parakeet.utilities.named
import kotlin.time.Duration
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.times

typealias TimerResource = Resource<Timer>
typealias MutableTimerResource = MutableResource<Timer>

object TimerResourceOperations {
    context (scope: InitScope)
    fun timer(name: String, initialTime: Duration = ZERO, initialRate: Double = 1.0) =
        resource(name, Timer(initialTime, initialRate))

    /**
     * Reset this timer to [time], not running.
     */
    context (scope: TaskScope)
    fun MutableTimerResource.reset(time: Duration = ZERO) =
        this.emit({ t: Timer -> Timer(time, 0.0) }.named { "Reset $this" })

    /**
     * Reset this timer to [time], running forward.
     */
    context (scope: TaskScope)
    fun MutableTimerResource.restart(time: Duration = ZERO) =
        this.emit({ t: Timer -> Timer(time, 1.0) }.named { "Restart $this" })

    /**
     * Reset this timer to [time], running backward.
     */
    context (scope: TaskScope)
    fun MutableTimerResource.restartCountdown(time: Duration) =
        this.emit({ t: Timer -> Timer(time, -1.0) }.named { "Restart countdown on $this" })

    /**
     * Pause this timer, but preserve the recorded time.
     */
    context (scope: TaskScope)
    fun MutableTimerResource.pause() =
        this.emit({ t: Timer -> Timer(t.time, 0.0) }.named { "Pause $this" })

    /**
     * Resume this timer, running forward from the current time.
     */
    context (scope: TaskScope)
    fun MutableTimerResource.resume() =
        this.emit({ t: Timer -> Timer(t.time, 1.0) }.named { "Resume $this" })

    /**
     * Resume this timer, running backward from the current time.
     */
    context (scope: TaskScope)
    fun MutableTimerResource.resumeCountdown() =
        this.emit({ t: Timer -> Timer(t.time, -1.0) }.named { "Resume countdown on $this" })

    operator fun TimerResource.plus(other: TimerResource): TimerResource =
        map(this, other) { t, o -> t + o }.fullyNamed { Name("($this) + ($other)") }
    operator fun TimerResource.plus(other: Duration): TimerResource = this + constant(other)
    operator fun Duration.plus(other: TimerResource): TimerResource = constant(this) + other
    operator fun TimerResource.minus(other: TimerResource): TimerResource =
        map(this, other) { t, o -> t - o }.fullyNamed { Name("($this) - ($other)") }
    operator fun TimerResource.minus(other: Duration): TimerResource = this - constant(other)
    operator fun Duration.minus(other: TimerResource): TimerResource = constant(this) - other

    // Writing actual operator overloads for DiscreteResource<Duration> causes platform declaration clash,
    // because the outer type is just Resource. Instead, offer the asTimer() conversion.
    fun DiscreteResource<Duration>.asTimer(): TimerResource =
        map(this) { t -> Timer(t.value, 0.0) }.fullyNamed { name }
    fun constant(time: Duration): TimerResource =
        ResourceMonad.pure(Timer(time, 0.0)).fullyNamed { Name(time.toString()) }

    private fun TimerResource.compareWith(other: TimerResource, bipredicate: (Duration, Duration) -> Boolean): BooleanResource =
        // Note: This could be cleaned up a little using monadic operations, but this shows up as a performance hot path.
        // To avoid some boxing and unboxing of Durations, we directly re-implement the monadic behavior here.
        ThinResource {
            val thisDynamics = this@compareWith.getDynamics()
            val otherDynamics = other.getDynamics()
            val t = thisDynamics.data
            val o = otherDynamics.data

            val initialResult = bipredicate(t.time, o.time)
            val deltaRate = o.rate - t.rate
            val estimatedRoot = if (deltaRate == 0.0) INFINITE else (t.time - o.time) / deltaRate
            val expiry = if (estimatedRoot.isInfinite()) INFINITE else {
                (-2..2)
                    // TODO: Think through whether we need to handle coarse epsilon here, and if so, how
                    .map { estimatedRoot + it * EPSILON }
                    .firstOrNull { possibleRoot ->
                        val projectedT = t.time + t.rate * possibleRoot
                        val projectedO = o.time + o.rate * possibleRoot
                        bipredicate(projectedT, projectedO) != initialResult
                    }
            }
            checkNotNull(expiry) { "Root finding failed on resource $this" }
            var finalExpiry = thisDynamics.expiry or otherDynamics.expiry
            if (expiry > ZERO) finalExpiry = finalExpiry or expiry
            // It's possible, especially for "satisfied at 0" cases, that the root is at or before 0 too.
            // This isn't a failure of root-finding, but it isn't a meaningful expiry either.
            Expiring(Discrete(initialResult), finalExpiry)
        }

    infix fun TimerResource.lessThan(other: TimerResource): BooleanResource =
        this.compareWith(other) { t, o -> t < o }.fullyNamed { Name("($this) < ($other)") }
    infix fun TimerResource.lessThanOrEquals(other: TimerResource): BooleanResource =
        this.compareWith(other) { t, o -> t <= o }.fullyNamed { Name("($this) <= ($other)") }
    infix fun TimerResource.greaterThan(other: TimerResource): BooleanResource =
        this.compareWith(other) { t, o -> t > o }.fullyNamed { Name("($this) > ($other)") }
    infix fun TimerResource.greaterThanOrEquals(other: TimerResource): BooleanResource =
        this.compareWith(other) { t, o -> t >= o }.fullyNamed { Name("($this) >= ($other)") }

    infix fun TimerResource.lessThan(other: Duration) = this lessThan constant(other)
    infix fun TimerResource.lessThanOrEquals(other: Duration) = this lessThanOrEquals constant(other)
    infix fun TimerResource.greaterThan(other: Duration) = this greaterThan constant(other)
    infix fun TimerResource.greaterThanOrEquals(other: Duration) = this greaterThanOrEquals constant(other)

    infix fun Duration.lessThan(other: TimerResource) = constant(this) lessThan other
    infix fun Duration.lessThanOrEquals(other: TimerResource) = constant(this) lessThanOrEquals other
    infix fun Duration.greaterThan(other: TimerResource) = constant(this) greaterThan other
    infix fun Duration.greaterThanOrEquals(other: TimerResource) = constant(this) greaterThanOrEquals other
}