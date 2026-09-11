package gov.nasa.jpl.parakeet.general.resources.polynomial

import gov.nasa.jpl.parakeet.utilities.InvertibleFunction
import gov.nasa.jpl.parakeet.foundation.resources.*
import gov.nasa.jpl.parakeet.foundation.resources.ExpiringMonad.map
import gov.nasa.jpl.parakeet.foundation.resources.discrete.Discrete
import gov.nasa.jpl.parakeet.kernel.Durations.EPSILON
import gov.nasa.jpl.parakeet.kernel.Durations.MAX_PRECISE_DURATION
import gov.nasa.jpl.parakeet.utilities.Serialization.alias
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.DoubleArraySerializer
import org.apache.commons.math3.analysis.polynomials.PolynomialsUtils.shift
import org.apache.commons.math3.analysis.solvers.LaguerreSolver
import java.util.*
import kotlin.Double.Companion.NaN
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds


typealias PolynomialResource = Resource<Polynomial>
typealias MutablePolynomialResource = MutableResource<Polynomial>

/**
 * Polynomial dynamics, a numeric value whose evolution is described by a polynomial function of time.
 * @param coefficients All coefficients of the polynomial; index is the degree of that coefficient
 *
 * @apiNote The units of `t` are seconds
 */
@Serializable(with = Polynomial.PolynomialSerializer::class)
class Polynomial private constructor(private val coefficients: DoubleArray) : Dynamics<Double, Polynomial> {
    override fun value(): Double = coefficients[0]

    override fun step(t: Duration): Polynomial =
        if (t == ZERO) this else _polynomial(shift(coefficients, t / 1.seconds))

    fun degree() = coefficients.size - 1
    fun isConstant() = degree() == 0
    fun isNonFinite() = !coefficients.all(Double::isFinite)
    /**
     * Get the raw coefficients.
     * Note that this copies the coefficients to a list, so is not very performant.
     * Prefer other query methods where possible.
     */
    fun coefficients() = coefficients.toList()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Polynomial

        return coefficients.contentEquals(other.coefficients)
    }

    override fun hashCode(): Int {
        return coefficients.contentHashCode()
    }

    override fun toString(): String = coefficients.mapIndexed { i, c ->
        if (c == 0.0) null
        else {
            val cStr = if (c < 0) "($c)" else "$c"
            when (i) {
                0 -> cStr
                1 -> "$cStr t"
                else -> "$cStr t^$i"
            }
        }
    }.filterNotNull().let {
        if (it.isEmpty()) "0.0" else it.joinToString(" + ")
    }

    operator fun get(i: Int) = if (i in coefficients.indices) coefficients[i] else 0.0
    fun setCoefficient(i: Int, value: Double): Polynomial {
        val newCoef = DoubleArray(max(i + 1, coefficients.size))
        coefficients.copyInto(newCoef)
        newCoef[i] = value
        return _polynomial(newCoef)
    }

    operator fun unaryPlus() = this
    operator fun unaryMinus(): Polynomial = _polynomial(coefficients.map(Double::unaryMinus).toDoubleArray())

    operator fun plus(other: Polynomial): Polynomial {
        val minLength = min(coefficients.size, other.coefficients.size)
        val maxLength = max(coefficients.size, other.coefficients.size)
        val newCoef = DoubleArray(maxLength)
        for (i in 0..<minLength) {
            newCoef[i] = coefficients[i] + other.coefficients[i]
        }
        if (coefficients.size > minLength)
            coefficients.copyInto(newCoef, minLength, minLength)
        if (other.coefficients.size > minLength)
            other.coefficients.copyInto(newCoef, minLength, minLength)
        return _polynomial(newCoef)
    }
    operator fun plus(other: Double): Polynomial {
        val newCoef = coefficients.copyOf()
        newCoef[0] += other
        return _polynomial(newCoef)
    }

    operator fun minus(other: Polynomial): Polynomial = this + (-other)
    operator fun minus(other: Double): Polynomial = this + (-other)

    operator fun times(other: Double): Polynomial = _polynomial(coefficients.map { it * other }.toDoubleArray())
    operator fun times(other: Polynomial): Polynomial {
        // Length = degree + 1, so
        // new length = 1 + new degree
        //   = 1 + (degree + other.degree)
        //   = 1 + (length - 1 + other.length - 1)
        //   = length + other.length - 1
        val newCoef = DoubleArray(coefficients.size + other.coefficients.size - 1) { 0.0 }
        for (exponent in newCoef.indices) {
            // 0 <= k < length and 0 <= exponent - k < other.length
            // implies k >= 0, k > exponent - other.length,
            // k < length, and k <= exponent
            for (k in max(0, exponent - other.coefficients.size + 1)..<min(coefficients.size, exponent + 1)) {
                newCoef[exponent] += coefficients[k] * other.coefficients[exponent - k]
            }
        }
        return _polynomial(newCoef)
    }
    operator fun div(other: Double): Polynomial = _polynomial(coefficients.map { it / other }.toDoubleArray())

    fun integral(startingValue: Double): Polynomial {
        val newCoef = DoubleArray(coefficients.size + 1)
        newCoef[0] = startingValue
        for (i in coefficients.indices) {
            newCoef[i + 1] = coefficients[i] / (i + 1)
        }
        return _polynomial(newCoef)
    }

    fun derivative(): Polynomial {
        val newCoef = DoubleArray(coefficients.size - 1)
        for (i in 1..<coefficients.size) {
            newCoef[i - 1] = coefficients[i] * i
        }
        return _polynomial(newCoef)
    }

    private fun greaterThan(other: Polynomial, strict: Boolean): Expiring<Discrete<Boolean>> {
        val comp = if (strict) { x: Double, y: Double -> x > y } else { x: Double, y: Double -> x >= y }
        val result = comp(this.value(), other.value())
        // Lazily compute expiry, which may be far more expensive than computing the value
        return Expiring(Discrete(result)) {
            (this - other).findExpiryNearRoot { t -> comp(step(t).value(), other.step(t).value()) != result }
        }
    }

    infix fun greaterThan(other: Polynomial): Expiring<Discrete<Boolean>> {
        return greaterThan(other, true)
    }

    infix fun greaterThanOrEquals(other: Polynomial): Expiring<Discrete<Boolean>> {
        return greaterThan(other, false)
    }

    infix fun lessThan(other: Polynomial): Expiring<Discrete<Boolean>> {
        return other.greaterThan(this)
    }

    infix fun lessThanOrEquals(other: Polynomial): Expiring<Discrete<Boolean>> {
        return other.greaterThanOrEquals(this)
    }

    /**
     * Computes if this polynomial "dominates" another right now.
     *
     * A polynomial P dominates Q in this context if it is greater than Q in the first coefficient that differs,
     * or equal to Q exactly.
     *
     * Intuitively, P dominates Q when max(P, Q) = P, potentially with a shorter expiry if Q crosses P sometime in the future.
     * Consider using [Polynomial.dominates] instead to get this expiry / crossover time as well.
     */
    fun _dominates(other: Polynomial): Boolean {
        for (i in 0..max(this.degree(), other.degree())) {
            if (this[i] > other[i]) return true
            if (this[i] < other[i]) return false
        }
        // Equal, so either answer is correct
        return true
    }

    /**
     * Computes if this polynomial "dominates" another.
     *
     * A polynomial P dominates Q in this context if it is greater than Q in the first coefficient that differs,
     * or equal to Q exactly.
     *
     * Intuitively, P dominates Q when max(P, Q) = P, potentially with a shorter expiry if Q crosses P sometime in the future.
     */
    fun dominates(other: Polynomial): Expiring<Discrete<Boolean>> {
        val result = this._dominates(other)
        // Lazily compute expiry, which may be far more expensive than computing the value
        return Expiring(Discrete(result)) {
            (this - other).findExpiryNearRoot { t -> step(t)._dominates(other.step(t)) != result }
        }
    }

    fun min(other: Polynomial): Expiring<Polynomial> {
        return map(this.dominates(other)) { if (it.value) other else this }
    }

    fun max(other: Polynomial): Expiring<Polynomial> {
        return map(this.dominates(other)) { if (it.value) this else other }
    }

    /**
     * Helper method for other comparison methods.
     * Finds the first time the predicate is true, near the next root of this polynomial.
     */
    private fun findExpiryNearRoot(expires: (Duration) -> Boolean): Duration {
        var start: Duration
        var end: Duration
        // Shadow the original "expires" function with a version that demands a future time
        val expires: (Duration) -> Boolean = { it > ZERO && expires(it) }
        val root: Duration = findFutureRoots().firstOrNull() ?: return Duration.INFINITE

        // Do an exponential search to bracket the transition time
        val initialTestResult: Boolean = expires(root)
        var rangeSize: Duration = EPSILON * (if (initialTestResult) -1 else 1)
        var testPoint: Duration = root + rangeSize
        var testResult: Boolean = expires(testPoint)
        while (testPoint <= MAX_PRECISE_DURATION && testResult == initialTestResult) {
            rangeSize *= 2
            testPoint = root + rangeSize
            testResult = expires(testPoint)
        }
        // TODO: There's an unhandled edge case here, where timePredicate is satisfied in a period we jumped over.
        //   Maybe try to use the precision of the arguments and the finer resolution polynomial "this"
        //   to do a more thorough but still efficient search?
        if (testPoint > MAX_PRECISE_DURATION && testResult == initialTestResult) {
            // We searched all the way to the longest precise duration
            // without finding where the result changes. Search failed, no expiry.
            return Duration.INFINITE
        }
        if (initialTestResult) {
            start = testPoint
            end = root
        } else {
            start = root
            end = testPoint
        }

        // Do a binary search to find the exact transition time
        while (end > start + EPSILON) {
            val midpoint: Duration = start + (end - start) / 2
            if (expires(midpoint)) {
                end = midpoint
            } else {
                start = midpoint
            }
        }
        return end
    }

    /**
     * Finds all roots of this function in the future
     */
    private fun findFutureRoots(): Sequence<Duration> {
        // TODO: In some sense, isn't having an infinite coefficient the same as a vertical line,
        //   hence the same as having a root at x = 0?
        //   Unless the value itself is non-finite, that is...
        // If this polynomial can never have a root, fail immediately
        if (this.isNonFinite() || this.isConstant()) {
            return emptySequence()
        }

        if (coefficients[0] == 0.0) {
            // TODO: What if the degree is high, and we have multiple zeros?
            return sequenceOf(ZERO)
        }

        // If the polynomial is linear, solve it analytically for performance
        if (this.degree() <= 1) {
            val t: Double = -this[0] / this[1]
            return if (t >= -ABSOLUTE_ACCURACY_FOR_DURATIONS / 2)
                sequenceOf(t.seconds)
            else
                emptySequence()
        }

        // Condition the problem by dividing through by the first coefficient:
        val conditionedCoefficients: DoubleArray = coefficients.map { it / coefficients[0] }.toDoubleArray()
        // Defining epsilon keeps the Laguerre solver faster and more stable for poorly-behaved polynomials.
        val epsilon =
            2 * Arrays.stream(conditionedCoefficients).map(Math::ulp).max()
                .orElseThrow()
        val solutions = LaguerreSolver(0.0, ABSOLUTE_ACCURACY_FOR_DURATIONS, epsilon)
            .solveAllComplex(conditionedCoefficients, 0.0)
        return solutions.filter { abs(it.imaginary) < epsilon }
            .map { it.real }
            .filter { it >= -ABSOLUTE_ACCURACY_FOR_DURATIONS / 2 }
            .sorted()
            .map { it.seconds }
            .asSequence()
    }

    companion object {
        private val ABSOLUTE_ACCURACY_FOR_DURATIONS: Double = EPSILON / 1.seconds

        fun polynomial(vararg coefficients: Double): Polynomial {
            return _polynomial(coefficients, false)
        }

        private fun _polynomial(coefficients: DoubleArray, reuseIfNormalized: Boolean = true): Polynomial {
            var n = coefficients.size
            if (n == 0) return Polynomial(doubleArrayOf(0.0))
            while (n > 1 && coefficients[n - 1] == 0.0) --n
            for (m in coefficients.indices) {
                // Any NaN coefficient invalidates the whole polynomial
                if (coefficients[m].isNaN()) return Polynomial(doubleArrayOf(NaN))
                // Infinite coefficients invalidate later terms
                if (coefficients[m].isInfinite()) {
                    n = m + 1
                    break
                }
            }
            // For performance, when we know we can trust the coefficients array to be effectively constant
            // because it's coming from a method in this class, we reuse it instead of copying to a new array.
            return Polynomial(
                if (reuseIfNormalized && n == coefficients.size) coefficients
                else coefficients.copyOf(n))
        }
    }

    class PolynomialSerializer : KSerializer<Polynomial> by DoubleArraySerializer().alias(InvertibleFunction.of(
        ::polynomial,
        { it.coefficients }
    ))
}

operator fun Double.plus(other: Polynomial) = other + this
operator fun Double.minus(other: Polynomial) = (-other) + this
operator fun Double.times(other: Polynomial) = other * this
