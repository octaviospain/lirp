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
 * ([CascadePlaylistGroup], [RestrictPlaylistGroup], [DetachPlaylistGroup], [NonePlaylistGroup])
 * to exercise the [AggregateSetRefDelegate.executeCascade] code path.
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
    lateinit var playlistRepo: PlaylistVolatileRepo

    beforeEach {
        ctx = LirpContext()
        playlistRepo = PlaylistVolatileRepo(ctx)
    }

    afterEach {
        ctx.close()
    }

    "CASCADE on set ref removes all referenced entities from their repository" {
        playlistRepo.create(id = 10L, name = "Mix A", itemIds = emptyList())
        playlistRepo.create(id = 20L, name = "Mix B", itemIds = emptyList())
        playlistRepo.size() shouldBe 2

        val groupRepo = CascadePlaylistGroupVolatileRepo(ctx)
        groupRepo.create(id = 100L, playlistIds = setOf(10L, 20L))

        groupRepo.remove(groupRepo.findById(100L).get())

        playlistRepo.contains(10L) shouldBe false
        playlistRepo.contains(20L) shouldBe false
        playlistRepo.size() shouldBe 0
    }

    "CASCADE on set ref skips already-removed entities with warning" {
        playlistRepo.create(id = 10L, name = "Mix A", itemIds = emptyList())
        playlistRepo.create(id = 20L, name = "Mix B", itemIds = emptyList())

        val groupRepo = CascadePlaylistGroupVolatileRepo(ctx)
        val group1 = groupRepo.create(id = 100L, playlistIds = setOf(10L, 20L))
        val group2 = groupRepo.create(id = 101L, playlistIds = setOf(10L, 20L))

        groupRepo.remove(group1) shouldBe true
        groupRepo.findById(100L).isPresent shouldBe false
        playlistRepo.size() shouldBe 0

        // Second removal — playlists already gone, no error
        groupRepo.remove(group2) shouldBe true
        groupRepo.findById(101L).isPresent shouldBe false
        playlistRepo.size() shouldBe 0
    }

    "CASCADE on unbound set ref delegate is a no-op" {
        val group = CascadePlaylistGroup(id = 100L, playlistIds = setOf(10L))

        val delegate = group.playlists as AbstractAggregateCollectionRefDelegate<Long, Playlist>
        // Unbound delegate — registryRef is null, so doCascade returns early without exception
        delegate.executeCascade(net.transgressoft.lirp.entity.CascadeAction.CASCADE, group)
    }

    "RESTRICT on set ref blocks parent removal when a referenced entity is still referenced" {
        playlistRepo.create(id = 10L, name = "Mix", itemIds = emptyList())

        val restrictGroupRepo = RestrictPlaylistGroupVolatileRepo(ctx)
        val group1 = restrictGroupRepo.create(id = 100L, playlistIds = setOf(10L))
        restrictGroupRepo.create(id = 101L, playlistIds = setOf(10L))

        val exception =
            shouldThrow<IllegalStateException> {
                restrictGroupRepo.remove(group1)
            }
        exception.message shouldContain "Cannot cascade-delete"
        playlistRepo.contains(10L) shouldBe true
    }

    "RESTRICT on set ref allows removal when no external references exist" {
        playlistRepo.create(id = 10L, name = "Mix", itemIds = emptyList())

        val restrictGroupRepo = RestrictPlaylistGroupVolatileRepo(ctx)
        val group = restrictGroupRepo.create(id = 100L, playlistIds = setOf(10L))

        restrictGroupRepo.remove(group) shouldBe true
        restrictGroupRepo.findById(100L).isPresent shouldBe false

        playlistRepo.contains(10L) shouldBe true
    }

    "RESTRICT on set ref with empty IDs is a no-op" {
        val restrictGroupRepo = RestrictPlaylistGroupVolatileRepo(ctx)
        val group = restrictGroupRepo.create(id = 100L, playlistIds = emptySet())

        restrictGroupRepo.remove(group) shouldBe true
        restrictGroupRepo.findById(100L).isPresent shouldBe false
    }

    "DETACH on set ref is a no-op" {
        playlistRepo.create(id = 10L, name = "Mix", itemIds = emptyList())

        val detachGroupRepo = DetachPlaylistGroupVolatileRepo(ctx)
        val group = detachGroupRepo.create(id = 100L, playlistIds = setOf(10L))

        detachGroupRepo.remove(group) shouldBe true
        detachGroupRepo.findById(100L).isPresent shouldBe false
        playlistRepo.contains(10L) shouldBe true
    }

    "NONE on set ref is a no-op" {
        playlistRepo.create(id = 10L, name = "Mix A", itemIds = emptyList())
        playlistRepo.create(id = 20L, name = "Mix B", itemIds = emptyList())

        val noneGroupRepo = NonePlaylistGroupVolatileRepo(ctx)
        val group = noneGroupRepo.create(id = 100L, playlistIds = setOf(10L, 20L))

        noneGroupRepo.remove(group) shouldBe true
        noneGroupRepo.findById(100L).isPresent shouldBe false
        playlistRepo.contains(10L) shouldBe true
        playlistRepo.contains(20L) shouldBe true
        playlistRepo.size() shouldBe 2
    }
})