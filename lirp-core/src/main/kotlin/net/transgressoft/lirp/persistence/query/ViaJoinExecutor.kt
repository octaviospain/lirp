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
import net.transgressoft.lirp.persistence.RegistryBase
import net.transgressoft.lirp.persistence.isSoftDeleted
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Executes the Via-join phase of a cross-aggregate query. Two strategies are supported,
 * with selection delegated to [QueryPlanner.chooseStrategy]:
 *
 * - [ViaStrategy.PER_PARENT_LOOP]: iterates every parent lazily and applies the Via* node's
 *   `matches` directly for the default [Visibility.ACTIVE_ONLY] case. For [Visibility.INCLUDE_DELETED]
 *   or [Visibility.ONLY_DELETED], a flag-scoped child lookup map is built once and the quantifier is
 *   applied over the parent's live `parentProp` collection against that map.
 * - [ViaStrategy.HASH_JOIN]: pre-filters the child registry by the child predicate using the
 *   flag-scoped child sequence, materialises matching ids into a [HashSet], then tests each
 *   parent's live `parentProp` collection against that set with the quantifier appropriate to
 *   the Via* operator.
 *
 * Both strategies and the multi-Via* compound fallback honour the same [Visibility] on
 * both parent enumeration and child resolution, guaranteeing both join strategies return
 * identical result sets under every visibility mode.
 *
 * **Strict-mirror semantics for [Visibility.ONLY_DELETED]:** the visible set on both sides is
 * `{soft-deleted only}`. A soft-deleted parent matches `onlyDeleted() + allMatch { … }`
 * vacuously when none of its referenced children are soft-deleted (the flag-scoped child set
 * is empty, and `all {}` over an empty collection is `true`). Likewise, `noneMatch { … }`
 * is vacuously `true` for a parent with no soft-deleted children. Example:
 * `repo.query { onlyDeleted(); where { Playlist::trackIds via tracks anyMatch { Track::title eq "X" } } }`
 * returns soft-deleted playlists that reference a soft-deleted track titled "X" — active
 * tracks are invisible in this view.
 *
 * The executor consumes only a [Visibility] mode, never the full generic [Query]: it reads no
 * predicate or ordering, so threading the lighter value type avoids a `Query<*>` element-type
 * coupling at this boundary.
 *
 * Strategy selection (cardinality estimation, the [ViaStrategy] enum) remains in [QueryPlanner].
 * This class contains only execution — no heuristics, no caching.
 */
internal class ViaJoinExecutor<T : IdentifiableEntity<*>> {

    /**
     * Core Via execution: splits hybrid `And(NonVia, Via*)`, selects a strategy for the
     * Via* arm, and applies the NonVia arm as a lazy post-filter.
     *
     * Multi-Via* compounds (`And(Via*, Via*)`, `Or(Via*, Via*)`) that cannot be isolated to a
     * single top-level Via* arm are handled via [evaluateCompoundFlagScoped] for non-default
     * visibility, or a straight `predicate.matches(p)` loop for the default [Visibility.ACTIVE_ONLY]
     * case (where `Via*.matches()` is correct because child resolution is active-only). The
     * returned sequence is fully lazy for single-via paths; compound paths materialise each
     * Via* arm's result set before composing the boolean.
     *
     * The default [visibility] value preserves the active-only fast path.
     */
    fun executeViaPlan(
        pred: Predicate<T>,
        parentRegistry: Registry<*, T>,
        splitHybridAnd: (Predicate<T>) -> Pair<Predicate<T>?, Predicate<T>?>,
        chooseStrategy: (Predicate<T>, Registry<*, T>) -> ViaStrategy,
        visibility: Visibility = Visibility.ACTIVE_ONLY
    ): Sequence<T> {
        val (nonVia, viaArm) = splitHybridAnd(pred)
        if (viaArm == null) {
            // Multi-Via* compound (e.g. And(Via*, Via*) or Or(Via*, Via*)).
            log.debug { "Via planner: multi-Via* compound, falling back to per-parent loop on full predicate" }
            if (visibility == Visibility.ACTIVE_ONLY) {
                // Default active-only fast path: Via*.matches() uses active-only child resolution, which is correct here.
                return sequence {
                    for (p in parentRegistry) {
                        if (pred.matches(p)) yield(p)
                    }
                }
            }
            // Non-default visibility: Via*.matches() resolves children via active-only findById, so we
            // must evaluate each Via* arm using flag-scoped child resolution and compose the boolean result.
            return evaluateCompoundFlagScoped(pred, parentRegistry, visibility)
        }

        val strategy = chooseStrategy(viaArm, parentRegistry)
        log.trace { "Via executor: dispatching strategy=$strategy for ${viaArm::class.simpleName}" }
        val viaResults =
            when (strategy) {
                ViaStrategy.PER_PARENT_LOOP -> perParentLoop(viaArm, parentRegistry, visibility)
                ViaStrategy.HASH_JOIN -> hashJoin(viaArm, parentRegistry, visibility)
            }

        return if (nonVia != null) viaResults.filter { nonVia.matches(it) } else viaResults
    }

    /**
     * Lazy per-parent loop execution. For the default [Visibility.ACTIVE_ONLY] case, each parent is
     * tested via the Via* node's `matches` implementation directly (fast path). For non-default
     * visibility, the flag-scoped child lookup map is built once and the quantifier is applied over
     * the parent's live `parentProp` collection. Live reads of `parentProp` happen at yield time —
     * never cached here. Suitable as the test seam entry point.
     */
    fun perParentLoop(
        via: Predicate<T>,
        parentRegistry: Registry<*, T>,
        visibility: Visibility = Visibility.ACTIVE_ONLY
    ): Sequence<T> {
        if (visibility == Visibility.ACTIVE_ONLY) {
            // Default active-only fast path: delegate to Via*.matches() unchanged.
            return sequence {
                for (p in parentRegistry) {
                    if (via.matches(p)) yield(p)
                }
            }
        }
        // Non-default visibility: resolve children from the flag-scoped visible set, dispatching
        // to the operator-specific quantifier (mirrors the hashJoin* dispatch).
        @Suppress("UNCHECKED_CAST")
        return when (via) {
            is ViaAnyMatch<*, *, *> ->
                perParentAnyMatch(via as ViaAnyMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>, parentRegistry, visibility)
            is ViaAllMatch<*, *, *> ->
                perParentAllMatch(via as ViaAllMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>, parentRegistry, visibility)
            is ViaNoneMatch<*, *, *> ->
                perParentNoneMatch(via as ViaNoneMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>, parentRegistry, visibility)
            is ViaWhere<*, *, *> ->
                perParentWhere(via as ViaWhere<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>, parentRegistry, visibility)
            else -> error("perParentLoop called with non-Via predicate: ${via::class.simpleName}")
        }
    }

    private fun perParentAnyMatch(
        v: ViaAnyMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>,
        parentRegistry: Registry<*, T>,
        visibility: Visibility
    ): Sequence<T> {
        val childMap = childLookup(v.childRegistry, visibility)
        return sequence {
            for (p in flagScopedParentSequence(parentRegistry, visibility)) {
                if (v.parentProp.get(p).any { childMap[it]?.let(v.childPredicate::matches) == true }) yield(p)
            }
        }
    }

    private fun perParentAllMatch(
        v: ViaAllMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>,
        parentRegistry: Registry<*, T>,
        visibility: Visibility
    ): Sequence<T> {
        val childMap = childLookup(v.childRegistry, visibility)
        return sequence {
            for (p in flagScopedParentSequence(parentRegistry, visibility)) {
                val scopedChildren = v.parentProp.get(p).mapNotNull { childMap[it] }
                if (scopedChildren.isEmpty() || scopedChildren.all(v.childPredicate::matches)) yield(p)
            }
        }
    }

    private fun perParentNoneMatch(
        v: ViaNoneMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>,
        parentRegistry: Registry<*, T>,
        visibility: Visibility
    ): Sequence<T> {
        val childMap = childLookup(v.childRegistry, visibility)
        return sequence {
            for (p in flagScopedParentSequence(parentRegistry, visibility)) {
                val scopedChildren = v.parentProp.get(p).mapNotNull { childMap[it] }
                if (scopedChildren.isEmpty() || scopedChildren.none(v.childPredicate::matches)) yield(p)
            }
        }
    }

    private fun perParentWhere(
        v: ViaWhere<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>,
        parentRegistry: Registry<*, T>,
        visibility: Visibility
    ): Sequence<T> {
        val childMap = childLookup(v.childRegistry, visibility)
        return sequence {
            for (p in flagScopedParentSequence(parentRegistry, visibility)) {
                val child = v.parentProp.get(p)?.let { childMap[it] }
                if (child != null && v.childPredicate.matches(child)) yield(p)
            }
        }
    }

    /**
     * Hash-join execution. Materialises matching child ids into a [HashSet] once using the
     * flag-scoped child sequence, then iterates parents lazily testing the live `parentProp`
     * collection against the set with the quantifier appropriate to the Via* operator.
     *
     * **Live-read invariant is preserved.** Although the matching-children set is snapshotted
     * up-front (the strategy's defining property), each parent's `parentProp.get(p)` call
     * runs at yield time. A `@ToManyAggregates(onDelete = DETACH)` reconciliation that runs
     * mid-iteration is reflected on the very next parent yielded — the snapshot only fixes
     * which child ids are "interesting", never which parents reference them.
     *
     * Empty-collection semantics and null-single-entity semantics are preserved per the
     * Via* operator's contract.
     */
    @Suppress("UNCHECKED_CAST")
    fun hashJoin(
        via: Predicate<T>,
        parentRegistry: Registry<*, T>,
        visibility: Visibility = Visibility.ACTIVE_ONLY
    ): Sequence<T> =
        when (via) {
            is ViaAnyMatch<*, *, *> ->
                hashJoinAnyMatch(via as ViaAnyMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>, parentRegistry, visibility)
            is ViaAllMatch<*, *, *> ->
                hashJoinAllMatch(via as ViaAllMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>, parentRegistry, visibility)
            is ViaNoneMatch<*, *, *> ->
                hashJoinNoneMatch(via as ViaNoneMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>, parentRegistry, visibility)
            is ViaWhere<*, *, *> ->
                hashJoinWhere(via as ViaWhere<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>, parentRegistry, visibility)
            else -> error("hashJoin called with non-Via predicate: ${via::class.simpleName}")
        }

    /**
     * Returns the flag-scoped sequence of children from [registry] according to [visibility].
     * This is the single source of the child visible set for both [ViaStrategy.HASH_JOIN] and
     * [ViaStrategy.PER_PARENT_LOOP], guaranteeing both join strategies return identical result
     * sets under every visibility mode.
     *
     * - [Visibility.ACTIVE_ONLY]: `registry.asSequence()` — active entities only.
     * - [Visibility.INCLUDE_DELETED]: all entities via [RegistryBase.rawIterator], bypassing the
     *   default soft-delete exclusion filter.
     * - [Visibility.ONLY_DELETED]: same raw iterator filtered to soft-deleted entities only.
     */
    private fun <K : Comparable<K>, C : IdentifiableEntity<K>> flagScopedChildSequence(
        registry: Registry<K, C>,
        visibility: Visibility
    ): Sequence<C> {
        if (visibility == Visibility.ACTIVE_ONLY) return registry.asSequence()
        val raw =
            (registry as? RegistryBase<K, C>)?.rawIterator()?.asSequence()
                ?: registry.asSequence()
        return if (visibility == Visibility.ONLY_DELETED) raw.filter { isSoftDeleted(it) } else raw
    }

    /**
     * Returns the flag-scoped sequence of parents from [parentRegistry] according to [visibility].
     * Mirrors [flagScopedChildSequence] for the parent side.
     */
    private fun flagScopedParentSequence(
        parentRegistry: Registry<*, T>,
        visibility: Visibility
    ): Sequence<T> {
        if (visibility == Visibility.ACTIVE_ONLY) return parentRegistry.asSequence()
        val raw =
            (parentRegistry as? RegistryBase<*, T>)?.rawIterator()?.asSequence()
                ?: parentRegistry.asSequence()
        return if (visibility == Visibility.ONLY_DELETED) raw.filter { isSoftDeleted(it) } else raw
    }

    /**
     * Evaluates a multi-Via* compound predicate (e.g. `And(Via*, Via*)`, `Or(Via*, Via*)`, or a
     * `Not`-wrapped Via*) with flag-scoped child resolution. For each `Via*` leaf, the result set
     * is computed via [perParentLoop] using the flag-scoped child lookup. `And` nodes intersect
     * the parent sets; `Or` nodes union them; `Not` over a Via*-containing subtree complements
     * against the flag-scoped parent universe so child resolution stays flag-scoped on the negated
     * side too. Via*-free leaf predicates fall back to [Predicate.matches] over the flag-scoped
     * parent sequence (active-only child resolution is irrelevant there).
     *
     * Called by [executeViaPlan] only when the query carries a non-default [visibility] and
     * [splitHybridAnd] cannot isolate a single top-level Via* arm.
     */
    private fun evaluateCompoundFlagScoped(
        pred: Predicate<T>,
        parentRegistry: Registry<*, T>,
        visibility: Visibility
    ): Sequence<T> {
        val matchingSet = collectFlagScopedMatches(pred, parentRegistry, visibility)
        return matchingSet.asSequence()
    }

    private fun collectFlagScopedMatches(
        pred: Predicate<T>,
        parentRegistry: Registry<*, T>,
        visibility: Visibility
    ): Set<T> =
        when {
            pred.isViaLeaf() -> perParentLoop(pred, parentRegistry, visibility).toHashSet()
            pred is Predicate.And -> {
                val left = collectFlagScopedMatches(pred.left, parentRegistry, visibility)
                val right = collectFlagScopedMatches(pred.right, parentRegistry, visibility)
                left.intersect(right)
            }
            pred is Predicate.Or -> {
                val left = collectFlagScopedMatches(pred.left, parentRegistry, visibility)
                val right = collectFlagScopedMatches(pred.right, parentRegistry, visibility)
                left.union(right)
            }
            pred is Predicate.Not && pred.inner.containsViaNode() -> {
                // Complement against the flag-scoped parent universe; resolving the inner Via* via
                // [collectFlagScopedMatches] keeps child resolution flag-scoped on the negated side.
                val universe = flagScopedParentSequence(parentRegistry, visibility).toHashSet()
                universe - collectFlagScopedMatches(pred.inner, parentRegistry, visibility)
            }
            else -> {
                // Via*-free predicate (a plain leaf or a Not over one): active-only child resolution
                // is irrelevant since no Via* node is reached. A Via* here would be silently resolved
                // active-only regardless of the flag — guard against that invariant violation.
                check(!pred.containsViaNode()) {
                    "collectFlagScopedMatches reached a Via* node through an unsupported predicate shape: ${pred::class.simpleName}"
                }
                flagScopedParentSequence(parentRegistry, visibility).filter { pred.matches(it) }.toHashSet()
            }
        }

    /** Whether this predicate subtree contains any `Via*` node, recursing through `And`/`Or`/`Not`. */
    private fun Predicate<T>.containsViaNode(): Boolean =
        when (this) {
            is Predicate.And -> left.containsViaNode() || right.containsViaNode()
            is Predicate.Or -> left.containsViaNode() || right.containsViaNode()
            is Predicate.Not -> inner.containsViaNode()
            else -> isViaLeaf()
        }

    private fun <K : Comparable<K>, C : IdentifiableEntity<K>> matchingChildIds(
        registry: Registry<K, C>,
        predicate: Predicate<C>,
        visibility: Visibility
    ): HashSet<K> = flagScopedChildSequence(registry, visibility).filter { predicate.matches(it) }.map { it.id }.toHashSet()

    private fun <K : Comparable<K>, C : IdentifiableEntity<K>> childLookup(
        registry: Registry<K, C>,
        visibility: Visibility
    ): Map<K, C> = flagScopedChildSequence(registry, visibility).associateBy { it.id }

    private fun hashJoinAnyMatch(
        v: ViaAnyMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>,
        parentRegistry: Registry<*, T>,
        visibility: Visibility
    ): Sequence<T> {
        val matchingIds = matchingChildIds(v.childRegistry, v.childPredicate, visibility)
        return sequence {
            for (p in flagScopedParentSequence(parentRegistry, visibility)) {
                if (v.parentProp.get(p).any { it in matchingIds }) yield(p)
            }
        }
    }

    private fun hashJoinAllMatch(
        v: ViaAllMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>,
        parentRegistry: Registry<*, T>,
        visibility: Visibility
    ): Sequence<T> {
        val matchingIds = matchingChildIds(v.childRegistry, v.childPredicate, visibility)
        val allChildIds = flagScopedChildSequence(v.childRegistry, visibility).map { it.id }.toHashSet()
        return sequence {
            for (p in flagScopedParentSequence(parentRegistry, visibility)) {
                val refs = v.parentProp.get(p)
                val scopedRefs = refs.filter { it in allChildIds }
                if (scopedRefs.isEmpty() || scopedRefs.all { it in matchingIds }) yield(p)
            }
        }
    }

    private fun hashJoinNoneMatch(
        v: ViaNoneMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>,
        parentRegistry: Registry<*, T>,
        visibility: Visibility
    ): Sequence<T> {
        val matchingIds = matchingChildIds(v.childRegistry, v.childPredicate, visibility)
        val allChildIds = flagScopedChildSequence(v.childRegistry, visibility).map { it.id }.toHashSet()
        return sequence {
            for (p in flagScopedParentSequence(parentRegistry, visibility)) {
                val refs = v.parentProp.get(p)
                val scopedRefs = refs.filter { it in allChildIds }
                if (scopedRefs.isEmpty() || scopedRefs.none { it in matchingIds }) yield(p)
            }
        }
    }

    private fun hashJoinWhere(
        v: ViaWhere<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>,
        parentRegistry: Registry<*, T>,
        visibility: Visibility
    ): Sequence<T> {
        val matchingIds = matchingChildIds(v.childRegistry, v.childPredicate, visibility)
        return sequence {
            for (p in flagScopedParentSequence(parentRegistry, visibility)) {
                val id = v.parentProp.get(p)
                if (id != null && id in matchingIds) yield(p)
            }
        }
    }
}