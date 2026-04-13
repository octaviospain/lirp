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

package net.transgressoft.lirp.event

import net.transgressoft.lirp.persistence.CustomerVolatileRepo
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

private const val SUBSCRIBER_COUNT = 250
private const val WRITER_COUNT = 50
private const val EVENTS_PER_WRITER = 50
private const val TOTAL_EVENTS = WRITER_COUNT * EVENTS_PER_WRITER

/**
 * Heavy-load stress tests that validate [FlowEventPublisher] event delivery completeness
 * with 250+ simultaneous subscribers and 2500+ events.
 *
 * All subscribers must receive every event with zero loss. Tests use real [Dispatchers.Default]
 * (never test dispatchers) and [CountDownLatch] for bounded waits. No [Thread.sleep] or fixed
 * delays are used. Subscribers are always registered before writers start.
 */
class HighConcurrencyStressTest : DescribeSpec({

    afterSpec {
        ReactiveScope.resetDefaultIoScope()
        ReactiveScope.resetDefaultFlowScope()
    }

    describe("FlowEventPublisher with 250 simultaneous subscribers") {

        it("delivers all CrudEvents to 250 subscribers without loss").config(timeout = 60.seconds) {
            val repository = CustomerVolatileRepo()

            // Each of the 250 subscribers gets its own queue and latch
            val subscribers =
                (0 until SUBSCRIBER_COUNT).map {
                    Pair(
                        ConcurrentLinkedQueue<CrudEvent<Int, *>>(),
                        CountDownLatch(TOTAL_EVENTS)
                    )
                }

            // Register all 250 subscribers before writers start
            val subscriptions =
                subscribers.map { (queue, latch) ->
                    repository.subscribe { event ->
                        queue.add(event)
                        latch.countDown()
                    }
                }

            // 50 writer coroutines each adding 50 unique Customer entities
            val writers =
                (0 until WRITER_COUNT).map { writerIndex ->
                    launch(Dispatchers.Default) {
                        repeat(EVENTS_PER_WRITER) { eventIndex ->
                            val id = writerIndex * EVENTS_PER_WRITER + eventIndex
                            repository.create(id, "customer-$id")
                        }
                    }
                }
            writers.joinAll()

            // Bounded wait — no Thread.sleep or fixed delays; 45 s is generous for CI
            for ((queue, latch) in subscribers) {
                latch.await(45, TimeUnit.SECONDS) shouldBe true
                queue.size shouldBe TOTAL_EVENTS
            }

            repository.size() shouldBe TOTAL_EVENTS

            subscriptions.forEach { it.cancel() }
            repository.close()
        }

        it("delivers all MutationEvents to 250 subscribers without loss").config(timeout = 60.seconds) {
            val publisher =
                FlowEventPublisher<MutationEvent.Type, MutationEvent<String, TestEntity>>(
                    "high-concurrency-stress",
                    closeOnEmpty = false
                )
            publisher.activateEvents(MutationEvent.Type.MUTATE)

            // Each of the 250 subscribers gets its own queue and latch
            val subscribers =
                (0 until SUBSCRIBER_COUNT).map {
                    Pair(
                        ConcurrentLinkedQueue<MutationEvent<String, TestEntity>>(),
                        CountDownLatch(TOTAL_EVENTS)
                    )
                }

            // Register all 250 subscribers before writers start
            val subscriptions =
                subscribers.map { (queue, latch) ->
                    publisher.subscribe { event ->
                        queue.add(event)
                        latch.countDown()
                    }
                }

            // 50 writer coroutines each emitting 50 MutationEvents via emitAsync
            val writers =
                (0 until WRITER_COUNT).map { writerIndex ->
                    launch(Dispatchers.Default) {
                        repeat(EVENTS_PER_WRITER) { eventIndex ->
                            val newEntity = TestEntity("entity-$writerIndex-$eventIndex")
                            newEntity.name = "Name-$writerIndex-$eventIndex"
                            val oldEntity = TestEntity("entity-$writerIndex-$eventIndex")
                            publisher.emitAsync(ReactiveMutationEvent(newEntity, oldEntity))
                        }
                    }
                }
            writers.joinAll()

            // Bounded wait per subscriber; 45 s is generous for CI
            for ((queue, latch) in subscribers) {
                latch.await(45, TimeUnit.SECONDS) shouldBe true
                queue.size shouldBe TOTAL_EVENTS
            }

            subscriptions.forEach { it.cancel() }
            publisher.close()
        }
    }
})