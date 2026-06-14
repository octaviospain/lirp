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
import io.github.oshai.kotlinlogging.KotlinLogging

private val log = KotlinLogging.logger {}

/**
 * Executes the Via-join phase of a cross-aggregate query. Two strategies are supported,
 * with selection delegated to [QueryPlanner.chooseStrategy]:
 *
 * - [ViaStrategy.PER_PARENT_LOOP]: iterates every parent lazily and applies the Via* node's
 *   `matches` directly, keeping live reads on `parentProp` for each parent individually.
 * - [ViaStrategy.HASH_JOIN]: pre-filters the child registry by the child predicate, materialises
 *   matching ids into a [HashSet], then tests each parent's live `parentProp` collection against
 *   that set with the quantifier appropriate to the Via* operator.
 *
 * Strategy selection (cardinality estimation, the [ViaStrategy] enum) remains in [QueryPlanner].
 * This class contains only execution — no heuristics, no caching.
 */
internal class ViaJoinExecutor<T : IdentifiableEntity<*>> {

    /**
     * Core Via execution: splits hybrid `And(NonVia, Via*)`, selects a strategy for the
     * Via* arm, and applies the NonVia arm as a lazy post-filter. Multi-Via*
     * compounds that cannot be isolated to a single top-level Via* arm fall back to a
     * per-parent `predicate.matches(p)` loop — each `Via*.matches` handles its own live
     * access correctness. The returned sequence is fully lazy.
     */
    fun executeViaPlan(
        pred: Predicate<T>,
        parentRegistry: Registry<*, T>,
        splitHybridAnd: (Predicate<T>) -> Pair<Predicate<T>?, Predicate<T>?>,
        chooseStrategy: (Predicate<T>, Registry<*, T>) -> ViaStrategy
    ): Sequence<T> {
        val (nonVia, viaArm) = splitHybridAnd(pred)
        if (viaArm == null) {
            // Multi-Via* compound (e.g. And(Via*, Via*) or Or(Via*, Via*)) — fall back to a
            // straight per-parent loop: each Via* node's `matches` already handles its own
            // live-read invariant, so correctness is preserved without strategy selection.
            log.debug { "Via planner: multi-Via* compound, falling back to per-parent loop on full predicate" }
            return sequence {
                for (p in parentRegistry) {
                    if (pred.matches(p)) yield(p)
                }
            }
        }

        val strategy = chooseStrategy(viaArm, parentRegistry)
        log.trace { "Via executor: dispatching strategy=$strategy for ${viaArm::class.simpleName}" }
        val viaResults =
            when (strategy) {
                ViaStrategy.PER_PARENT_LOOP -> perParentLoop(viaArm, parentRegistry)
                ViaStrategy.HASH_JOIN -> hashJoin(viaArm, parentRegistry)
            }

        return if (nonVia != null) viaResults.filter { nonVia.matches(it) } else viaResults
    }

    /**
     * Lazy per-parent loop execution. Each parent is yielded one at a time; live reads of
     * `parentProp` happen inside the Via* node's `matches` implementation, never cached
     * here. Suitable as the test seam entry point.
     */
    fun perParentLoop(via: Predicate<T>, parentRegistry: Registry<*, T>): Sequence<T> =
        sequence {
            for (p in parentRegistry) {
                if (via.matches(p)) yield(p)
            }
        }

    /**
     * Hash-join execution. Materialises matching child ids into a [HashSet] once, then
     * iterates parents lazily testing the live `parentProp` collection against the set
     * with the quantifier appropriate to the Via* operator.
     *
     * **Live-read invariant is preserved.** Although the matching-children set is snapshotted
     * up-front (the strategy's defining property), each parent's `parentProp.get(p)` call
     * runs at yield time. An `@Aggregate(onDelete = DETACH)` reconciliation that runs
     * mid-iteration is reflected on the very next parent yielded — the snapshot only fixes
     * which child ids are "interesting", never which parents reference them.
     *
     * Empty-collection semantics and null-single-entity semantics are preserved per the
     * Via* operator's contract.
     */
    @Suppress("UNCHECKED_CAST")
    fun hashJoin(via: Predicate<T>, parentRegistry: Registry<*, T>): Sequence<T> =
        when (via) {
            is ViaAnyMatch<*, *, *> ->
                hashJoinAnyMatch(via as ViaAnyMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>, parentRegistry)
            is ViaAllMatch<*, *, *> ->
                hashJoinAllMatch(via as ViaAllMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>, parentRegistry)
            is ViaNoneMatch<*, *, *> ->
                hashJoinNoneMatch(via as ViaNoneMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>, parentRegistry)
            is ViaWhere<*, *, *> ->
                hashJoinWhere(via as ViaWhere<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>, parentRegistry)
            else -> error("hashJoin called with non-Via predicate: ${via::class.simpleName}")
        }

    private fun <K : Comparable<K>, C : IdentifiableEntity<K>> matchingChildIds(
        registry: Registry<K, C>,
        predicate: Predicate<C>
    ): HashSet<K> = registry.asSequence().filter { predicate.matches(it) }.map { it.id }.toHashSet()

    private fun hashJoinAnyMatch(
        v: ViaAnyMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>,
        parentRegistry: Registry<*, T>
    ): Sequence<T> {
        val matchingIds = matchingChildIds(v.childRegistry, v.childPredicate)
        return sequence {
            for (p in parentRegistry) {
                if (v.parentProp.get(p).any { it in matchingIds }) yield(p)
            }
        }
    }

    private fun hashJoinAllMatch(
        v: ViaAllMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>,
        parentRegistry: Registry<*, T>
    ): Sequence<T> {
        val matchingIds = matchingChildIds(v.childRegistry, v.childPredicate)
        return sequence {
            for (p in parentRegistry) {
                val refs = v.parentProp.get(p)
                if (refs.isEmpty() || refs.all { it in matchingIds }) yield(p)
            }
        }
    }

    private fun hashJoinNoneMatch(
        v: ViaNoneMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>,
        parentRegistry: Registry<*, T>
    ): Sequence<T> {
        val matchingIds = matchingChildIds(v.childRegistry, v.childPredicate)
        return sequence {
            for (p in parentRegistry) {
                if (v.parentProp.get(p).none { it in matchingIds }) yield(p)
            }
        }
    }

    private fun hashJoinWhere(
        v: ViaWhere<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>,
        parentRegistry: Registry<*, T>
    ): Sequence<T> {
        val matchingIds = matchingChildIds(v.childRegistry, v.childPredicate)
        return sequence {
            for (p in parentRegistry) {
                val id = v.parentProp.get(p)
                if (id != null && id in matchingIds) yield(p)
            }
        }
    }
}