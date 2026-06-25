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

/**
 * Diagnostic snapshot produced by the `explainQuery` and `queryWithDiagnostics` extension
 * functions in the `lirp-core` module.
 *
 * `strategy` is the planner's chosen retrieval mode for the query. `indexHits` lists every
 * predicate leaf that was pushed to an index; an empty list means no index was used.
 * `postFilterPredicateCount` is the number of predicate leaves that were NOT resolved by any
 * index and require a post-filter pass over the candidate set; zero for [Strategy.INDEX_ONLY]
 * queries. `viaStrategy` is non-null when the query contained a cross-aggregate `via` arm, and
 * `null` for single-aggregate queries. `planningTimeNs` is the nanosecond duration of strategy
 * selection and index-leaf extraction, measured via [System.nanoTime]. `executionTimeNs` is
 * non-null only when the query was executed via `queryWithDiagnostics`; `null` when produced
 * by `explainQuery` (plan-only, no entity iteration).
 *
 * @see Strategy
 * @see ViaStrategy
 * @see IndexHit
 */
data class QueryDiagnostic(
    val strategy: Strategy,
    val indexHits: List<IndexHit>,
    val postFilterPredicateCount: Int,
    val viaStrategy: ViaStrategy?,
    val planningTimeNs: Long,
    val executionTimeNs: Long?
)