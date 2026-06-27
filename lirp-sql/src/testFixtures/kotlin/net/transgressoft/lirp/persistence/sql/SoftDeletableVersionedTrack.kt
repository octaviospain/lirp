/******************************************************************************
 *     Copyright (C) 2026  Octavio Calleya Garcia                             *
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

package net.transgressoft.lirp.persistence.sql

import net.transgressoft.lirp.entity.MutableSoftDeletable
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.PersistenceMapping
import net.transgressoft.lirp.persistence.Version
import java.time.Instant

/**
 * Music-domain SQL fixture combining `@Version` optimistic locking with [MutableSoftDeletable].
 *
 * Used by [SoftDeleteSqlTest] and [SoftDeleteSqlIntegrationTest] to verify that:
 * - soft-delete flushes an `UPDATE … SET deleted_at = ?` on the versioned-write path,
 * - the `@Version` column is bumped on every soft-delete and restore UPDATE,
 * - SQL load is unfiltered (all rows including `deleted_at IS NOT NULL` enter memory),
 * - restore flushes `deleted_at = NULL` and bumps the version again.
 *
 * The KSP processor generates `SoftDeletableVersionedTrack_LirpTableDef` automatically,
 * including the `deleted_at` nullable TEXT column (via [net.transgressoft.lirp.persistence.InstantColumnConverter])
 * and `VersionedTableDef.bumpVersion` wiring for the `version` column.
 */
@PersistenceMapping(name = "soft_deletable_versioned_tracks")
class SoftDeletableVersionedTrack(override val id: Int) :
    ReactiveEntityBase<Int, SoftDeletableVersionedTrack>(),
    MutableSoftDeletable {
    var title: String by reactiveProperty("")
    var artist: String by reactiveProperty("")

    @Version
    var version: Long by reactiveProperty(0L)

    override var deletedAt: Instant? by reactiveProperty(null)

    override val uniqueId: String get() = "soft-deletable-versioned-track-$id"

    override fun clone(): SoftDeletableVersionedTrack =
        SoftDeletableVersionedTrack(id).also { copy ->
            copy.withEventsDisabled {
                copy.title = title
                copy.artist = artist
                copy.version = version
                copy.deletedAt = deletedAt
            }
        }
}