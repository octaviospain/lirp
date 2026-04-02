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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Integration tests for the delegation-based repository registration pattern.
 *
 * Verifies that a repository wrapper using Kotlin's `by` delegation registers its underlying
 * [VolatileRepository] delegate into [LirpContext.default] via [RegistryBase.registerRepository]
 * called from an `init` block. Tests cover registration on construction, delegate identity,
 * routing of repository operations through the delegate, deregistration on close, independent
 * multi-type registration, and duplicate rejection.
 */
@DisplayName("DelegationRegistrationIntegration")
internal class DelegationRegistrationIntegrationTest : StringSpec({

    afterEach {
        LirpContext.resetDefault()
    }

    "constructing DelegatingCustomerRepo registers the delegate VolatileRepository in LirpContext.default" {
        val delegate = VolatileRepository<Int, Customer>("DelegatingCustomers")
        DelegatingCustomerRepo(delegate)

        val registered = LirpContext.default.registryFor(Customer::class.java)

        registered.shouldNotBeNull()
        registered shouldBe delegate
        registered.shouldBeInstanceOf<VolatileRepository<*, *>>()
    }

    "LirpContext.default.registries() contains exactly one entry keyed by Customer after DelegatingCustomerRepo construction" {
        val delegate = VolatileRepository<Int, Customer>("DelegatingCustomers")
        DelegatingCustomerRepo(delegate)

        val registries = LirpContext.default.registries()

        registries shouldHaveSize 1
        registries shouldContainKey Customer::class.java
        registries[Customer::class.java] shouldBe delegate
    }

    "DelegatingCustomerRepo routes add, contains, and size to the delegate" {
        val delegate = VolatileRepository<Int, Customer>("DelegatingCustomers")
        val wrapper = DelegatingCustomerRepo(delegate)

        val customer = wrapper.create(1, "Alice")

        wrapper.contains(1) shouldBe true
        wrapper.size() shouldBe 1
        delegate.contains(1) shouldBe true
        delegate.size() shouldBe 1
        delegate.findById(1).isPresent shouldBe true
        delegate.findById(1).get() shouldBe customer
    }

    "closing the delegate deregisters it from LirpContext.default" {
        val delegate = VolatileRepository<Int, Customer>("DelegatingCustomers")
        DelegatingCustomerRepo(delegate)

        LirpContext.default.registryFor(Customer::class.java).shouldNotBeNull()

        delegate.close()

        LirpContext.default.registryFor(Customer::class.java).shouldBeNull()
    }

    "two delegation wrappers for different entity types register independently" {
        val customerDelegate = VolatileRepository<Int, Customer>("DelegatingCustomers")
        val orderDelegate = VolatileRepository<Long, Order>("DelegatingOrders")
        DelegatingCustomerRepo(customerDelegate)
        DelegatingOrderRepo(orderDelegate)

        val registries = LirpContext.default.registries()

        registries shouldHaveSize 2
        registries[Customer::class.java] shouldBe customerDelegate
        registries[Order::class.java] shouldBe orderDelegate
    }

    "constructing a second DelegatingCustomerRepo for the same entity class throws ISE" {
        val delegate1 = VolatileRepository<Int, Customer>("DelegatingCustomers1")
        val delegate2 = VolatileRepository<Int, Customer>("DelegatingCustomers2")
        DelegatingCustomerRepo(delegate1)

        shouldThrow<IllegalStateException> {
            DelegatingCustomerRepo(delegate2)
        }.message shouldBe "A repository for Customer is already registered. Only one @LirpRepository per entity type is allowed."
    }
})