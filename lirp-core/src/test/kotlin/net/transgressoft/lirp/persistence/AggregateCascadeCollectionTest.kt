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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.engine.names.WithDataTestName
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Parametrized test suite for cascade behavior on collection-typed aggregate references.
 *
 * Each [CascadeCollectionRow] carries a single cascade-mode scenario. Verifies that
 * [CascadeAudioPlaylist] (CASCADE), [DetachAudioPlaylist] (DETACH), [NoneAudioPlaylist] (NONE),
 * and [RestrictAudioPlaylist] (RESTRICT) behave correctly when the parent entity is removed.
 * RESTRICT has two separate rows for the blocking and non-blocking paths.
 */
@DisplayName("AggregateCascadeCollection")
internal class AggregateCascadeCollectionTest : FunSpec({

    val reactive = reactiveScope()

    context("cascade remove on collection ref") {
        withData(
            CascadeCollectionRow("CASCADE — removes all referenced entities") {
                val ctx = LirpContext()
                val trackRepo = AudioItemVolatileRepository(ctx)
                try {
                    trackRepo.create(1, "Track A")
                    trackRepo.create(2, "Track B")
                    trackRepo.size() shouldBe 2

                    val playlistRepo = CascadePlaylistRepo(ctx)
                    playlistRepo.create(id = 10, name = "Mix", audioItemIds = listOf(1, 2))

                    playlistRepo.remove(playlistRepo.findById(10).get())

                    trackRepo.contains(1) shouldBe false
                    trackRepo.contains(2) shouldBe false
                    trackRepo.size() shouldBe 0
                } finally {
                    ctx.close()
                }
            },
            CascadeCollectionRow("CASCADE — skips already-removed entities") {
                val ctx = LirpContext()
                val trackRepo = AudioItemVolatileRepository(ctx)
                try {
                    trackRepo.create(1, "Track A")
                    trackRepo.create(2, "Track B")

                    val playlistRepo = CascadePlaylistRepo(ctx)
                    val playlist1 = playlistRepo.create(id = 10, name = "Mix 1", audioItemIds = listOf(1, 2))
                    val playlist2 = playlistRepo.create(id = 11, name = "Mix 2", audioItemIds = listOf(1, 2))

                    playlistRepo.remove(playlist1)
                    trackRepo.size() shouldBe 0

                    // Tracks already gone — should not throw
                    playlistRepo.remove(playlist2)
                    trackRepo.size() shouldBe 0
                } finally {
                    ctx.close()
                }
            },
            CascadeCollectionRow("DETACH — is a no-op") {
                val ctx = LirpContext()
                val trackRepo = AudioItemVolatileRepository(ctx)
                try {
                    trackRepo.create(1, "Track A")

                    val detachPlaylistRepo = DetachPlaylistRepo(ctx)
                    val playlist = detachPlaylistRepo.create(id = 10, name = "Mix", audioItemIds = listOf(1))

                    detachPlaylistRepo.remove(playlist)

                    trackRepo.contains(1) shouldBe true
                } finally {
                    ctx.close()
                }
            },
            CascadeCollectionRow("NONE — is a no-op (default behavior)") {
                val ctx = LirpContext()
                val trackRepo = AudioItemVolatileRepository(ctx)
                try {
                    trackRepo.create(1, "Track A")
                    trackRepo.create(2, "Track B")

                    val nonePlaylistRepo = NonePlaylistRepo(ctx)
                    val playlist = nonePlaylistRepo.create(id = 10, name = "Mix", audioItemIds = listOf(1, 2))

                    nonePlaylistRepo.remove(playlist)

                    trackRepo.contains(1) shouldBe true
                    trackRepo.contains(2) shouldBe true
                    trackRepo.size() shouldBe 2
                } finally {
                    ctx.close()
                }
            },
            CascadeCollectionRow("RESTRICT — blocks removal when entity is still externally referenced") {
                val ctx = LirpContext()
                val trackRepo = AudioItemVolatileRepository(ctx)
                try {
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
                } finally {
                    ctx.close()
                }
            },
            CascadeCollectionRow("RESTRICT — allows removal when no external references exist") {
                val ctx = LirpContext()
                val trackRepo = AudioItemVolatileRepository(ctx)
                try {
                    trackRepo.create(1, "Track A")

                    val restrictPlaylistRepo = RestrictPlaylistRepo(ctx)
                    val playlist = restrictPlaylistRepo.create(id = 10, name = "Mix", audioItemIds = listOf(1))

                    // Only this playlist references track 1 — removal should succeed
                    restrictPlaylistRepo.remove(playlist)

                    // Track still exists (RESTRICT does not delete, just blocks if externally referenced)
                    trackRepo.contains(1) shouldBe true
                } finally {
                    ctx.close()
                }
            }
        ) { row ->
            row.run()
        }
    }
})

/**
 * A parametrized row for collection-cascade tests. [name] is displayed as the test name;
 * [run] contains the self-contained test body that creates its own [LirpContext] and repositories.
 */
data class CascadeCollectionRow(
    val name: String,
    val run: suspend () -> Unit
) : WithDataTestName {
    override fun dataTestName() = name
}