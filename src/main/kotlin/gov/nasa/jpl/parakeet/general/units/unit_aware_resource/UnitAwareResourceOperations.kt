package gov.nasa.jpl.parakeet.general.units.unit_aware_resource

import gov.nasa.jpl.parakeet.foundation.reporting.ChannelReport
import gov.nasa.jpl.parakeet.foundation.reporting.ChannelReport.Metadatum
import gov.nasa.jpl.parakeet.foundation.reporting.Reporting.registered
import gov.nasa.jpl.parakeet.foundation.resources.Dynamics
import gov.nasa.jpl.parakeet.foundation.resources.FullDynamics
import gov.nasa.jpl.parakeet.foundation.resources.MutableResource
import gov.nasa.jpl.parakeet.foundation.resources.Resource
import gov.nasa.jpl.parakeet.foundation.resources.discrete.DoubleResource
import gov.nasa.jpl.parakeet.foundation.resources.discrete.MutableDoubleResource
import gov.nasa.jpl.parakeet.foundation.resources.fullyNamed
import gov.nasa.jpl.parakeet.foundation.resources.getValue
import gov.nasa.jpl.parakeet.foundation.resources.named
import gov.nasa.jpl.parakeet.foundation.tasks.InitScope
import gov.nasa.jpl.parakeet.foundation.tasks.ResourceScope
import gov.nasa.jpl.parakeet.foundation.tasks.SimulationScope
import gov.nasa.jpl.parakeet.general.resources.polynomial.IntegralResource
import gov.nasa.jpl.parakeet.general.resources.polynomial.MutablePolynomialResource
import gov.nasa.jpl.parakeet.general.resources.polynomial.Polynomial
import gov.nasa.jpl.parakeet.general.resources.polynomial.PolynomialResource
import gov.nasa.jpl.parakeet.general.units.Field
import gov.nasa.jpl.parakeet.general.units.Ring
import gov.nasa.jpl.parakeet.general.units.Scaling
import gov.nasa.jpl.parakeet.general.units.Unit
import gov.nasa.jpl.parakeet.general.units.UnitAware
import gov.nasa.jpl.parakeet.general.units.UnitAware.Companion.map
import gov.nasa.jpl.parakeet.general.units.UnitAware.Companion.name
import gov.nasa.jpl.parakeet.general.units.polynomial_quantity.PolynomialRing
import gov.nasa.jpl.parakeet.general.units.polynomial_quantity_resource.IntegralPolynomialResourceScaling
import gov.nasa.jpl.parakeet.general.units.polynomial_quantity_resource.MutablePolynomialResourceScaling
import gov.nasa.jpl.parakeet.general.units.polynomial_quantity_resource.PolynomialResourceRing
import gov.nasa.jpl.parakeet.general.units.quantity.DoubleField
import gov.nasa.jpl.parakeet.general.units.quantity_resource.DoubleResourceField
import gov.nasa.jpl.parakeet.general.units.quantity_resource.MutableDoubleResourceScaling
import gov.nasa.jpl.parakeet.kernel.Name
import kotlin.contracts.ExperimentalContracts

object UnitAwareResourceOperations {
    /**
     * Construct a unit-aware mutable resource
     */
    context (_: InitScope)
    inline fun <V, reified D : Dynamics<V, D>> resource(name: String, initialDynamics: UnitAware<D>): UnitAware<MutableResource<D>> =
        initialDynamics.map { gov.nasa.jpl.parakeet.foundation.resources.resource(name, it) }

    context (_: InitScope, _: Scaling<R>)
    inline fun <V, reified D : Dynamics<V, D>, R : Resource<D>> UnitAware<R>.registered(unit: Unit): UnitAware<R> = also {
        // Rescale the value, preserve the name, and report the result in the requested unit.
        // Then just return the original resource unchanged.
        valueIn(unit)
            .fullyNamed { name }
            .registered("unit" to Metadatum(unit))
    }

    /**
     * Get unit-aware full dynamics from a unit-aware resource.
     */
    context (_: ResourceScope)
    fun <V, D : Dynamics<V, D>> UnitAware<Resource<D>>.getDynamics(): UnitAware<FullDynamics<D>> = map { it.getDynamics() }

    /**
     * Get unit-aware value from a unit-aware resource.
     */
    context (_: ResourceScope)
    fun <V, D : Dynamics<V, D>> UnitAware<Resource<D>>.getValue(): UnitAware<V> = map { it.getValue() }

    context (_: SimulationScope)
    fun <D> UnitAware<Resource<D>>.named(nameFn: () -> String): UnitAware<Resource<D>> =
        map { it.named(nameFn) }

    fun <D> UnitAware<Resource<D>>.fullyNamed(nameFn: () -> Name): UnitAware<Resource<D>> =
        map { it.fullyNamed(nameFn) }

    /**
     * Convenience method for introducing all algebraic structures supporting "standard" unit-aware values and resources.
     */
    @OptIn(ExperimentalContracts::class)
    inline fun <R> unitAware(
        block: context (
            Field<Double>,
            Field<DoubleResource>,
            Scaling<MutableDoubleResource>,
            Ring<Polynomial>,
            Ring<PolynomialResource>,
            Scaling<MutablePolynomialResource>,
            Scaling<IntegralResource>,
        ) () -> R
    ): R {
        kotlin.contracts.contract {
            callsInPlace(block, kotlin.contracts.InvocationKind.EXACTLY_ONCE)
        }
        return context(
            DoubleField,
            DoubleResourceField,
            MutableDoubleResourceScaling,
            PolynomialRing,
            PolynomialResourceRing,
            MutablePolynomialResourceScaling,
        ) {
            context (
                IntegralPolynomialResourceScaling
            ) {
                block()
            }
        }
    }
}

object MutableUnitAwareResourceOperations {
    context (_: SimulationScope, _: Scaling<MutableResource<D>>)
    fun <D> UnitAware<MutableResource<D>>.named(nameFn: () -> String): UnitAware<MutableResource<D>> =
        UnitAware(valueIn(unit).named(nameFn), unit)

    context (_: Scaling<MutableResource<D>>)
    fun <D> UnitAware<MutableResource<D>>.fullyNamed(nameFn: () -> Name): UnitAware<MutableResource<D>> =
        UnitAware(valueIn(unit).fullyNamed(nameFn), unit)
}
