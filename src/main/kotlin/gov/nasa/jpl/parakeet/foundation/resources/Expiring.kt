package gov.nasa.jpl.parakeet.foundation.resources

import gov.nasa.jpl.parakeet.utilities.curry
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.INFINITE

@Serializable
data class Expiring<T>(val data: T, val expiry: Duration) {
    override fun toString(): String = if (expiry == INFINITE) {
        data.toString()
    } else {
        "$data (until $expiry)"
    }
}

// Specialized version of "minOf" for Durations, meant for "expiry" calculations
// This both reads cleaner: "my expiry = thisExpiry or thatExpiry"
// And, being specialized on a concrete value class, will be compiled to operate on the primitive it contains.
// This should avoid boxing the Duration in some cases.
infix fun Duration.or(other: Duration): Duration =
    if (this <= other) this else other

fun <D : Dynamics<*, D>> Expiring<D>.step(time: Duration) = Expiring(data.step(time), expiry - time)

@Suppress("NOTHING_TO_INLINE")
object ExpiringMonad {
    inline fun <A> pure(a: A): Expiring<A> = Expiring(a, INFINITE)
    inline fun <A, B> apply(a: Expiring<A>, f: Expiring<(A) -> B>) =
        Expiring(f.data(a.data), f.expiry or a.expiry)
    inline fun <A> join(a: Expiring<Expiring<A>>) = Expiring(a.data.data, a.expiry or a.data.expiry)
    // Although map can be defined in terms of apply and join, writing it this way instead makes it inlinable.
    // This can be a major boon to performance, so it's worth the redundant code
    inline fun <A, B> map(a: Expiring<A>, f: (A) -> B): Expiring<B> = Expiring(f(a.data), a.expiry)
    // Auxiliary methods - These are defined only in terms of pure/apply/join above, and can be copied from Monad to Monad
    inline fun <A, B> apply(f: Expiring<(A) -> B>): (Expiring<A>) -> Expiring<B> = { apply(it, f) }
    inline fun <A, B> map(crossinline f: (A) -> B): (Expiring<A>) -> Expiring<B> = { map(it, f) }
    inline fun <A, B> bind(a: Expiring<A>, f: (A) -> Expiring<B>): Expiring<B> = join(map(a, f))
    inline fun <A, B> bind(crossinline f: (A) -> Expiring<B>): (Expiring<A>) -> Expiring<B> = { bind(it, f) }
    // Auxiliary map
    inline fun <A, B, C> map(crossinline fn: (A, B) -> C): (Expiring<A>, Expiring<B>) -> Expiring<C> = { a, b -> map(a, b, fn) }
    inline fun <A, B, C> map(a: Expiring<A>, b: Expiring<B>, crossinline fn: (A, B) -> C): Expiring<C> = map(a, b, curry(fn))
    inline fun <A, B, C> map(a: Expiring<A>, b: Expiring<B>, crossinline fn: (A) -> (B) -> C): Expiring<C> = apply(b, map(a, fn))
    inline fun <A, B, C, D> map(crossinline fn: (A, B, C) -> D): (Expiring<A>, Expiring<B>, Expiring<C>) -> Expiring<D> = { a, b, c -> map(a, b, c, fn) }
    inline fun <A, B, C, D> map(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, crossinline fn: (A, B, C) -> D): Expiring<D> = map(a, b, c, curry(fn))
    inline fun <A, B, C, D> map(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, crossinline fn: (A) -> (B) -> (C) -> D): Expiring<D> = apply(c, map(a, b, fn))
    inline fun <A, B, C, D, E> map(crossinline fn: (A, B, C, D) -> E): (Expiring<A>, Expiring<B>, Expiring<C>, Expiring<D>) -> Expiring<E> = { a, b, c, d -> map(a, b, c, d, fn) }
    inline fun <A, B, C, D, E> map(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, crossinline fn: (A, B, C, D) -> E): Expiring<E> = map(a, b, c, d, curry(fn))
    inline fun <A, B, C, D, E> map(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, crossinline fn: (A) -> (B) -> (C) -> (D) -> E): Expiring<E> = apply(d, map(a, b, c, fn))
    inline fun <A, B, C, D, E, F> map(crossinline fn: (A, B, C, D, E) -> F): (Expiring<A>, Expiring<B>, Expiring<C>, Expiring<D>, Expiring<E>) -> Expiring<F> = { a, b, c, d, e -> map(a, b, c, d, e, fn) }
    inline fun <A, B, C, D, E, F> map(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, e: Expiring<E>, crossinline fn: (A, B, C, D, E) -> F): Expiring<F> = map(a, b, c, d, e, curry(fn))
    inline fun <A, B, C, D, E, F> map(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, e: Expiring<E>, crossinline fn: (A) -> (B) -> (C) -> (D) -> (E) -> F): Expiring<F> = apply(e, map(a, b, c, d, fn))
    inline fun <A, B, C, D, E, F, G> map(crossinline fn: (A, B, C, D, E, F) -> G): (Expiring<A>, Expiring<B>, Expiring<C>, Expiring<D>, Expiring<E>, Expiring<F>) -> Expiring<G> = { a, b, c, d, e, f -> map(a, b, c, d, e, f, fn) }
    inline fun <A, B, C, D, E, F, G> map(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, e: Expiring<E>, f: Expiring<F>, crossinline fn: (A, B, C, D, E, F) -> G): Expiring<G> = map(a, b, c, d, e, f, curry(fn))
    inline fun <A, B, C, D, E, F, G> map(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, e: Expiring<E>, f: Expiring<F>, crossinline fn: (A) -> (B) -> (C) -> (D) -> (E) -> (F) -> G): Expiring<G> = apply(f, map(a, b, c, d, e, fn))
    inline fun <A, B, C, D, E, F, G, H> map(crossinline fn: (A, B, C, D, E, F, G) -> H): (Expiring<A>, Expiring<B>, Expiring<C>, Expiring<D>, Expiring<E>, Expiring<F>, Expiring<G>) -> Expiring<H> = { a, b, c, d, e, f, g -> map(a, b, c, d, e, f, g, fn) }
    inline fun <A, B, C, D, E, F, G, H> map(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, e: Expiring<E>, f: Expiring<F>, g: Expiring<G>, crossinline fn: (A, B, C, D, E, F, G) -> H): Expiring<H> = map(a, b, c, d, e, f, g, curry(fn))
    inline fun <A, B, C, D, E, F, G, H> map(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, e: Expiring<E>, f: Expiring<F>, g: Expiring<G>, crossinline fn: (A) -> (B) -> (C) -> (D) -> (E) -> (F) -> (G) -> H): Expiring<H> = apply(g, map(a, b, c, d, e, f, fn))
    inline fun <A, B, C, D, E, F, G, H, I> map(crossinline fn: (A, B, C, D, E, F, G, H) -> I): (Expiring<A>, Expiring<B>, Expiring<C>, Expiring<D>, Expiring<E>, Expiring<F>, Expiring<G>, Expiring<H>) -> Expiring<I> = { a, b, c, d, e, f, g, h -> map(a, b, c, d, e, f, g, h, fn) }
    inline fun <A, B, C, D, E, F, G, H, I> map(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, e: Expiring<E>, f: Expiring<F>, g: Expiring<G>, h: Expiring<H>, crossinline fn: (A, B, C, D, E, F, G, H) -> I): Expiring<I> = map(a, b, c, d, e, f, g, h, curry(fn))
    inline fun <A, B, C, D, E, F, G, H, I> map(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, e: Expiring<E>, f: Expiring<F>, g: Expiring<G>, h: Expiring<H>, crossinline fn: (A) -> (B) -> (C) -> (D) -> (E) -> (F) -> (G) -> (H) -> I): Expiring<I> = apply(h, map(a, b, c, d, e, f, g, fn))
    inline fun <A, B, C, D, E, F, G, H, I, J> map(crossinline fn: (A, B, C, D, E, F, G, H, I) -> J): (Expiring<A>, Expiring<B>, Expiring<C>, Expiring<D>, Expiring<E>, Expiring<F>, Expiring<G>, Expiring<H>, Expiring<I>) -> Expiring<J> = { a, b, c, d, e, f, g, h, i -> map(a, b, c, d, e, f, g, h, i, fn) }
    inline fun <A, B, C, D, E, F, G, H, I, J> map(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, e: Expiring<E>, f: Expiring<F>, g: Expiring<G>, h: Expiring<H>, i: Expiring<I>, crossinline fn: (A, B, C, D, E, F, G, H, I) -> J): Expiring<J> = map(a, b, c, d, e, f, g, h, i, curry(fn))
    inline fun <A, B, C, D, E, F, G, H, I, J> map(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, e: Expiring<E>, f: Expiring<F>, g: Expiring<G>, h: Expiring<H>, i: Expiring<I>, crossinline fn: (A) -> (B) -> (C) -> (D) -> (E) -> (F) -> (G) -> (H) -> (I) -> J): Expiring<J> = apply(i, map(a, b, c, d, e, f, g, h, fn))
    inline fun <A, B, C, D, E, F, G, H, I, J, K> map(crossinline fn: (A, B, C, D, E, F, G, H, I, J) -> K): (Expiring<A>, Expiring<B>, Expiring<C>, Expiring<D>, Expiring<E>, Expiring<F>, Expiring<G>, Expiring<H>, Expiring<I>, Expiring<J>) -> Expiring<K> = { a, b, c, d, e, f, g, h, i, j -> map(a, b, c, d, e, f, g, h, i, j, fn) }
    inline fun <A, B, C, D, E, F, G, H, I, J, K> map(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, e: Expiring<E>, f: Expiring<F>, g: Expiring<G>, h: Expiring<H>, i: Expiring<I>, j: Expiring<J>, crossinline fn: (A, B, C, D, E, F, G, H, I, J) -> K): Expiring<K> = map(a, b, c, d, e, f, g, h, i, j, curry(fn))
    inline fun <A, B, C, D, E, F, G, H, I, J, K> map(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, e: Expiring<E>, f: Expiring<F>, g: Expiring<G>, h: Expiring<H>, i: Expiring<I>, j: Expiring<J>, crossinline fn: (A) -> (B) -> (C) -> (D) -> (E) -> (F) -> (G) -> (H) -> (I) -> (J) -> K): Expiring<K> = apply(j, map(a, b, c, d, e, f, g, h, i, fn))
    // Auxiliary bind
    inline fun <A, B, C> bind(crossinline fn: (A, B) -> Expiring<C>): (Expiring<A>, Expiring<B>) -> Expiring<C> = { a, b -> bind(a, b, fn) }
    inline fun <A, B, C> bind(a: Expiring<A>, b: Expiring<B>, crossinline fn: (A, B) -> Expiring<C>): Expiring<C> = bind(a, b, curry(fn))
    inline fun <A, B, C> bind(a: Expiring<A>, b: Expiring<B>, crossinline fn: (A) -> (B) -> Expiring<C>): Expiring<C> = join(map(a, b, fn))
    inline fun <A, B, C, D> bind(crossinline fn: (A, B, C) -> Expiring<D>): (Expiring<A>, Expiring<B>, Expiring<C>) -> Expiring<D> = { a, b, c -> bind(a, b, c, fn) }
    inline fun <A, B, C, D> bind(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, crossinline fn: (A, B, C) -> Expiring<D>): Expiring<D> = bind(a, b, c, curry(fn))
    inline fun <A, B, C, D> bind(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, crossinline fn: (A) -> (B) -> (C) -> Expiring<D>): Expiring<D> = join(map(a, b, c, fn))
    inline fun <A, B, C, D, E> bind(crossinline fn: (A, B, C, D) -> Expiring<E>): (Expiring<A>, Expiring<B>, Expiring<C>, Expiring<D>) -> Expiring<E> = { a, b, c, d -> bind(a, b, c, d, fn) }
    inline fun <A, B, C, D, E> bind(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, crossinline fn: (A, B, C, D) -> Expiring<E>): Expiring<E> = bind(a, b, c, d, curry(fn))
    inline fun <A, B, C, D, E> bind(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, crossinline fn: (A) -> (B) -> (C) -> (D) -> Expiring<E>): Expiring<E> = join(map(a, b, c, d, fn))
    inline fun <A, B, C, D, E, F> bind(crossinline fn: (A, B, C, D, E) -> Expiring<F>): (Expiring<A>, Expiring<B>, Expiring<C>, Expiring<D>, Expiring<E>) -> Expiring<F> = { a, b, c, d, e -> bind(a, b, c, d, e, fn) }
    inline fun <A, B, C, D, E, F> bind(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, e: Expiring<E>, crossinline fn: (A, B, C, D, E) -> Expiring<F>): Expiring<F> = bind(a, b, c, d, e, curry(fn))
    inline fun <A, B, C, D, E, F> bind(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, e: Expiring<E>, crossinline fn: (A) -> (B) -> (C) -> (D) -> (E) -> Expiring<F>): Expiring<F> = join(map(a, b, c, d, e, fn))
    inline fun <A, B, C, D, E, F, G> bind(crossinline fn: (A, B, C, D, E, F) -> Expiring<G>): (Expiring<A>, Expiring<B>, Expiring<C>, Expiring<D>, Expiring<E>, Expiring<F>) -> Expiring<G> = { a, b, c, d, e, f -> bind(a, b, c, d, e, f, fn) }
    inline fun <A, B, C, D, E, F, G> bind(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, e: Expiring<E>, f: Expiring<F>, crossinline fn: (A, B, C, D, E, F) -> Expiring<G>): Expiring<G> = bind(a, b, c, d, e, f, curry(fn))
    inline fun <A, B, C, D, E, F, G> bind(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, e: Expiring<E>, f: Expiring<F>, crossinline fn: (A) -> (B) -> (C) -> (D) -> (E) -> (F) -> Expiring<G>): Expiring<G> = join(map(a, b, c, d, e, f, fn))
    inline fun <A, B, C, D, E, F, G, H> bind(crossinline fn: (A, B, C, D, E, F, G) -> Expiring<H>): (Expiring<A>, Expiring<B>, Expiring<C>, Expiring<D>, Expiring<E>, Expiring<F>, Expiring<G>) -> Expiring<H> = { a, b, c, d, e, f, g -> bind(a, b, c, d, e, f, g, fn) }
    inline fun <A, B, C, D, E, F, G, H> bind(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, e: Expiring<E>, f: Expiring<F>, g: Expiring<G>, crossinline fn: (A, B, C, D, E, F, G) -> Expiring<H>): Expiring<H> = bind(a, b, c, d, e, f, g, curry(fn))
    inline fun <A, B, C, D, E, F, G, H> bind(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, e: Expiring<E>, f: Expiring<F>, g: Expiring<G>, crossinline fn: (A) -> (B) -> (C) -> (D) -> (E) -> (F) -> (G) -> Expiring<H>): Expiring<H> = join(map(a, b, c, d, e, f, g, fn))
    inline fun <A, B, C, D, E, F, G, H, I> bind(crossinline fn: (A, B, C, D, E, F, G, H) -> Expiring<I>): (Expiring<A>, Expiring<B>, Expiring<C>, Expiring<D>, Expiring<E>, Expiring<F>, Expiring<G>, Expiring<H>) -> Expiring<I> = { a, b, c, d, e, f, g, h -> bind(a, b, c, d, e, f, g, h, fn) }
    inline fun <A, B, C, D, E, F, G, H, I> bind(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, e: Expiring<E>, f: Expiring<F>, g: Expiring<G>, h: Expiring<H>, crossinline fn: (A, B, C, D, E, F, G, H) -> Expiring<I>): Expiring<I> = bind(a, b, c, d, e, f, g, h, curry(fn))
    inline fun <A, B, C, D, E, F, G, H, I> bind(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, e: Expiring<E>, f: Expiring<F>, g: Expiring<G>, h: Expiring<H>, crossinline fn: (A) -> (B) -> (C) -> (D) -> (E) -> (F) -> (G) -> (H) -> Expiring<I>): Expiring<I> = join(map(a, b, c, d, e, f, g, h, fn))
    inline fun <A, B, C, D, E, F, G, H, I, J> bind(crossinline fn: (A, B, C, D, E, F, G, H, I) -> Expiring<J>): (Expiring<A>, Expiring<B>, Expiring<C>, Expiring<D>, Expiring<E>, Expiring<F>, Expiring<G>, Expiring<H>, Expiring<I>) -> Expiring<J> = { a, b, c, d, e, f, g, h, i -> bind(a, b, c, d, e, f, g, h, i, fn) }
    inline fun <A, B, C, D, E, F, G, H, I, J> bind(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, e: Expiring<E>, f: Expiring<F>, g: Expiring<G>, h: Expiring<H>, i: Expiring<I>, crossinline fn: (A, B, C, D, E, F, G, H, I) -> Expiring<J>): Expiring<J> = bind(a, b, c, d, e, f, g, h, i, curry(fn))
    inline fun <A, B, C, D, E, F, G, H, I, J> bind(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, e: Expiring<E>, f: Expiring<F>, g: Expiring<G>, h: Expiring<H>, i: Expiring<I>, crossinline fn: (A) -> (B) -> (C) -> (D) -> (E) -> (F) -> (G) -> (H) -> (I) -> Expiring<J>): Expiring<J> = join(map(a, b, c, d, e, f, g, h, i, fn))
    inline fun <A, B, C, D, E, F, G, H, I, J, K> bind(crossinline fn: (A, B, C, D, E, F, G, H, I, J) -> Expiring<K>): (Expiring<A>, Expiring<B>, Expiring<C>, Expiring<D>, Expiring<E>, Expiring<F>, Expiring<G>, Expiring<H>, Expiring<I>, Expiring<J>) -> Expiring<K> = { a, b, c, d, e, f, g, h, i, j -> bind(a, b, c, d, e, f, g, h, i, j, fn) }
    inline fun <A, B, C, D, E, F, G, H, I, J, K> bind(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, e: Expiring<E>, f: Expiring<F>, g: Expiring<G>, h: Expiring<H>, i: Expiring<I>, j: Expiring<J>, crossinline fn: (A, B, C, D, E, F, G, H, I, J) -> Expiring<K>): Expiring<K> = bind(a, b, c, d, e, f, g, h, i, j, curry(fn))
    inline fun <A, B, C, D, E, F, G, H, I, J, K> bind(a: Expiring<A>, b: Expiring<B>, c: Expiring<C>, d: Expiring<D>, e: Expiring<E>, f: Expiring<F>, g: Expiring<G>, h: Expiring<H>, i: Expiring<I>, j: Expiring<J>, crossinline fn: (A) -> (B) -> (C) -> (D) -> (E) -> (F) -> (G) -> (H) -> (I) -> (J) -> Expiring<K>): Expiring<K> = join(map(a, b, c, d, e, f, g, h, i, j, fn))
}
