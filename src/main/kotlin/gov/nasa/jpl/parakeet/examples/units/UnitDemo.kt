package gov.nasa.jpl.parakeet.examples.units

import gov.nasa.jpl.parakeet.examples.units.DeviceIndicator.*
import gov.nasa.jpl.parakeet.examples.units.DeviceState.*
import gov.nasa.jpl.parakeet.foundation.plans.Activity
import gov.nasa.jpl.parakeet.foundation.plans.GroundedActivity
import gov.nasa.jpl.parakeet.foundation.Simulator
import gov.nasa.jpl.parakeet.foundation.plans.activities
import gov.nasa.jpl.parakeet.general.units.quantity_resource.QuantityResource
import gov.nasa.jpl.parakeet.general.resources.polynomial.PolynomialResource
import gov.nasa.jpl.parakeet.general.units.polynomial_quantity_resource.PolynomialQuantityResource
import gov.nasa.jpl.parakeet.general.units.polynomial_quantity_resource.PolynomialQuantityResourceOperations.asPolynomial
import gov.nasa.jpl.parakeet.general.units.polynomial_quantity_resource.PolynomialQuantityResourceOperations.clampedIntegral
import gov.nasa.jpl.parakeet.general.units.polynomial_quantity_resource.PolynomialQuantityResourceOperations.constant
import gov.nasa.jpl.parakeet.general.units.quantity.Quantity
import gov.nasa.jpl.parakeet.general.units.StandardUnits.HOUR
import gov.nasa.jpl.parakeet.general.units.StandardUnits.JOULE
import gov.nasa.jpl.parakeet.general.units.StandardUnits.WATT
import gov.nasa.jpl.parakeet.general.units.Unit
import gov.nasa.jpl.parakeet.general.units.UnitAware.Companion.times
import gov.nasa.jpl.parakeet.foundation.reporting.Reporting.registered
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceMonad.map
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations.discreteResource
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations.set
import gov.nasa.jpl.parakeet.foundation.resources.discrete.MutableDiscreteResource
import gov.nasa.jpl.parakeet.foundation.resources.named
import gov.nasa.jpl.parakeet.foundation.serialization.ResultSerializer
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope.Companion.subContext
import gov.nasa.jpl.parakeet.foundation.tasks.TaskScope
import gov.nasa.jpl.parakeet.general.reporting.usingEventCsvReportHandler
import gov.nasa.jpl.parakeet.general.units.UnitAware.Companion.VsQuantity.div
import gov.nasa.jpl.parakeet.general.units.UnitAware.Companion.minus
import gov.nasa.jpl.parakeet.general.units.UnitAware.Companion.plus
import gov.nasa.jpl.parakeet.general.units.UnitAware.Companion.upcast
import gov.nasa.jpl.parakeet.general.units.polynomial_quantity_resource.PolynomialQuantityResourceOperations.integral
import gov.nasa.jpl.parakeet.general.units.unit_aware_resource.UnitAwareResourceOperations.named
import gov.nasa.jpl.parakeet.general.units.unit_aware_resource.UnitAwareResourceOperations.registered
import gov.nasa.jpl.parakeet.general.units.unit_aware_resource.UnitAwareResourceOperations.unitAware
import gov.nasa.jpl.parakeet.kernel.Name
import gov.nasa.jpl.parakeet.kernel.NameOperations.div
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

// Recommended practice: Derive all the named units you're going to use in this file once, up front.
// This gives names to all the units, which will make them print prettier when debugging.
// It may also improve performance a little, since you do the unit computation once up front when loading the classes.
val MILLIWATT = Unit.derived("mW", 1e-3 * WATT)
val KILOWATT = Unit.derived("kW", 1e3 * WATT)
val KILOWATT_HOUR = Unit.derived("kWh", KILOWATT * HOUR)

fun main(args: Array<String>) {
    // Sample main function that basically hard-codes a plan
    // The point here is just to exercise the demo model, not to fully hook everything up.

    System.out.use { out ->
        out.usingEventCsvReportHandler(UnitDemo.JSON_FORMAT) { reportHandler ->
            val epoch = Instant.parse("2000-01-01T00:00:00Z")

            val simulation = Simulator(
                reportHandler,
                epoch,
                constructModel = ::UnitDemo,
            )

            // Quick hack to ensure all activities have a unique name.
            // TODO: Formalize this and incorporate it into the simulators.
            var id = 0
            fun <M> GroundedActivity(time: Instant, activity: Activity<M>) = GroundedActivity(
                time,
                Name((++id).toString()) / activity::class.simpleName!!,
                activity
            )

            simulation.apply {
                addActivity(GroundedActivity(
                    epoch + 10.minutes,
                    SwitchDevice(HEATER_A, ON)
                ))
                addActivity(GroundedActivity(
                    epoch + 20.minutes,
                    SwitchDevice(HEATER_B, STANDBY)
                ))
                addActivity(GroundedActivity(
                    epoch + 30.minutes,
                    SwitchDevice(CAMERA, STANDBY)
                ))
                addActivity(GroundedActivity(
                    epoch + 40.minutes,
                    SwitchDevice(CAMERA, ON)
                ))
                addActivity(GroundedActivity(
                    epoch + 45.minutes,
                    SwitchDevice(CAMERA, OFF)
                ))
                addActivity(GroundedActivity(
                    epoch + 50.minutes,
                    SwitchDevice(HEATER_A, STANDBY)
                ))
                addActivity(GroundedActivity(
                    epoch + 55.minutes,
                    SwitchDevice(HEATER_B, OFF)
                ))
            }

            simulation.runUntil(epoch + 2.hours)
        }
    }
}

class UnitDemo(
    context: InitScope,
) {
    val heaterA: Device
    val heaterB: Device
    val camera: Device
    val totalPowerDraw: QuantityResource
    val totalEnergyUsed: PolynomialQuantityResource

    val powerProduction: PolynomialQuantityResource
    val batteryEnergy: PolynomialQuantityResource
    val batterySOC: PolynomialResource

    init {
        with (context) {
            unitAware {
                // Note that we're free to mix units here, so long as we don't mix dimensions:
                heaterA = Device(subContext("heater_a"),
                    OFF to 0.0 * WATT,
                    STANDBY to 10.0 * MILLIWATT,
                    ON to 1.2 * WATT,
                )
                heaterB = Device(subContext("heater_b"),
                    OFF to 0.0 * WATT,
                    STANDBY to 10.0 * MILLIWATT,
                    ON to 1.2 * WATT,
                )
                camera = Device(subContext("camera"),
                    OFF to 0.0 * WATT,
                    STANDBY to 120.0 * MILLIWATT,
                    ON to 3.4 * WATT
                )

                // When we define this sum, the framework will do dimension-checks to ensure this sum is sensible.
                // Then, it'll decide on any scaling factors needed to make the units agree, and build those into the derivation.
                // During simulation, it doesn't need to repeat these steps. It just applies the scaling factor and adds the double values.
                totalPowerDraw = (heaterA.powerDraw + heaterB.powerDraw + camera.powerDraw)
                    .named { "total_power_draw" }
                    // Since we're going to register this resource, we're choosing a specific unit to register it in.
                    // Again, this conversion is dimension-checked at initialization and applied as simple double operations in simulation.
                    .registered(KILOWATT)

                // Unit-awareness also "plays nicely" with continuous resources. This lets us do unit-aware integration:
                // Note that specifying the starting value in kWh means we'll register the results in that unit too.
                totalEnergyUsed = totalPowerDraw.asPolynomial()
                    .integral("total_energy_used", 0.0 * KILOWATT_HOUR)
                    .registered(KILOWATT_HOUR)
                    .upcast()

                // We can even do complex things like clamped integration, all in a unit-aware way:
                powerProduction = constant(1.0 * WATT)
                // Note that powerProduction is in W; totalPowerDraw, in kW.
                // totalPowerDraw will implicitly be converted to W to make the derivation below sensible.
                val netPower = powerProduction - totalPowerDraw.asPolynomial()
                val maxBatteryEnergy = 1000.0 * JOULE
                batteryEnergy = netPower.clampedIntegral(
                    "battery_energy",
                    constant(0.0 * JOULE),
                    constant(maxBatteryEnergy),
                    500.0 * JOULE,
                    ).integral
                    .registered(JOULE)
                // Finally, when we expect a pure scalar (a dimensionless quantity), we can say so.
                // The framework will ensure we've cancelled all dimensions correctly, and apply any remaining scaling
                // left over from doing unit conversions (e.g., "hr / s" requires multiplying by 3600).
                batterySOC = (batteryEnergy / maxBatteryEnergy).valueIn(Unit.SCALAR)
                    .named { "battery_soc" }
                    .registered()
            }
        }
    }

    companion object {
        val JSON_FORMAT = Json {
            serializersModule = SerializersModule {
                contextual(Result::class) { ResultSerializer(it[0]) }

                activities {
                    activity(SwitchDevice::class)
                }
            }
        }
    }
}

class Device(
    context: InitScope,
    vararg powerUsage: Pair<DeviceState, Quantity>,
) {
    val state: MutableDiscreteResource<DeviceState>
    val powerDraw: QuantityResource

    init {
        unitAware {
            // For efficiency, pre-convert all the power usages to one unit, and apply that unit at the resource level
            val powerUsageMap = powerUsage.toMap().mapValues { (_, q) -> q.valueIn(MILLIWATT) }
            with (context) {
                state = discreteResource("state", OFF).registered()
                powerDraw = (map(state, powerUsageMap::getValue) * MILLIWATT)
                    .named { "power_draw" }
                    .registered(MILLIWATT)
            }
        }
    }
}

@Serializable
@SerialName("SwitchDevice")
class SwitchDevice(
    val device: DeviceIndicator,
    val state: DeviceState,
) : Activity<UnitDemo> {
    context(scope: TaskScope)
    override suspend fun effectModel(model: UnitDemo) {
        val selectedDevice = when (device) {
            HEATER_A -> model.heaterA
            HEATER_B -> model.heaterB
            CAMERA -> model.camera
        }
        selectedDevice.state.set(state)
    }
}

enum class DeviceIndicator { HEATER_A, HEATER_B, CAMERA }
enum class DeviceState { OFF, STANDBY, ON }
