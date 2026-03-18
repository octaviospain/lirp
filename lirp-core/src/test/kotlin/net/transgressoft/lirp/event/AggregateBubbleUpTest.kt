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

import net.transgressoft.lirp.persistence.BubbleUpOrder
import net.transgressoft.lirp.persistence.Customer
import net.transgressoft.lirp.persistence.EntityA
import net.transgressoft.lirp.persistence.EntityB
import net.transgressoft.lirp.persistence.EntityC
import net.transgressoft.lirp.persistence.Order
import net.transgressoft.lirp.persistence.RegistryBase
import net.transgressoft.lirp.persistence.VolatileRepository
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Tests for DDD-03: bubble-up event propagation from referenced child entities to parent entity subscribers.
 *
 * Verifies that [AggregateMutationEvent] is delivered to parent subscribers when bubble-up is enabled,
 * silenced when disabled, and that propagation is single-level only (no transitive forwarding).
 */
@DisplayName("AggregateBubbleUpTest")
@OptIn(ExperimentalCoroutinesApi::class)
internal class AggregateBubbleUpTest : FunSpec({

    val testDispatcher = UnconfinedTestDispatcher()
    val testScope = CoroutineScope(testDispatcher)

    beforeSpec {
        ReactiveScope.flowScope = testScope
        ReactiveScope.ioScope = testScope
    }

    lateinit var customerRepo: VolatileRepository<Int, Customer>

    beforeEach {
        customerRepo = VolatileRepository("Customers")
        RegistryBase.registerRegistry(Customer::class.java, customerRepo)
    }

    afterEach {
        RegistryBase.clearRegistries()
    }

    test("BubbleUpOrder receives AggregateMutationEvent when referenced Customer mutates") {
        val customer = Customer(id = 1, name = "Alice")
        customerRepo.add(customer)

        val orderRepo = VolatileRepository<Long, BubbleUpOrder>("BubbleUpOrders")
        val order = BubbleUpOrder(id = 100L, customerId = 1)
        orderRepo.add(order)

        val receivedEvent = AtomicReference<MutationEvent<*, *>>(null)
        val latch = CountDownLatch(1)

        order.subscribe { event ->
            receivedEvent.set(event)
            latch.countDown()
        }

        customer.updateName("Alice Updated")

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        receivedEvent.get().shouldBeInstanceOf<AggregateMutationEvent<*, *>>()
    }

    test("AggregateMutationEvent refName matches the declared reference property name") {
        val customer = Customer(id = 1, name = "Alice")
        customerRepo.add(customer)

        val orderRepo = VolatileRepository<Long, BubbleUpOrder>("BubbleUpOrders")
        val order = BubbleUpOrder(id = 100L, customerId = 1)
        orderRepo.add(order)

        val receivedEvent = AtomicReference<AggregateMutationEvent<*, *>>(null)
        val latch = CountDownLatch(1)

        order.subscribe { event ->
            if (event is AggregateMutationEvent<*, *>) {
                receivedEvent.set(event)
                latch.countDown()
            }
        }

        customer.updateName("Alice Updated")

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        receivedEvent.get()!!.refName shouldBe "customer"
    }

    test("AggregateMutationEvent childEvent contains the original MutationEvent from the referenced Customer") {
        val customer = Customer(id = 1, name = "Alice")
        customerRepo.add(customer)

        val orderRepo = VolatileRepository<Long, BubbleUpOrder>("BubbleUpOrders")
        val order = BubbleUpOrder(id = 100L, customerId = 1)
        orderRepo.add(order)

        val receivedEvent = AtomicReference<AggregateMutationEvent<*, *>>(null)
        val latch = CountDownLatch(1)

        order.subscribe { event ->
            if (event is AggregateMutationEvent<*, *>) {
                receivedEvent.set(event)
                latch.countDown()
            }
        }

        customer.updateName("Alice Updated")

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        val aggregateEvent = receivedEvent.get()!!
        aggregateEvent.childEvent.shouldBeInstanceOf<ReactiveMutationEvent<*, *>>()
        val childMutation = aggregateEvent.childEvent as ReactiveMutationEvent<Int, Customer>
        childMutation.newEntity.name shouldBe "Alice Updated"
        childMutation.oldEntity.name shouldBe "Alice"
    }

    test("Order with bubbleUp=false does NOT receive events when referenced Customer mutates") {
        val customer = Customer(id = 1, name = "Alice")
        customerRepo.add(customer)

        val orderRepo = VolatileRepository<Long, Order>("Orders")
        val order = Order(id = 100L, customerId = 1)
        orderRepo.add(order)

        val receivedEventCount = java.util.concurrent.atomic.AtomicInteger(0)

        order.subscribe { receivedEventCount.incrementAndGet() }

        customer.updateName("Alice Updated")

        // Wait briefly to confirm no event arrives
        Thread.sleep(300)
        receivedEventCount.get() shouldBe 0
    }

    test("Bubble-up propagation is single-level only: EntityA mutation notifies EntityB but NOT EntityC") {
        val repoA = VolatileRepository<Int, EntityA>("EntityAs")
        val repoB = VolatileRepository<Int, EntityB>("EntityBs")
        val repoC = VolatileRepository<Int, EntityC>("EntityCs")

        RegistryBase.registerRegistry(EntityA::class.java, repoA)
        RegistryBase.registerRegistry(EntityB::class.java, repoB)

        val entityA = EntityA(id = 1, value = "original")
        val entityB = EntityB(id = 10, entityAId = 1)
        val entityC = EntityC(id = 100, entityBId = 10)

        repoA.add(entityA)
        repoB.add(entityB)
        repoC.add(entityC)

        val bReceivedLatch = CountDownLatch(1)
        val cReceivedCount = java.util.concurrent.atomic.AtomicInteger(0)

        // EntityB should receive bubble-up from EntityA
        entityB.subscribe { event ->
            if (event is AggregateMutationEvent<*, *>) {
                bReceivedLatch.countDown()
            }
        }

        // EntityC should NOT receive any events — bubble-up is single-level
        entityC.subscribe { cReceivedCount.incrementAndGet() }

        entityA.updateValue("mutated")

        bReceivedLatch.await(2, TimeUnit.SECONDS) shouldBe true
        Thread.sleep(300)
        cReceivedCount.get() shouldBe 0
    }
})