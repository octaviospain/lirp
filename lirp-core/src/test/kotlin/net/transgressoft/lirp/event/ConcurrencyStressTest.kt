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

import net.transgressoft.lirp.Person
import net.transgressoft.lirp.persistence.VolatileRepository
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

private const val WRITER_COUNT = 120
private const val EVENTS_PER_WRITER = 10
private const val TOTAL_EVENTS = WRITER_COUNT * EVENTS_PER_WRITER

/**
 * High-concurrency stress tests that validate [FlowEventPublisher] event delivery completeness
 * under 100+ concurrent writers.
 *
 * These tests use real [Dispatchers.Default] (never test dispatchers) and [CountDownLatch] for
 * bounded waits. No [Thread.sleep] or fixed delays are used. Subscribers are registered before
 * writers start. Only completeness is verified — no ordering assertions are made.
 *
 * Tests cover:
 * - CrudEvent delivery under 120 concurrent writers via [VolatileRepository.add]
 * - MutationEvent delivery under 120 concurrent emitters via a standalone [FlowEventPublisher]
 * - Interleaved CRUD and mutation operations from truly concurrent coroutines
 * - Repository state consistency (correct size, no duplicate IDs) after concurrent stress
 */
class ConcurrencyStressTest : DescribeSpec({

    afterSpec {
        ReactiveScope.resetDefaultIoScope()
        ReactiveScope.resetDefaultFlowScope()
    }

    describe("FlowEventPublisher under concurrent CrudEvent load") {

        it("delivers all CrudEvents to all subscribers without loss").config(timeout = 30.seconds) {
            val repository = VolatileRepository<Int, Person>("crud-stress-test")

            // Each subscriber collects events and counts down a latch per received event
            val queue1 = ConcurrentLinkedQueue<CrudEvent<Int, Person>>()
            val queue2 = ConcurrentLinkedQueue<CrudEvent<Int, Person>>()
            val queue3 = ConcurrentLinkedQueue<CrudEvent<Int, Person>>()

            val latch1 = CountDownLatch(TOTAL_EVENTS)
            val latch2 = CountDownLatch(TOTAL_EVENTS)
            val latch3 = CountDownLatch(TOTAL_EVENTS)

            // Subscribers registered before writers start
            val sub1 =
                repository.subscribe { event ->
                    queue1.add(event)
                    latch1.countDown()
                }
            val sub2 =
                repository.subscribe { event ->
                    queue2.add(event)
                    latch2.countDown()
                }
            val sub3 =
                repository.subscribe { event ->
                    queue3.add(event)
                    latch3.countDown()
                }

            // 120 writer coroutines each adding 10 Person entities with unique IDs
            val writers =
                (0 until WRITER_COUNT).map { writerIndex ->
                    launch(Dispatchers.Default) {
                        repeat(EVENTS_PER_WRITER) { eventIndex ->
                            val id = writerIndex * EVENTS_PER_WRITER + eventIndex
                            repository.add(Person(id = id, money = id.toLong(), morals = true))
                        }
                    }
                }
            writers.joinAll()

            // Bounded wait — no Thread.sleep or fixed delays
            latch1.await(20, TimeUnit.SECONDS) shouldBe true
            latch2.await(20, TimeUnit.SECONDS) shouldBe true
            latch3.await(20, TimeUnit.SECONDS) shouldBe true

            queue1.size shouldBe TOTAL_EVENTS
            queue2.size shouldBe TOTAL_EVENTS
            queue3.size shouldBe TOTAL_EVENTS

            // Repository state consistency: correct size, unique IDs, no corruption
            repository.size() shouldBe TOTAL_EVENTS
            val ids = repository.map { it.id }.toSet()
            ids.size shouldBe TOTAL_EVENTS

            sub1.cancel()
            sub2.cancel()
            sub3.cancel()
        }
    }

    describe("FlowEventPublisher under concurrent MutationEvent load") {

        it("delivers all MutationEvents to all subscribers without loss").config(timeout = 30.seconds) {
            // Standalone FlowEventPublisher with closeOnEmpty=false to avoid premature close
            // from concurrent subscribe/cancel cycles during stress
            val publisher =
                FlowEventPublisher<MutationEvent.Type, MutationEvent<String, TestEntity>>(
                    "mutation-stress-publisher",
                    closeOnEmpty = false
                )
            publisher.activateEvents(MutationEvent.Type.MUTATE)

            val queue1 = ConcurrentLinkedQueue<MutationEvent<String, TestEntity>>()
            val queue2 = ConcurrentLinkedQueue<MutationEvent<String, TestEntity>>()
            val queue3 = ConcurrentLinkedQueue<MutationEvent<String, TestEntity>>()

            val latch1 = CountDownLatch(TOTAL_EVENTS)
            val latch2 = CountDownLatch(TOTAL_EVENTS)
            val latch3 = CountDownLatch(TOTAL_EVENTS)

            // Subscribers registered before writers start
            val sub1 =
                publisher.subscribe { event ->
                    queue1.add(event)
                    latch1.countDown()
                }
            val sub2 =
                publisher.subscribe { event ->
                    queue2.add(event)
                    latch2.countDown()
                }
            val sub3 =
                publisher.subscribe { event ->
                    queue3.add(event)
                    latch3.countDown()
                }

            // 120 writer coroutines each emitting 10 MutationEvents directly via emitAsync
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

            latch1.await(20, TimeUnit.SECONDS) shouldBe true
            latch2.await(20, TimeUnit.SECONDS) shouldBe true
            latch3.await(20, TimeUnit.SECONDS) shouldBe true

            queue1.size shouldBe TOTAL_EVENTS
            queue2.size shouldBe TOTAL_EVENTS
            queue3.size shouldBe TOTAL_EVENTS

            sub1.cancel()
            sub2.cancel()
            sub3.cancel()
            publisher.close()
        }
    }

    describe("FlowEventPublisher under interleaved CRUD and mutation load") {

        it("delivers all events under interleaved CRUD and mutation operations").config(timeout = 30.seconds) {
            val repository = VolatileRepository<Int, Person>("interleaved-crud-stress-test")

            // Standalone mutation publisher, separate from repository publisher
            val mutationPublisher =
                FlowEventPublisher<MutationEvent.Type, MutationEvent<String, TestEntity>>(
                    "interleaved-mutation-publisher",
                    closeOnEmpty = false
                )
            mutationPublisher.activateEvents(MutationEvent.Type.MUTATE)

            val crudWriterCount = WRITER_COUNT / 2
            val mutationWriterCount = WRITER_COUNT - crudWriterCount
            val expectedCrudEvents = crudWriterCount * EVENTS_PER_WRITER
            val expectedMutationEvents = mutationWriterCount * EVENTS_PER_WRITER

            // Separate queues and latches per event type per subscriber
            val crudQueue1 = ConcurrentLinkedQueue<CrudEvent<Int, Person>>()
            val crudQueue2 = ConcurrentLinkedQueue<CrudEvent<Int, Person>>()
            val mutQueue1 = ConcurrentLinkedQueue<MutationEvent<String, TestEntity>>()
            val mutQueue2 = ConcurrentLinkedQueue<MutationEvent<String, TestEntity>>()

            val crudLatch1 = CountDownLatch(expectedCrudEvents)
            val crudLatch2 = CountDownLatch(expectedCrudEvents)
            val mutLatch1 = CountDownLatch(expectedMutationEvents)
            val mutLatch2 = CountDownLatch(expectedMutationEvents)

            // Subscribers registered before writers start
            val crudSub1 =
                repository.subscribe { event ->
                    crudQueue1.add(event)
                    crudLatch1.countDown()
                }
            val crudSub2 =
                repository.subscribe { event ->
                    crudQueue2.add(event)
                    crudLatch2.countDown()
                }
            val mutSub1 =
                mutationPublisher.subscribe { event ->
                    mutQueue1.add(event)
                    mutLatch1.countDown()
                }
            val mutSub2 =
                mutationPublisher.subscribe { event ->
                    mutQueue2.add(event)
                    mutLatch2.countDown()
                }

            // Truly interleaved: CRUD writers and mutation writers launched simultaneously
            val crudWriters =
                (0 until crudWriterCount).map { writerIndex ->
                    launch(Dispatchers.Default) {
                        repeat(EVENTS_PER_WRITER) { eventIndex ->
                            val id = writerIndex * EVENTS_PER_WRITER + eventIndex
                            repository.add(Person(id = id, money = id.toLong(), morals = true))
                        }
                    }
                }
            val mutationWriters =
                (0 until mutationWriterCount).map { writerIndex ->
                    launch(Dispatchers.Default) {
                        repeat(EVENTS_PER_WRITER) { eventIndex ->
                            val newEntity = TestEntity("interleaved-$writerIndex-$eventIndex")
                            newEntity.name = "Interleaved-$writerIndex-$eventIndex"
                            val oldEntity = TestEntity("interleaved-$writerIndex-$eventIndex")
                            mutationPublisher.emitAsync(ReactiveMutationEvent(newEntity, oldEntity))
                        }
                    }
                }

            crudWriters.joinAll()
            mutationWriters.joinAll()

            crudLatch1.await(20, TimeUnit.SECONDS) shouldBe true
            crudLatch2.await(20, TimeUnit.SECONDS) shouldBe true
            mutLatch1.await(20, TimeUnit.SECONDS) shouldBe true
            mutLatch2.await(20, TimeUnit.SECONDS) shouldBe true

            // Verify CrudEvent counts per subscriber
            crudQueue1.size shouldBe expectedCrudEvents
            crudQueue2.size shouldBe expectedCrudEvents

            // Verify MutationEvent counts per subscriber
            mutQueue1.size shouldBe expectedMutationEvents
            mutQueue2.size shouldBe expectedMutationEvents

            crudSub1.cancel()
            crudSub2.cancel()
            mutSub1.cancel()
            mutSub2.cancel()
            mutationPublisher.close()
        }
    }
})