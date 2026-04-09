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

/**
 * Entity using [fxAggregateList] and [fxAggregateSet] delegates for integration testing
 * of the FxObservableCollectionProxy wiring through RegistryBase.
 */
class FxAudioPlaylistEntity(
    override val id: Int,
    name: String,
    initialAudioItemIds: List<Int> = emptyList(),
    initialPlaylistIds: Set<Int> = emptySet()
) : ReactiveEntityBase<Int, FxAudioPlaylistEntity>(), IdentifiableEntity<Int> {
    override val uniqueId: String get() = "fx-audio-playlist-$id"

    var name: String by reactiveProperty(name)

    @Aggregate(onDelete = CascadeAction.DETACH)
    val audioItems by fxAggregateList<Int, AudioItem>(initialAudioItemIds, dispatchToFxThread = false)

    @Aggregate(onDelete = CascadeAction.DETACH)
    val playlists by fxAggregateSet<Int, FxAudioPlaylistEntity>(initialPlaylistIds, dispatchToFxThread = false)

    override fun clone(): FxAudioPlaylistEntity =
        FxAudioPlaylistEntity(id, name, audioItems.referenceIds.toList(), LinkedHashSet(playlists.referenceIds))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FxAudioPlaylistEntity) return false
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

    override fun toString(): String = "FxAudioPlaylistEntity(id=$id, name='$name')"
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
        audioItemIds: List<Int> = emptyList(),
        playlistIds: Set<Int> = emptySet()
    ): FxAudioPlaylistEntity = FxAudioPlaylistEntity(id, name, audioItemIds, playlistIds).also(::add)
}