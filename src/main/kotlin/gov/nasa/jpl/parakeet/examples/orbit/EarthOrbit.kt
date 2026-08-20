package gov.nasa.jpl.parakeet.examples.orbit

import gov.nasa.jpl.parakeet.examples.orbit.OrbitalSimulation.Vector
import gov.nasa.jpl.parakeet.foundation.plans.activities
import gov.nasa.jpl.parakeet.foundation.reporting.Reporting.registered
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResource
import gov.nasa.jpl.parakeet.foundation.resources.named
import gov.nasa.jpl.parakeet.foundation.serialization.ResultSerializer
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope.Companion.subContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlin.time.Duration.Companion.hours

class EarthOrbit(
    context: InitScope,
) {
    private val orbitalSimulation: OrbitalSimulation
    val earthPosition: DiscreteResource<Vector>
    val moonPosition: DiscreteResource<Vector>

    init {
        with (context) {
            val earth = OrbitalSimulation.Body(
                "Earth",
                Vector(0.0, 0.0, 0.0),
                Vector(0.0, 0.0, 0.0),
                5.972e24
            )
            val moon = OrbitalSimulation.Body(
                "Moon",
                Vector(3.844e8, 0.0, 0.0),
                Vector(0.0, 1.022e3, 0.0),
                7.346e22
            )
            orbitalSimulation = OrbitalSimulation(
                subContext("Earth Orbit"),
                listOf(earth, moon),
                1.hours,
            )

            earthPosition = orbitalSimulation.bodyPositions.getValue(earth)
                .named { "earth_position" }
                .registered()
            moonPosition = orbitalSimulation.bodyPositions.getValue(moon)
                .named { "moon_position" }
                .registered()
        }
    }

    companion object {
        val JSON_FORMAT = Json {
            serializersModule = SerializersModule {
                contextual(Result::class) { ResultSerializer(it[0]) }
                activities<EarthOrbit> {}
            }
        }
    }
}