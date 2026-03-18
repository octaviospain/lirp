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
import net.transgressoft.lirp.event.ReactiveScope
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

    lateinit var customerRepo: CustomerVolatileRepo
    var orderRepo: VolatileRepository<*, *>? = null

    beforeEach {
        customerRepo = CustomerVolatileRepo()
        orderRepo = null
    }

    afterEach {
        customerRepo.close()
        orderRepo?.close()
        orderRepo = null
    }

    test("CASCADE remove() deletes the referenced Customer from its repository") {
        val customer = Customer(id = 1, name = "Alice")
        customerRepo.add(customer)

        val cascadeOrderRepo = CascadeOrderVolatileRepo().also { orderRepo = it }
        val order = CascadeOrder(id = 100L, customerId = 1)
        cascadeOrderRepo.add(order)

        // Verify setup
        customerRepo.findById(1).shouldBePresent()

        // Remove the parent — cascade should remove the child
        cascadeOrderRepo.remove(order)

        customerRepo.contains(1) shouldBe false
    }

    test("CASCADE clear() deletes all referenced Customers from their repositories") {
        val customer1 = Customer(id = 1, name = "Alice")
        val customer2 = Customer(id = 2, name = "Bob")
        customerRepo.add(customer1)
        customerRepo.add(customer2)

        val cascadeOrderRepo = CascadeOrderVolatileRepo().also { orderRepo = it }
        val order1 = CascadeOrder(id = 100L, customerId = 1)
        val order2 = CascadeOrder(id = 101L, customerId = 2)
        cascadeOrderRepo.add(order1)
        cascadeOrderRepo.add(order2)

        // Verify setup
        customerRepo.size() shouldBe 2

        // Clear all parents — cascade should remove all children
        cascadeOrderRepo.clear()

        customerRepo.size() shouldBe 0
    }

    test("DETACH remove() cancels the bubble-up subscription but referenced Customer remains in repository") {
        val customer = Customer(id = 1, name = "Alice")
        customerRepo.add(customer)

        val detachOrderRepo = DetachOrderVolatileRepo().also { orderRepo = it }
        val order = DetachOrder(id = 100L, customerId = 1)
        detachOrderRepo.add(order)

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
        val customer = Customer(id = 1, name = "Alice")
        customerRepo.add(customer)

        val noneOrderRepo = NoneOrderVolatileRepo().also { orderRepo = it }
        val order = NoneOrder(id = 100L, customerId = 1)
        noneOrderRepo.add(order)

        // Remove the parent with NONE cascade action
        noneOrderRepo.remove(order)

        // Customer still exists
        customerRepo.contains(1) shouldBe true
    }

    test("ReactiveEntityBase close() always executes DETACH cleanup regardless of cascade config") {
        val customer = Customer(id = 1, name = "Alice")
        customerRepo.add(customer)

        val detachOrderRepo = DetachOrderVolatileRepo().also { orderRepo = it }
        val order = DetachOrder(id = 100L, customerId = 1)
        detachOrderRepo.add(order)

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
})