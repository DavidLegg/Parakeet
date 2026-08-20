package gov.nasa.jpl.parakeet.foundation.plans

import gov.nasa.jpl.parakeet.foundation.tasks.ReportScope.Companion.report
import gov.nasa.jpl.parakeet.foundation.tasks.ResourceScope.Companion.now
import gov.nasa.jpl.parakeet.foundation.tasks.SimulationScope
import gov.nasa.jpl.parakeet.foundation.tasks.SimulationScope.Companion.subSimulationScope
import gov.nasa.jpl.parakeet.foundation.tasks.TaskOperations.delayUntil
import gov.nasa.jpl.parakeet.foundation.tasks.TaskScope
import gov.nasa.jpl.parakeet.foundation.tasks.TaskScope.Companion.spawn
import gov.nasa.jpl.parakeet.foundation.tasks.coroutineTask
import gov.nasa.jpl.parakeet.foundation.tasks.task
import gov.nasa.jpl.parakeet.kernel.Name
import gov.nasa.jpl.parakeet.kernel.NameOperations.div
import gov.nasa.jpl.parakeet.kernel.tasks.KernelTask
import gov.nasa.jpl.parakeet.kernel.tasks.PureTaskStep
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlin.time.Duration
import kotlin.time.Instant

object ActivityActions {
    @Serializable
    data class ActivityEvent(
        val name: Name,
        val type: String,
        val start: Instant,
        val end: Instant? = null,
        // Report the activity instance itself, but only for in-memory usage.
        // The default serialization drops this extra detail.
        @Transient
        val activity: Activity<*>? = null,
    ) {
        override fun toString(): String =
            "ActivityEvent(name='$name', type='$type', start=$start, end=$end, activity=${if (activity == null) "null" else "not null"})"
    }

    context (scope: TaskScope)
    suspend fun <M> call(activity: FloatingActivity<M>, model: M) {
        // TODO: Should this be the full activity.name? Or just its simple name?
        context (subSimulationScope(activity.name)) {
            val startTime = now()
            scope.activities.report(ActivityEvent(
                activity.name,
                activity.activity::class.simpleName!!,
                startTime,
                activity = activity.activity,
            ))
            activity.activity.effectModel(model)
            // Report both start and end time with the activity end event.
            // This avoids needing to generate or persist unique IDs for activities.
            // Instead, any start event which matches all three serialized fields can be paired with this end event;
            // all such start events are exactly equivalent.
            scope.activities.report(ActivityEvent(
                activity.name,
                activity.activity::class.simpleName!!,
                startTime,
                now(),
                activity = activity.activity,
            ))
        }
    }

    context (scope: TaskScope)
    suspend fun <M> call(activity: Activity<M>, model: M) =
        call(FloatingActivity(Name(requireNotNull(activity::class.simpleName)), activity), model)

    context (scope: TaskScope)
    suspend fun <M> defer(time: Duration, activity: FloatingActivity<M>, model: M) =
        deferUntil(now() + time, activity, model)

    context (scope: TaskScope)
    suspend fun <M> deferUntil(time: Instant, activity: FloatingActivity<M>, model: M) {
        spawn(activity.name, task {
            delayUntil(time)
            call(activity, model)
        })
    }

    context(scope: TaskScope)
    suspend fun <M> spawn(activity: GroundedActivity<M>, model: M) =
        deferUntil(activity.time, activity.float(), model)

    context(scope: TaskScope)
    suspend fun <M> spawn(activity: FloatingActivity<M>, model: M) =
        defer(Duration.ZERO, activity, model)

    context(scope: TaskScope)
    suspend fun <M> spawn(activity: Activity<M>, model: M) =
        spawn(FloatingActivity(Name(requireNotNull(activity::class.simpleName)), activity), model)

    // Utilities for translating foundation-level activities down to kernel-level tasks.
    // toKernelTask is the fundamental source of truth on how to do this.
    // The other methods are exposed to allow simulators to do partial translations as needed.

    context(scope: SimulationScope)
    fun <M> GroundedActivity<M>.toKernelTask(model: M): KernelTask = KernelTask(
        kernelTaskName(name),
        time,
        float().toPureTaskStep(model),
    )

    fun kernelTaskName(activityName: Name): Name = Name("activities") / activityName

    context(scope: SimulationScope)
    fun <M> FloatingActivity<M>.toPureTaskStep(model: M): PureTaskStep =
        coroutineTask(task { call(this, model) })
}
