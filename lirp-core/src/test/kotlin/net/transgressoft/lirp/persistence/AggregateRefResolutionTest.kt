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

import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.optional.shouldBeEmpty
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe

/**
 * Tests for aggregate reference resolve() returns entities from bound repositories.
 *
 * Each test creates a fresh [LirpContext] for isolation. Adding a [BubbleUpAudioPlaylist] to a
 * [VolatileRepository] triggers reference discovery and binding via [RegistryBase]. The [audioItem]
 * reference is then resolved against a separately maintained [AudioItem] repository in the same context.
 */
@DisplayName("AggregateRefDelegate")
internal class AggregateRefResolutionTest : FunSpec({

    val reactive = reactiveScope()

    lateinit var ctx: LirpContext
    lateinit var audioItemRepo: AudioItemVolatileRepository
    lateinit var playlistRepo: BubbleUpAudioPlaylistRepo

    beforeEach {
        ctx = LirpContext()
        audioItemRepo = AudioItemVolatileRepository(ctx)
        playlistRepo = BubbleUpAudioPlaylistRepo(ctx)
    }

    afterEach {
        ctx.close()
    }

    test("resolve returns the referenced audio item entity when it exists in the repository") {
        audioItemRepo.create(id = 1, title = "Track A")

        val playlist = playlistRepo.create(id = 100, audioItemId = 1)

        val resolved = playlist.audioItem.resolve()
        resolved.shouldBePresent { it shouldBe audioItemRepo.findById(1).get() }
    }

    test("resolve returns Optional.empty when the referenced audio item does not exist in the repository") {
        val playlist = playlistRepo.create(id = 100, audioItemId = 999)

        playlist.audioItem.resolve().shouldBeEmpty()
    }

    test("resolve returns updated entity after the referenced audioItemId field changes") {
        val audioItem1 = audioItemRepo.create(id = 1, title = "Track A")
        val audioItem2 = audioItemRepo.create(id = 2, title = "Track B")

        val playlist = playlistRepo.create(id = 100, audioItemId = 1)

        playlist.audioItem.resolve().shouldBePresent { it shouldBe audioItem1 }

        // Change the referenced audio item ID — cache must be invalidated
        playlist.audioItemId = 2

        playlist.audioItem.resolve().shouldBePresent { it shouldBe audioItem2 }
    }

    test("resolve returns Optional.empty after referenced audio item is removed from repository") {
        val audioItem = audioItemRepo.create(id = 1, title = "Track A")

        val playlist = playlistRepo.create(id = 100, audioItemId = 1)

        // Confirm initial resolution works
        playlist.audioItem.resolve().shouldBePresent()

        // Remove the audio item from its repository
        audioItemRepo.remove(audioItem)

        // Cache should not return stale data — findById called fresh each time
        playlist.audioItem.resolve().shouldBeEmpty()
    }

    test("optionalAggregate resolve returns empty when FK is null") {
        val optionalRepo = OptionalRefPlaylistRepo(ctx)
        val playlist = optionalRepo.create(id = 200, audioItemId = null)

        playlist.audioItem.resolve().shouldBeEmpty()
    }

    test("optionalAggregate resolve returns entity when FK is set") {
        val optionalRepo = OptionalRefPlaylistRepo(ctx)
        audioItemRepo.create(id = 5, title = "Track E")
        val playlist = optionalRepo.create(id = 200, audioItemId = 5)

        playlist.audioItem.resolve().shouldBePresent { it.title shouldBe "Track E" }
    }

    test("optionalAggregate isOptional returns true") {
        val playlist = OptionalRefPlaylist(id = 200, audioItemId = null)
        (playlist.audioItem as AggregateRefDelegate<*, *>).isOptional shouldBe true
    }

    test("optionalAggregate referenceId throws when FK is null") {
        val playlist = OptionalRefPlaylist(id = 200, audioItemId = null)
        io.kotest.assertions.throwables.shouldThrow<IllegalStateException> {
            playlist.audioItem.referenceId
        }
    }

    test("optionalAggregate resolve returns empty before registry binding") {
        val playlist = OptionalRefPlaylist(id = 200, audioItemId = 5)
        // Not added to any repo — delegate not bound
        playlist.audioItem.resolve().shouldBeEmpty()
    }

    test("cross-context isolation: playlist in context B cannot resolve audio item registered only in context A") {
        val ctxA = LirpContext()
        val ctxB = LirpContext()

        try {
            val audioItemRepoA = AudioItemVolatileRepository(ctxA)
            audioItemRepoA.create(id = 1, title = "Track A")

            val playlistRepoB = BubbleUpAudioPlaylistRepo(ctxB)
            val playlist = playlistRepoB.create(id = 100, audioItemId = 1)

            // Context B has no audio item repo — resolution should return empty
            playlist.audioItem.resolve().shouldBeEmpty()
        } finally {
            ctxA.close()
            ctxB.close()
        }
    }
})