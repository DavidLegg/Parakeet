package gov.nasa.jpl.parakeet.foundation.tasks

import gov.nasa.jpl.parakeet.foundation.plans.ActivityActions.ActivityEvent
import gov.nasa.jpl.parakeet.foundation.reporting.Channel
import gov.nasa.jpl.parakeet.foundation.reporting.ChannelReport
import gov.nasa.jpl.parakeet.foundation.reporting.ChannelReport.ChannelData
import gov.nasa.jpl.parakeet.foundation.reporting.ChannelReport.ChannelMetadata
import gov.nasa.jpl.parakeet.kernel.Effect
import gov.nasa.jpl.parakeet.foundation.resources.Resource
import gov.nasa.jpl.parakeet.foundation.resources.clock.Clock
import gov.nasa.jpl.parakeet.foundation.resources.clock.ClockResourceOperations.clock
import gov.nasa.jpl.parakeet.foundation.resources.getValue
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope.Companion.channel
import gov.nasa.jpl.parakeet.foundation.tasks.ResourceScope.Companion.now
import gov.nasa.jpl.parakeet.foundation.tasks.SimulationScope.Companion.simulationClock
import gov.nasa.jpl.parakeet.foundation.tasks.SimulationScope.Companion.subSimulationScope
import gov.nasa.jpl.parakeet.kernel.BasicInitScope
import gov.nasa.jpl.parakeet.kernel.Cell
import gov.nasa.jpl.parakeet.kernel.Condition
import gov.nasa.jpl.parakeet.kernel.Name
import gov.nasa.jpl.parakeet.kernel.NameOperations.div
import gov.nasa.jpl.parakeet.utilities.Reflection.withArg
import kotlin.contracts.ExperimentalContracts
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A context for all the "global" conveniences offered by foundation during simulation.
 */
interface SimulationScope {
    /**
     * A hierarchical [Name] that can be used to indicate "where" in the model or simulation we are.
     *
     * This is primarily used to name resources and tasks, for reporting results and debugging purposes.
     */
    val contextName: Name?

    /**
     * Primary simulation clock. Simulation time should be derived from this clock.
     */
    val simulationClock: Resource<Clock>

    /**
     * Standard channel for reporting [ActivityEvent]s for all activities.
     */
    val activities: Channel<ActivityEvent>

    /**
     * Standard channel for general-purpose "logging" style output
     */
    val stdout: Channel<String>

    /**
     * Standard channel for general-purpose "logging" style output that signals some kind of warning or error.
     */
    val stderr: Channel<String>

    companion object {
        context (scope: SimulationScope)
        val simulationClock get() = scope.simulationClock

        context (scope: SimulationScope)
        val activities get() = scope.activities

        context (scope: SimulationScope)
        val stdout get() = scope.stdout

        context (scope: SimulationScope)
        val stderr get() = scope.stderr

        context (scope: SimulationScope)
        fun subSimulationScope(contextName: Name) = object : SimulationScope by scope {
            override val contextName = scope.contextName / contextName
        }
    }
}

interface ResourceScope : SimulationScope {
    fun <V> read(cell: Cell<V>): V

    companion object {
        context (scope: ResourceScope)
        fun now() = simulationClock.getValue()
    }
}

interface ReportScope : SimulationScope {
    fun <T> report(channel: Channel<T>, value: T)

    companion object {
        context (scope: ReportScope)
        fun <T> Channel<T>.report(value: T) = scope.report(this, value)
    }
}

interface ConditionScope : ResourceScope

interface TaskScope : ResourceScope, ReportScope {
    fun <V> emit(cell: Cell<V>, effect: Effect<V>)
    suspend fun await(condition: Condition)
    suspend fun spawn(childName: Name, child: suspend context (TaskScope) () -> TaskScopeResult)

    companion object {
        context (scope: TaskScope)
        suspend fun await(condition: Condition) = scope.await(condition)

        context (scope: TaskScope)
        suspend fun spawn(childName: Name, child: suspend context (TaskScope) () -> TaskScopeResult) = scope.spawn(childName, child)

        context (scope: TaskScope)
        suspend fun spawn(childName: String, child: suspend context (TaskScope) () -> TaskScopeResult) = spawn(Name(childName), child)
    }
}

// Note: We specifically don't implement BasicInitScope here.
//   We want to supplant its methods with higher-level methods that change its signature somehow.
// TODO: Allocate/read/emit on Resource, instead of Cell, to force all cell allocation to hide behind a resource.
interface InitScope : SimulationScope, ResourceScope, ReportScope {
    fun <T: Any> allocate(
        name: Name,
        value: T,
        valueType: KType,
        stepBy: (T, Duration) -> T,
        applyConcurrentEffects: (List<Effect<T>>, T) -> T,
    ): Cell<T>

    /**
     * Spawn a regular task, which will run when the simulation starts
     */
    fun spawn(name: Name, block: suspend context (TaskScope) () -> TaskScopeResult)

    fun <T> channel(name: Name, metadata: Map<String, ChannelReport.Metadatum>, valueType: KType): Channel<T>

    companion object {
        context (scope: InitScope)
        fun <T: Any> allocate(
            name: Name,
            value: T,
            valueType: KType,
            stepBy: (T, Duration) -> T,
            applyConcurrentEffects: (List<Effect<T>>, T) -> T,
        ): Cell<T> = scope.allocate(name, value, valueType, stepBy, applyConcurrentEffects)

        context (scope: InitScope)
        fun spawn(name: Name, block: suspend context (TaskScope) () -> TaskScopeResult) = scope.spawn(name, block)

        context (scope: InitScope)
        fun spawn(name: String, block: suspend context (TaskScope) () -> TaskScopeResult) = spawn(Name(name), block)

        /**
         * Adds [contextName] to the naming context, adding it as a level in the namespace of all resources and tasks
         * allocated through the returned [InitScope].
         */
        context (scope: InitScope)
        fun subContext(contextName: String) = object : InitScope by scope {
            override val contextName get() = scope.contextName / contextName

            override fun <T : Any> allocate(
                name: Name,
                value: T,
                valueType: KType,
                stepBy: (T, Duration) -> T,
                applyConcurrentEffects: (List<Effect<T>>, T) -> T,
            ): Cell<T> = scope.allocate(Name(contextName) / name, value, valueType, stepBy, applyConcurrentEffects)

            override fun spawn(name: Name, block: suspend context (TaskScope) () -> TaskScopeResult) =
                scope.spawn(Name(contextName) / name, block)
        }

        /**
         * Runs [block] in a sub-scope of [InitScope], adding [contextName] to all resources' and tasks' namespaces.
         */
        @OptIn(ExperimentalContracts::class)
        context (scope: InitScope)
        inline fun <R> subContext(contextName: String, block: context (InitScope) () -> R): R {
            kotlin.contracts.contract {
                callsInPlace(block, kotlin.contracts.InvocationKind.EXACTLY_ONCE)
            }
            return block(subContext(contextName))
        }

        context (scope: InitScope)
        inline fun <reified T> channel(name: Name, vararg metadata: Pair<String, ChannelReport.Metadatum>) =
            scope.channel<T>(name, metadata.toMap(), typeOf<T>())
    }
}

/**
 * Construct a foundation-level [InitScope] by wrapping a kernel-level [BasicInitScope]
 */
context (basicInitScope: BasicInitScope)
fun InitScope(startTime: Instant): InitScope = object : InitScope {
    override fun <T : Any> allocate(
        name: Name,
        value: T,
        valueType: KType,
        stepBy: (T, Duration) -> T,
        applyConcurrentEffects: (List<Effect<T>>, T) -> T,
    ): Cell<T> = basicInitScope.allocate(name, value, valueType, stepBy, applyConcurrentEffects)

    override fun spawn(name: Name, block: suspend context(TaskScope) () -> TaskScopeResult) =
        // When spawning a task, build a simulation scope which incorporates the task's Name
        basicInitScope.spawn(name, context(subSimulationScope(contextName / name)) { coroutineTask(block) })

    override fun <V> read(cell: Cell<V>): V = basicInitScope.read(cell)
    override fun <T> report(channel: Channel<T>, value: T) = basicInitScope.report(
        ChannelData(
            channel.name,
            now(),
            value
        )
    )

    override fun <T> channel(
        name: Name,
        metadata: Map<String, ChannelReport.Metadatum>,
        valueType: KType
    ): Channel<T> {
        val reportType = ChannelData::class.withArg(valueType)
        basicInitScope.report(
            ChannelMetadata<T>(
                name,
                metadata,
                dataType = valueType,
                reportType = reportType,
                metadataType = ChannelMetadata::class.withArg(valueType),
            )
        )
        return Channel(name, reportType)
    }

    override val contextName: Name? = null
    override fun toString() = ""

    override val simulationClock = clock("simulation_clock", startTime)

    override val activities = channel<ActivityEvent>(Name("activities"))
    override val stdout = channel<String>(Name("stdout"))
    override val stderr = channel<String>(Name("stderr"))
}
