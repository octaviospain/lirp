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
 * The retrieval mode chosen by the query planner for a given query against a [net.transgressoft.lirp.persistence.Registry].
 *
 * The planner inspects predicate leaves and available index metadata to select the most
 * efficient strategy before execution. The chosen strategy is exposed via [QueryDiagnostic.strategy]
 * so callers can understand why a query performed as it did.
 *
 * @see QueryDiagnostic
 */
enum class Strategy {

    /**
     * Every predicate leaf is fully resolved by an index — exact equality, membership, or a
     * sorted-range slice — so results come directly from the index with no post-filtering.
     */
    INDEX_ONLY,

    /** Some leaves are index-resolved, but others require a post-filter pass over the candidates. */
    INDEX_THEN_FILTER,

    /** No leaf can be resolved through an index; a full in-memory scan is required. */
    SCAN_ONLY
}