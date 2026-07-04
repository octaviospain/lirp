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
import net.transgressoft.lirp.testing.ReactiveScopeSerialization
import net.transgressoft.lirp.testing.Stress
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import javafx.collections.MapChangeListener
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Concurrency regression tests for the registry-backed FX projections under a large async batch
 * import, using the production reactive dispatchers (via [ReactiveScopeSerialization]) so the real
 * background event coroutine and the flush pipeline are both active.
 *
 * Guards against a seed-window dropped-update race: registry events arriving in the interval between
 * the core subscribing and the projection finishing its direct seed must not be lost, even when the
 * projection is first initialized in the middle of the import. With unique-per-entity keys, a dropped
 * event is permanent (no later event re-flushes the key), so full materialization is a strict probe.
 *
 * The projections run with `dispatchToFxThread = false` so flushes are serialized through the
 * reactive flow scope rather than `Platform.runLater`, matching the rest of the projection test
 * suite and keeping the probe independent of FX-thread scheduling. The seed-window fix is dispatch
 * mode independent, so this still exercises it faithfully.
 */
@DisplayName("Registry FX projection concurrency")
class RegistryFxProjectionConcurrencyStressTest : StringSpec({
    extension(ReactiveScopeSerialization)

    afterEach { LirpContext.default.close() }

    val itemCount = 1200
    val importThreads = 8

    /** Imports [itemCount] entities concurrently and runs [initDuringImport] once a third are in. */
    fun runConcurrentImport(repo: MultiKeyAudioItemVolatileRepository, initDuringImport: () -> Unit) {
        val errors = ConcurrentLinkedQueue<Throwable>()
        val pool = Executors.newFixedThreadPool(importThreads)
        val completed = CountDownLatch(itemCount)
        val started = AtomicInteger(0)
        try {
            for (i in 1..itemCount) {
                pool.submit {
                    // Count starts, not completions, so the mid-import gate opens the seed window
                    // while the import is still in full flight — a stronger probe of the race.
                    started.incrementAndGet()
                    try {
                        repo.create(i, "Track-$i", setOf("artist-$i"))
                    } catch (t: Throwable) {
                        errors.add(t)
                    } finally {
                        completed.countDown()
                    }
                }
            }
            // Open the seed window: initialize the projection mid-import.
            while (started.get() < itemCount / 3) Thread.sleep(1)
            initDuringImport()
            completed.await(30, TimeUnit.SECONDS) shouldBe true
        } finally {
            pool.shutdownNow()
        }
        errors.isEmpty() shouldBe true
    }

    fun awaitMaterialized(currentCount: () -> Int): Int {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        var count = currentCount()
        while (count < itemCount && System.nanoTime() < deadline) {
            Thread.sleep(20)
            count = currentCount()
        }
        return count
    }

    "RegistryFxMultiKeyProjection materializes every entry when first initialized during a large concurrent import"
        .config(tags = setOf(Stress)) {
            val repo = MultiKeyAudioItemVolatileRepository()
            val projection = RegistryFxMultiKeyProjection(repo, { it.genres }, dispatchToFxThread = false)

            runConcurrentImport(repo) { projection.addListener(MapChangeListener { }) }

            val materialized = awaitMaterialized { projection.values.flatten().map { it.id }.toSet().size }
            materialized shouldBe itemCount
        }

    "RegistryFxProjection materializes every entry when first initialized during a large concurrent import"
        .config(tags = setOf(Stress)) {
            val repo = MultiKeyAudioItemVolatileRepository()
            val projection = RegistryFxProjection(repo, { it.title }, dispatchToFxThread = false)

            runConcurrentImport(repo) { projection.addListener(MapChangeListener { }) }

            val materialized = awaitMaterialized { projection.values.flatten().map { it.id }.toSet().size }
            materialized shouldBe itemCount
        }

    "RegistryFxMultiKeyProjection iterates entries, keys, and values without exception under a concurrent import"
        .config(tags = setOf(Stress)) {
            val repo = MultiKeyAudioItemVolatileRepository()
            val projection = RegistryFxMultiKeyProjection(repo, { it.genres }, dispatchToFxThread = false)
            projection.addListener(MapChangeListener { })

            shouldNotThrowAny {
                val reader = Executors.newSingleThreadExecutor()
                val stop = AtomicInteger(0)
                val readerError = ConcurrentLinkedQueue<Throwable>()
                val readerTask =
                    reader.submit {
                        while (stop.get() == 0) {
                            try {
                                projection.keys.toList()
                                projection.entries.forEach { it.value.size }
                                projection.values.flatten().size
                            } catch (t: Throwable) {
                                readerError.add(t)
                                return@submit
                            }
                        }
                    }
                try {
                    runConcurrentImport(repo) { /* already initialized above */ }
                    stop.set(1)
                    readerTask.get(30, TimeUnit.SECONDS)
                    readerError.isEmpty() shouldBe true
                } finally {
                    // Stop the reader loop (it polls `stop`, not interruption) and reap the thread on
                    // every path, so an assertion failure above cannot leak a spinning reader into
                    // later tests.
                    stop.set(1)
                    reader.shutdownNow()
                }
            }
        }
})