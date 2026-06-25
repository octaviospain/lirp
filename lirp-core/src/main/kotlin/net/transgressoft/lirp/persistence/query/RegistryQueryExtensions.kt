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
import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.StandardCrudEvent.Read
import net.transgressoft.lirp.persistence.Registry
import net.transgressoft.lirp.persistence.RegistryBase
import net.transgressoft.lirp.persistence.query.QueryPlanner.IndexableLeaf

/**
 * Executes a type-safe query against this registry using the Kotlin DSL.
 *
 * Example:
 * ```kotlin
 * val electronics = repo.query {
 *     where { category eq "electronics" }
 *     orderBy(price, Direction.ASC)
 *     limit(10)
 * }
 * ```
 *
 * The returned [Sequence] is lazy — no query execution occurs until a terminal
 * operation (e.g. [toList], [firstOrNull], [count]) is called.
 *
 * By default, this method does **not** emit [CrudEvent.Type.READ] events.
 * If READ events are enabled via [activateEvents], they are emitted on the
 * first terminal operation that consumes the sequence.
 *
 * **Note:** [CrudEvent.Type.READ] activation is evaluated at the time [query]
 * is called, not on each terminal operation. Activating or deactivating READ
 * events after calling [query] but before consuming the sequence does not
 * change the behaviour of the returned sequence.
 *
 * **Cross-aggregate queries:** when the predicate contains a `via … anyMatch /
 * allMatch / noneMatch / where` chain, the planner reads `parentProp` and
 * `childRegistry` directly from the captured Via* AST nodes. No `LirpViaAccessor`
 * callback is required for the in-memory path.
 *
 * @param block DSL builder block defining the query predicate, ordering, and pagination
 * @return A lazy [Sequence] of matching entities
 */
@JvmName("queryRegistry")
fun <K, T> Registry<K, T>.query(block: QueryBuilder<T>.() -> Unit): Sequence<T>
    where K : Comparable<K>, T : IdentifiableEntity<K> {
    val built = QueryBuilder<T>().apply(block).build()

    val base = this as? RegistryBase<K, T>
    val planner =
        if (base != null) {
            QueryPlanner(
                isIndexed = { base.isPropertyIndexed(it) },
                indexNameFor = { base.indexNameFor(it) ?: it.name },
                isSortedIndexed = { base.isPropertySortedIndexed(it) },
                sortedBucketFor = { name -> base.sortedBucketFor(name) }
            )
        } else {
            QueryPlanner(isIndexed = { false }, indexNameFor = { it.name })
        }

    val plan = planner.execute(built, this)

    return if (isEventActive(CrudEvent.Type.READ)) {
        plan.results.withReadEvents(this)
    } else {
        plan.results
    }
}

/**
 * Plans a query without executing it, returning a [QueryDiagnostic] describing the planner's
 * chosen strategy and index usage.
 *
 * Analogous to SQL `EXPLAIN`: the DSL block is compiled into a query plan and the planner
 * selects a strategy and extracts index leaves, but the result sequence is **never
 * materialised** — no entity iteration occurs. This makes `explainQuery` cheap to call for
 * diagnostic purposes without paying the execution cost.
 *
 * Example:
 * ```kotlin
 * val diagnostic = repo.explainQuery {
 *     where { category eq "electronics" }
 *     orderBy(price, Direction.DESC)
 * }
 * println(diagnostic.strategy)      // INDEX_ONLY
 * println(diagnostic.indexHits)     // [IndexHit(propertyName=category, ...)]
 * println(diagnostic.executionTimeNs) // null — plan-only, no execution
 * ```
 *
 * The returned [QueryDiagnostic.executionTimeNs] is always `null`. Use [queryWithDiagnostics]
 * when you need both the results and execution timing (SQL `EXPLAIN ANALYZE` equivalent).
 *
 * For cross-aggregate `via` queries the result sequence is still not materialised, but choosing
 * between the per-parent-loop and hash-join strategies samples parent keys and inspects the child
 * registry — so a `via` plan is not strictly zero-cost, unlike a single-aggregate plan.
 *
 * @param block DSL builder block defining the query predicate, ordering, and pagination
 * @return A [QueryDiagnostic] describing strategy, index hits, post-filter count, via-strategy,
 *   and planning duration; `executionTimeNs` is always `null`
 */
@JvmName("explainQueryRegistry")
fun <K, T> Registry<K, T>.explainQuery(block: QueryBuilder<T>.() -> Unit): QueryDiagnostic
    where K : Comparable<K>, T : IdentifiableEntity<K> {
    val t0 = System.nanoTime()
    val built = QueryBuilder<T>().apply(block).build()

    val base = this as? RegistryBase<K, T>
    val planner =
        if (base != null) {
            QueryPlanner(
                isIndexed = { base.isPropertyIndexed(it) },
                indexNameFor = { base.indexNameFor(it) ?: it.name },
                isSortedIndexed = { base.isPropertySortedIndexed(it) },
                sortedBucketFor = { name -> base.sortedBucketFor(name) }
            )
        } else {
            QueryPlanner(isIndexed = { false }, indexNameFor = { it.name })
        }

    val context = planner.execute(built, this)
    val planningTimeNs = System.nanoTime() - t0
    // results sequence is NOT consumed — plan-only guarantee (no terminal operation called)
    return QueryDiagnostic(
        strategy = context.strategy,
        indexHits = context.indexLeaves.map { it.toIndexHit() },
        postFilterPredicateCount = context.postFilterCount,
        viaStrategy = context.viaStrategy,
        planningTimeNs = planningTimeNs,
        executionTimeNs = null
    )
}

/**
 * Executes a query and returns both the results and a [QueryDiagnostic] with full timing.
 *
 * Analogous to SQL `EXPLAIN ANALYZE`: the DSL block is planned, the result sequence is eagerly
 * materialised, and execution time is recorded. The returned [DiagnosedQuery.results] wraps
 * the already-materialised list as a [Sequence] for uniformity with the [query] extension;
 * iterating `results` more than once is safe.
 *
 * Example:
 * ```kotlin
 * val diagnosed = repo.queryWithDiagnostics {
 *     where { category eq "electronics" }
 * }
 * val results = diagnosed.results.toList()
 * val diagnostic = diagnosed.diagnostic
 * println(diagnostic.planningTimeNs)   // nanoseconds spent in strategy selection
 * println(diagnostic.executionTimeNs)  // nanoseconds spent materialising results (non-null)
 * ```
 *
 * Use [explainQuery] when you only need the plan without paying the execution cost.
 *
 * @param block DSL builder block defining the query predicate, ordering, and pagination
 * @return A [DiagnosedQuery] pairing the eagerly-materialised result sequence with a
 *   [QueryDiagnostic] where `executionTimeNs` is always non-null
 */
@JvmName("queryWithDiagnosticsRegistry")
fun <K, T> Registry<K, T>.queryWithDiagnostics(block: QueryBuilder<T>.() -> Unit): DiagnosedQuery<T>
    where K : Comparable<K>, T : IdentifiableEntity<K> {
    val t0 = System.nanoTime()
    val built = QueryBuilder<T>().apply(block).build()

    val base = this as? RegistryBase<K, T>
    val planner =
        if (base != null) {
            QueryPlanner(
                isIndexed = { base.isPropertyIndexed(it) },
                indexNameFor = { base.indexNameFor(it) ?: it.name },
                isSortedIndexed = { base.isPropertySortedIndexed(it) },
                sortedBucketFor = { name -> base.sortedBucketFor(name) }
            )
        } else {
            QueryPlanner(isIndexed = { false }, indexNameFor = { it.name })
        }

    val context = planner.execute(built, this)
    val planningTimeNs = System.nanoTime() - t0
    val t1 = System.nanoTime()
    val results = context.results.toList()
    val executionTimeNs = System.nanoTime() - t1
    val diagnostic =
        QueryDiagnostic(
            strategy = context.strategy,
            indexHits = context.indexLeaves.map { it.toIndexHit() },
            postFilterPredicateCount = context.postFilterCount,
            viaStrategy = context.viaStrategy,
            planningTimeNs = planningTimeNs,
            executionTimeNs = executionTimeNs
        )
    return DiagnosedQuery(results = results.asSequence(), diagnostic = diagnostic)
}

/**
 * Maps an internal [IndexableLeaf] to the public [IndexHit] type.
 *
 * [IndexableLeaf.Single] (from [Predicate.Eq]) maps to [IndexHitType.EXACT] with null selectivity,
 * since the exact candidate count is not computable at plan time without executing the index lookup.
 * [IndexableLeaf.Multi] (from [Predicate.In]) maps to [IndexHitType.MULTI] with selectivity equal
 * to the number of distinct values in the set. [IndexableLeaf.RangeSlice] (from range predicates)
 * maps to [IndexHitType.RANGE] with selectivity equal to the pre-materialised candidate count.
 */
private fun IndexableLeaf.toIndexHit(): IndexHit =
    when (this) {
        is IndexableLeaf.Single -> IndexHit(propertyName, indexName, IndexHitType.EXACT, selectivity = null)
        is IndexableLeaf.Multi -> IndexHit(propertyName, indexName, IndexHitType.MULTI, selectivity = values.size)
        is IndexableLeaf.RangeSlice -> IndexHit(propertyName, indexName, IndexHitType.RANGE, selectivity = candidates.size)
    }

/**
 * Wraps a [Sequence] so that a [Read] event is emitted on the first terminal operation.
 *
 * The sequence is materialised into a list on first iteration so that the complete
 * result set can be included in the event. Subsequent iterations replay the same list.
 */
private fun <K, T> Sequence<T>.withReadEvents(registry: Registry<K, T>): Sequence<T>
    where K : Comparable<K>, T : IdentifiableEntity<K> {
    // Lazy materialises the source sequence exactly once on first iterator() call (thread-safe
    // by default — SYNCHRONIZED) and emits the Read event as part of that initialisation.
    // Subsequent iterator() calls reuse the same list without re-firing the event.
    val materialized =
        lazy {
            this@withReadEvents.toList().also { registry.emitAsync(Read(it)) }
        }
    return Sequence { materialized.value.iterator() }
}