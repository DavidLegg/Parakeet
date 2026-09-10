package gov.nasa.jpl.parakeet.foundation.resources

private const val MAX_SUBEFFECTS_WARNING_THRESHOLD = 6

/**
 * Merge concurrent general effects using automatic commutativity checking.
 */
fun <D> autoMerge(effects: List<ResourceEffect<D>>): (Result<FullDynamics<D>>) -> Result<FullDynamics<D>> {
    if (effects.size > MAX_SUBEFFECTS_WARNING_THRESHOLD) {
        // Warn the user if we're about to do something truly excessive...
        System.err.println("${effects.size} concurrent effects present in a single automatic merge.")
        System.err.println(
            LongRange(1, effects.size.toLong()).reduce { x, y -> x * y }.toString() +
                    " orderings need to be tested, which may require excessive time."
        )
        System.err.println("Consider using a custom effect type, or reworking model to reduce concurrent effects.")
        System.err.println("Effects to be merged:")
        effects.forEach { System.err.println("  $it") }
    }

    return { initialValue: Result<FullDynamics<D>> ->
        Result.runCatching {
            // Run the whole merging/checking procedure in a runCatching block.
            // If some ordering of effects faults the cell, we can throw that exception and fail fast.
            // Similarly, if some ordering of effects differs from the first ordering, we throw and fail fast.

            if (effects.size == 2) {
                // Special case for two effects.
                // In this common and simple case, avoid the overhead of allocating arrays and running the general-purpose permutation functions.
                val (f, g) = effects
                val r1 = f(g(initialValue)).getOrThrow()
                val r2 = g(f(initialValue)).getOrThrow()
                require(r1 == r2) {
                    "Non-commuting concurrent effects. autoMerge detected different results ($r1 vs. $r2) from ordering ($f | $g) vs. ($g | $f)"
                }
                r1
            } else {
                // Copy the effects to an array.
                // We'll juggle the pointers in this array to work through all permutations of effects efficiently.
                val effectArray = effects.toTypedArray()

                fun runEffects(): FullDynamics<D> {
                    var result = initialValue
                    for (effect in effectArray) {
                        result = effect(result)
                    }
                    return result.getOrThrow()
                }

                // First, compute the result from the first ordering of effects.
                // So long as every ordering produces this value, we can just return this value.
                // By default, every effect uses mapCatching internally to catch faults.
                // So, if there's an ordering of effects that could fault and recover the cell, that should be preserved.
                val firstResult = runEffects()

                /** Run block on every permutation of effects at or after n */
                fun runPermutations(n: Int, block: () -> Unit) {
                    if (n >= effectArray.size - 1) block()
                    else {
                        val element_n = effectArray[n]
                        runPermutations(n + 1) {
                            // effectArray[n+1:] is shuffled.
                            // Run the block without shuffling element n in.
                            block()
                            // swap element n into each position in this arrangement and run block on each
                            for (m in n + 1..<effectArray.size) {
                                effectArray[m - 1] = effectArray[m]
                                effectArray[m] = element_n
                                block()
                            }
                            // Finally, put everything back where it was so the rest of the shuffling works as expected
                            for (m in effectArray.size - 1 downTo n + 1) {
                                effectArray[m] = effectArray[m - 1]
                            }
                            effectArray[n] = element_n
                        }
                    }
                }

                runPermutations(0) {
                    val thisResult = runEffects()
                    require(firstResult == thisResult) {
                        "Non-commuting concurrent effects. autoMerge detected different results ($firstResult vs. $thisResult)" +
                                " from ordering (${effects.joinToString(" | ") { it.toString() }})" +
                                " vs. (${effectArray.joinToString(" | ") { it.toString() }})"
                    }
                }

                // If we reached this point, all the effects commute.
                firstResult
            }
        }
    }
}