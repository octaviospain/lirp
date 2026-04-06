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
import kotlinx.serialization.Serializable

// --- Collection reference test entities and repositories ---
// Extracted from LirpTestFixtures.kt for organizational clarity.
// Entities use aggregateList/aggregateSet delegates for list- and set-based aggregate references.

/**
 * Minimal test entity representing a track in a playlist.
 *
 * Used as the referenced entity type in [Playlist] collection reference tests.
 */
@Serializable
data class TestTrack(
    override val id: Int,
    val title: String
) : ReactiveEntityBase<Int, TestTrack>() {
    override val uniqueId: String get() = "track-$id"

    override fun clone(): TestTrack = copy()
}

/**
 * Test entity representing a playlist with an ordered list of track references.
 *
 * The [items] property demonstrates a collection aggregate reference using [aggregateList]:
 * the accessor for collection entries is provided by [Playlist_LirpRefAccessor].
 */
@Serializable
data class Playlist(
    override val id: Long,
    val name: String,
    val itemIds: List<Int>
) : ReactiveEntityBase<Long, Playlist>() {
    override val uniqueId: String get() = "playlist-$id"

    @Aggregate
    val items by aggregateList<Int, TestTrack>(itemIds)

    override fun clone(): Playlist = copy()
}

/**
 * Test entity representing a group of playlists using a set of playlist references.
 *
 * The [playlists] property demonstrates a collection aggregate reference using [aggregateSet]:
 * the accessor for collection entries is provided by [PlaylistGroup_LirpRefAccessor].
 */
@Serializable
data class PlaylistGroup(
    override val id: Long,
    val playlistIds: Set<Long>
) : ReactiveEntityBase<Long, PlaylistGroup>() {
    override val uniqueId: String get() = "playlist-group-$id"

    @Aggregate
    val playlists by aggregateSet<Long, Playlist>(playlistIds)

    override fun clone(): PlaylistGroup = copy()
}

/**
 * Test entity with CASCADE delete action on a collection: removing this entity from its repository
 * also removes all referenced [TestTrack] entities.
 */
@Serializable
data class CascadePlaylist(
    override val id: Long,
    val name: String,
    val itemIds: List<Int>
) : ReactiveEntityBase<Long, CascadePlaylist>() {
    override val uniqueId: String get() = "cascade-playlist-$id"

    @Aggregate(onDelete = CascadeAction.CASCADE)
    val items by aggregateList<Int, TestTrack>(itemIds)

    override fun clone(): CascadePlaylist = copy()
}

/**
 * Test entity with RESTRICT delete action on a collection: removing this entity is blocked
 * if any of the referenced [TestTrack] entities are still referenced by other entities.
 */
@Serializable
data class RestrictPlaylist(
    override val id: Long,
    val name: String,
    val itemIds: List<Int>
) : ReactiveEntityBase<Long, RestrictPlaylist>() {
    override val uniqueId: String get() = "restrict-playlist-$id"

    @Aggregate(onDelete = CascadeAction.RESTRICT)
    val items by aggregateList<Int, TestTrack>(itemIds)

    override fun clone(): RestrictPlaylist = copy()
}

/**
 * Test entity with DETACH delete action on a collection: removing this entity does nothing
 * to the referenced [TestTrack] entities (no bubble-up to cancel, just a no-op).
 */
@Serializable
data class DetachPlaylist(
    override val id: Long,
    val name: String,
    val itemIds: List<Int>
) : ReactiveEntityBase<Long, DetachPlaylist>() {
    override val uniqueId: String get() = "detach-playlist-$id"

    @Aggregate(onDelete = CascadeAction.DETACH)
    val items by aggregateList<Int, TestTrack>(itemIds)

    override fun clone(): DetachPlaylist = copy()
}

/**
 * Test entity with NONE delete action on a collection (default): removing this entity does nothing
 * to the referenced [TestTrack] entities.
 */
@Serializable
data class NonePlaylist(
    override val id: Long,
    val name: String,
    val itemIds: List<Int>
) : ReactiveEntityBase<Long, NonePlaylist>() {
    override val uniqueId: String get() = "none-playlist-$id"

    @Aggregate(onDelete = CascadeAction.NONE)
    val items by aggregateList<Int, TestTrack>(itemIds)

    override fun clone(): NonePlaylist = copy()
}

/**
 * Test repository for [TestTrack] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class TestTrackVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Int, TestTrack>(context, "TestTracks") {
    constructor() : this(LirpContext.default)

    fun create(id: Int, title: String): TestTrack = TestTrack(id, title).also { add(it) }
}

/**
 * Test repository for [Playlist] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class PlaylistVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, Playlist>(context, "Playlists") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, name: String, itemIds: List<Int>): Playlist = Playlist(id, name, itemIds).also { add(it) }
}

/**
 * Test repository for [PlaylistGroup] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class PlaylistGroupVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, PlaylistGroup>(context, "PlaylistGroups") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, playlistIds: Set<Long>): PlaylistGroup = PlaylistGroup(id, playlistIds).also { add(it) }
}

/**
 * Test repository for [CascadePlaylist] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class CascadePlaylistVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, CascadePlaylist>(context, "CascadePlaylists") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, name: String, itemIds: List<Int>): CascadePlaylist = CascadePlaylist(id, name, itemIds).also { add(it) }
}

/**
 * Test repository for [RestrictPlaylist] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class RestrictPlaylistVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, RestrictPlaylist>(context, "RestrictPlaylists") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, name: String, itemIds: List<Int>): RestrictPlaylist = RestrictPlaylist(id, name, itemIds).also { add(it) }
}

/**
 * Test repository for [DetachPlaylist] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class DetachPlaylistVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, DetachPlaylist>(context, "DetachPlaylists") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, name: String, itemIds: List<Int>): DetachPlaylist = DetachPlaylist(id, name, itemIds).also { add(it) }
}

/**
 * Test repository for [NonePlaylist] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class NonePlaylistVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, NonePlaylist>(context, "NonePlaylists") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, name: String, itemIds: List<Int>): NonePlaylist = NonePlaylist(id, name, itemIds).also { add(it) }
}

// --- Set-based cascade entities ---

/**
 * Test entity with CASCADE delete action on a set reference: removing this entity also
 * removes all referenced [Playlist] entities.
 */
@Serializable
data class CascadePlaylistGroup(
    override val id: Long,
    val playlistIds: Set<Long>
) : ReactiveEntityBase<Long, CascadePlaylistGroup>() {
    override val uniqueId: String get() = "cascade-group-$id"

    @Aggregate(onDelete = CascadeAction.CASCADE)
    val playlists by aggregateSet<Long, Playlist>(playlistIds)

    override fun clone(): CascadePlaylistGroup = copy()
}

/**
 * Test entity with RESTRICT delete action on a set reference: removing this entity is blocked
 * if any referenced [Playlist] is still referenced by other entities.
 */
@Serializable
data class RestrictPlaylistGroup(
    override val id: Long,
    val playlistIds: Set<Long>
) : ReactiveEntityBase<Long, RestrictPlaylistGroup>() {
    override val uniqueId: String get() = "restrict-group-$id"

    @Aggregate(onDelete = CascadeAction.RESTRICT)
    val playlists by aggregateSet<Long, Playlist>(playlistIds)

    override fun clone(): RestrictPlaylistGroup = copy()
}

/**
 * Test entity with DETACH delete action on a set reference.
 */
@Serializable
data class DetachPlaylistGroup(
    override val id: Long,
    val playlistIds: Set<Long>
) : ReactiveEntityBase<Long, DetachPlaylistGroup>() {
    override val uniqueId: String get() = "detach-group-$id"

    @Aggregate(onDelete = CascadeAction.DETACH)
    val playlists by aggregateSet<Long, Playlist>(playlistIds)

    override fun clone(): DetachPlaylistGroup = copy()
}

/**
 * Test entity with NONE delete action on a set reference (default).
 */
@Serializable
data class NonePlaylistGroup(
    override val id: Long,
    val playlistIds: Set<Long>
) : ReactiveEntityBase<Long, NonePlaylistGroup>() {
    override val uniqueId: String get() = "none-group-$id"

    @Aggregate(onDelete = CascadeAction.NONE)
    val playlists by aggregateSet<Long, Playlist>(playlistIds)

    override fun clone(): NonePlaylistGroup = copy()
}

/**
 * Test repository for [CascadePlaylistGroup] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class CascadePlaylistGroupVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, CascadePlaylistGroup>(context, "CascadePlaylistGroups") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, playlistIds: Set<Long>): CascadePlaylistGroup = CascadePlaylistGroup(id, playlistIds).also { add(it) }
}

/**
 * Test repository for [RestrictPlaylistGroup] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class RestrictPlaylistGroupVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, RestrictPlaylistGroup>(context, "RestrictPlaylistGroups") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, playlistIds: Set<Long>): RestrictPlaylistGroup = RestrictPlaylistGroup(id, playlistIds).also { add(it) }
}

/**
 * Test repository for [DetachPlaylistGroup] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class DetachPlaylistGroupVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, DetachPlaylistGroup>(context, "DetachPlaylistGroups") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, playlistIds: Set<Long>): DetachPlaylistGroup = DetachPlaylistGroup(id, playlistIds).also { add(it) }
}

/**
 * Test repository for [NonePlaylistGroup] entities, auto-registered via [@LirpRepository][LirpRepository].
 */
@LirpRepository
class NonePlaylistGroupVolatileRepo internal constructor(
    context: LirpContext
) : VolatileRepository<Long, NonePlaylistGroup>(context, "NonePlaylistGroups") {
    constructor() : this(LirpContext.default)

    fun create(id: Long, playlistIds: Set<Long>): NonePlaylistGroup = NonePlaylistGroup(id, playlistIds).also { add(it) }
}