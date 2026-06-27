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

import net.transgressoft.lirp.entity.SoftDeletable
import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Tests for the soft-delete cascade engine in [RegistryBase].
 *
 * Verifies that [RegistryBase.executeSoftCascadeForEntity] honors the declared cascade mode:
 * CASCADE propagates soft-deletion to referenced children; RESTRICT blocks when active children
 * exist; DETACH and NONE leave children unchanged.
 */
@DisplayName("SoftDeleteCascadeTest")
internal class SoftDeleteCascadeTest : StringSpec({

    val reactive = reactiveScope()

    lateinit var ctx: LirpContext
    lateinit var audioItemRepo: AudioItemVolatileRepository

    beforeEach {
        ctx = LirpContext()
        audioItemRepo = AudioItemVolatileRepository(ctx)
    }

    afterEach {
        ctx.close()
    }

    "CASCADE soft-delete propagates to referenced children" {
        val child = SoftDeletableMutableAudioItem(id = 1, title = "Track A")
        audioItemRepo.add(child)

        val cascadePlaylistRepo = CascadePlaylistRepo(ctx)
        cascadePlaylistRepo.create(id = 100, name = "Playlist A", audioItemIds = listOf(child.id))

        val playlist = cascadePlaylistRepo.findById(100).get()
        cascadePlaylistRepo.softDelete(playlist)

        reactive.advance()

        child.deletedAt.shouldNotBeNull()
    }

    "RESTRICT soft-delete throws when at least one active child exists" {
        val child = SoftDeletableMutableAudioItem(id = 1, title = "Track A")
        audioItemRepo.add(child)

        val restrictPlaylistRepo = RestrictPlaylistRepo(ctx)
        restrictPlaylistRepo.create(id = 100, name = "Playlist A", audioItemIds = listOf(child.id))

        val playlist = restrictPlaylistRepo.findById(100).get()
        shouldThrow<IllegalStateException> {
            restrictPlaylistRepo.softDelete(playlist)
        }
    }

    "RESTRICT soft-delete succeeds when all children are already soft-deleted" {
        val child = SoftDeletableMutableAudioItem(id = 1, title = "Track A")
        audioItemRepo.add(child)
        audioItemRepo.softDelete(child)

        reactive.advance()

        val restrictPlaylistRepo = RestrictPlaylistRepo(ctx)
        restrictPlaylistRepo.create(id = 100, name = "Playlist A", audioItemIds = listOf(child.id))

        val playlist = restrictPlaylistRepo.findById(100).get()
        val result = restrictPlaylistRepo.softDelete(playlist)

        reactive.advance()

        result shouldBe true
        (playlist as? SoftDeletable)?.deletedAt.shouldNotBeNull()
    }

    "DETACH soft-delete leaves children unchanged" {
        val child = SoftDeletableMutableAudioItem(id = 1, title = "Track A")
        audioItemRepo.add(child)

        val detachPlaylistRepo = DetachPlaylistRepo(ctx)
        detachPlaylistRepo.create(id = 100, name = "Playlist A", audioItemIds = listOf(child.id))

        val playlist = detachPlaylistRepo.findById(100).get()
        detachPlaylistRepo.softDelete(playlist)

        reactive.advance()

        child.deletedAt.shouldBeNull()
    }

    "NONE soft-delete leaves children unchanged" {
        val child = SoftDeletableMutableAudioItem(id = 1, title = "Track A")
        audioItemRepo.add(child)

        val nonePlaylistRepo = NonePlaylistRepo(ctx)
        nonePlaylistRepo.create(id = 100, name = "Playlist A", audioItemIds = listOf(child.id))

        val playlist = nonePlaylistRepo.findById(100).get()
        nonePlaylistRepo.softDelete(playlist)

        reactive.advance()

        child.deletedAt.shouldBeNull()
    }

    "cascade cycle guard terminates safely — soft-delete on a cyclic graph succeeds" {
        // Build a genuine cyclic aggregate graph: playlist(1) → child(2) → playlist(1).
        // Unlike hard-delete cascade, soft-delete cascade terminates naturally on cycles
        // because the recursive softDelete(parent) call short-circuits when deletedAt is
        // already set (idempotency). The cycle guard (visited set) additionally blocks any
        // recursive executeSoftCascadeForEntity re-entry for the same entity.
        val cyclicPlaylistRepo = SoftDeletableCyclicPlaylistRepo(ctx)
        val cyclicChildRepo = SoftDeletableCyclicPlaylistChildRepo(ctx)

        val child = cyclicChildRepo.create(id = 2L, parentId = 1L)
        val playlist = cyclicPlaylistRepo.create(id = 1L, childId = 2L)

        // Soft-deleting the playlist propagates to the child and terminates without infinite recursion
        cyclicPlaylistRepo.softDelete(playlist) shouldBe true

        reactive.advance()

        // Both the playlist and the child must be soft-deleted
        (playlist as? net.transgressoft.lirp.entity.SoftDeletable)?.deletedAt.shouldNotBeNull()
        (child as? net.transgressoft.lirp.entity.SoftDeletable)?.deletedAt.shouldNotBeNull()
    }

    "CASCADE scalar ref soft-delete propagates to referenced single child" {
        val child = SoftDeletableMutableAudioItem(id = 1, title = "Track A")
        audioItemRepo.add(child)

        val playlistRepo = CascadeScalarRefPlaylistRepo(ctx)
        playlistRepo.create(id = 100, audioItemId = child.id)

        val playlist = playlistRepo.findById(100).get()
        playlistRepo.softDelete(playlist)

        reactive.advance()

        child.deletedAt.shouldNotBeNull()
    }

    "RESTRICT scalar ref soft-delete throws when referenced child is active" {
        val child = SoftDeletableMutableAudioItem(id = 1, title = "Track A")
        audioItemRepo.add(child)

        val playlistRepo = RestrictScalarRefPlaylistRepo(ctx)
        playlistRepo.create(id = 100, audioItemId = child.id)

        val playlist = playlistRepo.findById(100).get()
        shouldThrow<IllegalStateException> {
            playlistRepo.softDelete(playlist)
        }
    }

    "RESTRICT scalar ref soft-delete succeeds when referenced child is already soft-deleted" {
        val child = SoftDeletableMutableAudioItem(id = 1, title = "Track A")
        audioItemRepo.add(child)
        audioItemRepo.softDelete(child)

        reactive.advance()

        val playlistRepo = RestrictScalarRefPlaylistRepo(ctx)
        playlistRepo.create(id = 100, audioItemId = child.id)

        val playlist = playlistRepo.findById(100).get()
        val result = playlistRepo.softDelete(playlist)

        reactive.advance()

        result shouldBe true
        (playlist as? SoftDeletable)?.deletedAt.shouldNotBeNull()
    }

    "RESTRICT collection check is evaluated against pre-CASCADE state" {
        // RESTRICT check must fire BEFORE any CASCADE mutations so a child active at call
        // time (that would be cascade-soft-deleted later) correctly blocks the operation.
        val restrictChild = SoftDeletableMutableAudioItem(id = 2, title = "Restrict Child")
        audioItemRepo.add(restrictChild)

        val restrictPlaylistRepo = RestrictPlaylistRepo(ctx)
        restrictPlaylistRepo.create(id = 200, name = "Restrict PL", audioItemIds = listOf(restrictChild.id))
        val restrictPlaylist = restrictPlaylistRepo.findById(200).get()

        // RESTRICT playlist references an active child — must fail
        shouldThrow<IllegalStateException> {
            restrictPlaylistRepo.softDelete(restrictPlaylist)
        }

        // restrictChild must NOT have been mutated during the failed operation
        restrictChild.deletedAt.shouldBeNull()
    }

    "RESTRICT violation leaves parent active — deletedAt null, still indexed, no SoftDelete event" {
        val child = SoftDeletableMutableAudioItem(id = 1, title = "Active Child")
        audioItemRepo.add(child)

        val restrictPlaylistRepo = RestrictPlaylistRepo(ctx)
        restrictPlaylistRepo.create(id = 100, name = "Restrict PL", audioItemIds = listOf(child.id))
        val playlist = restrictPlaylistRepo.findById(100).get()

        val events = mutableListOf<CrudEvent<*, *>>()
        restrictPlaylistRepo.subscribeAsync(CrudEvent.Type.SOFT_DELETE) { events.add(it) }
        reactive.advance()

        shouldThrow<IllegalStateException> {
            restrictPlaylistRepo.softDelete(playlist)
        }

        reactive.advance()

        // Parent must remain fully active: deletedAt still null, findById returns it, no event emitted
        (playlist as? SoftDeletable)?.deletedAt.shouldBeNull()
        restrictPlaylistRepo.findById(100).isPresent shouldBe true
        events.size shouldBe 0
    }
})