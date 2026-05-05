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

import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.ReactiveScope
import net.transgressoft.lirp.persistence.IndexedProduct
import net.transgressoft.lirp.persistence.IndexedProductVolatileRepo
import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.testing.SerializeWithReactiveScope
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Integration tests for the Query DSL against real repositories.
 */
@DisplayName("Query DSL Integration")
@SerializeWithReactiveScope
@OptIn(ExperimentalCoroutinesApi::class)
internal class QueryDslIntegrationTest : FunSpec({

    val testDispatcher = UnconfinedTestDispatcher()
    val testScope = CoroutineScope(testDispatcher)

    beforeSpec {
        ReactiveScope.flowScope = testScope
        ReactiveScope.ioScope = testScope
    }

    afterSpec {
        ReactiveScope.resetDefaultIoScope()
        ReactiveScope.resetDefaultFlowScope()
    }

    lateinit var ctx: LirpContext
    lateinit var productRepo: ProductVolatileRepo
    lateinit var employeeRepo: EmployeeVolatileRepo

    beforeTest {
        ctx = LirpContext()
        productRepo = ProductVolatileRepo(ctx)
        employeeRepo = EmployeeVolatileRepo(ctx)
    }

    afterTest {
        ctx.close()
    }

    test("query returns matching entities for indexed Eq") {
        productRepo.create(1, "books", 10.0, 5, "Book A")
        productRepo.create(2, "electronics", 100.0, 10, "Gadget")
        productRepo.create(3, "books", 15.0, 3, "Book B")

        val results = productRepo.query { where { Product::category eq "books" } }.toList()
        results shouldHaveSize 2
        results.map { it.name } shouldContainExactlyInAnyOrder listOf("Book A", "Book B")
    }

    test("query with range filter returns correct subset") {
        productRepo.create(1, "books", 10.0, 5, "Book A")
        productRepo.create(2, "electronics", 100.0, 10, "Gadget")
        productRepo.create(3, "books", 50.0, 3, "Book B")

        val results = productRepo.query { where { Product::price gte 50.0 } }.toList()
        results shouldHaveSize 2
        results.all { it.price >= 50.0 }.shouldBeTrue()
    }

    test("query with AND returns intersection") {
        productRepo.create(1, "electronics", 100.0, 10, "Gadget")
        productRepo.create(2, "electronics", 50.0, 8, "Tablet")
        productRepo.create(3, "books", 10.0, 5, "Book")

        val results =
            productRepo.query {
                where { (Product::category eq "electronics") and (Product::price gt 60.0) }
            }.toList()
        results shouldHaveSize 1
        results.first().name shouldBe "Gadget"
    }

    test("query with OR returns union") {
        productRepo.create(1, "books", 10.0, 5, "Book A")
        productRepo.create(2, "electronics", 100.0, 10, "Gadget")
        productRepo.create(3, "furniture", 200.0, 2, "Chair")

        val results =
            productRepo.query {
                where { (Product::category eq "books") or (Product::category eq "electronics") }
            }.toList()
        results shouldHaveSize 2
        results.map { it.name } shouldContainExactlyInAnyOrder listOf("Book A", "Gadget")
    }

    test("query with orderBy and limit returns top N") {
        productRepo.create(1, "books", 10.0, 5, "Book A")
        productRepo.create(2, "electronics", 100.0, 10, "Gadget")
        productRepo.create(3, "books", 50.0, 3, "Book B")

        val results =
            productRepo.query {
                orderBy(Product::price, Direction.DESC)
                limit(2)
            }.toList()
        results shouldHaveSize 2
        results.map { it.name } shouldBe listOf("Gadget", "Book B")
    }

    test("query with offset and limit returns correct page") {
        productRepo.create(1, "books", 10.0, 5, "A")
        productRepo.create(2, "books", 20.0, 5, "B")
        productRepo.create(3, "books", 30.0, 5, "C")
        productRepo.create(4, "books", 40.0, 5, "D")

        val results =
            productRepo.query {
                orderBy(Product::price)
                offset(1)
                limit(2)
            }.toList()
        results shouldHaveSize 2
        results.map { it.name } shouldBe listOf("B", "C")
    }

    test("query with composite orderBy orders correctly") {
        employeeRepo.create(1, "eng", 5, 120_000.0, "Alice")
        employeeRepo.create(2, "eng", 5, 110_000.0, "Bob")
        employeeRepo.create(3, "sales", 3, 90_000.0, "Charlie")

        val results =
            employeeRepo.query {
                orderBy(Employee::department)
                orderBy(Employee::salary, Direction.DESC)
            }.toList()
        results.map { it.name } shouldBe listOf("Alice", "Bob", "Charlie")
    }

    test("query with no predicate returns all entities") {
        productRepo.create(1, "books", 10.0, 5, "Book A")
        productRepo.create(2, "electronics", 100.0, 10, "Gadget")

        val results = productRepo.query { }.toList()
        results shouldHaveSize 2
    }

    test("query with no matches returns empty sequence") {
        productRepo.create(1, "books", 10.0, 5, "Book A")

        val results = productRepo.query { where { Product::category eq "toys" } }.toList()
        results.shouldBeEmpty()
    }

    test("query returns lazy Sequence — no execution until terminal operation") {
        productRepo.create(1, "books", 10.0, 5, "Book A")

        productRepo.activateEvents(CrudEvent.Type.READ)
        val readCount = AtomicInteger(0)
        productRepo.subscribe(CrudEvent.Type.READ) { readCount.incrementAndGet() }

        val sequence = productRepo.query { where { Product::category eq "books" } }

        // Before terminal operation: no READ event should have fired
        readCount.get() shouldBeExactly 0

        // Terminal operation triggers execution and READ event
        sequence.toList()

        testDispatcher.scheduler.advanceUntilIdle()

        readCount.get() shouldBeExactly 1
    }

    test("query with custom @Indexed name resolves index correctly") {
        val indexedRepo = IndexedProductVolatileRepo()
        indexedRepo.create(IndexedProduct(1, "books", "red"))
        indexedRepo.create(IndexedProduct(2, "electronics", "blue"))
        indexedRepo.create(IndexedProduct(3, "books", "red"))

        val results = indexedRepo.query { where { IndexedProduct::nullableTag eq "red" } }.toList()

        results shouldHaveSize 2
        results.map { it.id } shouldContainExactlyInAnyOrder listOf(1, 3)
    }

    test("RegistryBase helpers round-trip custom IndexEntry propertyName") {
        val indexedRepo = IndexedProductVolatileRepo()
        // IndexedProduct has @Indexed(name = "tag") val nullableTag: String?
        // The accessor generates IndexEntry("tag", "nullableTag") { it.nullableTag }
        // discoverIndexes is lazy — trigger it by adding an entity.
        indexedRepo.create(IndexedProduct(0, "trigger", "blue"))
        indexedRepo.isPropertyIndexed(IndexedProduct::nullableTag) shouldBe true
        indexedRepo.indexNameFor(IndexedProduct::nullableTag) shouldBe "tag"
    }
})