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

package net.transgressoft.lirp.persistence.fx.projection

import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.persistence.MultiKeyAudioItemVolatileRepository
import net.transgressoft.lirp.persistence.MultiKeyAudioPlaylistRepo
import net.transgressoft.lirp.persistence.MutableMultiKeyAudioItem
import net.transgressoft.lirp.persistence.fx.FxToolkitInit
import net.transgressoft.lirp.persistence.fx.fxAggregateList
import net.transgressoft.lirp.testing.Stress
import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import javafx.application.Platform
import javafx.beans.InvalidationListener
import javafx.collections.MapChangeListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Tests for [FxMultiKeyProjectionMap], verifying multi-key grouped projection from an aggregate
 * source, single-pulse batching via the pending-flush coalescer, per-entity mutation subscription
 * lifecycle, and in-place re-bucketing via [reconcile].
 *
 * All tests use `dispatchToFxThread = false` except the explicit FX-thread dispatch test, to
 * avoid [Platform.runLater] timing issues in the test harness.
 */
@DisplayName("FxMultiKeyProjectionMap")
class FxMultiKeyProjectionMapTest : StringSpec({

    val reactive = reactiveScope()

    beforeSpec {
        FxToolkitInit.ensureInitialized()
    }

    lateinit var trackRepo: MultiKeyAudioItemVolatileRepository
    lateinit var mkPlaylistRepo: MultiKeyAudioPlaylistRepo

    beforeEach {
        trackRepo = MultiKeyAudioItemVolatileRepository()
        mkPlaylistRepo = MultiKeyAudioPlaylistRepo()
    }

    afterEach {
        LirpContext.default.close()
    }

    "FxMultiKeyProjectionMap places an FX audio item with two genres into both buckets" {
        val source = fxAggregateList<Int, MutableMultiKeyAudioItem>(dispatchToFxThread = false)
        val projection = FxMultiKeyProjectionMap({ source }, { it.genres }, false)
        projection.addListener(MapChangeListener { })

        val item = MutableMultiKeyAudioItem(1, "Track A", setOf("Rock", "Jazz"))
        source.add(0, item)

        projection.containsKey("Rock") shouldBe true
        projection.containsKey("Jazz") shouldBe true
        projection["Rock"]!!.size shouldBe 1
        projection["Jazz"]!!.size shouldBe 1
        projection["Rock"]!![0].id shouldBe 1
        projection["Jazz"]!![0].id shouldBe 1
    }

    "FxMultiKeyProjectionMap mutating the genre set in place fires a single pulse adding the new bucket and removing the old" {
        val source = fxAggregateList<Int, MutableMultiKeyAudioItem>(dispatchToFxThread = false)
        val projection = FxMultiKeyProjectionMap({ source }, { it.genres }, false)
        val pulseCount = AtomicInteger(0)
        projection.addListener(MapChangeListener { pulseCount.incrementAndGet() })

        val item = MutableMultiKeyAudioItem(1, "Track A", setOf("Rock", "Jazz"))
        source.add(0, item)
        // Seed: two buckets populated, one add each = 2 pulses
        projection.containsKey("Rock") shouldBe true
        projection.containsKey("Jazz") shouldBe true

        // In-place genre mutation: {Rock,Jazz} → {Rock,Indie}
        // reconcile fires exactly one onBucketsChanged signal, draining in one flush
        pulseCount.set(0)
        item.genres = setOf("Rock", "Indie")
        reactive.advance()

        // After reconcile: Jazz removed, Indie added, Rock retained
        projection.containsKey("Rock") shouldBe true
        projection.containsKey("Indie") shouldBe true
        projection.containsKey("Jazz") shouldBe false

        // The re-bucket coalesces into a single flush: exactly two net MapChange
        // notifications (Indie added, Jazz removed). Rock is retained and never re-notified.
        pulseCount.get() shouldBe 2
    }

    "FxMultiKeyProjectionMap the item is never absent from all genre buckets during a re-bucket" {
        val source = fxAggregateList<Int, MutableMultiKeyAudioItem>(dispatchToFxThread = false)
        val projection = FxMultiKeyProjectionMap({ source }, { it.genres }, false)

        val bucketsAtFlushTime = mutableListOf<Set<String>>()
        projection.addListener(
            MapChangeListener {
                bucketsAtFlushTime.add(projection.keys.toSet())
            }
        )

        val item = MutableMultiKeyAudioItem(1, "Track A", setOf("Rock", "Jazz"))
        source.add(0, item)
        projection.containsKey("Rock") shouldBe true
        projection.containsKey("Jazz") shouldBe true

        // Mutate genres: {Rock,Jazz} → {Rock,Indie}
        item.genres = setOf("Rock", "Indie")
        reactive.advance()

        // At every observable snapshot, the item must be present in at least one bucket;
        // add-before-remove in reconcile prevents a transient all-buckets-absent state.
        bucketsAtFlushTime.forEach { keys ->
            (keys.any { it in setOf("Rock", "Jazz", "Indie") }) shouldBe true
        }
        // Final state: Rock and Indie present, Jazz gone
        projection.containsKey("Rock") shouldBe true
        projection.containsKey("Indie") shouldBe true
        projection.containsKey("Jazz") shouldBe false
    }

    "FxMultiKeyProjectionMap subscription lifecycle — no pulse fires after entity leaves all buckets" {
        val source = fxAggregateList<Int, MutableMultiKeyAudioItem>(dispatchToFxThread = false)
        val projection = FxMultiKeyProjectionMap({ source }, { it.genres }, false)
        val pulseCount = AtomicInteger(0)
        projection.addListener(MapChangeListener { pulseCount.incrementAndGet() })

        val item = MutableMultiKeyAudioItem(1, "Track A", setOf("Rock"))
        source.add(0, item)
        projection.containsKey("Rock") shouldBe true

        // Remove item from source — entity subscription should be cancelled
        source.removeAt(0)
        reactive.advance()
        projection.containsKey("Rock") shouldBe false
        projection.entitySubscriptions.containsKey(1) shouldBe false

        // Mutate item after removal from all buckets — no pulse should be scheduled
        pulseCount.set(0)
        item.genres = setOf("Jazz")
        reactive.advance()
        pulseCount.get() shouldBe 0
    }

    "FxMultiKeyProjectionMap empty key set — entity placed in zero buckets, no error" {
        val source = fxAggregateList<Int, MutableMultiKeyAudioItem>(dispatchToFxThread = false)
        val projection = FxMultiKeyProjectionMap({ source }, { it.genres }, false)
        projection.addListener(MapChangeListener { })

        val item = MutableMultiKeyAudioItem(1, "Track A", emptySet())
        source.add(0, item)

        projection.isEmpty() shouldBe true
        projection.size shouldBe 0
    }

    "FxMultiKeyProjectionMap close cancels all entity subscriptions" {
        val source = fxAggregateList<Int, MutableMultiKeyAudioItem>(dispatchToFxThread = false)
        val projection = FxMultiKeyProjectionMap({ source }, { it.genres }, false)
        projection.addListener(MapChangeListener { })

        val item1 = MutableMultiKeyAudioItem(1, "Track A", setOf("Rock"))
        val item2 = MutableMultiKeyAudioItem(2, "Track B", setOf("Jazz"))
        source.add(0, item1)
        source.add(1, item2)
        projection.containsKey("Rock") shouldBe true
        projection.containsKey("Jazz") shouldBe true
        projection.entitySubscriptions.size shouldBe 2

        projection.close()
        projection.entitySubscriptions.isEmpty() shouldBe true
    }

    "FxMultiKeyProjectionMap dispatches MapChangeListener on FX Application Thread" {
        val source = fxAggregateList<Int, MutableMultiKeyAudioItem>(dispatchToFxThread = true)
        val projection = FxMultiKeyProjectionMap({ source }, { it.genres }, true)
        val onFxThread = mutableListOf<Boolean>()
        val latch = CountDownLatch(1)

        projection.addListener(
            MapChangeListener {
                onFxThread.add(Platform.isFxApplicationThread())
                latch.countDown()
            }
        )

        val setupLatch = CountDownLatch(1)
        Platform.runLater {
            source.add(0, MutableMultiKeyAudioItem(1, "Track A", setOf("Rock")))
            setupLatch.countDown()
        }
        setupLatch.await(5, TimeUnit.SECONDS)

        latch.await(5, TimeUnit.SECONDS)
        onFxThread.isNotEmpty() shouldBe true
        onFxThread.all { it } shouldBe true
    }

    "FxMultiKeyProjectionMap removeListener stops MapChangeListener and InvalidationListener" {
        val source = fxAggregateList<Int, MutableMultiKeyAudioItem>(dispatchToFxThread = false)
        val projection = FxMultiKeyProjectionMap({ source }, { it.genres }, false)

        var changeCount = 0
        val mapListener = MapChangeListener<String, List<MutableMultiKeyAudioItem>> { changeCount++ }
        projection.addListener(mapListener)

        source.add(0, MutableMultiKeyAudioItem(1, "Track A", setOf("Rock")))
        changeCount shouldBe 1

        projection.removeListener(mapListener)
        source.add(1, MutableMultiKeyAudioItem(2, "Track B", setOf("Jazz")))
        changeCount shouldBe 1

        var invalidCount = 0
        val invListener = InvalidationListener { invalidCount++ }
        projection.addListener(invListener)
        source.add(2, MutableMultiKeyAudioItem(3, "Track C", setOf("Pop")))
        invalidCount shouldBe 1

        projection.removeListener(invListener)
        source.add(3, MutableMultiKeyAudioItem(4, "Track D", setOf("Blues")))
        invalidCount shouldBe 1
    }

    "FxMultiKeyProjectionMap containsValue returns true for a matching bucket and mutation methods throw" {
        val source = fxAggregateList<Int, MutableMultiKeyAudioItem>(dispatchToFxThread = false)
        val projection = FxMultiKeyProjectionMap({ source }, { it.genres }, false)
        projection.addListener(MapChangeListener { })

        val item = MutableMultiKeyAudioItem(1, "Track A", setOf("Rock"))
        source.add(0, item)

        projection.containsValue(listOf(item)) shouldBe true
        projection.containsValue(emptyList()) shouldBe false

        shouldThrow<UnsupportedOperationException> { projection.put("Jazz", listOf(item)) }
        shouldThrow<UnsupportedOperationException> { projection.remove("Rock") }
        shouldThrow<UnsupportedOperationException> { projection.putAll(emptyMap()) }
        shouldThrow<UnsupportedOperationException> { projection.clear() }
    }

    "TransformedFxMultiKeyProjectionMap maps multi-key bucket to transformed value" {
        val source = fxAggregateList<Int, MutableMultiKeyAudioItem>(dispatchToFxThread = false)
        val projection =
            TransformedFxMultiKeyProjectionMap(
                { source },
                { it.genres },
                { pk, items -> "[$pk:${items.size}]" },
                false
            )
        projection.addListener(MapChangeListener { })

        val item1 = MutableMultiKeyAudioItem(1, "Track A", setOf("Rock", "Jazz"))
        val item2 = MutableMultiKeyAudioItem(2, "Track B", setOf("Rock"))
        source.add(0, item1)
        source.add(1, item2)

        projection["Rock"] shouldBe "[Rock:2]"
        projection["Jazz"] shouldBe "[Jazz:1]"
        projection.size shouldBe 2
    }

    "TransformedFxMultiKeyProjectionMap fires MapChangeListener when item is removed" {
        val source = fxAggregateList<Int, MutableMultiKeyAudioItem>(dispatchToFxThread = false)
        val projection =
            TransformedFxMultiKeyProjectionMap(
                { source },
                { it.genres },
                { pk, items -> "[$pk:${items.size}]" },
                false
            )
        val pulseCount = AtomicInteger(0)
        projection.addListener(MapChangeListener { pulseCount.incrementAndGet() })

        val item = MutableMultiKeyAudioItem(1, "Track A", setOf("Rock"))
        source.add(0, item)
        projection["Rock"] shouldBe "[Rock:1]"

        pulseCount.set(0)
        source.removeAt(0)
        // Bucket removed — projection no longer contains Rock
        projection.containsKey("Rock") shouldBe false
    }

    "TransformedFxMultiKeyProjectionMap in-place genre mutation updates transformed value" {
        val source = fxAggregateList<Int, MutableMultiKeyAudioItem>(dispatchToFxThread = false)
        val projection =
            TransformedFxMultiKeyProjectionMap(
                { source },
                { it.genres },
                { pk, items -> "[$pk:${items.size}]" },
                false
            )
        projection.addListener(MapChangeListener { })

        val item = MutableMultiKeyAudioItem(1, "Track A", setOf("Rock", "Jazz"))
        source.add(0, item)
        projection["Rock"] shouldBe "[Rock:1]"
        projection["Jazz"] shouldBe "[Jazz:1]"

        // In-place mutation: {Rock,Jazz} → {Rock,Indie} — reconcile triggers re-bucketing
        item.genres = setOf("Rock", "Indie")
        reactive.advance()

        projection["Rock"] shouldBe "[Rock:1]"
        projection["Indie"] shouldBe "[Indie:1]"
        projection.containsKey("Jazz") shouldBe false
    }

    "TransformedFxMultiKeyProjectionMap exposes read-only keys, values, entries, containsValue, and isEmpty" {
        val source = fxAggregateList<Int, MutableMultiKeyAudioItem>(dispatchToFxThread = false)
        val projection =
            TransformedFxMultiKeyProjectionMap(
                { source },
                { it.genres },
                { pk, items -> "[$pk:${items.size}]" },
                false
            )
        projection.addListener(MapChangeListener { })

        projection.isEmpty() shouldBe true

        source.add(0, MutableMultiKeyAudioItem(1, "Track A", setOf("Rock", "Jazz")))
        source.add(1, MutableMultiKeyAudioItem(2, "Track B", setOf("Pop")))

        projection.keys.containsAll(setOf("Rock", "Jazz", "Pop")) shouldBe true
        projection.values.any { it.contains("Rock") } shouldBe true
        projection.containsValue("[Rock:1]") shouldBe true
        projection.entries.size shouldBe 3
        projection.isEmpty() shouldBe false
    }

    "TransformedFxMultiKeyProjectionMap mutation methods throw UnsupportedOperationException" {
        val source = fxAggregateList<Int, MutableMultiKeyAudioItem>(dispatchToFxThread = false)
        val projection =
            TransformedFxMultiKeyProjectionMap(
                { source },
                { it.genres },
                { pk, items -> "[$pk:${items.size}]" },
                false
            )
        shouldThrow<UnsupportedOperationException> { projection.put("Rock", "[Rock:0]") }
        shouldThrow<UnsupportedOperationException> { projection.remove("Rock") }
        shouldThrow<UnsupportedOperationException> { projection.putAll(emptyMap()) }
        shouldThrow<UnsupportedOperationException> { projection.clear() }
    }

    "TransformedFxMultiKeyProjectionMap close cancels all entity subscriptions" {
        val source = fxAggregateList<Int, MutableMultiKeyAudioItem>(dispatchToFxThread = false)
        val projection =
            TransformedFxMultiKeyProjectionMap(
                { source },
                { it.genres },
                { pk, items -> "[$pk:${items.size}]" },
                false
            )
        projection.addListener(MapChangeListener { })

        source.add(0, MutableMultiKeyAudioItem(1, "Track A", setOf("Rock")))
        source.add(1, MutableMultiKeyAudioItem(2, "Track B", setOf("Jazz")))
        projection.entitySubscriptions.size shouldBe 2

        projection.close()
        projection.entitySubscriptions.isEmpty() shouldBe true
    }

    "FxMultiKeyProjectionMap iterates without ConcurrentModificationException under concurrent multi-key churn"
        .config(tags = setOf(Stress)) {
            val source = fxAggregateList<Int, MutableMultiKeyAudioItem>(dispatchToFxThread = false)
            val projection = FxMultiKeyProjectionMap({ source }, { it.genres }, false)

            val genres = listOf("Rock", "Jazz", "Indie", "Pop", "Blues", "Metal", "Funk", "Soul")
            val seedSize = 100
            val mutationCount = 3000

            // Initialize the projection before seeding so the ListChangeListener path is active
            projection.addListener(MapChangeListener { })

            // Seed with entities spanning multiple genres so all buckets exist from the start
            val seedItems =
                (1..seedSize).map { i ->
                    MutableMultiKeyAudioItem(i, "Seed-$i", setOf(genres[i % genres.size], genres[(i + 1) % genres.size]))
                }
            seedItems.forEach { source.add(source.size, it) }
            projection.size shouldBe genres.size

            val executor = Executors.newSingleThreadExecutor()
            val latch = CountDownLatch(mutationCount)

            try {
                shouldNotThrowAny {
                    // Writer thread: add and immediately remove items across different genre pairs
                    for (i in 1..mutationCount) {
                        val newId = seedSize + i
                        val genre1 = genres[i % genres.size]
                        val genre2 = genres[(i + 2) % genres.size]
                        executor.submit {
                            val item = MutableMultiKeyAudioItem(newId, "Stress-$i", setOf(genre1, genre2))
                            source.add(source.size, item)
                            source.remove(item)
                            latch.countDown()
                        }
                    }

                    // Reader coroutine: iterate keys/entries/values concurrently with the writer
                    val readerJob =
                        launch(Dispatchers.Default) {
                            while (latch.count > 0L) {
                                projection.keys.toList()
                                projection.entries.forEach { it.value.size }
                                projection.values.toList()
                            }
                        }

                    latch.await(30, TimeUnit.SECONDS) shouldBe true
                    readerJob.join()
                }
            } finally {
                executor.shutdownNow()
            }

            // Seed entities remain; all their genre buckets should still be present
            val allBucketedIds = projection.values.flatten().map { it.id }.toSet()
            seedItems.forEach { item ->
                item.genres.forEach { genre ->
                    projection.containsKey(genre) shouldBe true
                }
                allBucketedIds.contains(item.id) shouldBe true
            }
        }
})