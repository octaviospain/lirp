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

import net.transgressoft.lirp.event.ReactiveScope
import net.transgressoft.lirp.testing.SerializeWithReactiveScope
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.optional.shouldBeEmpty
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Tests for DDD-02: aggregate reference resolve() returns entities from bound repositories.
 *
 * Each test creates a fresh [LirpContext] for isolation. Adding an [Order] to a [VolatileRepository]
 * triggers reference discovery and binding via [RegistryBase]. The [customer] reference is then
 * resolved against a separately maintained [Customer] repository in the same context.
 */
@DisplayName("AggregateRefDelegate")
@OptIn(ExperimentalCoroutinesApi::class)
@SerializeWithReactiveScope
internal class AggregateRefResolutionTest : FunSpec({

    val testDispatcher = UnconfinedTestDispatcher()
    val testScope = CoroutineScope(testDispatcher)

    beforeSpec {
        ReactiveScope.flowScope = testScope
        ReactiveScope.ioScope = testScope
    }

    lateinit var ctx: LirpContext
    lateinit var customerRepo: CustomerVolatileRepo
    lateinit var orderRepo: OrderVolatileRepo

    beforeEach {
        ctx = LirpContext()
        customerRepo = CustomerVolatileRepo(ctx)
        orderRepo = OrderVolatileRepo(ctx)
    }

    afterEach {
        ctx.close()
    }

    test("resolve returns the referenced customer entity when it exists in the repository") {
        customerRepo.create(id = 1, name = "Alice")

        val order = orderRepo.create(id = 100L, customerId = 1)

        val resolved = order.customer.resolve()
        resolved.shouldBePresent { it shouldBe customerRepo.findById(1).get() }
    }

    test("resolve returns Optional.empty when the referenced customer does not exist in the repository") {
        val order = orderRepo.create(id = 100L, customerId = 999)

        order.customer.resolve().shouldBeEmpty()
    }

    test("resolve returns updated entity after the referenced customerId field changes") {
        val customer1 = customerRepo.create(id = 1, name = "Alice")
        val customer2 = customerRepo.create(id = 2, name = "Bob")

        val order = orderRepo.create(id = 100L, customerId = 1)

        order.customer.resolve().shouldBePresent { it shouldBe customer1 }

        // Change the referenced customer ID — cache must be invalidated
        order.customerId = 2

        order.customer.resolve().shouldBePresent { it shouldBe customer2 }
    }

    test("resolve returns Optional.empty after referenced customer is removed from repository") {
        val customer = customerRepo.create(id = 1, name = "Alice")

        val order = orderRepo.create(id = 100L, customerId = 1)

        // Confirm initial resolution works
        order.customer.resolve().shouldBePresent()

        // Remove the customer from its repository
        customerRepo.remove(customer)

        // Cache should not return stale data — findById called fresh each time
        order.customer.resolve().shouldBeEmpty()
    }

    test("optionalAggregate resolve returns empty when FK is null") {
        val optionalRepo = OptionalRefOrderVolatileRepo(ctx)
        val order = optionalRepo.create(id = 200L, customerId = null)

        order.customer.resolve().shouldBeEmpty()
    }

    test("optionalAggregate resolve returns entity when FK is set") {
        val optionalRepo = OptionalRefOrderVolatileRepo(ctx)
        customerRepo.create(id = 5, name = "Charlie")
        val order = optionalRepo.create(id = 200L, customerId = 5)

        order.customer.resolve().shouldBePresent { it.name shouldBe "Charlie" }
    }

    test("optionalAggregate isOptional returns true") {
        val order = OptionalRefOrder(id = 200L, customerId = null)
        (order.customer as AggregateRefDelegate<*, *>).isOptional shouldBe true
    }

    test("optionalAggregate referenceId throws when FK is null") {
        val order = OptionalRefOrder(id = 200L, customerId = null)
        io.kotest.assertions.throwables.shouldThrow<IllegalStateException> {
            order.customer.referenceId
        }
    }

    test("optionalAggregate resolve returns empty before registry binding") {
        val order = OptionalRefOrder(id = 200L, customerId = 5)
        // Not added to any repo — delegate not bound
        order.customer.resolve().shouldBeEmpty()
    }

    test("cross-context isolation: order in context B cannot resolve customer registered only in context A") {
        val ctxA = LirpContext()
        val ctxB = LirpContext()

        try {
            val customerRepoA = CustomerVolatileRepo(ctxA)
            customerRepoA.create(id = 1, name = "Alice")

            val orderRepoB = OrderVolatileRepo(ctxB)
            val order = orderRepoB.create(id = 100L, customerId = 1)

            // Context B has no customer repo — resolution should return empty
            order.customer.resolve().shouldBeEmpty()
        } finally {
            ctxA.close()
            ctxB.close()
        }
    }
})