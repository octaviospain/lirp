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

import net.transgressoft.lirp.event.StandardCrudEvent
import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.persistence.MultiKeyAudioItemVolatileRepository
import net.transgressoft.lirp.persistence.MutableMultiKeyAudioItem
import net.transgressoft.lirp.persistence.SoftDeletableMultiKeyAudioItemRepo
import net.transgressoft.lirp.persistence.fx.FxToolkitInit
import net.transgressoft.lirp.persistence.projection.ProjectionEntryChange
import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import javafx.application.Platform
import javafx.collections.MapChangeListener
import javafx.collections.ObservableMap
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for [RegistryFxMultiKeyProjection], verifying multi-key grouped projection from a
 * registry source, single-pulse batching via the pending-flush coalescer, in-place key-set
 * re-bucketing via the registry Update path (without per-entity subscription), soft-delete
 * filtering, and close behavior.
 *
 * All tests use `dispatchToFxThread = false` except the explicit FX-thread dispatch test, to
 * avoid [Platform.runLater] timing issues in the test harness.
 *
 * Update events for in-place mutations are emitted manually via `emitAsync` on the repository,
 * mirroring the behavior of persistent repositories that subscribe to entity mutations internally.
 */
@DisplayName("RegistryFxMultiKeyProjection")
class RegistryFxMultiKeyProjectionTest : StringSpec({

    val reactive = reactiveScope()

    beforeSpec {
        FxToolkitInit.ensureInitialized()
    }

    lateinit var trackRepo: MultiKeyAudioItemVolatileRepository

    beforeEach {
        trackRepo = MultiKeyAudioItemVolatileRepository()
    }

    afterEach {
        LirpContext.default.close()
    }

    "RegistryFxMultiKeyProjection places an FX audio item with two genres into both buckets" {
        trackRepo.create(1, "Track A", setOf("Rock", "Jazz"))

        val projection = RegistryFxMultiKeyProjection(trackRepo, { it.genres }, false)
        projection.addListener(MapChangeListener { })

        projection.containsKey("Rock") shouldBe true
        projection.containsKey("Jazz") shouldBe true
        projection["Rock"]!!.size shouldBe 1
        projection["Jazz"]!!.size shouldBe 1
        projection["Rock"]!![0].id shouldBe 1
        projection["Jazz"]!![0].id shouldBe 1
    }

    "RegistryFxMultiKeyProjection mutating the genre set in place fires a single pulse adding the new bucket and removing the old" {
        val item = trackRepo.create(1, "Track A", setOf("Rock", "Jazz"))
        val projection = RegistryFxMultiKeyProjection(trackRepo, { it.genres }, false)
        val pulseCount = AtomicInteger(0)
        projection.addListener(MapChangeListener { pulseCount.incrementAndGet() })

        // Seed both buckets
        projection.containsKey("Rock") shouldBe true
        projection.containsKey("Jazz") shouldBe true

        // Mutate genres and fire Update event: {Rock,Jazz} → {Rock,Indie}
        val oldSnapshot = item.clone()
        item.genres = setOf("Rock", "Indie")
        pulseCount.set(0)
        trackRepo.emitAsync(StandardCrudEvent.Update(item, oldSnapshot))
        reactive.advance()

        projection.containsKey("Rock") shouldBe true
        projection.containsKey("Indie") shouldBe true
        projection.containsKey("Jazz") shouldBe false

        // One Update event coalesces into a single flush: exactly two net MapChange
        // notifications (Indie added, Jazz removed). Rock is retained and never re-notified.
        pulseCount.get() shouldBe 2
    }

    "RegistryFxMultiKeyProjection the item is never absent from all genre buckets during a re-bucket" {
        val item = trackRepo.create(1, "Track A", setOf("Rock", "Jazz"))
        val projection = RegistryFxMultiKeyProjection(trackRepo, { it.genres }, false)

        val bucketsAtFlushTime = mutableListOf<Set<String>>()
        projection.addListener(
            MapChangeListener {
                bucketsAtFlushTime.add(projection.keys.toSet())
            }
        )

        // Seed
        projection.containsKey("Rock") shouldBe true
        projection.containsKey("Jazz") shouldBe true

        val oldSnapshot = item.clone()
        item.genres = setOf("Rock", "Indie")
        trackRepo.emitAsync(StandardCrudEvent.Update(item, oldSnapshot))
        reactive.advance()

        // At every observable snapshot, at least one original or new bucket is present
        bucketsAtFlushTime.forEach { keys ->
            (keys.any { it in setOf("Rock", "Jazz", "Indie") }) shouldBe true
        }
        // Final state
        projection.containsKey("Rock") shouldBe true
        projection.containsKey("Indie") shouldBe true
        projection.containsKey("Jazz") shouldBe false
    }

    "RegistryFxMultiKeyProjection registry update that changes the genre set re-buckets" {
        val item = trackRepo.create(1, "Track A", setOf("Rock", "Jazz"))
        val projection = RegistryFxMultiKeyProjection(trackRepo, { it.genres }, false)
        val changes = mutableListOf<MapChangeListener.Change<out String, out List<MutableMultiKeyAudioItem>>>()
        projection.addListener(MapChangeListener(changes::add))

        projection.containsKey("Rock") shouldBe true
        projection.containsKey("Jazz") shouldBe true
        changes.clear()

        val oldSnapshot = item.clone()
        item.genres = setOf("Indie")
        trackRepo.emitAsync(StandardCrudEvent.Update(item, oldSnapshot))
        reactive.advance()

        projection.containsKey("Indie") shouldBe true
        projection.containsKey("Rock") shouldBe false
        projection.containsKey("Jazz") shouldBe false
        projection["Indie"]!!.size shouldBe 1
        changes.isNotEmpty() shouldBe true
    }

    "RegistryFxMultiKeyProjection excludes soft-deleted entities" {
        val softRepo = SoftDeletableMultiKeyAudioItemRepo()
        val item = softRepo.create(1, "Track A", setOf("Rock", "Jazz"))
        val projection = RegistryFxMultiKeyProjection(softRepo, { it.genres }, false)
        projection.addListener(MapChangeListener { })

        projection.containsKey("Rock") shouldBe true
        projection.containsKey("Jazz") shouldBe true

        val oldSnapshot = item.clone()
        item.deletedAt = Instant.now()
        softRepo.emitAsync(StandardCrudEvent.Update(item, oldSnapshot))
        reactive.advance()

        projection.containsKey("Rock") shouldBe false
        projection.containsKey("Jazz") shouldBe false
    }

    "RegistryFxMultiKeyProjection empty key set — entity placed in zero buckets, no error" {
        trackRepo.create(1, "Track A", emptySet())
        val projection = RegistryFxMultiKeyProjection(trackRepo, { it.genres }, false)
        projection.addListener(MapChangeListener { })

        projection.isEmpty() shouldBe true
    }

    "RegistryFxMultiKeyProjection containsValue, values, and mutation methods" {
        val item = trackRepo.create(1, "Track A", setOf("Rock", "Jazz"))
        val projection = RegistryFxMultiKeyProjection(trackRepo, { it.genres }, false)
        projection.addListener(MapChangeListener { })

        projection.values.any { it.contains(item) } shouldBe true
        projection.containsValue(listOf(item)) shouldBe true
        projection.isEmpty() shouldBe false

        shouldThrow<UnsupportedOperationException> { projection.put("Rock", emptyList()) }
        shouldThrow<UnsupportedOperationException> { projection.remove("Rock") }
        shouldThrow<UnsupportedOperationException> { projection.putAll(emptyMap()) }
        shouldThrow<UnsupportedOperationException> { projection.clear() }
    }

    "RegistryFxMultiKeyProjection close cancels registry subscription" {
        val item = trackRepo.create(1, "Track A", setOf("Rock"))
        val projection = RegistryFxMultiKeyProjection(trackRepo, { it.genres }, false)
        projection.addListener(MapChangeListener { })
        projection.containsKey("Rock") shouldBe true

        projection.close()

        // After close, further registry events produce no change
        val oldSnapshot = item.clone()
        item.genres = setOf("Jazz")
        trackRepo.emitAsync(StandardCrudEvent.Update(item, oldSnapshot))
        reactive.advance()

        // The projection no longer tracks updates; Rock is still in innerObservableMap
        projection.containsKey("Rock") shouldBe true
        projection.containsKey("Jazz") shouldBe false
    }

    "RegistryFxMultiKeyProjection dispatches MapChangeListener on FX Application Thread" {
        val projection = RegistryFxMultiKeyProjection(trackRepo, { it.genres }, true)
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
            trackRepo.create(1, "Track A", setOf("Rock"))
            setupLatch.countDown()
        }
        setupLatch.await(5, TimeUnit.SECONDS)

        latch.await(5, TimeUnit.SECONDS)
        onFxThread.isNotEmpty() shouldBe true
        onFxThread.all { it } shouldBe true
    }

    "TransformedRegistryFxMultiKeyProjection maps multi-key bucket to transformed value on create" {
        trackRepo.create(1, "Track A", setOf("Rock", "Jazz"))
        trackRepo.create(2, "Track B", setOf("Rock"))

        val projection =
            TransformedRegistryFxMultiKeyProjection(
                trackRepo,
                { it.genres },
                { pk, items -> "[$pk:${items.size}]" },
                false
            )
        projection.addListener(MapChangeListener { })

        projection["Rock"] shouldBe "[Rock:2]"
        projection["Jazz"] shouldBe "[Jazz:1]"
        projection.size shouldBe 2
    }

    "TransformedRegistryFxMultiKeyProjection updates transformed value when genre set changes" {
        val item = trackRepo.create(1, "Track A", setOf("Rock", "Jazz"))
        val projection =
            TransformedRegistryFxMultiKeyProjection(
                trackRepo,
                { it.genres },
                { pk, items -> "[$pk:${items.size}]" },
                false
            )
        projection.addListener(MapChangeListener { })

        projection.containsKey("Rock") shouldBe true
        projection.containsKey("Jazz") shouldBe true

        val oldSnapshot = item.clone()
        item.genres = setOf("Rock", "Indie")
        trackRepo.emitAsync(StandardCrudEvent.Update(item, oldSnapshot))
        reactive.advance()

        projection.containsKey("Rock") shouldBe true
        projection.containsKey("Indie") shouldBe true
        projection.containsKey("Jazz") shouldBe false
        projection["Rock"] shouldBe "[Rock:1]"
    }

    "TransformedRegistryFxMultiKeyProjection exposes read-only keys, values, entries, containsValue, and isEmpty" {
        trackRepo.create(1, "Track A", setOf("Rock", "Jazz"))
        trackRepo.create(2, "Track B", setOf("Pop"))

        val projection =
            TransformedRegistryFxMultiKeyProjection(
                trackRepo,
                { it.genres },
                { pk, items -> "[$pk:${items.size}]" },
                false
            )
        projection.addListener(MapChangeListener { })

        projection.keys.containsAll(setOf("Rock", "Jazz", "Pop")) shouldBe true
        projection.values.any { it.contains("Rock") } shouldBe true
        projection.containsValue("[Rock:1]") shouldBe true
        projection.entries.size shouldBe 3
        projection.isEmpty() shouldBe false
    }

    "TransformedRegistryFxMultiKeyProjection mutation methods throw UnsupportedOperationException" {
        val projection =
            TransformedRegistryFxMultiKeyProjection(
                trackRepo,
                { it.genres },
                { pk, items -> "[$pk:${items.size}]" },
                false
            )
        shouldThrow<UnsupportedOperationException> { projection.put("Rock", "[Rock:0]") }
        shouldThrow<UnsupportedOperationException> { projection.remove("Rock") }
        shouldThrow<UnsupportedOperationException> { projection.putAll(emptyMap()) }
        shouldThrow<UnsupportedOperationException> { projection.clear() }
    }

    "TransformedRegistryFxMultiKeyProjection close stops tracking updates" {
        val item = trackRepo.create(1, "Track A", setOf("Rock"))
        val projection =
            TransformedRegistryFxMultiKeyProjection(
                trackRepo,
                { it.genres },
                { pk, items -> "[$pk:${items.size}]" },
                false
            )
        projection.addListener(MapChangeListener { })
        projection.containsKey("Rock") shouldBe true

        projection.close()

        val oldSnapshot = item.clone()
        item.genres = setOf("Jazz")
        trackRepo.emitAsync(StandardCrudEvent.Update(item, oldSnapshot))
        reactive.advance()

        projection.containsKey("Rock") shouldBe true
        projection.containsKey("Jazz") shouldBe false
    }

    "TransformedRegistryFxMultiKeyProjection two-phase dataTransform runs off FX thread and fxFactory runs on FX thread building a real FX value" {
        val dataTransformThreadFlags = CopyOnWriteArrayList<Boolean>()
        val fxFactoryThreadFlags = CopyOnWriteArrayList<Boolean>()

        val pulseLatch = CountDownLatch(1)
        val projection =
            registryFxMultiKeyProjection(
                trackRepo,
                { it.genres },
                dataTransform = { _, items ->
                    dataTransformThreadFlags.add(Platform.isFxApplicationThread())
                    items.toList()
                },
                fxFactory = { genre, items ->
                    fxFactoryThreadFlags.add(Platform.isFxApplicationThread())
                    AlbumFxView(genre, items)
                },
                dispatchToFxThread = true
            )
        (projection as ObservableMap<String, AlbumFxView>).addListener(
            MapChangeListener {
                pulseLatch.countDown()
            }
        )

        // Create the entity from the test thread so dataTransform runs on the background
        // registry-event coroutine (off FX thread), and fxFactory runs on the FX thread via
        // Platform.runLater inside flush().
        trackRepo.create(1, "Track A", setOf("Rock"))
        pulseLatch.await(5, TimeUnit.SECONDS) shouldBe true

        dataTransformThreadFlags.isNotEmpty() shouldBe true
        dataTransformThreadFlags.all { !it } shouldBe true
        fxFactoryThreadFlags.isNotEmpty() shouldBe true
        fxFactoryThreadFlags.all { it } shouldBe true
        val view = projection["Rock"] as AlbumFxView
        view.hasTracks shouldBe true
    }

    "TransformedRegistryFxMultiKeyProjection fxFactory failure in one bucket does not prevent other buckets from flushing" {
        val projection =
            registryFxMultiKeyProjection(
                trackRepo,
                { it.genres },
                dataTransform = { _, items -> items.toList() },
                fxFactory = { genre, items ->
                    if (genre == "Jazz") error("injected fxFactory failure")
                    AlbumFxView(genre, items)
                },
                dispatchToFxThread = false
            )
        (projection as ObservableMap<String, AlbumFxView>).addListener(MapChangeListener { })

        trackRepo.create(1, "Track A", setOf("Rock", "Jazz"))
        reactive.advance()

        projection.containsKey("Jazz") shouldBe false
        projection.containsKey("Rock") shouldBe true
        val view = projection["Rock"] as AlbumFxView
        view.hasTracks shouldBe true
    }

    "TransformedRegistryFxMultiKeyProjection fxFactory failure during initial seed skips only that bucket and leaves the projection usable" {
        trackRepo.create(1, "Track A", setOf("Rock", "Jazz"))

        val projection =
            registryFxMultiKeyProjection(
                trackRepo,
                { it.genres },
                dataTransform = { _, items -> items.toList() },
                fxFactory = { genre, items ->
                    if (genre == "Jazz") error("injected fxFactory failure during seed")
                    AlbumFxView(genre, items)
                },
                dispatchToFxThread = false
            )

        // First access triggers the lazy seed loop; a throwing fxFactory must not escape it.
        projection.containsKey("Rock") shouldBe true
        projection.containsKey("Jazz") shouldBe false
        (projection["Rock"] as AlbumFxView).hasTracks shouldBe true
    }

    "TransformedRegistryFxMultiKeyProjection single valueTransform overload runs off FX thread preserving backward compatibility" {
        val transformedOnFxThread = CopyOnWriteArrayList<Boolean>()
        trackRepo.create(1, "Track A", setOf("Rock"))
        trackRepo.create(2, "Track B", setOf("Rock", "Jazz"))

        val projection =
            TransformedRegistryFxMultiKeyProjection(
                trackRepo,
                { it.genres },
                { pk, items ->
                    transformedOnFxThread.add(Platform.isFxApplicationThread())
                    "[$pk:${items.size}]"
                },
                false
            )
        projection.addListener(MapChangeListener { })

        transformedOnFxThread.isNotEmpty() shouldBe true
        transformedOnFxThread.all { !it } shouldBe true
        projection["Rock"] shouldBe "[Rock:2]"
        projection["Jazz"] shouldBe "[Jazz:1]"
    }

    "TransformedRegistryFxMultiKeyProjection addOnEntriesChangedListener replays current entries as adds on registration" {
        trackRepo.create(1, "Track A", setOf("Rock", "Jazz"))
        trackRepo.create(2, "Track B", setOf("Jazz"))

        val projection =
            registryFxMultiKeyProjection(trackRepo, { it.genres }, { pk, items -> "$pk:${items.size}" }, false)

        val replayed = mutableMapOf<String, Pair<String?, String?>>()
        projection.addOnEntriesChangedListener { changes ->
            changes.forEach { replayed[it.key] = it.oldValue to it.newValue }
        }

        replayed.keys shouldBe setOf("Rock", "Jazz")
        replayed["Rock"] shouldBe (null to "Rock:1")
        replayed["Jazz"] shouldBe (null to "Jazz:2")
    }

    "TransformedRegistryFxMultiKeyProjection addOnEntriesChangedListener emits multi-key fan-out in a single batch" {
        val projection =
            registryFxMultiKeyProjection(trackRepo, { it.genres }, { pk, items -> "$pk:${items.size}" }, false)

        val invocationCount = AtomicInteger(0)
        val lastBatchKeys = mutableListOf<String>()
        projection.addOnEntriesChangedListener { changes ->
            invocationCount.incrementAndGet()
            lastBatchKeys.addAll(changes.map { it.key })
        }

        // entity with two genres causes both buckets to be created in a single flush pulse
        trackRepo.create(1, "Track A", setOf("Rock", "Jazz"))
        reactive.advance()

        invocationCount.get() shouldBe 1
        lastBatchKeys.toSet() shouldBe setOf("Rock", "Jazz")
    }

    "TransformedRegistryFxMultiKeyProjection addOnEntriesChangedListener stops delivering after handle is closed" {
        trackRepo.create(1, "Track A", setOf("Rock"))
        reactive.advance()

        val projection =
            registryFxMultiKeyProjection(trackRepo, { it.genres }, { pk, items -> "$pk:${items.size}" }, false)

        val changesLog = mutableListOf<ProjectionEntryChange<String, String>>()
        val handle = projection.addOnEntriesChangedListener { changes -> changesLog.addAll(changes) }
        changesLog.clear()

        handle.close()
        trackRepo.create(2, "Track B", setOf("Jazz"))
        reactive.advance()

        changesLog shouldBe emptyList()
    }

    "TransformedRegistryFxMultiKeyProjection seed runs fxFactory on the FX thread when first access is off the FX thread" {
        trackRepo.create(1, "Track A", setOf("Rock", "Jazz"))

        val dataTransformOnFx = CopyOnWriteArrayList<Boolean>()
        val fxFactoryOnFx = CopyOnWriteArrayList<Boolean>()
        val projection =
            registryFxMultiKeyProjection(
                trackRepo,
                { it.genres },
                dataTransform = { _, items ->
                    dataTransformOnFx.add(Platform.isFxApplicationThread())
                    items.toList()
                },
                fxFactory = { genre, items ->
                    fxFactoryOnFx.add(Platform.isFxApplicationThread())
                    AlbumFxView(genre, items)
                },
                dispatchToFxThread = true
            )

        // First access on a background (non-FX) thread must still seed fxFactory on the FX thread.
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit { (projection as ObservableMap<String, AlbumFxView>).size }.get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        dataTransformOnFx.isNotEmpty() shouldBe true
        dataTransformOnFx.all { !it } shouldBe true
        fxFactoryOnFx.isNotEmpty() shouldBe true
        fxFactoryOnFx.all { it } shouldBe true
    }

    "TransformedRegistryFxMultiKeyProjection close refuses new entries-changed registration and delivers nothing after" {
        trackRepo.create(1, "Track A", setOf("Rock"))
        reactive.advance()
        val projection =
            registryFxMultiKeyProjection(trackRepo, { it.genres }, { pk, items -> "$pk:${items.size}" }, false)
        projection.containsKey("Rock") shouldBe true

        projection.close()

        val keys = mutableListOf<String>()
        projection.addOnEntriesChangedListener { changes -> keys.addAll(changes.map { it.key }) }
        keys shouldBe emptyList() // registration after close replays nothing

        trackRepo.create(2, "Track B", setOf("Jazz"))
        reactive.advance()
        keys shouldBe emptyList() // and delivers nothing after
    }
})