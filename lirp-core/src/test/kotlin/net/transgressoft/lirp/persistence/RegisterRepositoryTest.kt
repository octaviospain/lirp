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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk

/**
 * Unit tests for [RegistryBase.registerRepository], the public API for delegation-based
 * repository registration into [LirpContext.default].
 *
 * Verifies registration succeeds, idempotent same-instance calls are allowed, different-instance
 * duplicates throw [IllegalStateException], non-RegistryBase instances throw [IllegalArgumentException],
 * close() deregisters, and re-registration after close succeeds.
 */
@DisplayName("RegistryBase.registerRepository()")
internal class RegisterRepositoryTest : StringSpec({

    afterEach {
        LirpContext.resetDefault()
    }

    "registers delegate RegistryBase in LirpContext.default keyed by entity class" {
        val delegate = VolatileRepository<Int, Customer>("Customers")

        RegistryBase.registerRepository(Customer::class.java, delegate)

        LirpContext.default.registryFor(Customer::class.java) shouldBe delegate
    }

    "registerRepository() called twice with the same instance is idempotent" {
        val delegate = VolatileRepository<Int, Customer>("Customers")

        RegistryBase.registerRepository(Customer::class.java, delegate)
        RegistryBase.registerRepository(Customer::class.java, delegate)

        LirpContext.default.registryFor(Customer::class.java) shouldBe delegate
    }

    "registerRepository() with a different instance for same entity class throws ISE" {
        val delegate1 = VolatileRepository<Int, Customer>("Customers1")
        val delegate2 = VolatileRepository<Int, Customer>("Customers2")

        RegistryBase.registerRepository(Customer::class.java, delegate1)

        shouldThrow<IllegalStateException> {
            RegistryBase.registerRepository(Customer::class.java, delegate2)
        }.message shouldBe "A repository for Customer is already registered. Only one @LirpRepository per entity type is allowed."
    }

    "registerRepository() with a non-RegistryBase Registry instance throws IAE" {
        val nonRegistryBase = mockk<Repository<Int, Customer>>()

        shouldThrow<IllegalArgumentException> {
            RegistryBase.registerRepository(Customer::class.java, nonRegistryBase)
        }.message shouldContain "Only RegistryBase instances can be registered"
    }

    "close() on delegate deregisters from LirpContext.default" {
        val delegate = VolatileRepository<Int, Customer>("Customers")

        RegistryBase.registerRepository(Customer::class.java, delegate)
        LirpContext.default.registryFor(Customer::class.java) shouldBe delegate

        delegate.close()

        LirpContext.default.registryFor(Customer::class.java) shouldBe null
    }

    "registerRepository() with a delegate from a non-default context throws IAE" {
        val customContext = LirpContext()
        val delegate = VolatileRepository<Int, Customer>(customContext, "Customers")

        try {
            shouldThrow<IllegalArgumentException> {
                RegistryBase.registerRepository(Customer::class.java, delegate)
            }.message shouldContain "registerRepository() only supports RegistryBase instances created in LirpContext.default"
        } finally {
            delegate.close()
        }
    }

    "registerRepository() succeeds after close() and re-registration" {
        val delegate1 = VolatileRepository<Int, Customer>("Customers")

        RegistryBase.registerRepository(Customer::class.java, delegate1)
        delegate1.close()

        LirpContext.default.registryFor(Customer::class.java) shouldBe null

        val delegate2 = VolatileRepository<Int, Customer>("Customers2")
        RegistryBase.registerRepository(Customer::class.java, delegate2)

        LirpContext.default.registryFor(Customer::class.java) shouldBe delegate2
    }
})