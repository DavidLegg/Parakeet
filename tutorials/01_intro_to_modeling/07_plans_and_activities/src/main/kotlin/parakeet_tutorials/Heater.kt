package parakeet_tutorials

import gov.nasa.jpl.parakeet.foundation.reporting.Reporting.registered
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations.discreteResource
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations.equals
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations.set
import gov.nasa.jpl.parakeet.foundation.resources.discrete.MutableDiscreteResource
import gov.nasa.jpl.parakeet.foundation.resources.discrete.MutableDoubleResource
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope.Companion.spawn
import gov.nasa.jpl.parakeet.foundation.tasks.Reactions.onceWhenever
import gov.nasa.jpl.parakeet.foundation.tasks.TaskOperations.delay
import kotlin.time.Duration.Companion.minutes

class Heater(initScope: InitScope) {
    val state: MutableDiscreteResource<HeaterState>
    val temperature: MutableDoubleResource

    init {
        context(initScope) {
            state = discreteResource("state", HeaterState.OFF).registered()
            temperature = discreteResource("temperature", 70.0).registered()

            spawn("Warm up", onceWhenever(state equals HeaterState.ON) {
                delay(5.minutes)
                temperature.set(100.0)
            })
            spawn("Cool off", onceWhenever(state equals HeaterState.OFF) {
                delay(1.minutes)
                temperature.set(70.0)
            })
        }
    }

    enum class HeaterState { OFF, ON }
}