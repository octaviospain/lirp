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
 * Each test creates a fresh [LirpContext] for isolation. Adding a [Playlist] or [PlaylistGroup]
 * to a [VolatileRepository] triggers reference discovery and binding via [RegistryBase]. Collection
 * delegates are then resolved against the bound registry.
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
    lateinit var trackRepo: TestTrackVolatileRepo
    lateinit var playlistRepo: PlaylistVolatileRepo
    lateinit var playlistGroupRepo: PlaylistGroupVolatileRepo

    beforeEach {
        ctx = LirpContext()
        trackRepo = TestTrackVolatileRepo(ctx)
        playlistRepo = PlaylistVolatileRepo(ctx)
        playlistGroupRepo = PlaylistGroupVolatileRepo(ctx)
    }

    afterEach {
        ctx.close()
    }

    "AggregateListRefDelegate resolves all entities from bound registry in order" {
        val track1 = trackRepo.create(id = 1, title = "Track A")
        val track2 = trackRepo.create(id = 2, title = "Track B")
        val track3 = trackRepo.create(id = 3, title = "Track C")

        val playlist = playlistRepo.create(id = 10L, name = "Mix", itemIds = listOf(3, 1, 2))

        val resolved = playlist.items.resolveAll()
        resolved shouldContainExactly listOf(track3, track1, track2)
    }

    "AggregateListRefDelegate preserves duplicate IDs during resolution" {
        val track1 = trackRepo.create(id = 1, title = "Track A")

        val playlist = playlistRepo.create(id = 10L, name = "Repeat Mix", itemIds = listOf(1, 1, 1))

        val resolved = playlist.items.resolveAll()
        resolved shouldContainExactly listOf(track1, track1, track1)
        resolved shouldHaveSize 3
    }

    "AggregateSetRefDelegate resolves unique entities from bound registry" {
        val playlist1 = playlistRepo.create(id = 10L, name = "Mix 1", itemIds = emptyList())
        val playlist2 = playlistRepo.create(id = 20L, name = "Mix 2", itemIds = emptyList())

        val group = playlistGroupRepo.create(id = 100L, playlistIds = setOf(10L, 20L))

        val resolved = group.playlists.resolveAll()
        resolved shouldContainExactlyInAnyOrder listOf(playlist1, playlist2)
        resolved shouldBe setOf(playlist1, playlist2)
    }

    "Collection delegate resolveAll omits IDs not found in registry (partial resolution)" {
        val track1 = trackRepo.create(id = 1, title = "Track A")
        // id=999 does not exist in trackRepo

        val playlist = playlistRepo.create(id = 10L, name = "Mix", itemIds = listOf(1, 999))

        val resolved = playlist.items.resolveAll()
        resolved shouldContainExactly listOf(track1)
    }

    "Collection delegate resolveAll returns empty when all IDs absent" {
        // No tracks added to repo

        val playlist = playlistRepo.create(id = 10L, name = "Mix", itemIds = listOf(1, 2, 3))

        playlist.items.resolveAll().shouldBeEmpty()
    }

    "Collection delegate resolveAll reflects live registry state after entity removal" {
        val track1 = trackRepo.create(id = 1, title = "Track A")
        val track2 = trackRepo.create(id = 2, title = "Track B")

        val playlist = playlistRepo.create(id = 10L, name = "Mix", itemIds = listOf(1, 2))

        playlist.items.resolveAll() shouldContainExactly listOf(track1, track2)

        // Remove track1 from the registry
        trackRepo.remove(track1)

        // resolveAll should reflect the current state
        val resolvedAfterRemoval = playlist.items.resolveAll()
        resolvedAfterRemoval shouldContainExactly listOf(track2)
    }
})