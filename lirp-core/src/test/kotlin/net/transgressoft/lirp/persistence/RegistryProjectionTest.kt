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
import net.transgressoft.lirp.persistence.projection.RegistryProjection
import net.transgressoft.lirp.persistence.projection.registryMultiKeyProjection
import net.transgressoft.lirp.persistence.projection.registryProjection
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
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Tests for [RegistryProjection], verifying registry-source grouping behavior, incremental
 * updates via CrudEvent subscription, reverse-index re-bucketing on key change, soft-delete
 * filtering, onChange callback, sorted key ordering, and concurrent CME-free iteration.
 *
 * Update events are fired manually via `emitAsync` on the repository, mirroring the behaviour
 * of persistent repositories that subscribe to entity mutations internally.
 */
@DisplayName("RegistryProjection")
internal class RegistryProjectionTest : StringSpec({

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

        val projection = registryProjection(trackRepo, { it.albumName })

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

        val projection = registryProjection(trackRepo, { it.albumName })

        projection["Jazz"]!!.size shouldBe 1
        projection["Jazz"]!!.none { it.id == t1.id } shouldBe true
    }

    "adds entity to correct bucket on Create" {
        val projection = registryProjection(trackRepo, { it.albumName })
        projection.size shouldBe 0

        trackRepo.create(1, "New Track", "Classical")
        reactive.advance()

        projection["Classical"]!!.size shouldBe 1
    }

    "removes entity from bucket on Delete" {
        trackRepo.create(1, "Track A", "Pop")
        trackRepo.create(2, "Track B", "Pop")
        val trackC = trackRepo.create(3, "Track C", "Rock")

        val projection = registryProjection(trackRepo, { it.albumName })
        projection["Pop"]!!.size shouldBe 2

        trackRepo.remove(trackC)
        reactive.advance()

        projection.containsKey("Rock") shouldBe false
        projection.size shouldBe 1
    }

    "removes empty bucket key after last entity deleted" {
        val t1 = trackRepo.create(1, "Track A", "Blues")
        trackRepo.create(2, "Track B", "Jazz")

        val projection = registryProjection(trackRepo, { it.albumName })
        projection.containsKey("Blues") shouldBe true

        trackRepo.remove(t1)
        reactive.advance()

        projection.containsKey("Blues") shouldBe false
        projection.size shouldBe 1
    }

    "replaces entity in bucket on Update when key is unchanged" {
        trackRepo.create(1, "Old Title", "Rock")

        val projection = registryProjection(trackRepo, { it.albumName })
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

        val projection = registryProjection(trackRepo, { it.albumName })
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

        val projection = registryProjection(trackRepo, { it.albumName })
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

        val projection = registryProjection(trackRepo, { it.albumName })
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
        val projection = registryProjection(trackRepo, { it.albumName })

        projection.isEmpty() shouldBe false
        projection.containsKey("Jazz") shouldBe true
        projection.containsKey("Pop") shouldBe false
        projection.containsValue(projection["Jazz"]!!) shouldBe true
        projection.entries.size shouldBe 2
        projection.values.sumOf { it.size } shouldBe 2
    }

    "ignores Delete for an entity that was never bucketed" {
        trackRepo.create(1, "Track A", "Jazz")
        val projection = registryProjection(trackRepo, { it.albumName })
        projection["Jazz"]!!.size shouldBe 1

        // Entity absent from the reverse index falls back to a full-scan removal that finds nothing.
        trackRepo.emitAsync(StandardCrudEvent.Delete(MutableAudioItem(99, "Ghost", "Rock")))
        reactive.advance()

        projection["Jazz"]!!.size shouldBe 1
        projection.containsKey("Rock") shouldBe false
    }

    "skips a redundant Create for an already-bucketed entity without duplicating it" {
        trackRepo.create(1, "Track A", "Jazz")
        val projection = registryProjection(trackRepo, { it.albumName })
        projection["Jazz"]!!.size shouldBe 1

        // A redundant Create for an id already held in a bucket (as happens when the seed iterator and
        // a buffered seed-window create both reference the same entity) must not add it a second time.
        trackRepo.emitAsync(StandardCrudEvent.Create(MutableAudioItem(1, "Track A", "Jazz")))
        reactive.advance()

        projection["Jazz"]!!.size shouldBe 1
    }

    "fires onChange after Create" {
        val projection = registryProjection(trackRepo, { it.albumName })
        projection.size shouldBe 0

        var callbackSnapshot: Map<String, List<AudioItem>>? = null
        projection.addOnChangeListener { callbackSnapshot = it }

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

        val projection = registryProjection(trackRepo, { it.albumName })
        projection.size shouldBe 1

        var callbackCount = 0
        projection.addOnChangeListener { callbackCount++ }

        trackRepo.remove(t2)
        reactive.advance()

        callbackCount shouldBe 1
    }

    "fires onChange after Update" {
        trackRepo.create(1, "Track A", "Jazz")
        reactive.advance()

        val projection = registryProjection(trackRepo, { it.albumName })
        projection.size shouldBe 1

        var callbackCount = 0
        projection.addOnChangeListener { callbackCount++ }

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

        val projection = registryProjection(trackRepo, { it.albumName })

        projection.keys.toList() shouldContainExactly listOf("Blues", "Classical", "Jazz", "Rock")
    }

    "reflects writer state after concurrent creates" {
        val totalItems = 100
        val albumNames = listOf("Alpha", "Bravo", "Charlie", "Delta")

        val seedItems =
            (1..totalItems).map { i ->
                MutableAudioItem(i, "Track-$i", albumNames[i % albumNames.size])
            }

        val projection = registryProjection(trackRepo, { it.albumName })
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

    "registryProjection with valueTransform produces Map<PK, V> with correct transformed values for each bucket" {
        trackRepo.create(1, "Jazz Intro", "Jazz")
        trackRepo.create(2, "Jazz Outro", "Jazz")
        trackRepo.create(3, "Rock Anthem", "Rock")

        val transformed =
            registryProjection<Int, String, AudioItem, String>(trackRepo, { it.albumName }) { pk, items ->
                "$pk:${items.size}"
            }

        transformed["Jazz"] shouldBe "Jazz:2"
        transformed["Rock"] shouldBe "Rock:1"
        transformed.size shouldBe 2
    }

    "registryProjection with valueTransform recomputes only the affected bucket on a delta" {
        trackRepo.create(1, "Track A", "Jazz")
        trackRepo.create(2, "Track B", "Rock")

        var jazzTransformCount = 0
        var rockTransformCount = 0
        val transformed =
            registryProjection<Int, String, AudioItem, String>(trackRepo, { it.albumName }) { pk, items ->
                if (pk == "Jazz") jazzTransformCount++ else rockTransformCount++
                "$pk:${items.size}"
            }

        // Trigger initialization
        transformed["Jazz"] shouldBe "Jazz:1"
        transformed["Rock"] shouldBe "Rock:1"
        val jazzCountAfterInit = jazzTransformCount
        val rockCountAfterInit = rockTransformCount

        // Add a Jazz track via registry event — only Jazz bucket should be recomputed
        trackRepo.create(3, "Track C", "Jazz")
        reactive.advance()

        transformed["Jazz"] shouldBe "Jazz:2"
        jazzTransformCount shouldBe jazzCountAfterInit + 1
        rockTransformCount shouldBe rockCountAfterInit
    }

    "registryProjection with valueTransform removes emptied bucket key from transformed view on Delete" {
        trackRepo.create(1, "Track A", "Jazz")
        val trackRock = trackRepo.create(2, "Track B", "Rock")

        val transformed =
            registryProjection<Int, String, AudioItem, String>(trackRepo, { it.albumName }) { pk, items ->
                "$pk:${items.size}"
            }

        transformed.containsKey("Rock") shouldBe true

        trackRepo.remove(trackRock)
        reactive.advance()

        transformed.containsKey("Rock") shouldBe false
        transformed.size shouldBe 1
        transformed["Jazz"] shouldBe "Jazz:1"
    }

    "close stops the projection from reflecting subsequent registry mutations" {
        trackRepo.create(1, "Track A", "Jazz")
        val projection = registryProjection(trackRepo, { it.albumName })
        // Force lazy init so the subscription is live before closing.
        projection["Jazz"]!!.size shouldBe 1

        projection.close()

        // Mutations after close must not reach the cancelled subscription.
        trackRepo.create(2, "Track B", "Jazz")
        reactive.advance()
        val updatedTrackC = trackRepo.create(3, "Track C", "Rock")
        reactive.advance()
        trackRepo.remove(updatedTrackC)
        reactive.advance()

        projection["Jazz"]!!.size shouldBe 1
        projection.containsKey("Rock") shouldBe false
    }

    "close before first access is a no-op and does not throw" {
        val projection = registryProjection(trackRepo, { it.albumName })

        shouldNotThrowAny { projection.close() }
    }

    "registryProjection with valueTransform exposes close that stops reflecting registry mutations" {
        trackRepo.create(1, "Track A", "Jazz")
        val transformed =
            registryProjection<Int, String, AudioItem, String>(trackRepo, { it.albumName }) { pk, items ->
                "$pk:${items.size}"
            }
        transformed["Jazz"] shouldBe "Jazz:1"

        transformed.close()

        trackRepo.create(2, "Track B", "Jazz")
        reactive.advance()
        trackRepo.create(3, "Rock Anthem", "Rock")
        reactive.advance()

        transformed["Jazz"] shouldBe "Jazz:1"
        transformed.containsKey("Rock") shouldBe false
    }

    "registryMultiKeyProjection with valueTransform exposes close that stops reflecting registry mutations" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        multiKeyRepo.create(1, "Track A", setOf("Rock", "Jazz"))
        val transformed =
            registryMultiKeyProjection<Int, String, MutableMultiKeyAudioItem, String>(multiKeyRepo, { it.genres }) { pk, items ->
                "$pk:${items.size}"
            }
        transformed["Rock"] shouldBe "Rock:1"

        transformed.close()

        multiKeyRepo.create(2, "Track B", setOf("Indie"))
        reactive.advance()

        transformed.containsKey("Indie") shouldBe false
    }

    "MultiKeyRegistryProjection close stops the projection from reflecting subsequent mutations" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        multiKeyRepo.create(1, "Track A", setOf("Rock", "Jazz"))
        val projection = registryMultiKeyProjection(multiKeyRepo, { it.genres })
        // Force lazy init so the subscription is live before closing.
        projection["Rock"]!!.size shouldBe 1

        projection.close()

        multiKeyRepo.create(2, "Track B", setOf("Indie"))
        reactive.advance()

        projection.containsKey("Indie") shouldBe false
        projection["Rock"]!!.size shouldBe 1
    }

    "MultiKeyRegistryProjection close before first access is a no-op and does not throw" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        val projection = registryMultiKeyProjection(multiKeyRepo, { it.genres })

        shouldNotThrowAny { projection.close() }
    }

    "MultiKeyRegistryProjection leaves unchanged keys with identical content untouched on a key-set shrink Update" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        val item = multiKeyRepo.create(1, "Track A", setOf("Rock", "Jazz", "Indie"))
        reactive.advance()

        val projection = registryMultiKeyProjection(multiKeyRepo, { it.genres })
        projection["Rock"]!!.size shouldBe 1
        projection["Jazz"]!!.size shouldBe 1
        projection["Indie"]!!.size shouldBe 1

        // Shrink {Rock, Jazz, Indie} → {Rock}: Jazz and Indie removed, Rock unchanged with identical content.
        // The unchanged-Rock branch routes through replaceInBucketSilent and hits its no-op (already-equal)
        // return because the entity object is the same instance with no content change.
        val oldSnapshot = item.clone()
        item.genres = setOf("Rock")
        multiKeyRepo.emitAsync(StandardCrudEvent.Update(item, oldSnapshot))
        reactive.advance()

        projection["Rock"]!!.size shouldBe 1
        projection.containsKey("Jazz") shouldBe false
        projection.containsKey("Indie") shouldBe false
        projection.size shouldBe 1
    }

    "MultiKeyRegistryProjection replaces content in unchanged keys while shrinking the key set on Update" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        val item = multiKeyRepo.create(1, "Old Title", setOf("Rock", "Jazz"))
        reactive.advance()

        val projection = registryMultiKeyProjection(multiKeyRepo, { it.genres })
        projection["Rock"]!!.first().title shouldBe "Old Title"

        // Change a non-key field AND shrink the key set: Rock is unchanged (replace fires), Jazz removed.
        val oldSnapshot = item.clone()
        item.title = "New Title"
        item.genres = setOf("Rock")
        multiKeyRepo.emitAsync(StandardCrudEvent.Update(item, oldSnapshot))
        reactive.advance()

        projection["Rock"]!!.size shouldBe 1
        projection["Rock"]!!.first().title shouldBe "New Title"
        projection.containsKey("Jazz") shouldBe false
    }

    "registryMultiKeyProjection with valueTransform recomputes each affected key once per key-set update delta" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        val item = multiKeyRepo.create(1, "Track A", setOf("Rock", "Jazz"))
        reactive.advance()

        val transformCounts = mutableMapOf<String, Int>()
        val transformed =
            registryMultiKeyProjection<Int, String, MutableMultiKeyAudioItem, String>(
                multiKeyRepo,
                { it.genres }
            ) { pk, items ->
                transformCounts[pk] = (transformCounts[pk] ?: 0) + 1
                "$pk:${items.size}"
            }

        // Force initialization — Rock and Jazz computed once each.
        transformed["Rock"] shouldBe "Rock:1"
        transformed["Jazz"] shouldBe "Jazz:1"
        val countsAfterInit = transformCounts.toMap()

        // Single key-set update {Rock, Jazz} → {Rock, Indie}: Indie added, Jazz removed, Rock unchanged.
        val oldSnapshot = item.clone()
        item.genres = setOf("Rock", "Indie")
        multiKeyRepo.emitAsync(StandardCrudEvent.Update(item, oldSnapshot))
        reactive.advance()

        transformed["Indie"] shouldBe "Indie:1"
        transformed.containsKey("Jazz") shouldBe false
        // Indie recomputed exactly once for this delta; Rock unchanged (no recompute since its bucket content
        // did not change); Jazz removed (no recompute over an empty bucket).
        transformCounts["Indie"] shouldBe 1
        transformCounts["Rock"] shouldBe countsAfterInit["Rock"]
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

            val projection = registryProjection(trackRepo, { it.albumName })
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

    // -------------------------------------------------------------------------
    // Multi-key projection — registry source
    // -------------------------------------------------------------------------

    "MultiKeyRegistryProjection places entity in every genre bucket on Create" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        multiKeyRepo.create(1, "Double-Genre Track", setOf("Rock", "Jazz"))

        val projection = registryMultiKeyProjection(multiKeyRepo, { it.genres })

        projection.size shouldBe 2
        projection["Rock"]!!.size shouldBe 1
        projection["Jazz"]!!.size shouldBe 1
        projection["Rock"]!!.first().id shouldBe 1
        projection["Jazz"]!!.first().id shouldBe 1
    }

    "MultiKeyRegistryProjection adds new genre bucket and removes stale bucket on key-set Update" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        val item = multiKeyRepo.create(1, "Track A", setOf("Rock", "Jazz"))
        val anotherItem = multiKeyRepo.create(2, "Track B", setOf("Rock"))
        reactive.advance()

        val projection = registryMultiKeyProjection(multiKeyRepo, { it.genres })
        // Initial state: Rock=[item, anotherItem], Jazz=[item]
        projection["Rock"]!!.size shouldBe 2
        projection["Jazz"]!!.size shouldBe 1

        // Update item's genres from {Rock, Jazz} to {Rock, Indie}
        val oldSnapshot = item.clone()
        item.genres = setOf("Rock", "Indie")
        multiKeyRepo.emitAsync(StandardCrudEvent.Update(item, oldSnapshot))
        reactive.advance()

        // Indie added, Jazz removed, Rock still present
        projection["Indie"]!!.size shouldBe 1
        projection.containsKey("Jazz") shouldBe false
        projection["Rock"]!!.size shouldBe 2 // item and anotherItem still in Rock
        projection["Rock"]!!.any { it.id == item.id } shouldBe true
    }

    "MultiKeyRegistryProjection skips a redundant Create for an already-bucketed entity without duplicating it" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        multiKeyRepo.create(1, "Track A", setOf("Rock", "Jazz"))
        val projection = registryMultiKeyProjection(multiKeyRepo, { it.genres })
        projection["Rock"]!!.size shouldBe 1
        projection["Jazz"]!!.size shouldBe 1

        // A redundant Create for an id already held in its buckets must not add it a second time.
        multiKeyRepo.emitAsync(StandardCrudEvent.Create(MutableMultiKeyAudioItem(1, "Track A", setOf("Rock", "Jazz"))))
        reactive.advance()

        projection["Rock"]!!.size shouldBe 1
        projection["Jazz"]!!.size shouldBe 1
    }

    "MultiKeyRegistryProjection places entity in zero buckets when keyExtractor returns empty set" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        multiKeyRepo.create(1, "No-Genre Track", emptySet())
        reactive.advance()

        val projection = registryMultiKeyProjection(multiKeyRepo, { it.genres })

        projection.isEmpty() shouldBe true
    }

    "MultiKeyRegistryProjection removes entity from all buckets on Update to empty genre set" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        val item = multiKeyRepo.create(1, "Track", setOf("Rock", "Jazz"))
        reactive.advance()

        val projection = registryMultiKeyProjection(multiKeyRepo, { it.genres })
        projection["Rock"]!!.size shouldBe 1
        projection["Jazz"]!!.size shouldBe 1

        // Update to empty genres
        val oldSnapshot = item.clone()
        item.genres = emptySet()
        multiKeyRepo.emitAsync(StandardCrudEvent.Update(item, oldSnapshot))
        reactive.advance()

        projection.isEmpty() shouldBe true
    }

    "MultiKeyRegistryProjection deduplicates repeated genres before bucketing" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)

        val projection = registryMultiKeyProjection<Int, String, MutableMultiKeyAudioItem>(multiKeyRepo, { it.genres })
        projection.size shouldBe 0

        val item = multiKeyRepo.create(1, "Track", setOf("Rock", "Jazz"))
        reactive.advance()

        // Emit an Update that has a collection with duplicate genres
        val oldSnapshot = item.clone()
        // Simulate keyExtractor receiving a list with duplicates by creating a custom projection
        // We test duplicate-key dedup via the factory directly: the factory's keyExtractor returns a list with dupes
        val dupeProjection =
            registryMultiKeyProjection<Int, String, MutableMultiKeyAudioItem>(multiKeyRepo, { _ ->
                listOf("Rock", "Rock", "Jazz", "Jazz") // duplicates
            })

        dupeProjection["Rock"]!!.size shouldBe 1 // entity appears once in Rock bucket
        dupeProjection["Jazz"]!!.size shouldBe 1 // entity appears once in Jazz bucket
        dupeProjection.size shouldBe 2
    }

    "MultiKeyRegistryProjection removes soft-deleted entity from ALL genre buckets" {
        val softRepo = SoftDeletableMultiKeyAudioItemRepo(ctx)
        val item = softRepo.create(1, "Multi-Genre Track", setOf("Rock", "Jazz", "Indie"))
        reactive.advance()

        val projection = registryMultiKeyProjection(softRepo, { it.genres })
        projection["Rock"]!!.size shouldBe 1
        projection["Jazz"]!!.size shouldBe 1
        projection["Indie"]!!.size shouldBe 1

        // Soft-delete the entity
        val activeSnapshot = item.clone()
        item.deletedAt = java.time.Instant.now()
        softRepo.emitAsync(StandardCrudEvent.Update(item, activeSnapshot))
        reactive.advance()

        projection.isEmpty() shouldBe true
        projection.containsKey("Rock") shouldBe false
        projection.containsKey("Jazz") shouldBe false
        projection.containsKey("Indie") shouldBe false
    }

    "registryMultiKeyProjection with valueTransform buckets by genre and transforms each bucket" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        multiKeyRepo.create(1, "Track A", setOf("Rock", "Jazz"))
        multiKeyRepo.create(2, "Track B", setOf("Jazz"))
        reactive.advance()

        val transformed =
            registryMultiKeyProjection<Int, String, MutableMultiKeyAudioItem, String>(
                multiKeyRepo,
                { it.genres }
            ) { pk, items ->
                "$pk:${items.size}"
            }

        transformed["Rock"] shouldBe "Rock:1"
        transformed["Jazz"] shouldBe "Jazz:2"
        transformed.size shouldBe 2
        transformed.containsKey("Rock") shouldBe true
        transformed.containsKey("Pop") shouldBe false
        transformed.containsValue("Rock:1") shouldBe true
        transformed.isEmpty() shouldBe false
        transformed.keys.toSet() shouldBe setOf("Rock", "Jazz")
        transformed.values.toSet() shouldBe setOf("Rock:1", "Jazz:2")
    }

    "registryMultiKeyProjection with valueTransform removes emptied genre bucket from transformed view on Delete" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        val item = multiKeyRepo.create(1, "Track A", setOf("Rock"))
        reactive.advance()

        val transformed =
            registryMultiKeyProjection<Int, String, MutableMultiKeyAudioItem, String>(
                multiKeyRepo,
                { it.genres }
            ) { pk, items ->
                "$pk:${items.size}"
            }

        transformed.containsKey("Rock") shouldBe true

        multiKeyRepo.remove(item)
        reactive.advance()

        transformed.containsKey("Rock") shouldBe false
        transformed.isEmpty() shouldBe true
    }

    "registryMultiKeyProjection valueTransform replays current entries as adds when a listener registers" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        multiKeyRepo.create(1, "Track A", setOf("Rock", "Jazz"))
        multiKeyRepo.create(2, "Track B", setOf("Jazz"))
        reactive.advance()

        val transformed =
            registryMultiKeyProjection<Int, String, MutableMultiKeyAudioItem, String>(
                multiKeyRepo,
                { it.genres }
            ) { pk, items -> "$pk:${items.size}" }

        val replayed = mutableMapOf<String, Pair<String?, String?>>()
        transformed.addOnEntriesChangedListener { changes ->
            changes.forEach { replayed[it.key] = it.oldValue to it.newValue }
        }

        // Each current entry is replayed as an add (oldValue == null) so a late subscriber sees full state.
        replayed.keys shouldBe setOf("Rock", "Jazz")
        replayed["Rock"] shouldBe (null to "Rock:1")
        replayed["Jazz"] shouldBe (null to "Jazz:2")
    }

    "registryMultiKeyProjection valueTransform emits add, replace and remove entry changes on deltas" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        multiKeyRepo.create(1, "Track A", setOf("Rock"))
        reactive.advance()

        val transformed =
            registryMultiKeyProjection<Int, String, MutableMultiKeyAudioItem, String>(
                multiKeyRepo,
                { it.genres }
            ) { pk, items -> "$pk:${items.size}" }

        val changesLog = mutableListOf<Triple<String, String?, String?>>()
        transformed.addOnEntriesChangedListener { changes ->
            changes.forEach { changesLog += Triple(it.key, it.oldValue, it.newValue) }
        }
        changesLog.clear() // drop the initial replay of the seeded "Rock" bucket

        val itemB = multiKeyRepo.create(2, "Track B", setOf("Jazz")) // add a new bucket
        reactive.advance()
        multiKeyRepo.create(3, "Track C", setOf("Rock")) // recompute an existing bucket
        reactive.advance()
        multiKeyRepo.remove(itemB) // empty and drop the Jazz bucket
        reactive.advance()

        changesLog shouldContainExactly
            listOf(
                Triple("Jazz", null, "Jazz:1"),
                Triple("Rock", "Rock:1", "Rock:2"),
                Triple("Jazz", "Jazz:1", null)
            )
    }

    "registryMultiKeyProjection valueTransform stops delivering entry changes after the listener handle is closed" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        multiKeyRepo.create(1, "Track A", setOf("Rock"))
        reactive.advance()

        val transformed =
            registryMultiKeyProjection<Int, String, MutableMultiKeyAudioItem, String>(
                multiKeyRepo,
                { it.genres }
            ) { pk, items -> "$pk:${items.size}" }

        val changesLog = mutableListOf<Triple<String, String?, String?>>()
        val handle =
            transformed.addOnEntriesChangedListener { changes ->
                changes.forEach { changesLog += Triple(it.key, it.oldValue, it.newValue) }
            }
        changesLog.clear()

        handle.close()
        multiKeyRepo.create(2, "Track B", setOf("Jazz"))
        reactive.advance()

        changesLog shouldBe emptyList()
    }

    "registryProjection valueTransform replays current entries as adds when a listener registers" {
        trackRepo.create(1, "Track A", "Rock")
        trackRepo.create(2, "Track B", "Jazz")
        trackRepo.create(3, "Track C", "Jazz")
        reactive.advance()

        val transformed =
            registryProjection<Int, String, AudioItem, String>(trackRepo, { it.albumName }) { pk, items ->
                "$pk:${items.size}"
            }

        val replayed = mutableMapOf<String, Pair<String?, String?>>()
        transformed.addOnEntriesChangedListener { changes ->
            changes.forEach { replayed[it.key] = it.oldValue to it.newValue }
        }

        // Each current entry is replayed as an add (oldValue == null) so a late subscriber sees full state.
        replayed.keys shouldBe setOf("Rock", "Jazz")
        replayed["Rock"] shouldBe (null to "Rock:1")
        replayed["Jazz"] shouldBe (null to "Jazz:2")
    }

    "registryProjection valueTransform emits add, replace and remove entry changes on deltas" {
        trackRepo.create(1, "Track A", "Rock")
        reactive.advance()

        val transformed =
            registryProjection<Int, String, AudioItem, String>(trackRepo, { it.albumName }) { pk, items ->
                "$pk:${items.size}"
            }

        val changesLog = mutableListOf<Triple<String, String?, String?>>()
        transformed.addOnEntriesChangedListener { changes ->
            changes.forEach { changesLog += Triple(it.key, it.oldValue, it.newValue) }
        }
        changesLog.clear() // drop the initial replay of the seeded "Rock" bucket

        val itemB = trackRepo.create(2, "Track B", "Jazz") // add a new bucket
        reactive.advance()
        trackRepo.create(3, "Track C", "Rock") // recompute an existing bucket
        reactive.advance()
        trackRepo.remove(itemB) // empty and drop the Jazz bucket
        reactive.advance()

        changesLog shouldContainExactly
            listOf(
                Triple("Jazz", null, "Jazz:1"),
                Triple("Rock", "Rock:1", "Rock:2"),
                Triple("Jazz", "Jazz:1", null)
            )
    }

    "registryProjection valueTransform stops delivering entry changes after the listener handle is closed" {
        trackRepo.create(1, "Track A", "Rock")
        reactive.advance()

        val transformed =
            registryProjection<Int, String, AudioItem, String>(trackRepo, { it.albumName }) { pk, items ->
                "$pk:${items.size}"
            }

        val changesLog = mutableListOf<Triple<String, String?, String?>>()
        val handle =
            transformed.addOnEntriesChangedListener { changes ->
                changes.forEach { changesLog += Triple(it.key, it.oldValue, it.newValue) }
            }
        changesLog.clear()

        handle.close()
        trackRepo.create(2, "Track B", "Jazz")
        reactive.advance()

        changesLog shouldBe emptyList()
    }

    "registryProjection valueTransform delivers deltas to two listeners independently" {
        trackRepo.create(1, "Track A", "Rock")
        reactive.advance()

        val transformed =
            registryProjection<Int, String, AudioItem, String>(trackRepo, { it.albumName }) { pk, items ->
                "$pk:${items.size}"
            }

        val log1 = mutableListOf<Pair<String, String?>>()
        val log2 = mutableListOf<Pair<String, String?>>()
        transformed.addOnEntriesChangedListener { changes ->
            changes.forEach { log1 += it.key to it.newValue }
        }
        transformed.addOnEntriesChangedListener { changes ->
            changes.forEach { log2 += it.key to it.newValue }
        }
        log1.clear()
        log2.clear()

        trackRepo.create(2, "Track B", "Jazz")
        reactive.advance()

        log1.size shouldBe 1
        log1[0] shouldBe ("Jazz" to "Jazz:1")
        log2.size shouldBe 1
        log2[0] shouldBe ("Jazz" to "Jazz:1")
    }

    "registryProjection valueTransform and repository subscribe deliver distinct events without double-delivery and close composition is clean" {
        trackRepo.create(1, "Track A", "Rock")
        reactive.advance()

        val transformed =
            registryProjection<Int, String, AudioItem, String>(trackRepo, { it.albumName }) { pk, items ->
                "$pk:${items.size}"
            }

        val repoEventCount = AtomicInteger(0)
        val projectionDeltaCount = AtomicInteger(0)

        // Repository CrudEvent subscription counts entity-level events
        val repoSubscription = trackRepo.subscribe { repoEventCount.incrementAndGet() }
        // Projection listener counts bucket-level delta batches
        transformed.addOnEntriesChangedListener { _ -> projectionDeltaCount.incrementAndGet() }

        // drop replay from initial listener registration
        projectionDeltaCount.set(0)
        repoEventCount.set(0)

        // perform create/update/remove
        val item2 = trackRepo.create(2, "Track B", "Jazz")
        reactive.advance()
        trackRepo.create(3, "Track C", "Rock")
        reactive.advance()
        trackRepo.remove(item2)
        reactive.advance()

        // Three entity-level events: 3 creates + 1 remove = 4 repo events total minus the initial above = 3
        // Two distinct bucket keys affected: Jazz and Rock (not both on every event)
        // The projection sees bucket deltas, not entity events — counts differ
        repoEventCount.get() shouldBe 3
        // projection fires once per flush (one per reactive.advance()) for affected buckets
        projectionDeltaCount.get() shouldBe 3

        // Close the projection: projection listener must receive no more deltas
        transformed.close()
        projectionDeltaCount.set(0)
        repoEventCount.set(0)

        trackRepo.create(4, "Track D", "Pop")
        reactive.advance()

        // Repository subscription still alive after projection close
        repoEventCount.get() shouldBe 1
        // Projection listener receives nothing after close
        projectionDeltaCount.get() shouldBe 0

        repoSubscription.cancel()
    }

    "registryProjection valueTransform fires no delta when an in-place update leaves the transformed value unchanged" {
        val item = trackRepo.create(1, "Track A", "Rock") as MutableAudioItem
        reactive.advance()

        // The transform ignores the title, so a title-only update recomputes the same value.
        val transformed =
            registryProjection<Int, String, AudioItem, String>(trackRepo, { it.albumName }) { pk, items ->
                "$pk:${items.size}"
            }

        val changesLog = mutableListOf<Triple<String, String?, String?>>()
        transformed.addOnEntriesChangedListener { changes ->
            changes.forEach { changesLog += Triple(it.key, it.oldValue, it.newValue) }
        }
        changesLog.clear()

        val oldSnapshot = item.clone()
        item.title = "Renamed Track"
        trackRepo.emitAsync(StandardCrudEvent.Update(item, oldSnapshot))
        reactive.advance()

        changesLog shouldBe emptyList()
    }

    "registryProjection valueTransform isolates a throwing listener so a second listener still receives the batch" {
        trackRepo.create(1, "Track A", "Rock")
        reactive.advance()

        val transformed =
            registryProjection<Int, String, AudioItem, String>(trackRepo, { it.albumName }) { pk, items ->
                "$pk:${items.size}"
            }

        val secondListenerKeys = mutableListOf<String>()
        transformed.addOnEntriesChangedListener { error("listener boom") }
        transformed.addOnEntriesChangedListener { changes -> secondListenerKeys.addAll(changes.map { it.key }) }
        secondListenerKeys.clear()

        trackRepo.create(2, "Track B", "Jazz")
        reactive.advance()

        secondListenerKeys shouldBe listOf("Jazz")
    }

    "registryProjection valueTransform replays full current state before any subsequent delta on registration" {
        trackRepo.create(1, "Track A", "Rock")
        trackRepo.create(2, "Track B", "Jazz")
        reactive.advance()

        val transformed =
            registryProjection<Int, String, AudioItem, String>(trackRepo, { it.albumName }) { pk, items ->
                "$pk:${items.size}"
            }

        val ordered = mutableListOf<Triple<String, String?, String?>>()
        transformed.addOnEntriesChangedListener { changes ->
            changes.forEach { ordered += Triple(it.key, it.oldValue, it.newValue) }
        }

        // The replay (all adds, oldValue == null) is delivered before the post-registration delta.
        trackRepo.create(3, "Track C", "Rock")
        reactive.advance()

        val replaySize = 2
        ordered.take(replaySize).all { it.second == null } shouldBe true
        ordered.take(replaySize).map { it.first }.toSet() shouldBe setOf("Rock", "Jazz")
        ordered.drop(replaySize) shouldContainExactly listOf(Triple("Rock", "Rock:1", "Rock:2"))
    }

    // -------------------------------------------------------------------------
    // Ordered projection (entryOrdering) — single-key and multi-key
    // -------------------------------------------------------------------------

    "RegistryProjection with entryOrdering produces title-sorted buckets on seed" {
        trackRepo.create(1, "Zeppelin", "Rock")
        trackRepo.create(2, "Aerosmith", "Rock")
        trackRepo.create(3, "Beatles", "Rock")

        val projection = registryProjection(trackRepo, { it.albumName }, entryOrdering = compareBy { it.title })

        projection["Rock"]!!.map { it.title } shouldContainExactly listOf("Aerosmith", "Beatles", "Zeppelin")
    }

    "RegistryProjection with entryOrdering inserts incremental item at its sorted position" {
        trackRepo.create(1, "Zeppelin", "Rock")
        trackRepo.create(2, "Aerosmith", "Rock")

        val projection = registryProjection(trackRepo, { it.albumName }, entryOrdering = compareBy { it.title })
        projection["Rock"]!!.map { it.title } shouldContainExactly listOf("Aerosmith", "Zeppelin")

        trackRepo.create(3, "Beatles", "Rock")
        reactive.advance()

        projection["Rock"]!!.map { it.title } shouldContainExactly listOf("Aerosmith", "Beatles", "Zeppelin")
    }

    "RegistryProjection with entryOrdering keeps arrival order for equal-title elements" {
        val item1 = trackRepo.create(1, "Aria", "Classical")
        val item2 = trackRepo.create(2, "Aria", "Classical")
        val item3 = trackRepo.create(3, "Aria", "Classical")

        val projection = registryProjection(trackRepo, { it.albumName }, entryOrdering = compareBy { it.title })

        // All three have the same title — arrival order must be preserved (upper-bound stable)
        projection["Classical"]!!.map { it.id } shouldContainExactly listOf(item1.id, item2.id, item3.id)
    }

    "RegistryProjection with entryOrdering preserves order after incremental remove" {
        trackRepo.create(1, "Zeppelin", "Rock")
        val beatles = trackRepo.create(2, "Beatles", "Rock")
        trackRepo.create(3, "Aerosmith", "Rock")

        val projection = registryProjection(trackRepo, { it.albumName }, entryOrdering = compareBy { it.title })
        projection["Rock"]!!.map { it.title } shouldContainExactly listOf("Aerosmith", "Beatles", "Zeppelin")

        trackRepo.remove(beatles)
        reactive.advance()

        projection["Rock"]!!.map { it.title } shouldContainExactly listOf("Aerosmith", "Zeppelin")
    }

    "RegistryProjection with entryOrdering repositions entity when sort-key title changes on same-PK Update" {
        val item = trackRepo.create(1, "Zeppelin", "Rock")
        trackRepo.create(2, "Aerosmith", "Rock")

        val projection = registryProjection(trackRepo, { it.albumName }, entryOrdering = compareBy { it.title })
        projection["Rock"]!!.map { it.title } shouldContainExactly listOf("Aerosmith", "Zeppelin")

        // Mutate title so position changes — use distinct entity objects (album key unchanged)
        val oldSnapshot = MutableAudioItem(item.id, "Zeppelin", "Rock")
        val updated = MutableAudioItem(item.id, "AC/DC", "Rock")
        trackRepo.emitAsync(StandardCrudEvent.Update(updated, oldSnapshot))
        reactive.advance()

        // "AC/DC" sorts before "Aerosmith" alphabetically
        projection["Rock"]!!.map { it.title } shouldContainExactly listOf("AC/DC", "Aerosmith")
    }

    "RegistryProjection with entryOrdering: valueTransform receives already-ordered List<E>" {
        trackRepo.create(1, "Zeppelin", "Rock")
        trackRepo.create(2, "Aerosmith", "Rock")
        trackRepo.create(3, "Beatles", "Rock")

        val receivedOrder = mutableListOf<String>()
        val transformed =
            registryProjection<Int, String, AudioItem, String>(
                trackRepo, { it.albumName }, entryOrdering = compareBy { it.title }
            ) { _, items ->
                receivedOrder.addAll(items.map { it.title })
                "${items.size}"
            }

        // Trigger initialization and first transform call
        transformed["Rock"] shouldBe "3"
        receivedOrder shouldContainExactly listOf("Aerosmith", "Beatles", "Zeppelin")
    }

    "RegistryProjection without entryOrdering preserves insertion order" {
        trackRepo.create(1, "Zeppelin", "Rock")
        trackRepo.create(2, "Aerosmith", "Rock")
        trackRepo.create(3, "Beatles", "Rock")

        val projection = registryProjection(trackRepo, { it.albumName })

        // No ordering — bucket keeps seeding/insertion order
        projection["Rock"]!!.map { it.title } shouldContainExactly listOf("Zeppelin", "Aerosmith", "Beatles")
    }

    "MultiKeyRegistryProjection with entryOrdering produces title-sorted buckets per genre" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        multiKeyRepo.create(1, "Zeppelin", setOf("Rock"))
        multiKeyRepo.create(2, "Aerosmith", setOf("Rock", "Jazz"))
        multiKeyRepo.create(3, "Beatles", setOf("Rock"))

        val projection = registryMultiKeyProjection(multiKeyRepo, { it.genres }, entryOrdering = compareBy { it.title })

        projection["Rock"]!!.map { it.title } shouldContainExactly listOf("Aerosmith", "Beatles", "Zeppelin")
        projection["Jazz"]!!.map { it.title } shouldContainExactly listOf("Aerosmith")
    }

    "MultiKeyRegistryProjection with entryOrdering repositions entity in unchanged buckets on sort-key Update" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        val item = multiKeyRepo.create(1, "Zeppelin", setOf("Rock", "Jazz"))
        multiKeyRepo.create(2, "Aerosmith", setOf("Rock", "Jazz"))
        reactive.advance()

        val projection = registryMultiKeyProjection(multiKeyRepo, { it.genres }, entryOrdering = compareBy { it.title })
        projection["Rock"]!!.map { it.title } shouldContainExactly listOf("Aerosmith", "Zeppelin")

        // Title update with unchanged genres — both Rock and Jazz buckets must reposition item
        val oldSnapshot = item.clone()
        item.title = "AC/DC"
        multiKeyRepo.emitAsync(StandardCrudEvent.Update(item, oldSnapshot))
        reactive.advance()

        projection["Rock"]!!.map { it.title } shouldContainExactly listOf("AC/DC", "Aerosmith")
        projection["Jazz"]!!.map { it.title } shouldContainExactly listOf("AC/DC", "Aerosmith")
    }

    "MultiKeyRegistryProjection without entryOrdering preserves insertion order" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        multiKeyRepo.create(1, "Zeppelin", setOf("Rock"))
        multiKeyRepo.create(2, "Aerosmith", setOf("Rock"))
        multiKeyRepo.create(3, "Beatles", setOf("Rock"))

        val projection = registryMultiKeyProjection(multiKeyRepo, { it.genres })

        projection["Rock"]!!.map { it.title } shouldContainExactly listOf("Zeppelin", "Aerosmith", "Beatles")
    }

    // -------------------------------------------------------------------------
    // Bucket-level ordering (bucketKeyOrdering) — single-key and multi-key
    // -------------------------------------------------------------------------

    "RegistryProjection with bucketKeyOrdering exposes keys in comparator order on seed" {
        trackRepo.create(1, "Track A", "Rock")
        trackRepo.create(2, "Track B", "Jazz")
        trackRepo.create(3, "Track C", "Classical")

        // Reverse alphabetical order: Rock, Jazz, Classical
        val projection = registryProjection(trackRepo, { it.albumName }, bucketKeyOrdering = reverseOrder())

        projection.keys.toList() shouldContainExactly listOf("Rock", "Jazz", "Classical")
    }

    "RegistryProjection without bucketKeyOrdering exposes keys in PK natural order" {
        trackRepo.create(1, "Track A", "Rock")
        trackRepo.create(2, "Track B", "Jazz")
        trackRepo.create(3, "Track C", "Classical")

        val projection = registryProjection(trackRepo, { it.albumName })

        projection.keys.toList() shouldContainExactly listOf("Classical", "Jazz", "Rock")
    }

    "RegistryProjection with bucketKeyOrdering repositions bucket key after incremental create" {
        trackRepo.create(1, "Track A", "Rock")
        trackRepo.create(2, "Track B", "Jazz")

        val projection = registryProjection(trackRepo, { it.albumName }, bucketKeyOrdering = reverseOrder())
        projection.keys.toList() shouldContainExactly listOf("Rock", "Jazz")

        trackRepo.create(3, "Track C", "Classical")
        reactive.advance()

        // Classical sorts first in natural order but last in reverse order
        projection.keys.toList() shouldContainExactly listOf("Rock", "Jazz", "Classical")
    }

    "RegistryProjection with bucketKeyOrdering repositions bucket key after incremental remove empties bucket" {
        trackRepo.create(1, "Track A", "Rock")
        val jazz = trackRepo.create(2, "Track B", "Jazz")
        trackRepo.create(3, "Track C", "Classical")

        val projection = registryProjection(trackRepo, { it.albumName }, bucketKeyOrdering = reverseOrder())
        projection.keys.toList() shouldContainExactly listOf("Rock", "Jazz", "Classical")

        trackRepo.remove(jazz)
        reactive.advance()

        projection.keys.toList() shouldContainExactly listOf("Rock", "Classical")
    }

    "RegistryProjection with bucketKeyOrdering repositions bucket key after entity re-keys to new album" {
        val item = trackRepo.create(1, "Track A", "Rock")
        trackRepo.create(2, "Track B", "Rock") // Keep Rock bucket non-empty after re-key
        trackRepo.create(3, "Track C", "Jazz")

        val projection = registryProjection(trackRepo, { it.albumName }, bucketKeyOrdering = reverseOrder())
        projection.keys.toList() shouldContainExactly listOf("Rock", "Jazz")

        // Re-key entity 1 from "Rock" to "Blues"; entity 2 keeps Rock alive (reverse: Rock, Jazz, Blues)
        val oldSnapshot = MutableAudioItem(item.id, item.title, "Rock")
        val updated = MutableAudioItem(item.id, item.title, "Blues")
        trackRepo.emitAsync(StandardCrudEvent.Update(updated, oldSnapshot))
        reactive.advance()

        projection.keys.toList() shouldContainExactly listOf("Rock", "Jazz", "Blues")
    }

    "RegistryProjection with bucketKeyOrdering retains both buckets when keys compare equal under comparator" {
        // Case-insensitive comparator: "rock" and "Rock" compare equal; distinct PKs, both must survive
        trackRepo.create(1, "Track A", "rock")
        trackRepo.create(2, "Track B", "Rock")

        val caseInsensitive = Comparator<String> { a, b -> a.lowercase().compareTo(b.lowercase()) }
        val projection = registryProjection(trackRepo, { it.albumName }, bucketKeyOrdering = caseInsensitive)

        // Both "rock" and "Rock" must be retained — PK tiebreak prevents collapsing equal-comparing keys
        projection.keys.size shouldBe 2
        projection.keys shouldContainExactly setOf("rock", "Rock")
    }

    "MultiKeyRegistryProjection with bucketKeyOrdering exposes keys in comparator order on seed" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        multiKeyRepo.create(1, "Zeppelin", setOf("Rock"))
        multiKeyRepo.create(2, "Aerosmith", setOf("Jazz"))
        multiKeyRepo.create(3, "Beatles", setOf("Classical"))

        val projection = registryMultiKeyProjection(multiKeyRepo, { it.genres }, bucketKeyOrdering = reverseOrder())

        projection.keys.toList() shouldContainExactly listOf("Rock", "Jazz", "Classical")
    }

    "MultiKeyRegistryProjection without bucketKeyOrdering exposes keys in PK natural order" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        multiKeyRepo.create(1, "Zeppelin", setOf("Rock"))
        multiKeyRepo.create(2, "Aerosmith", setOf("Jazz"))
        multiKeyRepo.create(3, "Beatles", setOf("Classical"))

        val projection = registryMultiKeyProjection(multiKeyRepo, { it.genres })

        projection.keys.toList() shouldContainExactly listOf("Classical", "Jazz", "Rock")
    }

    "MultiKeyRegistryProjection with bucketKeyOrdering retains both buckets when keys compare equal under comparator" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        // "rock" and "Rock" compare equal case-insensitively; both must survive as distinct buckets
        multiKeyRepo.create(1, "Zeppelin", setOf("rock"))
        multiKeyRepo.create(2, "Aerosmith", setOf("Rock"))

        val caseInsensitive = Comparator<String> { a, b -> a.lowercase().compareTo(b.lowercase()) }
        val projection = registryMultiKeyProjection(multiKeyRepo, { it.genres }, bucketKeyOrdering = caseInsensitive)

        // PK tiebreak prevents collapsing equal-comparing keys — both must be retained
        projection.keys.size shouldBe 2
        projection.keys shouldContainExactly setOf("rock", "Rock")
    }

    "MultiKeyRegistryProjection with bucketKeyOrdering repositions bucket key after incremental key-set create" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        multiKeyRepo.create(1, "Zeppelin", setOf("Rock"))
        multiKeyRepo.create(2, "Aerosmith", setOf("Jazz"))

        val projection = registryMultiKeyProjection(multiKeyRepo, { it.genres }, bucketKeyOrdering = reverseOrder())
        projection.keys.toList() shouldContainExactly listOf("Rock", "Jazz")

        multiKeyRepo.create(3, "Beatles", setOf("Classical"))
        reactive.advance()

        projection.keys.toList() shouldContainExactly listOf("Rock", "Jazz", "Classical")
    }

    // -------------------------------------------------------------------------
    // Transformed projections: value ordering, both-comparator composition,
    // D-04 equal-value non-collapse, D-05 PK-default-not-hash
    // -------------------------------------------------------------------------

    "TransformedRegistryProjection without bucket comparator exposes keys in PK natural order" {
        // Albums inserted out of alphabetical order: "Rock", "Jazz", "Classical"
        trackRepo.create(1, "Track 1", "Rock")
        trackRepo.create(2, "Track 2", "Jazz")
        trackRepo.create(3, "Track 3", "Classical")

        val projection =
            registryProjection<Int, String, AudioItem, String>(trackRepo, { it.albumName }) { _, items ->
                "${items.size}"
            }

        // PK natural order = String natural order = alphabetical
        projection.keys.toList() shouldContainExactly listOf("Classical", "Jazz", "Rock")
    }

    "TransformedRegistryProjection with bucketValueOrdering exposes keys by transformed value order (value-only branch)" {
        trackRepo.create(1, "Track A", "C-Album")
        trackRepo.create(2, "Track B", "A-Album")
        trackRepo.create(3, "Track C", "B-Album")

        // Transform: produce the album name reversed (so "C-Album"->"mublA-C", "A-Album"->"mublA-A", "B-Album"->"mublA-B")
        // Reversed: "A-Album"->"mublA-A", "B-Album"->"mublA-B", "C-Album"->"mublA-C"
        // Natural order on reversed values: A < B < C, so keys come out A-Album, B-Album, C-Album
        val projection =
            registryProjection<Int, String, AudioItem, String>(
                trackRepo, { it.albumName },
                bucketValueOrdering = compareBy { it }
            ) { pk, _ ->
                pk.reversed()
            }

        // Reversed album names sorted: "mublA-A" < "mublA-B" < "mublA-C"
        // Original keys in that order: "A-Album", "B-Album", "C-Album"
        projection.keys.toList() shouldContainExactly listOf("A-Album", "B-Album", "C-Album")
    }

    "TransformedRegistryProjection with both comparators applies value-primary then key tiebreak then PK" {
        // Transform: bucket count as a string, so all single-entity buckets tie on "1"
        // With equal transformed values, key comparator (reverse String order) decides
        trackRepo.create(1, "Track A", "Zephyr")
        trackRepo.create(2, "Track B", "Alpha")
        trackRepo.create(3, "Track C", "Mango")
        // Fourth bucket with 2 entities — will sort first because "2" > "1" alphabetically? No: "2" > "1", so descending value comparator needed.
        // Use compareByDescending: buckets with count "2" sort before "1"
        trackRepo.create(4, "Track D", "Alpha") // Alpha now has 2 entities

        // Transform: stringify entity count per bucket
        // Alpha -> "2", Mango -> "1", Zephyr -> "1"
        // bucketValueOrdering = compareByDescending { it } -> "2" first, then "1"s
        // For ties on "1": bucketKeyOrdering = reverseOrder() -> Zephyr before Mango
        val projection =
            registryProjection<Int, String, AudioItem, String>(
                trackRepo, { it.albumName },
                bucketValueOrdering = compareByDescending { it },
                bucketKeyOrdering = reverseOrder()
            ) { _, items ->
                "${items.size}"
            }

        // Alpha has 2 entities -> "2" (first); Zephyr and Mango both "1", reverseOrder -> Zephyr, Mango
        projection.keys.toList() shouldContainExactly listOf("Alpha", "Zephyr", "Mango")
    }

    "TransformedRegistryProjection retains both keys when transformed values compare equal (D-04 equal-value non-collapse)" {
        trackRepo.create(1, "Track A", "Rock")
        trackRepo.create(2, "Track B", "Jazz")

        // Transform always returns the same constant — both buckets produce equal transformed values.
        // Without PK tiebreak this would collapse both into one slot; PK tiebreak prevents that.
        val projection =
            registryProjection<Int, String, AudioItem, String>(
                trackRepo, { it.albumName },
                bucketValueOrdering = compareBy { it }
            ) { _, _ ->
                "same-value"
            }

        // Both buckets must survive despite equal transformed values
        projection.keys.size shouldBe 2
        projection.keys shouldContainExactly setOf("Jazz", "Rock")
    }

    "TransformedMultiKeyRegistryProjection without bucket comparator exposes keys in PK natural order" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        // Genres inserted out of alphabetical order across entities
        multiKeyRepo.create(1, "Artist 1", setOf("Rock"))
        multiKeyRepo.create(2, "Artist 2", setOf("Jazz"))
        multiKeyRepo.create(3, "Artist 3", setOf("Classical"))

        val projection =
            registryMultiKeyProjection<Int, String, MutableMultiKeyAudioItem, String>(multiKeyRepo, { it.genres }) { _, items ->
                "${items.size}"
            }

        // PK natural order = alphabetical String order
        projection.keys.toList() shouldContainExactly listOf("Classical", "Jazz", "Rock")
    }

    "TransformedMultiKeyRegistryProjection with bucketValueOrdering exposes keys by transformed value (value-only branch)" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        multiKeyRepo.create(1, "Artist A", setOf("C-Genre"))
        multiKeyRepo.create(2, "Artist B", setOf("A-Genre"))
        multiKeyRepo.create(3, "Artist C", setOf("B-Genre"))

        // Transform: produce the genre name reversed; order by reversed value ascending
        val projection =
            registryMultiKeyProjection<Int, String, MutableMultiKeyAudioItem, String>(
                multiKeyRepo, { it.genres },
                bucketValueOrdering = compareBy { it }
            ) { pk, _ ->
                pk.reversed()
            }

        // Reversed: "C-Genre"->"erneG-C", "A-Genre"->"erneG-A", "B-Genre"->"erneG-B"
        // Natural order: A < B < C -> A-Genre, B-Genre, C-Genre
        projection.keys.toList() shouldContainExactly listOf("A-Genre", "B-Genre", "C-Genre")
    }

    "TransformedMultiKeyRegistryProjection retains both keys when transformed values compare equal (D-04 equal-value non-collapse)" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        multiKeyRepo.create(1, "Artist A", setOf("Rock"))
        multiKeyRepo.create(2, "Artist B", setOf("Jazz"))

        // Transform always returns the same constant value — PK tiebreak must prevent key collapse
        val projection =
            registryMultiKeyProjection<Int, String, MutableMultiKeyAudioItem, String>(
                multiKeyRepo, { it.genres },
                bucketValueOrdering = compareBy { it }
            ) { _, _ ->
                "same-value"
            }

        projection.keys.size shouldBe 2
        projection.keys shouldContainExactly setOf("Jazz", "Rock")
    }

    "TransformedMultiKeyRegistryProjection with both comparators applies value-primary then key tiebreak then PK" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        // Create buckets: "Rock" with 2 artists (transform -> "2"), "Jazz" and "Classical" with 1 each (-> "1")
        multiKeyRepo.create(1, "Artist 1", setOf("Rock"))
        multiKeyRepo.create(2, "Artist 2", setOf("Rock", "Jazz"))
        multiKeyRepo.create(3, "Artist 3", setOf("Classical"))

        // descending value order: "2" before "1"; for ties on "1": reverseOrder -> Jazz before Classical
        val projection =
            registryMultiKeyProjection<Int, String, MutableMultiKeyAudioItem, String>(
                multiKeyRepo, { it.genres },
                bucketValueOrdering = compareByDescending { it },
                bucketKeyOrdering = reverseOrder()
            ) { _, items ->
                "${items.size}"
            }

        // Rock has 2 -> first; Jazz and Classical both "1", reverseOrder -> Jazz, Classical
        projection.keys.toList() shouldContainExactly listOf("Rock", "Jazz", "Classical")
    }

    "TransformedRegistryProjection with bucketValueOrdering inserts a bucket key created after seed without error" {
        // Seed two buckets so the ordered index is non-empty before the incremental create.
        trackRepo.create(1, "Track A", "Bravo")
        trackRepo.create(2, "Track B", "Delta")

        val projection =
            registryProjection<Int, String, AudioItem, String>(
                trackRepo, { it.albumName },
                bucketValueOrdering = compareBy { it }
            ) { pk, _ -> pk }

        projection.keys.toList() shouldContainExactly listOf("Bravo", "Delta")

        // A CREATE introducing a brand-new bucket key while the ordered index is non-empty must not
        // throw: the reposition-remove is skipped for a key absent from the value cache, so the
        // value-ordering comparator never dereferences a missing cached value.
        trackRepo.create(3, "Track C", "Charlie")
        reactive.advance()

        projection.keys.toList() shouldContainExactly listOf("Bravo", "Charlie", "Delta")
    }

    "TransformedMultiKeyRegistryProjection with bucketValueOrdering inserts a bucket key created after seed without error" {
        val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
        multiKeyRepo.create(1, "Artist A", setOf("Bravo"))
        multiKeyRepo.create(2, "Artist B", setOf("Delta"))

        val projection =
            registryMultiKeyProjection<Int, String, MutableMultiKeyAudioItem, String>(
                multiKeyRepo, { it.genres },
                bucketValueOrdering = compareBy { it }
            ) { pk, _ -> pk }

        projection.keys.toList() shouldContainExactly listOf("Bravo", "Delta")

        multiKeyRepo.create(3, "Artist C", setOf("Charlie"))
        reactive.advance()

        projection.keys.toList() shouldContainExactly listOf("Bravo", "Charlie", "Delta")
    }

    "TransformedRegistryProjection with bucketValueOrdering repositions a bucket when its transformed value changes" {
        trackRepo.create(1, "Track A", "Alpha") // Alpha: 1 entry
        trackRepo.create(2, "Track B", "Beta") // Beta: 1 entry
        trackRepo.create(3, "Track C", "Beta") // Beta: 2 entries

        // Order by bucket size stringified, ascending: "1" < "2".
        val projection =
            registryProjection<Int, String, AudioItem, String>(
                trackRepo, { it.albumName },
                bucketValueOrdering = compareBy { it }
            ) { _, items -> "${items.size}" }

        projection.keys.toList() shouldContainExactly listOf("Alpha", "Beta")

        // Grow Alpha to 3 entries so its value ("3") sorts after Beta ("2"); the reposition must move
        // the existing key without dropping or duplicating it.
        trackRepo.create(4, "Track D", "Alpha")
        trackRepo.create(5, "Track E", "Alpha")
        reactive.advance()

        projection.keys.size shouldBe 2
        projection.keys.toList() shouldContainExactly listOf("Beta", "Alpha")
    }

    "MultiKeyRegistryProjection iterates without ConcurrentModificationException under concurrent key-set churn stress"
        .config(tags = setOf(Stress)) {
            extension(ReactiveScopeSerialization)

            val multiKeyRepo = MultiKeyAudioItemVolatileRepository(ctx)
            val seedSize = 50
            val mutations = 2000
            val readerIterations = 500

            val seedItems =
                (1..seedSize).map { i ->
                    multiKeyRepo.create(i, "Track-$i", setOf("Genre-${i % 3}"))
                }

            val projection = registryMultiKeyProjection(multiKeyRepo, { it.genres })
            // Trigger init before writers start
            projection.size shouldBe 3

            shouldNotThrowAny {
                val writerJob =
                    launch(Dispatchers.Default) {
                        repeat(mutations) { i ->
                            // Cycle genres to cause key-set churn across Genre-0, Genre-1, Genre-2
                            val item = MutableMultiKeyAudioItem(seedSize + i + 1, "Extra-$i", setOf("Genre-${i % 3}"))
                            multiKeyRepo.add(item)
                            multiKeyRepo.remove(item)
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