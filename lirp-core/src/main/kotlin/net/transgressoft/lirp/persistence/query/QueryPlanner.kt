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
        val pred = query.predicate
        val candidates: Sequence<T>
        val strategy: Strategy

        if (pred == null) {
            candidates = registry.asSequence()
            strategy = Strategy.SCAN_ONLY
        } else {
            val indexable = extractIndexableEqs(pred)
            if (indexable.isNotEmpty()) {
                var working: Set<T>? = null
                for ((name, value) in indexable) {
                    val hit = registry.findByIndex(name, value)
                    working = working?.let { it intersect hit } ?: hit
                    if (working.isEmpty()) break
                }
                val candidateSet = working ?: emptySet()

                strategy =
                    if (allLeavesAreIndexedEq(pred)) Strategy.INDEX_ONLY
                    else Strategy.INDEX_THEN_FILTER

                candidates =
                    if (strategy == Strategy.INDEX_ONLY) {
                        candidateSet.asSequence()
                    } else {
                        candidateSet.asSequence().filter { pred.matches(it) }
                    }
            } else {
                candidates = registry.asSequence().filter { pred.matches(it) }
                strategy = Strategy.SCAN_ONLY
            }
        }

        val ordered =
            if (query.orderBy.isEmpty()) candidates else {
                val list = candidates.toList()
                val cmp = composeComparator(query.orderBy)
                list.sortedWith(cmp).asSequence()
            }

        val sliced =
            ordered.drop(query.offset).let {
                if (query.limit != null) it.take(query.limit) else it
            }

        return Plan(strategy, sliced)
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