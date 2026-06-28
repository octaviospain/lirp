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
 * Immutable representation of a typed query with optional predicate, ordering, pagination,
 * and soft-delete visibility flags.
 *
 * By default (both flags `false`) queries exclude soft-deleted entities (fail-closed).
 * Set [includeDeleted] to include them alongside active entities, or [onlyDeleted] to
 * return only soft-deleted entities. The two flags are mutually exclusive.
 *
 * @param T the entity type
 * @param predicate the filter predicate, or `null` for no filtering
 * @param orderBy the list of order clauses (applied in order)
 * @param limit maximum number of results to return, or `null` for unlimited
 * @param offset number of results to skip before returning
 * @param includeDeleted whether to include soft-deleted entities alongside active ones
 * @param onlyDeleted whether to return only soft-deleted entities
 */
data class Query<T : IdentifiableEntity<*>>(
    val predicate: Predicate<T>?,
    val orderBy: List<OrderClause<T>>,
    val limit: Int?,
    val offset: Int,
    val includeDeleted: Boolean = false,
    val onlyDeleted: Boolean = false
) {
    init {
        require(offset >= 0) { "offset must be >= 0" }
        require(limit == null || limit >= 0) { "limit must be >= 0" }
        require(!(includeDeleted && onlyDeleted)) {
            "includeDeleted and onlyDeleted are mutually exclusive"
        }
    }
}

/**
 * Soft-delete visibility mode for a query's read path, derived from a [Query]'s
 * [Query.includeDeleted] / [Query.onlyDeleted] flags.
 *
 * Used to thread visibility through read-path components that need only the mode and not the
 * full generic [Query] (e.g. the cross-aggregate via executor), avoiding a `Query<*>`
 * element-type coupling at those boundaries.
 */
internal enum class Visibility {
    /** Active entities only — the fail-closed default. */
    ACTIVE_ONLY,

    /** Active and soft-deleted entities. */
    INCLUDE_DELETED,

    /** Soft-deleted entities only (strict mirror). */
    ONLY_DELETED
}

/** The [Visibility] mode implied by this query's mutually-exclusive soft-delete flags. */
internal fun Query<*>.visibility(): Visibility =
    when {
        onlyDeleted -> Visibility.ONLY_DELETED
        includeDeleted -> Visibility.INCLUDE_DELETED
        else -> Visibility.ACTIVE_ONLY
    }

/** A predicate-free, ordering-free, active-only query — the canonical default for optional `query` parameters. */
internal fun <T : IdentifiableEntity<*>> activeOnlyQuery(): Query<T> = Query(null, emptyList(), null, 0)