package gov.nasa.jpl.parakeet.foundation.resources.discrete

import gov.nasa.jpl.parakeet.foundation.resources.Expiring
import gov.nasa.jpl.parakeet.foundation.resources.FaultedResourceException
import gov.nasa.jpl.parakeet.utilities.named
import gov.nasa.jpl.parakeet.foundation.resources.Resource
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceMonad.map
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceMonad.pure
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DiscreteResourceOperations.emit
import gov.nasa.jpl.parakeet.foundation.resources.fullyNamed
import gov.nasa.jpl.parakeet.foundation.resources.or
import gov.nasa.jpl.parakeet.foundation.tasks.TaskScope
import gov.nasa.jpl.parakeet.kernel.Name
import kotlin.time.Duration

typealias BooleanResource = DiscreteResource<Boolean>
typealias MutableBooleanResource = MutableDiscreteResource<Boolean>

object BooleanResourceOperations {
    operator fun BooleanResource.not(): BooleanResource =
        map(this@not) { !it }.fullyNamed { Name("not (${this@not})") }
    // Do short-circuiting in resource operations for efficiency
    // Note that this is actually pretty complicated to get exactly right.
    // In particular, we need to commute faults with expiries carefully to allow a condition to recover from a fault.
    // For example, consider the condition "A or B", where A is false but expires in 5 minutes and B is faulted.
    // Initially, evaluating this must fault, as we must sample B.
    // However, we should re-sample in 5 minutes, since then A may have changed, and we may be able to short-circuit
    // B and avoid the fault.
    // In order to avoid entering a try/catch block uselessly when short-circuiting, I've chosen to re-write the
    // logic that would normally be handled by ResourceMonad.bind.
    // This includes sampling `other` and combining the expiry data.
    infix fun BooleanResource.and(other: BooleanResource): BooleanResource =
        Resource {
            val a = this@and.getDynamics()
            if (a.data.value) {
                try {
                    val b = other.getDynamics()
                    return@Resource Expiring(b.data, a.expiry or b.expiry)
                } catch (e: FaultedResourceException) {
                    throw e.expiringAt(a.expiry)
                }
            } else {
                a
            }
        }.fullyNamed { Name("(${this@and}) and ($other)") }
    infix fun BooleanResource.or(other: BooleanResource): BooleanResource =
        Resource {
            val a = this@or.getDynamics()
            if (!a.data.value) {
                try {
                    val b = other.getDynamics()
                    return@Resource Expiring(b.data, a.expiry or b.expiry)
                } catch (e: FaultedResourceException) {
                    throw e.expiringAt(a.expiry)
                }
            } else {
                a
            }
        }.fullyNamed { Name("(${this@or}) or ($other)") }

    // When working with constants, short-circuit during initialization instead
    infix fun Boolean.and(other: BooleanResource): BooleanResource = if (this) other else pure(false)
    infix fun Boolean.or(other: BooleanResource): BooleanResource = if (this) pure(true) else other
    infix fun BooleanResource.and(other: Boolean): BooleanResource = other and this
    infix fun BooleanResource.or(other: Boolean): BooleanResource = other or this

    // Just like the short-circuiting case above, when we choose between two resources,
    // we should try to commute faults with expiries to allow these to recover from a fault by switching
    // to a non-faulted path.
    fun <D> BooleanResource.choose(ifCase: Resource<D>, elseCase: Resource<D>): Resource<D> =
        Resource {
            val condition = this@choose.getDynamics()
            try {
                val result = if (condition.data.value) {
                    ifCase.getDynamics()
                } else {
                    elseCase.getDynamics()
                }
                Expiring(result.data, condition.expiry or result.expiry)
            } catch (e: FaultedResourceException) {
                throw e.expiringAt(condition.expiry)
            }
        }.fullyNamed { Name("if ($this) then ($ifCase) else ($elseCase)") }

    context(scope: TaskScope)
    fun MutableBooleanResource.toggle() = this.emit({ b: Boolean -> !b }.named { "Toggle $this" })
}