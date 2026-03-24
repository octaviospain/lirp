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
 * Unit tests for the [LirpRepository] auto-registration lifecycle using scoped [LirpContext] instances.
 *
 * Verifies that annotated repository subclasses register themselves at construction into the given
 * context, that duplicate registrations throw [IllegalStateException], that [RegistryBase.close]
 * deregisters the repo, that unannotated repos are skipped, that re-registration after close succeeds,
 * and that [LirpContext.close] closes all registered repositories.
 */
@DisplayName("LirpRepositoryDiscovery")
internal class LirpRepositoryDiscoveryTest : FunSpec({

    lateinit var ctx: LirpContext

    beforeEach {
        ctx = LirpContext()
    }

    afterEach {
        ctx.close()
    }

    test("annotated repo auto-registers at construction") {
        val repo = CustomerVolatileRepo(ctx)

        ctx.registryFor(Customer::class.java).shouldNotBeNull() shouldBe repo
    }

    test("duplicate registration for same entity type throws ISE") {
        CustomerVolatileRepo(ctx)

        shouldThrow<IllegalStateException> {
            CustomerVolatileRepo(ctx)
        }.message shouldBe "A repository for Customer is already registered. Only one @LirpRepository per entity type is allowed."
    }

    test("close() deregisters repo from context") {
        val repo = CustomerVolatileRepo(ctx)
        ctx.registryFor(Customer::class.java).shouldNotBeNull()

        repo.close()

        ctx.registryFor(Customer::class.java).shouldBeNull()
    }

    test("unannotated VolatileRepository does not auto-register") {
        val unannotated = VolatileRepository<Int, Customer>("test")
        try {
            ctx.registryFor(Customer::class.java).shouldBeNull()
        } finally {
            unannotated.close()
        }
    }

    test("re-registration succeeds after first repo is closed") {
        val repo1 = CustomerVolatileRepo(ctx)
        ctx.registryFor(Customer::class.java).shouldNotBeNull()

        repo1.close()
        ctx.registryFor(Customer::class.java).shouldBeNull()

        val repo2 = CustomerVolatileRepo(ctx)
        ctx.registryFor(Customer::class.java).shouldNotBeNull() shouldBe repo2
    }

    test("LirpContext.close() closes all registered repositories") {
        val customerRepo = CustomerVolatileRepo(ctx)
        val orderRepo = OrderVolatileRepo(ctx)

        ctx.registries().size shouldBe 2

        ctx.close()

        customerRepo.isClosed shouldBe true
        orderRepo.isClosed shouldBe true
        ctx.registries().size shouldBe 0
    }

    test("LirpContext.close() is idempotent") {
        CustomerVolatileRepo(ctx)

        ctx.close()
        ctx.close() // must not throw
    }

    test("LirpContext reset clears registry map without closing repositories") {
        val repo = CustomerVolatileRepo(ctx)

        ctx.registries().size shouldBe 1

        ctx.reset()

        ctx.registries().isEmpty() shouldBe true
        repo.create(1, "Alice")
        repo.contains(1) shouldBe true
    }

    test("LirpContext resetDefault clears registries from the default context") {
        val repo = CustomerVolatileRepo()

        LirpContext.default.registries().size shouldBe 1

        LirpContext.resetDefault()

        LirpContext.default.registries().isEmpty() shouldBe true

        repo.close()
    }
})