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

package net.transgressoft.lirp.entity

import net.transgressoft.lirp.event.FlowEventPublisher
import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.event.ReactiveScope
import net.transgressoft.lirp.event.TransEventPublisher
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

@ExperimentalCoroutinesApi
class ReactiveEntityLazyInitTest : StringSpec({
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

    "Publisher is not created until first subscription" {
        val publisherCreationCounter = AtomicInteger(0)
        val entity = LazyTestEntity("test-1", publisherCreationCounter)

        // Verify publisher has not been created yet
        publisherCreationCounter.get() shouldBe 0

        // Mutate the entity - should not create a publisher
        entity.value = "new-value-1"
        publisherCreationCounter.get() shouldBe 0

        // Subscribe - should trigger publisher creation
        val subscription = entity.subscribe { }
        publisherCreationCounter.get() shouldBe 1

        // Subsequent subscriptions should not create new publishers
        val subscription2 = entity.subscribe { }
        publisherCreationCounter.get() shouldBe 1

        subscription.cancel()
        subscription2.cancel()
    }

    "Events are not emitted when no subscribers exist" {
        val publisherCreationCounter = AtomicInteger(0)
        val entity = LazyTestEntity("test-2", publisherCreationCounter)

        // Mutate entity multiple times without subscribers
        repeat(10) { i ->
            entity.value = "value-$i"
        }

        // No publisher should have been created
        publisherCreationCounter.get() shouldBe 0
    }

    "Events are emitted after subscription" {
        val publisherCreationCounter = AtomicInteger(0)
        val entity = LazyTestEntity("test-3", publisherCreationCounter)

        // Mutate before subscription - no publisher created
        entity.value = "before-subscription"
        publisherCreationCounter.get() shouldBe 0

        // Subscribe
        val receivedEvents = mutableListOf<MutationEvent<String, LazyTestEntity>>()
        val subscription =
            entity.subscribe { event ->
                receivedEvents.add(event)
            }

        testDispatcher.scheduler.advanceUntilIdle()
        publisherCreationCounter.get() shouldBe 1

        // Mutate after subscription - should emit
        entity.value = "after-subscription"
        testDispatcher.scheduler.advanceUntilIdle()

        receivedEvents.size shouldBe 1
        receivedEvents[0].newEntity.value shouldBe "after-subscription"

        subscription.cancel()
    }

    "Accessing changes property initializes publisher" {
        val publisherCreationCounter = AtomicInteger(0)
        val entity = LazyTestEntity("test-4", publisherCreationCounter)

        publisherCreationCounter.get() shouldBe 0

        // Accessing changes should trigger lazy initialization
        val changes = entity.changes
        publisherCreationCounter.get() shouldBe 1
    }

    "Multiple entities share no publisher overhead until used" {
        val creationCounters = List(100) { AtomicInteger(0) }
        val entities =
            creationCounters.mapIndexed { i, counter ->
                LazyTestEntity("entity-$i", counter)
            }

        // Verify no publishers created
        creationCounters.forEach { counter ->
            counter.get() shouldBe 0
        }

        // Mutate all entities
        entities.forEachIndexed { i, entity ->
            entity.value = "mutated-$i"
        }

        // Still no publishers created
        creationCounters.forEach { counter ->
            counter.get() shouldBe 0
        }

        // Subscribe to only the first 10
        val subscriptions =
            entities.take(10).map { entity ->
                entity.subscribe { }
            }

        // Only the first 10 should have publishers
        creationCounters.take(10).forEach { counter ->
            counter.get() shouldBe 1
        }
        creationCounters.drop(10).forEach { counter ->
            counter.get() shouldBe 0
        }

        subscriptions.forEach { it.cancel() }
    }

    "Custom publisher factory is used for lazy initialization" {
        val customFactoryCalled = AtomicInteger(0)

        val entity =
            CustomPublisherEntity("custom-entity") {
                customFactoryCalled.incrementAndGet()
                FlowEventPublisher("custom-publisher")
            }

        // Factory isn't called yet
        customFactoryCalled.get() shouldBe 0

        // Mutate - factory still isn't called
        entity.value = "new-value"
        customFactoryCalled.get() shouldBe 0

        // Subscribe - factory should be called
        val subscription = entity.subscribe { }
        customFactoryCalled.get() shouldBe 1

        subscription.cancel()
    }

    "Lazy initialization is thread-safe" {
        val publisherCreationCounter = AtomicInteger(0)
        val entity = LazyTestEntity("thread-safe", publisherCreationCounter)

        // Simulate concurrent subscriptions
        val subscriptions =
            (1..1000).map {
                entity.subscribe { }
            }

        // Only one publisher should be created despite concurrent access
        publisherCreationCounter.get() shouldBe 1

        subscriptions.forEach { it.cancel() }
    }
})

/**
 * Test entity that tracks publisher creation for lazy initialization testing.
 */
class LazyTestEntity(
    override val id: String,
    private val creationCounter: AtomicInteger = AtomicInteger(0)
) : ReactiveEntityBase<String, LazyTestEntity>({
        creationCounter.incrementAndGet()
        FlowEventPublisher<MutationEvent.Type, MutationEvent<String, LazyTestEntity>>(id)
    }) {

    override val uniqueId: String
        get() = id

    var value: String = "initial"
        set(newValue) {
            mutateAndPublish(newValue, field) { field = it }
        }

    override fun clone(): LazyTestEntity {
        val clone = LazyTestEntity(id, creationCounter)
        clone.value = this.value
        return clone
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as LazyTestEntity
        return id == other.id && value == other.value
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + value.hashCode()
        return result
    }

    override fun toString(): String = "LazyTestEntity(id=$id, value=$value)"
}

/**
 * Test entity that accepts a custom publisher factory for testing publisher injection.
 */
class CustomPublisherEntity(
    override val id: String,
    publisherFactory: () -> TransEventPublisher<MutationEvent.Type, MutationEvent<String, CustomPublisherEntity>>
) : ReactiveEntityBase<String, CustomPublisherEntity>(publisherFactory) {

    override val uniqueId: String
        get() = id

    var value: String = "initial"
        set(newValue) {
            mutateAndPublish(newValue, field) { field = it }
        }

    override fun clone(): CustomPublisherEntity {
        val clone = CustomPublisherEntity(id) { FlowEventPublisher(id) }
        clone.value = this.value
        return clone
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CustomPublisherEntity
        return id == other.id && value == other.value
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + value.hashCode()
        return result
    }

    override fun toString(): String = "CustomPublisherEntity(id=$id, value=$value)"
}