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

import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Tests verifying the public API surface of [LirpContext].
 *
 * Confirms that [LirpContext.registryFor] is accessible from outside the module and that the
 * reified Kotlin overload returns a correctly typed result. Internal methods (register, deregister,
 * registries, reset) are excluded from this test class — their internality is a compile-time guarantee.
 */
@DisplayName("LirpContextPublicApi")
internal class LirpContextPublicApiTest : StringSpec({

    afterEach {
        LirpContext.resetDefault()
    }

    "LirpContext registryFor(Class) returns registered registry" {
        val delegate = VolatileRepository<Int, Customer>("Customers")
        RegistryBase.registerRepository(Customer::class.java, delegate)

        val registry = LirpContext.default.registryFor(Customer::class.java)

        registry.shouldNotBeNull()
        registry shouldBe delegate
    }

    "LirpContext registryFor(Class) returns null for unregistered class" {
        val freshContext = LirpContext()

        val registry = freshContext.registryFor(Customer::class.java)

        registry.shouldBeNull()
    }

    "LirpContext reified registryFor returns typed registry" {
        val delegate = VolatileRepository<Int, Customer>("Customers")
        RegistryBase.registerRepository(Customer::class.java, delegate)

        val registry = LirpContext.default.registryFor<Customer>()

        registry.shouldNotBeNull()
        registry.shouldBeInstanceOf<Registry<*, Customer>>()
        registry shouldBe delegate
    }

    "LirpContext reified registryFor returns null for unregistered type" {
        val freshContext = LirpContext()

        val registry = freshContext.registryFor<Customer>()

        registry.shouldBeNull()
    }

    // register(), deregister(), registries(), reset() remain internal — compile-time guarantee.
    // No runtime test is needed; attempting to call them from outside lirp-core would fail to compile.
})