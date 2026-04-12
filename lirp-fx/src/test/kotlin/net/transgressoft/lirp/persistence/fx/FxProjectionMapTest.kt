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

package net.transgressoft.lirp.persistence.fx

import net.transgressoft.lirp.event.ReactiveScope
import net.transgressoft.lirp.persistence.AudioItem
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import javafx.beans.InvalidationListener
import javafx.collections.MapChangeListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Tests for [FxProjectionMap] verifying grouped projection from list and set sources,
 * [MapChangeListener.Change] notifications, key ordering, and unmodifiability.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FxProjectionMapTest : StringSpec({

    val testScope = CoroutineScope(UnconfinedTestDispatcher())

    beforeSpec {
        FxToolkitInit.ensureInitialized()
        ReactiveScope.flowScope = testScope
    }

    afterSpec {
        ReactiveScope.resetDefaultFlowScope()
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
})