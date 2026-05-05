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

import net.transgressoft.lirp.persistence.BubbleUpOrderVolatileRepo
import net.transgressoft.lirp.persistence.Customer
import net.transgressoft.lirp.persistence.CustomerVolatileRepo
import net.transgressoft.lirp.persistence.EntityAVolatileRepo
import net.transgressoft.lirp.persistence.EntityBVolatileRepo
import net.transgressoft.lirp.persistence.EntityCVolatileRepo
import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.persistence.MutableRefOrderVolatileRepo
import net.transgressoft.lirp.persistence.OrderVolatileRepo
import net.transgressoft.lirp.testing.SerializeWithReactiveScope
import io.kotest.assertions.nondeterministic.continually
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
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
@Suppress("UNCHECKED_CAST")
@SerializeWithReactiveScope
internal class AggregateBubbleUpTest : FunSpec({

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

    test("BubbleUpOrder receives AggregateMutationEvent when referenced Customer mutates") {
        val customerRepo = CustomerVolatileRepo(ctx)
        val customer = customerRepo.create(id = 1, name = "Alice")

        val orderRepo = BubbleUpOrderVolatileRepo(ctx)
        val order = orderRepo.create(id = 100L, customerId = 1)

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
        val customerRepo = CustomerVolatileRepo(ctx)
        val customer = customerRepo.create(id = 1, name = "Alice")

        val orderRepo = BubbleUpOrderVolatileRepo(ctx)
        val order = orderRepo.create(id = 100L, customerId = 1)

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
        receivedEvent.get().refName shouldBe "customer"
    }

    test("AggregateMutationEvent childEvent contains the original MutationEvent from the referenced Customer") {
        val customerRepo = CustomerVolatileRepo(ctx)
        val customer = customerRepo.create(id = 1, name = "Alice")

        val orderRepo = BubbleUpOrderVolatileRepo(ctx)
        val order = orderRepo.create(id = 100L, customerId = 1)

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
        val aggregateEvent = receivedEvent.get()
        aggregateEvent.childEvent.shouldBeInstanceOf<ReactiveMutationEvent<Int, Customer>>()
        val childMutation = aggregateEvent.childEvent as ReactiveMutationEvent<Int, Customer>
        childMutation.newEntity.name shouldBe "Alice Updated"
        childMutation.oldEntity.name shouldBe "Alice"
    }

    test("Order with bubbleUp=false does NOT receive events when referenced Customer mutates") {
        val customerRepo = CustomerVolatileRepo(ctx)
        val customer = customerRepo.create(id = 1, name = "Alice")

        val orderRepo = OrderVolatileRepo(ctx)
        val order = orderRepo.create(id = 100L, customerId = 1)

        val receivedEventCount = java.util.concurrent.atomic.AtomicInteger(0)

        order.subscribe { receivedEventCount.incrementAndGet() }

        customer.updateName("Alice Updated")

        // Wait briefly to confirm no event arrives
        continually(300.milliseconds) { receivedEventCount.get() shouldBe 0 }
    }

    test("Bubble-up re-wires to new entity after reference ID change via mutateAndPublish") {
        val customerRepo = CustomerVolatileRepo(ctx)
        val customer1 = customerRepo.create(id = 1, name = "Alice")
        val customer2 = customerRepo.create(id = 2, name = "Bob")

        val orderRepo = MutableRefOrderVolatileRepo(ctx)
        val order = orderRepo.create(id = 100L, customerId = 1)

        val latch1 = CountDownLatch(1)
        val receivedCount = java.util.concurrent.atomic.AtomicInteger(0)

        order.subscribe { event ->
            if (event is AggregateMutationEvent<*, *>) {
                receivedCount.incrementAndGet()
                latch1.countDown()
            }
        }

        // Verify initial wiring: customer1 mutation arrives
        customer1.updateName("Alice Updated")
        latch1.await(2, TimeUnit.SECONDS) shouldBe true
        receivedCount.get() shouldBe 1

        // Change reference to customer2, then trigger re-wire via resolve()
        order.changeCustomer(2)
        order.customer.resolve()

        val latch2 = CountDownLatch(1)
        order.subscribe { event ->
            if (event is AggregateMutationEvent<*, *>) {
                latch2.countDown()
            }
        }

        // Mutate customer2 — should arrive (re-wired)
        customer2.updateName("Bob Updated")
        latch2.await(2, TimeUnit.SECONDS) shouldBe true

        // Mutate customer1 — should NOT produce further aggregate events
        val countBeforeOldMutation = receivedCount.get()
        customer1.updateName("Alice Again")
        // No additional events from old customer1 subscription
        continually(300.milliseconds) { receivedCount.get() shouldBe countBeforeOldMutation }
    }

    test("Bubble-up stays on old entity when new reference ID does not resolve") {
        val customerRepo = CustomerVolatileRepo(ctx)
        val customer1 = customerRepo.create(id = 1, name = "Alice")

        val orderRepo = MutableRefOrderVolatileRepo(ctx)
        val order = orderRepo.create(id = 100L, customerId = 1)

        val eventCount = java.util.concurrent.atomic.AtomicInteger(0)
        val latch = CountDownLatch(1)

        order.subscribe { event ->
            if (event is AggregateMutationEvent<*, *>) {
                eventCount.incrementAndGet()
                latch.countDown()
            }
        }

        // Verify initial wiring
        customer1.updateName("Alice Updated")
        latch.await(2, TimeUnit.SECONDS) shouldBe true

        // Change to non-existent ID — re-wire should fail, old subscription preserved
        order.changeCustomer(999)
        order.customer.resolve() // triggers re-wire attempt — should fail

        // Old subscription to customer1 still active
        val countBefore = eventCount.get()
        customer1.updateName("Alice Again")
        eventually(5.seconds) { eventCount.get() shouldBe countBefore + 1 }
    }

    test("Bubble-up re-wires after initially unresolvable new ID becomes available") {
        val customerRepo = CustomerVolatileRepo(ctx)
        val customer1 = customerRepo.create(id = 1, name = "Alice")

        val orderRepo = MutableRefOrderVolatileRepo(ctx)
        val order = orderRepo.create(id = 100L, customerId = 1)

        // Change to ID 2 — not yet in repo
        order.changeCustomer(2)
        order.customer.resolve() // re-wire fails, old sub (or none if customer1 was initial) stays

        // Add customer2
        val customer2 = customerRepo.create(id = 2, name = "Bob")

        // Trigger re-wire again — now succeeds
        order.customer.resolve()

        val eventCount = java.util.concurrent.atomic.AtomicInteger(0)
        val latch = CountDownLatch(1)

        order.subscribe { event ->
            if (event is AggregateMutationEvent<*, *>) {
                eventCount.incrementAndGet()
                latch.countDown()
            }
        }

        // Mutate customer2 — should arrive after successful re-wire
        customer2.updateName("Bob Updated")
        latch.await(2, TimeUnit.SECONDS) shouldBe true
        eventCount.get() shouldBe 1
    }

    test("Bubble-up propagation is single-level only: EntityA mutation notifies EntityB but NOT EntityC") {
        val repoA = EntityAVolatileRepo(ctx)
        val repoB = EntityBVolatileRepo(ctx)
        val repoC = EntityCVolatileRepo(ctx)

        val entityA = repoA.create(id = 1, value = "original")
        val entityB = repoB.create(id = 10, entityAId = 1)
        val entityC = repoC.create(id = 100, entityBId = 10)

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
        continually(300.milliseconds) { cReceivedCount.get() shouldBe 0 }
    }

    test("BubbleUpOrder added before its Customer exists completes wireBubbleUp without throwing") {
        val customerRepo = CustomerVolatileRepo(ctx)
        val bubbleUpOrderRepo = BubbleUpOrderVolatileRepo(ctx)

        val order = bubbleUpOrderRepo.create(id = 1L, customerId = 999)

        order.customer.resolve().isPresent shouldBe false

        customerRepo.create(id = 999, name = "LateCustomer")
        order.customer.resolve().isPresent shouldBe true
    }
})