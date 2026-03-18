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
 * Each test adds an [Order] to a [VolatileRepository], which triggers reference discovery and
 * binding via [RegistryBase]. The [customer] reference is then resolved against a separately
 * maintained [Customer] repository.
 */
@DisplayName("AggregateRefDelegate")
@OptIn(ExperimentalCoroutinesApi::class)
internal class AggregateRefResolutionTest : FunSpec({

    val testDispatcher = UnconfinedTestDispatcher()
    val testScope = CoroutineScope(testDispatcher)

    beforeSpec {
        ReactiveScope.flowScope = testScope
        ReactiveScope.ioScope = testScope
    }

    lateinit var customerRepo: VolatileRepository<Int, Customer>
    lateinit var orderRepo: VolatileRepository<Long, Order>

    beforeEach {
        customerRepo = VolatileRepository<Int, Customer>("Customers")
        orderRepo = VolatileRepository<Long, Order>("Orders")
        RegistryBase.registerRegistry(Customer::class.java, customerRepo)
    }

    afterEach {
        RegistryBase.clearRegistries()
    }

    test("resolve returns the referenced customer entity when it exists in the repository") {
        val customer = Customer(id = 1, name = "Alice")
        customerRepo.add(customer)

        val order = Order(id = 100L, customerId = 1)
        orderRepo.add(order)

        val resolved = order.customer.resolve()
        resolved.shouldBePresent { it shouldBe customer }
    }

    test("resolve returns Optional.empty when the referenced customer does not exist in the repository") {
        val order = Order(id = 100L, customerId = 999)
        orderRepo.add(order)

        order.customer.resolve().shouldBeEmpty()
    }

    test("resolve returns updated entity after the referenced customerId field changes") {
        val customer1 = Customer(id = 1, name = "Alice")
        val customer2 = Customer(id = 2, name = "Bob")
        customerRepo.add(customer1)
        customerRepo.add(customer2)

        val order = Order(id = 100L, customerId = 1)
        orderRepo.add(order)

        order.customer.resolve().shouldBePresent { it shouldBe customer1 }

        // Change the referenced customer ID — cache must be invalidated
        order.customerId = 2

        order.customer.resolve().shouldBePresent { it shouldBe customer2 }
    }

    test("resolve returns Optional.empty after referenced customer is removed from repository") {
        val customer = Customer(id = 1, name = "Alice")
        customerRepo.add(customer)

        val order = Order(id = 100L, customerId = 1)
        orderRepo.add(order)

        // Confirm initial resolution works
        order.customer.resolve().shouldBePresent()

        // Remove the customer from its repository
        customerRepo.remove(customer)

        // Cache should not return stale data — findById called fresh each time
        order.customer.resolve().shouldBeEmpty()
    }
})