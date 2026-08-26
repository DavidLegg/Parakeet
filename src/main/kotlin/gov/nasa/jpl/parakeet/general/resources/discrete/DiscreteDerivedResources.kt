package gov.nasa.jpl.parakeet.general.resources.discrete

import gov.nasa.jpl.parakeet.foundation.resources.Expiring
import gov.nasa.jpl.parakeet.foundation.resources.ThinResource
import gov.nasa.jpl.parakeet.foundation.resources.discrete.Discrete
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResource
import gov.nasa.jpl.parakeet.foundation.resources.or
import kotlin.time.Duration

object DiscreteDerivedResources {
    /**
     * Derive a [DiscreteResource] from other discrete resources.
     *
     * The resulting resource will correctly compute its expiry from the sources it's derived from.
     *
     * For derivations that involve non-discrete resources, or require more information than just the current value,
     * see [gov.nasa.jpl.parakeet.foundation.resources.ResourceMonad] and surrounding documentation.
     */
    fun <T> discreteDerivedResource(block: context (DiscreteDerivedResourceScope) () -> T): DiscreteResource<T> {
        return ThinResource {
            var expiry = Duration.INFINITE
            val resultValue = block(object : DiscreteDerivedResourceScope {
                override fun <V> getValue(resource: DiscreteResource<V>): V {
                    // This getDynamics call is implicitly using the ResourceScope of the returned resource.
                    val dynamics = resource.getDynamics()
                    // We'll implicitly fold in the expiry information
                    expiry = expiry or dynamics.expiry
                    return dynamics.data.value
                }
            })
            // Having sampled all our source resources and computed our expiry safely, return the result.
            Expiring(Discrete(resultValue), expiry)
        }
    }

    /**
     * A restriction of [gov.nasa.jpl.parakeet.foundation.tasks.ResourceScope] which only permits the reading of discrete resources.
     *
     * Meant to be used in conjunction with [discreteDerivedResource] to specify a discrete resource derived from other discrete resources.
     */
    interface DiscreteDerivedResourceScope {
        fun <V> getValue(resource: DiscreteResource<V>): V
    }

    context (scope: DiscreteDerivedResourceScope)
    fun <V> DiscreteResource<V>.getValue(): V = scope.getValue(this)
}