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

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.event.ReactiveScope
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.optional.shouldBeEmpty
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Verifies that aggregate reference operations execute without runtime reflection on the hot path.
 *
 * Absence of `findDelegateField` and `cancelAllBubbleUpSubscriptions` in the compiled class confirms
 * that reflection-based delegate access was removed. All four delegate paths (bind, wire, cascade, detach)
 * are exercised by adding, removing, and closing entities — if any reflection helper were invoked,
 * the corresponding test entity's method invocation would succeed only because reflection was used.
 *
 * This is a structural verification: the compile-time proof is that the removed methods no longer
 * exist in the class, and the runtime proof is that all operations complete successfully without errors.
 */
@DisplayName("RegistryBase")
@OptIn(ExperimentalCoroutinesApi::class)
internal class ReflectionFreeVerificationTest : FunSpec({

    val testDispatcher = UnconfinedTestDispatcher()
    val testScope = CoroutineScope(testDispatcher)

    beforeSpec {
        ReactiveScope.flowScope = testScope
        ReactiveScope.ioScope = testScope
    }

    lateinit var customerRepo: CustomerVolatileRepo
    lateinit var orderRepo: OrderVolatileRepo

    beforeEach {
        customerRepo = CustomerVolatileRepo()
        orderRepo = OrderVolatileRepo()
    }

    afterEach {
        customerRepo.close()
        orderRepo.close()
    }

    test("RegistryBase does not contain findDelegateField reflection helper method") {
        val methodNames = RegistryBase::class.java.declaredMethods.map { it.name }

        methodNames.contains("findDelegateField").shouldBeFalse()
    }

    test("RegistryBase does not contain bindDelegateField reflection helper method") {
        val methodNames = RegistryBase::class.java.declaredMethods.map { it.name }

        methodNames.contains("bindDelegateField").shouldBeFalse()
    }

    test("ReactiveEntityBase does not contain cancelAllBubbleUpSubscriptions reflection scan method") {
        val methodNames = ReactiveEntityBase::class.java.declaredMethods.map { it.name }

        methodNames.contains("cancelAllBubbleUpSubscriptions").shouldBeFalse()
    }

    test("AggregateRefDelegate does not contain bindRegistryUntyped method") {
        val methodNames = AggregateRefDelegate::class.java.declaredMethods.map { it.name }

        methodNames.contains("bindRegistryUntyped").shouldBeFalse()
    }

    test("bindEntityRefs path resolves customer reference after adding order to repository") {
        val customer = Customer(id = 1, name = "Alice")
        val order = Order(id = 100L, customerId = 1)
        customerRepo.add(customer)

        // Adding triggers discoverRefs -> bindEntityRefs via delegateGetter (no getDeclaredField)
        orderRepo.add(order)

        order.customer.resolve() shouldBePresent { it.name shouldBe "Alice" }
    }

    test("wireRefBubbleUp path wires subscription when bubbleUp is true") {
        val customer = Customer(id = 2, name = "Bob")
        val order = BubbleUpOrder(id = 200L, customerId = 2)
        customerRepo.add(customer)

        // BubbleUpOrder has bubbleUp=true, so wireRefBubbleUp is called via delegateGetter
        val bubbleUpRepo = BubbleUpOrderVolatileRepo()
        bubbleUpRepo.add(order)

        var received = false
        order.subscribe { received = true }
        customer.updateName("Bobby")

        received shouldBe true
        bubbleUpRepo.close()
    }

    test("executeCascadeForEntity path removes referenced entity on CASCADE delete") {
        val customer = Customer(id = 3, name = "Charlie")
        val cascadeOrder = CascadeOrder(id = 300L, customerId = 3)
        customerRepo.add(customer)

        val cascadeRepo = CascadeOrderVolatileRepo()
        cascadeRepo.add(cascadeOrder)

        // Removing triggers executeCascadeForEntity via delegateGetter (no findDelegateField)
        cascadeRepo.remove(cascadeOrder)

        // CASCADE: referenced customer is also removed
        customerRepo.findById(3).shouldBeEmpty()
        cascadeRepo.close()
    }

    test("detachAllRefs path via close() cancels bubble-up subscriptions without field scan") {
        val customer = Customer(id = 4, name = "Dana")
        val detachOrder = DetachOrder(id = 400L, customerId = 4)
        customerRepo.add(customer)

        val detachRepo = DetachOrderVolatileRepo()
        detachRepo.add(detachOrder)

        var receivedAfterClose = false
        detachOrder.subscribe { receivedAfterClose = true }

        // close() uses loadRefAccessor + cancelAllBubbleUp — not the old declaredFields scan
        detachOrder.close()
        customer.updateName("Dani")

        // After close, no further events should be received
        receivedAfterClose shouldBe false
        detachRepo.close()
    }
})