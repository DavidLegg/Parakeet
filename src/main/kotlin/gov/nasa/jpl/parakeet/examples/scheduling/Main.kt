package gov.nasa.jpl.parakeet.examples.scheduling

import gov.nasa.jpl.parakeet.examples.scheduling.data.model.DataModel
import gov.nasa.jpl.parakeet.examples.scheduling.geometry.model.GeometryModel
import gov.nasa.jpl.parakeet.examples.scheduling.geometry.model.GeometryModel.PointingTarget
import gov.nasa.jpl.parakeet.examples.scheduling.gnc.activities.GncSetAgility
import gov.nasa.jpl.parakeet.examples.scheduling.gnc.activities.GncSetSystemMode
import gov.nasa.jpl.parakeet.examples.scheduling.gnc.activities.GncTurn
import gov.nasa.jpl.parakeet.examples.scheduling.gnc.model.GncModel
import gov.nasa.jpl.parakeet.examples.scheduling.gnc.model.GncModel.BodyAxis
import gov.nasa.jpl.parakeet.examples.scheduling.imager.activities.ImagerDoObservation
import gov.nasa.jpl.parakeet.examples.scheduling.imager.activities.ImagerPowerOff
import gov.nasa.jpl.parakeet.examples.scheduling.imager.activities.ImagerPowerOn
import gov.nasa.jpl.parakeet.examples.scheduling.imager.model.IMAGE
import gov.nasa.jpl.parakeet.examples.scheduling.imager.model.ImagerModel
import gov.nasa.jpl.parakeet.examples.scheduling.power.model.PowerModel
import gov.nasa.jpl.parakeet.examples.scheduling.support_files.CommPass
import gov.nasa.jpl.parakeet.examples.scheduling.support_files.ScienceOp
import gov.nasa.jpl.parakeet.examples.scheduling.system.activities.GncActivity
import gov.nasa.jpl.parakeet.examples.scheduling.system.activities.ImagerActivity
import gov.nasa.jpl.parakeet.examples.scheduling.system.activities.TelecomActivity
import gov.nasa.jpl.parakeet.examples.scheduling.system.model.SystemModel
import gov.nasa.jpl.parakeet.examples.scheduling.telecom.activities.RadioPowerOff
import gov.nasa.jpl.parakeet.examples.scheduling.telecom.activities.RadioPowerOn
import gov.nasa.jpl.parakeet.examples.scheduling.telecom.activities.RadioSetDownlinkRate
import gov.nasa.jpl.parakeet.examples.scheduling.telecom.activities.TelecomPass
import gov.nasa.jpl.parakeet.examples.scheduling.telecom.model.TelecomModel
import gov.nasa.jpl.parakeet.examples.units.KILOWATT_HOUR
import gov.nasa.jpl.parakeet.foundation.plans.Activity
import gov.nasa.jpl.parakeet.foundation.plans.GroundedActivity
import gov.nasa.jpl.parakeet.foundation.plans.activities
import gov.nasa.jpl.parakeet.foundation.resources.discrete.Discrete
import gov.nasa.jpl.parakeet.foundation.serialization.ResultSerializer
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope
import gov.nasa.jpl.parakeet.general.plans.runStandardPlanSimulation
import gov.nasa.jpl.parakeet.general.results.Profile
import gov.nasa.jpl.parakeet.general.results.ProfileOperations.asResource
import gov.nasa.jpl.parakeet.general.scheduling.SchedulingAlgorithms.scheduleActivityToEndNear
import gov.nasa.jpl.parakeet.general.scheduling.SchedulingOperations.plusAssign
import gov.nasa.jpl.parakeet.general.scheduling.SchedulingSystem
import gov.nasa.jpl.parakeet.general.units.StandardUnits
import gov.nasa.jpl.parakeet.general.units.StandardUnits.DEGREE
import gov.nasa.jpl.parakeet.general.units.StandardUnits.GIGABYTE
import gov.nasa.jpl.parakeet.general.units.StandardUnits.MEGABYTE
import gov.nasa.jpl.parakeet.general.units.StandardUnits.WATT
import gov.nasa.jpl.parakeet.general.units.UnitAware.Companion.div
import gov.nasa.jpl.parakeet.general.units.UnitAware.Companion.times
import gov.nasa.jpl.parakeet.kernel.Name
import gov.nasa.jpl.parakeet.kernel.NameOperations.div
import gov.nasa.jpl.parakeet.utilities.InvertibleFunction
import gov.nasa.jpl.parakeet.utilities.Serialization.alias
import kotlinx.serialization.builtins.DoubleArraySerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import org.apache.commons.math3.geometry.euclidean.threed.Rotation
import org.apache.commons.math3.geometry.euclidean.threed.Vector3D
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

// From our knowledge of this particular model, we know that a GncTurn never takes more than 45 minutes.
// We'll use this to make our scheduling procedure highly efficient.
private val GNC_TURN_MAX_DURATION = 60.minutes

private val STANDARD_CONFIG = SystemModel.Config(
    GeometryModel.Config(1.hours),
    GncModel.Config(1.hours, 1.minutes, 0.5 * DEGREE),
    TelecomModel.Config(),
    DataModel.Config(32.0 * GIGABYTE),
    PowerModel.Config(300.0 * WATT, 2.2 * KILOWATT_HOUR),
    ImagerModel.Config(1.2 * MEGABYTE / IMAGE, 15.0 * IMAGE / StandardUnits.MINUTE),
)
val JSON_FORMAT = Json {
    serializersModule = SerializersModule {
        contextual(Result::class) { ResultSerializer(it[0]) }
        // Vector3D serialization
        contextual(DoubleArraySerializer().alias(InvertibleFunction.of(
            { Vector3D(it[0], it[1], it[2]) },
            { doubleArrayOf(it.x, it.y, it.z) }
        )))
        // Rotation serialization
        contextual(DoubleArraySerializer().alias(InvertibleFunction.of(
            { Rotation(it[0], it[1], it[2], it[3], false) },
            { doubleArrayOf(it.q0, it.q1, it.q2, it.q3) }
        )))

        activities<SystemModel> {
            // System activities
            // These are primarily "Adapter" or "glue" activities,
            // which adapt a subsystem activity to the system level, or glue together subsystem views of an activity.
            activity(GncActivity::class)
            activity(TelecomActivity::class)
            activity(ImagerActivity::class)

            // Subsystem activities
            // These are the "meaty" activities, which describe detailed interactions with a single subsystem.
            subsystemActivities<GncModel> {
                activity(GncSetAgility::class)
                activity(GncSetSystemMode::class)
                activity(GncTurn::class)
            }

            subsystemActivities<TelecomModel> {
                activity(RadioPowerOff::class)
                activity(RadioPowerOn::class)
                activity(RadioSetDownlinkRate::class)
                activity(TelecomPass::class)
            }

            subsystemActivities<ImagerModel> {
                activity(ImagerPowerOn::class)
                activity(ImagerPowerOff::class)
                activity(ImagerDoObservation::class)
            }
        }
    }
}

val GNC_JSON_FORMAT = Json {
    serializersModule = SerializersModule {
        contextual(Result::class) { ResultSerializer(it[0]) }
        // Vector3D serialization
        contextual(DoubleArraySerializer().alias(InvertibleFunction.of(
            { Vector3D(it[0], it[1], it[2]) },
            { doubleArrayOf(it.x, it.y, it.z) }
        )))
        // Rotation serialization
        contextual(DoubleArraySerializer().alias(InvertibleFunction.of(
            { Rotation(it[0], it[1], it[2], it[3], false) },
            { doubleArrayOf(it.q0, it.q1, it.q2, it.q3) }
        )))

        activities<GncModel> {
            activity(GncSetAgility::class)
            activity(GncSetSystemMode::class)
            activity(GncTurn::class)
        }
    }
}

fun main(args: Array<String>) {
    when (args[0].lowercase()) {
        "simulate" -> simulationMain(args.sliceArray(1..<args.size))
        // "schedule" -> schedulingMain(args.sliceArray(1..<args.size))
        else -> throw IllegalArgumentException("First argument must be a command: simulate, schedule")
    }
}

fun simulationMain(args: Array<String>) {
    runStandardPlanSimulation(
        args[0],
        { SystemModel(this, STANDARD_CONFIG) },
        JSON_FORMAT,
    )
}

// TODO: Update this with the new SchedulingSystem interface and/or new scheduling utilities.
/*
@OptIn(ExperimentalSerializationApi::class, ExperimentalPathApi::class)
fun schedulingMain(args: Array<String>) {
    unitAware {
        val commScheduleFile = Path(args[0]).absolute()
        val scienceOpFile = Path(args[1]).absolute()
        val outputDir = Path(args[2]).absolute()
        println("Scheduling input files:")
        println("  Comm Passes: $commScheduleFile")
        println("  Science Ops: $scienceOpFile")
        println("  Output Dir:  $outputDir")

        val clock = TimeSource.Monotonic
        println("Begin scheduling procedure")
        val schedulingStart = clock.markNow()

        println("Begin setup")
        val setupStart = clock.markNow()

        val planStart = Instant.parse("2020-01-01T00:00:00Z")
        val planEnd = Instant.parse("2025-01-01T00:00:00Z")
        val baseScheduler = SchedulingSystem(
            { SystemModel(contextOf<InitScope>(), STANDARD_CONFIG) },
            startTime = planStart,
        )

        val commPasses: List<CommPass> = commScheduleFile.inputStream()
            .use { JSON_FORMAT.decodeFromStream<List<CommPass>>(it) }
            .sortedBy { it.start }
        val scienceOps: List<ScienceOp> = scienceOpFile.inputStream()
            .use { JSON_FORMAT.decodeFromStream<List<ScienceOp>>(it) }
            .sortedBy { it.start }

        val setupEnd = clock.markNow()
        println("End setup - ${setupEnd - setupStart}")

        // Note, because this is just a regular java program, we can just intermix regular printlns and stuff to get results out.

        // The layers in this demo show (roughly) increasing complexity in scheduling logic.
        // Layer 1 shows the simplest scheduling logic - importing activities from file.
        println("Begin layer 1 - critical activities")
        val layer1Start = clock.markNow()
        val layer1Scheduler = baseScheduler.copy()

        // Add in some fixed setup activities, akin to configuring the craft after launch:
        layer1Scheduler += GroundedActivity(
            planStart + 10.minutes,
            GncActivity(GncSetSystemMode(GncModel.GncSystemMode.ACTIVE))
        )

        // Add in the "critical" activities - these are meant as stand-ins for things that are basically fully determined.
        // In a real mission, this would be things like TCMs decided by navigation.

        layer1Scheduler += commPasses.filter { it.critical }.map(::commPassActivity)
        layer1Scheduler += scienceOps.filter { it.critical }.map(::scienceOpActivity)

        layer1Scheduler.runUntil(planEnd)

        val layer1End = clock.markNow()
        println("End layer 1 - ${layer1End - layer1Start}")

        // Layer 2 shows slightly more sophisticated scheduling.
        // Here, we're searching for the right start time, such that a turn ends before a deadline.
        // Since our turn duration is modeled, and depends on the attitude we're turning from and to,
        // this is harder than simply laying down activities at known times.
        println("Begin layer 2 - Turns for critical activities")
        val layer2Start = clock.markNow()
        // Build a new scheduler, starting at the beginning of the plan, but copy the plan from layer 1:
        val layer2Scheduler = baseScheduler.copy()
        layer2Scheduler += layer1Scheduler.plan()

        // We need to turn to each critical science opportunity, then turn back to the background attitude.
        // The background attitude points our antenna at Earth, so we don't need to turn for comm passes.
        // When scheduling turns, we'll just run the GNC subsystem model, not the full system model.
        // To power that, we need to collect some profiles from the results of the last layer.
        val gncInputProfiles = GncInputProfiles(layer1Scheduler)
        scienceOps.filter { it.critical }.forEach {
            print(".")
            // Advance the scheduler to the earliest time the turn may start, to minimize re-simulation.
            layer2Scheduler.runUntil(it.start - GNC_TURN_MAX_DURATION)
            layer2Scheduler.scheduleScienceOpTurns(it, gncInputProfiles)
        }
        println()

        // Finally, advance the layer 2 scheduler to the end of the plan to finish out simulation:
        layer2Scheduler.runUntil(planEnd)

        // Then, collect the results and do a sweep looking for conflicts.
        // Since these are critical activities, we won't change anything, just warn the user if they happen.
        val turning = layer2Scheduler.compute {
            countActivities { (it.activity as? GncActivity)?.subsystemActivity is GncTurn } greaterThan 0
        }
        val observing = layer2Scheduler.compute {
            countActivities { (it.activity as? ImagerActivity)?.subsystemActivity is ImagerDoObservation } greaterThan 0
        }
        val communicating = layer2Scheduler.compute {
            countActivities { (it.activity as? TelecomActivity)?.subsystemActivity is TelecomPass } greaterThan 0
        }

        (observing and communicating).takeIf { it.sometimes() }?.let {
            println("Warning! Critical observation(s) and critical comm pass(es) overlap!")
            for (window in it.windows()) {
                println("  ${window.start} - ${window.endExclusive}")
            }
        }

        (turning and observing).takeIf { it.sometimes() }?.let {
            println("Warning! Critical turn(s) and critical observation(s) overlap!")
            for (window in (turning and observing).windows()) {
                println("  ${window.start} - ${window.endExclusive}")
            }
        }

        (turning and communicating).takeIf { it.sometimes() }?.let {
            println("Warning! Critical turn(s) and critical comm pass(es) overlap!")
            for (window in (turning and communicating).windows()) {
                println("  ${window.start} - ${window.endExclusive}")
            }
        }

        val layer2End = clock.markNow()
        println("End layer 2 - ${layer2End - layer2Start}")

        // Layer 3 shows even more sophistication in scheduling.
        // Here, we interleave querying the results and scheduling activities to roughly optimize our data return,
        // while taking into account constraints like data storage.
        println("Begin layer 3 - Opportunistic science and downlink")
        val layer3Start = clock.markNow()

        // Copy the base scheduler again, and copy in the plan from the previous layer.
        val layer3Scheduler = baseScheduler.copy()
        layer3Scheduler += layer2Scheduler.plan()

        // For each event, build the window of interest around it.
        val scienceOpWindows =
            scienceOps.map { it to (it.start - GNC_TURN_MAX_DURATION to it.end + GNC_TURN_MAX_DURATION) }
        val commWindows = commPasses.map { it to (it.start to it.end) }

        val gncInputProfiles2 = GncInputProfiles(layer2Scheduler)

        for ((event, window) in (scienceOpWindows + commWindows).sortedBy { it.second.first }) {
            print(".")
            // Advance the scheduler to the beginning of the window
            // This will give us the best available information about resources and running activities.
            layer3Scheduler.runUntil(window.first)
            // Make a copy of the scheduler, and run it through the end of the window.
            // This will tell us how activities scheduled so far on this layer overlap this window.
            val layer3Results = layer3Scheduler.copy().apply { runUntil(window.second) }
            // We need the spacecraft to be available over the entire window.
            // Restrict this computation to just the window of interest.
            val spacecraftUnavailable: BooleanProfile = layer3Results.compute(window.first, window.second) {
                // Count all the activities which are any kind of conflicting behavior
                // The spacecraft is busy if any of these activities are happening
                countActivities {
                    (it.activity as? GncActivity)?.subsystemActivity is GncTurn
                            || (it.activity as? ImagerActivity)?.subsystemActivity is ImagerDoObservation
                            || (it.activity as? TelecomActivity)?.subsystemActivity is TelecomPass
                } greaterThan 0
            }
            // If the spacecraft is unavailable at any time during the window of interest, move on to the next opportunity.
            if (spacecraftUnavailable.sometimes()) continue

            // Compute how much data we've used, as a fraction of the total data capacity
            // Note that this is using the latest values as of the start of the window,
            // and just doing the computation in primitives, rather than using `compute` to do more sophisticated analysis.
            val dataCapacity = layer3Results.lastQuantity { data.dataCapacity }
            val dataStored = layer3Results.lastQuantity { data.storedData }
            val fractionDataCapacityUsed = (dataStored / dataCapacity).valueIn(SCALAR)
            val minimumDataFractionForDownlink = 0.2
            val maximumDataFractionForScience = 0.8

            when (event) {
                is ScienceOp -> if (fractionDataCapacityUsed < maximumDataFractionForScience) {
                    // If we have enough data storage to hold the results, then schedule this observation.
                    layer3Scheduler += scienceOpActivity(event)
                    // Similar to before, use pre-computed profiles based on the prior layer to drive a GNC subsystem model
                    // for turn scheduling, which should be faster than a full system model.
                    // In one profiled run, using a GNC subsystem model instead of a full-system model here
                    // reduced the total time to schedule all non-critical turns from ~25s to ~7s.
                    layer3Scheduler.scheduleScienceOpTurns(event, gncInputProfiles2)
                }

                is CommPass -> if (fractionDataCapacityUsed > minimumDataFractionForDownlink) {
                    // If we have enough data to be worth downlinking, then schedule the comm pass.
                    layer3Scheduler += commPassActivity(event)
                }
                // This should not be reachable
                else -> throw UnsupportedOperationException("Unexpected event type!")
            }
        }
        println()

        // Run this scheduler to the end to finish out the results.
        layer3Scheduler.runUntil(planEnd)

        val layer3End = clock.markNow()
        println("End layer 3 - ${layer3End - layer3Start}")

        println("Begin writing output")
        val outputStart = clock.markNow()

        // DEBUG
        // val plan = layer3Scheduler.plan()
        val plan = layer2Scheduler.plan()

        if (outputDir.exists()) outputDir.deleteRecursively()
        outputDir.createDirectories()
        JSON_FORMAT.encodeToFile(plan, outputDir / "plan.json")

        val outputEnd = clock.markNow()
        println("End writing output - ${outputEnd - outputStart}")

        val schedulingEnd = clock.markNow()
        println("End scheduling procedure - ${schedulingEnd - schedulingStart}")
    }
}
 */

// TODO: This is a bit of a hack to ensure we have unique names for all activities.
//   I should come up with a better way to do this, incorporated into PlanSimulation and/or SchedulingSystem
var nextActivityId = 1

fun <M> GroundedActivity(
    time: Instant,
    activity: Activity<M>,
    name: String = activity::class.simpleName!!,
) = GroundedActivity(
    time,
    Name(nextActivityId++.toString()) / name,
    activity
)

fun commPassActivity(pass: CommPass) = GroundedActivity(
    pass.start,
    TelecomActivity(
        TelecomPass(
            pass.end - pass.start,
            pass.downlinkRate,
        )
    ),
    name = TelecomPass::class.simpleName!!,
)

fun scienceOpActivity(it: ScienceOp) = GroundedActivity(
    it.start,
    ImagerActivity(ImagerDoObservation(
        it.end - it.start,
    )),
    name = ImagerDoObservation::class.simpleName!!,
)

fun backgroundTurn() = GncActivity(GncTurn(
    PointingTarget.EARTH,
    PointingTarget.J2000_NEG_Z,
    BodyAxis.HGA,
    BodyAxis.PLUS_Y,
))

fun scienceOpTurn(target: PointingTarget) = GncTurn(
    target,
    if (
        target == PointingTarget.J2000_NEG_Z
        || target == PointingTarget.J2000_POS_Z
    ) PointingTarget.J2000_POS_Y else PointingTarget.J2000_NEG_Z,
    BodyAxis.IMAGER,
    BodyAxis.PLUS_Y,
)

data class GncInputProfiles(
    val pointingTargets: Map<PointingTarget, Profile<Discrete<Vector3D>>>,
) {
    // Given some sim results, build the profiles
    // TODO: Update with new scheduling tools.
    /*
    constructor (scheduler: SchedulingSystem<SystemModel>) : this(PointingTarget.entries.associateWith {
        scheduler.profile { geometry.pointingDirection.getValue(it) }
    })
     */

    // Given an init scope in a particular simulation, build the resources to feed a GncModel
    context (scope: InitScope)
    fun asInputs(): GncModel.Inputs = GncModel.Inputs(pointingTargets.mapValues { it.value.asResource() })
}

fun SchedulingSystem<SystemModel>.scheduleScienceOpTurns(scienceOp: ScienceOp, gncInputProfiles: GncInputProfiles) {
    // For performance testing, we have one method that defers to either of two implementations:

    // Direct scheduling is the easier and more reliable way to do this
    scheduleScienceOpTurns_direct(scienceOp, gncInputProfiles)

    // Subsystem scheduling runs faster (~25% faster for this example)
    // by building just a subsystem and simulating that, not the full system.
    // There are a fair number of caveats to doing this, though.
    // scheduleScienceOpTurns_subsystem(scienceOp, gncInputProfiles)
}

fun SchedulingSystem<SystemModel>.scheduleScienceOpTurns_direct(scienceOp: ScienceOp, gncInputProfiles: GncInputProfiles) {
    // Advance the scheduler to the earliest time a turn may start, to avoid re-simulating more than necessary
    this.scheduleActivityToEndNear(GncActivity(scienceOpTurn(scienceOp.target)), scienceOp.start)
    this += GroundedActivity(scienceOp.end, backgroundTurn())
}

// TODO: This no longer works because a Checkpoint includes the model it's designed for as a type parameter.
//   Fix this or delete this use case entirely.
/*
fun SchedulingSystem<SystemModel, SystemModel.Config>.scheduleScienceOpTurns_subsystem(scienceOp: ScienceOp, gncInputProfiles: GncInputProfiles) {
    // Build a dedicated GNC scheduler, rather than running the full system, for performance.
    val gncScheduler = SchedulingSystem<GncModel, GncModel.Config>(
        config.gncConfig,
        { config ->
            // Instead of other subsystems, fill the inputs for the GNC system with replays of the prior layer.
            // Since we know GNC turns won't change geometry profiles, we can save work by just replaying geometry.
            // Note that we need to build the GNC model in subContext("gnc") to line up with the full system fincon.
            GncModel(subContext("gnc"), config, gncInputProfiles.asInputs())
        },
        // Collect a fincon from the full system to ensure the new scheduler is in the same state
        // Note that this is a "rough" fincon - some tasks will restart because subsystems don't align perfectly with full-systems,
        // but it should be "good enough" to get a high-precision turn time estimate.
        // Part of this requires building a subsystem-specific JSON_FORMAT, so that activities get dumped during restore.
        // This is a bit of a kludge, but again it's largely "good enough".
        incon = fincon(),
    )
    // Use the GNC scheduler to find when to start the turn
    val turn = gncScheduler.scheduleActivityToEndNear(scienceOpTurn(scienceOp.target), scienceOp.start)
    // Then, load that turn into the full system scheduler, recalling that we need to wrap the GNC subsystem activity into a system activity.
    this += GroundedActivity(turn.time, GncActivity(turn.activity))
    // Also add a turn away from the target, back to the background attitude. This doesn't need advanced scheduling.
    this += GroundedActivity(scienceOp.end, backgroundTurn())
}
 */
