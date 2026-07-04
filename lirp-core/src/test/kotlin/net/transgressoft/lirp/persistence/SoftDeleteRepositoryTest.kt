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

import net.transgressoft.lirp.entity.IdentifiableEntity
import net.transgressoft.lirp.entity.MutableSoftDeletable
import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.StandardCrudEvent
import net.transgressoft.lirp.testing.EventRecorder
import net.transgressoft.lirp.testing.reactiveScope
import net.transgressoft.lirp.testing.record
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Instant

/**
 * A minimal soft-deletable entity with an [@Indexed] category property for secondary-index tests.
 */
data class SoftDeletableIndexedProduct(
    override val id: Int,
    @Indexed val category: String,
    override val uniqueId: String = "sdip-$id"
) : IdentifiableEntity<Int>, MutableSoftDeletable {
    override var deletedAt: Instant? = null

    override fun clone() = copy()
}

/**
 * Repository for [SoftDeletableIndexedProduct] entities.
 */
class SoftDeletableIndexedProductRepo : VolatileRepository<Int, SoftDeletableIndexedProduct>("SoftDeletableIndexedProducts")

/**
 * Tests for [VolatileRepository.softDelete] and [VolatileRepository.restore].
 *
 * Covers: mutation semantics, event emission, entity residency after soft-delete,
 * deindex/reindex behavior, and cascade wiring through [RegistryBase.executeSoftCascadeForEntity].
 */
@DisplayName("SoftDeleteRepositoryTest")
internal class SoftDeleteRepositoryTest : StringSpec({

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

    "softDelete on active entity returns true and sets deletedAt to non-null" {
        val entity = SoftDeletableMutableAudioItem(id = 1, title = "Track A")
        audioItemRepo.add(entity)

        val result = audioItemRepo.softDelete(entity)

        reactive.advance()

        result.shouldBeTrue()
        entity.deletedAt.shouldNotBeNull()
    }

    "softDelete keeps entity resident in memory (visible via rawIterator)" {
        val entity = SoftDeletableMutableAudioItem(id = 1, title = "Track A")
        audioItemRepo.add(entity)

        audioItemRepo.softDelete(entity)

        reactive.advance()

        // The entity stays in memory after soft-delete; rawIterator exposes it for internal consumers.
        // contains(id) returns false by design — soft-deleted entities are excluded from the public API.
        (audioItemRepo as RegistryBase<*, *>).rawIterator().asSequence().any { it.id == entity.id }.shouldBeTrue()
    }

    "softDelete emits StandardCrudEvent.SoftDelete" {
        val entity = SoftDeletableMutableAudioItem(id = 1, title = "Track A")
        audioItemRepo.add(entity)

        val recorder: EventRecorder<CrudEvent<Int, AudioItem>> =
            audioItemRepo.record(CrudEvent.Type.SOFT_DELETE)

        audioItemRepo.softDelete(entity)
        reactive.advance()

        recorder.count shouldBe 1
        val emitted = recorder.last
        emitted.shouldBeInstanceOf<StandardCrudEvent.SoftDelete<Int, AudioItem>>()
        emitted.entities shouldBe mapOf(entity.id to entity)
    }

    "softDelete on already soft-deleted entity returns false" {
        val entity = SoftDeletableMutableAudioItem(id = 1, title = "Track A")
        audioItemRepo.add(entity)
        audioItemRepo.softDelete(entity)

        val secondResult = audioItemRepo.softDelete(entity)

        secondResult.shouldBeFalse()
    }

    "softDelete on entity not in repository returns false" {
        val entity = SoftDeletableMutableAudioItem(id = 99, title = "Ghost")

        val result = audioItemRepo.softDelete(entity)

        result.shouldBeFalse()
    }

    "softDelete on entity not implementing MutableSoftDeletable returns false" {
        val plainEntity = audioItemRepo.create(id = 1, title = "Plain")

        val result = audioItemRepo.softDelete(plainEntity)

        result.shouldBeFalse()
    }

    "restore on soft-deleted entity returns true and clears deletedAt to null" {
        val entity = SoftDeletableMutableAudioItem(id = 1, title = "Track A")
        audioItemRepo.add(entity)
        audioItemRepo.softDelete(entity)

        reactive.advance()

        val result = audioItemRepo.restore(entity)

        reactive.advance()

        result.shouldBeTrue()
        entity.deletedAt.shouldBeNull()
    }

    "restore emits StandardCrudEvent.Restore" {
        val entity = SoftDeletableMutableAudioItem(id = 1, title = "Track A")
        audioItemRepo.add(entity)
        audioItemRepo.softDelete(entity)

        reactive.advance()

        val recorder: EventRecorder<CrudEvent<Int, AudioItem>> =
            audioItemRepo.record(CrudEvent.Type.RESTORE)
        audioItemRepo.restore(entity)
        reactive.advance()

        recorder.count shouldBe 1
        val emitted = recorder.last
        emitted.shouldNotBeNull()
        (emitted is StandardCrudEvent.Restore).shouldBeTrue()
        emitted.entities shouldBe mapOf(entity.id to entity)
    }

    "restore on active entity returns false" {
        val entity = SoftDeletableMutableAudioItem(id = 1, title = "Track A")
        audioItemRepo.add(entity)

        val result = audioItemRepo.restore(entity)

        result.shouldBeFalse()
    }

    "findByIndex excludes soft-deleted entity and re-includes it after restore" {
        val repo = SoftDeletableIndexedProductRepo()
        val entity = SoftDeletableIndexedProduct(id = 1, category = "rock")
        repo.add(entity)

        repo.findByIndex("category", "rock") shouldContain entity

        repo.softDelete(entity)
        reactive.advance()

        repo.findByIndex("category", "rock").shouldBeEmpty()

        repo.restore(entity)
        reactive.advance()

        repo.findByIndex("category", "rock") shouldContain entity

        repo.close()
    }

    "soft-deleting a CASCADE parent soft-deletes its children" {
        val child = SoftDeletableMutableAudioItem(id = 1, title = "Track A")
        audioItemRepo.add(child)

        val cascadeRepo = CascadePlaylistRepo(ctx)
        cascadeRepo.create(id = 100, name = "Playlist A", audioItemIds = listOf(child.id))

        val playlist = cascadeRepo.findById(100).get()
        cascadeRepo.softDelete(playlist)

        reactive.advance()

        child.deletedAt.shouldNotBeNull()
    }

    "soft-deleting a RESTRICT parent with active child throws IllegalStateException" {
        val child = SoftDeletableMutableAudioItem(id = 1, title = "Track A")
        audioItemRepo.add(child)

        val restrictRepo = RestrictPlaylistRepo(ctx)
        restrictRepo.create(id = 100, name = "Playlist A", audioItemIds = listOf(child.id))

        val playlist = restrictRepo.findById(100).get()
        shouldThrow<IllegalStateException> {
            restrictRepo.softDelete(playlist)
        }
    }
})