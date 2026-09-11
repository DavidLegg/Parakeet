package gov.nasa.jpl.parakeet.general.results

import gov.nasa.jpl.parakeet.foundation.Simulator
import gov.nasa.jpl.parakeet.foundation.reporting.BaseChannelizedReportHandler
import gov.nasa.jpl.parakeet.foundation.reporting.ChannelReport
import gov.nasa.jpl.parakeet.foundation.reporting.ChannelReport.ChannelData
import gov.nasa.jpl.parakeet.foundation.reporting.Reporting.register
import gov.nasa.jpl.parakeet.foundation.resources.*
import gov.nasa.jpl.parakeet.foundation.resources.Expiring
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope
import gov.nasa.jpl.parakeet.foundation.tasks.SimulationScope.Companion.simulationClock
import gov.nasa.jpl.parakeet.general.results.ProfileOperations.computeProfile
import gov.nasa.jpl.parakeet.kernel.Name
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import kotlin.time.Duration.Companion.INFINITE
import kotlin.time.Instant

object ProfileOperations {
    val Profile<*>.start: Instant get() = window.start
    val Profile<*>.end: Instant get() = window.endInclusive

    /**
     * Returns a view of [this] channel of results as a profile.
     * This method does not copy the results.
     *
     * Assumes that [data] is already sorted by time, and does not verify this.
     * Results are undefined if this assumption is violated.
     */
    fun <D : Dynamics<*, D>> ResourceResults<D>.asProfile(
        name: Name = metadata.channel,
        startTime: Instant = data.first().time,
        endTime: Instant,
    ): Profile<D> {
        require(data.isNotEmpty()) {
            "Cannot construct a profile from empty results"
        }
        require(startTime <= endTime) {
            "Invalid start time $startTime after end time $endTime"
        }
        require(data.first().time <= startTime) {
            "Data starts at ${data.first().time} after start time $startTime"
        }
        return object : Profile<D> {
            override val name: Name = name
            override val window: ClosedRange<Instant> = startTime..endTime

            override fun getSegment(time: Instant): Expiring<D> {
                require(time in window) {
                    "Time $time is outside of profile window [${start}, ${end}]"
                }
                val i = data.binarySearchBy(time) { it.time }
                val nextSegmentIndex: Int
                val dynamics: D
                if (i >= 0) {
                    // This time is the exact start of a profile segment.
                    // We can return the dynamics exactly, without stepping it
                    nextSegmentIndex = i + 1
                    dynamics = data[i].data
                } else {
                    // This time is not the exact start of a profile segment.
                    // Instead, the binary search returns the segment after the active one:
                    nextSegmentIndex = i.inv()
                    val activeSegment = data[nextSegmentIndex - 1]
                    // Finally, use that to look up which dynamics is active, and how long to step it forward:
                    dynamics = activeSegment.data.step(time - activeSegment.time)
                }
                // Eagerly compute expiry to avoid holding a reference to data, which may be a large list
                return Expiring(dynamics, data.getOrNull(nextSegmentIndex)?.time?.let { it - time } ?: INFINITE)
            }

            override fun iterator(): Iterator<Pair<Instant, D>> = data.map { it.time to it.data }.iterator()
        }
    }

    /**
     * Read a profile from simulation results.
     *
     * @param name The channel name to read, also becomes the profile name.
     */
    fun <D : Dynamics<*, D>> SimulationResults.getProfile(name: Name): Profile<D> =
        @Suppress("UNCHECKED_CAST")
        (resources.getValue(name) as ResourceResults<D>).asProfile(endTime = endTime)

    /**
     * Create a resource which exactly replays this [Profile].
     *
     * This can be used for deriving profiles in a small simulation, e.g. with [computeProfile].
     * This can also be used for subsystem simulations, where the inputs to a subsystem are replayed
     * instead of running the full system.
     */
    context (_: InitScope)
    fun <D : Dynamics<*, D>> Profile<D>.asResource(): Resource<D> =
        ResourceMonad.bind(simulationClock) {
            ThinResourceMonad.pure(this.getSegment(it.time))
        }.fullyNamed { name }

    /**
     * Compute a profile by running a simulation.
     */
    fun <V, D : Dynamics<V, D>> computeProfile(
        start: Instant,
        end: Instant,
        derivation: context (InitScope) () -> Resource<D>,
        dynamicsType: KType,
    ): Profile<D> {
        lateinit var results: MutableResourceResults<D>
        lateinit var resultName: Name
        // Construct and run a simulation to compute the derived profile
        Simulator(
            object : BaseChannelizedReportHandler() {
                override fun <T> constructChannel(metadata: ChannelReport.ChannelMetadata<T>): (ChannelData<T>) -> Unit {
                    @Suppress("UNCHECKED_CAST")
                    if (metadata.channel == resultName) {
                        results = MutableResourceResults(metadata) as MutableResourceResults<D>
                        return { results.data.add(it as ChannelData<D>) }
                    } else {
                        // Just ignore all other channels
                        return {}
                    }
                }
            },
            start,
        ) {
            val resultResource = derivation()
            resultName = resultResource.name
            register(resultResource, dynamicsType)
        }.runUntil(end)

        // Since results is local, we know it will not be mutated at this point.
        // This makes it safe to base the profile directly on the mutable results without copying them.
        return results.asProfile(endTime = end)
    }

    /**
     * Compute a profile by running a simulation.
     */
    inline fun <V, reified D : Dynamics<V, D>> computeProfile(
        start: Instant,
        end: Instant,
        noinline derivation: context (InitScope) () -> Resource<D>,
    ) = computeProfile(start, end, derivation, typeOf<D>())

    operator fun <T : Comparable<T>> ClosedRange<T>.contains(other: ClosedRange<T>): Boolean =
        other.start >= this.start && other.endInclusive <= this.endInclusive
}