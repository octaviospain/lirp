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
 * Result of `queryWithDiagnostics`, pairing the eager result sequence with a [QueryDiagnostic].
 *
 * `results` wraps the already-materialised entity list as a [Sequence] so call sites are
 * uniform with the existing `query` extension. Iterating `results` more than once is safe —
 * the underlying list is retained.
 *
 * @param T the entity type
 * @see QueryDiagnostic
 */
data class DiagnosedQuery<T>(
    val results: Sequence<T>,
    val diagnostic: QueryDiagnostic
)