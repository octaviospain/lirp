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

import net.transgressoft.lirp.persistence.projection.registryMultiKeyProjection
import net.transgressoft.lirp.persistence.projection.registryProjection
import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for soft-delete / restore event wiring in [net.transgressoft.lirp.persistence.projection.RegistryProjection]
 * and [net.transgressoft.lirp.persistence.projection.MultiKeyRegistryProjection].
 *
 * Verifies that:
 * - Soft-deleting an entity removes it from its [RegistryProjection] bucket.
 * - Restoring an entity re-adds it to the correct [RegistryProjection] bucket.
 * - The same hold for [MultiKeyRegistryProjection].
 * - Projection seeding from a registry that already contains soft-deleted entities does not
 *   place them in buckets (and does not double-add active ones).
 */
@DisplayName("SoftDeleteProjectionTest")
internal class SoftDeleteProjectionTest : StringSpec({

    val reactive = reactiveScope()

    lateinit var ctx: LirpContext
    lateinit var audioItemRepo: AudioItemVolatileRepository
    lateinit var softDeletedMultiKeyRepo: SoftDeletableMultiKeyAudioItemRepo

    beforeEach {
        ctx = LirpContext()
        audioItemRepo = AudioItemVolatileRepository(ctx)
        softDeletedMultiKeyRepo = SoftDeletableMultiKeyAudioItemRepo(ctx)
    }

    afterEach {
        ctx.close()
    }

    // ---------------------------------------------------------------------------
    // RegistryProjection — single-key
    // ---------------------------------------------------------------------------

    "soft-deleting an entity removes it from its RegistryProjection bucket via SOFT_DELETE event" {
        val entity = SoftDeletableMutableAudioItem(id = 1, title = "Track A", albumName = "Jazz")
        audioItemRepo.add(entity)

        val projection = registryProjection(audioItemRepo, { it.albumName })
        projection["Jazz"]!!.size shouldBe 1

        audioItemRepo.softDelete(entity)
        reactive.advance()

        projection.containsKey("Jazz") shouldBe false
    }

    "restoring an entity re-adds it to the correct RegistryProjection bucket via RESTORE event" {
        val entity = SoftDeletableMutableAudioItem(id = 1, title = "Track A", albumName = "Jazz")
        audioItemRepo.add(entity)

        val projection = registryProjection(audioItemRepo, { it.albumName })
        projection["Jazz"]!!.size shouldBe 1

        audioItemRepo.softDelete(entity)
        reactive.advance()
        projection.containsKey("Jazz") shouldBe false

        audioItemRepo.restore(entity)
        reactive.advance()

        projection["Jazz"]!!.size shouldBe 1
        projection["Jazz"]!!.first().id shouldBe entity.id
    }

    "RegistryProjection seeding excludes already-soft-deleted entities" {
        val active = SoftDeletableMutableAudioItem(id = 1, title = "Active", albumName = "Rock")
        val deleted = SoftDeletableMutableAudioItem(id = 2, title = "Deleted", albumName = "Rock")
        audioItemRepo.add(active)
        audioItemRepo.add(deleted)
        audioItemRepo.softDelete(deleted)
        reactive.advance()

        // Access projection after soft-delete is already applied
        val projection = registryProjection(audioItemRepo, { it.albumName })

        projection["Rock"]!!.size shouldBe 1
        projection["Rock"]!!.first().id shouldBe active.id
    }

    // ---------------------------------------------------------------------------
    // MultiKeyRegistryProjection — multi-key
    // ---------------------------------------------------------------------------

    "soft-deleting an entity removes it from all MultiKeyRegistryProjection buckets" {
        val entity = softDeletedMultiKeyRepo.create(id = 1, title = "Track A", genres = setOf("Rock", "Metal"))

        val projection = registryMultiKeyProjection(softDeletedMultiKeyRepo, { it.genres })
        projection["Rock"]!!.size shouldBe 1
        projection["Metal"]!!.size shouldBe 1

        softDeletedMultiKeyRepo.softDelete(entity)
        reactive.advance()

        projection.containsKey("Rock") shouldBe false
        projection.containsKey("Metal") shouldBe false
    }

    "restoring an entity re-adds it to all MultiKeyRegistryProjection buckets" {
        val entity = softDeletedMultiKeyRepo.create(id = 1, title = "Track A", genres = setOf("Rock", "Metal"))

        val projection = registryMultiKeyProjection(softDeletedMultiKeyRepo, { it.genres })
        softDeletedMultiKeyRepo.softDelete(entity)
        reactive.advance()

        softDeletedMultiKeyRepo.restore(entity)
        reactive.advance()

        projection["Rock"]!!.size shouldBe 1
        projection["Metal"]!!.size shouldBe 1
    }

    "MultiKeyRegistryProjection seeding excludes already-soft-deleted entities" {
        val active = softDeletedMultiKeyRepo.create(id = 1, title = "Active", genres = setOf("Jazz"))
        val deleted = softDeletedMultiKeyRepo.create(id = 2, title = "Deleted", genres = setOf("Jazz"))
        softDeletedMultiKeyRepo.softDelete(deleted)
        reactive.advance()

        val projection = registryMultiKeyProjection(softDeletedMultiKeyRepo, { it.genres })

        projection["Jazz"]!!.size shouldBe 1
        projection["Jazz"]!!.first().id shouldBe active.id
    }
})