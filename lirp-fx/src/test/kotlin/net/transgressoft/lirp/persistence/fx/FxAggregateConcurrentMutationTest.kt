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

import net.transgressoft.lirp.persistence.AudioItem
import net.transgressoft.lirp.persistence.MutableAudioItem
import net.transgressoft.lirp.testing.Stress
import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tests for [FxAggregateList] and [FxAggregateSet] verifying that serialized (single-thread)
 * mutations produce no lost updates. Closes coverage Gap 1 from the CONCERNS.md audit:
 * the documented single-thread contract for these collections was untested.
 *
 * All mutations are dispatched to a single-thread executor to satisfy the single-thread
 * access contract. [dispatchToFxThread] is always false, consistent with all lirp-fx tests.
 */
class FxAggregateConcurrentMutationTest : StringSpec({
    tags(Stress)

    reactiveScope()

    "FxAggregateList serialized mutations produce no lost updates" {
        val list = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val executor = Executors.newSingleThreadExecutor()
        val latch = CountDownLatch(200)

        for (i in 0 until 200) {
            executor.submit {
                list.add(list.size, MutableAudioItem(i, "Item-$i"))
                latch.countDown()
            }
        }

        try {
            latch.await(10, TimeUnit.SECONDS) shouldBe true
            list.size shouldBe 200
            list.referenceIds.toSet().size shouldBe 200
        } finally {
            executor.shutdownNow()
        }
    }

    "FxAggregateSet serialized mutations produce no lost updates" {
        val set = fxAggregateSet<Int, AudioItem>(dispatchToFxThread = false)
        val executor = Executors.newSingleThreadExecutor()
        val latch = CountDownLatch(200)

        for (i in 0 until 200) {
            executor.submit {
                set.add(MutableAudioItem(i, "Item-$i"))
                latch.countDown()
            }
        }

        try {
            latch.await(10, TimeUnit.SECONDS) shouldBe true
            set.size shouldBe 200
            set.referenceIds.size shouldBe 200
        } finally {
            executor.shutdownNow()
        }
    }

    "FxAggregateList serialized interleaved add and remove produce correct final state" {
        val list = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val executor = Executors.newSingleThreadExecutor()
        val latch = CountDownLatch(150)

        // Add 100 items (IDs 0-99)
        for (i in 0 until 100) {
            executor.submit {
                list.add(list.size, MutableAudioItem(i, "Item-$i"))
                latch.countDown()
            }
        }

        // Remove odd-indexed items from high to low. All indices are guaranteed in-range
        // on the single-thread executor because adds complete before removes and iterating
        // from the highest index downward prevents index shift from invalidating subsequent removes.
        for (i in 49 downTo 0) {
            val oddIndex = 2 * i + 1
            executor.submit {
                list.removeAt(oddIndex) // always valid: range [1..99] on a size-100 list
                latch.countDown()
            }
        }

        try {
            latch.await(10, TimeUnit.SECONDS) shouldBe true
            list.size shouldBe 50
            list.all { it.id % 2 == 0 } shouldBe true
        } finally {
            executor.shutdownNow()
        }
    }

    "FxAggregateSet iteration during concurrent mutation does not throw" {
        val set = fxAggregateSet<Int, AudioItem>(dispatchToFxThread = false)
        for (i in 0 until 100) set.add(MutableAudioItem(i, "Item-$i"))

        val errors = CopyOnWriteArrayList<Throwable>()
        val running = AtomicBoolean(true)

        // Single writer mutating the backing cache while readers snapshot it. Before the fix,
        // ArrayList(localElements) in iterator() raced the writer's structural modification and
        // threw ArrayIndexOutOfBoundsException from HashSet.toArray (issue #310).
        val writer =
            Thread {
                try {
                    for (i in 100 until 2100) {
                        val item = MutableAudioItem(i, "Item-$i")
                        set.add(item)
                        set.remove(item)
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    // Always release the readers, even if a mutation throws, so a writer failure is
                    // reported as the root cause instead of hanging the test on readers.forEach { join() }.
                    running.set(false)
                }
            }
        val readers =
            (1..3).map {
                Thread {
                    while (running.get()) {
                        try {
                            set.toList()
                        } catch (t: Throwable) {
                            errors.add(t)
                        }
                    }
                }
            }

        writer.start()
        readers.forEach { it.start() }
        writer.join()
        readers.forEach { it.join() }

        errors.firstOrNull()?.let { throw AssertionError("Concurrent iteration raced mutation", it) }
        set.size shouldBe 100
    }

    "FxAggregateList iteration during concurrent mutation does not throw" {
        val list = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        for (i in 0 until 100) list.add(list.size, MutableAudioItem(i, "Item-$i"))

        val errors = CopyOnWriteArrayList<Throwable>()
        val running = AtomicBoolean(true)

        // Single writer appending/removing at the tail while readers iterate. Before the fix, the
        // inherited index-based iterator resolved get(index) against a live, shrinking size and the
        // ArrayList(localElements) snapshots raced structural modification.
        val writer =
            Thread {
                try {
                    for (i in 100 until 2100) {
                        val item = MutableAudioItem(i, "Item-$i")
                        list.add(list.size, item)
                        list.remove(item)
                    }
                } catch (t: Throwable) {
                    errors.add(t)
                } finally {
                    // Always release the readers, even if a mutation throws, so a writer failure is
                    // reported as the root cause instead of hanging the test on readers.forEach { join() }.
                    running.set(false)
                }
            }
        val readers =
            (1..3).map {
                Thread {
                    while (running.get()) {
                        try {
                            list.toList()
                        } catch (t: Throwable) {
                            errors.add(t)
                        }
                    }
                }
            }

        writer.start()
        readers.forEach { it.start() }
        writer.join()
        readers.forEach { it.join() }

        errors.firstOrNull()?.let { throw AssertionError("Concurrent iteration raced mutation", it) }
        list.size shouldBe 100
    }
})