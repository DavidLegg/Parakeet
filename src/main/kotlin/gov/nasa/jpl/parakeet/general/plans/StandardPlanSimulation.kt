package gov.nasa.jpl.parakeet.general.plans

import gov.nasa.jpl.parakeet.foundation.plans.Plan
import gov.nasa.jpl.parakeet.foundation.Simulator
import gov.nasa.jpl.parakeet.foundation.plans.Checkpoint
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope
import gov.nasa.jpl.parakeet.general.reporting.ParallelReportHandler.Companion.inParallel
import gov.nasa.jpl.parakeet.general.reporting.usingEventCsvReportHandler
import gov.nasa.jpl.parakeet.utilities.Serialization.decodeFromFile
import gov.nasa.jpl.parakeet.utilities.Serialization.encodeToFile
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.OutputStream
import kotlin.io.path.Path
import kotlin.io.path.absolute
import kotlin.io.path.outputStream

@Serializable
data class StandardPlanSimulationSetup<M>(
    @SerialName("initial_conditions")
    val inconFile: String? = null,
    @SerialName("plan")
    val planFile: String,
    @SerialName("final_conditions")
    val finconFile: String? = null,
    @SerialName("output")
    val outputFile: String? = null,
)

/**
 * Baseline way to set up and run a [Simulator].
 *
 * A JSON setup file is read from disk.
 * The setup file indicates a plan and (optionally) an incon, as paths relative to the location of the setup file.
 * The setup file also optionally indicates an output file and fincon file, also as paths relative to the location of the setup file.
 * If no output file is requested, output is written to stdout.
 *
 * Missions looking to deviate from this baseline should copy this function and modify it to suit their needs.
 *
 * @param setupFile
 * The path of the [StandardPlanSimulationSetup] file, relative to the working directory.
 *
 * @param constructModel
 * Model constructor, usually the constructor method of a top-level model class.
 *
 * @param jsonFormat
 * The [Json] format to use everywhere, including plan deserialization, reports, and incon/fincon handling.
 */
fun <M: Any> runStandardPlanSimulation(
    setupFile: String,
    constructModel: InitScope.() -> M,
    jsonFormat: Json = Json.Default,
) {
    val setupPath = Path(setupFile).absolute()
    val setup = jsonFormat.decodeFromFile<StandardPlanSimulationSetup<M>>(setupPath)
    // Set up output to go to the output file if specified, or stdout if not.
    val println: (String) -> Unit
    val outputStream: OutputStream
    if (setup.outputFile == null) {
        println = {}
        outputStream = System.out
    } else {
        println = { kotlin.io.println(it) }
        println("Read setup file $setupPath")
        val outputPath = setupPath.resolveSibling(setup.outputFile)
        println("Writing output to $outputPath")
        outputPath.parent.toFile().mkdirs()
        outputStream = outputPath.outputStream()
    }

    val planPath = setupPath.resolveSibling(setup.planFile)
    println("Reading plan $planPath")
    val plan = jsonFormat.decodeFromFile<Plan<M>>(planPath)
    outputStream.use { out ->
        out.usingEventCsvReportHandler(jsonFormat) {
            // Write output in parallel with simulation
            it.inParallel { reportHandler ->
                // Initialize the simulation from an incon, if available.
                val incon: Checkpoint<M>?
                if (setup.inconFile != null) {
                    val inconPath = setupPath.resolveSibling(setup.inconFile)
                    println("Reading initial conditions $inconPath")
                    incon = jsonFormat.decodeFromFile<Checkpoint<M>>(inconPath)
                } else {
                    println("No initial conditions given.")
                    incon = null
                }

                val simulation = Simulator(
                    reportHandler,
                    plan.startTime,
                    incon,
                    constructModel,
                )

                // Run the plan itself
                println("Running plan")
                simulation.runPlan(plan)

                // Write a fincon if requested
                if (setup.finconFile != null) {
                    val finconPath = setupPath.resolveSibling(setup.finconFile)
                    println("Writing final conditions to $finconPath")
                    jsonFormat.encodeToFile(simulation.save(), finconPath)
                } else {
                    println("No final conditions requested")
                }
            }
        }
    }
}