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
import net.transgressoft.lirp.persistence.MutableAudioItem
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Tests for [FxAggregateList] and [FxAggregateSet] verifying that serialized (single-thread)
 * mutations produce no lost updates. Closes coverage Gap 1 from the CONCERNS.md audit:
 * the documented single-thread contract for these collections was untested.
 *
 * All mutations are dispatched to a single-thread executor to satisfy the single-thread
 * access contract. [dispatchToFxThread] is always false, consistent with all lirp-fx tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FxAggregateConcurrentMutationTest : StringSpec({

    val testScope = CoroutineScope(UnconfinedTestDispatcher())

    beforeSpec {
        FxToolkitInit.ensureInitialized()
        ReactiveScope.flowScope = testScope
    }

    afterSpec {
        ReactiveScope.resetDefaultFlowScope()
    }

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

        latch.await(10, TimeUnit.SECONDS) shouldBe true
        executor.shutdown()

        list.size shouldBe 200
        list.referenceIds.toSet().size shouldBe 200
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

        latch.await(10, TimeUnit.SECONDS) shouldBe true
        executor.shutdown()

        set.size shouldBe 200
        set.referenceIds.size shouldBe 200
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

        latch.await(10, TimeUnit.SECONDS) shouldBe true
        executor.shutdown()

        list.size shouldBe 50
        list.all { it.id % 2 == 0 } shouldBe true
    }
})