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
import net.transgressoft.lirp.testing.SerializeWithReactiveScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Tests for cascade behavior on collection-typed aggregate references.
 *
 * Verifies that [CascadeAudioPlaylist] (CASCADE), [DetachAudioPlaylist] (DETACH),
 * [NoneAudioPlaylist] (NONE), and [RestrictAudioPlaylist] (RESTRICT) behave correctly
 * when the parent entity is removed.
 */
@DisplayName("AggregateCascadeCollection")
@OptIn(ExperimentalCoroutinesApi::class)
@SerializeWithReactiveScope
internal class AggregateCascadeCollectionTest : StringSpec({

    val testDispatcher = UnconfinedTestDispatcher()
    val testScope = CoroutineScope(testDispatcher)

    beforeSpec {
        ReactiveScope.flowScope = testScope
        ReactiveScope.ioScope = testScope
    }

    lateinit var ctx: LirpContext
    lateinit var trackRepo: AudioItemVolatileRepository

    beforeEach {
        ctx = LirpContext()
        trackRepo = AudioItemVolatileRepository(ctx)
    }

    afterEach {
        ctx.close()
    }

    "CASCADE on collection ref removes all referenced entities from their repository" {
        trackRepo.create(1, "Track A")
        trackRepo.create(2, "Track B")
        trackRepo.size() shouldBe 2

        val playlistRepo = CascadePlaylistRepo(ctx)
        playlistRepo.create(id = 10, name = "Mix", audioItemIds = listOf(1, 2))

        // Remove parent — CASCADE should remove both tracks
        playlistRepo.remove(playlistRepo.findById(10).get())

        trackRepo.contains(1) shouldBe false
        trackRepo.contains(2) shouldBe false
        trackRepo.size() shouldBe 0
    }

    "CASCADE on collection ref skips already-removed entities with warning" {
        trackRepo.create(1, "Track A")
        trackRepo.create(2, "Track B")

        val playlistRepo = CascadePlaylistRepo(ctx)
        val playlist1 = playlistRepo.create(id = 10, name = "Mix 1", audioItemIds = listOf(1, 2))
        val playlist2 = playlistRepo.create(id = 11, name = "Mix 2", audioItemIds = listOf(1, 2))

        // Remove playlist1 — cascades track1 and track2
        playlistRepo.remove(playlist1)
        trackRepo.size() shouldBe 0

        // Remove playlist2 — tracks already gone, should not throw
        playlistRepo.remove(playlist2)
        trackRepo.size() shouldBe 0
    }

    "RESTRICT on collection ref blocks parent removal when a referenced entity is still referenced by another entity" {
        trackRepo.create(1, "Track A")

        val restrictPlaylistRepo = RestrictPlaylistRepo(ctx)
        val playlist1 = restrictPlaylistRepo.create(id = 10, name = "Mix 1", audioItemIds = listOf(1))
        // Another playlist also references track 1
        restrictPlaylistRepo.create(id = 11, name = "Mix 2", audioItemIds = listOf(1))

        val exception =
            shouldThrow<IllegalStateException> {
                restrictPlaylistRepo.remove(playlist1)
            }
        exception.message shouldContain "Cannot cascade-delete"
    }

    "RESTRICT on collection ref allows removal when no external references exist" {
        trackRepo.create(1, "Track A")

        val restrictPlaylistRepo = RestrictPlaylistRepo(ctx)
        val playlist = restrictPlaylistRepo.create(id = 10, name = "Mix", audioItemIds = listOf(1))

        // Only this playlist references track 1 — removal should succeed
        restrictPlaylistRepo.remove(playlist)

        // Track still exists (RESTRICT does not delete, just blocks if externally referenced)
        trackRepo.contains(1) shouldBe true
    }

    "DETACH on collection ref is a no-op" {
        trackRepo.create(1, "Track A")

        val detachPlaylistRepo = DetachPlaylistRepo(ctx)
        val playlist = detachPlaylistRepo.create(id = 10, name = "Mix", audioItemIds = listOf(1))

        // Remove parent with DETACH — no error, tracks remain
        detachPlaylistRepo.remove(playlist)

        trackRepo.contains(1) shouldBe true
    }

    "NONE on collection ref is a no-op (default behavior)" {
        trackRepo.create(1, "Track A")
        trackRepo.create(2, "Track B")

        val nonePlaylistRepo = NonePlaylistRepo(ctx)
        val playlist = nonePlaylistRepo.create(id = 10, name = "Mix", audioItemIds = listOf(1, 2))

        nonePlaylistRepo.remove(playlist)

        // All tracks remain unaffected
        trackRepo.contains(1) shouldBe true
        trackRepo.contains(2) shouldBe true
        trackRepo.size() shouldBe 2
    }
})