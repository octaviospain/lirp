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

import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.persistence.VolatileRepository
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Integration tests for [explainQuery] and [queryWithDiagnostics] against real repositories,
 * verifying diagnostic accuracy across all three retrieval strategies.
 */
@DisplayName("Query Diagnostics Extension")
internal class QueryDiagnosticsExtensionTest : FunSpec({

    lateinit var ctx: LirpContext
    lateinit var productRepo: ProductVolatileRepo

    beforeTest {
        ctx = LirpContext()
        productRepo = ProductVolatileRepo(ctx)
        productRepo.create(1, "books", 10.0, 5, "Book A")
        productRepo.create(2, "electronics", 100.0, 10, "Gadget")
        productRepo.create(3, "books", 15.0, 3, "Book B")
        productRepo.create(4, "electronics", 50.0, 8, "Tablet")
    }

    afterTest { ctx.close() }

    test("explainQuery returns INDEX_ONLY for indexed Eq with one EXACT index hit") {
        val diagnostic = productRepo.explainQuery { where { Product::category eq "books" } }

        diagnostic.strategy shouldBe Strategy.INDEX_ONLY
        diagnostic.indexHits shouldHaveSize 1
        diagnostic.indexHits[0].propertyName shouldBe "category"
        diagnostic.indexHits[0].type shouldBe IndexHitType.EXACT
        diagnostic.postFilterPredicateCount shouldBe 0
    }

    test("explainQuery executionTimeNs is null") {
        val diagnostic = productRepo.explainQuery { where { Product::category eq "books" } }

        diagnostic.executionTimeNs.shouldBeNull()
    }

    test("explainQuery planningTimeNs is non-negative") {
        val diagnostic = productRepo.explainQuery { where { Product::category eq "books" } }

        (diagnostic.planningTimeNs >= 0) shouldBe true
    }

    test("explainQuery returns SCAN_ONLY with empty indexHits for non-indexed predicate") {
        val diagnostic = productRepo.explainQuery { where { Product::price gt 20.0 } }

        diagnostic.strategy shouldBe Strategy.SCAN_ONLY
        diagnostic.indexHits.shouldBeEmpty()
    }

    test("explainQuery returns INDEX_THEN_FILTER with positive postFilterPredicateCount for indexed-Eq AND non-indexed range") {
        val diagnostic =
            productRepo.explainQuery {
                where { (Product::category eq "electronics") and (Product::price gt 60.0) }
            }

        diagnostic.strategy shouldBe Strategy.INDEX_THEN_FILTER
        diagnostic.postFilterPredicateCount shouldBe 1
        diagnostic.indexHits shouldHaveSize 1
    }

    test("queryWithDiagnostics planningTimeNs is non-negative") {
        val diagnosed = productRepo.queryWithDiagnostics { where { Product::category eq "books" } }

        (diagnosed.diagnostic.planningTimeNs >= 0) shouldBe true
    }

    test("queryWithDiagnostics executionTimeNs is non-null") {
        val diagnosed = productRepo.queryWithDiagnostics { where { Product::category eq "books" } }

        diagnosed.diagnostic.executionTimeNs.shouldNotBeNull()
        (diagnosed.diagnostic.executionTimeNs!! >= 0) shouldBe true
    }

    test("queryWithDiagnostics results match query results for indexed Eq") {
        val block: QueryBuilder<Product>.() -> Unit = { where { Product::category eq "books" } }

        val queryResult = productRepo.query(block).toList()
        val diagnosed = productRepo.queryWithDiagnostics(block)
        val diagnosedResult = diagnosed.results.toList()

        diagnosedResult.map { it.id }.toSet() shouldBe queryResult.map { it.id }.toSet()
        diagnosedResult shouldHaveSize queryResult.size
    }

    test("queryWithDiagnostics results match query results for SCAN_ONLY predicate") {
        val block: QueryBuilder<Product>.() -> Unit = { where { Product::price gt 20.0 } }

        val queryResult = productRepo.query(block).toList()
        val diagnosedResult = productRepo.queryWithDiagnostics(block).results.toList()

        diagnosedResult.map { it.id }.toSet() shouldBe queryResult.map { it.id }.toSet()
    }

    test("queryWithDiagnostics results can be iterated more than once") {
        val diagnosed = productRepo.queryWithDiagnostics { where { Product::category eq "books" } }

        val first = diagnosed.results.toList()
        val second = diagnosed.results.toList()
        first shouldBe second
    }

    test("explainQuery on In predicate returns MULTI IndexHit with correct selectivity") {
        val diagnostic =
            productRepo.explainQuery {
                where { Product::category isIn listOf("books", "electronics") }
            }

        diagnostic.strategy shouldBe Strategy.INDEX_ONLY
        diagnostic.indexHits shouldHaveSize 1
        diagnostic.indexHits[0].type shouldBe IndexHitType.MULTI
        diagnostic.indexHits[0].selectivity shouldBe 2
    }

    test("explainQuery on sorted-indexed range predicate returns RANGE IndexHit") {
        val rangeRepo =
            object : VolatileRepository<Int, RangeFixture>(ctx, "RangeRepo") {}
        (1..10).forEach { i ->
            rangeRepo.add(RangeFixture(i, if (i % 2 == 0) "X" else "Y", i * 4, null))
        }

        val diagnostic = rangeRepo.explainQuery { where { RangeFixture::age gt 20 } }

        diagnostic.indexHits shouldHaveSize 1
        diagnostic.indexHits[0].type shouldBe IndexHitType.RANGE
        diagnostic.indexHits[0].selectivity shouldBe 5
    }
})