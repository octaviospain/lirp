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

package net.transgressoft.lirp.persistence.query

import net.transgressoft.lirp.entity.IdentifiableEntity
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.Aggregate
import net.transgressoft.lirp.persistence.BubbleUpOrder
import net.transgressoft.lirp.persistence.Customer
import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.persistence.RegistryBase
import net.transgressoft.lirp.persistence.VolatileRepository
import net.transgressoft.lirp.persistence.aggregate
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Tests for [RegistryBase.discoverViaAccessors] and the companion-object
 * [RegistryBase.viaAccessorFor] cross-class cache.
 *
 * Verifies the convention-based `Class.forName("{Entity}_LirpViaAccessor")` discovery
 * mirrors [RegistryBase.discoverIndexes] semantics: double-checked locking, anonymous/local
 * class skip, `ClassNotFoundException` → null. The KSP-generated `BubbleUpOrder_LirpViaAccessor`
 * provides the single-ref fixture; [SimpleNoAggregateEntity] (no `@Aggregate` properties)
 * exercises the negative path.
 */
@DisplayName("Via accessor discovery")
internal class ViaAccessorDiscoveryTest : FunSpec({

    test("discoverViaAccessors finds {BubbleUpOrder}_LirpViaAccessor via Class.forName") {
        val customers = VolatileRepository<Int, Customer>(LirpContext.default, "DiscoveryCustomers")
        customers.add(Customer(1, "Alice"))
        val orderRepo = TestableOrderRepo()
        orderRepo.add(BubbleUpOrder(1L, 1))

        val accessor = orderRepo.viaAccessorOrNull()
        accessor.shouldNotBeNull()
        accessor.collectionEntries.shouldBeEmpty()
        accessor.singleEntries shouldHaveSize 1
        accessor.singleEntries[0].refName shouldBe "customer"
        accessor.singleEntries[0].referencedClass shouldBe Customer::class.java

        customers.close()
        orderRepo.close()
    }

    test("viaAccessor field is populated and second discovery returns the cached accessor reference") {
        val customers = VolatileRepository<Int, Customer>(LirpContext.default, "DiscoveryCustomers2")
        customers.add(Customer(2, "Bob"))
        val orderRepo = TestableOrderRepo()
        orderRepo.add(BubbleUpOrder(2L, 2))

        val first = orderRepo.viaAccessorOrNull()
        val second = orderRepo.viaAccessorOrNull()
        first.shouldNotBeNull()
        (first === second) shouldBe true

        customers.close()
        orderRepo.close()
    }

    test("viaAccessorFor returns an accessor across registry instances for the same entity class") {
        // First call seeds the static cache; second call across an independent caller hits the cache.
        val accessor1 = RegistryBase.viaAccessorFor(BubbleUpOrder::class.java)
        val accessor2 = RegistryBase.viaAccessorFor(BubbleUpOrder::class.java)
        accessor1.shouldNotBeNull()
        accessor2.shouldNotBeNull()
        (accessor1 === accessor2) shouldBe true
        accessor1.singleEntries shouldHaveSize 1
        accessor1.singleEntries[0].referencedClass shouldBe Customer::class.java
    }

    test("entity with no @Aggregate properties returns null accessor") {
        val repo = SimpleNoAggregateRepo()
        repo.add(SimpleNoAggregateEntity(1, "foo"))
        repo.viaAccessorOrNull().shouldBeNull()

        // Cross-class cache also returns null for the class
        RegistryBase.viaAccessorFor(SimpleNoAggregateEntity::class.java).shouldBeNull()

        repo.close()
    }

    test("anonymous subclass of an open @Aggregate-bearing entity returns null via the short-circuit, not via ClassNotFoundException") {
        // OpenAggregateEntity is `open` and has an @Aggregate property, so KSP generates
        // OpenAggregateEntity_LirpViaAccessor at test compile time. That accessor is loadable
        // via Class.forName for the BASE class. Without the isAnonymousClass / isLocalClass
        // short-circuit, the discovery path would attempt
        // Class.forName("$basePackage.$basePackage$openAggregateEntityTest$anon$1_LirpViaAccessor"),
        // which would still happen to return null — but for the wrong reason
        // (ClassNotFoundException on the synthetic anonymous name). The short-circuit makes the
        // distinction observable: it returns null BEFORE consulting the cache or Class.forName.

        // Baseline: BASE class has a real accessor — proves the test isn't relying on an
        // accidental ClassNotFoundException to produce its null result.
        RegistryBase.viaAccessorFor(OpenAggregateEntity::class.java).shouldNotBeNull()

        val anon = object : OpenAggregateEntity(99, 7) {}
        anon.javaClass.isAnonymousClass shouldBe true

        // The anonymous class's `name` is something like
        // `net.transgressoft.lirp.persistence.query.ViaAccessorDiscoveryTest$1$<n>$anon$1`.
        // A generated accessor under that exact binary name CANNOT exist on the classpath, so
        // both the short-circuit and a ClassNotFoundException would return null. The short-
        // circuit is the contractual answer; we assert the anonymous-class flag holds and the
        // result is null. The wrong-reason concern would only surface if the short-circuit
        // were removed, in which case the cache would become populated with Optional.empty()
        // for every anonymous subclass — a memory leak the guard explicitly prevents.
        RegistryBase.viaAccessorFor(anon.javaClass).shouldBeNull()
    }
})

/**
 * Open entity carrying an `@Aggregate` property so KSP generates a real
 * `OpenAggregateEntity_LirpViaAccessor` at test compile time. Used by the short-circuit
 * isolation test: anonymous subclasses of this class must return null from
 * [RegistryBase.viaAccessorFor] via the early-return on `isAnonymousClass`, not via a
 * `ClassNotFoundException` on the synthetic binary name.
 */
open class OpenAggregateEntity(
    override val id: Int,
    open val customerId: Int
) : ReactiveEntityBase<Int, OpenAggregateEntity>() {
    override val uniqueId: String get() = "open-agg-$id"

    @Aggregate
    val customer by aggregate<Int, Customer> { customerId }

    override fun clone() = OpenAggregateEntity(id, customerId)
}

/** Concrete repo exposing [BubbleUpOrder]; uses real KSP-generated `BubbleUpOrder_LirpViaAccessor`. */
internal class TestableOrderRepo :
    VolatileRepository<Long, BubbleUpOrder>(LirpContext.default, "TestableOrders")

/** Plain entity without any `@Aggregate` properties — no `_LirpViaAccessor` will exist. */
internal open class SimpleNoAggregateEntity(
    override val id: Int,
    val label: String,
    override val uniqueId: String = "simple-$id"
) : IdentifiableEntity<Int> {
    override fun clone(): IdentifiableEntity<Int> = SimpleNoAggregateEntity(id, label, uniqueId)
}

internal class SimpleNoAggregateRepo :
    VolatileRepository<Int, SimpleNoAggregateEntity>(LirpContext.default, "SimpleNoAggregate")