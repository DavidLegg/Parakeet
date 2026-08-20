package parakeet_tutorials

import gov.nasa.jpl.parakeet.foundation.Simulator
import gov.nasa.jpl.parakeet.foundation.reporting.Reporting.registered
import gov.nasa.jpl.parakeet.foundation.resources.discrete.BooleanResource
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations.discreteResource
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations.greaterThan
import gov.nasa.jpl.parakeet.foundation.resources.discrete.IntResourceOperations.increment
import gov.nasa.jpl.parakeet.foundation.resources.discrete.MutableIntResource
import gov.nasa.jpl.parakeet.foundation.resources.getValue
import gov.nasa.jpl.parakeet.foundation.resources.named
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope.Companion.spawn
import gov.nasa.jpl.parakeet.foundation.tasks.ReportScope.Companion.report
import gov.nasa.jpl.parakeet.foundation.tasks.SimulationScope.Companion.stdout
import gov.nasa.jpl.parakeet.foundation.tasks.task
import gov.nasa.jpl.parakeet.general.results.MutableSimulationResults
import gov.nasa.jpl.parakeet.general.results.SimulationResultsOperations.reportHandler
import kotlin.time.Instant
import parakeet_tutorials.util.Output.dump
import kotlin.time.Duration.Companion.days

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

        // Let's try to increment our counter
        // If we uncomment the following line of code, it won't compile, saying that no 'TaskScope' is found.
        // This is because resources can only be written to by tasks, and this is not a task, this is the initialization block.
        // counter.increment()

        // Instead, we can spawn a task, and that task can do the incrementing for us.
        // When we spawn a task, we need to give it a name.
        // While there are a few different kinds of task we could use, we'll use the simplest kind, produced by the "task" function.
        spawn("Increment Counter",  task {
            // This is the body of the task. It runs after initialization, when the simulation starts.
            // In a task, we can write to resources:
            counter.increment()
            // We can also read from them:
            val n = counter.getValue()
            // And we can issue reports:
            stdout.report("Counter is $n!")
        })
    }

    // To see when the task runs, let's dump the results twice. First, before running the simulator:
    results.dump()

    println("Running simulator...")
    simulator.runUntil(end)

    // And then again after we run:
    results.dump()
}
