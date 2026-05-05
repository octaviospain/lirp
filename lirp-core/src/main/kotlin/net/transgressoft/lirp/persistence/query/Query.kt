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
 * Sort direction for query ordering.
 */
enum class Direction {
    /** Ascending order (A → Z, smallest → largest). */
    ASC,

    /** Descending order (Z → A, largest → smallest). */
    DESC
}

/**
 * A single order clause specifying a property and direction.
 *
 * @param T the entity type
 * @param prop the property to order by
 * @param direction the sort direction
 */
data class OrderClause<T : IdentifiableEntity<*>>(
    val prop: KProperty1<T, Comparable<Any>>,
    val direction: Direction
)

/**
 * Immutable representation of a typed query with optional predicate, ordering, and pagination.
 *
 * @param T the entity type
 * @param predicate the filter predicate, or `null` for no filtering
 * @param orderBy the list of order clauses (applied in order)
 * @param limit maximum number of results to return, or `null` for unlimited
 * @param offset number of results to skip before returning
 */
data class Query<T : IdentifiableEntity<*>>(
    val predicate: Predicate<T>?,
    val orderBy: List<OrderClause<T>>,
    val limit: Int?,
    val offset: Int
) {
    init {
        require(offset >= 0) { "offset must be >= 0" }
        require(limit == null || limit >= 0) { "limit must be >= 0" }
    }
}