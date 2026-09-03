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
import gov.nasa.jpl.parakeet.foundation.resources.discrete.IntResourceOperations.increment
import gov.nasa.jpl.parakeet.foundation.resources.discrete.MutableDiscreteResource
import gov.nasa.jpl.parakeet.foundation.resources.emit
import gov.nasa.jpl.parakeet.foundation.resources.getValue
import gov.nasa.jpl.parakeet.foundation.resources.named
import gov.nasa.jpl.parakeet.foundation.resources.resource
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
    example1()
    example2()
    example3()
}

fun example1() {
    println("=== Example 1: Basic Concurrency ===")
    val start = Instant.parse("2030-01-01T00:00:00Z")
    val end = start + 1.days
    val results = MutableSimulationResults(start, end)
    val simulator = Simulator(
        reportHandler = results.reportHandler(),
        startTime = start,
    ) {
        val x = discreteResource("x", 0)

        spawn("T1", task {
            delay(1.hours)
            stdout.report("T1: ${x.getValue()}")
            x.increment()
            stdout.report("T1: ${x.getValue()}")
            delay(0.hours)
            stdout.report("T1: ${x.getValue()}")
        })

        spawn("T2", task {
            delay(1.hours)
            stdout.report("T2: ${x.getValue()}")
            x.increment(2)
            stdout.report("T2: ${x.getValue()}")
            delay(0.hours)
            stdout.report("T2: ${x.getValue()}")
        })
    }

    simulator.runUntil(end)
    results.dump()
    println()
}

fun example2() {
    println("=== Example 2: Fault due to Imprecise Modeling ===")
    val start = Instant.parse("2030-01-01T00:00:00Z")
    val end = start + 1.days
    val results = MutableSimulationResults(start, end)
    val simulator = Simulator(
        reportHandler = results.reportHandler(),
        startTime = start,
    ) {
        val x = discreteResource("x", 0)

        spawn("T1", task {
            delay(1.hours)
            stdout.report("T1: ${x.getValue()}")
            // Instead of using `increment` to precisely define our intent,
            // we'll use the all-too-common pattern of read, modify, write
            // x.increment()
            x.set(x.getValue() + 1)
            stdout.report("T1: ${x.getValue()}")
            delay(0.hours)
            stdout.report("T1: ${x.getValue()}")
        })

        spawn("T2", task {
            delay(1.hours)
            stdout.report("T2: ${x.getValue()}")
            // x.increment(2)
            x.set(x.getValue() + 2)
            stdout.report("T2: ${x.getValue()}")
            delay(0.hours)
            stdout.report("T2: ${x.getValue()}")
        })
    }

    // When we run this version, we'll observe a fault in x that crashed both tasks.
    simulator.runUntil(end)
    results.dump()
    println()
}

fun example3() {
    println("=== Example 3: Fault due to Ambiguous Modeling ===")
    val start = Instant.parse("2030-01-01T00:00:00Z")
    val end = start + 1.days
    val results = MutableSimulationResults(start, end)
    val simulator = Simulator(
        reportHandler = results.reportHandler(),
        startTime = start,
    ) {
        val x = discreteResource("x", 0)

        // This time, we'll be precise with our intent that T1 should increment x, and T2 should reset it to 0.
        // However, doing both concurrently is a genuinely ambiguous request to the simulator, with no single clearly-right answer.
        // Following the principle of "never wrong", the simulator cannot choose either plausible outcome, because neither is always "right".
        // Instead, it must fault x and crash the tasks.

        spawn("T1", task {
            delay(1.hours)
            stdout.report("T1: ${x.getValue()}")
            x.increment()
            stdout.report("T1: ${x.getValue()}")
            delay(0.hours)
            stdout.report("T1: ${x.getValue()}")
        })

        spawn("T2", task {
            delay(1.hours)
            stdout.report("T2: ${x.getValue()}")
            x.set(0)
            stdout.report("T2: ${x.getValue()}")
            delay(0.hours)
            stdout.report("T2: ${x.getValue()}")
        })
    }

    // When we run this version, we'll observe a fault in x that crashed both tasks.
    simulator.runUntil(end)
    results.dump()
    println()
}
