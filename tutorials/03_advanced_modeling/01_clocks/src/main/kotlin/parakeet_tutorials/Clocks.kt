package parakeet_tutorials

import gov.nasa.jpl.parakeet.foundation.Simulator
import gov.nasa.jpl.parakeet.foundation.reporting.Reporting.registered
import gov.nasa.jpl.parakeet.foundation.resources.Expiring
import gov.nasa.jpl.parakeet.foundation.resources.clock.Clock
import gov.nasa.jpl.parakeet.foundation.resources.clock.ClockResource
import gov.nasa.jpl.parakeet.foundation.resources.clock.ClockResourceOperations.VsTimer.plus
import gov.nasa.jpl.parakeet.foundation.resources.clock.ClockResourceOperations.clock
import gov.nasa.jpl.parakeet.foundation.resources.clock.ClockResourceOperations.minus
import gov.nasa.jpl.parakeet.foundation.resources.clock.ClockResourceOperations.pause
import gov.nasa.jpl.parakeet.foundation.resources.clock.ClockResourceOperations.resume
import gov.nasa.jpl.parakeet.foundation.resources.clock.ClockResourceOperations.set
import gov.nasa.jpl.parakeet.foundation.resources.clock.MutableClockResource
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations.discreteResource
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations.equals
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations.notEquals
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations.set
import gov.nasa.jpl.parakeet.foundation.resources.discrete.MutableDiscreteResource
import gov.nasa.jpl.parakeet.foundation.resources.emit
import gov.nasa.jpl.parakeet.foundation.resources.getValue
import gov.nasa.jpl.parakeet.foundation.resources.named
import gov.nasa.jpl.parakeet.foundation.resources.resource
import gov.nasa.jpl.parakeet.foundation.resources.set
import gov.nasa.jpl.parakeet.foundation.resources.timer.MutableTimerResource
import gov.nasa.jpl.parakeet.foundation.resources.timer.Timer
import gov.nasa.jpl.parakeet.foundation.resources.timer.TimerResource
import gov.nasa.jpl.parakeet.foundation.resources.timer.TimerResourceOperations.lessThanOrEquals
import gov.nasa.jpl.parakeet.foundation.resources.timer.TimerResourceOperations.pause
import gov.nasa.jpl.parakeet.foundation.resources.timer.TimerResourceOperations.plus
import gov.nasa.jpl.parakeet.foundation.resources.timer.TimerResourceOperations.resume
import gov.nasa.jpl.parakeet.foundation.resources.timer.TimerResourceOperations.resumeCountdown
import gov.nasa.jpl.parakeet.foundation.resources.timer.TimerResourceOperations.timer
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope.Companion.spawn
import gov.nasa.jpl.parakeet.foundation.tasks.Reactions.await
import gov.nasa.jpl.parakeet.foundation.tasks.Reactions.onceWhenever
import gov.nasa.jpl.parakeet.foundation.tasks.Reactions.whenever
import gov.nasa.jpl.parakeet.foundation.tasks.ReportScope.Companion.report
import gov.nasa.jpl.parakeet.foundation.tasks.ResourceScope.Companion.now
import gov.nasa.jpl.parakeet.foundation.tasks.SimulationScope.Companion.simulationClock
import gov.nasa.jpl.parakeet.foundation.tasks.SimulationScope.Companion.stdout
import gov.nasa.jpl.parakeet.foundation.tasks.TaskOperations.delay
import gov.nasa.jpl.parakeet.foundation.tasks.TaskOperations.delayUntil
import gov.nasa.jpl.parakeet.foundation.tasks.task
import gov.nasa.jpl.parakeet.general.results.MutableSimulationResults
import gov.nasa.jpl.parakeet.general.results.SimulationResultsOperations.reportHandler
import gov.nasa.jpl.parakeet.utilities.named
import kotlin.time.Instant
import parakeet_tutorials.util.Output.dump
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

fun main() {
    clockDemo()
    println("\n\n")
    timerDemo()
    println("\n\n")
    arithmeticDemo()
}

fun clockDemo() {
    val start = Instant.parse("2030-01-01T00:00:00Z")
    val end = start + 1.days
    val results = MutableSimulationResults(start, end)
    val simulator = Simulator(
        reportHandler = results.reportHandler(),
        startTime = start,
    ) {
        // The function "now()" returns the current simulation time
        assert(now() == start)
        spawn("Check the Clock", task {
            assert(now() == start)
            delay(1.hours)
            assert(now() == start + 1.hours)
            delayUntil(start + 6.hours)
            assert(now() == start + 6.hours)
        })

        // It does this by accessing the simulation clock, a resource available through the simulation scope
        // The simulation scope is a context parameter provided to the Simulator's constructModel block.
        val theSimulationClock: ClockResource = simulationClock

        // Clocks are the first non-discrete resource we've covered in these tutorials.
        // They have an Instant value, which changes continuously as time progresses.
        // No events "happened" to the simulation clock to change its value.

        // To support clocks and other continuous resource types, Parakeet asks that resources define their "dynamics".
        // For example, the dynamics for a clock resource look like this:
        val expringClockDynamics: Expiring<Clock> = theSimulationClock.getDynamics()
        // We'll ignore that "Expiring" layer for now:
        val clockDynamics: Clock = expringClockDynamics.data
        // All dynamics have two primary properties:
        // 1. A current state, including a current value given by `.value()`
        // 2. A way to evolve forward in time, given by `.step()`

        // For a clock, the current state is the time, and a "rate" parameter:
        assert(clockDynamics.value() == start)
        assert(clockDynamics.rate == 1.0)
        // The step function advances the time by the duration multiplied by the rate
        assert(clockDynamics.step(1.hours).value() == start + 1.hours)

        // Using these tools, we can build a clock dynamics that counts down, or is paused:
        val countdownClockDynamics: Clock = clockDynamics.copy(rate = -1.0)
        assert(countdownClockDynamics.value() == start)
        assert(countdownClockDynamics.step(1.hours).value() == start - 1.hours)

        val pausedClockDynamics: Clock = clockDynamics.copy(rate = 0.0)
        assert(pausedClockDynamics.value() == start)
        assert(pausedClockDynamics.step(1.hours).value() == start)

        // We can also create a clock resource:
        val mutableClock: MutableClockResource =
            resource("mutableClock", Clock(start, 1.0))
                .registered()
        // Unlike simulationClock, this one is mutable, so we can apply effects:
        spawn("Apply effects to mutableClock", task {
            delay(1.hours)
            mutableClock.pause()
            delay(1.hours)
            mutableClock.resume()
            delay(1.hours)
            mutableClock.set(start)

            // You can read more about Clock effects in ClockResourceOperations
        })
    }

    simulator.runUntil(end)
    println("=== Clock Demo ===")
    results.dump()
}

fun timerDemo() {
    val start = Instant.parse("2030-01-01T00:00:00Z")
    val end = start + 1.days
    val results = MutableSimulationResults(start, end)
    val simulator = Simulator(
        reportHandler = results.reportHandler(),
        startTime = start,
    ) {
        // Timers are similar to clocks, but they work with relative time (Duration) instead of absolute time (Instant).
        // Like clocks, timers have a "rate" parameter that tells them how to evolve over time.

        // For example, we might have a count-up timer that tracks the cumulative duration we've been in a particular state:
        val state: MutableDiscreteResource<State> =
            discreteResource("state", State.OFF).registered()
        // We can create a timer using the general resource constructor:
        // val timeSpentOn: MutableTimerResource = resource("timeSpentOn", Timer(ZERO, 1.0))
        // Or, since timers are so ubiquitous, with a more concise convenience function:
        val timeSpentOn: MutableTimerResource =
            timer("timeSpentOn", initialRate = 0.0).registered()
        // There are more advanced ways to do this using derived resources, but a daemon task like this works well:
        spawn("Update timeSpentOn", whenever(state equals State.ON) {
            timeSpentOn.resume()
            await(state notEquals State.ON)
            timeSpentOn.pause()
        })

        // Note that the integration of timeSpentOn is automatic and continuous:
        spawn("Check timeSpentOn", task {
            assert(timeSpentOn.getValue() == 0.hours)
            delay(1.hours)
            assert(timeSpentOn.getValue() == 0.hours)

            state.set(State.ON)
            assert(timeSpentOn.getValue() == 0.hours)
            delay(1.hours)
            assert(timeSpentOn.getValue() == 1.hours)
            delay(1.hours)
            assert(timeSpentOn.getValue() == 2.hours)

            state.set(State.OFF)
            assert(timeSpentOn.getValue() == 2.hours)
            delay(1.hours)
            assert(timeSpentOn.getValue() == 2.hours)
        })

        // For another common use-case, here's a way to build a count-down timer
        val launchTimer: MutableTimerResource =
            timer("launchTimer", initialTime = 1.hours, initialRate = 0.0).registered()
        // And a daemon that reacts to it (in this case, it reacts once, but you could loop to react every time it hits 0)
        spawn("Launch", task {
            await(launchTimer lessThanOrEquals ZERO)
            stdout.report("Launch!")
        })
        // We can start a countdown timer by setting the rate to -1.0 with resumeCountdown()
        val nominalLaunchTime = start + 6.hours
        spawn("Start launch timer", task {
            delayUntil(nominalLaunchTime - launchTimer.getValue())
            launchTimer.resumeCountdown()
        })
        // And we can affect the timer in non-trivial ways, e.g. pausing and adding time:
        spawn("Modify launch timer", task {
            // Simulate a 5 minute pause when the timer hits T-30 minutes:
            await(launchTimer lessThanOrEquals 30.minutes)
            launchTimer.pause()
            delay(5.minutes)
            launchTimer.resume()

            // Simulate another 2 minute pause when the timer hits T-10 minutes,
            // plus putting another 5 minutes on the timer:
            await(launchTimer lessThanOrEquals 10.minutes)
            launchTimer.pause()
            delay(2.minutes)
            // There's no built-in operation for incrementing the time like this,
            // but we can use the all-purpose `emit` function to write that effect in-line.
            launchTimer.emit(
                { timer: Timer -> timer.copy(time = timer.time + 5.minutes) }
                    // While not necessary, it's good practice to name the effect.
                    // This name shows up when debugging the model, making it easier to understand what's happening.
                    .named { "Add 5 minutes to $launchTimer" }
            )
            launchTimer.resume()
        })
    }

    simulator.runUntil(end)
    println("=== Timer Demo ===")
    results.dump()
}

fun arithmeticDemo() {
    val start = Instant.parse("2030-01-01T00:00:00Z")
    val end = start + 1.days
    val results = MutableSimulationResults(start, end)
    val simulator = Simulator(
        reportHandler = results.reportHandler(),
        startTime = start,
    ) {
        val clock1: MutableClockResource = clock("clock1").registered()
        val clock2: MutableClockResource = clock("clock2", initialTime = start + 6.hours, initialRate = -1.0).registered()
        val timer1: MutableTimerResource = timer("timer1", initialTime = 1.hours, initialRate = 0.0).registered()
        val timer2: MutableTimerResource = timer("timer2").registered()

        // Examples of several legal ways to add and subtract clocks and timers:
        val clockDifference: TimerResource = (clock1 - clock2).named { "clockDifference" }.registered()
        val offsetClock: ClockResource = (clock1 + timer1).named { "offsetClock" }.registered()
        val timerSum: TimerResource = (timer1 + timer2).named { "timerSum" }.registered()

        // Try adding tasks and effects here to observe the derived resources react
    }

    simulator.runUntil(end)
    println("=== Arithmetic Demo ===")
    results.dump()
}

enum class State {
    OFF,
    ON,
}
