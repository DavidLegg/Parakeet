package gov.nasa.jpl.parakeet.examples.orbiter.power

import gov.nasa.jpl.parakeet.foundation.plans.Plan
import gov.nasa.jpl.parakeet.foundation.Simulator
import gov.nasa.jpl.parakeet.foundation.plans.activities
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations.discreteResource
import gov.nasa.jpl.parakeet.foundation.resources.discrete.MutableDoubleResource
import gov.nasa.jpl.parakeet.foundation.serialization.ResultSerializer
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope.Companion.subContext
import gov.nasa.jpl.parakeet.general.reporting.ReportHandling.jsonlReportHandler
import gov.nasa.jpl.parakeet.utilities.Serialization.encodeToFile
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.modules.SerializersModule
import java.io.FileInputStream
import kotlin.io.path.Path

/**
 * Run [BatteryModel] as a standalone simulation.
 */
@OptIn(ExperimentalSerializationApi::class)
fun main(args: Array<String>) {
    val planFile = args[0]
    val finconFile = args[1]

    val jsonFormat = Json {
        serializersModule = SerializersModule {
            contextual(Result::class) { ResultSerializer(it[0]) }
            activities {
                activity(ChangePowerDemand::class)
            }
        }
    }
    val plan: Plan<StandaloneBatteryModel> = jsonFormat.decodeFromStream(FileInputStream(planFile))

    val simulation = Simulator(
        jsonlReportHandler(),
        plan.startTime,
        constructModel = ::StandaloneBatteryModel,
    )

    simulation.runPlan(plan)
    jsonFormat.encodeToFile(simulation.save(), Path(finconFile))
}

/**
 * Dummy interface wrapping the battery model.
 * Directly constructs the inputs for the battery model, to be poked directly by activities.
 */
class StandaloneBatteryModel(
    context: InitScope,
) {
    val powerDemand: MutableDoubleResource
    val powerProduction: MutableDoubleResource
    val battery: BatteryModel

    init {
        with (context) {
            powerDemand = discreteResource("powerDemand", 0.0)
            powerProduction = discreteResource("powerDemand", 0.0)
            battery = BatteryModel(subContext("battery"), BatterySimConfig(), powerDemand, powerProduction)
        }
    }
}
