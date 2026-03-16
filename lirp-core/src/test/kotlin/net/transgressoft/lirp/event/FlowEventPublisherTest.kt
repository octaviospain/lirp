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

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.event.CrudEvent.Type.CREATE
import net.transgressoft.lirp.event.CrudEvent.Type.DELETE
import net.transgressoft.lirp.event.CrudEvent.Type.UPDATE
import net.transgressoft.lirp.event.StandardCrudEvent.Create
import net.transgressoft.lirp.event.StandardCrudEvent.Delete
import net.transgressoft.lirp.event.StandardCrudEvent.Update
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.UUID
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withContext

@ExperimentalCoroutinesApi
class FlowEventPublisherTest : DescribeSpec({
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

    describe("ReactiveEntity event emission") {
        it("emits change event on property modification") {
            val entity = TestEntity(UUID.randomUUID().toString())
            val receivedEvents = mutableListOf<MutationEvent<String, TestEntity>>()

            val subscription =
                entity.subscribe { event ->
                    receivedEvents.add(event)
                }

            val oldName = entity.name
            val newName = "Updated Name"
            entity.name = newName

            testDispatcher.scheduler.advanceUntilIdle()

            receivedEvents.size shouldBe 1
            val event = receivedEvents[0]
            event.shouldBeInstanceOf<MutationEvent<String, TestEntity>>()
            event.newEntity.name shouldBe newName
            event.oldEntity.name shouldBe oldName

            subscription.cancel()
        }

        it("emits change event when mutating a non public instance variable via method") {
            val entity = TestEntity(UUID.randomUUID().toString())
            val receivedEvents = mutableListOf<MutationEvent<String, TestEntity>>()

            entity.subscribe { event ->
                receivedEvents.add(event)
            }

            entity.addFriendAddress("John", "Apple avenue")

            testDispatcher.scheduler.advanceUntilIdle()

            receivedEvents.size shouldBe 1
            val event = receivedEvents[0]
            event.newEntity.getAddress("John") shouldBe "Apple avenue"
            event.oldEntity.getAddress("John") shouldBe null
        }

        it("does not emit change event when mutating an incorrectly managed property via method") {
            val entity = TestEntity(UUID.randomUUID().toString())
            val receivedEvents = mutableListOf<MutationEvent<String, TestEntity>>()

            entity.subscribe { event ->
                receivedEvents.add(event)
            }

            entity.addUnmanagedProperty("John", "Apple avenue")

            testDispatcher.scheduler.advanceUntilIdle()

            receivedEvents.size shouldBe 0
        }

        it("does not emit change event when property is set to its current value") {
            val entity = TestEntity(UUID.randomUUID().toString())
            val receivedEvents = mutableListOf<MutationEvent<String, TestEntity>>()

            val subscription =
                entity.subscribe { event ->
                    receivedEvents.add(event)
                }

            val oldName = entity.name
            entity.name = oldName // Same value

            testDispatcher.scheduler.advanceUntilIdle()

            receivedEvents.size shouldBe 0

            subscription.cancel()
        }

        it("updates lastDateModified on property modification") {
            val entity = TestEntity(UUID.randomUUID().toString())
            val initialDate = entity.lastDateModified

            // Wait a bit to ensure time difference
            Thread.sleep(10)

            entity.name = "Updated Name"

            entity.lastDateModified.isAfter(initialDate) shouldBe true
        }

        it("includes a deep clone of the old entity in the change event") {
            val entity = TestEntity(UUID.randomUUID().toString())
            val receivedEvents = mutableListOf<MutationEvent<String, TestEntity>>()

            val subscription =
                entity.subscribe { event ->
                    receivedEvents.add(event)
                }

            val originalName = entity.name
            entity.name = "New Name"

            testDispatcher.scheduler.advanceUntilIdle()

            receivedEvents.size shouldBe 1
            val event = receivedEvents[0]

            // Verify the old entity is a proper clone
            event.oldEntity.name shouldBe originalName
            event.newEntity.id shouldBe entity.id

            // Verify it's a different instance
            (event.oldEntity !== entity) shouldBe true

            subscription.cancel()
        }

        it("changes Flow can be collected by Kotlin Flow operators") {
            val entity = TestEntity(UUID.randomUUID().toString())
            testScope.launch {
                val event = entity.changes.first()
                event.shouldBeInstanceOf<MutationEvent<String, TestEntity>>()
                event.newEntity.name shouldBe "Collected via Flow"
            }

            entity.name = "Collected via Flow"
            testDispatcher.scheduler.advanceUntilIdle()
        }
    }

    describe("CRUD event publishing") {
        it("publishes CREATE events") {
            val publisher =
                FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("Publisher").apply {
                    activateEvents(CREATE)
                }
            val receivedEvents = mutableListOf<CrudEvent<String, TestEntity>>()

            val subscription =
                publisher.subscribe { event ->
                    if (event.isCreate()) {
                        receivedEvents.add(event)
                    }
                }

            val entity = TestEntity(UUID.randomUUID().toString())
            publisher.emitAsync(Create(entity))

            testDispatcher.scheduler.advanceUntilIdle()

            receivedEvents.size shouldBe 1
            val event = receivedEvents[0]
            event.entities.shouldContainExactly(mapOf(entity.id to entity))

            subscription.cancel()
        }

        it("publishes UPDATE events") {
            val publisher =
                FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("Publisher").apply {
                    activateEvents(UPDATE)
                }
            val receivedEvents = mutableListOf<CrudEvent<String, TestEntity>>()

            val subscription =
                publisher.subscribe { event ->
                    if (event.isUpdate()) {
                        receivedEvents.add(event)
                    }
                }

            val uuid = UUID.randomUUID().toString()
            val originalEntity = TestEntity(uuid)
            val updatedEntity = TestEntity(uuid)
            updatedEntity.name = "Updated Name"

            publisher.emitAsync(Update(updatedEntity, originalEntity))

            testDispatcher.scheduler.advanceUntilIdle()

            receivedEvents.size shouldBe 1
            val event = receivedEvents[0]
            event.entities.shouldContainExactly(mapOf(updatedEntity.id to updatedEntity))
            event.oldEntities.shouldContainExactly(mapOf(originalEntity.id to originalEntity))

            subscription.cancel()
        }

        it("publishes DELETE events") {
            val publisher =
                FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("Publisher").apply {
                    activateEvents(DELETE)
                }
            val receivedEvents = mutableListOf<CrudEvent<String, TestEntity>>()

            val subscription =
                publisher.subscribe { event ->
                    if (event.isDelete()) {
                        receivedEvents.add(event)
                    }
                }

            val entity = TestEntity(UUID.randomUUID().toString())
            publisher.emitAsync(Delete(entity))

            testDispatcher.scheduler.advanceUntilIdle()

            receivedEvents.size shouldBe 1
            val event = receivedEvents[0]
            event.entities.shouldContainExactly(mapOf(entity.id to entity))

            subscription.cancel()
        }

        it("dispatches events to type-specific subscribers") {
            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("Publisher")
            val createCounter = AtomicInteger(0)
            val updateCounter = AtomicInteger(0)
            val deleteCounter = AtomicInteger(0)

            val createSubscription = publisher.subscribe(CREATE) { createCounter.incrementAndGet() }
            val updateSubscription = publisher.subscribe(UPDATE) { updateCounter.incrementAndGet() }
            val deleteSubscription = publisher.subscribe(DELETE) { deleteCounter.incrementAndGet() }

            val entity = TestEntity(UUID.randomUUID().toString())
            publisher.emitAsync(Create(entity))

            val uuid = UUID.randomUUID().toString()
            val originalEntity = TestEntity(uuid)
            val updatedEntity = TestEntity(uuid)
            updatedEntity.name = "Updated Name"
            publisher.emitAsync(Update(updatedEntity, originalEntity))

            publisher.emitAsync(Delete(entity))

            testDispatcher.scheduler.advanceUntilIdle()

            createSubscription.cancel()
            updateSubscription.cancel()
            deleteSubscription.cancel()
        }

        it("StandardCrudEvent Update requires consistent entity collections") {
            val entity1 = TestEntity("entity-1")
            val entity2 = TestEntity("entity-2")
            val oldEntity1 = TestEntity("entity-1")

            // Valid update - same keys and size
            val validUpdate = Update(entity1, oldEntity1)
            validUpdate.entities.size shouldBe 1
            validUpdate.oldEntities.size shouldBe 1

            // Valid update with multiple entities
            val oldEntity2 = TestEntity("entity-2")
            val validMultiUpdate: CrudEvent<String, TestEntity> = Update(listOf(entity1, entity2), listOf(oldEntity1, oldEntity2))
            validMultiUpdate.entities.size shouldBe 2

            // Invalid update - different sizes
            shouldThrow<IllegalArgumentException> {
                Update(mapOf(entity1.id to entity1), mapOf(oldEntity1.id to oldEntity1, entity2.id to oldEntity2))
            }.message shouldContain "consistent"

            // Invalid update - different keys
            shouldThrow<IllegalArgumentException> {
                Update(mapOf(entity1.id to entity1), mapOf(entity2.id to oldEntity2))
            }.message shouldContain "consistent"
        }
    }

    describe("Subscription types") {
        it("distributes events to multiple subscribers") {
            val entity = TestEntity(UUID.randomUUID().toString())
            val counter1 = AtomicInteger(0)
            val counter2 = AtomicInteger(0)

            val subscription1 = entity.subscribe { counter1.incrementAndGet() }
            val subscription2 = entity.subscribe { counter2.incrementAndGet() }

            entity.name = "First update"
            entity.name = "Second update"

            testDispatcher.scheduler.advanceUntilIdle()

            counter1.get() shouldBe 2
            counter2.get() shouldBe 2

            subscription1.cancel()
            entity.name = "Third update"

            testDispatcher.scheduler.advanceUntilIdle()

            counter1.get() shouldBe 2 // Unchanged after cancellation
            counter2.get() shouldBe 3

            subscription2.cancel()
        }

        it("supports Java Consumer subscriptions") {
            val entity = TestEntity(UUID.randomUUID().toString())
            val counter = AtomicInteger(0)

            val subscription = entity.subscribe(Consumer { counter.incrementAndGet() })

            entity.name = "Updated via Consumer"

            testDispatcher.scheduler.advanceUntilIdle()

            counter.get() shouldBe 1

            subscription.cancel()
        }

        it("supports Java Flow.Subscriber subscriptions") {
            val entity = TestEntity(UUID.randomUUID().toString())
            val counter = AtomicInteger(0)

            val subscriber =
                object : Flow.Subscriber<MutationEvent<String, TestEntity>> {
                    override fun onSubscribe(subscription: Flow.Subscription) {
                        // In a real subscriber, you'd maybe call subscription.request(Long.MAX_VALUE) here, although in this library doesn't make sense
                    }

                    override fun onNext(item: MutationEvent<String, TestEntity>) {
                        counter.incrementAndGet()
                    }

                    override fun onError(throwable: Throwable) {}

                    override fun onComplete() {}
                }

            entity.subscribe(subscriber)

            entity.name = "Updated via Flow.Subscriber"

            testDispatcher.scheduler.advanceUntilIdle()

            counter.get() shouldBe 1
        }

        it("cancelled subscription stops receiving events immediately") {
            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("TestPublisher")
            publisher.activateEvents(CREATE)

            val receivedEvents = mutableListOf<CrudEvent<String, TestEntity>>()
            val subscription = publisher.subscribe { receivedEvents.add(it) }

            publisher.emitAsync(Create(TestEntity("before-cancel")))
            testDispatcher.scheduler.advanceUntilIdle()
            receivedEvents.size shouldBe 1

            subscription.cancel()

            publisher.emitAsync(Create(TestEntity("after-cancel")))
            testDispatcher.scheduler.advanceUntilIdle()

            // Should not have received event after cancellation
            receivedEvents.size shouldBe 1
        }
    }

    describe("Event ordering and backpressure") {
        it("processes events in order despite rapid emission") {
            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("TestPublisher")
            publisher.activateEvents(CREATE, UPDATE, DELETE)

            val receivedEvents = mutableListOf<CrudEvent<String, TestEntity>>()
            val subscription =
                publisher.subscribe { event ->
                    receivedEvents.add(event)
                }

            // Emit events rapidly
            repeat(100) { i ->
                val entity = TestEntity("entity-$i")
                publisher.emitAsync(Create(entity))
            }

            testDispatcher.scheduler.advanceUntilIdle()

            receivedEvents.size shouldBe 100
            receivedEvents.forEachIndexed { index, event ->
                event.entities.values.first().id shouldBe "entity-$index"
            }

            subscription.cancel()
        }

        it("multiple subscribers all receive all events in order") {
            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("TestPublisher")
            publisher.activateEvents(CREATE)

            val subscriber1Events = mutableListOf<CrudEvent<String, TestEntity>>()
            val subscriber2Events = mutableListOf<CrudEvent<String, TestEntity>>()
            val subscriber3Events = mutableListOf<CrudEvent<String, TestEntity>>()

            val sub1 = publisher.subscribe { subscriber1Events.add(it) }
            val sub2 = publisher.subscribe { subscriber2Events.add(it) }
            val sub3 = publisher.subscribe { subscriber3Events.add(it) }

            repeat(50) { i ->
                publisher.emitAsync(Create(TestEntity("entity-$i")))
            }

            testDispatcher.scheduler.advanceUntilIdle()

            subscriber1Events.size shouldBe 50
            subscriber2Events.size shouldBe 50
            subscriber3Events.size shouldBe 50

            // All subscribers received same events in same order
            subscriber1Events.map { it.entities.values.first().id } shouldBe subscriber2Events.map { it.entities.values.first().id }
            subscriber2Events.map { it.entities.values.first().id } shouldBe subscriber3Events.map { it.entities.values.first().id }

            sub1.cancel()
            sub2.cancel()
            sub3.cancel()
        }

        it("subscriber can safely emit new events during event handling") {
            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("TestPublisher")
            publisher.activateEvents(CREATE, UPDATE)

            val allEvents = mutableListOf<CrudEvent<String, TestEntity>>()
            val processedCount = AtomicInteger(0)

            val subscription =
                publisher.subscribe { event ->
                    allEvents.add(event)
                    processedCount.incrementAndGet()

                    // Emit a follow-up event during processing (simulating cascading updates)
                    if (event.isCreate()) {
                        val entity = event.entities.values.first()
                        val updatedEntity = TestEntity(entity.id).apply { }
                        publisher.emitAsync(Update(updatedEntity, entity))
                    }
                }

            publisher.emitAsync(Create(TestEntity("entity-1")))

            testDispatcher.scheduler.advanceUntilIdle()

            // Should have processed: 1 CREATE + 1 UPDATE
            processedCount.get() shouldBe 2
            allEvents.size shouldBe 2
            allEvents[0].isCreate() shouldBe true
            allEvents[1].isUpdate() shouldBe true

            subscription.cancel()
        }

        it("channel backpressure handles burst of events without loss") {
            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("TestPublisher")
            publisher.activateEvents(CREATE)

            val receivedEvents = mutableListOf<CrudEvent<String, TestEntity>>()

            val subscription =
                publisher.subscribe { event ->
                    receivedEvents.add(event)
                    // Simulate slow subscriber
                    delay(1.milliseconds)
                }

            // Burst emit 1000 events rapidly
            repeat(1000) { i ->
                publisher.emitAsync(Create(TestEntity("entity-$i")))
            }

            testDispatcher.scheduler.advanceUntilIdle()

            // All events should be received despite slow processing
            receivedEvents.size shouldBe 1000
            receivedEvents.forEachIndexed { index, event ->
                event.entities.values.first().id shouldBe "entity-$index"
            }

            subscription.cancel()
        }

        it("late subscriber receives no historical events (no replay)") {
            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("TestPublisher")
            publisher.activateEvents(CREATE)

            // Emit events before subscription
            repeat(10) { i ->
                publisher.emitAsync(Create(TestEntity("early-$i")))
            }

            testDispatcher.scheduler.advanceUntilIdle()

            val lateSubscriberEvents = mutableListOf<CrudEvent<String, TestEntity>>()
            val lateSubscription = publisher.subscribe { lateSubscriberEvents.add(it) }

            testDispatcher.scheduler.advanceUntilIdle()

            // Late subscriber should not receive earlier events
            lateSubscriberEvents.size shouldBe 0

            // But should receive new events
            publisher.emitAsync(Create(TestEntity("new-1")))
            testDispatcher.scheduler.advanceUntilIdle()

            lateSubscriberEvents.size shouldBe 1
            lateSubscriberEvents[0].entities.values.first().id shouldBe "new-1"

            lateSubscription.cancel()
        }
    }

    describe("Publisher configuration") {
        it("can be configured with REAL_TIME config") {
            val publisher =
                FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>(
                    "RealTimePublisher",
                    PublisherConfig.REAL_TIME
                ).apply {
                    activateEvents(CREATE)
                }

            val receivedEvents = mutableListOf<CrudEvent<String, TestEntity>>()
            val subscription = publisher.subscribe { receivedEvents.add(it) }

            val entity = TestEntity(UUID.randomUUID().toString())
            publisher.emitAsync(Create(entity))

            testDispatcher.scheduler.advanceUntilIdle()

            receivedEvents.size shouldBe 1
            receivedEvents[0].entities.values.first().id shouldBe entity.id

            subscription.cancel()
        }

        it("can be configured with LOW_MEMORY config") {
            val publisher =
                FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>(
                    "LowMemoryPublisher",
                    PublisherConfig.LOW_MEMORY
                ).apply {
                    activateEvents(CREATE)
                }

            val receivedEvents = mutableListOf<CrudEvent<String, TestEntity>>()
            val subscription = publisher.subscribe { receivedEvents.add(it) }

            val entity = TestEntity(UUID.randomUUID().toString())
            publisher.emitAsync(Create(entity))

            testDispatcher.scheduler.advanceUntilIdle()

            receivedEvents.size shouldBe 1
            receivedEvents[0].entities.values.first().id shouldBe entity.id

            subscription.cancel()
        }

        it("with replay config delivers historical events to late subscribers") {
            val replayCount = 3
            val publisher =
                FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>(
                    "ReplayPublisher",
                    PublisherConfig.withReplay(replayCount)
                ).apply {
                    activateEvents(CREATE)
                }

            // Emit events before any subscriber exists
            val earlyEntities = List(5) { i -> TestEntity("early-entity-$i") }
            earlyEntities.forEach { entity ->
                publisher.emitAsync(Create(entity))
            }

            testDispatcher.scheduler.advanceUntilIdle()

            // Late subscriber should receive the last 3 events (replay count)
            val lateSubscriberEvents = mutableListOf<CrudEvent<String, TestEntity>>()
            val lateSubscription = publisher.subscribe { lateSubscriberEvents.add(it) }

            testDispatcher.scheduler.advanceUntilIdle()

            // Should have received the replayed events
            lateSubscriberEvents.size shouldBe replayCount
            lateSubscriberEvents[0].entities.values.first().id shouldBe "early-entity-2"
            lateSubscriberEvents[1].entities.values.first().id shouldBe "early-entity-3"
            lateSubscriberEvents[2].entities.values.first().id shouldBe "early-entity-4"

            // New events should also be received
            val newEntity = TestEntity("new-entity")
            publisher.emitAsync(Create(newEntity))
            testDispatcher.scheduler.advanceUntilIdle()

            lateSubscriberEvents.size shouldBe replayCount + 1
            lateSubscriberEvents.last().entities.values.first().id shouldBe newEntity.id

            lateSubscription.cancel()
        }
    }

    describe("PublisherConfig channelCapacity") {
        it("DEFAULT config uses Channel.UNLIMITED for channelCapacity") {
            PublisherConfig.DEFAULT.channelCapacity shouldBe Channel.UNLIMITED
        }

        it("REAL_TIME config uses bounded channelCapacity of 64") {
            PublisherConfig.REAL_TIME.channelCapacity shouldBe 64
        }

        it("LOW_MEMORY config uses bounded channelCapacity of 128") {
            PublisherConfig.LOW_MEMORY.channelCapacity shouldBe 128
        }

        it("custom channelCapacity is applied to PublisherConfig") {
            val config = PublisherConfig(channelCapacity = 32)
            config.channelCapacity shouldBe 32
        }

        it("FlowEventPublisher with bounded channel accepts events up to capacity") {
            val publisher =
                FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>(
                    "BoundedPublisher",
                    PublisherConfig(channelCapacity = 4)
                ).apply {
                    activateEvents(CREATE)
                }

            val receivedEvents = mutableListOf<CrudEvent<String, TestEntity>>()
            val subscription = publisher.subscribe { receivedEvents.add(it) }

            repeat(4) { i ->
                publisher.emitAsync(Create(TestEntity("entity-$i")))
            }

            testDispatcher.scheduler.advanceUntilIdle()

            receivedEvents.size shouldBe 4

            subscription.cancel()
        }
    }

    describe("Closed-state lifecycle") {
        it("isClosed returns false on new publisher") {
            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("test-publisher")

            publisher.isClosed shouldBe false
        }

        it("close marks publisher as closed") {
            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("test-publisher")

            publisher.close()

            publisher.isClosed shouldBe true
        }

        it("close is idempotent") {
            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("test-publisher")

            publisher.close()
            publisher.close() // second call must not throw
        }

        it("emitAsync throws IllegalStateException on closed publisher") {
            val publisher =
                FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("test-publisher").apply {
                    activateEvents(CREATE)
                }
            publisher.close()

            val exception =
                shouldThrow<IllegalStateException> {
                    publisher.emitAsync(Create(TestEntity("entity-1")))
                }
            exception.message shouldContain "test-publisher"
        }

        it("subscribe with action throws IllegalStateException on closed publisher") {
            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("test-publisher")
            publisher.close()

            val exception =
                shouldThrow<IllegalStateException> {
                    publisher.subscribe { }
                }
            exception.message shouldContain "test-publisher"
        }

        it("subscribe with Consumer throws IllegalStateException on closed publisher") {
            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("test-publisher")
            publisher.close()

            val exception =
                shouldThrow<IllegalStateException> {
                    publisher.subscribe(Consumer { })
                }
            exception.message shouldContain "test-publisher"
        }

        it("subscribe with event types throws IllegalStateException on closed publisher") {
            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("test-publisher")
            publisher.close()

            val exception =
                shouldThrow<IllegalStateException> {
                    publisher.subscribe(CREATE) { }
                }
            exception.message shouldContain "test-publisher"
        }

        it("subscribe with Flow.Subscriber throws IllegalStateException on closed publisher") {
            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("test-publisher")
            publisher.close()

            val subscriber =
                object : Flow.Subscriber<CrudEvent<String, TestEntity>> {
                    override fun onSubscribe(subscription: Flow.Subscription) {}

                    override fun onNext(item: CrudEvent<String, TestEntity>) {}

                    override fun onError(throwable: Throwable) {}

                    override fun onComplete() {}
                }

            val exception =
                shouldThrow<IllegalStateException> {
                    publisher.subscribe(subscriber)
                }
            exception.message shouldContain "test-publisher"
        }

        it("existing subscribers stop receiving events after close") {
            val publisher =
                FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("test-publisher").apply {
                    activateEvents(CREATE)
                }
            val receivedEvents = mutableListOf<CrudEvent<String, TestEntity>>()
            publisher.subscribe { receivedEvents.add(it) }

            publisher.emitAsync(Create(TestEntity("before-close")))
            testDispatcher.scheduler.advanceUntilIdle()
            receivedEvents.size shouldBe 1

            publisher.close()

            // Emitting after close throws — no new event reaches the subscriber
            shouldThrow<IllegalStateException> {
                publisher.emitAsync(Create(TestEntity("after-close")))
            }
            testDispatcher.scheduler.advanceUntilIdle()

            receivedEvents.size shouldBe 1
        }
    }

    describe("TOCTOU safety") {
        it("emitAsync uses activatedEventTypes snapshot to avoid TOCTOU race") {
            // Run on real threads so that concurrent activate/disable and emit happen in parallel
            withContext(Dispatchers.Default) {
                val publisher =
                    FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>(
                        "ToctouPublisher",
                        PublisherConfig.DEFAULT
                    ).apply {
                        activateEvents(CREATE)
                    }
                publisher.subscribe { }

                val iterations = 1000
                val toggleJob =
                    launch {
                        repeat(iterations) {
                            publisher.disableEvents(CREATE)
                            publisher.activateEvents(CREATE)
                        }
                    }
                val emitJob =
                    launch {
                        repeat(iterations) { i ->
                            publisher.emitAsync(Create(TestEntity("entity-$i")))
                        }
                    }

                joinAll(toggleJob, emitJob)

                publisher.close()
            }
            // The test passes if no exception is thrown during concurrent operation
        }

        it("emitAsync filters events when event type is not activated") {
            val publisher =
                FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("FilterPublisher").apply {
                    activateEvents(UPDATE)
                }
            val receivedEvents = mutableListOf<CrudEvent<String, TestEntity>>()
            val subscription = publisher.subscribe { receivedEvents.add(it) }

            publisher.emitAsync(Create(TestEntity("entity-1")))
            testDispatcher.scheduler.advanceUntilIdle()

            receivedEvents.size shouldBe 0

            subscription.cancel()
        }
    }

    describe("Subscriber count tracking") {
        it("subscriberCount is 0 on new publisher") {
            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("test-publisher")

            publisher.subscriberCount shouldBe 0
        }

        it("subscriberCount increments on subscribe") {
            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("test-publisher")

            val subscription = publisher.subscribe { }

            publisher.subscriberCount shouldBe 1

            subscription.cancel()
        }

        it("subscriberCount decrements on cancel") {
            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("test-publisher")

            val subscription = publisher.subscribe { }
            publisher.subscriberCount shouldBe 1

            subscription.cancel()
            testDispatcher.scheduler.advanceUntilIdle()

            publisher.subscriberCount shouldBe 0
        }

        it("subscriberCount tracks multiple subscribers correctly") {
            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("test-publisher")

            val sub1 = publisher.subscribe { }
            publisher.subscriberCount shouldBe 1

            val sub2 = publisher.subscribe { }
            publisher.subscriberCount shouldBe 2

            sub1.cancel()
            testDispatcher.scheduler.advanceUntilIdle()
            publisher.subscriberCount shouldBe 1

            sub2.cancel()
            testDispatcher.scheduler.advanceUntilIdle()
            publisher.subscriberCount shouldBe 0
        }

        it("subscriberCount tracks Flow.Subscriber subscribe") {
            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("test-publisher")

            val subscriber =
                object : Flow.Subscriber<CrudEvent<String, TestEntity>> {
                    override fun onSubscribe(subscription: Flow.Subscription) {}

                    override fun onNext(item: CrudEvent<String, TestEntity>) {}

                    override fun onError(throwable: Throwable) {}

                    override fun onComplete() {}
                }

            publisher.subscribe(subscriber)

            publisher.subscriberCount shouldBe 1
        }
    }
})

class TestEntity(override val id: String) : ReactiveEntityBase<String, TestEntity>({ _ ->
    FlowEventPublisher(id, closeOnEmpty = true)
}) {

    private val addressBook = mutableMapOf<String, String>()

    private val nonManagedProperty = mutableMapOf<String, String>()

    var name: String = "Initial Name"
        set(value) {
            mutateAndPublish(value, field) { field = it }
        }

    override val uniqueId = "$id-$name"

    var description: String = "Initial Description"
        set(value) {
            mutateAndPublish(value, field) { field = it }
        }

    fun addFriendAddress(name: String, address: String) {
        mutateAndPublish {
            addressBook[name] = address
        }
    }

    fun addUnmanagedProperty(name: String, address: String) {
        mutateAndPublish {
            nonManagedProperty[name] = address
        }
    }

    fun getAddress(name: String) = addressBook[name]

    override fun clone(): TestEntity {
        val clone = TestEntity(id)
        clone.name = this.name
        clone.description = this.description
        return clone
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TestEntity
        if (name != other.name) return false
        if (description != other.description) return false
        return addressBook == other.addressBook
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + description.hashCode()
        result = 31 * result + addressBook.hashCode()
        return result
    }

    override fun toString(): String = "TestEntity(id=$id, name=$name, description=$description)"
}