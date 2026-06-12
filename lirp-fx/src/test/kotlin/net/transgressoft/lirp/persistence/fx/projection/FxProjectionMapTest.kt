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

import net.transgressoft.lirp.persistence.AudioItem
import net.transgressoft.lirp.persistence.MutableMultiKeyAudioItem
import net.transgressoft.lirp.persistence.fx.FxAudioItem
import net.transgressoft.lirp.persistence.fx.FxToolkitInit
import net.transgressoft.lirp.persistence.fx.fxAggregateList
import net.transgressoft.lirp.persistence.fx.fxAggregateSet
import net.transgressoft.lirp.testing.Stress
import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import javafx.application.Platform
import javafx.beans.InvalidationListener
import javafx.collections.MapChangeListener
import javafx.collections.ObservableMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Value object used by transform tests: holds a projection key and the sorted list of titles
 * in that bucket, so a title change produces a different [FxAlbumBucket] and triggers a
 * [MapChangeListener] notification.
 */
data class FxAlbumBucket(val key: String, val titles: List<String>)

/**
 * Tests for [FxProjectionMap] verifying grouped projection from list and set sources,
 * [MapChangeListener.Change] notifications, key ordering, and unmodifiability.
 */
class FxProjectionMapTest : StringSpec({

    reactiveScope()

    beforeSpec {
        FxToolkitInit.ensureInitialized()
    }

    "FxProjectionMap groups entities by key extractor on add" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection = FxProjectionMap({ source }, { it.albumName }, false)

        source.add(0, FxAudioItem(1, "Track A", "Jazz"))

        projection.containsKey("Jazz") shouldBe true
        projection["Jazz"]!!.size shouldBe 1
        projection["Jazz"]!![0].id shouldBe 1
    }

    "FxProjectionMap fires MapChangeListener wasAdded when new group key appears" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection = FxProjectionMap({ source }, { it.albumName }, false)

        val changes = mutableListOf<MapChangeListener.Change<out String, out List<AudioItem>>>()
        projection.addListener(MapChangeListener(changes::add))

        source.add(0, FxAudioItem(1, "Track A", "Jazz"))

        changes.size shouldBe 1
        changes[0].wasAdded() shouldBe true
        changes[0].key shouldBe "Jazz"
    }

    "FxProjectionMap fires MapChangeListener when entity added to existing group" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection = FxProjectionMap({ source }, { it.albumName }, false)

        source.add(0, FxAudioItem(1, "Track A", "Jazz"))

        val changes = mutableListOf<MapChangeListener.Change<out String, out List<AudioItem>>>()
        projection.addListener(MapChangeListener(changes::add))

        source.add(1, FxAudioItem(2, "Track B", "Jazz"))

        changes.size shouldBe 1
        changes[0].key shouldBe "Jazz"
        projection["Jazz"]!!.size shouldBe 2
    }

    "FxProjectionMap removes bucket when last entity removed" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection = FxProjectionMap({ source }, { it.albumName }, false)

        val item = FxAudioItem(1, "Track A", "Jazz")
        source.add(0, item)
        source.removeAt(0)

        projection.containsKey("Jazz") shouldBe false
    }

    "FxProjectionMap fires MapChangeListener wasRemoved when bucket removed" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection = FxProjectionMap({ source }, { it.albumName }, false)

        val item = FxAudioItem(1, "Track A", "Jazz")
        source.add(0, item)

        val changes = mutableListOf<MapChangeListener.Change<out String, out List<AudioItem>>>()
        projection.addListener(MapChangeListener(changes::add))

        source.removeAt(0)

        changes.any { it.wasRemoved() } shouldBe true
    }

    "FxProjectionMap updates bucket without removing on partial remove" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection = FxProjectionMap({ source }, { it.albumName }, false)

        val item1 = FxAudioItem(1, "Track A", "Jazz")
        val item2 = FxAudioItem(2, "Track B", "Jazz")
        source.addAll(listOf(item1, item2))

        source.removeAt(0)

        projection.containsKey("Jazz") shouldBe true
        projection["Jazz"]!!.size shouldBe 1
    }

    "FxProjectionMap keys are in natural sorted order" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection = FxProjectionMap({ source }, { it.albumName }, false)

        source.addAll(
            listOf(
                FxAudioItem(1, "T1", "Zebra"),
                FxAudioItem(2, "T2", "Alpha"),
                FxAudioItem(3, "T3", "Middle")
            )
        )

        projection.keys.toList() shouldContainExactly listOf("Alpha", "Middle", "Zebra")
    }

    "FxProjectionMap handles clear on source" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection = FxProjectionMap({ source }, { it.albumName }, false)

        source.addAll(
            listOf(
                FxAudioItem(1, "T1", "Jazz"),
                FxAudioItem(2, "T2", "Rock")
            )
        )
        source.clear()

        projection.isEmpty() shouldBe true
    }

    "FxProjectionMap builds initial state from source on first getValue" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        source.addAll(
            listOf(
                FxAudioItem(1, "T1", "Jazz"),
                FxAudioItem(2, "T2", "Jazz"),
                FxAudioItem(3, "T3", "Rock")
            )
        )

        val projection = FxProjectionMap({ source }, { it.albumName }, false)

        projection["Jazz"]!!.size shouldBe 2
        projection["Rock"]!!.size shouldBe 1
    }

    "FxProjectionMap with set source groups entities correctly" {
        val source = fxAggregateSet<Int, AudioItem>(dispatchToFxThread = false)
        val projection = FxProjectionMap({ source }, { it.albumName }, false)

        source.add(FxAudioItem(1, "Track A", "Jazz"))
        source.add(FxAudioItem(2, "Track B", "Rock"))

        projection["Jazz"]!!.size shouldBe 1
        projection["Rock"]!!.size shouldBe 1
    }

    "FxProjectionMap is unmodifiable" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection = FxProjectionMap({ source }, { it.albumName }, false)

        shouldThrow<UnsupportedOperationException> {
            projection.put("Jazz", listOf(FxAudioItem(1, "T1", "Jazz")))
        }
        shouldThrow<UnsupportedOperationException> {
            projection.remove("Jazz")
        }
    }

    "FxProjectionMap with dispatchToFxThread=false fires on flowScope" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection = FxProjectionMap({ source }, { it.albumName }, false)

        var listenerFired = false
        projection.addListener(MapChangeListener { listenerFired = true })

        source.add(0, FxAudioItem(1, "Track A", "Jazz"))

        listenerFired shouldBe true
    }

    "FxProjectionMap entries contains all key-value pairs after population" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection = FxProjectionMap({ source }, { it.albumName }, false)

        source.addAll(listOf(FxAudioItem(1, "T1", "Jazz"), FxAudioItem(2, "T2", "Rock")))

        val entries = projection.entries
        entries.size shouldBe 2
        entries.map { it.key }.toSet() shouldBe setOf("Jazz", "Rock")
    }

    "FxProjectionMap values contains all bucket lists" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection = FxProjectionMap({ source }, { it.albumName }, false)

        source.addAll(
            listOf(
                FxAudioItem(1, "T1", "Jazz"),
                FxAudioItem(2, "T2", "Jazz"),
                FxAudioItem(3, "T3", "Rock")
            )
        )

        val values = projection.values
        values.size shouldBe 2
        values.any { it.size == 2 } shouldBe true
        values.any { it.size == 1 } shouldBe true
    }

    "FxProjectionMap containsValue returns true for a matching bucket" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection = FxProjectionMap({ source }, { it.albumName }, false)

        val item1 = FxAudioItem(1, "T1", "Jazz")
        val item2 = FxAudioItem(2, "T2", "Rock")
        source.addAll(listOf(item1, item2))

        projection.containsValue(listOf(item1)) shouldBe true
        projection.containsValue(listOf(item2)) shouldBe true
        projection.containsValue(listOf(item1, item2)) shouldBe false
    }

    "FxProjectionMap putAll throws UnsupportedOperationException" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection = FxProjectionMap({ source }, { it.albumName }, false)

        shouldThrow<UnsupportedOperationException> {
            projection.putAll(mapOf("Jazz" to listOf(FxAudioItem(1, "T1", "Jazz"))))
        }
    }

    "FxProjectionMap clear throws UnsupportedOperationException" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection = FxProjectionMap({ source }, { it.albumName }, false)

        shouldThrow<UnsupportedOperationException> {
            projection.clear()
        }
    }

    "FxProjectionMap removeListener stops MapChangeListener from receiving changes" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection = FxProjectionMap({ source }, { it.albumName }, false)

        var changeCount = 0
        val listener = MapChangeListener<String, List<AudioItem>> { changeCount++ }
        projection.addListener(listener)

        source.add(0, FxAudioItem(1, "T1", "Jazz"))
        changeCount shouldBe 1

        projection.removeListener(listener)
        source.add(1, FxAudioItem(2, "T2", "Rock"))
        changeCount shouldBe 1
    }

    "FxProjectionMap addListener InvalidationListener fires on change" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection = FxProjectionMap({ source }, { it.albumName }, false)

        var invalidationCount = 0
        val listener = InvalidationListener { invalidationCount++ }
        projection.addListener(listener)

        source.add(0, FxAudioItem(1, "T1", "Jazz"))

        invalidationCount shouldBe 1
    }

    "FxProjectionMap removeListener InvalidationListener stops invalidation notifications" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection = FxProjectionMap({ source }, { it.albumName }, false)

        var invalidationCount = 0
        val listener = InvalidationListener { invalidationCount++ }
        projection.addListener(listener)

        source.add(0, FxAudioItem(1, "T1", "Jazz"))
        invalidationCount shouldBe 1

        projection.removeListener(listener)
        source.add(1, FxAudioItem(2, "T2", "Rock"))
        invalidationCount shouldBe 1
    }

    "FxProjectionMap does not lose source mutations occurring during initialization" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)

        val preloaded = (1..5).map { FxAudioItem(it, "Pre-$it", "Jazz") }
        source.addAll(preloaded)

        val concurrentItem = FxAudioItem(99, "Concurrent", "Rock")

        val initStarted = CountDownLatch(1)
        val mutationDone = CountDownLatch(1)

        val mutator =
            Thread {
                initStarted.await(5, TimeUnit.SECONDS)
                source.add(source.size, concurrentItem)
                mutationDone.countDown()
            }
        mutator.start()

        val projection = FxProjectionMap({ source }, { it.albumName }, false)

        initStarted.countDown()
        mutationDone.await(5, TimeUnit.SECONDS)

        val jazzCount = projection["Jazz"]?.size ?: 0
        val rockCount = projection["Rock"]?.size ?: 0

        jazzCount shouldBe 5
        rockCount shouldBe 1
        mutator.join(5000)
    }

    "FxProjectionMap with dispatchToFxThread=false serializes rapid mutations without loss" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection = FxProjectionMap({ source }, { it.albumName }, false)

        // Trigger initialization
        projection.size

        // Rapidly add 20 items across 4 albums
        val items =
            (1..20).map { i ->
                val album = listOf("Jazz", "Rock", "Blues", "Pop")[i % 4]
                FxAudioItem(i, "Track-$i", album)
            }
        items.forEach { source.add(source.size, it) }

        projection.size shouldBe 4
        projection["Jazz"]!!.size shouldBe 5
        projection["Rock"]!!.size shouldBe 5
        projection["Blues"]!!.size shouldBe 5
        projection["Pop"]!!.size shouldBe 5
    }

    "FxProjectionMap reflects writer state in reader iteration after writer completes" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection = FxProjectionMap({ source }, { it.albumName }, false)
        // Trigger init before writer starts so the source-listener subscription is live.
        projection.size shouldBe 0

        val albums = listOf("Alpha", "Bravo", "Charlie", "Delta")
        val totalItems = 200
        val executor = Executors.newSingleThreadExecutor()
        val latch = CountDownLatch(totalItems)

        // Reader runs as a coroutine so a writer failure trips latch.await(10s) instead of looping forever.
        val readerJob =
            launch(Dispatchers.Default) {
                while (latch.count > 0L) {
                    projection.keys.toList()
                    projection.entries.forEach { it.value.size }
                }
            }

        try {
            for (i in 1..totalItems) {
                val item = FxAudioItem(i, "Track-$i", albums[i % albums.size])
                executor.submit {
                    source.add(source.size, item)
                    latch.countDown()
                }
            }

            latch.await(10, TimeUnit.SECONDS) shouldBe true
            projection.size shouldBe albums.size
            projection.values.sumOf { it.size } shouldBe totalItems
            projection.keys.toList() shouldContainExactly albums.sorted()
        } finally {
            readerJob.cancel()
            readerJob.join()
            executor.shutdownNow()
        }
    }

    "TransformedFxProjectionMap maps bucket to value via valueTransform" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection =
            TransformedFxProjectionMap(
                { source },
                { it.albumName },
                { pk, items -> FxAlbumBucket(pk, items.map { it.title }) },
                false
            )

        source.add(0, FxAudioItem(1, "Track A", "Jazz"))
        source.add(1, FxAudioItem(2, "Track B", "Jazz"))
        source.add(2, FxAudioItem(3, "Track C", "Rock"))

        projection["Jazz"] shouldBe FxAlbumBucket("Jazz", listOf("Track A", "Track B"))
        projection["Rock"] shouldBe FxAlbumBucket("Rock", listOf("Track C"))
        projection.size shouldBe 2
    }

    "TransformedFxProjectionMap fires exactly one MapChangeListener pulse per source event" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val pulseCount = AtomicInteger(0)
        val projection =
            TransformedFxProjectionMap(
                { source },
                { it.albumName },
                { pk, items -> FxAlbumBucket(pk, items.map { it.title }) },
                false
            )
        projection.addListener(MapChangeListener { pulseCount.incrementAndGet() })

        source.add(0, FxAudioItem(1, "Track A", "Jazz"))
        pulseCount.get() shouldBe 1

        pulseCount.set(0)
        source.add(1, FxAudioItem(2, "Track B", "Jazz"))
        pulseCount.get() shouldBe 1

        pulseCount.set(0)
        source.removeAt(0)
        pulseCount.get() shouldBe 1
    }

    "TransformedFxProjectionMap removes key from map when last item in bucket is removed" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection =
            TransformedFxProjectionMap(
                { source },
                { it.albumName },
                { pk, items -> FxAlbumBucket(pk, items.map { it.title }) },
                false
            )
        projection.addListener(MapChangeListener { })

        source.add(0, FxAudioItem(1, "Track A", "Jazz"))
        projection.containsKey("Jazz") shouldBe true

        source.removeAt(0)
        projection.containsKey("Jazz") shouldBe false
        projection.isEmpty() shouldBe true
    }

    "TransformedFxProjectionMap with FxAggregateSet source groups entities by key extractor" {
        val source = fxAggregateSet<Int, AudioItem>(dispatchToFxThread = false)
        val projection =
            TransformedFxProjectionMap(
                { source },
                { it.albumName },
                { pk, items -> FxAlbumBucket(pk, items.map { it.title }) },
                false
            )
        projection.addListener(MapChangeListener { })

        source.add(FxAudioItem(1, "Track A", "Jazz"))
        source.add(FxAudioItem(2, "Track B", "Rock"))

        projection["Jazz"] shouldBe FxAlbumBucket("Jazz", listOf("Track A"))
        projection["Rock"] shouldBe FxAlbumBucket("Rock", listOf("Track B"))

        source.remove(FxAudioItem(1, "Track A", "Jazz"))
        projection.containsKey("Jazz") shouldBe false
    }

    "TransformedFxProjectionMap exposes read-only keys, values, entries, containsValue, and isEmpty" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection =
            TransformedFxProjectionMap(
                { source },
                { it.albumName },
                { pk, items -> FxAlbumBucket(pk, items.map { it.title }) },
                false
            )
        projection.addListener(MapChangeListener { })

        projection.isEmpty() shouldBe true

        source.add(0, FxAudioItem(1, "Track A", "Jazz"))
        source.add(1, FxAudioItem(2, "Track B", "Rock"))

        projection.keys.containsAll(setOf("Jazz", "Rock")) shouldBe true
        projection.values.any { it.key == "Jazz" } shouldBe true
        projection.containsValue(FxAlbumBucket("Rock", listOf("Track B"))) shouldBe true
        projection.entries.size shouldBe 2
        projection.isEmpty() shouldBe false
    }

    "TransformedFxProjectionMap mutation methods throw UnsupportedOperationException" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection =
            TransformedFxProjectionMap(
                { source },
                { it.albumName },
                { pk, items -> FxAlbumBucket(pk, items.map { it.title }) },
                false
            )
        shouldThrow<UnsupportedOperationException> { projection.put("Jazz", FxAlbumBucket("Jazz", emptyList())) }
        shouldThrow<UnsupportedOperationException> { projection.remove("Jazz") }
        shouldThrow<UnsupportedOperationException> { projection.putAll(emptyMap()) }
        shouldThrow<UnsupportedOperationException> { projection.clear() }
    }

    "TransformedFxProjectionMap removeListener stops receiving changes" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection =
            TransformedFxProjectionMap(
                { source },
                { it.albumName },
                { pk, items -> FxAlbumBucket(pk, items.map { it.title }) },
                false
            )
        var count = 0
        val listener = MapChangeListener<String, FxAlbumBucket> { count++ }
        projection.addListener(listener)

        source.add(0, FxAudioItem(1, "Track A", "Jazz"))
        count shouldBe 1

        projection.removeListener(listener)
        source.add(1, FxAudioItem(2, "Track B", "Rock"))
        count shouldBe 1
    }

    "TransformedFxProjectionMap valueTransform runs off FX Application Thread" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val transformThreads = mutableListOf<Boolean>()
        val projection =
            TransformedFxProjectionMap(
                { source },
                { it.albumName },
                { pk, items ->
                    transformThreads.add(Platform.isFxApplicationThread())
                    FxAlbumBucket(pk, items.map { it.title })
                },
                false
            )
        projection.addListener(MapChangeListener { })

        source.add(0, FxAudioItem(1, "Track A", "Jazz"))

        transformThreads.isNotEmpty() shouldBe true
        transformThreads.all { !it } shouldBe true
    }

    "TransformedFxProjectionMap runs valueTransform off FX thread while dispatching the pulse on the FX thread when dispatchToFxThread is true" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val transformOnFxThread = mutableListOf<Boolean>()
        val listenerOnFxThread = mutableListOf<Boolean>()
        val projection =
            TransformedFxProjectionMap(
                { source },
                { it.albumName },
                { pk, items ->
                    transformOnFxThread.add(Platform.isFxApplicationThread())
                    FxAlbumBucket(pk, items.map { it.title })
                },
                true
            )
        val pulseLatch = CountDownLatch(1)
        projection.addListener(
            MapChangeListener {
                listenerOnFxThread.add(Platform.isFxApplicationThread())
                pulseLatch.countDown()
            }
        )

        source.add(0, FxAudioItem(1, "Track A", "Jazz"))
        pulseLatch.await(5, TimeUnit.SECONDS) shouldBe true

        // The transform is precomputed on the source-event thread (off the FX thread),
        // while only the final map mirror — and therefore the listener — runs on the FX thread.
        transformOnFxThread.isNotEmpty() shouldBe true
        transformOnFxThread.all { !it } shouldBe true
        listenerOnFxThread.isNotEmpty() shouldBe true
        listenerOnFxThread.all { it } shouldBe true
    }

    "FxProjectionMap iterates without ConcurrentModificationException under concurrent reader and writer stress"
        .config(tags = setOf(Stress)) {
            val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
            val projection = FxProjectionMap({ source }, { it.albumName }, false)

            val seedSize = 100
            val mutationCount = 5000
            val readerIterations = 1000

            val seedItems = (1..seedSize).map { FxAudioItem(it, "Seed-$it", "Album-${it % 8}") }
            seedItems.forEach { source.add(source.size, it) }
            projection.size shouldBe 8

            val executor = Executors.newSingleThreadExecutor()
            val latch = CountDownLatch(mutationCount)

            try {
                shouldNotThrowAny {
                    for (i in 1..mutationCount) {
                        val item = FxAudioItem(seedSize + i, "Stress-$i", "Album-${i % 8}")
                        executor.submit {
                            source.add(source.size, item)
                            source.remove(item)
                            latch.countDown()
                        }
                    }

                    val readerJob =
                        launch(Dispatchers.Default) {
                            repeat(readerIterations) {
                                projection.keys.toList()
                                projection.entries.forEach { it.value.size }
                            }
                        }

                    latch.await(30, TimeUnit.SECONDS) shouldBe true
                    readerJob.join()
                }
            } finally {
                executor.shutdownNow()
            }
        }

    /**
     * Compile-time overload resolution test: all four identity/transform factory forms for
     * [fxProjectionMap] and [fxMultiKeyProjectionMap] resolve without ambiguity in the same scope.
     * The returned objects are typed correctly — identity forms return the released map types;
     * transform forms return [ObservableMap] typed to the value `V`. The primary value of this
     * test is that it compiles, proving no overload ambiguity.
     */
    "factory overload resolution — all four fxProjectionMap / fxMultiKeyProjectionMap forms resolve without ambiguity" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val mkSource = fxAggregateList<Int, MutableMultiKeyAudioItem>(dispatchToFxThread = false)

        // identity forms — return released concrete types (ABI check: 3-arg signature unchanged)
        val identityMap: FxProjectionMap<Int, String, AudioItem> =
            fxProjectionMap(sourceRef = { source }, keyExtractor = { it.albumName }, dispatchToFxThread = false)
        val transformMap: TransformedFxProjectionMap<Int, String, AudioItem, FxAlbumBucket> =
            fxProjectionMap(
                sourceRef = { source },
                keyExtractor = { it.albumName },
                valueTransform = { pk, items -> FxAlbumBucket(pk, items.map { it.title }) },
                dispatchToFxThread = false
            )
        val mkIdentityMap: FxMultiKeyProjectionMap<Int, String, MutableMultiKeyAudioItem> =
            fxMultiKeyProjectionMap(sourceRef = { mkSource }, keyExtractor = { it.genres }, dispatchToFxThread = false)
        val mkTransformMap: TransformedFxMultiKeyProjectionMap<Int, String, MutableMultiKeyAudioItem, FxAlbumBucket> =
            fxMultiKeyProjectionMap(
                sourceRef = { mkSource },
                keyExtractor = { it.genres },
                valueTransform = { pk, items -> FxAlbumBucket(pk, items.map { it.title }) },
                dispatchToFxThread = false
            )

        // the released 3-arg form returns FxProjectionMap (ABI check)
        identityMap.shouldBeInstanceOf<FxProjectionMap<Int, String, AudioItem>>()
        // transform forms are ObservableMap-typed
        transformMap.shouldBeInstanceOf<ObservableMap<*, *>>()
        mkIdentityMap.shouldBeInstanceOf<ObservableMap<*, *>>()
        mkTransformMap.shouldBeInstanceOf<ObservableMap<*, *>>()

        // Exercise the identity map: add an item to source, then access the projection — the
        // first access triggers initialize() which seeds from the source's current contents
        source.add(0, FxAudioItem(1, "Track A", "Jazz"))
        identityMap.containsKey("Jazz") shouldBe true

        // Exercise transform map: after source.add above, containsKey triggers init which seeds
        // from the source's existing item via populateInitialState
        transformMap.containsKey("Jazz") shouldBe true
        transformMap["Jazz"] shouldBe FxAlbumBucket("Jazz", listOf("Track A"))

        // Exercise multi-key maps: initialize them first via addListener, then add items
        mkIdentityMap.addListener(MapChangeListener { })
        mkTransformMap.addListener(MapChangeListener { })
        val mkItem = MutableMultiKeyAudioItem(1, "Track A", setOf("Rock", "Jazz"))
        mkSource.add(0, mkItem)
        mkIdentityMap.containsKey("Rock") shouldBe true
        mkIdentityMap.containsKey("Jazz") shouldBe true

        mkTransformMap.containsKey("Rock") shouldBe true
        mkTransformMap["Rock"] shouldBe FxAlbumBucket("Rock", listOf("Track A"))
    }
})