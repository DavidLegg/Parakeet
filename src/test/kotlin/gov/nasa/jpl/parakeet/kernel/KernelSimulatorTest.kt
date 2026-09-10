package gov.nasa.jpl.parakeet.kernel

import gov.nasa.jpl.parakeet.kernel.BasicInitScope.Companion.allocate
import gov.nasa.jpl.parakeet.kernel.BasicInitScope.Companion.read
import gov.nasa.jpl.parakeet.kernel.BasicInitScope.Companion.spawn
import gov.nasa.jpl.parakeet.kernel.DependentMap.Companion.get
import gov.nasa.jpl.parakeet.kernel.tasks.BasicTaskActions
import gov.nasa.jpl.parakeet.kernel.tasks.PureStepResult.*
import gov.nasa.jpl.parakeet.kernel.tasks.PureTask.TaskHistoryStep
import gov.nasa.jpl.parakeet.kernel.tasks.PureTask.TaskHistoryStep.*
import gov.nasa.jpl.parakeet.kernel.tasks.PureTaskStep
import gov.nasa.jpl.parakeet.kernel.tasks.TaskHistoryProvider.Companion.provide
import gov.nasa.jpl.parakeet.utilities.named
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.reflect.typeOf
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class KernelSimulatorTest {
    private data class SimulationResult(
        val reports: List<Any?>,
        val fincon: KernelCheckpoint?,
    )

    private fun runSimulation(
        duration: Duration,
        incon: KernelCheckpoint? = null,
        takeFincon: Boolean = false,
        initialize: context (BasicInitScope) () -> Unit,
    ): SimulationResult {
        // Build a simulation that'll write reports to memory
        val reports = mutableListOf<Any?>()
        val startTime = incon?.time ?: Instant.parse("2000-01-01T00:00:00Z")
        val simulation = KernelSimulator(
            reports::add,
            initialize,
            incon,
            startTime,
        )
        // Run the simulation to the end
        // More advanced simulators might apply some checks against a "stalled" simulation here.
        val endTime = startTime + duration
        while (simulation.time() < endTime) simulation.stepTo(endTime)
        // Cut a fincon, if requested
        val fincon = if (takeFincon) simulation.save() else null
        // Return all results, and let the simulation itself be garbage collected
        return SimulationResult(reports, fincon)
    }

    private fun <T> applyAllEffects(effects: List<(T) -> T>): (T) -> T = {
        var result = it
        for (effect in effects) {
            result = effect(result)
        }
        result
    }

    context (scope: BasicInitScope)
    private fun allocateIntCounterCell(name: String, value: Int) = allocate(
        Name(name),
        value,
        typeOf<Int>(),
        { x, _ -> x },
        ::applyAllEffects,
    )

    @Serializable
    private data class LinearDynamics(val value: Double, val rate: Double)
    private fun linearDynamicsStep(d: LinearDynamics, t: Duration) = LinearDynamics(d.value + d.rate * (t / 1.seconds), d.rate)
    context (scope: BasicInitScope)
    private fun allocateLinearCell(name: String, value: Double, rate: Double) = allocate(
        Name(name),
        LinearDynamics(value, rate),
        typeOf<LinearDynamics>(),
        ::linearDynamicsStep,
        ::applyAllEffects,
    )

    context (scope: BasicInitScope)
    private fun allocateClockCell(name: String, t: Duration) = allocate(
        Name(name),
        t,
        typeOf<Duration>(),
        { s, delta -> s + delta },
        ::applyAllEffects,
    )

    /**
     * "Patch" test-ism - I removed the Delay task step type in favor of using Await.
     * Rather than rewrite a bunch of tests, I'm re-building Delay in terms of Await.
     */
    private fun BasicTaskActions.Delay(time: Duration, clock: Cell<Duration>, block: PureTaskStep): Await {
        val endTime = read(clock) + time
        return Await(
            { actions: ReadActions -> SatisfiedAt(endTime - actions.read(clock)) }
                .named { "Delay until $endTime" },
            block)
    }

    @Test
    fun empty_simulation_is_valid() {
        runSimulation(1.hours) {}
    }

    @Test
    fun task_can_report_result() {
        val results = runSimulation(1.hours) {
            spawn(Name("report result")) {
                it.report("result")
                Complete
            }
        }
        assertEquals(listOf("result"), results.reports)
    }

    @Test
    fun task_can_allocate_cell() {
        runSimulation(1.hours) {
            allocateIntCounterCell("x", 42)
        }
    }

    @Test
    fun task_can_read_cell() {
        val results = runSimulation(1.hours) {
            val x = allocateIntCounterCell("x", 42)
            spawn(Name("read cell")) {
                val xVal = it.read(x)
                it.report("x = $xVal")
                Complete
            }
        }
        assertEquals(listOf("x = 42"), results.reports)
    }

    @Test
    fun task_can_read_cell_during_init() {
        runSimulation(1.hours) {
            val x = allocateIntCounterCell("x", 42)
            assertEquals(42, read(x))
        }
    }

    @Test
    fun task_can_emit_effect() {
        val results = runSimulation(1.hours) {
            val x = allocateIntCounterCell("x", 42)
            spawn(Name("emit effect")) {
                it.emit(x) { it + 13 }
                val xVal = it.read(x)
                it.report("x = $xVal")
                Complete
            }
        }
        assertEquals(listOf("x = 55"), results.reports)
    }

    // Note: There used to be an explicit "delay" StepResult, separate from Await.
    // Await has since subsumed Delay, but we're keeping this test around because later tests use "delay-style awaits"
    // to test cell stepping. I want to know that this very simple style of await works, then test that cells work,
    // then come back and test that more complicated styles of awaiting also work.
    @Test
    fun task_can_delay() {
        runSimulation(1.hours) {
            val clock = allocateClockCell("clock", ZERO)
            spawn(Name("delay")) {
                it.Delay(30.minutes, clock) {
                    Complete
                }
            }
        }
    }

    @Test
    fun cells_can_step() {
        val results = runSimulation(1.hours) {
            // This is *not* a good way to implement stepping, since multiple steps, each < 1 minute,
            // will not change the value, but a single >1 minute step would.
            // It's fine for this test, though.
            val x = allocate(Name("x"), 0, typeOf<Int>(), { x, t -> x + (t / 1.minutes).toInt() }, ::applyAllEffects)
            val clock = allocateClockCell("clock", ZERO)
            spawn(Name("step cell")) {
                val xVal = it.read(x)
                it.report("now x = $xVal")
                it.Delay(30.minutes, clock) {
                    val xVal = it.read(x)
                    it.report("later x = $xVal")
                    Complete
                }
            }
        }
        assertEquals(listOf("now x = 0", "later x = 30"), results.reports)
    }

    @Test
    fun parallel_tasks_do_not_observe_each_other() {
        val results = runSimulation(1.hours) {
            val x = allocateIntCounterCell("x", 10)
            spawn(Name("Task A")) {
                it.emit(x) { it + 5 }
                val xVal = it.read(x)
                it.report("A says: x = $xVal")
                Complete
            }
            spawn(Name("Task B")) {
                it.emit(x) { it + 3 }
                val xVal = it.read(x)
                it.report("B says: x = $xVal")
                Complete
            }
            spawn(Name("Task C")) {
                val xVal = it.read(x)
                it.report("C says: x = $xVal")
                val xVal2 = it.read(x)
                it.report("C still says: x = $xVal2")
                Complete
            }
        }
        with (results) {
            assert(reports.size == 4)
            // Order of reports is largely non-deterministic because these tasks are running in parallel
            assertContains(reports, "A says: x = 15")
            assertContains(reports, "B says: x = 13")
            assertContains(reports, "C says: x = 10")
            assertContains(reports, "C still says: x = 10")
        }
    }

    @Test
    fun parallel_tasks_join_effects_at_each_delay() {
        val results = runSimulation(1.hours) {
            val x = allocateIntCounterCell("x", 10)
            val clock = allocateClockCell("clock", ZERO)
            spawn(Name("Task A")) {
                it.emit(x) { it + 5 }
                val xVal = it.read(x)
                it.report("A says: x = $xVal")
                Complete
            }
            spawn(Name("Task B")) {
                val xVal = it.read(x)
                it.report("B first says: x = $xVal")
                it.Delay(ZERO, clock) {
                    val xVal = it.read(x)
                    it.report("B next says: x = $xVal")
                    Complete
                }
            }
        }
        with (results) {
            assert(reports.size == 3)
            // Order of reports is largely non-deterministic because these tasks are running in parallel
            assertContains(reports, "A says: x = 15")
            assertContains(reports, "B first says: x = 10")
            assertContains(reports, "B next says: x = 15")
        }
    }

    @Test
    fun concurrent_effects_are_joined_using_effect_trait() {
        val results = runSimulation(1.hours) {
            // Note: This is *not* a correct effect trait, but it's simple and lets us observe what's happening better.
            val x = allocate(Name("x"), 10, typeOf<Int>(), { x, _ -> x }, {
                effects -> { 100 + applyAllEffects(effects)(it) }
            })
            val clock = allocateClockCell("clock", ZERO)
            spawn(Name("Task A")) {
                it.emit(x) { it + 5 }
                val xVal = it.read(x)
                it.report("A says: x = $xVal")
                Complete
            }
            spawn(Name("Task B")) {
                it.emit(x) { it + 3 }
                val xVal = it.read(x)
                it.report("B says: x = $xVal")
                Complete
            }
            spawn(Name("Task C")) {
                it.Delay(ZERO, clock) {
                    val xVal = it.read(x)
                    it.report("C says: x = $xVal")
                    Complete
                }
            }
        }
        with (results) {
            assert(reports.size == 3)
            // Order of reports is largely non-deterministic because these tasks are running in parallel
            assertContains(reports, "A says: x = 15")
            assertContains(reports, "B says: x = 13")
            assertContains(reports, "C says: x = 118")
        }
    }

    @Test
    fun task_can_await_condition() {
        runSimulation(1.hours) {
            spawn(Name("Await condition")) {
                Await({ SatisfiedAt(ZERO) }) {
                    Complete
                }
            }
        }
    }

    @Test
    fun await_trivial_condition_runs_task_in_next_batch() {
        val results = runSimulation(1.hours) {
            val x = allocateIntCounterCell("x", 10)
            val clock = allocateClockCell("clock", ZERO)
            spawn(Name("Awaiter")) {
                Await({ SatisfiedAt(ZERO) }) {
                    val xVal = it.read(x)
                    it.report("Awaiter says: x = $xVal")
                    Complete
                }
            }
            spawn(Name("Counter")) {
                it.emit(x) { it + 1 }
                it.Delay(ZERO, clock) {
                    it.emit(x) { it + 1 }
                    it.Delay(ZERO, clock) {
                        it.emit(x) { it + 1 }
                        Complete
                    }
                }
            }
        }
        assertEquals(listOf("Awaiter says: x = 11"), results.reports)
    }

    @Test
    fun await_never_condition_does_not_run_task() {
        val results = runSimulation(1.hours) {
            spawn(Name("Awaiter")) {
                Await({ UnsatisfiedUntil(INFINITE) }) {
                    it.report("Awaiter ran!")
                    Complete
                }
            }
        }
        assert(results.reports.isEmpty())
    }

    @Test
    fun await_nontrivial_condition_runs_task_in_first_batch_after_condition_is_satisfied() {
        val results = runSimulation(1.hours) {
            val x = allocateIntCounterCell("x", 10)
            val y = allocateIntCounterCell("y", 12)
            val clock = allocateClockCell("clock", ZERO)
            spawn(Name("Awaiter")) {
                Await({
                    val xValue = it.read(x)
                    val yValue = it.read(y)
                    if (xValue >= yValue) SatisfiedAt(ZERO) else UnsatisfiedUntil(INFINITE)
                }) {
                    val xVal = it.read(x)
                    it.report("Awaiter says: x = $xVal")
                    val yVal = it.read(y)
                    it.report("Awaiter says: y = $yVal")
                    Complete
                }
            }
            spawn(Name("Counter")) {
                it.emit(x) { it + 1 }
                it.Delay(ZERO, clock) {
                    it.emit(y) { it - 1 }
                    it.Delay(ZERO, clock) {
                        it.emit(x) { it + 1 }
                        Complete
                    }
                }
            }
        }
        assertEquals(listOf("Awaiter says: x = 11", "Awaiter says: y = 11"), results.reports)
    }

    @Test
    fun await_nonzero_condition_waits_specified_time() {
        val results = runSimulation(1.hours) {
            val x = allocateLinearCell("x", 10.0, 1.0)
            spawn(Name("Awaiter")) {
                Await({
                    // Example implementation of a "greater than 20" condition for a linear dynamics type.
                    with (it.read(x)) {
                        when {
                            value >= 20 -> SatisfiedAt(ZERO)
                            rate <= 0 -> UnsatisfiedUntil(INFINITE)
                            else -> SatisfiedAt(((20 - value) / rate).seconds)
                        }
                    }
                }) {
                    val xVal = it.read(x)
                    it.report(xVal)
                    Complete
                }
            }
        }
        assertEquals(1, results.reports.size)
        assertNearlyEquals(20.0, (results.reports[0] as LinearDynamics).value)
        assertNearlyEquals(1.0, (results.reports[0] as LinearDynamics).rate)
    }

    @Test
    fun await_nonzero_condition_can_be_interrupted() {
        val results = runSimulation(1.hours) {
            val x = allocateLinearCell("x", 10.0, 1.0)
            val y = allocateIntCounterCell("y", 0)
            val clock = allocateClockCell("clock", ZERO)
            spawn(Name("Awaiter")) {
                Await({
                    // Example implementation of a "greater than 20" condition for a linear dynamics type.
                    with (it.read(x)) {
                        when {
                            value >= 20 -> SatisfiedAt(ZERO)
                            rate <= 0 -> UnsatisfiedUntil(INFINITE)
                            else -> SatisfiedAt(((20 - value) / rate).seconds)
                        }
                    }
                }) {
                    val xVal = it.read(x)
                    it.report(xVal)
                    val yVal = it.read(y)
                    it.report("Awaiter says: y = $yVal")
                    Complete
                }
            }
            spawn(Name("Interrupter")) {
                it.emit(y) { it + 1 }
                it.Delay(6.seconds, clock) {
                    it.emit(y) { it + 1 }
                    it.emit(x) { LinearDynamics(19.0, -0.5) }
                    it.Delay(20.minutes, clock) {
                        it.emit(y) { it + 1 }
                        it.emit(x) { LinearDynamics(35.0, 0.0) }
                        Complete
                    }
                }
            }
        }

        assertEquals(2, results.reports.size)
        assertNearlyEquals(35.0, (results.reports[0] as LinearDynamics).value)
        assertNearlyEquals(0.0, (results.reports[0] as LinearDynamics).rate)
        // Condition is satisfied in reaction to last batch of the interrupter task, which is why y = 3
        assertEquals("Awaiter says: y = 3", results.reports[1])
    }

    @Test
    fun await_nonzero_condition_can_wait_after_interruption() {
        val results = runSimulation(1.hours) {
            val x = allocateLinearCell("x", 10.0, 1.0)
            val y = allocateIntCounterCell("y", 0)
            val clock = allocateClockCell("clock", ZERO)
            spawn(Name("Awaiter")) {
                Await({
                    // Example implementation of a "greater than 20" condition for a linear dynamics type.
                    with (it.read(x)) {
                        when {
                            value >= 20 -> SatisfiedAt(ZERO)
                            rate <= 0 -> UnsatisfiedUntil(INFINITE)
                            else -> SatisfiedAt(((20 - value) / rate).seconds)
                        }
                    }
                }) {
                    val xVal = it.read(x)
                    it.report(xVal)
                    val yVal = it.read(y)
                    it.report("Awaiter says: y = $yVal")
                    Complete
                }
            }
            spawn(Name("Interrupter")) {
                it.emit(y) { it + 1 }
                it.Delay(6.seconds, clock) {
                    it.emit(y) { it + 1 }
                    it.emit(x) { LinearDynamics(19.0, -0.5) }
                    it.Delay(20.minutes, clock) {
                        it.emit(y) { it + 1 }
                        it.emit(x) { LinearDynamics(19.0, 0.1) }
                        Complete
                    }
                }
            }
        }

        assertEquals(2, results.reports.size)
        // Condition isn't satisfied until it waits long enough for x to grow to 20
        assertNearlyEquals(20.0, (results.reports[0] as LinearDynamics).value)
        assertNearlyEquals(0.1, (results.reports[0] as LinearDynamics).rate)
        assertEquals("Awaiter says: y = 3", results.reports[1])
    }

    @Test
    fun unsatisfied_condition_will_be_reevaluated() {
        val results = runSimulation(1.hours) {
            val x = allocateLinearCell("x", 10.0, 1.0)
            spawn(Name("Awaiter")) {
                Await({
                    if (it.read(x).value >= 15) SatisfiedAt(ZERO)
                    else UnsatisfiedUntil(2.seconds)
                }) {
                    val xVal = it.read(x)
                    it.report(xVal)
                    Complete
                }
            }
        }

        assertEquals(1, results.reports.size)
        // Condition repeatedly delays re-evaluation for 2s, so value is 16 by the time it's satisfied
        assertNearlyEquals(16.0, (results.reports[0] as LinearDynamics).value)
        assertNearlyEquals(1.0, (results.reports[0] as LinearDynamics).rate)
    }

    @Test
    fun unsatisfied_condition_reevaluation_can_be_interrupted() {
        val results = runSimulation(1.hours) {
            val x = allocateLinearCell("x", 10.0, 1.0)
            val clock = allocateClockCell("clock", ZERO)
            spawn(Name("Awaiter")) {
                Await({
                    if (it.read(x).value >= 15) SatisfiedAt(ZERO)
                    else UnsatisfiedUntil(1.minutes)
                }) {
                    val xVal = it.read(x)
                    it.report(xVal)
                    Complete
                }
            }

            spawn(Name("Interrupter")) {
                it.Delay(5.seconds, clock) {
                    it.emit(x) { LinearDynamics(20.0, 0.0) }
                    Complete
                }
            }
        }

        assertEquals(1, results.reports.size)
        // Condition evaluates at first to Unsatisfied, with a 1-minute expiry.
        // That's interrupted by the interrupter task, which sets the value to 20.
        // Had it not been interrupted, it would have grown to 70.
        assertNearlyEquals(20.0, (results.reports[0] as LinearDynamics).value)
        assertNearlyEquals(0.0, (results.reports[0] as LinearDynamics).rate)
    }

    @Test
    fun empty_simulation_can_be_saved() {
        val results = runSimulation(1.minutes, takeFincon = true) {}
        with (results.fincon!!) {
            assertEquals(Instant.parse("2000-01-01T00:01:00Z"), time)
        }
    }

    @Test
    fun cells_can_be_saved() {
        context (scope: BasicInitScope)
        fun initialize() {
            val x = allocateLinearCell("x", 10.0, 1.0)
            val y = allocateLinearCell("y", 10.0, -0.1)
        }
        val results = runSimulation(1.minutes, takeFincon = true) { initialize() }
        with (results.fincon!!) {
            assertEquals(Instant.parse("2000-01-01T00:01:00Z"), time)
            with (assertNotNull(cells.get<LinearDynamics>(Name("x")))) {
                assertNearlyEquals(70.0, value)
                assertNearlyEquals(1.0, rate)
            }
            with (assertNotNull(cells.get<LinearDynamics>(Name("y")))) {
                assertNearlyEquals(4.0, value)
                assertNearlyEquals(-0.1, rate)
            }
        }
    }

    @Test
    fun cells_can_be_restored() {
        context (scope: BasicInitScope)
        fun initialize() {
            val x = allocateLinearCell("x", 10.0, 1.0)
            val y = allocateLinearCell("y", 10.0, -0.1)
        }
        val results = runSimulation(1.minutes, takeFincon = true) { initialize() }
        runSimulation(2.minutes, incon=results.fincon) { initialize() }
    }

    @Test
    fun tasks_can_be_saved() {
        context (scope: BasicInitScope)
        fun initialize() {
            val x = allocateLinearCell("x", 10.0, 1.0)
            val y = allocateLinearCell("y", 10.0, -0.1)
            val clock = allocateClockCell("clock", ZERO)

            spawn(Name("Complete Immediately")) {
                Complete
            }
            spawn(Name("Single Batch Task")) {
                val xDynamics = it.read(x)
                val yDynamics = it.read(y)
                it.report(mapOf("tag" to "Single Batch Task", "x" to xDynamics, "y" to yDynamics))
                Complete
            }
            spawn(Name("Multi Batch Task")) {
                val xDynamics = it.read(x)
                val yDynamics = it.read(y)
                it.report(mapOf("tag" to "Multi Batch Task - 1", "x" to xDynamics, "y" to yDynamics))
                it.Delay(90.seconds, clock) {
                    val xDynamics = it.read(x)
                    val yDynamics = it.read(y)
                    it.report(mapOf("tag" to "Multi Batch Task - 2", "x" to xDynamics, "y" to yDynamics))
                    Complete
                }
            }
        }

        val results = runSimulation(1.minutes, takeFincon = true) { initialize() }

        with (results.fincon!!) {
            assertEquals(Instant.parse("2000-01-01T00:01:00Z"), time)
            with (assertNotNull(cells.get<LinearDynamics>(Name("x")))) {
                assertNearlyEquals(70.0, value)
                assertNearlyEquals(1.0, rate)
            }
            with (assertNotNull(cells.get<LinearDynamics>(Name("y")))) {
                assertNearlyEquals(4.0, value)
                assertNearlyEquals(-0.1, rate)
            }
            assertEquals(3, tasks.size)
            with (tasks.get(0)) {
                assertEquals(Name("Complete Immediately"), name)
                assertEquals(Name("Complete Immediately"), root)
                assertNull(time)
                assertNull(history)
            }
            with (tasks.get(1)) {
                assertEquals(Name("Multi Batch Task"), name)
                assertEquals(Name("Multi Batch Task"), root)
                assertEquals(Instant.parse("2000-01-01T00:01:00Z"), time)
                val historyProvider = assertNotNull(history).provider()
                with (assertNotNull(historyProvider.provide<ReadMarker<LinearDynamics>>(ReadMarker.concreteType(typeOf<LinearDynamics>()))).value) {
                    assertNearlyEquals(10.0, value)
                    assertNearlyEquals(1.0, rate)
                }
                with (assertNotNull(historyProvider.provide<ReadMarker<LinearDynamics>>(ReadMarker.concreteType(typeOf<LinearDynamics>()))).value) {
                    assertNearlyEquals(10.0, value)
                    assertNearlyEquals(-0.1, rate)
                }
                // Implicit read of the current time to do Delay
                assertEquals(ReadMarker(0.seconds), historyProvider.provide<ReadMarker<Duration>>(ReadMarker.concreteType(typeOf<Duration>())))
                assertFalse(historyProvider.hasNext())
            }
            with (tasks.get(2)) {
                assertEquals(Name("Single Batch Task"), name)
                assertEquals(Name("Single Batch Task"), root)
                assertNull(time)
                assertNull(history)
            }
        }
    }

    @Test
    fun tasks_can_be_restored() {
        context (scope: BasicInitScope)
        fun initialize() {
            val x = allocateLinearCell("x", 10.0, 1.0)
            val y = allocateLinearCell("y", 10.0, -0.1)
            val clock = allocateClockCell("clock", ZERO)

            spawn(Name("Complete Immediately")) {
                Complete
            }
            spawn(Name("Single Batch Task")) {
                val xDynamics = it.read(x)
                val yDynamics = it.read(y)
                // Add a delay 0 to make the report order deterministic, for easier verification
                it.Delay(ZERO, clock) {
                    it.report(mapOf("tag" to "Single Batch Task", "x" to xDynamics, "y" to yDynamics))
                    Complete
                }
            }
            spawn(Name("Multi Batch Task")) {
                val xDynamics = it.read(x)
                val yDynamics = it.read(y)
                it.report(mapOf("tag" to "Multi Batch Task - 1", "x" to xDynamics, "y" to yDynamics))
                // Since this delay spans a fincon boundary, do the delay correctly by reading a clock.
                // Abusing Await like I've done elsewhere, directly returning the time, causes buggy behavior with fincons.
                it.Delay(90.seconds, clock) {
                    val xDynamics = it.read(x)
                    val yDynamics = it.read(y)
                    it.report(mapOf("tag" to "Multi Batch Task - 2", "x" to xDynamics, "y" to yDynamics))
                    Complete
                }
            }
        }

        val results = runSimulation(1.minutes, takeFincon = true) { initialize() }

        assertEquals(2, results.reports.size)
        assertEquals("Multi Batch Task - 1", (results.reports[0] as Map<*, *>)["tag"])
        assertNearlyEquals(10.0, ((results.reports[0] as Map<*, *>)["x"] as LinearDynamics).value)
        assertNearlyEquals(1.0, ((results.reports[0] as Map<*, *>)["x"] as LinearDynamics).rate)
        assertNearlyEquals(10.0, ((results.reports[0] as Map<*, *>)["y"] as LinearDynamics).value)
        assertNearlyEquals(-0.1, ((results.reports[0] as Map<*, *>)["y"] as LinearDynamics).rate)
        assertEquals("Single Batch Task", (results.reports[1] as Map<*, *>)["tag"])
        assertNearlyEquals(10.0, ((results.reports[1] as Map<*, *>)["x"] as LinearDynamics).value)
        assertNearlyEquals(1.0, ((results.reports[1] as Map<*, *>)["x"] as LinearDynamics).rate)
        assertNearlyEquals(10.0, ((results.reports[1] as Map<*, *>)["y"] as LinearDynamics).value)
        assertNearlyEquals(-0.1, ((results.reports[1] as Map<*, *>)["y"] as LinearDynamics).rate)
        // No report for Multi Batch Task - 2, because the simulation ended during its delay

        val nextResults = runSimulation(2.minutes, incon = results.fincon) { initialize() }

        // There should be no reports for Single Batch or the first part of Multi Batch,
        // because those tasks have already run.
        // Internally, we'll replay them to get to the part of the task we'd like to resume,
        // but officially, they aren't part of the simulation execution & output.
        assertEquals(1, nextResults.reports.size)
        assertEquals("Multi Batch Task - 2", (nextResults.reports[0] as Map<*, *>)["tag"])
        assertNearlyEquals(100.0, ((nextResults.reports[0] as Map<*, *>)["x"] as LinearDynamics).value)
        assertNearlyEquals(1.0, ((nextResults.reports[0] as Map<*, *>)["x"] as LinearDynamics).rate)
        assertNearlyEquals(1.0, ((nextResults.reports[0] as Map<*, *>)["y"] as LinearDynamics).value)
        assertNearlyEquals(-0.1, ((nextResults.reports[0] as Map<*, *>)["y"] as LinearDynamics).rate)
    }

    @Test
    fun restart_tasks_can_run_indefinitely() {
        val results = runSimulation(1.hours) {
            val x = allocateIntCounterCell("x", 0)
            val clock = allocateClockCell("clock", ZERO)
            spawn(Name("Repeater")) {
                it.Delay(10.minutes, clock) {
                    it.emit(x) { it + 1 }
                    val time = it.read(clock)
                    val xVal = it.read(x)
                    it.report("x = $xVal at $time")
                    Restart
                }
            }
        }

        assertEquals(listOf(
            "x = 1 at 10m",
            "x = 2 at 20m",
            "x = 3 at 30m",
            "x = 4 at 40m",
            "x = 5 at 50m",
        ), results.reports)
    }

    @Test
    fun restart_tasks_save_fincon_data_since_last_restart_only() {
        val results = runSimulation(59.minutes, takeFincon = true) {
            val x = allocateIntCounterCell("x", 0)
            val clock = allocateClockCell("clock", ZERO)
            spawn(Name("Repeater")) {
                it.Delay(10.minutes, clock) {
                    it.emit(x) { it + 1 }
                    val time = it.read(clock)
                    val xVal = it.read(x)
                    it.report("x = $xVal at $time")
                    Restart
                }
            }
        }
        with (results.fincon!!) {
            assertEquals(Instant.parse("2000-01-01T00:59:00Z"), time)
            assertEquals(5, cells.get<Int>(Name("x")))
            assertEquals(59.minutes, cells.get<Duration>(Name("clock")))
            with (tasks.single()) {
                assertEquals(Instant.parse("2000-01-01T00:59:00Z"), time)
                assertEquals(Name("Repeater"), name)
                assertEquals(Name("Repeater"), root)
                val historyProvider = assertNotNull(history).provider()
                assertEquals(50.minutes, historyProvider.provide<ReadMarker<Duration>>(ReadMarker.concreteType(typeOf<Duration>()))?.value)
                assertFalse(historyProvider.hasNext())
            }
        }
    }

    @Test
    fun restart_tasks_replay_from_last_restart_when_restoring() {
        // This is a deeply cursed way to leak state out of the simulation.
        // This should never be done in production, but for the sake of testing,
        // it's a useful way to observe the replay, when that replay *should* be invisible.
        var plays = 0
        context (scope: BasicInitScope)
        fun initialize() {
            val x = allocateIntCounterCell("x", 0)
            val clock = allocateClockCell("clock", ZERO)
            spawn(Name("Repeater")) {
                plays++
                it.Delay(10.minutes, clock) {
                    it.emit(x) { it + 1 }
                    val time = it.read(clock)
                    val xVal = it.read(x)
                    it.report("x = $xVal at $time")
                    Restart
                }
            }
        }

        val results = runSimulation(59.minutes, takeFincon = true) { initialize() }
        assertEquals(6, plays)
        plays = 0

        runSimulation(6.minutes, incon = results.fincon) { initialize() }
        // 1 play during the "restore" phase, to get the task re-initialized
        // 1 play during actual simulation, at time = 1 hour
        assertEquals(2, plays)
    }

    @Test
    fun tasks_can_spawn_children() {
        val results = runSimulation(1.hours) {
            spawn(Name("Parent")) {
                it.report("Parent's report")
                Spawn(Name("Child"), {
                    it.report("Child's report")
                    Complete
                }) {
                    Complete
                }
            }
        }
        assertEquals(listOf("Parent's report", "Child's report"), results.reports)
    }

    @Test
    fun child_tasks_run_in_parallel_with_parent_and_each_other() {
        val results = runSimulation(1.hours) {
            var x = allocateIntCounterCell("x", 0)
            val clock = allocateClockCell("clock", ZERO)

            spawn(Name("Counter")) {
                it.emit(x) { it + 1 }
                it.Delay(ZERO, clock) {
                    it.emit(x) { it + 1 }
                    it.Delay(ZERO, clock) {
                        it.emit(x) { it + 1 }
                        it.Delay(ZERO, clock) {
                            it.emit(x) { it + 1 }
                            Complete
                        }
                    }
                }
            }

            spawn(Name("P")) {
                val xVal = it.read(x)
                it.report("Tick 0: P says: x = $xVal")
                Spawn(Name("C1"), {
                    val xVal = it.read(x)
                    it.report("Tick 1: C1 says: x = $xVal")
                    it.Delay(ZERO, clock) {
                        val xVal = it.read(x)
                        it.report("Tick 2: C1 says: x = $xVal")
                        Complete
                    }
                }) {
                    Spawn(Name("C2"), {
                        val xVal = it.read(x)
                        it.report("Tick 1: C2 says: x = $xVal")
                        it.Delay(ZERO, clock) {
                            val xVal = it.read(x)
                            it.report("Tick 2: C2 says: x = $xVal")
                            Complete
                        }
                    }) {
                        it.Delay(ZERO, clock) {
                            val xVal = it.read(x)
                            it.report("Tick 1: P says: x = $xVal")
                            it.Delay(ZERO, clock) {
                                val xVal = it.read(x)
                                it.report("Tick 2: P says: x = $xVal")
                                Complete
                            }
                        }
                    }
                }
            }
        }

        with (results) {
            assertEquals(7, reports.size)
            assertEquals("Tick 0: P says: x = 0", reports[0])
            // Ordering of reports in the same tick is non-deterministic
            assertEquals(
                setOf(
                    "Tick 1: P says: x = 1",
                    "Tick 1: C1 says: x = 1",
                    "Tick 1: C2 says: x = 1",
                ),
                reports.subList(1, 4).toSet())
            assertEquals(
                setOf(
                    "Tick 2: P says: x = 2",
                    "Tick 2: C1 says: x = 2",
                    "Tick 2: C2 says: x = 2",
                ),
                reports.subList(4, 7).toSet())
        }
    }

    @Test
    fun child_tasks_can_be_saved() {
        val results = runSimulation(1.hours, takeFincon = true) {
            val clock = allocateClockCell("clock", ZERO)
            spawn(Name("P")) {
                it.report("P -- 1")
                Spawn(Name("C"), {
                    it.report("C -- 1")
                    it.Delay(45.minutes, clock) {
                        // 00:45:00
                        it.report("C -- 2")
                        Complete
                    }
                }) {
                    it.Delay(30.minutes, clock) {
                        // 00:30:00
                        it.report("P -- 2")
                        Spawn(Name("D"), {
                            it.report("D -- 1")
                            it.Delay(45.minutes, clock) {
                                // 01:15:00
                                it.report("D -- 2")
                                Complete
                            }
                        }) {
                            it.Delay(1.hours, clock) {
                                // 01:30:00
                                it.report("P -- 3")
                                Complete
                            }
                        }
                    }
                }
            }
        }
        with (results.fincon!!) {
            assertEquals(Instant.parse("2000-01-01T01:00:00Z"), time)
            assertEquals(2, tasks.size)
            with (tasks[0]) {
                assertEquals(Name("D"), name)
                assertEquals(Name("P"), root)
                assertEquals(Instant.parse("2000-01-01T01:00:00Z"), time)
                val historyProvider = assertNotNull(history).provider()
                assertEquals(SpawnMarker(SpawnMarkerBranch.Parent), historyProvider.provide<TaskHistoryStep>())
                assertEquals(ReadMarker(0.seconds), historyProvider.provide<ReadMarker<Duration>>(ReadMarker.concreteType(typeOf<Duration>())))
                assertEquals(AwaitMarker, historyProvider.provide<TaskHistoryStep>())
                assertEquals(SpawnMarker(SpawnMarkerBranch.Child), historyProvider.provide<TaskHistoryStep>())
                assertEquals(ReadMarker(30.minutes), historyProvider.provide<ReadMarker<Duration>>(ReadMarker.concreteType(typeOf<Duration>())))
                assertFalse(historyProvider.hasNext())
            }
            with (tasks[1]) {
                assertEquals(Name("P"), name)
                assertEquals(Name("P"), root)
                assertEquals(Instant.parse("2000-01-01T01:00:00Z"), time)
                val historyProvider = assertNotNull(history).provider()
                assertEquals(SpawnMarker(SpawnMarkerBranch.Parent), historyProvider.provide<TaskHistoryStep>())
                assertEquals(ReadMarker(0.seconds), historyProvider.provide<ReadMarker<Duration>>(ReadMarker.concreteType(typeOf<Duration>())))
                assertEquals(AwaitMarker, historyProvider.provide<TaskHistoryStep>())
                assertEquals(SpawnMarker(SpawnMarkerBranch.Parent), historyProvider.provide<TaskHistoryStep>())
                assertEquals(ReadMarker(30.minutes), historyProvider.provide<ReadMarker<Duration>>(ReadMarker.concreteType(typeOf<Duration>())))
                assertFalse(historyProvider.hasNext())
            }
        }
    }

    @Test
    fun child_tasks_can_be_restored() {
        context (scope: BasicInitScope)
        fun initialize() {
            val clock = allocateClockCell("clock", ZERO)
            spawn(Name("P")) {
                it.report("P -- 1")
                Spawn(Name("C"), {
                    it.report("C -- 1")
                    it.Delay(45.minutes, clock) {
                        // 00:45:00
                        it.report("C -- 2")
                        Complete
                    }
                }) {
                    it.Delay(30.minutes, clock) {
                        // 00:30:00
                        it.report("P -- 2")
                        Spawn(Name("D"), {
                            it.report("D -- 1")
                            it.Delay(45.minutes, clock) {
                                // 01:15:00
                                it.report("D -- 2")
                                Complete
                            }
                        }) {
                            it.Delay(1.hours, clock) {
                                // 01:30:00
                                it.report("P -- 3")
                                Complete
                            }
                        }
                    }
                }
            }
        }
        val results = runSimulation(1.hours, takeFincon = true) { initialize() }

        assertEquals(listOf(
            "P -- 1",
            "C -- 1",
            "P -- 2",
            "D -- 1",
            "C -- 2",
        ), results.reports)

        val nextResults = runSimulation(2.hours, incon = results.fincon) { initialize() }

        assertEquals(listOf("D -- 2", "P -- 3"), nextResults.reports)
    }

    @Test
    fun children_of_repeating_tasks_can_be_restored() {
        context (scope: BasicInitScope)
        fun initialize() {
            val n = allocateIntCounterCell("n", 1)
            val clock = allocateClockCell("clock", ZERO)

            spawn(Name("Repeater")) {
                it.Delay(10.minutes, clock) {
                    val nValue = it.read(n)
                    Spawn(Name("Child $nValue"), {
                        val time = it.read(clock)
                        it.report("Child $nValue start at $time")
                        it.Delay(45.minutes, clock) {
                            val time = it.read(clock)
                            it.report("Child $nValue end at $time")
                            Complete
                        }
                    }) {
                        it.emit(n) { it + 1 }
                        Restart
                    }
                }
            }
        }
        val results = runSimulation(58.minutes, takeFincon = true) { initialize() }

        assertEquals(listOf(
            "Child 1 start at 10m",
            "Child 2 start at 20m",
            "Child 3 start at 30m",
            "Child 4 start at 40m",
            "Child 5 start at 50m",
            "Child 1 end at 55m",
        ), results.reports)

        val nextResults = runSimulation(2.hours - 58.minutes, incon = results.fincon) { initialize() }

        assertEquals(listOf(
            "Child 6 start at 1h",
            "Child 2 end at 1h 5m",
            "Child 7 start at 1h 10m",
            "Child 3 end at 1h 15m",
            "Child 8 start at 1h 20m",
            "Child 4 end at 1h 25m",
            "Child 9 start at 1h 30m",
            "Child 5 end at 1h 35m",
            "Child 10 start at 1h 40m",
            "Child 6 end at 1h 45m",
            "Child 11 start at 1h 50m",
            "Child 7 end at 1h 55m",
        ), nextResults.reports)
    }

    @Test
    fun repeating_children_can_be_restored() {
        context (scope: BasicInitScope)
        fun initialize() {
            val n = allocateIntCounterCell("n", 1)
            val clock = allocateClockCell("clock", ZERO)

            spawn(Name("Parent")) {
                it.Delay(5.minutes, clock) {
                    Spawn(Name("Repeater"), {
                        it.Delay(10.minutes, clock) {
                            val time = it.read(clock)
                            val nVal = it.read(n)
                            it.report("Iteration $nVal at $time")
                            it.emit(n) { it + 1 }
                            Restart
                        }
                    }) {
                        Complete
                    }
                }
            }
        }

        val results = runSimulation(1.hours, takeFincon = true) { initialize() }

        assertEquals(listOf(
            "Iteration 1 at 15m",
            "Iteration 2 at 25m",
            "Iteration 3 at 35m",
            "Iteration 4 at 45m",
            "Iteration 5 at 55m",
        ), results.reports)

        val nextResults = runSimulation(1.hours, incon = results.fincon) { initialize() }

        assertEquals(listOf(
            "Iteration 6 at 1h 5m",
            "Iteration 7 at 1h 15m",
            "Iteration 8 at 1h 25m",
            "Iteration 9 at 1h 35m",
            "Iteration 10 at 1h 45m",
            "Iteration 11 at 1h 55m",
        ), nextResults.reports)
    }

    @Test
    fun grandchild_tasks_can_be_restored() {
        context (scope: BasicInitScope)
        fun initialize() {
            val clock = allocateClockCell("clock", ZERO)
            spawn(Name("P")) {
                it.report("P -- 1")
                Spawn(Name("C"), {
                    it.report("C -- 1")
                    Spawn(Name("GC"), {
                        it.report("GC -- 1")
                        it.Delay(90.minutes, clock) {
                            it.report("GC -- 2")
                            Complete
                        }
                    }) {
                        it.report("C -- 2")
                        Complete
                    }
                }) {
                    it.report("P -- 2")
                    Complete
                }
            }
        }

        val results = runSimulation(1.hours, takeFincon = true) { initialize() }

        assertEquals(listOf(
            "P -- 1",
            "P -- 2",
            "C -- 1",
            "C -- 2",
            "GC -- 1",
        ), results.reports)

        val nextResults = runSimulation(2.hours, incon = results.fincon) { initialize() }

        assertEquals(listOf("GC -- 2"), nextResults.reports)
    }

    @Test
    fun tasks_running_at_fincon_time_can_be_restored() {
        // I'm concerned there could be a gap if tasks run in parallel with the fincon collector.
        // Stress test this by spawning a bunch of tasks, from init and from the sim itself, that'll run exactly at fincon time.
        context (scope: BasicInitScope)
        fun initialize() {
            val clock = allocateClockCell("clock", ZERO)
            for (i in 1..100) {
                // Case A - Task delays directly to fincon time
                spawn(Name("A$i")) {
                    it.report("A$i -- 1")
                    it.Delay(1.minutes, clock) {
                        it.report("A$i -- 2")
                        it.Delay(10.seconds, clock) {
                            it.report("A$i -- 3")
                            Complete
                        }
                    }
                }

                // Case B - Task delays to fincon time after first batch
                //   Fincon task delays to fincon time directly, so if there are effects due to the order that tasks
                //   are added to the queue, this hopes to provoke those effects.
                spawn(Name("B$i")) {
                    it.report("B$i -- 1")
                    it.Delay(30.seconds, clock) {
                        it.Delay(30.seconds, clock) {
                            it.report("B$i -- 2")
                            it.Delay(10.seconds, clock) {
                                it.report("B$i -- 3")
                                Complete
                            }
                        }
                    }
                }

                // Case C - Children spawned at fincon time
                spawn(Name("C$i")) {
                    it.report("C$i -- 1")
                    it.Delay(1.minutes, clock) {
                        Spawn(Name("C${i}_child"), {
                            it.report("C$i -- 2")
                            it.Delay(10.seconds, clock) {
                                it.report("C$i -- 3")
                                Complete
                            }
                        }) {
                            it.Delay(10.seconds, clock) {
                                Complete
                            }
                        }
                    }
                }
            }
        }
        val results = runSimulation(1.minutes, takeFincon = true) { initialize() }
        val nextResults = runSimulation(2.minutes, incon = results.fincon) { initialize() }

        // For every task, assert that it was properly saved and restored by seeing the 3rd report from it.
        for (i in 1..100) {
            for (t in listOf("A", "B", "C")) {
                assertContains(nextResults.reports, "$t$i -- 3")
            }
        }
    }
}

private fun assertNearlyEquals(expected: Double, actual: Double) {
    assert(abs(expected - actual) < 1e-5)
}
