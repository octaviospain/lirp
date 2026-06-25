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
 * Records a single predicate leaf that was resolved through an index during query planning.
 *
 * `propertyName` is the Kotlin property name on the entity (e.g. `"category"`).
 * `indexName` is the storage index name, which may differ when a custom name is set via
 * [@PersistenceMapping][net.transgressoft.lirp.persistence.PersistenceMapping].
 * `type` classifies the kind of index lookup performed for this leaf.
 * `selectivity` quantifies the lookup, with a meaning that depends on [type]: for [IndexHitType.RANGE]
 * it is the number of candidate entities the index slice returned; for [IndexHitType.MULTI] it is the
 * number of distinct values probed (one index lookup per value); for [IndexHitType.EXACT] it is `null`,
 * since the candidate count is not computed at plan time.
 *
 * @see QueryDiagnostic
 * @see IndexHitType
 */
data class IndexHit(
    val propertyName: String,
    val indexName: String,
    val type: IndexHitType,
    val selectivity: Int?
)