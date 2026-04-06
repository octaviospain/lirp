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

import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.json.JsonFileRepository
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

// --- Mutable collection delegate test entities and repositories ---
// Provides test fixtures for MutableAggregateListRefDelegate and MutableAggregateSetRefDelegate.
// Reuses TestTrack and TestTrackVolatileRepo from LirpCollectionTestFixtures.kt.

/**
 * Test entity representing a playlist that supports runtime mutation of its track list
 * via a [mutableAggregateList] delegate.
 *
 * The [items] property delegates to a mutable aggregate list backed by the delegate's backing store,
 * initialized from [itemIds] at construction time. The [clone] method snapshots the delegate's current
 * IDs to satisfy the `mutateAndPublish` equality check. Equality is based on the delegate's live
 * [referenceIds] so that add/remove/clear mutations are correctly detected as state changes.
 */
@Serializable
data class MutablePlaylist(
    override val id: Long,
    val name: String,
    val itemIds: List<Int> = emptyList()
) : ReactiveEntityBase<Long, MutablePlaylist>() {
    override val uniqueId: String get() = "mutable-playlist-$id"

    @Aggregate(onDelete = CascadeAction.NONE)
    val items by mutableAggregateList<Int, TestTrack>(itemIds)

    override fun clone(): MutablePlaylist = copy(itemIds = items.referenceIds.toList())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MutablePlaylist) return false
        return id == other.id && name == other.name && items.referenceIds == other.items.referenceIds
    }

    override fun hashCode(): Int = 31 * (31 * id.hashCode() + name.hashCode()) + items.referenceIds.hashCode()
}

/**
 * Test entity representing a group that supports runtime mutation of its playlist set
 * via a [mutableAggregateSet] delegate.
 *
 * The [playlists] property delegates to a mutable aggregate set backed by the delegate's backing store,
 * initialized from [playlistIds] at construction time. Equality is based on the delegate's live
 * [referenceIds] so that add/remove/clear mutations are correctly detected as state changes.
 */
@Serializable
data class MutablePlaylistGroup(
    override val id: Long,
    val playlistIds: Set<Long> = emptySet()
) : ReactiveEntityBase<Long, MutablePlaylistGroup>() {
    override val uniqueId: String get() = "mutable-group-$id"

    @Aggregate(onDelete = CascadeAction.NONE)
    val playlists by mutableAggregateSet<Long, MutablePlaylist>(playlistIds)

    override fun clone(): MutablePlaylistGroup = copy(playlistIds = LinkedHashSet(playlists.referenceIds))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MutablePlaylistGroup) return false
        return id == other.id && playlists.referenceIds == other.playlists.referenceIds
    }

    override fun hashCode(): Int = 31 * id.hashCode() + playlists.referenceIds.hashCode()
}

/**
 * Test repository for [MutablePlaylist] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class MutablePlaylistVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, MutablePlaylist>(context, "MutablePlaylists") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, name: String, itemIds: List<Int> = emptyList()): MutablePlaylist =
        MutablePlaylist(id, name, itemIds).also { add(it) }
}

/**
 * Test repository for [MutablePlaylistGroup] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class MutablePlaylistGroupVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, MutablePlaylistGroup>(context, "MutablePlaylistGroups") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, playlistIds: Set<Long> = emptySet()): MutablePlaylistGroup =
        MutablePlaylistGroup(id, playlistIds).also { add(it) }
}

/**
 * Test JSON repository for [MutablePlaylist] entities, using kotlinx-serialization for round-trip
 * persistence of the [MutablePlaylist.itemIds] field that backs the mutable aggregate delegate.
 */
class MutablePlaylistJsonFileRepository internal constructor(
    context: LirpContext,
    file: File,
    serializationDelayMs: Long = 50L,
    loadOnInit: Boolean = true
) : JsonFileRepository<Long, MutablePlaylist>(
        context,
        file,
        MapSerializer(Long.serializer(), MutablePlaylist.serializer()),
        serializationDelay = serializationDelayMs.milliseconds,
        loadOnInit = loadOnInit
    ) {
    constructor(file: File, serializationDelayMs: Long = 50L, loadOnInit: Boolean = true) :
        this(LirpContext.default, file, serializationDelayMs, loadOnInit)

    fun create(id: Long, name: String, itemIds: List<Int> = emptyList()): MutablePlaylist =
        MutablePlaylist(id, name, itemIds).also { add(it) }
}