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
import net.transgressoft.lirp.event.ReactiveScope
import net.transgressoft.lirp.persistence.AggregateSetProxy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Tests for cascade behavior on set-typed aggregate references ([AggregateSetRefDelegate]).
 *
 * Mirrors [AggregateCascadeCollectionTest] but uses [aggregateSet]-based entities
 * ([CascadeMusicPlaylistGroup], [RestrictMusicPlaylistGroup], [DetachMusicPlaylistGroup],
 * [NoneMusicPlaylistGroup]) to exercise the [AggregateSetRefDelegate.executeCascade] code path.
 */
@DisplayName("AggregateSetCascade")
@OptIn(ExperimentalCoroutinesApi::class)
internal class AggregateSetCascadeTest : StringSpec({

    val testDispatcher = UnconfinedTestDispatcher()
    val testScope = CoroutineScope(testDispatcher)

    beforeSpec {
        ReactiveScope.flowScope = testScope
        ReactiveScope.ioScope = testScope
    }

    lateinit var ctx: LirpContext
    lateinit var playlistRepo: AudioPlaylistVolatileRepository

    beforeEach {
        ctx = LirpContext()
        playlistRepo = AudioPlaylistVolatileRepository(ctx)
    }

    afterEach {
        ctx.close()
    }

    "CASCADE on set ref removes all referenced entities from their repository" {
        playlistRepo.add(MutableAudioPlaylistEntity(10, "Mix A"))
        playlistRepo.add(MutableAudioPlaylistEntity(20, "Mix B"))
        playlistRepo.size() shouldBe 2

        val groupRepo = CascadeMusicPlaylistGroupRepo(ctx)
        groupRepo.create(id = 100, playlistIds = setOf(10, 20))

        groupRepo.remove(groupRepo.findById(100).get())

        playlistRepo.contains(10) shouldBe false
        playlistRepo.contains(20) shouldBe false
        playlistRepo.size() shouldBe 0
    }

    "CASCADE on set ref skips already-removed entities with warning" {
        playlistRepo.add(MutableAudioPlaylistEntity(10, "Mix A"))
        playlistRepo.add(MutableAudioPlaylistEntity(20, "Mix B"))

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
    }

    "CASCADE on unbound set ref delegate is a no-op" {
        val group = CascadeMusicPlaylistGroup(id = 100, initialPlaylistIds = setOf(10))

        // Unwrap proxy to reach inner delegate; unbound so doCascade returns early without exception
        val proxy = group.playlists
        proxy.innerDelegate.executeCascade(CascadeAction.CASCADE, group)
    }

    "RESTRICT on set ref blocks parent removal when a referenced entity is still referenced" {
        playlistRepo.add(MutableAudioPlaylistEntity(10, "Mix"))

        val restrictGroupRepo = RestrictMusicPlaylistGroupRepo(ctx)
        val group1 = restrictGroupRepo.create(id = 100, playlistIds = setOf(10))
        restrictGroupRepo.create(id = 101, playlistIds = setOf(10))

        val exception =
            shouldThrow<IllegalStateException> {
                restrictGroupRepo.remove(group1)
            }
        exception.message shouldContain "Cannot cascade-delete"
        playlistRepo.contains(10) shouldBe true
    }

    "RESTRICT on set ref allows removal when no external references exist" {
        playlistRepo.add(MutableAudioPlaylistEntity(10, "Mix"))

        val restrictGroupRepo = RestrictMusicPlaylistGroupRepo(ctx)
        val group = restrictGroupRepo.create(id = 100, playlistIds = setOf(10))

        restrictGroupRepo.remove(group) shouldBe true
        restrictGroupRepo.findById(100).isPresent shouldBe false

        playlistRepo.contains(10) shouldBe true
    }

    "RESTRICT on set ref with empty IDs is a no-op" {
        val restrictGroupRepo = RestrictMusicPlaylistGroupRepo(ctx)
        val group = restrictGroupRepo.create(id = 100, playlistIds = emptySet())

        restrictGroupRepo.remove(group) shouldBe true
        restrictGroupRepo.findById(100).isPresent shouldBe false
    }

    "DETACH on set ref is a no-op" {
        playlistRepo.add(MutableAudioPlaylistEntity(10, "Mix"))

        val detachGroupRepo = DetachMusicPlaylistGroupRepo(ctx)
        val group = detachGroupRepo.create(id = 100, playlistIds = setOf(10))

        detachGroupRepo.remove(group) shouldBe true
        detachGroupRepo.findById(100).isPresent shouldBe false
        playlistRepo.contains(10) shouldBe true
    }

    "NONE on set ref is a no-op" {
        playlistRepo.add(MutableAudioPlaylistEntity(10, "Mix A"))
        playlistRepo.add(MutableAudioPlaylistEntity(20, "Mix B"))

        val noneGroupRepo = NoneMusicPlaylistGroupRepo(ctx)
        val group = noneGroupRepo.create(id = 100, playlistIds = setOf(10, 20))

        noneGroupRepo.remove(group) shouldBe true
        noneGroupRepo.findById(100).isPresent shouldBe false
        playlistRepo.contains(10) shouldBe true
        playlistRepo.contains(20) shouldBe true
        playlistRepo.size() shouldBe 2
    }
})