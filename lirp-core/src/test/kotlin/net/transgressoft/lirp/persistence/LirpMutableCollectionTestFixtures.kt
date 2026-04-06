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
 * The [items] property delegates to a mutable aggregate list backed by [itemIds]. The [idSetter]
 * writes mutated IDs back to [itemIds] after each add/remove/clear, keeping the serializable field
 * in sync. The [clone] method deep-copies [itemIds] to satisfy the `mutateAndPublish` equality check.
 */
@Serializable
data class MutablePlaylist(
    override val id: Long,
    val name: String,
    var itemIds: List<Int> = emptyList()
) : ReactiveEntityBase<Long, MutablePlaylist>() {
    override val uniqueId: String get() = "mutable-playlist-$id"

    @Aggregate(onDelete = CascadeAction.NONE)
    val items by mutableAggregateList<Int, TestTrack>(
        idProvider = { itemIds },
        idSetter = { itemIds = it }
    )

    override fun clone(): MutablePlaylist = copy(itemIds = ArrayList(itemIds))
}

/**
 * Test entity representing a group that supports runtime mutation of its playlist set
 * via a [mutableAggregateSet] delegate.
 *
 * The [playlists] property delegates to a mutable aggregate set backed by [playlistIds]. Uniqueness
 * is enforced by the set delegate. The [clone] method deep-copies [playlistIds] to a new
 * [LinkedHashSet] to preserve insertion order and satisfy the `mutateAndPublish` equality check.
 */
@Serializable
data class MutablePlaylistGroup(
    override val id: Long,
    var playlistIds: Set<Long> = emptySet()
) : ReactiveEntityBase<Long, MutablePlaylistGroup>() {
    override val uniqueId: String get() = "mutable-group-$id"

    @Aggregate(onDelete = CascadeAction.NONE)
    val playlists by mutableAggregateSet<Long, MutablePlaylist>(
        idProvider = { playlistIds },
        idSetter = { playlistIds = it }
    )

    override fun clone(): MutablePlaylistGroup = copy(playlistIds = LinkedHashSet(playlistIds))
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