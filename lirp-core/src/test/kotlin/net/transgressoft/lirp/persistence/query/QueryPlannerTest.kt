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

import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe

/**
 * Tests for [QueryPlanner] strategy selection and execution correctness.
 */
@DisplayName("QueryPlanner")
internal class QueryPlannerTest : FunSpec({

    val productPlanner =
        QueryPlanner<Product>(
            isIndexed = { it.name == "category" },
            indexNameFor = { it.name }
        )

    val employeePlanner =
        QueryPlanner<Employee>(
            isIndexed = { it.name == "department" || it.name == "level" },
            indexNameFor = { it.name }
        )

    lateinit var productRepo: ProductVolatileRepo
    lateinit var employeeRepo: EmployeeVolatileRepo

    beforeTest {
        productRepo = ProductVolatileRepo()
        productRepo.create(1, "books", 10.0, 5, "Book A")
        productRepo.create(2, "electronics", 100.0, 10, "Gadget")
        productRepo.create(3, "books", 15.0, 3, "Book B")
        productRepo.create(4, "electronics", 50.0, 8, "Tablet")

        employeeRepo = EmployeeVolatileRepo()
        employeeRepo.create(1, "eng", 5, 120_000.0, "Alice")
        employeeRepo.create(2, "eng", 5, 110_000.0, "Bob")
        employeeRepo.create(3, "sales", 3, 90_000.0, "Charlie")
        employeeRepo.create(4, "eng", 4, 100_000.0, "Diana")
    }

    test("INDEX_ONLY for single indexed Eq") {
        val plan =
            productPlanner.execute(
                query { where { Product::category eq "books" } },
                productRepo
            )
        plan.strategy shouldBe QueryPlanner.Strategy.INDEX_ONLY
        plan.results.toList().map { it.name } shouldContainExactlyInAnyOrder listOf("Book A", "Book B")
    }

    test("INDEX_THEN_FILTER for indexed Eq plus non-indexed Gt") {
        val plan =
            productPlanner.execute(
                query { where { (Product::category eq "electronics") and (Product::price gt 60.0) } },
                productRepo
            )
        plan.strategy shouldBe QueryPlanner.Strategy.INDEX_THEN_FILTER
        plan.results.toList().map { it.name } shouldContainExactlyInAnyOrder listOf("Gadget")
    }

    test("SCAN_ONLY for non-indexed Gt only") {
        val plan =
            productPlanner.execute(
                query { where { Product::price gt 20.0 } },
                productRepo
            )
        plan.strategy shouldBe QueryPlanner.Strategy.SCAN_ONLY
        plan.results.toList().map { it.name } shouldContainExactlyInAnyOrder listOf("Gadget", "Tablet")
    }

    test("INDEX_ONLY for multi-index AND intersects results") {
        val plan =
            employeePlanner.execute(
                query { where { (Employee::department eq "eng") and (Employee::level eq 5) } },
                employeeRepo
            )
        plan.strategy shouldBe QueryPlanner.Strategy.INDEX_ONLY
        plan.results.toList().map { it.name } shouldContainExactlyInAnyOrder listOf("Alice", "Bob")
    }

    test("SCAN_ONLY for OR of indexed properties") {
        val plan =
            productPlanner.execute(
                query { where { (Product::category eq "books") or (Product::category eq "electronics") } },
                productRepo
            )
        plan.strategy shouldBe QueryPlanner.Strategy.SCAN_ONLY
        plan.results.toList() shouldHaveSize 4
    }

    test("SCAN_ONLY for NOT of indexed Eq") {
        val plan =
            productPlanner.execute(
                query { where { !(Product::category eq "books") } },
                productRepo
            )
        plan.strategy shouldBe QueryPlanner.Strategy.SCAN_ONLY
        plan.results.toList().map { it.name } shouldContainExactlyInAnyOrder listOf("Gadget", "Tablet")
    }

    test("Impossible AND short-circuits to empty without scan") {
        val plan =
            productPlanner.execute(
                query { where { (Product::category eq "books") and (Product::category eq "sports") } },
                productRepo
            )
        plan.strategy shouldBe QueryPlanner.Strategy.INDEX_ONLY
        plan.results.toList().shouldBeEmpty()
    }

    test("Null value in Eq falls back to SCAN_ONLY") {
        val plan =
            productPlanner.execute(
                query { where { Product::category eq null } },
                productRepo
            )
        plan.strategy shouldBe QueryPlanner.Strategy.SCAN_ONLY
        plan.results.toList().shouldBeEmpty()
    }

    test("Empty predicate returns all entities as SCAN_ONLY") {
        val plan =
            productPlanner.execute(
                query<Product> { },
                productRepo
            )
        plan.strategy shouldBe QueryPlanner.Strategy.SCAN_ONLY
        plan.results.toList() shouldHaveSize 4
    }

    test("orderBy ASC returns correctly ordered results") {
        val plan =
            productPlanner.execute(
                query { orderBy(Product::price) },
                productRepo
            )
        plan.results.toList().map { it.name } shouldBe listOf("Book A", "Book B", "Tablet", "Gadget")
    }

    test("orderBy DESC returns correctly ordered results") {
        val plan =
            productPlanner.execute(
                query { orderBy(Product::price, Direction.DESC) },
                productRepo
            )
        plan.results.toList().map { it.name } shouldBe listOf("Gadget", "Tablet", "Book B", "Book A")
    }

    test("Composite orderBy orders by primary then secondary") {
        val plan =
            employeePlanner.execute(
                query {
                    orderBy(Employee::department)
                    orderBy(Employee::salary, Direction.DESC)
                },
                employeeRepo
            )
        val names = plan.results.toList().map { it.name }
        names shouldBe listOf("Alice", "Bob", "Diana", "Charlie")
    }

    test("offset skips first N results") {
        val plan =
            productPlanner.execute(
                query {
                    orderBy(Product::price)
                    offset(2)
                },
                productRepo
            )
        plan.results.toList().map { it.name } shouldBe listOf("Tablet", "Gadget")
    }

    test("limit restricts result count") {
        val plan =
            productPlanner.execute(
                query {
                    orderBy(Product::price)
                    limit(2)
                },
                productRepo
            )
        plan.results.toList().map { it.name } shouldBe listOf("Book A", "Book B")
    }

    test("offset plus limit returns correct slice") {
        val plan =
            productPlanner.execute(
                query {
                    orderBy(Product::price)
                    offset(1)
                    limit(2)
                },
                productRepo
            )
        plan.results.toList().map { it.name } shouldBe listOf("Book B", "Tablet")
    }
})