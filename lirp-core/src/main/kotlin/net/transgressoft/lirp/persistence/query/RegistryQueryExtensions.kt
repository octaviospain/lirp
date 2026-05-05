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
                indexNameFor = { base.indexNameFor(it) ?: it.name }
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
 * Wraps a [Sequence] so that a [Read] event is emitted on the first terminal operation.
 *
 * The sequence is materialised into a list on first iteration so that the complete
 * result set can be included in the event. Subsequent iterations replay the same list.
 */
private fun <K, T> Sequence<T>.withReadEvents(registry: Registry<K, T>): Sequence<T>
    where K : Comparable<K>, T : IdentifiableEntity<K> {
    val lock = Any()
    var cached: List<T>? = null
    var emitted = false

    return object : Sequence<T> {
        override fun iterator(): Iterator<T> {
            val list =
                synchronized(lock) {
                    val snapshot = cached ?: this@withReadEvents.toList().also { cached = it }
                    if (!emitted) {
                        registry.emitAsync(Read(snapshot))
                        emitted = true
                    }
                    snapshot
                }
            return list.iterator()
        }
    }
}