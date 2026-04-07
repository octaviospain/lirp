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

import net.transgressoft.lirp.event.ReactiveScope
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Tests for collection reference resolution from a bound registry.
 *
 * Each test creates a fresh [LirpContext] for isolation. Adding an [ImmutableAudioPlaylist] or
 * [ImmutablePlaylistGroup] to a [VolatileRepository] triggers reference discovery and binding
 * via [RegistryBase]. Collection delegates are then resolved against the bound registry.
 */
@DisplayName("AggregateCollectionRefDelegate")
@OptIn(ExperimentalCoroutinesApi::class)
internal class AggregateCollectionRefResolutionTest : StringSpec({

    val testDispatcher = UnconfinedTestDispatcher()
    val testScope = CoroutineScope(testDispatcher)

    beforeSpec {
        ReactiveScope.flowScope = testScope
        ReactiveScope.ioScope = testScope
    }

    lateinit var ctx: LirpContext
    lateinit var trackRepo: AudioItemVolatileRepository
    lateinit var playlistRepo: ImmutableAudioPlaylistVolatileRepo
    lateinit var playlistGroupRepo: ImmutablePlaylistGroupVolatileRepo

    beforeEach {
        ctx = LirpContext()
        trackRepo = AudioItemVolatileRepository(ctx)
        playlistRepo = ImmutableAudioPlaylistVolatileRepo(ctx)
        playlistGroupRepo = ImmutablePlaylistGroupVolatileRepo(ctx)
    }

    afterEach {
        ctx.close()
    }

    "AggregateListRefDelegate resolves all entities from bound registry in order" {
        val track1 =
            MutableAudioItem(1, "Track A").also {
                trackRepo.add(it)
            }
        val track2 =
            MutableAudioItem(2, "Track B").also {
                trackRepo.add(it)
            }
        val track3 =
            MutableAudioItem(3, "Track C").also {
                trackRepo.add(it)
            }

        val playlist = playlistRepo.create(id = 10, name = "Mix", audioItemIds = listOf(3, 1, 2))

        val resolved = playlist.audioItems.resolveAll()
        resolved shouldContainExactly listOf(track3, track1, track2)
    }

    "AggregateListRefDelegate preserves duplicate IDs during resolution" {
        val track1 =
            MutableAudioItem(1, "Track A").also {
                trackRepo.add(it)
            }

        val playlist = playlistRepo.create(id = 10, name = "Repeat Mix", audioItemIds = listOf(1, 1, 1))

        val resolved = playlist.audioItems.resolveAll()
        resolved shouldContainExactly listOf(track1, track1, track1)
        resolved shouldHaveSize 3
    }

    "AggregateSetRefDelegate resolves unique entities from bound registry" {
        val playlist1 = playlistRepo.create(id = 10, name = "Mix 1")
        val playlist2 = playlistRepo.create(id = 20, name = "Mix 2")

        val group = playlistGroupRepo.create(id = 100, playlistIds = setOf(10, 20))

        val resolved = group.playlists.resolveAll()
        resolved shouldContainExactlyInAnyOrder listOf(playlist1, playlist2)
        resolved shouldBe setOf(playlist1, playlist2)
    }

    "Collection delegate resolveAll omits IDs not found in registry (partial resolution)" {
        val track1 =
            MutableAudioItem(1, "Track A").also {
                trackRepo.add(it)
            }
        // id=999 does not exist in trackRepo

        val playlist = playlistRepo.create(id = 10, name = "Mix", audioItemIds = listOf(1, 999))

        val resolved = playlist.audioItems.resolveAll()
        resolved shouldContainExactly listOf(track1)
    }

    "Collection delegate resolveAll returns empty when all IDs absent" {
        // No tracks added to repo
        val playlist = playlistRepo.create(id = 10, name = "Mix", audioItemIds = listOf(1, 2, 3))

        playlist.audioItems.resolveAll().shouldBeEmpty()
    }

    "Collection delegate resolveAll reflects live registry state after entity removal" {
        val track1 =
            MutableAudioItem(1, "Track A").also {
                trackRepo.add(it)
            }
        val track2 =
            MutableAudioItem(2, "Track B").also {
                trackRepo.add(it)
            }

        val playlist = playlistRepo.create(id = 10, name = "Mix", audioItemIds = listOf(1, 2))

        playlist.audioItems.resolveAll() shouldContainExactly listOf(track1, track2)

        // Remove track1 from the registry
        trackRepo.remove(track1)

        // resolveAll should reflect the current state
        val resolvedAfterRemoval = playlist.audioItems.resolveAll()
        resolvedAfterRemoval shouldContainExactly listOf(track2)
    }
})