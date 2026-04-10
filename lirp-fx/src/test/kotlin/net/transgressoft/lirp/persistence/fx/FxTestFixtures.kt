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

package net.transgressoft.lirp.persistence.fx

import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.entity.IdentifiableEntity
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.Aggregate
import net.transgressoft.lirp.persistence.AudioItem
import net.transgressoft.lirp.persistence.LirpRepository
import net.transgressoft.lirp.persistence.VolatileRepository
import javafx.beans.property.BooleanProperty
import javafx.beans.property.DoubleProperty
import javafx.beans.property.IntegerProperty
import javafx.beans.property.ObjectProperty
import javafx.beans.property.StringProperty

/**
 * Cohesive test entity exercising all lirp-fx delegate types: [reactiveProperty] for name,
 * [fxString] / [fxInteger] / [fxBoolean] / [fxDouble] / [fxObject] scalar delegates,
 * and [fxAggregateList] / [fxAggregateSet] collection delegates.
 */
class FxAudioPlaylistEntity(
    override val id: Int,
    name: String,
    initialYear: Int = 0,
    initialActive: Boolean = false,
    initialRating: Double = 0.0,
    initialTag: String? = null,
    initialDescription: String? = null,
    initialAudioItemIds: List<Int> = emptyList(),
    initialPlaylistIds: Set<Int> = emptySet()
) : ReactiveEntityBase<Int, FxAudioPlaylistEntity>(), IdentifiableEntity<Int> {
    override val uniqueId: String get() = "fx-audio-playlist-$id"

    var name: String by reactiveProperty(name)

    val tagProperty: StringProperty by fxString(initialTag ?: "", dispatchToFxThread = false)
    val yearProperty: IntegerProperty by fxInteger(initialYear, dispatchToFxThread = false)
    val activeProperty: BooleanProperty by fxBoolean(initialActive, dispatchToFxThread = false)
    val ratingProperty: DoubleProperty by fxDouble(initialRating, dispatchToFxThread = false)
    val descriptionProperty: ObjectProperty<String?> by fxObject<String?>(initialDescription, dispatchToFxThread = false)

    @Aggregate(onDelete = CascadeAction.DETACH)
    val audioItems by fxAggregateList<Int, AudioItem>(initialAudioItemIds, dispatchToFxThread = false)

    @Aggregate(onDelete = CascadeAction.DETACH)
    val playlists by fxAggregateSet<Int, FxAudioPlaylistEntity>(initialPlaylistIds, dispatchToFxThread = false)

    override fun clone(): FxAudioPlaylistEntity =
        FxAudioPlaylistEntity(
            id, name, yearProperty.get(), activeProperty.get(), ratingProperty.get(),
            tagProperty.get(), descriptionProperty.get(),
            audioItems.referenceIds.toList(), LinkedHashSet(playlists.referenceIds)
        )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FxAudioPlaylistEntity) return false
        return id == other.id &&
            name == other.name &&
            tagProperty.get() == other.tagProperty.get() &&
            yearProperty.get() == other.yearProperty.get() &&
            activeProperty.get() == other.activeProperty.get() &&
            ratingProperty.get() == other.ratingProperty.get() &&
            descriptionProperty.get() == other.descriptionProperty.get() &&
            audioItems.referenceIds == other.audioItems.referenceIds &&
            playlists.referenceIds == other.playlists.referenceIds
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + (tagProperty.get()?.hashCode() ?: 0)
        result = 31 * result + yearProperty.get()
        result = 31 * result + activeProperty.get().hashCode()
        result = 31 * result + ratingProperty.get().hashCode()
        result = 31 * result + (descriptionProperty.get()?.hashCode() ?: 0)
        result = 31 * result + audioItems.referenceIds.hashCode()
        result = 31 * result + playlists.referenceIds.hashCode()
        return result
    }

    override fun toString(): String = "FxAudioPlaylistEntity(id=$id, name='$name')"

    /** Test-only bridge: exposes the protected [withEventsDisabled] for integration test assertions. */
    fun <T> silently(action: () -> T): T = withEventsDisabled(action)
}

/**
 * Fx-aware audio item entity extending the core [AudioItem] interface with JavaFX property
 * delegates for [title] and [albumName]. Used for projection map and fx integration tests.
 */
class FxAudioItem(
    override val id: Int,
    title: String,
    albumName: String = ""
) : ReactiveEntityBase<Int, AudioItem>(), AudioItem {
    override val uniqueId: String get() = "fx-audio-item-$id"

    val titleProperty: StringProperty by fxString(title, dispatchToFxThread = false)
    val albumNameProperty: StringProperty by fxString(albumName, dispatchToFxThread = false)

    override var title: String
        get() = titleProperty.get()
        set(value) {
            titleProperty.set(value)
        }

    override var albumName: String
        get() = albumNameProperty.get()
        set(value) {
            albumNameProperty.set(value)
        }

    override fun compareTo(other: AudioItem): Int = id.compareTo(other.id)

    override fun clone(): FxAudioItem = FxAudioItem(id, title, albumName)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FxAudioItem) return false
        return id == other.id && title == other.title && albumName == other.albumName
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + albumName.hashCode()
        return result
    }

    override fun toString(): String = "FxAudioItem(id=$id, title='$title', albumName='$albumName')"
}

/**
 * In-memory repository for [FxAudioItem] entities used in projection map integration tests.
 */
@LirpRepository
class FxAudioItemVolatileRepository :
    VolatileRepository<Int, AudioItem>("FxAudioItems") {

    fun create(id: Int, title: String, albumName: String = ""): FxAudioItem =
        FxAudioItem(id, title, albumName).also(::add)
}

/**
 * Repository for [FxAudioPlaylistEntity] entities. Uses [LirpContext.default] and the
 * public [VolatileRepository] constructor. KSP generates the accessor for delegate discovery.
 */
@LirpRepository
class FxAudioPlaylistVolatileRepository :
    VolatileRepository<Int, FxAudioPlaylistEntity>("FxAudioPlaylists") {

    fun create(
        id: Int,
        name: String,
        year: Int = 0,
        active: Boolean = false,
        rating: Double = 0.0,
        tag: String? = null,
        description: String? = null,
        audioItemIds: List<Int> = emptyList(),
        playlistIds: Set<Int> = emptySet()
    ): FxAudioPlaylistEntity =
        FxAudioPlaylistEntity(id, name, year, active, rating, tag, description, audioItemIds, playlistIds).also(::add)
}