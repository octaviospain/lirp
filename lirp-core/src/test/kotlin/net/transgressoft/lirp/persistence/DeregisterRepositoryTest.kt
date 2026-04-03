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

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Unit tests for [RegistryBase.deregisterRepository], the public API for removing
 * manually registered repositories from [LirpContext.default].
 *
 * Verifies removal, idempotent no-op for unregistered classes, re-registration after
 * deregister, non-closing semantics, and concurrent register/deregister safety.
 */
@DisplayName("RegistryBase.deregisterRepository()")
internal class DeregisterRepositoryTest : StringSpec({

    afterEach {
        LirpContext.resetDefault()
    }

    "deregisterRepository() removes a registered delegate from LirpContext.default" {
        val delegate = VolatileRepository<Int, Customer>("Customers")
        RegistryBase.registerRepository(Customer::class.java, delegate)

        LirpContext.default.registryFor(Customer::class.java).shouldNotBeNull()

        RegistryBase.deregisterRepository(Customer::class.java)

        LirpContext.default.registryFor(Customer::class.java).shouldBeNull()
    }

    "deregisterRepository() for an unregistered entity class completes without exception" {
        shouldNotThrowAny {
            RegistryBase.deregisterRepository(Customer::class.java)
        }
    }

    "deregisterRepository() then registerRepository() with a new delegate succeeds" {
        val delegate1 = VolatileRepository<Int, Customer>("Customers1")
        RegistryBase.registerRepository(Customer::class.java, delegate1)

        RegistryBase.deregisterRepository(Customer::class.java)

        val delegate2 = VolatileRepository<Int, Customer>("Customers2")
        RegistryBase.registerRepository(Customer::class.java, delegate2)

        LirpContext.default.registryFor(Customer::class.java) shouldBe delegate2
    }

    "deregisterRepository() does not close the repository or its publisher" {
        val delegate = VolatileRepository<Int, Customer>("Customers")
        RegistryBase.registerRepository(Customer::class.java, delegate)

        RegistryBase.deregisterRepository(Customer::class.java)

        val customer = Customer(1, "Alice")
        delegate.add(customer)
        delegate.contains(1) shouldBe true
        delegate.size() shouldBe 1
    }

    "concurrent register and deregister from multiple coroutines does not corrupt registriesMap" {
        val iterations = 100
        val delegates = mutableListOf<VolatileRepository<Int, Customer>>()
        withContext(Dispatchers.Default) {
            (1..iterations).map { i ->
                launch {
                    val delegate = VolatileRepository<Int, Customer>("Customers-$i")
                    synchronized(delegates) { delegates.add(delegate) }
                    try {
                        RegistryBase.registerRepository(Customer::class.java, delegate)
                    } catch (_: IllegalStateException) {
                        // Expected when another coroutine already registered a different instance
                    }
                    RegistryBase.deregisterRepository(Customer::class.java)
                }
            }.forEach { it.join() }
        }

        LirpContext.default.registryFor(Customer::class.java).shouldBeNull()
        delegates.forEach { it.close() }
    }
})