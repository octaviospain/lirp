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
import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withData
import io.kotest.engine.names.WithDataTestName
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Parametrized test suite for cascade behavior on set-typed aggregate references ([AggregateSetRefDelegate]).
 *
 * Each [CascadeSetRow] carries a single cascade-mode scenario. Mirrors [AggregateCascadeCollectionTest]
 * but uses [aggregateSet]-based entities ([CascadeMusicPlaylistGroup], [RestrictMusicPlaylistGroup],
 * [DetachMusicPlaylistGroup], [NoneMusicPlaylistGroup]) to exercise the
 * [AggregateSetRefDelegate.executeCascade] code path. An additional standalone test covers the
 * unbound-delegate no-op path that does not fit the parametrized structure.
 */
@DisplayName("AggregateSetCascade")
internal class AggregateSetCascadeTest : FunSpec({

    val reactive = reactiveScope()

    context("cascade remove on set ref") {
        withData(
            CascadeSetRow("CASCADE — removes all referenced entities") {
                val ctx = LirpContext()
                val playlistRepo = AudioPlaylistVolatileRepository(ctx)
                try {
                    playlistRepo.add(DefaultAudioPlaylist(10, "Mix A"))
                    playlistRepo.add(DefaultAudioPlaylist(20, "Mix B"))
                    playlistRepo.size() shouldBe 2

                    val groupRepo = CascadeMusicPlaylistGroupRepo(ctx)
                    groupRepo.create(id = 100, playlistIds = setOf(10, 20))

                    groupRepo.remove(groupRepo.findById(100).get())

                    playlistRepo.contains(10) shouldBe false
                    playlistRepo.contains(20) shouldBe false
                    playlistRepo.size() shouldBe 0
                } finally {
                    ctx.close()
                }
            },
            CascadeSetRow("CASCADE — skips already-removed entities") {
                val ctx = LirpContext()
                val playlistRepo = AudioPlaylistVolatileRepository(ctx)
                try {
                    playlistRepo.add(DefaultAudioPlaylist(10, "Mix A"))
                    playlistRepo.add(DefaultAudioPlaylist(20, "Mix B"))

                    val groupRepo = CascadeMusicPlaylistGroupRepo(ctx)
                    val group1 = groupRepo.create(id = 100, playlistIds = setOf(10, 20))
                    val group2 = groupRepo.create(id = 101, playlistIds = setOf(10, 20))

                    groupRepo.remove(group1) shouldBe true
                    groupRepo.findById(100).isPresent shouldBe false
                    playlistRepo.size() shouldBe 0

                    // Second removal — playlists already gone, no error
                    groupRepo.remove(group2) shouldBe true
                    groupRepo.findById(101).isPresent shouldBe false
                    playlistRepo.size() shouldBe 0
                } finally {
                    ctx.close()
                }
            },
            CascadeSetRow("DETACH — is a no-op") {
                val ctx = LirpContext()
                val playlistRepo = AudioPlaylistVolatileRepository(ctx)
                try {
                    playlistRepo.add(DefaultAudioPlaylist(10, "Mix"))

                    val detachGroupRepo = DetachMusicPlaylistGroupRepo(ctx)
                    val group = detachGroupRepo.create(id = 100, playlistIds = setOf(10))

                    detachGroupRepo.remove(group) shouldBe true
                    detachGroupRepo.findById(100).isPresent shouldBe false
                    playlistRepo.contains(10) shouldBe true
                } finally {
                    ctx.close()
                }
            },
            CascadeSetRow("NONE — is a no-op") {
                val ctx = LirpContext()
                val playlistRepo = AudioPlaylistVolatileRepository(ctx)
                try {
                    playlistRepo.add(DefaultAudioPlaylist(10, "Mix A"))
                    playlistRepo.add(DefaultAudioPlaylist(20, "Mix B"))

                    val noneGroupRepo = NoneMusicPlaylistGroupRepo(ctx)
                    val group = noneGroupRepo.create(id = 100, playlistIds = setOf(10, 20))

                    noneGroupRepo.remove(group) shouldBe true
                    noneGroupRepo.findById(100).isPresent shouldBe false
                    playlistRepo.contains(10) shouldBe true
                    playlistRepo.contains(20) shouldBe true
                    playlistRepo.size() shouldBe 2
                } finally {
                    ctx.close()
                }
            },
            CascadeSetRow("RESTRICT — blocks removal when entity is still externally referenced") {
                val ctx = LirpContext()
                val playlistRepo = AudioPlaylistVolatileRepository(ctx)
                try {
                    playlistRepo.add(DefaultAudioPlaylist(10, "Mix"))

                    val restrictGroupRepo = RestrictMusicPlaylistGroupRepo(ctx)
                    val group1 = restrictGroupRepo.create(id = 100, playlistIds = setOf(10))
                    restrictGroupRepo.create(id = 101, playlistIds = setOf(10))

                    val exception =
                        shouldThrow<IllegalStateException> {
                            restrictGroupRepo.remove(group1)
                        }
                    exception.message shouldContain "Cannot cascade-delete"
                    playlistRepo.contains(10) shouldBe true
                } finally {
                    ctx.close()
                }
            },
            CascadeSetRow("RESTRICT — allows removal when no external references exist") {
                val ctx = LirpContext()
                val playlistRepo = AudioPlaylistVolatileRepository(ctx)
                try {
                    playlistRepo.add(DefaultAudioPlaylist(10, "Mix"))

                    val restrictGroupRepo = RestrictMusicPlaylistGroupRepo(ctx)
                    val group = restrictGroupRepo.create(id = 100, playlistIds = setOf(10))

                    restrictGroupRepo.remove(group) shouldBe true
                    restrictGroupRepo.findById(100).isPresent shouldBe false

                    playlistRepo.contains(10) shouldBe true
                } finally {
                    ctx.close()
                }
            },
            CascadeSetRow("RESTRICT — allows removal with empty IDs") {
                val ctx = LirpContext()
                try {
                    val restrictGroupRepo = RestrictMusicPlaylistGroupRepo(ctx)
                    val group = restrictGroupRepo.create(id = 100, playlistIds = emptySet())

                    restrictGroupRepo.remove(group) shouldBe true
                    restrictGroupRepo.findById(100).isPresent shouldBe false
                } finally {
                    ctx.close()
                }
            }
        ) { row ->
            row.run()
        }
    }

    test("CASCADE on unbound set ref delegate is a no-op") {
        val group = CascadeMusicPlaylistGroup(id = 100, initialPlaylistIds = setOf(10))

        // Unwrap proxy to reach inner delegate; unbound so executeCascade returns early without exception
        val proxy = group.playlists
        proxy.innerDelegate.executeCascade(CascadeAction.CASCADE, group)
    }
})

/**
 * A parametrized row for set-cascade tests. [name] is displayed as the test name;
 * [run] contains the self-contained test body that creates its own [LirpContext] and repositories.
 */
data class CascadeSetRow(
    val name: String,
    val run: suspend () -> Unit
) : WithDataTestName {
    override fun dataTestName() = name
}