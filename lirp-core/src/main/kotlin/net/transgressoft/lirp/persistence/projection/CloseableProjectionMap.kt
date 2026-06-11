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

package net.transgressoft.lirp.persistence.projection

/**
 * A read-only [Map] projection whose underlying registry subscription can be released via [close].
 *
 * Returned by the registry-source value-transform projection factories so callers can stop a
 * transformed projection when it is no longer needed, mirroring the lifecycle of the untransformed
 * registry projections (which are themselves [AutoCloseable]). Closing is idempotent and safe to
 * call before first access. After closing, the projection no longer receives registry updates.
 *
 * Usable with `use { ... }` for scoped lifetimes:
 * ```kotlin
 * registryProjectionMap(trackRepo) { it.albumName } { pk, items -> AlbumSummary(pk, items.size) }
 *     .use { summaryByAlbum -> /* read summaryByAlbum */ }
 * ```
 *
 * @param PK the projection key type
 * @param V the transformed value type
 */
interface CloseableProjectionMap<PK, V> : Map<PK, V>, AutoCloseable