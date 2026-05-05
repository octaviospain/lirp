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
 * Verifies that [FlowEventPublisher]'s SupervisorJob-based coroutine isolation guarantees hold:
 * an exception thrown by one subscriber's coroutine does not prevent other subscribers from
 * receiving events.
 *
 * All tests use [FlowEventPublisher] directly (not TestEntity) to avoid interference from
 * closeOnEmpty semantics. The test scope includes a [SupervisorJob] to mirror production
 * [ReactiveScope] behaviour so that child coroutine failures do not cancel the parent.
 */
@ExperimentalCoroutinesApi
@SerializeWithReactiveScope
class ExceptionIsolationTest : DescribeSpec({

    val testDispatcher = UnconfinedTestDispatcher()

    beforeSpec {
        // SupervisorJob is REQUIRED: without it, a failing child coroutine would cancel the
        // parent scope and prevent all other subscriber coroutines from running.
        ReactiveScope.flowScope = CoroutineScope(testDispatcher + SupervisorJob())
        ReactiveScope.ioScope = CoroutineScope(testDispatcher + SupervisorJob())
    }

    afterSpec {
        ReactiveScope.resetDefaultFlowScope()
        ReactiveScope.resetDefaultIoScope()
    }

    describe("Subscriber exception isolation") {

        it("exception in one subscriber does not prevent other subscribers from receiving events") {
            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("isolation-test-1")
            publisher.activateEvents(CREATE)

            // Throwing subscriber — unconditional exception on every event
            publisher.subscribe { throw RuntimeException("intentional subscriber exception") }

            val healthyCounter1 = AtomicInteger(0)
            val healthyCounter2 = AtomicInteger(0)
            publisher.subscribe { healthyCounter1.incrementAndGet() }
            publisher.subscribe { healthyCounter2.incrementAndGet() }

            repeat(15) { i ->
                publisher.emitAsync(Create(TestEntity("e-$i")))
            }
            testDispatcher.scheduler.advanceUntilIdle()

            healthyCounter1.get() shouldBe 15
            healthyCounter2.get() shouldBe 15
        }

        it("multiple throwing subscribers do not prevent healthy subscriber from receiving events") {
            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("isolation-test-2")
            publisher.activateEvents(CREATE)

            // Two throwing subscribers
            publisher.subscribe { throw RuntimeException("thrower-1 exception") }
            publisher.subscribe { throw RuntimeException("thrower-2 exception") }

            val healthyCounter = AtomicInteger(0)
            publisher.subscribe { healthyCounter.incrementAndGet() }

            repeat(15) { i ->
                publisher.emitAsync(Create(TestEntity("e-$i")))
            }
            testDispatcher.scheduler.advanceUntilIdle()

            healthyCounter.get() shouldBe 15
        }

        it("exception isolation works across subscribe overloads") {
            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("isolation-test-3")
            publisher.activateEvents(CREATE)

            // Throwing subscriber registered via the plain subscribe() overload
            publisher.subscribe { throw RuntimeException("intentional exception from plain subscribe") }

            // Healthy subscriber registered via the filtered subscribe(eventType) overload
            val healthyCounter = AtomicInteger(0)
            publisher.subscribe(CREATE) { healthyCounter.incrementAndGet() }

            repeat(15) { i ->
                publisher.emitAsync(Create(TestEntity("e-$i")))
            }
            testDispatcher.scheduler.advanceUntilIdle()

            healthyCounter.get() shouldBe 15
        }
    }
})