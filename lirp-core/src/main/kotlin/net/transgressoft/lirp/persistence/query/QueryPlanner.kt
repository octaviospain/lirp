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
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.NavigableMap
import kotlin.reflect.KProperty1

private val log = KotlinLogging.logger {}

/**
 * Plans and executes [Query] instances against a [Registry], selecting the optimal
 * retrieval strategy based on indexed property metadata.
 *
 * The planner operates in three modes:
 * - [Strategy.INDEX_ONLY]: every predicate leaf is an indexed equality, membership, or
 *   sorted-range check; results come directly from the index with no re-filtering.
 * - [Strategy.INDEX_THEN_FILTER]: some predicate leaves are indexed equality checks,
 *   but others (range, negation, OR) require post-filtering.
 * - [Strategy.SCAN_ONLY]: no indexed equality leaves are present; a full scan is required.
 *
 * `Predicate.In` leaves on indexed properties evaluate as the union of per-value `findByIndex`
 * lookups (no cardinality threshold). Empty-`In` short-circuits at planner level to `emptySequence()`.
 *
 * `Predicate.Gt`/`Gte`/`Lt`/`Lte` leaves on `@Indexed(sorted = true)` properties slice the
 * registry's `NavigableMap` bucket (`tailMap` for `Gt`/`Gte`, `headMap` for `Lt`/`Lte`) and
 * flatten the matching value sets into a candidate `Set<T>` (O(log N + |result|)). The slice
 * is pre-materialised at plan time via [IndexableLeaf.RangeSlice], bypassing per-key
 * `findByIndex` dispatch to avoid the per-key overhead of O(|slice| × hash-lookup).
 *
 * @param T the entity type
 * @param isIndexed returns `true` if the given property is indexed
 * @param indexNameFor returns the index name for a property (fallback to [KProperty1.name])
 * @param isSortedIndexed returns `true` if the given property is `@Indexed(sorted = true)`;
 *   defaults to `false` so callers that do not opt in retain current behavior
 * @param sortedBucketFor returns the live `NavigableMap` bucket for the given index name, or
 *   `null` if the index is not sorted; defaults to `{ null }` so non-sorted callers are unaffected
 */
internal class QueryPlanner<T : IdentifiableEntity<*>>(
    private val isIndexed: (KProperty1<T, *>) -> Boolean,
    private val indexNameFor: (KProperty1<T, *>) -> String = { it.name },
    private val isSortedIndexed: (KProperty1<T, *>) -> Boolean = { false },
    private val sortedBucketFor: (String) -> NavigableMap<Comparable<Any>, MutableSet<T>>? = { null }
) {

    private val viaJoinExecutor = ViaJoinExecutor<T>()

    /**
     * Internal discriminated leaf representation used during index candidate extraction.
     *
     * [Single] carries a single exact value (from [Predicate.Eq]); [Multi] carries a value-set
     * (from [Predicate.In]) and is resolved as a union of per-value `findByIndex` lookups.
     * [RangeSlice] carries a pre-materialised candidate set from a `NavigableMap` range slice
     * (from `Gt`/`Gte`/`Lt`/`Lte` on sorted-indexed properties); no further `findByIndex`
     * dispatch is needed — the candidates are already flattened from the bucket sets.
     * The [RangeSlice.candidates] set holds `T` instances erased to `Any`; callers must
     * suppress the unchecked-cast warning when retrieving them as `Set<T>`.
     */
    internal sealed interface IndexableLeaf {
        val propertyName: String
        val indexName: String

        data class Single(override val propertyName: String, override val indexName: String, val value: Any) : IndexableLeaf

        data class Multi(override val propertyName: String, override val indexName: String, val values: Set<Any>) : IndexableLeaf

        /** [candidates] erased to `Set<Any>` — type-safely `Set<T>` at construction. */
        data class RangeSlice(override val propertyName: String, override val indexName: String, val candidates: Set<Any>) : IndexableLeaf
    }

    /**
     * Planning result carrying the chosen strategy, index leaves, post-filter count, via-strategy,
     * and a lazy result sequence.
     *
     * `strategy` is the retrieval mode selected by the planner. `indexLeaves` lists every predicate
     * leaf that was pushed to an index. `postFilterCount` is the number of predicate leaves not
     * resolved by any index, requiring a post-filter pass over the candidate set. `viaStrategy` is
     * non-null when the query contained a cross-aggregate `via` arm. `results` is the lazy entity
     * sequence, which may include ordering and pagination applied on top of the candidate set.
     *
     * @param strategy the chosen execution strategy
     * @param indexLeaves the index-resolved leaves, in extraction order
     * @param postFilterCount the number of predicate leaves that require post-filtering
     * @param viaStrategy the via-join strategy when a cross-aggregate arm is present, or `null`
     * @param results a lazy sequence of matching entities
     */
    internal data class PlanContext<T>(
        val strategy: Strategy,
        val indexLeaves: List<IndexableLeaf>,
        val postFilterCount: Int,
        val viaStrategy: ViaStrategy?,
        val results: Sequence<T>
    )

    private data class SelectResult<T>(
        val strategy: Strategy,
        val indexLeaves: List<IndexableLeaf>,
        val postFilterCount: Int,
        val viaStrategy: ViaStrategy?,
        val candidates: Sequence<T>
    )

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
     * is what makes `@ToOneAggregate(onDelete = DETACH)` / `@ToManyAggregates(onDelete = DETACH)` reconciliation surface in the next
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
     * @return a [PlanContext] containing the strategy, index leaves, post-filter count, via-strategy, and result sequence
     */
    fun execute(query: Query<T>, registry: Registry<*, T>): PlanContext<T> {
        // Normalise Via* fold rules before any strategy selection.
        // ViaNormalizer is a no-op for predicates without Via* nodes, so this stays cheap
        // for queries that do not use cross-aggregate traversal.
        val pred = query.predicate?.let { normalize(it) }
        val (strategy, indexLeaves, postFilterCount, viaStrat, candidates) = selectStrategyAndCandidates(pred, registry)
        val ordered = applyOrdering(candidates, query.orderBy)
        val sliced = applyPagination(ordered, query.offset, query.limit)
        return PlanContext(strategy, indexLeaves, postFilterCount, viaStrat, sliced)
    }

    private fun selectStrategyAndCandidates(
        pred: Predicate<T>?,
        registry: Registry<*, T>
    ): SelectResult<T> {
        if (pred == null) return SelectResult(Strategy.SCAN_ONLY, emptyList(), 0, null, registry.asSequence())
        // Empty-In short-circuit: x ∈ ∅ is always false — no entities can match.
        if (pred is Predicate.In<*, *> && (pred as Predicate.In<T, *>).values.isEmpty()) {
            return SelectResult(Strategy.INDEX_ONLY, emptyList(), 0, null, emptySequence())
        }
        if (containsVia(pred)) {
            // Cross-aggregate path: split hybrid predicate, choose Via strategy, apply
            // any NonVia arm as a lazy post-filter. Strategy reported to callers is
            // still SCAN_ONLY at the parent level (no index acceleration on Via* nodes).
            // The NonVia arm of a hybrid And(NonVia, Via*) is post-filtered, so its leaves
            // count toward postFilterCount; a bare or multi-Via* predicate has none.
            val viaStrat = strategyFor(pred, registry)
            val (nonViaArm, _) = splitHybridAnd(pred)
            val viaPostFilterCount = nonViaArm?.let { countLeaves(it) } ?: 0
            return SelectResult(Strategy.SCAN_ONLY, emptyList(), viaPostFilterCount, viaStrat, executeViaPlan(pred, registry))
        }
        val indexable = extractIndexableLeaves(pred)
        if (indexable.isEmpty()) {
            log.trace { "QueryPlanner: no indexable leaves — full scan on ${registry.size()} entities" }
            return SelectResult(Strategy.SCAN_ONLY, emptyList(), countLeaves(pred), null, registry.asSequence().filter { pred.matches(it) })
        }
        return indexAcceleratedCandidates(pred, indexable, registry)
    }

    /**
     * Counts the non-Via predicate leaves in [pred]. Used to compute the post-filter count
     * for scan paths where no index acceleration is available.
     *
     * Composite nodes (And, Or) recurse into both arms. Not-wrapped leaves count as one leaf
     * (the inner leaf). Via* nodes are not counted — they are reported via [PlanContext.viaStrategy].
     */
    private fun countLeaves(pred: Predicate<T>): Int =
        when (pred) {
            is Predicate.Eq<*, *>, is Predicate.In<*, *>,
            is Predicate.Gt<*, *>, is Predicate.Gte<*, *>,
            is Predicate.Lt<*, *>, is Predicate.Lte<*, *> -> 1
            is Predicate.Not<*> -> {
                @Suppress("UNCHECKED_CAST")
                countLeaves((pred as Predicate.Not<T>).inner)
            }
            is Predicate.And<*> -> {
                @Suppress("UNCHECKED_CAST")
                val a = pred as Predicate.And<T>
                countLeaves(a.left) + countLeaves(a.right)
            }
            is Predicate.Or<*> -> {
                @Suppress("UNCHECKED_CAST")
                val o = pred as Predicate.Or<T>
                countLeaves(o.left) + countLeaves(o.right)
            }
            else -> 0
        }

    private fun indexAcceleratedCandidates(
        pred: Predicate<T>,
        indexable: List<IndexableLeaf>,
        registry: Registry<*, T>
    ): SelectResult<T> {
        // Use the internal non-copying index read when available (RegistryBase), falling back to the
        // public defensive-copy findByIndex for any other Registry implementation.
        val noCopyRegistry = registry as? RegistryBase<*, T>
        val strategy = if (allLeavesAreIndexedEq(pred)) Strategy.INDEX_ONLY else Strategy.INDEX_THEN_FILTER
        // When any In leaf contains null, the index cannot resolve null-valued entities (null keys
        // are not stored), so execution falls back to a full scan re-evaluating every leaf — none
        // are effectively index-resolved, so the whole predicate is post-filtered.
        val nullInScan = containsInWithNull(pred)
        val postFilterCount =
            when {
                strategy == Strategy.INDEX_ONLY -> 0
                nullInScan -> countLeaves(pred)
                else -> countLeaves(pred) - indexable.size
            }
        // Candidate resolution (index reads, intersection, post-filter scan) is deferred into the
        // returned sequence so the planner stays plan-only until a terminal operation consumes the
        // results — honoring the lazy contract in execute()'s KDoc and keeping explainQuery cheap.
        val candidates =
            sequence {
                var working: Set<T>? = null
                for (leaf in indexable) {
                    val hit: Collection<T> =
                        when (leaf) {
                            is IndexableLeaf.Single ->
                                noCopyRegistry?.findByIndexNoCopy(leaf.indexName, leaf.value)
                                    ?: registry.findByIndex(leaf.indexName, leaf.value)
                            is IndexableLeaf.Multi ->
                                leaf.values.flatMapTo(HashSet()) { v ->
                                    noCopyRegistry?.findByIndexNoCopy(leaf.indexName, v)
                                        ?: registry.findByIndex(leaf.indexName, v)
                                }
                            // RangeSlice candidates are pre-materialised from NavigableMap bucket sets —
                            // no further findByIndex dispatch needed. The cast is safe: RangeSlice is
                            // constructed only via rangeLeaf(), which receives Set<T> from rangeSlice().
                            is IndexableLeaf.RangeSlice -> {
                                @Suppress("UNCHECKED_CAST")
                                leaf.candidates as Set<T>
                            }
                        }
                    working = working?.let { it intersect hit } ?: hit.toHashSet()
                    if (working.isEmpty()) break
                }
                val candidateSet = working ?: emptySet()
                val resolved =
                    when {
                        strategy == Strategy.INDEX_ONLY -> candidateSet.asSequence()
                        nullInScan -> registry.asSequence().filter { pred.matches(it) }
                        else -> candidateSet.asSequence().filter { pred.matches(it) }
                    }
                yieldAll(resolved)
            }
        return SelectResult(strategy, indexable, postFilterCount, null, candidates)
    }

    /**
     * Returns `true` if [pred] contains a [Predicate.In] leaf whose [Predicate.In.values] set
     * contains `null`. Such predicates require a full registry scan even when the property is
     * indexed, because null values are never stored in the index.
     *
     * Recurses into [Predicate.And], [Predicate.Or], and [Predicate.Not] composites so that
     * a null-valued `In` leaf nested inside an `Or` branch is correctly detected.
     */
    private fun containsInWithNull(pred: Predicate<T>): Boolean =
        when (pred) {
            is Predicate.In<*, *> -> null in (pred as Predicate.In<T, *>).values
            is Predicate.And<*> -> {
                @Suppress("UNCHECKED_CAST")
                val a = pred as Predicate.And<T>
                containsInWithNull(a.left) || containsInWithNull(a.right)
            }
            is Predicate.Or<*> -> {
                @Suppress("UNCHECKED_CAST")
                val o = pred as Predicate.Or<T>
                containsInWithNull(o.left) || containsInWithNull(o.right)
            }
            is Predicate.Not<*> -> {
                @Suppress("UNCHECKED_CAST")
                containsInWithNull((pred as Predicate.Not<T>).inner)
            }
            else -> false
        }

    /**
     * Materialises a `NavigableMap` range slice for a range predicate leaf, returning the
     * flattened candidate set or `null` when the index is unavailable.
     *
     * The cast `v as Comparable<Any>` is safe because [Predicate.Gt], [Predicate.Gte],
     * [Predicate.Lt], and [Predicate.Lte] all declare `V : Comparable<V>` on their value
     * parameter. Kotlin type erasure forces the cast at the call site; the type bound on the
     * predicate class guarantees no `ClassCastException` at runtime.
     *
     * When [sortedBucketFor] returns `null` (defensive — the storage invariant should prevent
     * this for any sorted-indexed property) the method returns `null`, causing the call site
     * to fall back to the scan path for that leaf.
     */
    @Suppress("UNCHECKED_CAST")
    private fun rangeSlice(leaf: Predicate<T>): Set<T>? {
        return when (leaf) {
            is Predicate.Gt<*, *> -> {
                val pred = leaf as Predicate.Gt<T, Comparable<Any>>
                val bucket =
                    sortedBucketFor(indexNameFor(pred.prop)) ?: run {
                        log.debug { "sortedBucketFor returned null for ${pred.prop.name}; falling back to scan" }
                        return null
                    }
                bucket.tailMap(pred.value, false).values.flatMapTo(HashSet()) { it }
            }
            is Predicate.Gte<*, *> -> {
                val pred = leaf as Predicate.Gte<T, Comparable<Any>>
                val bucket =
                    sortedBucketFor(indexNameFor(pred.prop)) ?: run {
                        log.debug { "sortedBucketFor returned null for ${pred.prop.name}; falling back to scan" }
                        return null
                    }
                bucket.tailMap(pred.value, true).values.flatMapTo(HashSet()) { it }
            }
            is Predicate.Lt<*, *> -> {
                val pred = leaf as Predicate.Lt<T, Comparable<Any>>
                val bucket =
                    sortedBucketFor(indexNameFor(pred.prop)) ?: run {
                        log.debug { "sortedBucketFor returned null for ${pred.prop.name}; falling back to scan" }
                        return null
                    }
                bucket.headMap(pred.value, false).values.flatMapTo(HashSet()) { it }
            }
            is Predicate.Lte<*, *> -> {
                val pred = leaf as Predicate.Lte<T, Comparable<Any>>
                val bucket =
                    sortedBucketFor(indexNameFor(pred.prop)) ?: run {
                        log.debug { "sortedBucketFor returned null for ${pred.prop.name}; falling back to scan" }
                        return null
                    }
                bucket.headMap(pred.value, true).values.flatMapTo(HashSet()) { it }
            }
            else -> null
        }
    }

    private fun applyOrdering(candidates: Sequence<T>, orderBy: List<OrderClause<T>>): Sequence<T> {
        if (orderBy.isEmpty()) return candidates
        val cmp = composeComparator(orderBy)
        // Defer the materialise-and-sort to the first terminal operation so planning stays
        // execution-free; the sort runs when the consumer iterates, not at plan() / execute() time.
        return sequence { yieldAll(candidates.toList().sortedWith(cmp)) }
    }

    private fun applyPagination(seq: Sequence<T>, offset: Int, limit: Int?): Sequence<T> {
        val dropped = seq.drop(offset)
        return if (limit != null) dropped.take(limit) else dropped
    }

    /**
     * Walks the predicate AST and extracts every indexed leaf as an [IndexableLeaf].
     *
     * [Predicate.Eq] on an indexed property with a non-null value produces [IndexableLeaf.Single].
     * [Predicate.In] on an indexed property with non-empty non-null values produces [IndexableLeaf.Multi].
     * `Gt`/`Gte`/`Lt`/`Lte` on a sorted-indexed property produce [IndexableLeaf.RangeSlice] with
     * candidates pre-materialised via the `NavigableMap` slice. Non-sorted-indexed range leaves
     * return `emptyList()` (conservative fallback to scan for that subtree).
     * [Predicate.And] nodes are recursed into; [Predicate.Or] and [Predicate.Not] short-circuit.
     */
    @Suppress("UNCHECKED_CAST")
    private fun extractIndexableLeaves(pred: Predicate<T>): List<IndexableLeaf> =
        when (pred) {
            is Predicate.Eq<*, *> -> {
                val eq = pred as Predicate.Eq<T, Any?>
                if (isIndexed(eq.prop) && eq.value != null) {
                    listOf(IndexableLeaf.Single(eq.prop.name, indexNameFor(eq.prop), eq.value))
                } else {
                    emptyList()
                }
            }
            is Predicate.In<*, *> -> {
                val inPred = pred as Predicate.In<T, Any?>
                if (!isIndexed(inPred.prop)) return emptyList()
                // Empty values: emit an empty Multi so AND-intersection in
                // indexAcceleratedCandidates collapses the candidate set to ∅. Without this,
                // allLeavesAreIndexedEq classifies the leaf as resolved (null !in []) while it
                // contributes no candidates, and a sibling Eq's candidates leak through INDEX_ONLY.
                if (inPred.values.isEmpty()) {
                    return listOf(IndexableLeaf.Multi(inPred.prop.name, indexNameFor(inPred.prop), emptySet()))
                }
                val nonNullValues = inPred.values.filterNotNull().toSet()
                if (nonNullValues.isEmpty()) emptyList()
                else listOf(IndexableLeaf.Multi(inPred.prop.name, indexNameFor(inPred.prop), nonNullValues))
            }
            is Predicate.Gt<*, *> -> rangeLeaf(pred as Predicate.Gt<T, *>)
            is Predicate.Gte<*, *> -> rangeLeaf(pred as Predicate.Gte<T, *>)
            is Predicate.Lt<*, *> -> rangeLeaf(pred as Predicate.Lt<T, *>)
            is Predicate.Lte<*, *> -> rangeLeaf(pred as Predicate.Lte<T, *>)
            is Predicate.And<*> -> {
                val a = pred as Predicate.And<T>
                extractIndexableLeaves(a.left) + extractIndexableLeaves(a.right)
            }
            else -> emptyList()
        }

    private fun rangeLeafResolved(prop: KProperty1<T, *>): Boolean =
        isSortedIndexed(prop) && sortedBucketFor(indexNameFor(prop)) != null

    /** Produces a [IndexableLeaf.RangeSlice] for range leaves on sorted-indexed properties. */
    private fun rangeLeaf(pred: Predicate<T>): List<IndexableLeaf> {
        val prop =
            when (pred) {
                is Predicate.Gt<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    (pred as Predicate.Gt<T, *>).prop
                }
                is Predicate.Gte<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    (pred as Predicate.Gte<T, *>).prop
                }
                is Predicate.Lt<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    (pred as Predicate.Lt<T, *>).prop
                }
                is Predicate.Lte<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    (pred as Predicate.Lte<T, *>).prop
                }
                else -> return emptyList()
            }
        if (!isSortedIndexed(prop)) return emptyList()
        val candidates = rangeSlice(pred) ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        return listOf(IndexableLeaf.RangeSlice(prop.name, indexNameFor(prop), candidates as Set<Any>))
    }

    /**
     * Returns `true` if every leaf in the AST is fully resolved by the index with no residual scan.
     *
     * Covers:
     * - [Predicate.Eq] on an indexed property with a non-null value
     * - [Predicate.In] on an indexed property with no null values
     * - `Gt`/`Gte`/`Lt`/`Lte` on a sorted-indexed property (range slice is complete — no residual)
     *
     * The function name is historical (`allLeavesAreIndexedEq`); renaming is deferred to avoid
     * call-site churn — it now also covers `In` and sorted-range leaves.
     */
    @Suppress("UNCHECKED_CAST")
    private fun allLeavesAreIndexedEq(pred: Predicate<T>): Boolean =
        when (pred) {
            is Predicate.Eq<*, *> ->
                (pred as Predicate.Eq<T, Any?>).let { eq -> isIndexed(eq.prop) && eq.value != null }
            is Predicate.In<*, *> -> {
                val inPred = pred as Predicate.In<T, Any?>
                isIndexed(inPred.prop) && null !in inPred.values
            }
            // A sorted-indexed range leaf is only safely "resolved" if the registry has
            // actually allocated the NavigableMap bucket. If sortedBucketFor returns null,
            // rangeLeaf emits no candidates — classifying the leaf as resolved anyway would
            // let a sibling indexed leaf's candidates leak through INDEX_ONLY without the
            // range predicate applied.
            is Predicate.Gt<*, *> -> rangeLeafResolved((pred as Predicate.Gt<T, *>).prop)
            is Predicate.Gte<*, *> -> rangeLeafResolved((pred as Predicate.Gte<T, *>).prop)
            is Predicate.Lt<*, *> -> rangeLeafResolved((pred as Predicate.Lt<T, *>).prop)
            is Predicate.Lte<*, *> -> rangeLeafResolved((pred as Predicate.Lte<T, *>).prop)
            is Predicate.And<*> -> {
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
     * Test seam exposing the planner's chosen-strategy lazy sequence directly,
     * bypassing the [Registry.query] entry point. The returned sequence yields parents
     * at the per-parent boundary so a test may mutate a parent's `parentProp` between
     * two `.next()` calls and observe the live read on the very next yield.
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
     * Core Via execution: delegates to [ViaJoinExecutor] which handles strategy dispatch
     * and all hash-join / per-parent-loop implementations. Strategy selection via
     * [chooseStrategy] and the [ViaStrategy] enum remain in this class.
     */
    private fun executeViaPlan(pred: Predicate<T>, parentRegistry: Registry<*, T>): Sequence<T> =
        viaJoinExecutor.executeViaPlan(pred, parentRegistry, ::splitHybridAnd, ::chooseStrategy)

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