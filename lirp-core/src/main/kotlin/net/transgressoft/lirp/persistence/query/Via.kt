/******************************************************************************
 *     Copyright (C) 2025  Octavio Calleya Garcia                             *
 *                                                                            *
 *     This program is free software: you can redistribute it and/or modify   *
 *     it under the terms of the GNU General Public License as published by   *
 *     the Free Software Foundation, either version 3 of the License, or      *
 *     (at your option) any later version.                                    *
 *                                                                            *
 *     This program is distributed in the hope that it will be useful,        *
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of         *
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the          *
 *     GNU General Public License for more details.                           *
 *                                                                            *
 *     You should have received a copy of the GNU General Public License      *
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>. *
 ******************************************************************************/

package net.transgressoft.lirp.persistence.query

import net.transgressoft.lirp.entity.IdentifiableEntity
import net.transgressoft.lirp.persistence.Registry
import kotlin.reflect.KProperty1

/**
 * Maximum nesting depth allowed for chained `via` cross-aggregate predicates.
 *
 * A chain of `parent via childRegistry anyMatch { child via grandChildRegistry anyMatch { ... } }`
 * counts each `Via*` node as one level. Construction beyond this depth fails fast with
 * a diagnostic naming the chain. The hard limit is intentional: deeper chains hit
 * exponential evaluation cost and are best replaced by denormalisation or split queries.
 */
private const val MAX_VIA_DEPTH: Int = 3

/**
 * Cross-aggregate "exists" predicate: matches a parent when at least one resolvable child
 * referenced by [parentProp] satisfies [childPredicate].
 *
 * Empty collection semantics: `anyMatch` on an empty collection returns `false`. Missing
 * children (where [Registry.findById] returns an empty Optional) are treated as non-matching.
 *
 * **Live-read invariant.** The parent collection of foreign keys is read via [KProperty1.get]
 * on every invocation of [matches]; the predicate does not snapshot at construction. After
 * an `@Aggregate(onDelete = DETACH)` lifecycle event reconciles a child's id out of the
 * parent's collection — directly in memory, or after a SQL `DETACH` round-trip — the next
 * query reads the updated collection and the detached parent disappears from the result.
 *
 * Construction is guarded by a depth-3 limit on nested `Via*` chains; exceeding the limit
 * fails fast with a diagnostic naming the property chain.
 *
 * Example (KDoc-only): `Playlist::trackIds via tracks anyMatch { Track::price gt 100.0 }`
 * builds a `Predicate<Playlist>` selecting playlists with at least one track priced over 100.
 *
 * @param TParent the parent entity type
 * @param K the child identifier type
 * @param TChild the child entity type
 * @param parentProp property reference reading the parent's collection of child ids
 * @param childRegistry registry holding the children
 * @param childPredicate predicate evaluated against each resolved child
 */
class ViaAnyMatch<TParent : IdentifiableEntity<*>, K : Comparable<K>, TChild : IdentifiableEntity<K>>(
    val parentProp: KProperty1<TParent, Collection<K>>,
    val childRegistry: Registry<K, TChild>,
    val childPredicate: Predicate<TChild>
) : Predicate<TParent>() {
    init {
        enforceMaxViaDepth(this, parentProp.name, childPredicate)
    }

    /** Returns `true` if any id in `parentProp.get(t)` resolves to a child matching [childPredicate]. */
    override fun matches(t: TParent): Boolean =
        parentProp.get(t).any { id ->
            childRegistry.findById(id).map { childPredicate.matches(it) }.orElse(false)
        }
}

/**
 * Cross-aggregate "for all" predicate: matches a parent when every resolvable child
 * referenced by [parentProp] satisfies [childPredicate].
 *
 * Empty collection semantics: `allMatch` on an empty collection returns `true` (vacuously,
 * matching Kotlin stdlib `Collection.all`). Missing children count as non-matching, so a
 * parent with any unresolved id will not match.
 *
 * **Live-read invariant.** [matches] reads `parentProp.get(t)` on every invocation. After
 * an `@Aggregate(onDelete = DETACH)` lifecycle event reconciles a parent's collection,
 * the next query reflects the change immediately — no snapshot is kept at construction.
 *
 * Construction is guarded by a depth-3 limit on nested `Via*` chains.
 *
 * Example (KDoc-only): `Playlist::trackIds via tracks allMatch { Track::price gt 0.0 }`
 * builds a `Predicate<Playlist>` selecting playlists whose every track is priced above zero.
 *
 * @param TParent the parent entity type
 * @param K the child identifier type
 * @param TChild the child entity type
 * @param parentProp property reference reading the parent's collection of child ids
 * @param childRegistry registry holding the children
 * @param childPredicate predicate evaluated against each resolved child
 */
class ViaAllMatch<TParent : IdentifiableEntity<*>, K : Comparable<K>, TChild : IdentifiableEntity<K>>(
    val parentProp: KProperty1<TParent, Collection<K>>,
    val childRegistry: Registry<K, TChild>,
    val childPredicate: Predicate<TChild>
) : Predicate<TParent>() {
    init {
        enforceMaxViaDepth(this, parentProp.name, childPredicate)
    }

    /** Returns `true` if every id in `parentProp.get(t)` resolves to a child matching [childPredicate]. */
    override fun matches(t: TParent): Boolean =
        parentProp.get(t).all { id ->
            childRegistry.findById(id).map { childPredicate.matches(it) }.orElse(false)
        }
}

/**
 * Cross-aggregate "no match" predicate: matches a parent when no resolvable child
 * referenced by [parentProp] satisfies [childPredicate].
 *
 * Empty collection semantics: `noneMatch` on an empty collection returns `true`
 * (vacuously). Missing children count as non-matching, so they do not disqualify the parent.
 *
 * **Live-read invariant.** [matches] reads `parentProp.get(t)` on every invocation. After
 * an `@Aggregate(onDelete = DETACH)` lifecycle event reconciles a parent's collection,
 * subsequent queries reflect the change immediately — no snapshot is kept at construction.
 *
 * Construction is guarded by a depth-3 limit on nested `Via*` chains.
 *
 * Example (KDoc-only): `Playlist::trackIds via tracks noneMatch { Track::price gt 1000.0 }`
 * builds a `Predicate<Playlist>` selecting playlists with no excessively-priced track.
 *
 * @param TParent the parent entity type
 * @param K the child identifier type
 * @param TChild the child entity type
 * @param parentProp property reference reading the parent's collection of child ids
 * @param childRegistry registry holding the children
 * @param childPredicate predicate evaluated against each resolved child
 */
class ViaNoneMatch<TParent : IdentifiableEntity<*>, K : Comparable<K>, TChild : IdentifiableEntity<K>>(
    val parentProp: KProperty1<TParent, Collection<K>>,
    val childRegistry: Registry<K, TChild>,
    val childPredicate: Predicate<TChild>
) : Predicate<TParent>() {
    init {
        enforceMaxViaDepth(this, parentProp.name, childPredicate)
    }

    /** Returns `true` if no id in `parentProp.get(t)` resolves to a child matching [childPredicate]. */
    override fun matches(t: TParent): Boolean =
        parentProp.get(t).none { id ->
            childRegistry.findById(id).map { childPredicate.matches(it) }.orElse(false)
        }
}

/**
 * Single-entity cross-aggregate predicate: matches a parent when its [parentProp] is
 * non-null AND the referenced child satisfies [childPredicate].
 *
 * Null/missing semantics: a null property value or an unresolved id (empty Optional from
 * [Registry.findById]) returns `false`, excluding the parent from results.
 *
 * **Live-read invariant.** [matches] reads `parentProp.get(t)` on every invocation. After
 * an `@Aggregate(onDelete = DETACH)` lifecycle event nulls a parent's single-ref FK — the
 * SQL layer applies `ON DELETE SET NULL`; consumers driving DETACH in memory must clear
 * the property themselves — subsequent queries observe the null and drop the parent.
 *
 * Construction is guarded by a depth-3 limit on nested `Via*` chains.
 *
 * Example (KDoc-only): `Order::customerId via customers where { Customer::city eq "Berlin" }`
 * builds a `Predicate<Order>` selecting orders whose linked customer lives in Berlin.
 *
 * @param TParent the parent entity type
 * @param K the child identifier type
 * @param TChild the child entity type
 * @param parentProp property reference reading the parent's nullable child id
 * @param childRegistry registry holding the children
 * @param childPredicate predicate evaluated against the resolved child
 */
class ViaWhere<TParent : IdentifiableEntity<*>, K : Comparable<K>, TChild : IdentifiableEntity<K>>(
    val parentProp: KProperty1<TParent, K?>,
    val childRegistry: Registry<K, TChild>,
    val childPredicate: Predicate<TChild>
) : Predicate<TParent>() {
    init {
        enforceMaxViaDepth(this, parentProp.name, childPredicate)
    }

    /** Returns `true` if `parentProp.get(t)` is non-null and the resolved child matches [childPredicate]. */
    override fun matches(t: TParent): Boolean {
        val id = parentProp.get(t) ?: return false
        return childRegistry.findById(id).map { childPredicate.matches(it) }.orElse(false)
    }
}

/**
 * Intermediate step for building a collection-ref cross-aggregate predicate.
 *
 * Produced by the `KProperty1<TParent, Collection<K>> via Registry<K, TChild>` infix,
 * consumed by [anyMatch], [allMatch], [noneMatch].
 */
class ViaCollectionStep<TParent : IdentifiableEntity<*>, K : Comparable<K>, TChild : IdentifiableEntity<K>>(
    val parentProp: KProperty1<TParent, Collection<K>>,
    val childRegistry: Registry<K, TChild>
)

/**
 * Intermediate step for building a single-entity cross-aggregate predicate.
 *
 * Produced by the `KProperty1<TParent, K?> via Registry<K, TChild>` infix, consumed by [where].
 */
class ViaSingleStep<TParent : IdentifiableEntity<*>, K : Comparable<K>, TChild : IdentifiableEntity<K>>(
    val parentProp: KProperty1<TParent, K?>,
    val childRegistry: Registry<K, TChild>
)

/**
 * Pivots a collection foreign-key property into a related child registry.
 *
 * Example: `Playlist::trackIds via tracks anyMatch { Track::price gt 100.0 }`.
 *
 * @param child the registry holding the child entities
 * @return a [ViaCollectionStep] awaiting `anyMatch` / `allMatch` / `noneMatch`
 */
infix fun <TParent : IdentifiableEntity<*>, K : Comparable<K>, TChild : IdentifiableEntity<K>> KProperty1<TParent, Collection<K>>.via(
    child: Registry<K, TChild>
): ViaCollectionStep<TParent, K, TChild> =
    ViaCollectionStep(this, child)

/**
 * Pivots a single-entity foreign-key property into a related child registry.
 *
 * Example: `Order::customer via customers where { Customer::city eq "Berlin" }`.
 *
 * @param child the registry holding the child entities
 * @return a [ViaSingleStep] awaiting `where`
 */
@JvmName("viaSingle")
infix fun <TParent : IdentifiableEntity<*>, K : Comparable<K>, TChild : IdentifiableEntity<K>> KProperty1<TParent, K?>.via(
    child: Registry<K, TChild>
): ViaSingleStep<TParent, K, TChild> =
    ViaSingleStep(this, child)

/**
 * Builds a [ViaAnyMatch] predicate from this step: "at least one child satisfies the block".
 *
 * Empty parent collection produces `false`.
 *
 * @param block builder for the child predicate
 * @return a [Predicate] over the parent
 */
infix fun <TParent : IdentifiableEntity<*>, K : Comparable<K>, TChild : IdentifiableEntity<K>> ViaCollectionStep<TParent, K, TChild>.anyMatch(
    block: () -> Predicate<TChild>
): Predicate<TParent> =
    ViaAnyMatch(parentProp, childRegistry, block())

/**
 * Builds a [ViaAllMatch] predicate from this step: "every child satisfies the block".
 *
 * Empty parent collection produces `true` (vacuously, matching Kotlin stdlib semantics).
 *
 * @param block builder for the child predicate
 * @return a [Predicate] over the parent
 */
infix fun <TParent : IdentifiableEntity<*>, K : Comparable<K>, TChild : IdentifiableEntity<K>> ViaCollectionStep<TParent, K, TChild>.allMatch(
    block: () -> Predicate<TChild>
): Predicate<TParent> =
    ViaAllMatch(parentProp, childRegistry, block())

/**
 * Builds a [ViaNoneMatch] predicate from this step: "no child satisfies the block".
 *
 * Empty parent collection produces `true` (vacuously).
 *
 * @param block builder for the child predicate
 * @return a [Predicate] over the parent
 */
infix fun <TParent : IdentifiableEntity<*>, K : Comparable<K>, TChild : IdentifiableEntity<K>> ViaCollectionStep<TParent, K, TChild>.noneMatch(
    block: () -> Predicate<TChild>
): Predicate<TParent> =
    ViaNoneMatch(parentProp, childRegistry, block())

/**
 * Builds a [ViaWhere] predicate from this step: "the referenced child satisfies the block".
 *
 * Null parent reference produces `false`.
 *
 * @param block builder for the child predicate
 * @return a [Predicate] over the parent
 */
infix fun <TParent : IdentifiableEntity<*>, K : Comparable<K>, TChild : IdentifiableEntity<K>> ViaSingleStep<TParent, K, TChild>.where(
    block: () -> Predicate<TChild>
): Predicate<TParent> =
    ViaWhere(parentProp, childRegistry, block())

/**
 * Walks [predicate] counting nested `Via*` nodes; returns the maximum chain depth observed.
 *
 * `Predicate.And`/`Or`/`Not` are descended into but do not increment the count themselves.
 * Non-Via leaves (Eq/Gt/etc.) contribute zero.
 */
private fun viaDepth(predicate: Predicate<*>): Int =
    when (predicate) {
        is ViaAnyMatch<*, *, *> -> 1 + viaDepth(predicate.childPredicate)
        is ViaAllMatch<*, *, *> -> 1 + viaDepth(predicate.childPredicate)
        is ViaNoneMatch<*, *, *> -> 1 + viaDepth(predicate.childPredicate)
        is ViaWhere<*, *, *> -> 1 + viaDepth(predicate.childPredicate)
        is Predicate.And<*> -> maxOf(viaDepth(predicate.left), viaDepth(predicate.right))
        is Predicate.Or<*> -> maxOf(viaDepth(predicate.left), viaDepth(predicate.right))
        is Predicate.Not<*> -> viaDepth(predicate.inner)
        else -> 0
    }

/**
 * Walks [predicate] collecting the parent-property name at each `Via*` level along the
 * deepest chain, producing a human-readable trail like `trackIds → artistIds → labelIds`.
 */
private fun viaChain(predicate: Predicate<*>): List<String> =
    when (predicate) {
        is ViaAnyMatch<*, *, *> -> listOf(predicate.parentProp.name) + viaChain(predicate.childPredicate)
        is ViaAllMatch<*, *, *> -> listOf(predicate.parentProp.name) + viaChain(predicate.childPredicate)
        is ViaNoneMatch<*, *, *> -> listOf(predicate.parentProp.name) + viaChain(predicate.childPredicate)
        is ViaWhere<*, *, *> -> listOf(predicate.parentProp.name) + viaChain(predicate.childPredicate)
        is Predicate.And<*> -> deeperChain(predicate.left, predicate.right)
        is Predicate.Or<*> -> deeperChain(predicate.left, predicate.right)
        is Predicate.Not<*> -> viaChain(predicate.inner)
        else -> emptyList()
    }

private fun deeperChain(left: Predicate<*>, right: Predicate<*>): List<String> {
    val l = viaChain(left)
    val r = viaChain(right)
    return if (l.size >= r.size) l else r
}

/**
 * Enforces the depth-3 limit at AST construction. Walks [childPredicate] to compute the
 * total chain depth (this node counts as 1 plus the maximum nested Via* depth). When the
 * total exceeds [MAX_VIA_DEPTH], raises `error()` with a diagnostic naming the full chain
 * starting from [thisLevelName].
 *
 * Per CLAUDE.md error-handling invariant, this is a bug detector: misuse must fail loud,
 * never be downgraded to a log.
 */
private fun enforceMaxViaDepth(self: Predicate<*>, thisLevelName: String, childPredicate: Predicate<*>) {
    val totalDepth = viaDepth(self)
    if (totalDepth > MAX_VIA_DEPTH) {
        val chain = (listOf(thisLevelName) + viaChain(childPredicate)).joinToString(" → ")
        error(
            "Nested 'via' chain exceeds depth $MAX_VIA_DEPTH ($chain). " +
                "LIRP enforces a depth-$MAX_VIA_DEPTH limit on cross-aggregate via traversal; " +
                "see the LIRP wiki for supported relationship patterns."
        )
    }
}