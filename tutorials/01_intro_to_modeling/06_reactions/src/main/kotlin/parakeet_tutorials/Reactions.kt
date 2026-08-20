package parakeet_tutorials

import gov.nasa.jpl.parakeet.foundation.Simulator
import gov.nasa.jpl.parakeet.foundation.reporting.Reporting.registered
import gov.nasa.jpl.parakeet.foundation.resources.discrete.BooleanResource
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations.discreteResource
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations.greaterThan
import gov.nasa.jpl.parakeet.foundation.resources.discrete.IntResourceOperations.decrement
import gov.nasa.jpl.parakeet.foundation.resources.discrete.IntResourceOperations.increment
import gov.nasa.jpl.parakeet.foundation.resources.discrete.MutableIntResource
import gov.nasa.jpl.parakeet.foundation.resources.getValue
import gov.nasa.jpl.parakeet.foundation.resources.named
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope.Companion.spawn
import gov.nasa.jpl.parakeet.foundation.tasks.Reactions.every
import gov.nasa.jpl.parakeet.foundation.tasks.Reactions.whenever
import gov.nasa.jpl.parakeet.foundation.tasks.Reactions.wheneverChanges
import gov.nasa.jpl.parakeet.foundation.tasks.ReportScope.Companion.report
import gov.nasa.jpl.parakeet.foundation.tasks.SimulationScope.Companion.stdout
import gov.nasa.jpl.parakeet.foundation.tasks.TaskOperations.delay
import gov.nasa.jpl.parakeet.foundation.tasks.task
import gov.nasa.jpl.parakeet.general.results.MutableSimulationResults
import gov.nasa.jpl.parakeet.general.results.SimulationResultsOperations.reportHandler
import kotlin.time.Instant
import parakeet_tutorials.util.Output.dump
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

fun main() {
    val start = Instant.parse("2030-01-01T00:00:00Z")
    val end = start + 1.days
    val results = MutableSimulationResults(start, end)
    val simulator = Simulator(
        reportHandler = results.reportHandler(),
        startTime = start,
    ) {
        val counter: MutableIntResource = discreteResource("counter", 0).registered()
        val counterIsLarge: BooleanResource = (counter greaterThan 5).named { "counterIsLarge" }.registered()

        // The most common reaction loop is "whenever".
        // This awaits a condition, then runs the block, then repeats.
        // Let's set up a task to decrement the counter whenever it's positive.
        // To keep things interesting, we'll add a short delay.
        spawn("Decrement Counter", whenever(counter greaterThan 0) {
            stdout.report("Counter is ${counter.getValue()}, decrementing...")
            delay(5.minutes)
            counter.decrement()
            stdout.report("Decrement complete. Counter is ${counter.getValue()}")
        })

        // We'll set up a task to increment the counter a few times, slow enough that the decrementing task above
        // can keep up, pushing the counter back down to zero every time:
        spawn("Increment Counter Slowly", task {
            delay(1.hours)
            repeat(3) {
                stdout.report("Incrementing counter slowly")
                counter.increment()
                delay(10.minutes)
            }
        })

        // We'll also set up a task to increment the counter quickly, outpacing the decrementing task.
        // This way, we'll see the counter increase above one, and watch the loop above walk it back down to zero.
        spawn("Increment Counter Quickly", task {
            delay(2.hours)
            repeat(6) {
                stdout.report("Incrementing counter quickly")
                counter.increment()
                delay(10.seconds)
            }
        })

        // There's also "onceWhenever", which is like "whenever", except it waits for the condition to be false again before repeating.
        spawn("Warn about counter", whenever(counter greaterThan 3) {
            stdout.report("Warning! Counter is ${counter.getValue()}!")
        })

        // Another less-frequently used reaction loop is "every", which runs at fixed intervals:
        spawn("Increment Counter Periodically", every(6.hours) {
            stdout.report("Incrementing counter periodically")
            counter.increment()
        })

        // And finally, there's "wheneverChanges", which runs the block whenever the indicated resources changes.
        // Actually, it's whenever the resource changes "discontinuously"... but discrete resources can only change discontinuously,
        // so that's all resource changes, at least for now.
        spawn("Alarm", wheneverChanges(counterIsLarge) {
            if (counterIsLarge.getValue()) {
                stdout.report("Alarm on! Counter is large!")
            } else {
                stdout.report("Alarm off! Counter is small!")
            }
        })
    }

    simulator.runUntil(end)
    results.dump()
}
