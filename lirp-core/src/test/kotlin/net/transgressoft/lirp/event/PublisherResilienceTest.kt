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

import net.transgressoft.lirp.event.CrudEvent.Type.CREATE
import net.transgressoft.lirp.event.StandardCrudEvent.Create
import net.transgressoft.lirp.testing.SerializeWithReactiveScope
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Verifies that [FlowEventPublisher] remains fully operational after a subscriber throws an
 * exception: new subscriptions can be registered, existing healthy subscribers continue
 * receiving events, and the publisher's subscriber count correctly reflects only active
 * (non-crashed) subscribers.
 *
 * All tests use [FlowEventPublisher] directly (not TestEntity) to avoid interference from
 * closeOnEmpty semantics. The test scope includes a [SupervisorJob] to mirror production
 * [ReactiveScope] behaviour so that child coroutine failures do not cancel the parent.
 */
@ExperimentalCoroutinesApi
@SerializeWithReactiveScope
class PublisherResilienceTest : DescribeSpec({

    val testDispatcher = UnconfinedTestDispatcher()

    beforeSpec {
        // SupervisorJob is REQUIRED: without it, a failing child coroutine would cancel the
        // parent scope and prevent subsequent operations from working correctly.
        ReactiveScope.flowScope = CoroutineScope(testDispatcher + SupervisorJob())
        ReactiveScope.ioScope = CoroutineScope(testDispatcher + SupervisorJob())
    }

    afterSpec {
        ReactiveScope.resetDefaultFlowScope()
        ReactiveScope.resetDefaultIoScope()
    }

    describe("Publisher resilience after subscriber exception") {

        it("accepts new subscriptions after a subscriber threw an exception") {
            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("resilience-test-1")
            publisher.activateEvents(CREATE)

            // Throwing subscriber — its coroutine will crash on every received event
            publisher.subscribe { throw RuntimeException("intentional exception") }

            // First emission phase: throwing subscriber crashes
            repeat(5) { i ->
                publisher.emitAsync(Create(TestEntity("e-$i")))
            }
            testDispatcher.scheduler.advanceUntilIdle()

            // Register a NEW subscriber after the throwing one has crashed
            val newCounter = AtomicInteger(0)
            publisher.subscribe { newCounter.incrementAndGet() }

            // Second emission phase: only new subscriber should be active
            repeat(5) { i ->
                publisher.emitAsync(Create(TestEntity("new-e-$i")))
            }
            testDispatcher.scheduler.advanceUntilIdle()

            newCounter.get() shouldBe 5
            publisher.isClosed shouldBe false
        }

        it("existing healthy subscribers continue receiving events after another subscriber throws") {
            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("resilience-test-2")
            publisher.activateEvents(CREATE)

            // Register both a throwing subscriber and a healthy one simultaneously
            publisher.subscribe { throw RuntimeException("intentional exception") }
            val healthyCounter = AtomicInteger(0)
            publisher.subscribe { healthyCounter.incrementAndGet() }

            // First emission phase: both subscribers active, throwing one crashes
            repeat(10) { i ->
                publisher.emitAsync(Create(TestEntity("e-$i")))
            }
            testDispatcher.scheduler.advanceUntilIdle()

            // Healthy subscriber must have received all events during the throwing phase
            healthyCounter.get() shouldBe 10

            // Second emission phase: throwing subscriber's coroutine is dead, healthy continues
            repeat(5) { i ->
                publisher.emitAsync(Create(TestEntity("post-e-$i")))
            }
            testDispatcher.scheduler.advanceUntilIdle()

            healthyCounter.get() shouldBe 15
        }

        it("subscriberCount reflects only active subscribers after a subscriber exception") {
            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("resilience-test-3")
            publisher.activateEvents(CREATE)

            // Register one throwing and two healthy subscribers
            publisher.subscribe { throw RuntimeException("intentional exception") }
            publisher.subscribe { /* healthy subscriber 1 */ }
            publisher.subscribe { /* healthy subscriber 2 */ }

            // Emit events to trigger the throwing subscriber's crash and let its coroutine terminate
            repeat(5) { i ->
                publisher.emitAsync(Create(TestEntity("e-$i")))
            }
            testDispatcher.scheduler.advanceUntilIdle()

            // The throwing subscriber's coroutine has terminated, so its count was decremented
            publisher.subscriberCount shouldBe 2
        }
    }
})