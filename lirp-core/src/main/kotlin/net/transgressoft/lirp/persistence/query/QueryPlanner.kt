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
import kotlin.reflect.KProperty1

private val log = KotlinLogging.logger {}

/**
 * Execution strategy for cross-aggregate `via … anyMatch/allMatch/noneMatch/where` predicates.
 *
 * Selected per-query by [QueryPlanner.chooseStrategy] based on the cardinality estimate
 * `|children matching predicate| < |parents| × avg-refs-per-parent` (D-08). Never cached
 * across queries; re-estimated on every execution (D-10).
 */
enum class ViaStrategy {
    /**
     * Iterates parents lazily; for each parent, reads the live `parentProp` collection and
     * applies the Via* node's `matches` directly (delegates child resolution via
     * [Registry.findById]).
     */
    PER_PARENT_LOOP,

    /**
     * Pre-filters the child registry by the child predicate, materialises matching ids into
     * a [HashSet], then per-parent tests `parentProp` reads (live) against that set with the
     * quantifier appropriate to the Via* operator.
     */
    HASH_JOIN
}

/**
 * Plans and executes [Query] instances against a [Registry], selecting the optimal
 * retrieval strategy based on indexed property metadata.
 *
 * The planner operates in three modes:
 * - [Strategy.INDEX_ONLY]: every predicate leaf is an indexed equality check;
 *   results come directly from the index with no re-filtering.
 * - [Strategy.INDEX_THEN_FILTER]: some predicate leaves are indexed equality checks,
 *   but others (range, negation, OR) require post-filtering.
 * - [Strategy.SCAN_ONLY]: no indexed equality leaves are present; a full scan is required.
 *
 * @param T the entity type
 * @param isIndexed returns `true` if the given property is indexed
 * @param indexNameFor returns the index name for a property (fallback to [KProperty1.name])
 */
internal class QueryPlanner<T : IdentifiableEntity<*>>(
    private val isIndexed: (KProperty1<T, *>) -> Boolean,
    private val indexNameFor: (KProperty1<T, *>) -> String = { it.name }
) {

    /**
     * Execution strategy selected by the planner.
     */
    enum class Strategy {
        /** All predicate leaves are indexed equality checks; results come directly from the index. */
        INDEX_ONLY,

        /** Some leaves are indexed equality checks, but others require post-filtering. */
        INDEX_THEN_FILTER,

        /** No indexed equality leaves are present; a full in-memory scan is required. */
        SCAN_ONLY
    }

    /**
     * Result of query planning, containing the chosen strategy and a lazy [Sequence]
     * of matching entities.
     *
     * @param strategy the chosen execution strategy
     * @param results a lazy sequence of matching entities
     */
    data class Plan<T>(val strategy: Strategy, val results: Sequence<T>)

    /**
     * Executes [query] against [registry], selecting the optimal strategy.
     *
     * The returned [Sequence] is lazy — no entities are fetched until a terminal
     * operation (e.g. [toList], [firstOrNull], [count]) is invoked.
     *
     * **Execution pipeline.** The predicate is first normalised (cross-aggregate OR/AND
     * folding; a no-op for predicates without `Via*` nodes). If the result contains any
     * `Via*` node, execution dispatches into the cross-aggregate path, which splits hybrid
     * `And(NonVia, Via*)` predicates, picks a [ViaStrategy] per query via [chooseStrategy],
     * and applies any non-Via arm as a lazy post-filter. Otherwise the single-aggregate
     * index / scan paths apply unchanged.
     *
     * **Live `referenceIds` reads.** Both Via execution paths read `parentProp.get(p)` at
     * per-parent matching time; no parent collection is snapshotted at planner entry. This
     * is what makes `@Aggregate(onDelete = DETACH)` reconciliation surface in the next
     * query call.
     *
     * **Note on ordering:** when [Query.orderBy] is non-empty, the candidate sequence
     * is materialised into a [List] before sorting. For large unfiltered registries
     * this is O(n) memory; combine with [Query.limit] where possible.
     *
     * **Note on pagination without ordering:** [Strategy.INDEX_ONLY] returns results
     * in [Set] iteration order, which is non-deterministic across JVM runs. For
     * stable [Query.offset] / [Query.limit] behaviour, always pair pagination with
     * an explicit [Query.orderBy].
     *
     * @param query the query to execute
     * @param registry the registry to search
     * @return a [Plan] containing the strategy and result sequence
     */
    fun execute(query: Query<T>, registry: Registry<*, T>): Plan<T> {
        // Normalise Via* fold rules (Plan 01 ViaNormalizer) before any strategy selection.
        // ViaNormalizer is a no-op for predicates without Via* nodes, so this stays cheap
        // for Phase 52-only queries.
        val pred = query.predicate?.let { normalize(it) }
        val (strategy, candidates) = selectStrategyAndCandidates(pred, registry)
        val ordered = applyOrdering(candidates, query.orderBy)
        val sliced = applyPagination(ordered, query.offset, query.limit)
        return Plan(strategy, sliced)
    }

    private fun selectStrategyAndCandidates(
        pred: Predicate<T>?,
        registry: Registry<*, T>
    ): Pair<Strategy, Sequence<T>> {
        if (pred == null) return Strategy.SCAN_ONLY to registry.asSequence()
        if (containsVia(pred)) {
            // Cross-aggregate path: split hybrid predicate, choose Via strategy, apply
            // any NonVia arm as a lazy post-filter (W-2). Strategy reported to callers is
            // still SCAN_ONLY at the parent level (no index acceleration on Via* nodes).
            return Strategy.SCAN_ONLY to executeViaPlan(pred, registry)
        }
        val indexable = extractIndexableEqs(pred)
        if (indexable.isEmpty()) return Strategy.SCAN_ONLY to registry.asSequence().filter { pred.matches(it) }
        return indexAcceleratedCandidates(pred, indexable, registry)
    }

    private fun indexAcceleratedCandidates(
        pred: Predicate<T>,
        indexable: List<Pair<String, Any>>,
        registry: Registry<*, T>
    ): Pair<Strategy, Sequence<T>> {
        var working: Set<T>? = null
        for ((name, value) in indexable) {
            val hit = registry.findByIndex(name, value)
            working = working?.let { it intersect hit } ?: hit
            if (working.isEmpty()) break
        }
        val candidateSet = working ?: emptySet()
        val strategy = if (allLeavesAreIndexedEq(pred)) Strategy.INDEX_ONLY else Strategy.INDEX_THEN_FILTER
        val candidates =
            if (strategy == Strategy.INDEX_ONLY) candidateSet.asSequence()
            else candidateSet.asSequence().filter { pred.matches(it) }
        return strategy to candidates
    }

    private fun applyOrdering(candidates: Sequence<T>, orderBy: List<OrderClause<T>>): Sequence<T> {
        if (orderBy.isEmpty()) return candidates
        val cmp = composeComparator(orderBy)
        return candidates.toList().sortedWith(cmp).asSequence()
    }

    private fun applyPagination(seq: Sequence<T>, offset: Int, limit: Int?): Sequence<T> {
        val dropped = seq.drop(offset)
        return if (limit != null) dropped.take(limit) else dropped
    }

    /**
     * Walks the predicate AST and extracts every [Predicate.Eq] leaf whose property
     * is indexed and whose value is non-null.
     *
     * [Predicate.And] nodes are recursed into; [Predicate.Or], [Predicate.Not],
     * and range predicates short-circuit to empty for the subtree (conservative fallback).
     */
    private fun extractIndexableEqs(pred: Predicate<T>): List<Pair<String, Any>> =
        when (pred) {
            is Predicate.Eq<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val eq = pred as Predicate.Eq<T, Any?>
                if (isIndexed(eq.prop) && eq.value != null) {
                    listOf(indexNameFor(eq.prop) to eq.value)
                } else {
                    emptyList()
                }
            }
            is Predicate.And<*> -> {
                @Suppress("UNCHECKED_CAST")
                val a = pred as Predicate.And<T>
                extractIndexableEqs(a.left) + extractIndexableEqs(a.right)
            }
            else -> emptyList()
        }

    /**
     * Returns `true` if every leaf in the AST is an indexed [Predicate.Eq].
     */
    private fun allLeavesAreIndexedEq(pred: Predicate<T>): Boolean =
        when (pred) {
            is Predicate.Eq<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                (pred as Predicate.Eq<T, Any?>).let { eq -> isIndexed(eq.prop) && eq.value != null }
            }
            is Predicate.And<*> -> {
                @Suppress("UNCHECKED_CAST")
                val a = pred as Predicate.And<T>
                allLeavesAreIndexedEq(a.left) && allLeavesAreIndexedEq(a.right)
            }
            else -> false
        }

    /**
     * Returns `true` if [pred] contains any `Via*` node (direct or under composite
     * `And`/`Or`/`Not`). Drives the cross-aggregate branch in [execute].
     */
    private fun containsVia(pred: Predicate<T>): Boolean =
        when (pred) {
            is ViaAnyMatch<*, *, *>, is ViaAllMatch<*, *, *>,
            is ViaNoneMatch<*, *, *>, is ViaWhere<*, *, *> -> true
            is Predicate.And<*> -> {
                @Suppress("UNCHECKED_CAST")
                val a = pred as Predicate.And<T>
                containsVia(a.left) || containsVia(a.right)
            }
            is Predicate.Or<*> -> {
                @Suppress("UNCHECKED_CAST")
                val o = pred as Predicate.Or<T>
                containsVia(o.left) || containsVia(o.right)
            }
            is Predicate.Not<*> -> {
                @Suppress("UNCHECKED_CAST")
                val n = pred as Predicate.Not<T>
                containsVia(n.inner)
            }
            else -> false
        }

    /**
     * Detects a single top-level `Via*` node in [pred]. Returns the node when [pred] itself
     * is a `Via*`, or the unique `Via*` operand of an outermost `And(NonVia, Via*)` /
     * `And(Via*, NonVia)`. Returns `null` for predicates without a single, top-level Via*
     * (including multi-Via* compounds, `Or`-wrapped Via*, or Via* nested under `Not`).
     */
    private fun detectTopLevelVia(pred: Predicate<T>): Predicate<T>? =
        when (pred) {
            is ViaAnyMatch<*, *, *>, is ViaAllMatch<*, *, *>,
            is ViaNoneMatch<*, *, *>, is ViaWhere<*, *, *> -> pred
            else -> null
        }

    /**
     * Splits an outermost `And(NonVia, Via*)` (or `And(Via*, NonVia)`) into the pair
     * `(nonVia, via)`. When [pred] is itself a `Via*` with no companion, returns
     * `(null, pred)`. Returns `(null, null)` when no single top-level Via* arm can be
     * isolated (forces the multi-Via*-per-parent fallback in [executeViaPlan]).
     */
    private fun splitHybridAnd(pred: Predicate<T>): Pair<Predicate<T>?, Predicate<T>?> {
        if (detectTopLevelVia(pred) != null) return null to pred
        if (pred is Predicate.And<*>) {
            @Suppress("UNCHECKED_CAST")
            val a = pred as Predicate.And<T>
            val leftVia = detectTopLevelVia(a.left)
            val rightVia = detectTopLevelVia(a.right)
            // Hybrid only when exactly one side is a Via* and the other has no Via* at all.
            if (leftVia != null && !containsVia(a.right)) return a.right to leftVia
            if (rightVia != null && !containsVia(a.left)) return a.left to rightVia
        }
        return null to null
    }

    /**
     * Selects a [ViaStrategy] for a single top-level `Via*` node by sampling up to 100
     * parents for the average reference count and counting children matching the child
     * predicate. The decision rule is `matchingChildCount < parentSize × avgRefsPerParent` →
     * [ViaStrategy.HASH_JOIN]; otherwise [ViaStrategy.PER_PARENT_LOOP].
     *
     * **No caching, re-estimated per query.** Selectivity changes with the dataset and with
     * the child predicate; a cached estimate would either go stale or require maintenance
     * hooks on every child mutation. The spike that validated this design measured "even a
     * heuristic outperforms always-A" — sampling cost is the cheaper invariant than the
     * staleness risk of caching.
     *
     * **Sampling ceiling.** The first up to 100 parents seed `avgRefsPerParent`. With fewer
     * than 100 parents the entire registry is sampled. Beyond 100 the ceiling caps the
     * sampling cost; the bound was validated by the 50k × 20 stress fixture as sufficient.
     *
     * The DEBUG log line records the cardinality inputs (`parentSize`, `sampleSize`,
     * `avgRefsPerParent`, `matchingChildCount`, `crossover`) for tuning visibility.
     *
     * @param via a `Via*` subclass node
     * @param parentRegistry the registry holding the parent entities
     * @param sampleCeiling maximum number of parents to sample for the avg-refs estimate
     * @return [ViaStrategy.HASH_JOIN] when `matchingChildCount < parentSize × avgRefsPerParent`,
     *         else [ViaStrategy.PER_PARENT_LOOP]; degenerate empty-parent registries always
     *         choose [ViaStrategy.PER_PARENT_LOOP]
     */
    internal fun chooseStrategy(
        via: Predicate<T>,
        parentRegistry: Registry<*, T>,
        sampleCeiling: Int = 100
    ): ViaStrategy {
        val parentSize = parentRegistry.size()
        if (parentSize == 0) return ViaStrategy.PER_PARENT_LOOP

        val sampleCount = minOf(sampleCeiling, parentSize)
        val sample = parentRegistry.asSequence().take(sampleCount).toList()

        val (avgRefsPerParent, matchingChildCount) =
            when (via) {
                is ViaAnyMatch<*, *, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val v = via as ViaAnyMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>
                    val avg = sample.sumOf { v.parentProp.get(it).size }.toDouble() / sample.size
                    val count = v.childRegistry.asSequence().count { v.childPredicate.matches(it) }
                    avg to count
                }
                is ViaAllMatch<*, *, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val v = via as ViaAllMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>
                    val avg = sample.sumOf { v.parentProp.get(it).size }.toDouble() / sample.size
                    val count = v.childRegistry.asSequence().count { v.childPredicate.matches(it) }
                    avg to count
                }
                is ViaNoneMatch<*, *, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val v = via as ViaNoneMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>
                    val avg = sample.sumOf { v.parentProp.get(it).size }.toDouble() / sample.size
                    val count = v.childRegistry.asSequence().count { v.childPredicate.matches(it) }
                    avg to count
                }
                is ViaWhere<*, *, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    val v = via as ViaWhere<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>
                    val avg = sample.count { v.parentProp.get(it) != null }.toDouble() / sample.size
                    val count = v.childRegistry.asSequence().count { v.childPredicate.matches(it) }
                    avg to count
                }
                else -> error("chooseStrategy called with non-Via predicate: ${via::class.simpleName}")
            }

        val crossover = parentSize * avgRefsPerParent
        val chosen =
            if (matchingChildCount < crossover) ViaStrategy.HASH_JOIN else ViaStrategy.PER_PARENT_LOOP

        log.debug {
            "Via planner: strategy=$chosen parentSize=$parentSize sampleSize=${sample.size} " +
                "avgRefsPerParent=$avgRefsPerParent matchingChildCount=$matchingChildCount crossover=$crossover"
        }
        return chosen
    }

    /**
     * Test seam (W-4) exposing the planner's chosen-strategy lazy sequence directly,
     * bypassing the [Registry.query] entry point. The returned sequence yields parents
     * at the per-parent boundary so a test may mutate a parent's `parentProp` between
     * two `.next()` calls and observe the live read on the very next yield.
     *
     * Not part of the public API; consumed by Plan 05 Task 1 case 3.
     *
     * @param predicate the query predicate (must already be a Via* node or contain one)
     * @param parentRegistry the registry holding the parent entities
     * @return a lazy [Sequence] of parents matching the predicate under the chosen strategy
     */
    internal fun executeViaSequence(predicate: Predicate<T>, parentRegistry: Registry<*, T>): Sequence<T> {
        val normalised = normalize(predicate)
        return executeViaPlan(normalised, parentRegistry)
    }

    /**
     * Returns the chosen [ViaStrategy] for the top-level `Via*` arm of [predicate]. Test
     * seam used by [ViaPlannerTest] to assert strategy decisions without re-executing.
     *
     * For hybrid `And(NonVia, Via*)` predicates the strategy is computed against the Via*
     * arm only. Multi-Via* compounds return [ViaStrategy.PER_PARENT_LOOP] (see
     * [executeViaPlan]).
     */
    internal fun strategyFor(predicate: Predicate<T>, parentRegistry: Registry<*, T>): ViaStrategy {
        val normalised = normalize(predicate)
        val (_, viaArm) = splitHybridAnd(normalised)
        return if (viaArm != null) chooseStrategy(viaArm, parentRegistry) else ViaStrategy.PER_PARENT_LOOP
    }

    /**
     * Core Via execution: splits hybrid `And(NonVia, Via*)`, selects a strategy for the
     * Via* arm, and applies the NonVia arm as a lazy post-filter (W-2). Multi-Via*
     * compounds that cannot be isolated to a single top-level Via* arm fall back to a
     * per-parent `predicate.matches(p)` loop — each `Via*.matches` handles its own live
     * access correctness. The returned sequence is fully lazy.
     */
    private fun executeViaPlan(pred: Predicate<T>, parentRegistry: Registry<*, T>): Sequence<T> {
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
     * here. Suitable as the test seam (W-4) entry point.
     */
    private fun perParentLoop(via: Predicate<T>, parentRegistry: Registry<*, T>): Sequence<T> =
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
    private fun hashJoin(via: Predicate<T>, parentRegistry: Registry<*, T>): Sequence<T> =
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

    /**
     * Composes a [Comparator] from a list of [OrderClause]s.
     *
     * Nulls sort before non-null values for [Direction.ASC] and after non-null values
     * for [Direction.DESC] (equivalent to SQL `NULLS FIRST` / `NULLS LAST`).
     */
    private fun composeComparator(orders: List<OrderClause<T>>): Comparator<T> {
        var c: Comparator<T>? = null
        for (clause in orders) {
            val keyed =
                Comparator<T> { a, b ->
                    @Suppress("UNCHECKED_CAST")
                    val av = clause.prop.get(a) as Comparable<Any>?
                    val bv = clause.prop.get(b) as Comparable<Any>?
                    when {
                        av == null && bv == null -> 0
                        av == null -> -1
                        bv == null -> 1
                        else -> av.compareTo(bv)
                    }
                }
            val cur = if (clause.direction == Direction.ASC) keyed else keyed.reversed()
            c = if (c == null) cur else c.thenComparing(cur)
        }
        return c ?: Comparator { _, _ -> 0 }
    }
}