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

package net.transgressoft.lirp.persistence

import net.transgressoft.lirp.event.AggregateMutationEvent
import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.event.ReactiveScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Tests for DDD-04: cascade behavior when the referencing entity is removed from its repository.
 *
 * Verifies that [CascadeOrder] (CASCADE) removes the referenced [Customer], [DetachOrder] (DETACH)
 * only cancels subscription, and [NoneOrder] (NONE) does nothing on delete.
 */
@DisplayName("AggregateCascadeTest")
@OptIn(ExperimentalCoroutinesApi::class)
internal class AggregateCascadeTest : FunSpec({

    val testDispatcher = UnconfinedTestDispatcher()
    val testScope = CoroutineScope(testDispatcher)

    beforeSpec {
        ReactiveScope.flowScope = testScope
        ReactiveScope.ioScope = testScope
    }

    lateinit var ctx: LirpContext

    beforeEach {
        ctx = LirpContext()
    }

    afterEach {
        ctx.close()
    }

    test("CASCADE remove() deletes the referenced Customer from its repository") {
        val customerRepo = CustomerVolatileRepo(ctx)
        val customer = customerRepo.create(id = 1, name = "Alice")

        val cascadeOrderRepo = CascadeOrderVolatileRepo(ctx)
        cascadeOrderRepo.create(id = 100L, customerId = 1)

        // Verify setup
        customerRepo.findById(1).shouldBePresent()

        // Remove the parent — cascade should remove the child
        cascadeOrderRepo.remove(cascadeOrderRepo.findById(100L).get())

        customerRepo.contains(1) shouldBe false
    }

    test("CASCADE clear() deletes all referenced Customers from their repositories") {
        val customerRepo = CustomerVolatileRepo(ctx)
        customerRepo.create(id = 1, name = "Alice")
        customerRepo.create(id = 2, name = "Bob")

        val cascadeOrderRepo = CascadeOrderVolatileRepo(ctx)
        cascadeOrderRepo.create(id = 100L, customerId = 1)
        cascadeOrderRepo.create(id = 101L, customerId = 2)

        // Verify setup
        customerRepo.size() shouldBe 2

        // Clear all parents — cascade should remove all children
        cascadeOrderRepo.clear()

        customerRepo.size() shouldBe 0
    }

    test("DETACH remove() cancels the bubble-up subscription but referenced Customer remains in repository") {
        val customerRepo = CustomerVolatileRepo(ctx)
        val customer = customerRepo.create(id = 1, name = "Alice")

        val detachOrderRepo = DetachOrderVolatileRepo(ctx)
        val order = detachOrderRepo.create(id = 100L, customerId = 1)

        // Subscribe to order events (to check bubble-up is active before detach)
        val eventCountBefore = AtomicInteger(0)
        val beforeLatch = CountDownLatch(1)
        val subscription =
            order.subscribe { event ->
                if (event is AggregateMutationEvent<*, *>) {
                    eventCountBefore.incrementAndGet()
                    beforeLatch.countDown()
                }
            }

        // Confirm bubble-up is active
        customer.updateName("Alice Updated")
        beforeLatch.await(2, TimeUnit.SECONDS) shouldBe true
        eventCountBefore.get() shouldBe 1

        // Remove the parent — DETACH should cancel subscription, customer stays
        detachOrderRepo.remove(order)

        // Customer still exists
        customerRepo.contains(1) shouldBe true

        // After removal, the order should not receive further events
        val eventCountAfter = AtomicInteger(0)
        customer.updateName("Alice Updated Again")
        Thread.sleep(300)
        eventCountAfter.get() shouldBe 0
        subscription.cancel()
    }

    test("NONE remove() does nothing — referenced Customer stays in repository and subscription stays active") {
        val customerRepo = CustomerVolatileRepo(ctx)
        customerRepo.create(id = 1, name = "Alice")

        val noneOrderRepo = NoneOrderVolatileRepo(ctx)
        val order = noneOrderRepo.create(id = 100L, customerId = 1)

        // Remove the parent with NONE cascade action
        noneOrderRepo.remove(order)

        // Customer still exists
        customerRepo.contains(1) shouldBe true
    }

    test("RESTRICT remove() throws IllegalStateException when another entity still references the target Customer") {
        val customerRepo = CustomerVolatileRepo(ctx)
        customerRepo.create(id = 1, name = "Alice")

        val restrictOrderRepo = RestrictOrderVolatileRepo(ctx)
        val order1 = restrictOrderRepo.create(id = 100L, customerId = 1)
        restrictOrderRepo.create(id = 101L, customerId = 1)

        // order1 references customer; order2 also references customer
        // Removing order1 should throw because order2 still references customer
        val exception =
            shouldThrow<IllegalStateException> {
                restrictOrderRepo.remove(order1)
            }
        exception.message shouldContain "Cannot cascade-delete"
    }

    test("RESTRICT remove() allows deletion when no other entity references the target Customer") {
        val customerRepo = CustomerVolatileRepo(ctx)
        customerRepo.create(id = 1, name = "Alice")

        val restrictOrderRepo = RestrictOrderVolatileRepo(ctx)
        val order = restrictOrderRepo.create(id = 100L, customerId = 1)

        // Only order references customer — removal proceeds without error
        restrictOrderRepo.remove(order)

        // Customer still exists (RESTRICT does not cascade-delete, just prevents if others reference)
        customerRepo.contains(1) shouldBe true
    }

    test("CASCADE on a cyclic reference graph throws IllegalStateException with cycle detected message") {
        val cyclicParentRepo = CyclicParentVolatileRepo(ctx)
        val cyclicChildRepo = CyclicChildVolatileRepo(ctx)

        val parent = cyclicParentRepo.create(id = 1L, childId = 2L)
        cyclicChildRepo.create(id = 2L, parentId = 1L)

        val exception =
            shouldThrow<IllegalStateException> {
                cyclicParentRepo.remove(parent)
            }
        exception.message shouldContain "Cascade cycle detected"
    }

    test("CASCADE on an already-removed entity logs warning and returns without error") {
        val customerRepo = CustomerVolatileRepo(ctx)
        customerRepo.create(id = 1, name = "Alice")

        val cascadeOrderRepo = CascadeOrderVolatileRepo(ctx)
        val order1 = cascadeOrderRepo.create(id = 100L, customerId = 1)
        val order2 = cascadeOrderRepo.create(id = 101L, customerId = 1)

        // Remove order1 — customer gets cascade-deleted
        cascadeOrderRepo.remove(order1)
        customerRepo.contains(1) shouldBe false

        // Remove order2 — customer already gone, should complete without error (not throw)
        cascadeOrderRepo.remove(order2)
        customerRepo.contains(1) shouldBe false
    }

    test("Concurrent wireBubbleUp and cancelBubbleUp do not leak subscriptions") {
        val customerRepo = CustomerVolatileRepo(ctx)
        val customer = customerRepo.create(id = 1, name = "Alice")

        val bubbleUpOrderRepo = BubbleUpOrderVolatileRepo(ctx)
        val order = bubbleUpOrderRepo.create(id = 100L, customerId = 1)

        // Cast to AggregateRefDelegate to access wireBubbleUp/cancelBubbleUp directly.
        // order.customer returns this (the delegate itself) via getValue().
        val delegate = order.customer as AggregateRefDelegate<Customer, Int>

        // Launch 50 coroutines: even-indexed wire, odd-indexed cancel
        runBlocking {
            (0 until 50).map { index ->
                launch(Dispatchers.Default) {
                    if (index % 2 == 0) {
                        delegate.wireBubbleUp(order, "customer")
                    } else {
                        delegate.cancelBubbleUp()
                    }
                }
            }.joinAll()
        }

        // Final clean state: cancel any residual subscription
        delegate.cancelBubbleUp()

        // After final cancel, no events should be forwarded
        val eventCount = AtomicInteger(0)
        order.subscribe { event ->
            if (event is AggregateMutationEvent<*, *>) {
                eventCount.incrementAndGet()
            }
        }

        customer.updateName("Alice Updated After Concurrent Storm")
        Thread.sleep(300)
        eventCount.get() shouldBe 0
    }

    test("ReactiveEntityBase close() always executes DETACH cleanup regardless of cascade config") {
        val customerRepo = CustomerVolatileRepo(ctx)
        val customer = customerRepo.create(id = 1, name = "Alice")

        val detachOrderRepo = DetachOrderVolatileRepo(ctx)
        val order = detachOrderRepo.create(id = 100L, customerId = 1)

        val eventCount = AtomicInteger(0)
        val initialLatch = CountDownLatch(1)

        order.subscribe { event ->
            if (event is AggregateMutationEvent<*, *>) {
                eventCount.incrementAndGet()
                initialLatch.countDown()
            }
        }

        // Verify bubble-up is active
        customer.updateName("Alice Updated")
        initialLatch.await(2, TimeUnit.SECONDS) shouldBe true
        eventCount.get() shouldBe 1

        // Close the order entity (not remove from repository)
        order.close()

        // After close, no more bubble-up events should reach the order
        customer.updateName("Alice Updated Again")
        Thread.sleep(300)
        eventCount.get() shouldBe 1 // still 1, no new events
    }

    test("DetachOrder bubble-up subscription is cancelled when the order is removed from its repository") {
        val customerRepo = CustomerVolatileRepo(ctx)
        val detachOrderRepo = DetachOrderVolatileRepo(ctx)

        val customer = customerRepo.create(id = 1, name = "Alice")
        val order = detachOrderRepo.create(id = 100L, customerId = 1)

        val received = mutableListOf<MutationEvent<Long, DetachOrder>>()
        order.subscribe { received.add(it) }

        customer.updateName("Bob")
        testDispatcher.scheduler.advanceUntilIdle()

        received.size shouldBe 1

        detachOrderRepo.remove(order)

        customer.updateName("Charlie")
        testDispatcher.scheduler.advanceUntilIdle()

        received.size shouldBe 1
    }
})