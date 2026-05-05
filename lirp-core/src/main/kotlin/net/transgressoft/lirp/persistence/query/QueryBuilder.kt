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
import kotlin.reflect.KProperty1

/**
 * Builder for constructing [Query] instances via the Kotlin DSL.
 *
 * Example:
 * ```kotlin
 * val query = query<Product> {
 *     where { category eq "electronics" }
 *     orderBy(price, Direction.DESC)
 *     limit(10)
 * }
 * ```
 *
 * @param T the entity type being queried
 */
class QueryBuilder<T : IdentifiableEntity<*>> {

    /** The current filter predicate, or `null` if [where] has not been called. */
    var predicate: Predicate<T>? = null
        private set

    private val _orders = mutableListOf<OrderClause<T>>()

    /** The list of order clauses accumulated so far. */
    val orders: List<OrderClause<T>> get() = _orders

    /** The maximum number of results to return, or `null` for unlimited. */
    var limit: Int? = null
        private set

    /** The number of results to skip before returning. */
    var offset: Int = 0
        private set

    /**
     * Sets the filter predicate for this query.
     *
     * Calling this method more than once throws [IllegalStateException];
     * compose predicates with `and` / `or` instead.
     *
     * Example:
     * ```kotlin
     * where { category eq "electronics" }
     * ```
     *
     * @param block a lambda returning a [Predicate]
     * @throws IllegalStateException if a predicate has already been set
     */
    fun where(block: () -> Predicate<T>) {
        check(predicate == null) { "where() can only be called once per query" }
        predicate = block()
    }

    /**
     * Adds an order clause to sort results by the given property.
     *
     * Example:
     * ```kotlin
     * orderBy(price, Direction.DESC)
     * ```
     *
     * @param prop the property to order by
     * @param direction the sort direction (default [Direction.ASC])
     */
    @Suppress("UNCHECKED_CAST")
    fun <V : Comparable<V>> orderBy(prop: KProperty1<T, V>, direction: Direction = Direction.ASC) {
        _orders.add(OrderClause(prop as KProperty1<T, Comparable<Any>>, direction))
    }

    /**
     * Sets the maximum number of results to return.
     *
     * @param n the limit
     */
    fun limit(n: Int) {
        require(n >= 0) { "limit must be >= 0" }
        this.limit = n
    }

    /**
     * Sets the number of results to skip before returning.
     *
     * @param n the offset
     */
    fun offset(n: Int) {
        require(n >= 0) { "offset must be >= 0" }
        this.offset = n
    }

    /**
     * Builds the immutable [Query] from the current builder state.
     *
     * @return the configured [Query]
     */
    fun build(): Query<T> = Query(predicate, _orders.toList(), limit, offset)
}