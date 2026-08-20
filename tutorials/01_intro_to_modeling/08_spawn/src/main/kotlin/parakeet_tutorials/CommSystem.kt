package parakeet_tutorials

import gov.nasa.jpl.parakeet.foundation.plans.ActivityActions.spawn
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations.discreteResource
import gov.nasa.jpl.parakeet.foundation.resources.discrete.MutableDiscreteResource
import gov.nasa.jpl.parakeet.foundation.resources.getValue
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope.Companion.spawn
import gov.nasa.jpl.parakeet.foundation.tasks.Reactions.every
import gov.nasa.jpl.parakeet.foundation.tasks.ReportScope.Companion.report
import gov.nasa.jpl.parakeet.foundation.tasks.SimulationScope.Companion.stdout
import gov.nasa.jpl.parakeet.foundation.tasks.TaskOperations.delay
import gov.nasa.jpl.parakeet.foundation.tasks.TaskScope
import gov.nasa.jpl.parakeet.foundation.tasks.TaskScope.Companion.spawn
import gov.nasa.jpl.parakeet.foundation.tasks.task
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class CommSystem(initScope: InitScope) {
    // In a real model, one-way light time would be an input to this model, rather than a mutable resource within it.
    // For simplicity, we're not doing that here.
    val oneWayLightTime: MutableDiscreteResource<Duration>

    init {
        context(initScope) {
            // Don't bother registering this resource. It's not important for this demo.
            oneWayLightTime = discreteResource("oneWayLightTime (s)", 30.minutes)

            // Any kind of task can spawn any kind of task.
            // To demonstrate this, suppose we are regularly uplinking a keep-alive message:
            // Here, the daemon task "Send keep-alive" will spawn a sub-task called "Uplink".
            spawn("Send keep-alive", every(6.hours) {
                downlink("keep-alive")
            })

            // Daemons can also spawn activities, though this is unusual in practice:
            spawn("Periodic downlink", every(12.hours) {
                // Notice that when we spawn an activity, we need to pass the model to it:
                spawn(DownlinkFiles("EHA", 3, 30.seconds), this)
                // This version of spawn comes from the utility class ActivityActions.
                // In that class there's also "call" for running an activity synchronously,
                // and "defer" and "deferUntil" for scheduling the activity asynchronously in the future.
            })
        }
    }

    // Once again, in a real model, you'll probably want more sophisticated handling of uplink and downlink
    // than merely printing messages to stdout, but this is sufficient for this demo.

    // By declaring this function with TaskScope context, we're saying this function must be called from a task.
    // This could be an activity or a daemon, we don't care.
    context (_: TaskScope)
    suspend fun downlink(message: String) {
        // Perform this work in a spawned sub-task, so as not to block the caller.
        // This is only possible because we have a TaskScope in our context.
        spawn("Downlink", task {
            stdout.report("S/C sends '$message'")
            delay(oneWayLightTime.getValue())
            stdout.report("Ground receives '$message'")
        })
    }
}