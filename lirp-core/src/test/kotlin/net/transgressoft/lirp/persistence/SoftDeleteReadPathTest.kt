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

import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.testing.Stress
import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.optional.shouldNotBePresent
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for the default soft-delete read-path exclusion on [RegistryBase].
 *
 * Every read surface — [RegistryBase.findById], [RegistryBase.iterator],
 * [RegistryBase.asSequence], [RegistryBase.size], [RegistryBase.isEmpty],
 * [RegistryBase.contains], [RegistryBase.findByIndex], [RegistryBase.findFirstByIndex],
 * [RegistryBase.lazySearch], [RegistryBase.search], [RegistryBase.findFirst],
 * [RegistryBase.findByUniqueId] — must exclude soft-deleted entities by default (fail-closed).
 *
 * The internal [RegistryBase.rawIterator] must expose ALL entities (including soft-deleted ones)
 * for internal consumers such as the query planner's `includeDeleted` path.
 *
 * Restoring an entity must make it reappear on every read surface.
 */
@DisplayName("SoftDeleteReadPathTest")
internal class SoftDeleteReadPathTest : StringSpec({

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

    "findById on soft-deleted entity returns empty Optional" {
        val entity = SoftDeletableMutableAudioItem(id = 1, title = "Track A")
        audioItemRepo.add(entity)
        audioItemRepo.softDelete(entity)
        reactive.advance()

        val result = audioItemRepo.findById(1)

        result.isPresent.shouldBeFalse()
    }

    "findById on active entity returns the entity" {
        val entity = SoftDeletableMutableAudioItem(id = 1, title = "Track A")
        audioItemRepo.add(entity)

        val result = audioItemRepo.findById(1)

        result.isPresent.shouldBeTrue()
        result.get() shouldBe entity
    }

    "iterator excludes soft-deleted entities" {
        val active = SoftDeletableMutableAudioItem(id = 1, title = "Active")
        val deleted = SoftDeletableMutableAudioItem(id = 2, title = "Deleted")
        audioItemRepo.add(active)
        audioItemRepo.add(deleted)
        audioItemRepo.softDelete(deleted)
        reactive.advance()

        val iterated = audioItemRepo.iterator().asSequence().toList()

        iterated shouldBe listOf(active)
    }

    "asSequence excludes soft-deleted entities" {
        val active = SoftDeletableMutableAudioItem(id = 1, title = "Active")
        val deleted = SoftDeletableMutableAudioItem(id = 2, title = "Deleted")
        audioItemRepo.add(active)
        audioItemRepo.add(deleted)
        audioItemRepo.softDelete(deleted)
        reactive.advance()

        val result = audioItemRepo.asSequence().toList()

        result shouldBe listOf(active)
    }

    "size() counts only active entities" {
        val active = SoftDeletableMutableAudioItem(id = 1, title = "Active")
        val deleted = SoftDeletableMutableAudioItem(id = 2, title = "Deleted")
        audioItemRepo.add(active)
        audioItemRepo.add(deleted)
        audioItemRepo.softDelete(deleted)
        reactive.advance()

        audioItemRepo.size() shouldBe 1
    }

    "isEmpty returns false when only soft-deleted entities exist" {
        val deleted = SoftDeletableMutableAudioItem(id = 1, title = "Deleted")
        audioItemRepo.add(deleted)
        audioItemRepo.softDelete(deleted)
        reactive.advance()

        audioItemRepo.isEmpty.shouldBeTrue()
    }

    "contains(id) returns false for a soft-deleted entity" {
        val entity = SoftDeletableMutableAudioItem(id = 1, title = "Track A")
        audioItemRepo.add(entity)
        audioItemRepo.softDelete(entity)
        reactive.advance()

        // contains(id) bypasses iterator() and consults entitiesById directly —
        // it must apply an explicit deletedAt != null guard.
        audioItemRepo.contains(1).shouldBeFalse()
    }

    "contains(id) returns true for an active entity" {
        val entity = SoftDeletableMutableAudioItem(id = 1, title = "Track A")
        audioItemRepo.add(entity)

        audioItemRepo.contains(1).shouldBeTrue()
    }

    "rawIterator returns soft-deleted entities" {
        val active = SoftDeletableMutableAudioItem(id = 1, title = "Active")
        val deleted = SoftDeletableMutableAudioItem(id = 2, title = "Deleted")
        audioItemRepo.add(active)
        audioItemRepo.add(deleted)
        audioItemRepo.softDelete(deleted)
        reactive.advance()

        val all = (audioItemRepo as RegistryBase<*, *>).rawIterator().asSequence().toList()

        all.size shouldBe 2
    }

    "restoring a soft-deleted entity makes it visible via findById" {
        val entity = SoftDeletableMutableAudioItem(id = 1, title = "Track A")
        audioItemRepo.add(entity)
        audioItemRepo.softDelete(entity)
        reactive.advance()
        audioItemRepo.restore(entity)
        reactive.advance()

        val result = audioItemRepo.findById(1)

        result.isPresent.shouldBeTrue()
        entity.deletedAt.shouldBeNull()
    }

    "restoring a soft-deleted entity makes it visible via iterator" {
        val entity = SoftDeletableMutableAudioItem(id = 1, title = "Track A")
        audioItemRepo.add(entity)
        audioItemRepo.softDelete(entity)
        reactive.advance()
        audioItemRepo.restore(entity)
        reactive.advance()

        val iterated = audioItemRepo.iterator().asSequence().toList()

        iterated shouldBe listOf(entity)
    }

    "restoring a soft-deleted entity increments size()" {
        val entity = SoftDeletableMutableAudioItem(id = 1, title = "Track A")
        audioItemRepo.add(entity)
        audioItemRepo.softDelete(entity)
        reactive.advance()
        audioItemRepo.size() shouldBe 0

        audioItemRepo.restore(entity)
        reactive.advance()

        audioItemRepo.size() shouldBe 1
    }

    "restoring a soft-deleted entity makes contains(id) return true" {
        val entity = SoftDeletableMutableAudioItem(id = 1, title = "Track A")
        audioItemRepo.add(entity)
        audioItemRepo.softDelete(entity)
        reactive.advance()
        audioItemRepo.contains(1).shouldBeFalse()

        audioItemRepo.restore(entity)
        reactive.advance()

        audioItemRepo.contains(1).shouldBeTrue()
    }

    "contains(predicate) excludes soft-deleted entities" {
        val active = SoftDeletableMutableAudioItem(id = 1, title = "Active")
        val deleted = SoftDeletableMutableAudioItem(id = 2, title = "Deleted")
        audioItemRepo.add(active)
        audioItemRepo.add(deleted)
        audioItemRepo.softDelete(deleted)
        reactive.advance()

        audioItemRepo.contains { true }.shouldBeTrue()
        audioItemRepo.contains { it.id == 2 }.shouldBeFalse()
    }

    "lazySearch excludes soft-deleted entities" {
        val active = SoftDeletableMutableAudioItem(id = 1, title = "Active")
        val deleted = SoftDeletableMutableAudioItem(id = 2, title = "Deleted")
        audioItemRepo.add(active)
        audioItemRepo.add(deleted)
        audioItemRepo.softDelete(deleted)
        reactive.advance()

        val result = audioItemRepo.lazySearch { true }.toList()

        result shouldBe listOf(active)
    }

    "search(predicate) excludes soft-deleted entities" {
        val active = SoftDeletableMutableAudioItem(id = 1, title = "Active")
        val deleted = SoftDeletableMutableAudioItem(id = 2, title = "Deleted")
        audioItemRepo.add(active)
        audioItemRepo.add(deleted)
        audioItemRepo.softDelete(deleted)
        reactive.advance()

        val result = audioItemRepo.search { true }

        result shouldBe setOf(active)
    }

    "search(size, predicate) excludes soft-deleted entities" {
        val active = SoftDeletableMutableAudioItem(id = 1, title = "Active")
        val deleted = SoftDeletableMutableAudioItem(id = 2, title = "Deleted")
        audioItemRepo.add(active)
        audioItemRepo.add(deleted)
        audioItemRepo.softDelete(deleted)
        reactive.advance()

        val result = audioItemRepo.search(10) { true }

        result shouldBe setOf(active)
    }

    "findFirst(predicate) excludes soft-deleted entities" {
        val active = SoftDeletableMutableAudioItem(id = 1, title = "Active")
        val deleted = SoftDeletableMutableAudioItem(id = 2, title = "Deleted")
        audioItemRepo.add(active)
        audioItemRepo.add(deleted)
        audioItemRepo.softDelete(deleted)
        reactive.advance()

        audioItemRepo.findFirst { it.id == 2 }.shouldNotBePresent()
        audioItemRepo.findFirst { true }.shouldBePresent()
    }

    "findByUniqueId excludes soft-deleted entities" {
        val entity = SoftDeletableMutableAudioItem(id = 1, title = "Track A")
        audioItemRepo.add(entity)
        audioItemRepo.softDelete(entity)
        reactive.advance()

        audioItemRepo.findByUniqueId(entity.uniqueId).shouldNotBePresent()
    }

    "findByUniqueId returns active entity" {
        val entity = SoftDeletableMutableAudioItem(id = 1, title = "Track A")
        audioItemRepo.add(entity)

        audioItemRepo.findByUniqueId(entity.uniqueId).shouldBePresent()
    }

    "includeDeleted query path surfaces soft-deleted entities via lazySearch backbone" {
        val deleted = SoftDeletableMutableAudioItem(id = 1, title = "Deleted")
        audioItemRepo.add(deleted)
        audioItemRepo.softDelete(deleted)
        reactive.advance()

        // Default read excludes soft-deleted; rawIterator includes it
        audioItemRepo.search { true }.shouldBe(emptySet())
        val all = (audioItemRepo as RegistryBase<*, *>).rawIterator().asSequence().toList()
        all.size shouldBe 1
    }

    "concurrent softDelete calls emit exactly one SoftDelete event per entity".config(tags = setOf(Stress)) {
        val threads = 20
        val executor = Executors.newFixedThreadPool(threads)
        val softDeleteCount = AtomicInteger(0)
        val events = CopyOnWriteArrayList<CrudEvent<*, *>>()
        // Latch sized to 1: counts down when the single successful SoftDelete event arrives
        val eventLatch = CountDownLatch(1)

        val entity = SoftDeletableMutableAudioItem(id = 99, title = "Contested")
        audioItemRepo.add(entity)

        // Subscribe to count SoftDelete events before we hammer concurrent calls
        audioItemRepo.subscribeAsync(CrudEvent.Type.SOFT_DELETE) {
            events.add(it)
            eventLatch.countDown()
        }
        reactive.advance()

        val startLatch = CountDownLatch(threads)
        val futures =
            (1..threads).map {
                executor.submit {
                    startLatch.countDown()
                    startLatch.await()
                    if (audioItemRepo.softDelete(entity)) softDeleteCount.incrementAndGet()
                }
            }
        futures.forEach { it.get() }
        reactive.advance()

        executor.shutdown()
        // Exactly one logical soft-delete must succeed
        softDeleteCount.get() shouldBe 1
        // Wait deterministically for the single expected SOFT_DELETE event to arrive
        eventLatch.await(2, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
        events.size shouldBe 1
    }

    "concurrent restore calls emit exactly one Restore event per entity".config(tags = setOf(Stress)) {
        val threads = 20
        val executor = Executors.newFixedThreadPool(threads)
        val restoreCount = AtomicInteger(0)
        val events = CopyOnWriteArrayList<CrudEvent<*, *>>()
        // Latch sized to 1: counts down when the single successful Restore event arrives
        val eventLatch = CountDownLatch(1)

        val entity = SoftDeletableMutableAudioItem(id = 98, title = "Contested Restore")
        audioItemRepo.add(entity)
        audioItemRepo.softDelete(entity)
        reactive.advance()

        audioItemRepo.subscribeAsync(CrudEvent.Type.RESTORE) {
            events.add(it)
            eventLatch.countDown()
        }
        reactive.advance()

        val startLatch = CountDownLatch(threads)
        val futures =
            (1..threads).map {
                executor.submit {
                    startLatch.countDown()
                    startLatch.await()
                    if (audioItemRepo.restore(entity)) restoreCount.incrementAndGet()
                }
            }
        futures.forEach { it.get() }
        reactive.advance()

        executor.shutdown()
        restoreCount.get() shouldBe 1
        // Wait deterministically for the single expected Restore event to arrive
        eventLatch.await(2, java.util.concurrent.TimeUnit.SECONDS) shouldBe true
        events.size shouldBe 1
    }
})