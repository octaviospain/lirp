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
import net.transgressoft.lirp.entity.MutableSoftDeletable
import net.transgressoft.lirp.entity.ReactiveEntity
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.LirpEventSubscriber
import net.transgressoft.lirp.event.LirpEventSubscriberBase
import net.transgressoft.lirp.persistence.json.JsonFileRepository
import java.io.File
import java.time.Instant
import java.util.concurrent.Flow
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

// =====================================================================
// Canonical music-domain fixture entities, repositories, and supporting
// types for lirp test suites. Mirrors the polymorphic interface hierarchy,
// abstract base classes, and delegation patterns from music-commons.
// =====================================================================

// ---------------------------------------------------------------------------
// Audio Item hierarchy
// ---------------------------------------------------------------------------

/**
 * Reactive audio item with mutable [title] and [albumName] properties. Mirrors
 * `music-commons:ReactiveAudioItem` with its self-referencing type parameter.
 */
interface ReactiveAudioItem<I : ReactiveAudioItem<I>> : ReactiveEntity<Int, I>, Comparable<I> {
    var title: String
    var albumName: String
}

/**
 * Marker interface binding the self-type to a concrete audio item.
 * Mirrors `music-commons:AudioItem`.
 */
interface AudioItem : ReactiveAudioItem<AudioItem> {
    override fun clone(): AudioItem
}

/**
 * Concrete mutable audio item entity backed by [reactiveProperty] for [title] and [albumName].
 *
 * Not declared `internal` so it is accessible from the lirp-sql testFixtures source set.
 */
class MutableAudioItem
    @JvmOverloads
    constructor(
        override val id: Int,
        title: String,
        albumName: String = ""
    ) : ReactiveEntityBase<Int, AudioItem>(), AudioItem {
        override val uniqueId: String get() = "audio-item-$id"

        override var title: String by reactiveProperty(title)
        override var albumName: String by reactiveProperty(albumName)

        override fun compareTo(other: AudioItem): Int = id.compareTo(other.id)

        override fun clone(): MutableAudioItem = MutableAudioItem(id, title, albumName)

        /** Publishes a single mutation that atomically sets [title] to [newTitle]. */
        fun bulkUpdate(newTitle: String) = mutateAndPublish { title = newTitle }

        /** Publishes a single mutation that atomically sets [albumName] to [newAlbumName]. */
        fun bulkAlbumUpdate(newAlbumName: String) = mutateAndPublish { albumName = newAlbumName }

        /** Silently disables event emission for subsequent property assignments. */
        fun suppressEvents() = disableEvents()

        /** Re-enables event emission after [suppressEvents]. */
        fun restoreEvents() = enableEvents()

        /**
         * Executes [action] with events disabled and re-enables them on return.
         * Any property assignments inside [action] do not emit mutation events.
         */
        fun <T> silently(action: () -> T): T = withEventsDisabled(action)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MutableAudioItem) return false
            return id == other.id && title == other.title && albumName == other.albumName
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + title.hashCode()
            result = 31 * result + albumName.hashCode()
            return result
        }

        override fun toString(): String = "MutableAudioItem(id=$id, title='$title', albumName='$albumName')"
    }

// ---------------------------------------------------------------------------
// SoftDeletable audio item — music-domain fixture implementing SoftDeletable
// ---------------------------------------------------------------------------

/**
 * Audio item that supports soft deletion via a reactive [deletedAt] property.
 *
 * Implements both [AudioItem] and [MutableSoftDeletable], allowing it to be stored in
 * [AudioItemVolatileRepository] and used to exercise soft-delete-aware repository operations.
 * Setting [deletedAt] via [Repository.softDelete] sets the timestamp and additionally emits a
 * [net.transgressoft.lirp.event.StandardCrudEvent.SoftDelete] event.
 *
 * Not declared `internal` so it is accessible from all test source sets.
 */
class SoftDeletableMutableAudioItem
    @JvmOverloads
    constructor(
        override val id: Int,
        title: String,
        albumName: String = ""
    ) : ReactiveEntityBase<Int, AudioItem>(), AudioItem, MutableSoftDeletable {
        override val uniqueId: String get() = "soft-deletable-audio-item-$id"

        override var title: String by reactiveProperty(title)
        override var albumName: String by reactiveProperty(albumName)

        /** The instant at which this item was soft-deleted, or `null` if it is active. */
        override var deletedAt: Instant? by reactiveProperty(null)

        override fun compareTo(other: AudioItem): Int = id.compareTo(other.id)

        override fun clone(): SoftDeletableMutableAudioItem =
            SoftDeletableMutableAudioItem(id, title, albumName).also { it.deletedAt = deletedAt }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SoftDeletableMutableAudioItem) return false
            return id == other.id && title == other.title && albumName == other.albumName && deletedAt == other.deletedAt
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + title.hashCode()
            result = 31 * result + albumName.hashCode()
            result = 31 * result + (deletedAt?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "SoftDeletableMutableAudioItem(id=$id, title='$title', albumName='$albumName', deletedAt=$deletedAt)"
    }

// ---------------------------------------------------------------------------
// Multi-key audio item — music-domain fixture with a Collection<String> genres property
// ---------------------------------------------------------------------------

/**
 * Reactive audio item with a mutable [genres] property returning a [Set] of genre strings.
 * Used in multi-key projection tests where a single entity is bucketed under each of its genres.
 *
 * Not declared `internal` so it is accessible from the lirp-fx and lirp-sql testFixtures source sets.
 */
class MutableMultiKeyAudioItem
    @JvmOverloads
    constructor(
        override val id: Int,
        title: String,
        genres: Set<String> = emptySet()
    ) : ReactiveEntityBase<Int, MutableMultiKeyAudioItem>(), IdentifiableEntity<Int>, Comparable<MutableMultiKeyAudioItem> {
        override val uniqueId: String get() = "multi-key-audio-item-$id"

        var title: String by reactiveProperty(title)
        var genres: Set<String> by reactiveProperty(genres)

        override fun compareTo(other: MutableMultiKeyAudioItem): Int = id.compareTo(other.id)

        override fun clone(): MutableMultiKeyAudioItem = MutableMultiKeyAudioItem(id, title, genres)

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is MutableMultiKeyAudioItem) return false
            return id == other.id && title == other.title && genres == other.genres
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + title.hashCode()
            result = 31 * result + genres.hashCode()
            return result
        }

        override fun toString(): String = "MutableMultiKeyAudioItem(id=$id, title='$title', genres=$genres)"
    }

/**
 * Named [VolatileRepository] subclass for [MutableMultiKeyAudioItem] entities, enabling KSP-generated
 * [LirpRegistryInfo] auto-registration. Provides a [create] helper for concise test setup.
 */
@LirpRepository
class MultiKeyAudioItemVolatileRepository internal constructor(context: LirpContext) :
    VolatileRepository<Int, MutableMultiKeyAudioItem>(context, "MultiKeyAudioItems") {
        constructor() : this(LirpContext.default)

        fun create(id: Int, title: String, genres: Set<String> = emptySet()): MutableMultiKeyAudioItem =
            MutableMultiKeyAudioItem(id, title, genres).also(::add)
    }

// ---------------------------------------------------------------------------
// SoftDeletable multi-key audio item — for soft-delete + multi-key projection tests
// ---------------------------------------------------------------------------

/**
 * Soft-deletable variant of [MutableMultiKeyAudioItem] that implements both [MutableSoftDeletable]
 * and [Comparable]. Used in multi-key projection tests to verify that soft-deleting an entity
 * removes it from ALL its genre buckets and from the reverse index.
 *
 * Setting [deletedAt] via [Repository.softDelete] sets the timestamp and additionally emits a
 * [net.transgressoft.lirp.event.StandardCrudEvent.SoftDelete] event, triggering multi-key
 * projection removal across all buckets.
 */
class SoftDeletableMultiKeyAudioItem
    @JvmOverloads
    constructor(
        override val id: Int,
        title: String,
        genres: Set<String> = emptySet()
    ) : ReactiveEntityBase<Int, SoftDeletableMultiKeyAudioItem>(),
        IdentifiableEntity<Int>,
        MutableSoftDeletable,
        Comparable<SoftDeletableMultiKeyAudioItem> {
        override val uniqueId: String get() = "soft-deletable-multi-key-audio-item-$id"

        var title: String by reactiveProperty(title)
        var genres: Set<String> by reactiveProperty(genres)

        /** The instant at which this item was soft-deleted, or `null` if it is active. */
        override var deletedAt: Instant? by reactiveProperty(null)

        override fun compareTo(other: SoftDeletableMultiKeyAudioItem): Int = id.compareTo(other.id)

        override fun clone(): SoftDeletableMultiKeyAudioItem =
            SoftDeletableMultiKeyAudioItem(id, title, genres).also { it.deletedAt = deletedAt }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SoftDeletableMultiKeyAudioItem) return false
            return id == other.id && title == other.title && genres == other.genres && deletedAt == other.deletedAt
        }

        override fun hashCode(): Int {
            var result = id.hashCode()
            result = 31 * result + title.hashCode()
            result = 31 * result + genres.hashCode()
            result = 31 * result + (deletedAt?.hashCode() ?: 0)
            return result
        }

        override fun toString(): String =
            "SoftDeletableMultiKeyAudioItem(id=$id, title='$title', genres=$genres, deletedAt=$deletedAt)"
    }

/**
 * Named [VolatileRepository] for [SoftDeletableMultiKeyAudioItem] entities. Provides a [create]
 * helper for concise test setup.
 */
@LirpRepository
class SoftDeletableMultiKeyAudioItemRepo internal constructor(context: LirpContext) :
    VolatileRepository<Int, SoftDeletableMultiKeyAudioItem>(context, "SoftDeletableMultiKeyAudioItems") {
        constructor() : this(LirpContext.default)

        fun create(id: Int, title: String, genres: Set<String> = emptySet()): SoftDeletableMultiKeyAudioItem =
            SoftDeletableMultiKeyAudioItem(id, title, genres).also(::add)
    }

// ---------------------------------------------------------------------------
// MultiKeyAudioPlaylist — aggregate container for MutableMultiKeyAudioItem
// ---------------------------------------------------------------------------

/**
 * Simple playlist aggregate that holds [MutableMultiKeyAudioItem] entities via a
 * [MutableAggregateList]. Used in multi-key aggregate-source projection tests to verify
 * that [MultiKeyProjection] correctly buckets entities under every genre key.
 */
class MultiKeyAudioPlaylist(
    override val id: Int,
    val name: String,
    initialAudioItemIds: List<Int> = emptyList()
) : ReactiveEntityBase<Int, MultiKeyAudioPlaylist>(), IdentifiableEntity<Int> {
    override val uniqueId: String get() = "multi-key-audio-playlist-$id"

    @ToManyAggregates(onDelete = CascadeAction.DETACH)
    val audioItems by mutableAggregateList<Int, MutableMultiKeyAudioItem>(initialAudioItemIds)

    override fun clone(): MultiKeyAudioPlaylist =
        MultiKeyAudioPlaylist(id, name, audioItems.referenceIds.toList())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MultiKeyAudioPlaylist) return false
        return id == other.id && name == other.name && audioItems.referenceIds == other.audioItems.referenceIds
    }

    override fun hashCode(): Int = 31 * (31 * id.hashCode() + name.hashCode()) + audioItems.referenceIds.hashCode()

    override fun toString(): String = "MultiKeyAudioPlaylist(id=$id, name='$name')"
}

/** Repository for [MultiKeyAudioPlaylist] entities. */
@LirpRepository
class MultiKeyAudioPlaylistRepo internal constructor(context: LirpContext) :
    VolatileRepository<Int, MultiKeyAudioPlaylist>(context, "MultiKeyAudioPlaylists") {
        constructor() : this(LirpContext.default)

        fun create(
            id: Int,
            name: String,
            audioItemIds: List<Int> = emptyList()
        ): MultiKeyAudioPlaylist =
            MultiKeyAudioPlaylist(id, name, audioItemIds).also { add(it) }
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
    val audioItems: MutableList<I>
    val playlists: MutableSet<P>
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
    override val id: Int,
    name: String
) : ReactiveEntityBase<Int, P>(), ReactiveAudioPlaylist<I, P> {

    override val uniqueId: String get() = "audio-playlist-$id"

    override var name: String by reactiveProperty(name)

    override fun compareTo(other: AudioPlaylist<I>): Int = id.compareTo(other.id)
}

/**
 * Concrete mutable playlist with audio items and self-referencing sub-playlists.
 * Mirrors `music-commons:MutablePlaylist`.
 *
 * Not declared `internal` so it is accessible from the lirp-sql testFixtures source set.
 */
class DefaultAudioPlaylist(
    id: Int,
    name: String,
    initialAudioItemIds: List<Int> = emptyList(),
    initialPlaylistIds: Set<Int> = emptySet()
) : MutablePlaylistBase<AudioItem, MutableAudioPlaylist>(id, name),
    MutableAudioPlaylist {

    @ToManyAggregates(onDelete = CascadeAction.DETACH)
    override val audioItems by mutableAggregateList<Int, AudioItem>(initialAudioItemIds)

    @ToManyAggregates(onDelete = CascadeAction.DETACH)
    override val playlists by mutableAggregateSet<Int, MutableAudioPlaylist>(initialPlaylistIds)

    override fun clone(): DefaultAudioPlaylist =
        DefaultAudioPlaylist(id, name, audioItems.referenceIds.toList(), LinkedHashSet(playlists.referenceIds))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DefaultAudioPlaylist) return false
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

    override fun toString(): String = "DefaultAudioPlaylist(id=$id, name='$name')"
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

        fun create(id: Int, title: String, albumName: String = ""): AudioItem = MutableAudioItem(id, title, albumName).also(::add)
    }

/**
 * Concrete audio library backed by an [AudioItemVolatileRepository].
 * Mirrors `music-commons:DefaultAudioLibrary`.
 */
class DefaultAudioLibrary internal constructor(repository: Repository<Int, AudioItem>) :
    AudioLibraryBase<AudioItem, ArtistCatalog<AudioItem>>(repository, DefaultArtistCatalogRegistry()) {

        constructor(context: LirpContext) : this(AudioItemVolatileRepository(context))
        constructor() : this(LirpContext.default)

        fun create(id: Int, title: String, albumName: String = ""): AudioItem = MutableAudioItem(id, title, albumName).also(::add)
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
            DefaultAudioPlaylist(id, name, audioItemIds, playlistIds)
    }

// ---------------------------------------------------------------------------
// Cascade variant entities — test all four cascade modes on audioItems
// ---------------------------------------------------------------------------

/**
 * Audio playlist variant with [CascadeAction.CASCADE] on [audioItems]: removing this entity
 * also removes all referenced audio items; soft-deleting it soft-deletes them.
 * Implements [MutableSoftDeletable] to participate in the soft-delete cascade path.
 */
class CascadeAudioPlaylist(
    override val id: Int,
    initialAudioItemIds: List<Int> = emptyList()
) : ReactiveEntityBase<Int, CascadeAudioPlaylist>(), IdentifiableEntity<Int>, MutableSoftDeletable {
    override val uniqueId: String get() = "cascade-audio-playlist-$id"

    var name: String by reactiveProperty("")

    override var deletedAt: java.time.Instant? by reactiveProperty(null)

    @ToManyAggregates(onDelete = CascadeAction.CASCADE)
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
 * blocked if any referenced audio items are still active; soft-deleting it is similarly blocked
 * when active children exist.
 * Implements [MutableSoftDeletable] to participate in the soft-delete cascade path.
 */
class RestrictAudioPlaylist(
    override val id: Int,
    name: String,
    initialAudioItemIds: List<Int> = emptyList()
) : ReactiveEntityBase<Int, RestrictAudioPlaylist>(), IdentifiableEntity<Int>, MutableSoftDeletable {
    override val uniqueId: String get() = "restrict-audio-playlist-$id"

    var name: String by reactiveProperty(name)

    override var deletedAt: java.time.Instant? by reactiveProperty(null)

    @ToManyAggregates(onDelete = CascadeAction.RESTRICT)
    val audioItems by mutableAggregateList<Int, AudioItem>(initialAudioItemIds)

    override fun clone(): RestrictAudioPlaylist =
        RestrictAudioPlaylist(id, name, audioItems.referenceIds.toList())

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
 * nothing to the referenced audio items; soft-deleting it also leaves children unchanged.
 * Implements [MutableSoftDeletable] to participate in the soft-delete cascade path.
 */
class NoneAudioPlaylist(
    override val id: Int,
    name: String,
    initialAudioItemIds: List<Int> = emptyList()
) : ReactiveEntityBase<Int, NoneAudioPlaylist>(), IdentifiableEntity<Int>, MutableSoftDeletable {
    override val uniqueId: String get() = "none-audio-playlist-$id"

    var name: String by reactiveProperty(name)

    override var deletedAt: java.time.Instant? by reactiveProperty(null)

    @ToManyAggregates(onDelete = CascadeAction.NONE)
    val audioItems by mutableAggregateList<Int, AudioItem>(initialAudioItemIds)

    override fun clone(): NoneAudioPlaylist =
        NoneAudioPlaylist(id, name, audioItems.referenceIds.toList())

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
            RestrictAudioPlaylist(id, name, audioItemIds).also(::add)
    }

/** Repository for [NoneAudioPlaylist] entities. */
@LirpRepository
class NonePlaylistRepo internal constructor(context: LirpContext) :
    VolatileRepository<Int, NoneAudioPlaylist>(context, "NonePlaylists") {
        constructor() : this(LirpContext.default)

        fun create(id: Int, name: String, audioItemIds: List<Int> = emptyList()): NoneAudioPlaylist =
            NoneAudioPlaylist(id, name, audioItemIds).also(::add)
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

// ---------------------------------------------------------------------------
// Immutable aggregate delegate entities — for AggregateCollectionRefDeclaration/ResolutionTests
// ---------------------------------------------------------------------------

/**
 * Audio playlist with an immutable (read-only) [aggregateList] delegate for [audioItems].
 * Used in collection reference declaration and resolution tests to verify the [AggregateListRefDelegate].
 */
class ImmutableAudioPlaylist(
    override val id: Int,
    val name: String,
    initialAudioItemIds: List<Int> = emptyList()
) : ReactiveEntityBase<Int, ImmutableAudioPlaylist>(), IdentifiableEntity<Int> {
    override val uniqueId: String get() = "immutable-audio-playlist-$id"

    @ToManyAggregates
    val audioItems by aggregateList<Int, AudioItem>(initialAudioItemIds)

    override fun clone(): ImmutableAudioPlaylist =
        ImmutableAudioPlaylist(id, name, audioItems.referenceIds.toList())

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImmutableAudioPlaylist) return false
        return id == other.id && name == other.name && audioItems.referenceIds == other.audioItems.referenceIds
    }

    override fun hashCode(): Int = 31 * (31 * id.hashCode() + name.hashCode()) + audioItems.referenceIds.hashCode()

    override fun toString(): String = "ImmutableAudioPlaylist(id=$id, name='$name')"
}

/**
 * Playlist group with an immutable (read-only) [aggregateSet] delegate for [playlists].
 * Used in set-based collection reference declaration and resolution tests.
 */
class ImmutablePlaylistGroup(
    override val id: Int,
    initialPlaylistIds: Set<Int> = emptySet()
) : ReactiveEntityBase<Int, ImmutablePlaylistGroup>(), IdentifiableEntity<Int> {
    override val uniqueId: String get() = "immutable-playlist-group-$id"

    @ToManyAggregates
    val playlists by aggregateSet<Int, ImmutableAudioPlaylist>(initialPlaylistIds)

    override fun clone(): ImmutablePlaylistGroup =
        ImmutablePlaylistGroup(id, LinkedHashSet(playlists.referenceIds))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImmutablePlaylistGroup) return false
        return id == other.id && playlists.referenceIds == other.playlists.referenceIds
    }

    override fun hashCode(): Int = 31 * id.hashCode() + playlists.referenceIds.hashCode()

    override fun toString(): String = "ImmutablePlaylistGroup(id=$id)"
}

/** Repository for [ImmutableAudioPlaylist] entities. */
@LirpRepository
class ImmutableAudioPlaylistVolatileRepo internal constructor(context: LirpContext) :
    VolatileRepository<Int, ImmutableAudioPlaylist>(context, "ImmutableAudioPlaylists") {
        constructor() : this(LirpContext.default)

        fun create(id: Int, name: String, audioItemIds: List<Int> = emptyList()): ImmutableAudioPlaylist =
            ImmutableAudioPlaylist(id, name, audioItemIds).also { add(it) }
    }

/** Repository for [ImmutablePlaylistGroup] entities. */
@LirpRepository
class ImmutablePlaylistGroupVolatileRepo internal constructor(context: LirpContext) :
    VolatileRepository<Int, ImmutablePlaylistGroup>(context, "ImmutablePlaylistGroups") {
        constructor() : this(LirpContext.default)

        fun create(id: Int, playlistIds: Set<Int> = emptySet()): ImmutablePlaylistGroup =
            ImmutablePlaylistGroup(id, playlistIds).also { add(it) }
    }

// ---------------------------------------------------------------------------
// DetachAudioPlaylist — DETACH cascade variant on audioItems
// ---------------------------------------------------------------------------

/**
 * Audio playlist variant with [CascadeAction.DETACH] on [audioItems]: removing this entity does
 * nothing to the referenced audio items; soft-deleting it also leaves children unchanged.
 * Implements [MutableSoftDeletable] to participate in the soft-delete cascade path.
 */
class DetachAudioPlaylist(
    override val id: Int,
    initialAudioItemIds: List<Int> = emptyList()
) : ReactiveEntityBase<Int, DetachAudioPlaylist>(), IdentifiableEntity<Int>, MutableSoftDeletable {
    override val uniqueId: String get() = "detach-audio-playlist-$id"

    var name: String by reactiveProperty("")

    override var deletedAt: java.time.Instant? by reactiveProperty(null)

    @ToManyAggregates(onDelete = CascadeAction.DETACH)
    val audioItems by mutableAggregateList<Int, AudioItem>(initialAudioItemIds)

    override fun clone(): DetachAudioPlaylist =
        DetachAudioPlaylist(id, audioItems.referenceIds.toList())
            .also { it.withEventsDisabledForClone { it.name = name } }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DetachAudioPlaylist) return false
        return id == other.id && name == other.name && audioItems.referenceIds == other.audioItems.referenceIds
    }

    override fun hashCode(): Int = 31 * (31 * id.hashCode() + name.hashCode()) + audioItems.referenceIds.hashCode()

    override fun toString(): String = "DetachAudioPlaylist(id=$id, name='$name')"
}

/** Repository for [DetachAudioPlaylist] entities. */
@LirpRepository
class DetachPlaylistRepo internal constructor(context: LirpContext) :
    VolatileRepository<Int, DetachAudioPlaylist>(context, "DetachPlaylists") {
        constructor() : this(LirpContext.default)

        fun create(id: Int, name: String, audioItemIds: List<Int> = emptyList()): DetachAudioPlaylist =
            DetachAudioPlaylist(id, audioItemIds).also {
                it.name = name
                add(it)
            }
    }

// ---------------------------------------------------------------------------
// Set-based cascade variants — for AggregateSetCascadeTest
// ---------------------------------------------------------------------------

/**
 * Playlist group with [CascadeAction.CASCADE] on [playlists]: removing this entity also removes
 * all referenced [MutableAudioPlaylist] entities.
 */
class CascadeMusicPlaylistGroup(
    override val id: Int,
    initialPlaylistIds: Set<Int> = emptySet()
) : ReactiveEntityBase<Int, CascadeMusicPlaylistGroup>(), IdentifiableEntity<Int> {
    override val uniqueId: String get() = "cascade-music-playlist-group-$id"

    @ToManyAggregates(onDelete = CascadeAction.CASCADE)
    val playlists by aggregateSet<Int, MutableAudioPlaylist>(initialPlaylistIds)

    override fun clone(): CascadeMusicPlaylistGroup =
        CascadeMusicPlaylistGroup(id, LinkedHashSet(playlists.referenceIds))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CascadeMusicPlaylistGroup) return false
        return id == other.id && playlists.referenceIds == other.playlists.referenceIds
    }

    override fun hashCode(): Int = 31 * id.hashCode() + playlists.referenceIds.hashCode()

    override fun toString(): String = "CascadeMusicPlaylistGroup(id=$id)"
}

/**
 * Playlist group with [CascadeAction.RESTRICT] on [playlists]: removing this entity is blocked
 * if any referenced [MutableAudioPlaylist] is still referenced by other entities.
 */
class RestrictMusicPlaylistGroup(
    override val id: Int,
    initialPlaylistIds: Set<Int> = emptySet()
) : ReactiveEntityBase<Int, RestrictMusicPlaylistGroup>(), IdentifiableEntity<Int> {
    override val uniqueId: String get() = "restrict-music-playlist-group-$id"

    @ToManyAggregates(onDelete = CascadeAction.RESTRICT)
    val playlists by aggregateSet<Int, MutableAudioPlaylist>(initialPlaylistIds)

    override fun clone(): RestrictMusicPlaylistGroup =
        RestrictMusicPlaylistGroup(id, LinkedHashSet(playlists.referenceIds))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RestrictMusicPlaylistGroup) return false
        return id == other.id && playlists.referenceIds == other.playlists.referenceIds
    }

    override fun hashCode(): Int = 31 * id.hashCode() + playlists.referenceIds.hashCode()

    override fun toString(): String = "RestrictMusicPlaylistGroup(id=$id)"
}

/**
 * Playlist group with [CascadeAction.DETACH] on [playlists]: removing this entity is a no-op
 * with respect to the referenced [MutableAudioPlaylist] entities.
 */
class DetachMusicPlaylistGroup(
    override val id: Int,
    initialPlaylistIds: Set<Int> = emptySet()
) : ReactiveEntityBase<Int, DetachMusicPlaylistGroup>(), IdentifiableEntity<Int> {
    override val uniqueId: String get() = "detach-music-playlist-group-$id"

    @ToManyAggregates(onDelete = CascadeAction.DETACH)
    val playlists by aggregateSet<Int, MutableAudioPlaylist>(initialPlaylistIds)

    override fun clone(): DetachMusicPlaylistGroup =
        DetachMusicPlaylistGroup(id, LinkedHashSet(playlists.referenceIds))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DetachMusicPlaylistGroup) return false
        return id == other.id && playlists.referenceIds == other.playlists.referenceIds
    }

    override fun hashCode(): Int = 31 * id.hashCode() + playlists.referenceIds.hashCode()

    override fun toString(): String = "DetachMusicPlaylistGroup(id=$id)"
}

/**
 * Playlist group with [CascadeAction.NONE] on [playlists]: removing this entity does nothing
 * to the referenced [MutableAudioPlaylist] entities.
 */
class NoneMusicPlaylistGroup(
    override val id: Int,
    initialPlaylistIds: Set<Int> = emptySet()
) : ReactiveEntityBase<Int, NoneMusicPlaylistGroup>(), IdentifiableEntity<Int> {
    override val uniqueId: String get() = "none-music-playlist-group-$id"

    @ToManyAggregates(onDelete = CascadeAction.NONE)
    val playlists by aggregateSet<Int, MutableAudioPlaylist>(initialPlaylistIds)

    override fun clone(): NoneMusicPlaylistGroup =
        NoneMusicPlaylistGroup(id, LinkedHashSet(playlists.referenceIds))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NoneMusicPlaylistGroup) return false
        return id == other.id && playlists.referenceIds == other.playlists.referenceIds
    }

    override fun hashCode(): Int = 31 * id.hashCode() + playlists.referenceIds.hashCode()

    override fun toString(): String = "NoneMusicPlaylistGroup(id=$id)"
}

/** Repository for [CascadeMusicPlaylistGroup] entities. */
@LirpRepository
class CascadeMusicPlaylistGroupRepo internal constructor(context: LirpContext) :
    VolatileRepository<Int, CascadeMusicPlaylistGroup>(context, "CascadeMusicPlaylistGroups") {
        constructor() : this(LirpContext.default)

        fun create(id: Int, playlistIds: Set<Int> = emptySet()): CascadeMusicPlaylistGroup =
            CascadeMusicPlaylistGroup(id, playlistIds).also { add(it) }
    }

/** Repository for [RestrictMusicPlaylistGroup] entities. */
@LirpRepository
class RestrictMusicPlaylistGroupRepo internal constructor(context: LirpContext) :
    VolatileRepository<Int, RestrictMusicPlaylistGroup>(context, "RestrictMusicPlaylistGroups") {
        constructor() : this(LirpContext.default)

        fun create(id: Int, playlistIds: Set<Int> = emptySet()): RestrictMusicPlaylistGroup =
            RestrictMusicPlaylistGroup(id, playlistIds).also { add(it) }
    }

/** Repository for [DetachMusicPlaylistGroup] entities. */
@LirpRepository
class DetachMusicPlaylistGroupRepo internal constructor(context: LirpContext) :
    VolatileRepository<Int, DetachMusicPlaylistGroup>(context, "DetachMusicPlaylistGroups") {
        constructor() : this(LirpContext.default)

        fun create(id: Int, playlistIds: Set<Int> = emptySet()): DetachMusicPlaylistGroup =
            DetachMusicPlaylistGroup(id, playlistIds).also { add(it) }
    }

/** Repository for [NoneMusicPlaylistGroup] entities. */
@LirpRepository
class NoneMusicPlaylistGroupRepo internal constructor(context: LirpContext) :
    VolatileRepository<Int, NoneMusicPlaylistGroup>(context, "NoneMusicPlaylistGroups") {
        constructor() : this(LirpContext.default)

        fun create(id: Int, playlistIds: Set<Int> = emptySet()): NoneMusicPlaylistGroup =
            NoneMusicPlaylistGroup(id, playlistIds).also { add(it) }
    }

// ---------------------------------------------------------------------------
// Bubble-up fixture entities — music-domain equivalents for BubbleUpOrder,
// EntityA/B/C transitive chain, MutableRefOrder, OptionalRefOrder, and delegating repos
// ---------------------------------------------------------------------------

/**
 * Scalar audio item reference with bubble-up enabled. Used to verify that a mutation on the
 * referenced [AudioItem] propagates to this entity's subscribers as an [AggregateMutationEvent].
 * Replaces the generic-domain [BubbleUpOrder] with music-domain naming.
 */
@Serializable
class BubbleUpAudioPlaylist(
    override val id: Int,
    var audioItemId: Int
) : ReactiveEntityBase<Int, BubbleUpAudioPlaylist>(), IdentifiableEntity<Int> {
    override val uniqueId: String get() = "bubble-up-audio-playlist-$id"

    @ToOneAggregate(target = AudioItem::class, bubbleUp = true)
    val audioItem by aggregate<Int, AudioItem> { audioItemId }

    override fun clone(): BubbleUpAudioPlaylist = BubbleUpAudioPlaylist(id, audioItemId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BubbleUpAudioPlaylist) return false
        return id == other.id && audioItemId == other.audioItemId
    }

    override fun hashCode(): Int = 31 * id.hashCode() + audioItemId.hashCode()

    override fun toString(): String = "BubbleUpAudioPlaylist(id=$id, audioItemId=$audioItemId)"
}

/** Repository for [BubbleUpAudioPlaylist] entities. */
@LirpRepository
class BubbleUpAudioPlaylistRepo internal constructor(context: LirpContext) :
    VolatileRepository<Int, BubbleUpAudioPlaylist>(context, "BubbleUpAudioPlaylists") {
        constructor() : this(LirpContext.default)

        fun create(id: Int, audioItemId: Int): BubbleUpAudioPlaylist =
            BubbleUpAudioPlaylist(id, audioItemId).also { add(it) }
    }

// ---------------------------------------------------------------------------
// Transitive bubble-up chain — music-domain equivalents for EntityA/B/C
// ---------------------------------------------------------------------------

/**
 * Leaf entity in the transitive bubble-up chain with a mutable [trackName] property.
 * Replaces [EntityA] with music-domain naming.
 */
@PersistenceMapping(name = "bubble_audio_track")
class BubbleAudioTrack(
    override val id: Int,
    val initialTrackName: String
) : ReactiveEntityBase<Int, BubbleAudioTrack>(), IdentifiableEntity<Int> {
    var trackName: String by reactiveProperty(initialTrackName)

    override val uniqueId: String get() = "bubble-audio-track-$id"

    override fun clone(): BubbleAudioTrack = BubbleAudioTrack(id, trackName)

    fun updateTrackName(newName: String) {
        trackName = newName
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BubbleAudioTrack) return false
        return id == other.id && trackName == other.trackName
    }

    override fun hashCode(): Int = 31 * id.hashCode() + trackName.hashCode()

    override fun toString(): String = "BubbleAudioTrack(id=$id, trackName='$trackName')"
}

/**
 * Middle entity in the transitive bubble-up chain: references [BubbleAudioTrack] with
 * bubble-up enabled. Replaces [EntityB] with music-domain naming.
 *
 * Navigation via generated extension `BubbleAudioPlaylist.track`.
 */
@PersistenceMapping(name = "bubble_audio_playlist")
class BubbleAudioPlaylist(
    override val id: Int,
    trackId: Int
) : ReactiveEntityBase<Int, BubbleAudioPlaylist>(), IdentifiableEntity<Int> {
    override val uniqueId: String get() = "bubble-audio-playlist-$id"

    @ToOneAggregate(target = BubbleAudioTrack::class, bubbleUp = true, onDelete = CascadeAction.NONE)
    var trackId: Int by reactiveProperty(trackId)

    override fun clone(): BubbleAudioPlaylist = BubbleAudioPlaylist(id, trackId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BubbleAudioPlaylist) return false
        return id == other.id && trackId == other.trackId
    }

    override fun hashCode(): Int = 31 * id.hashCode() + trackId.hashCode()

    override fun toString(): String = "BubbleAudioPlaylist(id=$id, trackId=$trackId)"
}

/**
 * Top entity in the transitive bubble-up chain: references [BubbleAudioPlaylist] with
 * bubble-up enabled. A mutation in [BubbleAudioTrack] propagates to [BubbleAudioPlaylist]
 * but NOT to [BubbleAudioLibrary] — propagation is single-level only.
 * Replaces [EntityC] with music-domain naming.
 */
class BubbleAudioLibrary(
    override val id: Int,
    var playlistId: Int
) : ReactiveEntityBase<Int, BubbleAudioLibrary>(), IdentifiableEntity<Int> {
    override val uniqueId: String get() = "bubble-audio-library-$id"

    @ToOneAggregate(target = BubbleAudioPlaylist::class, bubbleUp = true)
    val playlist by aggregate<Int, BubbleAudioPlaylist> { playlistId }

    override fun clone(): BubbleAudioLibrary = BubbleAudioLibrary(id, playlistId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BubbleAudioLibrary) return false
        return id == other.id && playlistId == other.playlistId
    }

    override fun hashCode(): Int = 31 * id.hashCode() + playlistId.hashCode()

    override fun toString(): String = "BubbleAudioLibrary(id=$id, playlistId=$playlistId)"
}

/** Repository for [BubbleAudioTrack] entities. */
@LirpRepository
class BubbleAudioTrackRepo internal constructor(context: LirpContext) :
    VolatileRepository<Int, BubbleAudioTrack>(context, "BubbleAudioTracks") {
        constructor() : this(LirpContext.default)

        fun create(id: Int, trackName: String): BubbleAudioTrack =
            BubbleAudioTrack(id, trackName).also { add(it) }
    }

/** Repository for [BubbleAudioPlaylist] entities. */
@LirpRepository
class BubbleAudioPlaylistRepo internal constructor(context: LirpContext) :
    VolatileRepository<Int, BubbleAudioPlaylist>(context, "BubbleAudioPlaylists") {
        constructor() : this(LirpContext.default)

        fun create(id: Int, trackId: Int): BubbleAudioPlaylist =
            BubbleAudioPlaylist(id, trackId).also { add(it) }
    }

/** Repository for [BubbleAudioLibrary] entities. */
@LirpRepository
class BubbleAudioLibraryRepo internal constructor(context: LirpContext) :
    VolatileRepository<Int, BubbleAudioLibrary>(context, "BubbleAudioLibraries") {
        constructor() : this(LirpContext.default)

        fun create(id: Int, playlistId: Int): BubbleAudioLibrary =
            BubbleAudioLibrary(id, playlistId).also { add(it) }
    }

// ---------------------------------------------------------------------------
// MutableRefPlaylist — music-domain equivalent for MutableRefOrder
// ---------------------------------------------------------------------------

/**
 * Playlist with a mutable reactive reference to an [AudioItem]. When [audioItemId] changes,
 * the bubble-up subscription is re-wired to the new referenced entity.
 * Replaces [MutableRefOrder] with music-domain naming.
 */
class MutableRefPlaylist(
    override val id: Int,
    val initialAudioItemId: Int
) : ReactiveEntityBase<Int, MutableRefPlaylist>(), IdentifiableEntity<Int> {
    var audioItemId: Int by reactiveProperty(initialAudioItemId)

    override val uniqueId: String get() = "mutable-ref-playlist-$id"

    @ToOneAggregate(target = AudioItem::class, bubbleUp = true)
    val audioItem by aggregate<Int, AudioItem> { audioItemId }

    override fun clone(): MutableRefPlaylist = MutableRefPlaylist(id, audioItemId)

    /** Changes the referenced audio item, triggering bubble-up re-wiring on the next [resolve]. */
    fun changeItem(newId: Int) {
        audioItemId = newId
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MutableRefPlaylist) return false
        return id == other.id && audioItemId == other.audioItemId
    }

    override fun hashCode(): Int = 31 * id.hashCode() + audioItemId.hashCode()

    override fun toString(): String = "MutableRefPlaylist(id=$id, audioItemId=$audioItemId)"
}

/** Repository for [MutableRefPlaylist] entities. */
@LirpRepository
class MutableRefPlaylistRepo internal constructor(context: LirpContext) :
    VolatileRepository<Int, MutableRefPlaylist>(context, "MutableRefPlaylists") {
        constructor() : this(LirpContext.default)

        fun create(id: Int, audioItemId: Int): MutableRefPlaylist =
            MutableRefPlaylist(id, audioItemId).also { add(it) }
    }

// ---------------------------------------------------------------------------
// OptionalRefPlaylist — music-domain equivalent for OptionalRefOrder
// ---------------------------------------------------------------------------

/**
 * Playlist with an optional (nullable FK) reference to an [AudioItem]. Returns
 * [java.util.Optional.empty] when [audioItemId] is null, resolves correctly when set.
 * Replaces [OptionalRefOrder] with music-domain naming.
 */
class OptionalRefPlaylist(
    override val id: Int,
    var audioItemId: Int? = null
) : ReactiveEntityBase<Int, OptionalRefPlaylist>(), IdentifiableEntity<Int> {
    override val uniqueId: String get() = "optional-ref-playlist-$id"

    @ToOneAggregate(target = AudioItem::class, bubbleUp = false, onDelete = CascadeAction.DETACH)
    val audioItem by optionalAggregate<Int, AudioItem> { audioItemId }

    override fun clone(): OptionalRefPlaylist = OptionalRefPlaylist(id, audioItemId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OptionalRefPlaylist) return false
        return id == other.id && audioItemId == other.audioItemId
    }

    override fun hashCode(): Int = 31 * id.hashCode() + (audioItemId?.hashCode() ?: 0)

    override fun toString(): String = "OptionalRefPlaylist(id=$id, audioItemId=$audioItemId)"
}

/** Repository for [OptionalRefPlaylist] entities. */
@LirpRepository
class OptionalRefPlaylistRepo internal constructor(context: LirpContext) :
    VolatileRepository<Int, OptionalRefPlaylist>(context, "OptionalRefPlaylists") {
        constructor() : this(LirpContext.default)

        fun create(id: Int, audioItemId: Int? = null): OptionalRefPlaylist =
            OptionalRefPlaylist(id, audioItemId).also { add(it) }
    }

// ---------------------------------------------------------------------------
// Delegating repositories — music-domain equivalents for DelegatingCustomerRepo/DelegatingOrderRepo
// ---------------------------------------------------------------------------

/**
 * Delegation-based repository wrapper for [AudioItem] entities.
 *
 * Demonstrates the manual registration pattern: the `init` block calls
 * [RegistryBase.registerRepository] to register the delegate [VolatileRepository]
 * into [LirpContext.default]. Calling [close] deregisters from the context first,
 * then closes the delegate.
 */
class DelegatingAudioItemRepo(
    private val delegate: VolatileRepository<Int, AudioItem>
) : Repository<Int, AudioItem> by delegate, AutoCloseable {

    init {
        RegistryBase.registerRepository(AudioItem::class.java, delegate)
    }

    fun create(id: Int, title: String, albumName: String = ""): AudioItem =
        MutableAudioItem(id, title, albumName).also { add(it) }

    override fun close() {
        RegistryBase.deregisterRepository(AudioItem::class.java)
        delegate.close()
    }
}

/**
 * Delegation-based repository wrapper for [MutableAudioPlaylist] entities.
 *
 * Registers the delegate [VolatileRepository] into [LirpContext.default] on construction
 * and deregisters on [close].
 */
class DelegatingPlaylistRepo(
    private val delegate: VolatileRepository<Int, MutableAudioPlaylist>
) : Repository<Int, MutableAudioPlaylist> by delegate, AutoCloseable {

    init {
        RegistryBase.registerRepository(MutableAudioPlaylist::class.java, delegate)
    }

    override fun close() {
        RegistryBase.deregisterRepository(MutableAudioPlaylist::class.java)
        delegate.close()
    }
}

// ---------------------------------------------------------------------------
// CyclicPlaylist / CyclicPlaylistChild — cycle detection fixture entities
// ---------------------------------------------------------------------------

/**
 * Fixture entity forming a cyclic graph: [CyclicPlaylist] references [CyclicPlaylistChild]
 * with [CascadeAction.CASCADE], and [CyclicPlaylistChild] references back with
 * [CascadeAction.CASCADE]. Used to test cycle detection in cascade deletion.
 */
class CyclicPlaylist(
    override val id: Long,
    var childId: Long
) : ReactiveEntityBase<Long, CyclicPlaylist>(), IdentifiableEntity<Long> {
    override val uniqueId: String get() = "cyclic-playlist-$id"

    @ToOneAggregate(target = CyclicPlaylistChild::class, onDelete = CascadeAction.CASCADE)
    val child by aggregate<Long, CyclicPlaylistChild> { childId }

    override fun clone(): CyclicPlaylist = CyclicPlaylist(id, childId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CyclicPlaylist) return false
        return id == other.id && childId == other.childId
    }

    override fun hashCode(): Int = 31 * id.hashCode() + childId.hashCode()

    override fun toString(): String = "CyclicPlaylist(id=$id, childId=$childId)"
}

/**
 * Fixture entity forming a cyclic graph: [CyclicPlaylistChild] references [CyclicPlaylist]
 * with [CascadeAction.CASCADE]. Used to test cycle detection in cascade deletion.
 */
class CyclicPlaylistChild(
    override val id: Long,
    var parentId: Long
) : ReactiveEntityBase<Long, CyclicPlaylistChild>(), IdentifiableEntity<Long> {
    override val uniqueId: String get() = "cyclic-playlist-child-$id"

    @ToOneAggregate(target = CyclicPlaylist::class, onDelete = CascadeAction.CASCADE)
    val parent by aggregate<Long, CyclicPlaylist> { parentId }

    override fun clone(): CyclicPlaylistChild = CyclicPlaylistChild(id, parentId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CyclicPlaylistChild) return false
        return id == other.id && parentId == other.parentId
    }

    override fun hashCode(): Int = 31 * id.hashCode() + parentId.hashCode()

    override fun toString(): String = "CyclicPlaylistChild(id=$id, parentId=$parentId)"
}

/** Repository for [CyclicPlaylist] entities. */
@LirpRepository
class CyclicPlaylistRepo internal constructor(context: LirpContext) :
    VolatileRepository<Long, CyclicPlaylist>(context, "CyclicPlaylists") {
        constructor() : this(LirpContext.default)

        fun create(id: Long, childId: Long): CyclicPlaylist =
            CyclicPlaylist(id, childId).also { add(it) }
    }

/** Repository for [CyclicPlaylistChild] entities. */
@LirpRepository
class CyclicPlaylistChildRepo internal constructor(context: LirpContext) :
    VolatileRepository<Long, CyclicPlaylistChild>(context, "CyclicPlaylistChildren") {
        constructor() : this(LirpContext.default)

        fun create(id: Long, parentId: Long): CyclicPlaylistChild =
            CyclicPlaylistChild(id, parentId).also { add(it) }
    }

// ---------------------------------------------------------------------------
// Soft-deletable cyclic fixtures — cycle detection in soft-delete cascade
// ---------------------------------------------------------------------------

/**
 * Soft-deletable playlist forming a cyclic aggregate graph: [SoftDeletableCyclicPlaylist]
 * references [SoftDeletableCyclicPlaylistChild] with [CascadeAction.CASCADE], and the child
 * references back with [CascadeAction.CASCADE]. Used to verify cycle detection in the
 * soft-delete cascade guard.
 */
class SoftDeletableCyclicPlaylist(
    override val id: Long,
    var childId: Long
) : ReactiveEntityBase<Long, SoftDeletableCyclicPlaylist>(), IdentifiableEntity<Long>, MutableSoftDeletable {
    override val uniqueId: String get() = "soft-deletable-cyclic-playlist-$id"
    override var deletedAt: Instant? by reactiveProperty(null)

    @ToOneAggregate(target = SoftDeletableCyclicPlaylistChild::class, onDelete = CascadeAction.CASCADE)
    val child by aggregate<Long, SoftDeletableCyclicPlaylistChild> { childId }

    override fun clone(): SoftDeletableCyclicPlaylist = SoftDeletableCyclicPlaylist(id, childId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SoftDeletableCyclicPlaylist) return false
        return id == other.id && childId == other.childId
    }

    override fun hashCode(): Int = 31 * id.hashCode() + childId.hashCode()

    override fun toString(): String = "SoftDeletableCyclicPlaylist(id=$id, childId=$childId)"
}

/**
 * Soft-deletable child forming a cyclic aggregate graph: references [SoftDeletableCyclicPlaylist]
 * with [CascadeAction.CASCADE]. Used together with [SoftDeletableCyclicPlaylist] to verify cycle
 * detection in the soft-delete cascade guard.
 */
class SoftDeletableCyclicPlaylistChild(
    override val id: Long,
    var parentId: Long
) : ReactiveEntityBase<Long, SoftDeletableCyclicPlaylistChild>(), IdentifiableEntity<Long>, MutableSoftDeletable {
    override val uniqueId: String get() = "soft-deletable-cyclic-playlist-child-$id"
    override var deletedAt: Instant? by reactiveProperty(null)

    @ToOneAggregate(target = SoftDeletableCyclicPlaylist::class, onDelete = CascadeAction.CASCADE)
    val parent by aggregate<Long, SoftDeletableCyclicPlaylist> { parentId }

    override fun clone(): SoftDeletableCyclicPlaylistChild = SoftDeletableCyclicPlaylistChild(id, parentId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SoftDeletableCyclicPlaylistChild) return false
        return id == other.id && parentId == other.parentId
    }

    override fun hashCode(): Int = 31 * id.hashCode() + parentId.hashCode()

    override fun toString(): String = "SoftDeletableCyclicPlaylistChild(id=$id, parentId=$parentId)"
}

/** Repository for [SoftDeletableCyclicPlaylist] entities. */
@LirpRepository
class SoftDeletableCyclicPlaylistRepo internal constructor(context: LirpContext) :
    VolatileRepository<Long, SoftDeletableCyclicPlaylist>(context, "SoftDeletableCyclicPlaylists") {
        constructor() : this(LirpContext.default)

        fun create(id: Long, childId: Long): SoftDeletableCyclicPlaylist =
            SoftDeletableCyclicPlaylist(id, childId).also { add(it) }
    }

/** Repository for [SoftDeletableCyclicPlaylistChild] entities. */
@LirpRepository
class SoftDeletableCyclicPlaylistChildRepo internal constructor(context: LirpContext) :
    VolatileRepository<Long, SoftDeletableCyclicPlaylistChild>(context, "SoftDeletableCyclicPlaylistChildren") {
        constructor() : this(LirpContext.default)

        fun create(id: Long, parentId: Long): SoftDeletableCyclicPlaylistChild =
            SoftDeletableCyclicPlaylistChild(id, parentId).also { add(it) }
    }

// ---------------------------------------------------------------------------
// Scalar cascade-mode variants — exercise AggregateRefDelegate code paths
// ---------------------------------------------------------------------------

/**
 * Playlist with a scalar [CascadeAction.RESTRICT] reference to an [AudioItem]: removing this
 * entity is blocked if the referenced audio item is still referenced by another entity.
 * Used to verify the [AggregateRefDelegate.doRestrict] code path.
 */
class RestrictRefPlaylist(
    override val id: Int,
    val audioItemId: Int
) : ReactiveEntityBase<Int, RestrictRefPlaylist>(), IdentifiableEntity<Int> {
    override val uniqueId: String get() = "restrict-ref-playlist-$id"

    @ToOneAggregate(target = AudioItem::class, onDelete = CascadeAction.RESTRICT)
    val audioItem by aggregate<Int, AudioItem> { audioItemId }

    override fun clone(): RestrictRefPlaylist = RestrictRefPlaylist(id, audioItemId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RestrictRefPlaylist) return false
        return id == other.id && audioItemId == other.audioItemId
    }

    override fun hashCode(): Int = 31 * id.hashCode() + audioItemId.hashCode()

    override fun toString(): String = "RestrictRefPlaylist(id=$id, audioItemId=$audioItemId)"
}

/** Repository for [RestrictRefPlaylist] entities. */
@LirpRepository
class RestrictRefPlaylistRepo internal constructor(context: LirpContext) :
    VolatileRepository<Int, RestrictRefPlaylist>(context, "RestrictRefPlaylists") {
        constructor() : this(LirpContext.default)

        fun create(id: Int, audioItemId: Int): RestrictRefPlaylist =
            RestrictRefPlaylist(id, audioItemId).also { add(it) }
    }

/**
 * Playlist with a scalar [CascadeAction.NONE] reference to an [AudioItem]: removing this
 * entity does nothing to the referenced audio item.
 * Used to verify the [AggregateRefDelegate.doCascade] NONE code path.
 */
class NoneRefPlaylist(
    override val id: Int,
    val audioItemId: Int
) : ReactiveEntityBase<Int, NoneRefPlaylist>(), IdentifiableEntity<Int> {
    override val uniqueId: String get() = "none-ref-playlist-$id"

    @ToOneAggregate(target = AudioItem::class, onDelete = CascadeAction.NONE)
    val audioItem by aggregate<Int, AudioItem> { audioItemId }

    override fun clone(): NoneRefPlaylist = NoneRefPlaylist(id, audioItemId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NoneRefPlaylist) return false
        return id == other.id && audioItemId == other.audioItemId
    }

    override fun hashCode(): Int = 31 * id.hashCode() + audioItemId.hashCode()

    override fun toString(): String = "NoneRefPlaylist(id=$id, audioItemId=$audioItemId)"
}

/** Repository for [NoneRefPlaylist] entities. */
@LirpRepository
class NoneRefPlaylistRepo internal constructor(context: LirpContext) :
    VolatileRepository<Int, NoneRefPlaylist>(context, "NoneRefPlaylists") {
        constructor() : this(LirpContext.default)

        fun create(id: Int, audioItemId: Int): NoneRefPlaylist =
            NoneRefPlaylist(id, audioItemId).also { add(it) }
    }

// ---------------------------------------------------------------------------
// Single-ref soft-delete cascade variants — exercise executeSoftCascadeForEntity with @ToOneAggregate
// ---------------------------------------------------------------------------

/**
 * Soft-deletable playlist with a [CascadeAction.CASCADE] scalar reference to an [AudioItem].
 * Soft-deleting this entity propagates soft-deletion to the referenced audio item when it
 * implements [MutableSoftDeletable].
 */
class CascadeScalarRefPlaylist(
    override val id: Int,
    val audioItemId: Int
) : ReactiveEntityBase<Int, CascadeScalarRefPlaylist>(), IdentifiableEntity<Int>, MutableSoftDeletable {
    override val uniqueId: String get() = "cascade-scalar-ref-playlist-$id"

    override var deletedAt: java.time.Instant? by reactiveProperty(null)

    @ToOneAggregate(target = AudioItem::class, onDelete = CascadeAction.CASCADE)
    val audioItem by aggregate<Int, AudioItem> { audioItemId }

    override fun clone(): CascadeScalarRefPlaylist = CascadeScalarRefPlaylist(id, audioItemId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CascadeScalarRefPlaylist) return false
        return id == other.id && audioItemId == other.audioItemId
    }

    override fun hashCode(): Int = 31 * id.hashCode() + audioItemId.hashCode()

    override fun toString(): String = "CascadeScalarRefPlaylist(id=$id, audioItemId=$audioItemId)"
}

/** Repository for [CascadeScalarRefPlaylist] entities. */
@LirpRepository
class CascadeScalarRefPlaylistRepo internal constructor(context: LirpContext) :
    VolatileRepository<Int, CascadeScalarRefPlaylist>(context, "CascadeScalarRefPlaylists") {
        constructor() : this(LirpContext.default)

        fun create(id: Int, audioItemId: Int): CascadeScalarRefPlaylist =
            CascadeScalarRefPlaylist(id, audioItemId).also { add(it) }
    }

/**
 * Soft-deletable playlist with a [CascadeAction.RESTRICT] scalar reference to an [AudioItem].
 * Soft-deleting this entity is blocked when the referenced audio item is still active (has a
 * null `deletedAt`).
 */
class RestrictScalarRefPlaylist(
    override val id: Int,
    val audioItemId: Int
) : ReactiveEntityBase<Int, RestrictScalarRefPlaylist>(), IdentifiableEntity<Int>, MutableSoftDeletable {
    override val uniqueId: String get() = "restrict-scalar-ref-playlist-$id"

    override var deletedAt: java.time.Instant? by reactiveProperty(null)

    @ToOneAggregate(target = AudioItem::class, onDelete = CascadeAction.RESTRICT)
    val audioItem by aggregate<Int, AudioItem> { audioItemId }

    override fun clone(): RestrictScalarRefPlaylist = RestrictScalarRefPlaylist(id, audioItemId)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RestrictScalarRefPlaylist) return false
        return id == other.id && audioItemId == other.audioItemId
    }

    override fun hashCode(): Int = 31 * id.hashCode() + audioItemId.hashCode()

    override fun toString(): String = "RestrictScalarRefPlaylist(id=$id, audioItemId=$audioItemId)"
}

/** Repository for [RestrictScalarRefPlaylist] entities. */
@LirpRepository
class RestrictScalarRefPlaylistRepo internal constructor(context: LirpContext) :
    VolatileRepository<Int, RestrictScalarRefPlaylist>(context, "RestrictScalarRefPlaylists") {
        constructor() : this(LirpContext.default)

        fun create(id: Int, audioItemId: Int): RestrictScalarRefPlaylist =
            RestrictScalarRefPlaylist(id, audioItemId).also { add(it) }
    }