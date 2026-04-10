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

import net.transgressoft.lirp.event.ReactiveScope
import net.transgressoft.lirp.persistence.AudioItem
import net.transgressoft.lirp.persistence.LirpContext
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Integration tests verifying that [fxProjectionMap] correctly groups and updates entities
 * sourced from [FxAggregateList] collections backed by VolatileRepository-stored entities.
 *
 * Covers initial grouping, add propagation, and remove propagation.
 */
@DisplayName("FxProjectionMapIntegrationTest")
@OptIn(ExperimentalCoroutinesApi::class)
class FxProjectionMapIntegrationTest : StringSpec({

    val testDispatcher = UnconfinedTestDispatcher()
    val testScope = CoroutineScope(testDispatcher)

    beforeSpec {
        FxToolkitInit.ensureInitialized()
        ReactiveScope.flowScope = testScope
        ReactiveScope.ioScope = testScope
    }

    afterSpec {
        ReactiveScope.resetDefaultFlowScope()
        ReactiveScope.resetDefaultIoScope()
    }

    afterEach {
        LirpContext.default.close()
    }

    "fxProjectionMap groups entities added through VolatileRepository by albumName" {
        val trackRepo = FxAudioItemVolatileRepository()
        val track1 = trackRepo.create(1, "Song A", "Jazz")
        val track2 = trackRepo.create(2, "Song B", "Jazz")
        val track3 = trackRepo.create(3, "Song C", "Rock")

        val playlistRepo = FxAudioPlaylistVolatileRepository()
        val playlist = playlistRepo.create(1, "My Playlist")

        playlist.audioItems.add(track1)
        playlist.audioItems.add(track2)
        playlist.audioItems.add(track3)

        val map by fxProjectionMap(playlist::audioItems, AudioItem::albumName, false)

        map.size shouldBe 2
        map["Jazz"]!!.size shouldBe 2
        map["Rock"]!!.size shouldBe 1
    }

    "fxProjectionMap updates when entity is added to VolatileRepository aggregate list" {
        val trackRepo = FxAudioItemVolatileRepository()
        val track1 = trackRepo.create(1, "Song A", "Jazz")

        val playlistRepo = FxAudioPlaylistVolatileRepository()
        val playlist = playlistRepo.create(1, "Playlist")

        playlist.audioItems.add(track1)

        val map by fxProjectionMap(playlist::audioItems, AudioItem::albumName, false)

        map["Jazz"]!!.size shouldBe 1

        val track2 = trackRepo.create(2, "Song B", "Jazz")
        playlist.audioItems.add(track2)

        map["Jazz"]!!.size shouldBe 2
    }

    "fxProjectionMap updates when entity is removed from VolatileRepository aggregate list" {
        val trackRepo = FxAudioItemVolatileRepository()
        val track1 = trackRepo.create(1, "Song A", "Jazz")
        val track2 = trackRepo.create(2, "Song B", "Jazz")

        val playlistRepo = FxAudioPlaylistVolatileRepository()
        val playlist = playlistRepo.create(1, "Playlist")

        playlist.audioItems.add(track1)
        playlist.audioItems.add(track2)

        val map by fxProjectionMap(playlist::audioItems, AudioItem::albumName, false)

        map["Jazz"]!!.size shouldBe 2

        playlist.audioItems.remove(track1)

        map["Jazz"]!!.size shouldBe 1
    }

    "fxProjectionMap removes bucket when last entity for a key is removed" {
        val trackRepo = FxAudioItemVolatileRepository()
        val track1 = trackRepo.create(1, "Song A", "Jazz")
        val track2 = trackRepo.create(2, "Song B", "Rock")

        val playlistRepo = FxAudioPlaylistVolatileRepository()
        val playlist = playlistRepo.create(1, "Playlist")

        playlist.audioItems.add(track1)
        playlist.audioItems.add(track2)

        val map by fxProjectionMap(playlist::audioItems, AudioItem::albumName, false)

        map.size shouldBe 2

        playlist.audioItems.remove(track1)

        map.containsKey("Jazz") shouldBe false
        map.size shouldBe 1
    }

    "fxProjectionMap groups entities across multiple albums from VolatileRepository" {
        val trackRepo = FxAudioItemVolatileRepository()
        val track1 = trackRepo.create(1, "Song A", "Jazz")
        val track2 = trackRepo.create(2, "Song B", "Rock")
        val track3 = trackRepo.create(3, "Song C", "Jazz")
        val track4 = trackRepo.create(4, "Song D", "Classical")

        val playlistRepo = FxAudioPlaylistVolatileRepository()
        val playlist = playlistRepo.create(1, "Playlist")

        playlist.audioItems.addAll(listOf(track1, track2, track3, track4))

        val map by fxProjectionMap(playlist::audioItems, AudioItem::albumName, false)

        map.size shouldBe 3
        map["Jazz"]!!.size shouldBe 2
        map["Rock"]!!.size shouldBe 1
        map["Classical"]!!.size shouldBe 1
        map.keys.toList() shouldBe listOf("Classical", "Jazz", "Rock")
    }
})