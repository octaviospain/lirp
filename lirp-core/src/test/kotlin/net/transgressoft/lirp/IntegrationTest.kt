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

package net.transgressoft.lirp

import net.transgressoft.lirp.PersonVolatileRepo
import net.transgressoft.lirp.entity.LazyTestEntity
import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.FlowEventPublisher
import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.event.PublisherConfig
import net.transgressoft.lirp.event.ReactiveScope
import net.transgressoft.lirp.event.StandardCrudEvent.Create
import net.transgressoft.lirp.event.TestEntity
import net.transgressoft.lirp.persistence.LirpContext
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.engine.spec.tempfile
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.property.arbitrary.next
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout

/** Integration tests for concurrent access, failure recovery, and resource lifecycle scenarios. */
@ExperimentalCoroutinesApi
class IntegrationTest : DescribeSpec({

    val testDispatcher = UnconfinedTestDispatcher()
    val testScope = CoroutineScope(testDispatcher)

    beforeSpec {
        ReactiveScope.flowScope = testScope
        ReactiveScope.ioScope = testScope
    }

    afterSpec {
        ReactiveScope.resetDefaultIoScope()
        ReactiveScope.resetDefaultFlowScope()
    }

    describe("Concurrent subscriptions") {
        it("handles 200 subscribers with 2000 events without deadlocking") {
            val subscriberCount = 200
            val eventCount = 2000

            val publisher =
                FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("concurrent-sub-test").apply {
                    activateEvents(CrudEvent.Type.CREATE)
                }
            val collectedCounts = Collections.synchronizedList(mutableListOf<Int>())

            withTimeout(10_000) {
                val subscriberJobs =
                    (1..subscriberCount).map {
                        testScope.launch {
                            val events = Collections.synchronizedList(mutableListOf<CrudEvent<String, TestEntity>>())
                            val subscription = publisher.subscribe { event -> events.add(event) }
                            testDispatcher.scheduler.advanceUntilIdle()
                            collectedCounts.add(events.size)
                            subscription.cancel()
                        }
                    }

                val producerJobs =
                    (1..4).map { producerId ->
                        testScope.launch {
                            val batchSize = eventCount / 4
                            repeat(batchSize) { i ->
                                val idx = (producerId - 1) * batchSize + i
                                publisher.emitAsync(Create(TestEntity("entity-$idx")))
                            }
                        }
                    }

                testDispatcher.scheduler.advanceUntilIdle()
                subscriberJobs.joinAll()
                producerJobs.joinAll()
            }

            collectedCounts.size shouldBe subscriberCount
            publisher.close()
        }
    }

    describe("Repository state consistency") {
        it("maintains consistency under 50 coroutines performing 1000 mixed operations each") {
            val coroutineCount = 50
            val opsPerCoroutine = 1000
            val entityPoolSize = 100

            val ctx = LirpContext()
            val repository = PersonVolatileRepo(ctx)
            val personPool = (1..entityPoolSize).map { arbitraryPerson(it).next() }

            val jobs =
                (1..coroutineCount).map { seed ->
                    testScope.launch {
                        val random = kotlin.random.Random(seed)
                        repeat(opsPerCoroutine) {
                            val person = personPool[random.nextInt(personPool.size)]
                            when (random.nextInt(5)) {
                                0 -> repository.create(person)
                                1 -> repository.remove(person)
                                2 -> {
                                    repository.remove(person)
                                    repository.create(person)
                                }
                                3 ->
                                    personPool.shuffled(random).take(10).forEach { p ->
                                        repository.remove(p)
                                        repository.create(p)
                                    }
                                4 -> repository.clear()
                            }
                        }
                    }
                }

            testDispatcher.scheduler.advanceUntilIdle()
            jobs.joinAll()

            val reportedSize = repository.size()
            val foundCount = (1..entityPoolSize).count { repository.findById(it).isPresent }
            reportedSize shouldBe foundCount
            ctx.close()
        }
    }

    describe("JSON file corruption recovery") {
        it("survives file deletion mid-operation and continues with in-memory state intact") {
            val jsonFile = tempfile("corruption-test", ".json").also { it.deleteOnExit() }
            val repository = PersonJsonFileRepository(jsonFile)

            val initialBatch = (1..20).map { arbitraryPerson(it).next() }
            initialBatch.forEach { repository.add(it) }
            testDispatcher.scheduler.advanceUntilIdle()
            repository.size() shouldBe 20

            jsonFile.delete()

            shouldNotThrowAny {
                val secondBatch = (21..40).map { arbitraryPerson(it).next() }
                secondBatch.forEach { repository.add(it) }
            }
            testDispatcher.scheduler.advanceUntilIdle()
            repository.size() shouldBe 40

            // Write corrupted content to simulate a partial write
            jsonFile.writeText("{invalid json content")

            shouldNotThrowAny {
                (41..50).forEach { repository.add(arbitraryPerson(it).next()) }
            }
            testDispatcher.scheduler.advanceUntilIdle()
            repository.size() shouldBe 50

            shouldNotThrowAny { repository.close() }
        }
    }

    describe("Publisher buffer behavior") {
        it("SUSPEND overflow strategy with tiny buffer delivers at least the last event to each subscriber") {
            val subscriberCount = 10
            val eventCount = 200

            val publisher =
                FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>(
                    "tiny-buffer-test",
                    PublisherConfig(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.SUSPEND)
                ).apply {
                    activateEvents(CrudEvent.Type.CREATE)
                }

            val subscriberResults =
                Collections.synchronizedList(
                    mutableListOf<List<CrudEvent<String, TestEntity>>>()
                )

            val subscriptions =
                (1..subscriberCount).map {
                    val events = Collections.synchronizedList(mutableListOf<CrudEvent<String, TestEntity>>())
                    val sub = publisher.subscribe { event -> events.add(event) }
                    sub to events
                }

            repeat(eventCount) { i ->
                publisher.emitAsync(Create(TestEntity("entity-$i")))
            }
            testDispatcher.scheduler.advanceUntilIdle()

            subscriptions.forEach { (_, events) ->
                subscriberResults.add(events.toList())
            }

            subscriberResults.forEach { events ->
                events.size shouldBeGreaterThan 0
                events.last().entities.values.first().id shouldBe "entity-${eventCount - 1}"
            }

            subscriptions.forEach { (sub, _) -> sub.cancel() }
            publisher.close()
        }
    }

    describe("Resource cleanup") {
        it("repository close() with multiple active subscriptions frees resources") {
            val jsonFile = tempfile("cleanup-test", ".json").also { it.deleteOnExit() }
            val repository = PersonJsonFileRepository(jsonFile)
            val entityCount = 20
            val subscribersPerEntity = 3

            val repoEvents = Collections.synchronizedList(mutableListOf<CrudEvent<Int, Personly>>())
            val repoSubscription = repository.subscribe { repoEvents.add(it) }

            val persons = (1..entityCount).map { arbitraryPerson(it).next() }
            persons.forEach { repository.add(it) }

            val entitySubscriptions =
                persons.flatMap { person ->
                    (1..subscribersPerEntity).map {
                        person.subscribe { }
                    }
                }
            testDispatcher.scheduler.advanceUntilIdle()

            entitySubscriptions.size shouldBe entityCount * subscribersPerEntity
            jsonFile.readText().isNotEmpty() shouldBe true

            repository.close()
            testDispatcher.scheduler.advanceUntilIdle()

            jsonFile.readText().isNotEmpty() shouldBe true
            repoEvents.size shouldBeGreaterThan 0

            repoSubscription.cancel()
            entitySubscriptions.forEach { it.cancel() }
        }
    }

    describe("Concurrent entity mutations") {
        it("500 concurrent mutations on same entity do not corrupt state") {
            val mutationCount = 500

            val entity = LazyTestEntity("concurrent-test")
            val receivedEvents = Collections.synchronizedList(mutableListOf<MutationEvent<String, LazyTestEntity>>())
            val subscription = entity.subscribe { event -> receivedEvents.add(event) }

            val jobs =
                (1..mutationCount).map { coroutineId ->
                    testScope.launch {
                        entity.value = "value-$coroutineId"
                    }
                }

            testDispatcher.scheduler.advanceUntilIdle()
            jobs.joinAll()

            val finalValue = entity.value
            finalValue.startsWith("value-") shouldBe true
            finalValue.removePrefix("value-").toInt() shouldBe (1..mutationCount).toList().find { "value-$it" == finalValue }

            receivedEvents.forEach { event ->
                (event.oldEntity.value != event.newEntity.value) shouldBe true
            }

            // Verify entity still functions after the storm
            entity.value = "post-storm"
            testDispatcher.scheduler.advanceUntilIdle()
            entity.value shouldBe "post-storm"

            subscription.cancel()
        }
    }

    describe("Publisher closure and reactivation under load") {
        it("30 dormant-to-active cycles with 50 mutations each do not silently drop events") {
            val cycleCount = 30
            val mutationsPerCycle = 50

            val creationCounter = AtomicInteger(0)
            val entity = LazyTestEntity("reactivation-test", creationCounter)

            repeat(cycleCount) { cycle ->
                val cycleEvents = Collections.synchronizedList(mutableListOf<MutationEvent<String, LazyTestEntity>>())

                val subscriptions =
                    (1..3).map {
                        entity.subscribe { event -> cycleEvents.add(event) }
                    }

                repeat(mutationsPerCycle) { i ->
                    entity.value = "cycle-$cycle-mutation-$i"
                }
                testDispatcher.scheduler.advanceUntilIdle()

                cycleEvents.size shouldBeGreaterThan 0
                cycleEvents.last().newEntity.value shouldBe "cycle-$cycle-mutation-${mutationsPerCycle - 1}"

                subscriptions.forEach { it.cancel() }
                testDispatcher.scheduler.advanceUntilIdle()
            }

            creationCounter.get() shouldBeGreaterThanOrEqual cycleCount

            val finalEvents = Collections.synchronizedList(mutableListOf<MutationEvent<String, LazyTestEntity>>())
            val finalSubscription = entity.subscribe { event -> finalEvents.add(event) }
            entity.value = "final-check"
            testDispatcher.scheduler.advanceUntilIdle()

            finalEvents.size shouldBe 1
            finalEvents[0].newEntity.value shouldBe "final-check"

            finalSubscription.cancel()
        }
    }
})