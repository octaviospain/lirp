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
import net.transgressoft.lirp.entity.IdentifiableEntity
import net.transgressoft.lirp.entity.ReactiveEntity
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.LirpEventSubscriber
import net.transgressoft.lirp.event.LirpEventSubscriberBase
import net.transgressoft.lirp.persistence.json.JsonFileRepository
import java.io.File
import java.util.concurrent.Flow
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.KSerializer

// =====================================================================
// Music-commons-inspired integration test fixture entities, repositories,
// and supporting types. Mirrors the polymorphic interface hierarchy,
// abstract base classes, and delegation patterns from music-commons.
// =====================================================================

// ---------------------------------------------------------------------------
// Audio Item hierarchy
// ---------------------------------------------------------------------------

/**
 * Reactive audio item with a mutable [title] property. Mirrors
 * `music-commons:ReactiveAudioItem` with its self-referencing type parameter.
 */
interface ReactiveAudioItem<I : ReactiveAudioItem<I>> : ReactiveEntity<Int, I>, Comparable<I> {
    var title: String
}

/**
 * Marker interface binding the self-type to a concrete audio item.
 * Mirrors `music-commons:AudioItem`.
 */
interface AudioItem : ReactiveAudioItem<AudioItem> {
    override fun clone(): AudioItem
}

/**
 * Concrete mutable audio item entity backed by [reactiveProperty] for [title].
 *
 * Not declared `internal` so it is accessible from the lirp-sql testFixtures source set.
 */
class MutableAudioItem(override val id: Int) : ReactiveEntityBase<Int, AudioItem>(), AudioItem {
    override val uniqueId: String get() = "audio-item-$id"

    override var title: String by reactiveProperty("")

    override fun compareTo(other: AudioItem): Int = id.compareTo(other.id)

    override fun clone(): MutableAudioItem =
        MutableAudioItem(id).also { it.withEventsDisabledForClone { it.title = title } }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MutableAudioItem) return false
        return id == other.id && title == other.title
    }

    override fun hashCode(): Int = 31 * id.hashCode() + title.hashCode()

    override fun toString(): String = "MutableAudioItem(id=$id, title='$title')"
}

// ---------------------------------------------------------------------------
// Playlist hierarchy
// ---------------------------------------------------------------------------

/**
 * Read-only playlist contract with a name and contained items.
 * Mirrors `music-commons:AudioPlaylist`.
 */
interface AudioPlaylist<I : ReactiveAudioItem<I>> : IdentifiableEntity<Int>, Comparable<AudioPlaylist<I>> {
    val name: String
}

/**
 * Reactive playlist adding mutability to [name] and entity lifecycle.
 * Mirrors `music-commons:ReactiveAudioPlaylist`.
 */
interface ReactiveAudioPlaylist<I : ReactiveAudioItem<I>, P : ReactiveAudioPlaylist<I, P>> :
    AudioPlaylist<I>, ReactiveEntity<Int, P> {
    override var name: String
    val audioItems: MutableAggregateCollectionRef<Int, I>
    val playlists: MutableAggregateCollectionRef<Int, P>
}

/**
 * Marker interface binding the playlist self-type and audio item type.
 * Mirrors `music-commons:MutableAudioPlaylist`.
 */
interface MutableAudioPlaylist : ReactiveAudioPlaylist<AudioItem, MutableAudioPlaylist>

/**
 * Abstract base for mutable playlists with aggregate delegates for audio items and sub-playlists.
 * Mirrors `music-commons:MutablePlaylistBase`.
 *
 * Not declared `internal` so it is accessible from the lirp-sql testFixtures source set.
 */
abstract class MutablePlaylistBase<I : ReactiveAudioItem<I>, P : ReactiveAudioPlaylist<I, P>>(
    override val id: Int
) : ReactiveEntityBase<Int, P>(), ReactiveAudioPlaylist<I, P> {

    override val uniqueId: String get() = "audio-playlist-$id"

    override var name: String by reactiveProperty("")

    abstract override val audioItems: MutableAggregateCollectionRef<Int, I>

    abstract override val playlists: MutableAggregateCollectionRef<Int, P>

    override fun compareTo(other: AudioPlaylist<I>): Int = id.compareTo(other.id)
}

/**
 * Concrete mutable playlist with audio items and self-referencing sub-playlists.
 * Mirrors `music-commons:MutablePlaylist`.
 *
 * Not declared `internal` so it is accessible from the lirp-sql testFixtures source set.
 */
class MutableAudioPlaylistEntity(
    id: Int,
    initialAudioItemIds: List<Int> = emptyList(),
    initialPlaylistIds: Set<Int> = emptySet()
) : MutablePlaylistBase<AudioItem, MutableAudioPlaylist>(id),
    MutableAudioPlaylist {

    @Aggregate(onDelete = CascadeAction.DETACH)
    override val audioItems by mutableAggregateList<Int, AudioItem>(initialAudioItemIds)

    @Aggregate(onDelete = CascadeAction.DETACH)
    override val playlists by mutableAggregateSet<Int, MutableAudioPlaylist>(initialPlaylistIds)

    override fun clone(): MutableAudioPlaylistEntity =
        MutableAudioPlaylistEntity(id, audioItems.referenceIds.toList(), LinkedHashSet(playlists.referenceIds))
            .also { it.withEventsDisabledForClone { it.name = name } }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MutableAudioPlaylistEntity) return false
        return id == other.id &&
            name == other.name &&
            audioItems.referenceIds == other.audioItems.referenceIds &&
            playlists.referenceIds == other.playlists.referenceIds
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + audioItems.referenceIds.hashCode()
        result = 31 * result + playlists.referenceIds.hashCode()
        return result
    }

    override fun toString(): String = "MutableAudioPlaylistEntity(id=$id, name='$name')"
}

// ---------------------------------------------------------------------------
// Artist Catalog — minimal stubs to satisfy AudioLibrary type parameters
// ---------------------------------------------------------------------------

/** Minimal stub mirroring `music-commons:ReactiveArtistCatalog`. */
interface ReactiveArtistCatalog<AC : ReactiveArtistCatalog<AC, I>, I : ReactiveAudioItem<I>>

/** Minimal stub mirroring `music-commons:ArtistCatalog`. */
interface ArtistCatalog<I : ReactiveAudioItem<I>> :
    ReactiveArtistCatalog<ArtistCatalog<I>, I>,
    Comparable<ArtistCatalog<I>>

/** Minimal stub mirroring `music-commons:ArtistCatalogRegistryBase`. */
open class ArtistCatalogRegistryBase<I, AC>
    where I : ReactiveAudioItem<I>,
          AC : ReactiveArtistCatalog<AC, I>

/** Minimal stub mirroring `music-commons:DefaultArtistCatalogRegistry`. */
class DefaultArtistCatalogRegistry :
    ArtistCatalogRegistryBase<AudioItem, ArtistCatalog<AudioItem>>()

// ---------------------------------------------------------------------------
// AudioLibrary — interface + abstract base + concrete (delegation pattern)
// ---------------------------------------------------------------------------

/**
 * Repository interface for audio items with artist catalog support.
 * Mirrors `music-commons:AudioLibrary`.
 */
interface AudioLibrary<I : ReactiveAudioItem<I>, AC : ReactiveArtistCatalog<AC, I>> :
    Repository<Int, I>,
    Flow.Publisher<CrudEvent<Int, I>>

/**
 * Abstract base delegating all [Repository] operations to an inner repository.
 * Mirrors `music-commons:AudioLibraryBase`.
 */
abstract class AudioLibraryBase<I, AC>(
    protected val repository: Repository<Int, I>,
    protected val observableArtistCatalogRegistry: ArtistCatalogRegistryBase<I, AC>
) : AudioLibrary<I, AC>,
    Repository<Int, I> by repository
    where I : ReactiveAudioItem<I>,
          I : Comparable<I>,
          AC : ReactiveArtistCatalog<AC, I>,
          AC : Comparable<AC>

/**
 * Named [VolatileRepository] subclass for [AudioItem] entities, enabling KSP-generated
 * [LirpRegistryInfo] auto-registration when used as the backing store for [DefaultAudioLibrary].
 */
@LirpRepository
class AudioItemVolatileRepository internal constructor(context: LirpContext) :
    VolatileRepository<Int, AudioItem>(context, "AudioItems") {
        constructor() : this(LirpContext.default)
    }

/**
 * Concrete audio library backed by an [AudioItemVolatileRepository].
 * Mirrors `music-commons:DefaultAudioLibrary`.
 */
class DefaultAudioLibrary internal constructor(repository: Repository<Int, AudioItem>) :
    AudioLibraryBase<AudioItem, ArtistCatalog<AudioItem>>(repository, DefaultArtistCatalogRegistry()) {

        constructor(context: LirpContext) : this(AudioItemVolatileRepository(context))
        constructor() : this(LirpContext.default)

        fun create(id: Int, title: String): AudioItem =
            MutableAudioItem(id).also {
                it.title = title
                add(it)
            }
    }

// ---------------------------------------------------------------------------
// AudioItemEventSubscriber — supports PlaylistHierarchy event delegation
// ---------------------------------------------------------------------------

/**
 * Subscriber for audio item CRUD events.
 * Mirrors `music-commons:AudioItemEventSubscriber`.
 */
open class AudioItemEventSubscriber<I : ReactiveAudioItem<I>>(name: String) :
    LirpEventSubscriberBase<I, CrudEvent.Type, CrudEvent<Int, I>>(name)

// ---------------------------------------------------------------------------
// PlaylistHierarchy — interface + abstract base + concrete (delegation pattern)
// ---------------------------------------------------------------------------

/**
 * Repository interface for playlists with audio item event subscription.
 * Mirrors `music-commons:PlaylistHierarchy`.
 */
interface PlaylistHierarchy<I : ReactiveAudioItem<I>, P : ReactiveAudioPlaylist<I, P>> :
    Repository<Int, P>,
    LirpEventSubscriber<I, CrudEvent.Type, CrudEvent<Int, I>>,
    Flow.Publisher<CrudEvent<Int, P>>

/**
 * Abstract base delegating [Repository] ops to an inner repository and
 * [LirpEventSubscriber] ops to an [AudioItemEventSubscriber].
 * Mirrors `music-commons:PlaylistHierarchyBase`.
 */
abstract class PlaylistHierarchyBase<I : ReactiveAudioItem<I>, P : ReactiveAudioPlaylist<I, P>>(
    repository: Repository<Int, P>,
    audioItemEventSubscriber: AudioItemEventSubscriber<I> = AudioItemEventSubscriber("PlaylistHierarchySubscriber")
) : PlaylistHierarchy<I, P>,
    Repository<Int, P> by repository,
    LirpEventSubscriber<I, CrudEvent.Type, CrudEvent<Int, I>> by audioItemEventSubscriber

/**
 * Named [VolatileRepository] subclass for [MutableAudioPlaylist] entities, enabling KSP-generated
 * [LirpRegistryInfo] auto-registration when used as the backing store for [DefaultPlaylistHierarchy].
 */
@LirpRepository
class AudioPlaylistVolatileRepository internal constructor(context: LirpContext) :
    VolatileRepository<Int, MutableAudioPlaylist>(context, "AudioPlaylists") {
        constructor() : this(LirpContext.default)
    }

/**
 * Concrete playlist hierarchy backed by an [AudioPlaylistVolatileRepository].
 * Mirrors `music-commons:DefaultPlaylistHierarchy`.
 */
class DefaultPlaylistHierarchy internal constructor(repository: Repository<Int, MutableAudioPlaylist>) :
    PlaylistHierarchyBase<AudioItem, MutableAudioPlaylist>(repository) {

        constructor(context: LirpContext) : this(AudioPlaylistVolatileRepository(context))
        constructor() : this(LirpContext.default)

        fun create(
            id: Int,
            name: String,
            audioItemIds: List<Int> = emptyList(),
            playlistIds: Set<Int> = emptySet()
        ): MutableAudioPlaylist =
            MutableAudioPlaylistEntity(id, audioItemIds, playlistIds).also {
                it.name = name
                add(it)
            }
    }

// ---------------------------------------------------------------------------
// Cascade variant entities — test all four cascade modes on audioItems
// ---------------------------------------------------------------------------

/**
 * Audio playlist variant with [CascadeAction.CASCADE] on [audioItems]: removing this entity
 * also removes all referenced audio items.
 */
class CascadeAudioPlaylist(
    override val id: Int,
    initialAudioItemIds: List<Int> = emptyList()
) : ReactiveEntityBase<Int, CascadeAudioPlaylist>(), IdentifiableEntity<Int> {
    override val uniqueId: String get() = "cascade-audio-playlist-$id"

    var name: String by reactiveProperty("")

    @Aggregate(onDelete = CascadeAction.CASCADE)
    val audioItems by mutableAggregateList<Int, AudioItem>(initialAudioItemIds)

    override fun clone(): CascadeAudioPlaylist =
        CascadeAudioPlaylist(id, audioItems.referenceIds.toList())
            .also { it.withEventsDisabledForClone { it.name = name } }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CascadeAudioPlaylist) return false
        return id == other.id && name == other.name && audioItems.referenceIds == other.audioItems.referenceIds
    }

    override fun hashCode(): Int = 31 * (31 * id.hashCode() + name.hashCode()) + audioItems.referenceIds.hashCode()

    override fun toString(): String = "CascadeAudioPlaylist(id=$id, name='$name')"
}

/**
 * Audio playlist variant with [CascadeAction.RESTRICT] on [audioItems]: removing this entity is
 * blocked if any referenced audio items are still referenced by other entities.
 */
class RestrictAudioPlaylist(
    override val id: Int,
    initialAudioItemIds: List<Int> = emptyList()
) : ReactiveEntityBase<Int, RestrictAudioPlaylist>(), IdentifiableEntity<Int> {
    override val uniqueId: String get() = "restrict-audio-playlist-$id"

    var name: String by reactiveProperty("")

    @Aggregate(onDelete = CascadeAction.RESTRICT)
    val audioItems by mutableAggregateList<Int, AudioItem>(initialAudioItemIds)

    override fun clone(): RestrictAudioPlaylist =
        RestrictAudioPlaylist(id, audioItems.referenceIds.toList())
            .also { it.withEventsDisabledForClone { it.name = name } }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RestrictAudioPlaylist) return false
        return id == other.id && name == other.name && audioItems.referenceIds == other.audioItems.referenceIds
    }

    override fun hashCode(): Int = 31 * (31 * id.hashCode() + name.hashCode()) + audioItems.referenceIds.hashCode()

    override fun toString(): String = "RestrictAudioPlaylist(id=$id, name='$name')"
}

/**
 * Audio playlist variant with [CascadeAction.NONE] on [audioItems]: removing this entity does
 * nothing to the referenced audio items.
 */
class NoneAudioPlaylist(
    override val id: Int,
    initialAudioItemIds: List<Int> = emptyList()
) : ReactiveEntityBase<Int, NoneAudioPlaylist>(), IdentifiableEntity<Int> {
    override val uniqueId: String get() = "none-audio-playlist-$id"

    var name: String by reactiveProperty("")

    @Aggregate(onDelete = CascadeAction.NONE)
    val audioItems by mutableAggregateList<Int, AudioItem>(initialAudioItemIds)

    override fun clone(): NoneAudioPlaylist =
        NoneAudioPlaylist(id, audioItems.referenceIds.toList())
            .also { it.withEventsDisabledForClone { it.name = name } }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NoneAudioPlaylist) return false
        return id == other.id && name == other.name && audioItems.referenceIds == other.audioItems.referenceIds
    }

    override fun hashCode(): Int = 31 * (31 * id.hashCode() + name.hashCode()) + audioItems.referenceIds.hashCode()

    override fun toString(): String = "NoneAudioPlaylist(id=$id, name='$name')"
}

// ---------------------------------------------------------------------------
// Cascade variant repositories
// ---------------------------------------------------------------------------

/** Repository for [CascadeAudioPlaylist] entities. */
@LirpRepository
class CascadePlaylistRepo internal constructor(context: LirpContext) :
    VolatileRepository<Int, CascadeAudioPlaylist>(context, "CascadePlaylists") {
        constructor() : this(LirpContext.default)

        fun create(id: Int, name: String, audioItemIds: List<Int> = emptyList()): CascadeAudioPlaylist =
            CascadeAudioPlaylist(id, audioItemIds).also {
                it.name = name
                add(it)
            }
    }

/** Repository for [RestrictAudioPlaylist] entities. */
@LirpRepository
class RestrictPlaylistRepo internal constructor(context: LirpContext) :
    VolatileRepository<Int, RestrictAudioPlaylist>(context, "RestrictPlaylists") {
        constructor() : this(LirpContext.default)

        fun create(id: Int, name: String, audioItemIds: List<Int> = emptyList()): RestrictAudioPlaylist =
            RestrictAudioPlaylist(id, audioItemIds).also {
                it.name = name
                add(it)
            }
    }

/** Repository for [NoneAudioPlaylist] entities. */
@LirpRepository
class NonePlaylistRepo internal constructor(context: LirpContext) :
    VolatileRepository<Int, NoneAudioPlaylist>(context, "NonePlaylists") {
        constructor() : this(LirpContext.default)

        fun create(id: Int, name: String, audioItemIds: List<Int> = emptyList()): NoneAudioPlaylist =
            NoneAudioPlaylist(id, audioItemIds).also {
                it.name = name
                add(it)
            }
    }

// ---------------------------------------------------------------------------
// JSON repository classes for integration tests
// ---------------------------------------------------------------------------

/**
 * JSON-backed repository for [AudioItem] entities, used in integration tests
 * to verify persistence and round-trip serialization.
 */
@LirpRepository
class AudioItemJsonFileRepository internal constructor(
    context: LirpContext,
    file: File,
    serializer: KSerializer<Map<Int, AudioItem>>,
    serializationDelayMs: Long = 50L,
    loadOnInit: Boolean = true
) : JsonFileRepository<Int, AudioItem>(context, file, serializer, serializationDelay = serializationDelayMs.milliseconds, loadOnInit = loadOnInit)

/**
 * JSON-backed repository for [MutableAudioPlaylist] entities, used in integration tests
 * to verify persistence and round-trip serialization.
 */
@LirpRepository
class PlaylistHierarchyJsonFileRepository internal constructor(
    context: LirpContext,
    file: File,
    serializer: KSerializer<Map<Int, MutableAudioPlaylist>>,
    serializationDelayMs: Long = 50L,
    loadOnInit: Boolean = true
) : JsonFileRepository<Int, MutableAudioPlaylist>(context, file, serializer, serializationDelay = serializationDelayMs.milliseconds, loadOnInit = loadOnInit)