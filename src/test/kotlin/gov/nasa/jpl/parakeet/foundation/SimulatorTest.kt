package gov.nasa.jpl.parakeet.foundation

import gov.nasa.jpl.parakeet.foundation.SimulationResultsAssertions.checkActivities
import gov.nasa.jpl.parakeet.foundation.SimulationResultsAssertions.checkChannel
import gov.nasa.jpl.parakeet.foundation.SimulationResultsAssertions.finished
import gov.nasa.jpl.parakeet.foundation.SimulationResultsAssertions.reports
import gov.nasa.jpl.parakeet.foundation.SimulationResultsAssertions.reportsDiscrete
import gov.nasa.jpl.parakeet.foundation.SimulationResultsAssertions.unfinished
import gov.nasa.jpl.parakeet.foundation.SimulatorTest.ModelWithResources.DummyActivity
import gov.nasa.jpl.parakeet.foundation.SimulatorTest.PowerState.*
import gov.nasa.jpl.parakeet.foundation.SimulatorTest.TestModel.*
import gov.nasa.jpl.parakeet.foundation.plans.*
import gov.nasa.jpl.parakeet.foundation.plans.ActivityActions.spawn
import gov.nasa.jpl.parakeet.foundation.reporting.Reporting.registered
import gov.nasa.jpl.parakeet.foundation.resources.discrete.*
import gov.nasa.jpl.parakeet.foundation.resources.discrete.BooleanResourceOperations.and
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceMonad.map
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations.discreteResource
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations.equals
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations.greaterThan
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations.notEquals
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations.set
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DoubleResourceOperations.increase
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DoubleResourceOperations.plus
import gov.nasa.jpl.parakeet.foundation.resources.getValue
import gov.nasa.jpl.parakeet.foundation.resources.named
import gov.nasa.jpl.parakeet.foundation.serialization.ResultSerializer
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope.Companion.spawn
import gov.nasa.jpl.parakeet.foundation.tasks.Reactions.await
import gov.nasa.jpl.parakeet.foundation.tasks.Reactions.whenever
import gov.nasa.jpl.parakeet.foundation.tasks.ReportScope.Companion.report
import gov.nasa.jpl.parakeet.foundation.tasks.SimulationScope.Companion.stdout
import gov.nasa.jpl.parakeet.foundation.tasks.TaskOperations.delay
import gov.nasa.jpl.parakeet.foundation.tasks.TaskScope
import gov.nasa.jpl.parakeet.foundation.tasks.task
import gov.nasa.jpl.parakeet.general.reporting.ReportHandling.discardReports
import gov.nasa.jpl.parakeet.general.results.MutableSimulationResults
import gov.nasa.jpl.parakeet.general.results.SimulationResultsOperations.reportHandler
import gov.nasa.jpl.parakeet.kernel.Name
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.modules.SerializersModule
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class SimulatorTest {
    class EmptyModel(context: InitScope)

    @Test
    fun empty_model_can_be_created() {
        assertDoesNotThrow {
            val epoch = Instant.parse("2020-01-01T00:00:00Z")
            val simulation = Simulator(
                reportHandler = discardReports,
                startTime = epoch,
                constructModel = ::EmptyModel,
            )
            simulation.runUntil(Instant.parse("2020-01-01T01:00:00Z"))
        }
    }

    class ModelWithResources {
        val intR: MutableIntResource
        val longR: MutableLongResource
        val boolR: MutableBooleanResource
        val stringR: MutableStringResource
        val doubleR: MutableDoubleResource
        val floatR: MutableFloatResource
        val enumR: MutableDiscreteResource<PowerState>

        constructor(context: InitScope) {
            with(context) {
                intR = discreteResource("intR", 0).registered()
                longR = discreteResource("longR", 1L).registered()
                boolR = discreteResource("boolR", false).registered()
                stringR = discreteResource("stringR", "string value").registered()
                doubleR = discreteResource("doubleR", 2.0).registered()
                floatR = discreteResource("floatR", 3.0f).registered()
                enumR = discreteResource("enumR", OFF).registered()

                assertEquals(0, intR.getValue())
                assertEquals(1L, longR.getValue())
                assertEquals(false, boolR.getValue())
                assertEquals("string value", stringR.getValue())
                assertEquals(2.0, doubleR.getValue())
                assertEquals(3.0f, floatR.getValue())
                assertEquals(OFF, enumR.getValue())

                spawn("Reader", task {
                    assertEquals(0, intR.getValue())
                    assertEquals(1L, longR.getValue())
                    assertEquals(false, boolR.getValue())
                    assertEquals("string value", stringR.getValue())
                    assertEquals(2.0, doubleR.getValue())
                    assertEquals(3.0f, floatR.getValue())
                    assertEquals(OFF, enumR.getValue())

                    contextOf<TaskScope>().stdout.report("Reader done")
                })
            }
        }

        @Serializable
        class DummyActivity() : Activity<ModelWithResources> {
            context(scope: TaskScope)
            override suspend fun effectModel(model: ModelWithResources) {}
        }

        companion object {
            val JSON_FORMAT = Json {
                serializersModule = SerializersModule {
                    contextual(Result::class) { ResultSerializer(it[0]) }

                    activities {
                        activity(DummyActivity::class)
                    }
                }
            }
        }
    }

    @Test
    fun model_with_resources_can_be_created() {
        val reports = MutableSimulationResults()
        val epoch = Instant.parse("2020-01-01T00:00:00Z")
        val simulation = Simulator(
            reportHandler = reports.reportHandler(),
            startTime = epoch,
            constructModel = ::ModelWithResources,
        )
        simulation.runUntil(Instant.parse("2020-01-01T01:00:00Z"))

        with (reports) {
            checkChannel<Discrete<Int>>("intR") {
                reportsDiscrete("2020-01-01T00:00:00Z", 0)
            }
            checkChannel<Discrete<Long>>("longR") {
                reportsDiscrete("2020-01-01T00:00:00Z", 1L)
            }
            checkChannel<Discrete<Boolean>>("boolR") {
                reportsDiscrete("2020-01-01T00:00:00Z", false)
            }
            checkChannel<Discrete<String>>("stringR") {
                reportsDiscrete("2020-01-01T00:00:00Z", "string value")
            }
            checkChannel<Discrete<Double>>("doubleR") {
                reportsDiscrete("2020-01-01T00:00:00Z", 2.0)
            }
            checkChannel<Discrete<Float>>("floatR") {
                reportsDiscrete("2020-01-01T00:00:00Z", 3.0f)
            }
            checkChannel<Discrete<PowerState>>("enumR") {
                reportsDiscrete("2020-01-01T00:00:00Z", OFF)
            }
            // Ensure the reader task finished, which proves those assertions ran
            checkChannel<String>("stdout") {
                reports("2020-01-01T00:00:00Z", "Reader done")
            }
        }
    }

    @Test
    fun activities_can_be_created() {
        val reports = MutableSimulationResults()
        val epoch = Instant.parse("2020-01-01T00:00:00Z")
        val simulation = Simulator(
            reportHandler = reports.reportHandler(),
            startTime = epoch,
            constructModel = ::ModelWithResources,
        )
        simulation.runPlan(
            Plan(
                epoch,
                epoch + 1.hours,
                listOf(
                    GroundedActivity(Instant.parse("2020-01-01T00:05:00Z"), Name("Activity 1"), DummyActivity()),
                    GroundedActivity(Instant.parse("2020-01-01T00:15:00Z"), Name("Activity 2"), DummyActivity()),
                    GroundedActivity(Instant.parse("2020-01-01T00:45:00Z"), Name("Activity 3"), DummyActivity()),
                )
            )
        )
        simulation.runUntil(Instant.parse("2020-01-01T01:00:00Z"))

        with (reports) {
            checkActivities {
                finished("Activity 1", "2020-01-01T00:05:00Z", "2020-01-01T00:05:00Z")
                finished("Activity 2", "2020-01-01T00:15:00Z", "2020-01-01T00:15:00Z")
                finished("Activity 3", "2020-01-01T00:45:00Z", "2020-01-01T00:45:00Z")
            }
        }
    }

    enum class PowerState { OFF, WARMUP, STANDBY, ON, SHUTDOWN }

    class TestModel {
        val deviceState: MutableDiscreteResource<PowerState>
        val powerTable: Map<PowerState, Double>
        val miscPower: MutableDoubleResource
        val totalPower: DoubleResource

        constructor(context: InitScope) {
            with(context) {
                deviceState = discreteResource("deviceState", OFF).registered()
                powerTable = mapOf(
                    OFF to 0.0,
                    WARMUP to 5.0,
                    STANDBY to 1.0,
                    ON to 10.0,
                    SHUTDOWN to 1.0,
                )
                miscPower = discreteResource("miscPower", 0.0).registered()
                totalPower = (map(deviceState, powerTable::getValue) + miscPower)
                    .named { "totalPower" }
                    .registered()

                spawn("Overheat Protection", whenever(
                    (totalPower greaterThan 15.0) and (deviceState notEquals OFF)) {
                    contextOf<TaskScope>().stderr.report("Overheat Protection triggered!")
                    spawn(DeviceShutdown(), this@TestModel)
                    await(deviceState equals OFF)
                })
            }
        }

        @Serializable
        class DeviceBoot() : Activity<TestModel> {
            context(scope: TaskScope)
            override suspend fun effectModel(model: TestModel) {
                when (model.deviceState.getValue()) {
                    OFF, WARMUP -> {
                        model.deviceState.set(WARMUP)
                        delay(5.minutes)
                        model.deviceState.set(STANDBY)
                    }
                    else -> model.deviceState.set(STANDBY)
                }
            }
        }

        @Serializable
        data class DeviceActivate(val duration: Duration) : Activity<TestModel> {
            context(scope: TaskScope)
            override suspend fun effectModel(model: TestModel) {
                if (model.deviceState.getValue() != STANDBY) {
                    spawn(DeviceBoot(), model)
                    await(model.deviceState equals STANDBY)
                }
                model.deviceState.set(ON)
                delay(duration)
                model.deviceState.set(STANDBY)
            }
        }

        @Serializable
        class DeviceShutdown() : Activity<TestModel> {
            context(scope: TaskScope)
            override suspend fun effectModel(model: TestModel) {
                model.deviceState.set(SHUTDOWN)
                delay(5.minutes)
                model.deviceState.set(OFF)
            }
        }

        @Serializable
        class ReportDeviceState : Activity<TestModel> {
            context(scope: TaskScope)
            override suspend fun effectModel(model: TestModel) {
                stdout.report("RDS: deviceState = ${model.deviceState.getValue()}")
            }
        }

        @Serializable
        class AddMiscPower(val amount: Double) : Activity<TestModel> {
            context(scope: TaskScope)
            override suspend fun effectModel(model: TestModel) {
                model.miscPower.increase(amount)
            }
        }

        companion object {
            val JSON_FORMAT = Json {
                serializersModule = SerializersModule {
                    contextual(Result::class) { ResultSerializer(it[0]) }

                    activities {
                        activity(DeviceBoot::class)
                        activity(DeviceActivate::class)
                        activity(DeviceShutdown::class)
                        activity(ReportDeviceState::class)
                        activity(AddMiscPower::class)
                    }
                }
            }
        }
    }

    @Test
    fun activities_can_interact_with_model() {
        val reports = MutableSimulationResults()
        val epoch = Instant.parse("2020-01-01T00:00:00Z")
        val simulation = Simulator(
            reportHandler = reports.reportHandler(),
            startTime = epoch,
            constructModel = ::TestModel,
        )
        simulation.runPlan(
            Plan(
                epoch,
                epoch + 2.hours + 5.minutes,
                listOf(
                    GroundedActivity(Instant.parse("2020-01-01T00:05:00Z"), Name("DeviceBoot"), DeviceBoot()),
                    GroundedActivity(Instant.parse("2020-01-01T00:15:00Z"), Name("Observation 1"), DeviceActivate(10.minutes)),
                    GroundedActivity(Instant.parse("2020-01-01T00:26:00Z"), Name("DeviceShutdown1"), DeviceShutdown()),
                    GroundedActivity(Instant.parse("2020-01-01T01:00:00Z"), Name("Observation 2"), DeviceActivate(20.minutes)),
                    GroundedActivity(Instant.parse("2020-01-01T01:30:00Z"), Name("DeviceShutdown2"), DeviceShutdown()),
                    GroundedActivity(Instant.parse("2020-01-01T01:40:00Z"), Name("Observation 3"), DeviceActivate(60.minutes)),
                    GroundedActivity(Instant.parse("2020-01-01T01:50:00Z"), Name("AddMiscPower1"), AddMiscPower(3.0)),
                    GroundedActivity(Instant.parse("2020-01-01T01:55:00Z"), Name("AddMiscPower2"), AddMiscPower(3.0)),
                ),
            )
        )

        with (reports) {
            checkActivities {
                finished("DeviceBoot", "2020-01-01T00:05:00Z", "2020-01-01T00:10:00Z")
                finished("Observation 1", "2020-01-01T00:15:00Z", "2020-01-01T00:25:00Z")
                finished("DeviceShutdown1", "2020-01-01T00:26:00Z", "2020-01-01T00:31:00Z")
                finished("DeviceBoot", "2020-01-01T01:00:00Z", "2020-01-01T01:05:00Z")
                finished("Observation 2", "2020-01-01T01:00:00Z", "2020-01-01T01:25:00Z")
                finished("DeviceShutdown2", "2020-01-01T01:30:00Z", "2020-01-01T01:35:00Z")
                finished("DeviceBoot", "2020-01-01T01:40:00Z", "2020-01-01T01:45:00Z")
                unfinished("Observation 3", "2020-01-01T01:40:00Z")
                finished("AddMiscPower1", "2020-01-01T01:50:00Z")
                finished("AddMiscPower2", "2020-01-01T01:55:00Z")
                finished("DeviceShutdown", "2020-01-01T01:55:00Z", "2020-01-01T02:00:00Z")
            }
            checkChannel<String>("stderr") {
                reports("2020-01-01T01:55:00Z", "Overheat Protection triggered!")
            }
            checkChannel<Discrete<PowerState>>("deviceState") {
                reportsDiscrete("2020-01-01T00:00:00Z", OFF)
                reportsDiscrete("2020-01-01T00:05:00Z", WARMUP)
                reportsDiscrete("2020-01-01T00:10:00Z", STANDBY)
                reportsDiscrete("2020-01-01T00:15:00Z", ON)
                reportsDiscrete("2020-01-01T00:25:00Z", STANDBY)
                reportsDiscrete("2020-01-01T00:26:00Z", SHUTDOWN)
                reportsDiscrete("2020-01-01T00:31:00Z", OFF)
                reportsDiscrete("2020-01-01T01:00:00Z", WARMUP)
                reportsDiscrete("2020-01-01T01:05:00Z", STANDBY)
                reportsDiscrete("2020-01-01T01:05:00Z", ON)
                reportsDiscrete("2020-01-01T01:25:00Z", STANDBY)
                reportsDiscrete("2020-01-01T01:30:00Z", SHUTDOWN)
                reportsDiscrete("2020-01-01T01:35:00Z", OFF)
                reportsDiscrete("2020-01-01T01:40:00Z", WARMUP)
                reportsDiscrete("2020-01-01T01:45:00Z", STANDBY)
                reportsDiscrete("2020-01-01T01:45:00Z", ON)
                reportsDiscrete("2020-01-01T01:55:00Z", SHUTDOWN)
                reportsDiscrete("2020-01-01T02:00:00Z", OFF)
            }
            checkChannel<Discrete<Double>>("miscPower") {
                reportsDiscrete("2020-01-01T00:00:00Z", 0.0)
                reportsDiscrete("2020-01-01T01:50:00Z", 3.0)
                reportsDiscrete("2020-01-01T01:55:00Z", 6.0)
            }
            checkChannel<Discrete<Double>>("totalPower") {
                reportsDiscrete("2020-01-01T00:00:00Z", 0.0)
                reportsDiscrete("2020-01-01T00:05:00Z", 5.0)
                reportsDiscrete("2020-01-01T00:10:00Z", 1.0)
                reportsDiscrete("2020-01-01T00:15:00Z", 10.0)
                reportsDiscrete("2020-01-01T00:25:00Z", 1.0)
                reportsDiscrete("2020-01-01T00:31:00Z", 0.0)
                reportsDiscrete("2020-01-01T01:00:00Z", 5.0)
                reportsDiscrete("2020-01-01T01:05:00Z", 1.0)
                reportsDiscrete("2020-01-01T01:05:00Z", 10.0)
                reportsDiscrete("2020-01-01T01:25:00Z", 1.0)
                reportsDiscrete("2020-01-01T01:35:00Z", 0.0)
                reportsDiscrete("2020-01-01T01:40:00Z", 5.0)
                reportsDiscrete("2020-01-01T01:45:00Z", 1.0)
                reportsDiscrete("2020-01-01T01:45:00Z", 10.0)
                reportsDiscrete("2020-01-01T01:50:00Z", 13.0)
                reportsDiscrete("2020-01-01T01:55:00Z", 16.0)
                reportsDiscrete("2020-01-01T01:55:00Z", 7.0)
                reportsDiscrete("2020-01-01T02:00:00Z", 6.0)
            }
        }
    }

    @Test
    fun activities_can_be_saved_and_restored_in_memory() {
        val t0 = Instant.parse("2020-01-01T00:00:00Z")
        val t1 = Instant.parse("2020-01-01T00:20:00Z")
        val reports1 = MutableSimulationResults(t0, t1)
        val simulation1 = Simulator(
            reportHandler = reports1.reportHandler(),
            startTime = t0,
            constructModel = ::TestModel,
        )
        // Use addActivities and runUntil to force a state with finished, running, and unstarted activities
        simulation1.apply {
            addActivity(GroundedActivity(Instant.parse("2020-01-01T00:03:00Z"), Name("DeviceBoot"), DeviceBoot()))
            addActivity(GroundedActivity(Instant.parse("2020-01-01T00:10:00Z"), Name("DeviceActivate"), DeviceActivate(60.minutes)))
            addActivity(GroundedActivity(Instant.parse("2020-01-01T01:20:00Z"), Name("DeviceShutdown"), DeviceShutdown()))
        }

        simulation1.runUntil(t1)
        with (reports1) {
            checkActivities {
                finished("DeviceBoot", "2020-01-01T00:03:00Z", "2020-01-01T00:08:00Z")
                unfinished("DeviceActivate", "2020-01-01T00:10:00Z")
            }
        }

        val fincon1 = simulation1.save()

        val t2 = Instant.parse("2020-01-01T02:00:00Z")
        val reports2 = MutableSimulationResults(t1, t2)
        val simulation2 = Simulator(
            reportHandler = reports2.reportHandler(),
            incon = fincon1,
            constructModel = ::TestModel,
        )
        // Add an activity which will spawn a child, which will be active during the next fincon cycle
        simulation2.addActivity(GroundedActivity(Instant.parse("2020-01-01T01:58:00Z"), Name("DeviceActivate2"), DeviceActivate(20.minutes)))
        simulation2.runUntil(t2)

        with (reports2) {
            checkActivities {
                finished("DeviceActivate", "2020-01-01T00:10:00Z", "2020-01-01T01:10:00Z", false)
                finished("DeviceShutdown", "2020-01-01T01:20:00Z", "2020-01-01T01:25:00Z")
                unfinished("DeviceActivate2", "2020-01-01T01:58:00Z")
                unfinished("DeviceBoot", "2020-01-01T01:58:00Z")
            }
        }

        val fincon2 = simulation2.save()

        val t3 = Instant.parse("2020-01-01T03:00:00Z")
        val reports3 = MutableSimulationResults(t2, t3)
        val simulation3 = Simulator(
            reportHandler = reports3.reportHandler(),
            incon = fincon2,
            constructModel = ::TestModel,
        )
        simulation3.runUntil(t3)
        with(reports3) {
            checkActivities {
                finished("DeviceBoot", "2020-01-01T01:58:00Z", "2020-01-01T02:03:00Z", false)
                finished("DeviceActivate2", "2020-01-01T01:58:00Z", "2020-01-01T02:23:00Z", false)
            }
        }
    }

    @Test
    fun activities_can_be_saved_and_restored_to_json() {
        val t0 = Instant.parse("2020-01-01T00:00:00Z")
        val t1 = Instant.parse("2020-01-01T00:20:00Z")
        val reports1 = MutableSimulationResults(t0, t1)
        val epoch = Instant.parse("2020-01-01T00:00:00Z")
        val simulation1 = Simulator(
            reportHandler = reports1.reportHandler(),
            startTime = epoch,
            constructModel = ::TestModel,
        )
        // Use addActivities and runUntil to force a state with finished, running, and unstarted activities
        simulation1.apply {
            addActivity(GroundedActivity(Instant.parse("2020-01-01T00:03:00Z"), Name("DeviceBoot"), DeviceBoot()))
            addActivity(GroundedActivity(Instant.parse("2020-01-01T00:10:00Z"), Name("DeviceActivate"), DeviceActivate(60.minutes)))
            addActivity(GroundedActivity(Instant.parse("2020-01-01T01:20:00Z"), Name("DeviceShutdown"), DeviceShutdown()))
        }

        simulation1.runUntil(t1)
        with (reports1) {
            checkActivities {
                finished("DeviceBoot", "2020-01-01T00:03:00Z", "2020-01-01T00:08:00Z")
                unfinished("DeviceActivate", "2020-01-01T00:10:00Z")
            }
        }

        val fincon1 = TestModel.JSON_FORMAT.encodeToJsonElement(simulation1.save())

        val t2 = Instant.parse("2020-01-01T02:00:00Z")
        val reports2 = MutableSimulationResults(t1, t2)
        val simulation2 = Simulator(
            reportHandler = reports2.reportHandler(),
            incon = TestModel.JSON_FORMAT.decodeFromJsonElement<Checkpoint<TestModel>>(fincon1),
            constructModel = ::TestModel,
        )
        // Add an activity which will spawn a child, which will be active during the next fincon cycle
        simulation2.addActivity(GroundedActivity(Instant.parse("2020-01-01T01:58:00Z"), Name("DeviceActivate2"), DeviceActivate(20.minutes)))
        simulation2.runUntil(Instant.parse("2020-01-01T02:00:00Z"))

        with (reports2) {
            checkActivities {
                finished("DeviceActivate", "2020-01-01T00:10:00Z", "2020-01-01T01:10:00Z", false)
                finished("DeviceShutdown", "2020-01-01T01:20:00Z", "2020-01-01T01:25:00Z")
                unfinished("DeviceActivate2", "2020-01-01T01:58:00Z")
                unfinished("DeviceBoot", "2020-01-01T01:58:00Z")
            }
        }

        val fincon2 = TestModel.JSON_FORMAT.encodeToJsonElement(simulation2.save())

        val t3 = Instant.parse("2020-01-01T03:00:00Z")
        val reports3 = MutableSimulationResults(t2, t3)
        val simulation3 = Simulator(
            reportHandler = reports3.reportHandler(),
            incon = TestModel.JSON_FORMAT.decodeFromJsonElement<Checkpoint<TestModel>>(fincon2),
            constructModel = ::TestModel,
        )
        simulation3.runUntil(Instant.parse("2020-01-01T03:00:00Z"))
        with(reports3) {
            checkActivities {
                finished("DeviceBoot", "2020-01-01T01:58:00Z", "2020-01-01T02:03:00Z", false)
                finished("DeviceActivate2", "2020-01-01T01:58:00Z", "2020-01-01T02:23:00Z", false)
            }
        }
    }

    @Test
    fun `concurrent activities do not observe each other`() {
        val reports = MutableSimulationResults()
        val epoch = Instant.parse("2020-01-01T00:00:00Z")
        val simulation = Simulator(
            reportHandler = reports.reportHandler(),
            startTime = epoch,
            constructModel = ::TestModel,
        )
        simulation.runPlan(
            Plan(
                epoch,
                epoch + 2.hours + 5.minutes,
                listOf(
                    GroundedActivity(Instant.parse("2020-01-01T00:00:00Z"), Name("ReportDeviceState1"), ReportDeviceState()),
                    GroundedActivity(Instant.parse("2020-01-01T00:00:00Z"), Name("DeviceBoot2"), DeviceBoot()),
                    GroundedActivity(Instant.parse("2020-01-01T00:00:00Z"), Name("ReportDeviceState3"), ReportDeviceState()),
                    GroundedActivity(Instant.parse("2020-01-01T00:30:00Z"), Name("ReportDeviceState4"), ReportDeviceState()),
                    GroundedActivity(Instant.parse("2020-01-01T00:30:00Z"), Name("DeviceShutdown5"), DeviceShutdown()),
                    GroundedActivity(Instant.parse("2020-01-01T00:30:00Z"), Name("ReportDeviceState6"), ReportDeviceState()),
                    GroundedActivity(Instant.parse("2020-01-01T01:00:00Z"), Name("ReportDeviceState7"), ReportDeviceState()),
                    GroundedActivity(Instant.parse("2020-01-01T01:00:00Z"), Name("DeviceBoot8"), DeviceBoot()),
                    GroundedActivity(Instant.parse("2020-01-01T01:00:00Z"), Name("ReportDeviceState9"), ReportDeviceState()),
                    GroundedActivity(Instant.parse("2020-01-01T01:30:00Z"), Name("ReportDeviceState10"), ReportDeviceState()),
                    GroundedActivity(Instant.parse("2020-01-01T01:30:00Z"), Name("DeviceShutdown11"), DeviceShutdown()),
                    GroundedActivity(Instant.parse("2020-01-01T01:30:00Z"), Name("ReportDeviceState12"), ReportDeviceState()),
                ),
            )
        )
        with (reports) {
            checkChannel("stdout") {
                // Both ReportDeviceStates should see the value of deviceState prior to the change by the concurrent activity.
                reports("2020-01-01T00:00:00Z", "RDS: deviceState = OFF")
                reports("2020-01-01T00:00:00Z", "RDS: deviceState = OFF")
                reports("2020-01-01T00:30:00Z", "RDS: deviceState = STANDBY")
                reports("2020-01-01T00:30:00Z", "RDS: deviceState = STANDBY")
                reports("2020-01-01T01:00:00Z", "RDS: deviceState = OFF")
                reports("2020-01-01T01:00:00Z", "RDS: deviceState = OFF")
                reports("2020-01-01T01:30:00Z", "RDS: deviceState = STANDBY")
                reports("2020-01-01T01:30:00Z", "RDS: deviceState = STANDBY")
            }
        }
    }
}
