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

import net.transgressoft.lirp.event.StandardCrudEvent
import net.transgressoft.lirp.testing.ReactiveScopeSerialization
import net.transgressoft.lirp.testing.Stress
import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Tests for [RegistryProjectionMap], verifying registry-source grouping behavior, incremental
 * updates via CrudEvent subscription, reverse-index re-bucketing on key change, soft-delete
 * filtering, onChange callback, sorted key ordering, and concurrent CME-free iteration.
 *
 * Update events are fired manually via `emitAsync` on the repository, mirroring the behaviour
 * of persistent repositories that subscribe to entity mutations internally.
 */
@DisplayName("RegistryProjectionMap")
internal class RegistryProjectionMapTest : StringSpec({

    val reactive = reactiveScope()

    lateinit var ctx: LirpContext
    lateinit var trackRepo: AudioItemVolatileRepository

    beforeEach {
        ctx = LirpContext()
        trackRepo = AudioItemVolatileRepository(ctx)
    }

    afterEach {
        ctx.close()
    }

    "builds initial state from registry on first access" {
        trackRepo.create(1, "Jazz Intro", "Jazz")
        trackRepo.create(2, "Jazz Outro", "Jazz")
        trackRepo.create(3, "Rock Anthem", "Rock")

        val projection = registryProjectionMap(trackRepo) { it.albumName }

        projection.size shouldBe 2
        projection["Jazz"]!!.size shouldBe 2
        projection["Rock"]!!.size shouldBe 1
    }

    "skips soft-deleted entities during lazy seed" {
        val t1 =
            SoftDeletableMutableAudioItem(1, "Deleted Track", "Jazz").also {
                it.deletedAt = Instant.now()
                trackRepo.add(it)
            }
        reactive.advance()
        trackRepo.create(2, "Active Track", "Jazz")
        reactive.advance()

        val projection = registryProjectionMap(trackRepo) { it.albumName }

        projection["Jazz"]!!.size shouldBe 1
        projection["Jazz"]!!.none { it.id == t1.id } shouldBe true
    }

    "adds entity to correct bucket on Create" {
        val projection = registryProjectionMap(trackRepo) { it.albumName }
        projection.size shouldBe 0

        trackRepo.create(1, "New Track", "Classical")
        reactive.advance()

        projection["Classical"]!!.size shouldBe 1
    }

    "removes entity from bucket on Delete" {
        trackRepo.create(1, "Track A", "Pop")
        trackRepo.create(2, "Track B", "Pop")
        val trackC = trackRepo.create(3, "Track C", "Rock")

        val projection = registryProjectionMap(trackRepo) { it.albumName }
        projection["Pop"]!!.size shouldBe 2

        trackRepo.remove(trackC)
        reactive.advance()

        projection.containsKey("Rock") shouldBe false
        projection.size shouldBe 1
    }

    "removes empty bucket key after last entity deleted" {
        val t1 = trackRepo.create(1, "Track A", "Blues")
        trackRepo.create(2, "Track B", "Jazz")

        val projection = registryProjectionMap(trackRepo) { it.albumName }
        projection.containsKey("Blues") shouldBe true

        trackRepo.remove(t1)
        reactive.advance()

        projection.containsKey("Blues") shouldBe false
        projection.size shouldBe 1
    }

    "replaces entity in bucket on Update when key is unchanged" {
        trackRepo.create(1, "Old Title", "Rock")

        val projection = registryProjectionMap(trackRepo) { it.albumName }
        projection["Rock"]!!.first().title shouldBe "Old Title"

        // Use distinct entity objects so handleReplaceInBucket detects the change
        val oldSnapshot = MutableAudioItem(1, "Old Title", "Rock")
        val updatedEntity = MutableAudioItem(1, "New Title", "Rock")
        trackRepo.emitAsync(StandardCrudEvent.Update(updatedEntity, oldSnapshot))
        reactive.advance()

        projection["Rock"]!!.size shouldBe 1
        projection["Rock"]!!.first().title shouldBe "New Title"
    }

    "re-buckets entity via reverse index when key changes on Update" {
        trackRepo.create(1, "Track A", "Jazz")
        trackRepo.create(2, "Track B", "Rock")

        val projection = registryProjectionMap(trackRepo) { it.albumName }
        projection["Jazz"]!!.size shouldBe 1
        projection["Rock"]!!.size shouldBe 1

        // Use distinct entity objects: old key was Jazz, new key is Rock
        val oldSnapshot = MutableAudioItem(1, "Track A", "Jazz")
        val updatedEntity = MutableAudioItem(1, "Track A", "Rock")
        trackRepo.emitAsync(StandardCrudEvent.Update(updatedEntity, oldSnapshot))
        reactive.advance()

        projection.containsKey("Jazz") shouldBe false
        projection["Rock"]!!.size shouldBe 2
    }

    "removes entity from bucket on Update when deletedAt is set" {
        val t1 =
            SoftDeletableMutableAudioItem(1, "Track A", "Jazz").also {
                trackRepo.add(it)
            }
        reactive.advance()
        trackRepo.create(2, "Track B", "Jazz")
        reactive.advance()

        val projection = registryProjectionMap(trackRepo) { it.albumName }
        projection["Jazz"]!!.size shouldBe 2

        // Soft-delete: set deletedAt and emit Update event
        val oldSnapshot = t1.clone()
        t1.deletedAt = Instant.now()
        trackRepo.emitAsync(StandardCrudEvent.Update(t1, oldSnapshot))
        reactive.advance()

        projection["Jazz"]!!.size shouldBe 1
        projection["Jazz"]!!.none { it.id == t1.id } shouldBe true
    }

    "restores entity to bucket on Update when deletedAt is cleared" {
        val t1 =
            SoftDeletableMutableAudioItem(1, "Track A", "Jazz").also {
                trackRepo.add(it)
            }
        reactive.advance()

        val projection = registryProjectionMap(trackRepo) { it.albumName }
        projection["Jazz"]!!.size shouldBe 1

        // Soft-delete removes it from its bucket and drops it from the reverse index
        val activeSnapshot = t1.clone()
        t1.deletedAt = Instant.now()
        trackRepo.emitAsync(StandardCrudEvent.Update(t1, activeSnapshot))
        reactive.advance()
        projection.containsKey("Jazz") shouldBe false

        // Clearing deletedAt on a later Update restores it (oldKey is absent → treated as an add)
        val deletedSnapshot = t1.clone()
        t1.deletedAt = null
        trackRepo.emitAsync(StandardCrudEvent.Update(t1, deletedSnapshot))
        reactive.advance()

        projection["Jazz"]!!.size shouldBe 1
        projection["Jazz"]!!.first().id shouldBe t1.id
    }

    "exposes read-only accessors consistent with bucket state" {
        trackRepo.create(1, "Track A", "Jazz")
        trackRepo.create(2, "Track B", "Rock")
        val projection = registryProjectionMap(trackRepo) { it.albumName }

        projection.isEmpty() shouldBe false
        projection.containsKey("Jazz") shouldBe true
        projection.containsKey("Pop") shouldBe false
        projection.containsValue(projection["Jazz"]!!) shouldBe true
        projection.entries.size shouldBe 2
        projection.values.sumOf { it.size } shouldBe 2
    }

    "ignores Delete for an entity that was never bucketed" {
        trackRepo.create(1, "Track A", "Jazz")
        val projection = registryProjectionMap(trackRepo) { it.albumName }
        projection["Jazz"]!!.size shouldBe 1

        // Entity absent from the reverse index falls back to a full-scan removal that finds nothing.
        trackRepo.emitAsync(StandardCrudEvent.Delete(MutableAudioItem(99, "Ghost", "Rock")))
        reactive.advance()

        projection["Jazz"]!!.size shouldBe 1
        projection.containsKey("Rock") shouldBe false
    }

    "fires onChange after Create" {
        val projection = registryProjectionMap(trackRepo) { it.albumName }
        projection.size shouldBe 0

        var callbackSnapshot: Map<String, List<AudioItem>>? = null
        projection.onChange = { callbackSnapshot = it }

        trackRepo.create(1, "Track A", "Blues")
        reactive.advance()

        callbackSnapshot shouldNotBe null
        callbackSnapshot!!.containsKey("Blues") shouldBe true
    }

    "fires onChange after Delete" {
        trackRepo.create(1, "Track A", "Blues")
        reactive.advance()
        val t2 = trackRepo.create(2, "Track B", "Blues")
        reactive.advance()

        val projection = registryProjectionMap(trackRepo) { it.albumName }
        projection.size shouldBe 1

        var callbackCount = 0
        projection.onChange = { callbackCount++ }

        trackRepo.remove(t2)
        reactive.advance()

        callbackCount shouldBe 1
    }

    "fires onChange after Update" {
        trackRepo.create(1, "Track A", "Jazz")
        reactive.advance()

        val projection = registryProjectionMap(trackRepo) { it.albumName }
        projection.size shouldBe 1

        var callbackCount = 0
        projection.onChange = { callbackCount++ }

        // Create a fresh entity object with same id but updated title — distinct object
        // reference so handleReplaceInBucket detects an actual change
        val oldSnapshot = MutableAudioItem(1, "Track A", "Jazz")
        val updatedEntity = MutableAudioItem(1, "Track A Updated", "Jazz")
        trackRepo.emitAsync(StandardCrudEvent.Update(updatedEntity, oldSnapshot))
        reactive.advance()

        callbackCount shouldBe 1
        projection["Jazz"]!!.first().title shouldBe "Track A Updated"
    }

    "keys are in natural sorted order" {
        trackRepo.create(1, "A", "Rock")
        trackRepo.create(2, "B", "Classical")
        trackRepo.create(3, "C", "Blues")
        trackRepo.create(4, "D", "Jazz")

        val projection = registryProjectionMap(trackRepo) { it.albumName }

        projection.keys.toList() shouldContainExactly listOf("Blues", "Classical", "Jazz", "Rock")
    }

    "reflects writer state after concurrent creates" {
        val totalItems = 100
        val albumNames = listOf("Alpha", "Bravo", "Charlie", "Delta")

        val seedItems =
            (1..totalItems).map { i ->
                MutableAudioItem(i, "Track-$i", albumNames[i % albumNames.size])
            }

        val projection = registryProjectionMap(trackRepo) { it.albumName }
        projection.size shouldBe 0

        val executor = Executors.newSingleThreadExecutor()
        val latch = CountDownLatch(totalItems)

        val readerJob =
            reactive.scope.launch(Dispatchers.Default) {
                while (latch.count > 0L) {
                    projection.keys.toList()
                    projection.entries.forEach { it.value.size }
                }
            }

        try {
            for (item in seedItems) {
                executor.submit {
                    trackRepo.add(item)
                    latch.countDown()
                }
            }
            latch.await(10, TimeUnit.SECONDS) shouldBe true
            reactive.advance()

            projection.values.sumOf { it.size } shouldBe totalItems
            projection.keys.toList() shouldContainExactly albumNames.sorted()
        } finally {
            readerJob.cancel()
            executor.shutdownNow()
        }
    }

    "iterates without ConcurrentModificationException under concurrent add and remove stress"
        .config(tags = setOf(Stress)) {
            extension(ReactiveScopeSerialization)

            val seedSize = 50
            val mutations = 2000
            val readerIterations = 500

            val seedItems =
                (1..seedSize).map { i ->
                    MutableAudioItem(i, "Track-$i", "Album-${i % 5}")
                }
            seedItems.forEach { trackRepo.add(it) }

            val projection = registryProjectionMap(trackRepo) { it.albumName }
            // Trigger init before writers start
            projection.size shouldBe 5

            shouldNotThrowAny {
                val writerJob =
                    launch(Dispatchers.Default) {
                        repeat(mutations) { i ->
                            val item = MutableAudioItem(seedSize + i + 1, "Extra-$i", "Album-${i % 5}")
                            trackRepo.add(item)
                            trackRepo.remove(item)
                        }
                    }

                val readerJob =
                    launch(Dispatchers.Default) {
                        repeat(readerIterations) {
                            projection.keys.toList()
                            projection.entries.forEach { it.value.size }
                        }
                    }

                writerJob.join()
                readerJob.join()
            }
        }
})