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

import net.transgressoft.lirp.event.StandardCrudEvent
import net.transgressoft.lirp.persistence.AudioItem
import net.transgressoft.lirp.persistence.AudioItemVolatileRepository
import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.persistence.MutableAudioItem
import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import javafx.application.Platform
import javafx.collections.MapChangeListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [RegistryFxProjectionMap], verifying lazy seeding from a pre-populated registry,
 * [MapChangeListener] notifications on Create/Update/Delete, key-change re-bucketing via the
 * internal reverse index, soft-delete filtering, and the FX Application Thread dispatch contract.
 *
 * All tests use `dispatchToFxThread = false` except the explicit FX-thread-dispatch verification,
 * to avoid [Platform.runLater] timing issues in the test harness.
 *
 * Update events are fired manually via `emitAsync` on the repository, mirroring the behaviour
 * of persistent repositories that subscribe to entity mutations internally.
 */
@DisplayName("RegistryFxProjectionMap")
class RegistryFxProjectionMapTest : StringSpec({

    val reactive = reactiveScope()

    beforeSpec {
        FxToolkitInit.ensureInitialized()
    }

    lateinit var trackRepo: AudioItemVolatileRepository

    beforeEach {
        trackRepo = AudioItemVolatileRepository()
    }

    afterEach {
        LirpContext.default.close()
    }

    "groups entities by key extractor on first addListener" {
        trackRepo.create(1, "Track A", "Jazz")
        trackRepo.create(2, "Track B", "Jazz")
        trackRepo.create(3, "Track C", "Rock")

        val projection = RegistryFxProjectionMap(trackRepo, AudioItem::albumName, false)
        val changes = mutableListOf<MapChangeListener.Change<out String, out List<AudioItem>>>()
        projection.addListener(MapChangeListener(changes::add))

        projection["Jazz"]!!.size shouldBe 2
        projection["Rock"]!!.size shouldBe 1
        projection.size shouldBe 2
    }

    "builds initial state lazily from registry on first getValue" {
        trackRepo.create(1, "Track A", "Jazz")
        trackRepo.create(2, "Track B", "Jazz")
        trackRepo.create(3, "Track C", "Rock")

        val projection = RegistryFxProjectionMap(trackRepo, AudioItem::albumName, false)

        // Initial state is not populated until first access
        val map by projection
        map["Jazz"]!!.size shouldBe 2
        map["Rock"]!!.size shouldBe 1
    }

    "dispatches MapChangeListener on FX thread on Create" {
        val projection = RegistryFxProjectionMap(trackRepo, AudioItem::albumName, true)
        val changes = mutableListOf<MapChangeListener.Change<out String, out List<AudioItem>>>()
        val onFxThread = mutableListOf<Boolean>()

        projection.addListener(
            MapChangeListener { change ->
                onFxThread.add(Platform.isFxApplicationThread())
                changes.add(change)
            }
        )

        val latch = CountDownLatch(1)
        Platform.runLater {
            trackRepo.create(1, "Track A", "Jazz")
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)

        // Wait for the FX thread to process the change
        val doneLatch = CountDownLatch(1)
        Platform.runLater { doneLatch.countDown() }
        doneLatch.await(5, TimeUnit.SECONDS)

        changes.size shouldBe 1
        changes[0].wasAdded() shouldBe true
        onFxThread.all { it } shouldBe true
    }

    "dispatches MapChangeListener on Create" {
        val projection = RegistryFxProjectionMap(trackRepo, AudioItem::albumName, false)
        val changes = mutableListOf<MapChangeListener.Change<out String, out List<AudioItem>>>()
        projection.addListener(MapChangeListener(changes::add))

        trackRepo.create(1, "Track A", "Jazz")
        reactive.advance()

        changes.size shouldBe 1
        changes[0].wasAdded() shouldBe true
        changes[0].key shouldBe "Jazz"
        projection["Jazz"]!!.size shouldBe 1
    }

    "dispatches MapChangeListener on Delete" {
        val item = trackRepo.create(1, "Track A", "Jazz")
        val projection = RegistryFxProjectionMap(trackRepo, AudioItem::albumName, false)
        val changes = mutableListOf<MapChangeListener.Change<out String, out List<AudioItem>>>()
        projection.addListener(MapChangeListener(changes::add))

        // Seed has been read, now delete
        projection["Jazz"]!!.size shouldBe 1
        trackRepo.remove(item)
        reactive.advance()

        changes.any { it.wasRemoved() } shouldBe true
        projection.containsKey("Jazz") shouldBe false
    }

    "dispatches MapChangeListener on Update with same key" {
        val item = trackRepo.create(1, "Track A", "Jazz") as MutableAudioItem
        val projection = RegistryFxProjectionMap(trackRepo, AudioItem::albumName, false)
        val changes = mutableListOf<MapChangeListener.Change<out String, out List<AudioItem>>>()
        projection.addListener(MapChangeListener(changes::add))

        projection["Jazz"]!!.size shouldBe 1

        // Mutate entity and fire Update event — albumName (bucket key) unchanged, only title changed
        val oldSnapshot = item.clone()
        item.title = "Track A Updated"
        trackRepo.emitAsync(StandardCrudEvent.Update(item, oldSnapshot))
        reactive.advance()

        // Two changes expected: a remove (wasRemoved) and a re-add (wasAdded) for the same key,
        // because JavaFX's ObservableMapWrapper skips callObservers when old and new List values
        // compare equal — so we remove+re-insert to guarantee listener notification.
        changes.size shouldBe 2
        changes.any { it.wasRemoved() } shouldBe true
        changes.any { it.wasAdded() } shouldBe true
        projection["Jazz"]!!.size shouldBe 1
        projection["Jazz"]!![0].title shouldBe "Track A Updated"
    }

    "re-buckets entity on FX thread when key changes on Update" {
        val item = trackRepo.create(1, "Track A", "Jazz") as MutableAudioItem
        val projection = RegistryFxProjectionMap(trackRepo, AudioItem::albumName, false)
        val changes = mutableListOf<MapChangeListener.Change<out String, out List<AudioItem>>>()
        projection.addListener(MapChangeListener(changes::add))

        // Seed
        projection["Jazz"]!!.size shouldBe 1

        // Change the projection key and fire Update event
        val oldSnapshot = item.clone()
        item.albumName = "Rock"
        trackRepo.emitAsync(StandardCrudEvent.Update(item, oldSnapshot))
        reactive.advance()

        projection.containsKey("Jazz") shouldBe false
        projection["Rock"]!!.size shouldBe 1
        projection["Rock"]!![0].id shouldBe 1
    }

    "removes empty bucket when last entity deleted" {
        val item = trackRepo.create(1, "Track A", "Jazz")
        val projection = RegistryFxProjectionMap(trackRepo, AudioItem::albumName, false)
        projection.addListener(MapChangeListener { })

        projection["Jazz"]!!.size shouldBe 1
        trackRepo.remove(item)
        reactive.advance()

        projection.containsKey("Jazz") shouldBe false
        projection.isEmpty() shouldBe true
    }

    "fires single MapChangeListener pulse per event" {
        val projection = RegistryFxProjectionMap(trackRepo, AudioItem::albumName, false)
        val pulseCount = AtomicInteger(0)

        projection.addListener(MapChangeListener { pulseCount.incrementAndGet() })

        // A single repository add should produce exactly one MapChangeListener pulse
        trackRepo.create(1, "Track A", "Jazz")
        reactive.advance()
        pulseCount.get() shouldBe 1

        pulseCount.set(0)
        val item = trackRepo.create(2, "Track B", "Rock") as MutableAudioItem
        reactive.advance()
        pulseCount.get() shouldBe 1

        // A key change (Update) should produce exactly two pulses:
        // one removal from the old bucket, one addition to the new bucket
        pulseCount.set(0)
        val oldSnapshot = item.clone()
        item.albumName = "Jazz"
        trackRepo.emitAsync(StandardCrudEvent.Update(item, oldSnapshot))
        reactive.advance()
        // The single mutateMap block performs all bucket mutations: remove from "Rock" + add to "Jazz" = 2 map changes
        pulseCount.get() shouldBe 2

        // A single delete produces exactly one pulse
        pulseCount.set(0)
        trackRepo.remove(item)
        reactive.advance()
        pulseCount.get() shouldBe 1
    }

    "keys are in natural sorted order" {
        trackRepo.create(1, "T1", "Zebra")
        trackRepo.create(2, "T2", "Alpha")
        trackRepo.create(3, "T3", "Middle")

        val projection = RegistryFxProjectionMap(trackRepo, AudioItem::albumName, false)

        projection.keys.toList() shouldBe listOf("Alpha", "Middle", "Zebra")
    }
})