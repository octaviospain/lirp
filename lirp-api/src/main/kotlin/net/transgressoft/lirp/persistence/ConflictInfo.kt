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

package net.transgressoft.lirp.persistence

import net.transgressoft.lirp.entity.ReactiveEntity

/**
 * Describes a single `@Version` conflict detected during a transaction block commit.
 *
 * [entity] is the pre-block snapshot of the in-memory entity — captured before rollback so
 * the caller can read the values that were attempted. [canonical] is the authoritative database
 * state at conflict time; `null` when the row was concurrently deleted. [version] is the
 * actual database version at conflict time; `-1` when the row was deleted, `null` for
 * non-`@Version` entities.
 *
 * Naming follows the vocabulary established by [net.transgressoft.lirp.event.StandardCrudEvent.Conflict].
 *
 * @param K the entity key type.
 * @param R the entity type.
 * @param entity the pre-block snapshot of the in-memory entity with the values that were attempted.
 * @param canonical the authoritative database state at conflict time; `null` when the row was concurrently deleted.
 * @param version the actual database version at conflict time; `-1` when the row was deleted, `null` for non-`@Version` entities.
 */
data class ConflictInfo<K : Comparable<K>, R : ReactiveEntity<K, R>>(
    val entity: R,
    val canonical: R?,
    val version: Long?
)