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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Unit tests for the [LirpRepository] auto-registration lifecycle.
 *
 * Verifies that annotated repository subclasses register themselves at construction,
 * that duplicate registrations throw [IllegalStateException], that [RegistryBase.close]
 * deregisters the repo, that unannotated repos are skipped, and that re-registration
 * after close succeeds.
 */
@DisplayName("LirpRepositoryDiscovery")
internal class LirpRepositoryDiscoveryTest : FunSpec({

    val openRepos = mutableListOf<AutoCloseable>()

    afterEach {
        openRepos.forEach { it.close() }
        openRepos.clear()
    }

    test("annotated repo auto-registers at construction") {
        val repo = CustomerVolatileRepo().also { openRepos.add(it) }

        RegistryBase.registryFor(Customer::class.java).shouldNotBeNull() shouldBe repo
    }

    test("duplicate registration for same entity type throws ISE") {
        CustomerVolatileRepo().also { openRepos.add(it) }

        shouldThrow<IllegalStateException> {
            CustomerVolatileRepo()
        }.message shouldBe "A repository for Customer is already registered. Only one @LirpRepository per entity type is allowed."
    }

    test("close() deregisters repo") {
        val repo = CustomerVolatileRepo()
        RegistryBase.registryFor(Customer::class.java).shouldNotBeNull()

        repo.close()

        RegistryBase.registryFor(Customer::class.java).shouldBeNull()
    }

    test("unannotated VolatileRepository does not auto-register") {
        VolatileRepository<Int, Customer>("test").also { openRepos.add(it) }

        RegistryBase.registryFor(Customer::class.java).shouldBeNull()
    }

    test("re-registration succeeds after first repo is closed") {
        val repo1 = CustomerVolatileRepo()
        RegistryBase.registryFor(Customer::class.java).shouldNotBeNull()

        repo1.close()
        RegistryBase.registryFor(Customer::class.java).shouldBeNull()

        val repo2 = CustomerVolatileRepo().also { openRepos.add(it) }
        RegistryBase.registryFor(Customer::class.java).shouldNotBeNull() shouldBe repo2
    }
})