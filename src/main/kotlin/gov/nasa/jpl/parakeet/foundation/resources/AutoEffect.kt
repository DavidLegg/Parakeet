package gov.nasa.jpl.parakeet.foundation.resources

import gov.nasa.jpl.parakeet.kernel.EffectBatch
import gov.nasa.jpl.parakeet.kernel.EffectBranch

private const val MAX_SUBEFFECTS_WARNING_THRESHOLD = 6

/**
 * Merge concurrent general effects using automatic commutativity checking.
 * This function conforms to the [CommitResourceEffects] signature.
 */
fun <D> safelyApplyConcurrentEffects(effects: ResourceEffectBatch<D>, initialValue: Result<FullDynamics<D>>): Result<FullDynamics<D>> =
    // TODO: Think through this composition - do we need to lower the effects differently? Throw early? Check equality differently?
    Result.runCatching { testConcurrentEffects(effects, initialValue).getOrThrow() }

/**
 * Apply all permutations of [effectsBatch] to [initialValue].
 *
 * If all permutations agree on the result, return that result.
 * Otherwise, throw an [IllegalArgumentException].
 */
// TODO: Consider adding an "equivalence" param, to modify our notion of "good enough" commutativity
fun <D> testConcurrentEffects(effectsBatch: EffectBatch<D>, initialValue: D): D {
    val numBranches = effectsBatch.size

    if (numBranches > MAX_SUBEFFECTS_WARNING_THRESHOLD) {
        // Warn the user if we're about to do something truly excessive...
        System.err.println("${effectsBatch.size} concurrent branches present in a single automatic merge.")
        System.err.println(
            LongRange(1, effectsBatch.size.toLong()).reduce { x, y -> x * y }.toString() +
                    " orderings need to be tested, which may require excessive time."
        )
        System.err.println("Consider using a custom effect type, or reworking model to reduce concurrent effects.")
        System.err.println("Effects to be merged:")
        effectsBatch.forEach { System.err.println("  $it") }
    }

    // Note: A commit with a single branch is applied without the commit function.
    // Typically, it's applied automatically by iteratively applying each individual effect.
    // For completeness, the general case would handle one branch fine, but it doesn't need to be performant.
    return if (numBranches == 2) {
        // Special case: Don't do the full permutation procedure, just directly test the two combinations
        val (f, g) = effectsBatch
        val r1 = f.applyBranch(g.applyBranch(initialValue))
        val r2 = g.applyBranch(f.applyBranch(initialValue))
        require(r1 == r2) {
            "Non-commuting concurrent effects. Detected different results ($r1 vs. $r2) from ordering ($f | $g) vs. ($g | $f)"
        }
        r1
    } else {
        // Copy the effects to an array.
        // We'll juggle the pointers in this array to work through all permutations of effects efficiently.
        val branchArray = effectsBatch.toTypedArray()

        fun runEffects(): D {
            var result = initialValue
            for (branch in branchArray) {
                result = branch.applyBranch(result)
            }
            return result
        }

        // First, compute the result from the first ordering of effects.
        // So long as every ordering produces this value, we can just return this value.
        // By default, every effect uses mapCatching internally to catch faults.
        // So, if there's an ordering of effects that could fault and recover the cell, that should be preserved.
        val firstResult = runEffects()

        /** Run block on every permutation of effects at or after n */
        fun runPermutations(n: Int, block: () -> Unit) {
            if (n >= branchArray.size - 1) block()
            else {
                val element_n = branchArray[n]
                runPermutations(n + 1) {
                    // effectArray[n+1:] is shuffled.
                    // Run the block without shuffling element n in.
                    block()
                    // swap element n into each position in this arrangement and run block on each
                    for (m in n + 1..<branchArray.size) {
                        branchArray[m - 1] = branchArray[m]
                        branchArray[m] = element_n
                        block()
                    }
                    // Finally, put everything back where it was so the rest of the shuffling works as expected
                    for (m in branchArray.size - 1 downTo n + 1) {
                        branchArray[m] = branchArray[m - 1]
                    }
                    branchArray[n] = element_n
                }
            }
        }

        runPermutations(0) {
            val thisResult = runEffects()
            require(firstResult == thisResult) {
                "Non-commuting concurrent effects. safelyApplyConcurrentEffects detected different results ($firstResult vs. $thisResult)" +
                        " from ordering (${effectsBatch.joinToString(" | ") { it.toString() }})" +
                        " vs. (${branchArray.joinToString(" | ") { it.toString() }})"
            }
        }

        // If we reached this point, all the effects commute.
        firstResult
    }
}

fun <D> EffectBranch<D>.applyBranch(value: D): D {
    var result = value
    for (effect in this) result = effect(result)
    return result
}
