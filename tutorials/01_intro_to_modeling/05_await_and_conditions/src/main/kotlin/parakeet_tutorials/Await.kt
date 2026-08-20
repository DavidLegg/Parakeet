package parakeet_tutorials

import gov.nasa.jpl.parakeet.foundation.Simulator
import gov.nasa.jpl.parakeet.foundation.reporting.Reporting.registered
import gov.nasa.jpl.parakeet.foundation.resources.discrete.BooleanResource
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations.discreteResource
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations.equals
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations.greaterThan
import gov.nasa.jpl.parakeet.foundation.resources.discrete.IntResourceOperations.decrement
import gov.nasa.jpl.parakeet.foundation.resources.discrete.IntResourceOperations.increment
import gov.nasa.jpl.parakeet.foundation.resources.discrete.MutableIntResource
import gov.nasa.jpl.parakeet.foundation.resources.getValue
import gov.nasa.jpl.parakeet.foundation.resources.named
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope.Companion.spawn
import gov.nasa.jpl.parakeet.foundation.tasks.Reactions.await
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

        // We'll set up a task to walk the counter up to 6:
        spawn("Increment Counter",  task {
            delay(1.hours)
            repeat(6) {
                counter.increment()
                delay(10.minutes)
            }
        })

        // Now we'll set up another task to walk the counter back down to 0:
        spawn("Reset Counter", task {
            // A boolean resource functionally "is" a condition, so we can wait for it to be true:
            await(counterIsLarge)
            repeat(counter.getValue()) {
                delay(5.minutes)
                counter.decrement()
            }
        })

        // We don't have to build the resource and give it a name, though.
        // We can (and often do) build a resource on the fly to await it:
        spawn("Warn about the counter", task {
            await(counter greaterThan 3)
            stdout.report("Counter is ${counter.getValue()}!")
            await(counter equals 0)
            stdout.report("Counter is back to 0!")
        })
    }

    simulator.runUntil(end)
    results.dump()
}
