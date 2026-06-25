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
 * Execution strategy for cross-aggregate `via … anyMatch/allMatch/noneMatch/where` predicates.
 *
 * Selected per-query based on the cardinality estimate
 * `|children matching predicate| < |parents| × avg-refs-per-parent`. Never cached
 * across queries; re-estimated on every execution. The chosen strategy is exposed via
 * [QueryDiagnostic.viaStrategy] whenever a cross-aggregate query is planned or executed.
 *
 * @see QueryDiagnostic
 */
enum class ViaStrategy {
    /**
     * Iterates parents lazily; for each parent, reads the live `parentProp` collection and
     * applies the via predicate directly.
     */
    PER_PARENT_LOOP,

    /**
     * Pre-filters the child registry by the child predicate, materialises matching ids into
     * a [HashSet], then per-parent tests `parentProp` reads (live) against that set with the
     * quantifier appropriate to the via operator.
     */
    HASH_JOIN
}