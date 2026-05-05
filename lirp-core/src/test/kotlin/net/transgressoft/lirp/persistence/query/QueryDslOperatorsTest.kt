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
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe

/**
 * Unit tests for Query DSL operators and predicate evaluation.
 */
@DisplayName("Query DSL Operators")
internal class QueryDslOperatorsTest : FunSpec({

    val book = Product(1, "books", 10.0, 5, "Book A")
    val gadget = Product(2, "electronics", 100.0, 10, "Gadget")

    test("Eq matches when property equals value") {
        val pred = Product::category eq "books"
        pred.matches(book).shouldBeTrue()
    }

    test("Eq does not match when property differs") {
        val pred = Product::category eq "books"
        pred.matches(gadget).shouldBeFalse()
    }

    test("Gt matches when property is greater") {
        val pred = Product::price gt 50.0
        pred.matches(gadget).shouldBeTrue()
        pred.matches(book).shouldBeFalse()
    }

    test("Gte matches when property is equal or greater") {
        val pred = Product::price gte 100.0
        pred.matches(gadget).shouldBeTrue()
        pred.matches(book).shouldBeFalse()
    }

    test("Lt matches when property is lesser") {
        val pred = Product::price lt 50.0
        pred.matches(book).shouldBeTrue()
        pred.matches(gadget).shouldBeFalse()
    }

    test("Lte matches when property is equal or lesser") {
        val pred = Product::price lte 10.0
        pred.matches(book).shouldBeTrue()
        pred.matches(gadget).shouldBeFalse()
    }

    test("And matches only when both predicates match") {
        val pred = (Product::category eq "books") and (Product::price gt 5.0)
        pred.matches(book).shouldBeTrue()
        pred.matches(gadget).shouldBeFalse()
    }

    test("Or matches when either predicate matches") {
        val pred = (Product::category eq "books") or (Product::category eq "electronics")
        pred.matches(book).shouldBeTrue()
        pred.matches(gadget).shouldBeTrue()
    }

    test("Or does not match when neither predicate matches") {
        val pred = (Product::category eq "furniture") or (Product::category eq "toys")
        pred.matches(book).shouldBeFalse()
    }

    test("Not inverts the inner predicate") {
        val pred = !(Product::category eq "books")
        pred.matches(book).shouldBeFalse()
        pred.matches(gadget).shouldBeTrue()
    }

    test("Complex nested composition evaluates correctly") {
        val pred = (Product::category eq "books") and !(Product::price gt 20.0)
        pred.matches(book).shouldBeTrue()
        pred.matches(gadget).shouldBeFalse()
    }

    test("QueryBuilder builds Query with predicate") {
        val q =
            query<Product> {
                where { Product::category eq "books" }
            }
        q.predicate.shouldNotBeNull()
        (q.predicate as Predicate.Eq<Product, String>).prop shouldBe Product::category
        (q.predicate as Predicate.Eq<Product, String>).value shouldBe "books"
    }

    test("QueryBuilder builds Query with orderBy") {
        val q =
            query<Product> {
                orderBy(Product::price, Direction.DESC)
            }
        q.orderBy shouldHaveSize 1
        q.orderBy.single().prop shouldBe Product::price
        q.orderBy.single().direction shouldBe Direction.DESC
    }

    test("QueryBuilder builds Query with limit and offset") {
        val q =
            query<Product> {
                limit(10)
                offset(5)
            }
        q.limit shouldBe 10
        q.offset shouldBe 5
    }

    test("QueryBuilder builds Query with multiple orderBy clauses") {
        val q =
            query<Product> {
                orderBy(Product::category)
                orderBy(Product::price, Direction.DESC)
            }
        q.orderBy.size shouldBe 2
    }

    test("QueryBuilder builds Query with null predicate when where is absent") {
        val q = query<Product> { }
        q.predicate shouldBe null
        q.orderBy shouldBe emptyList()
        q.limit shouldBe null
        q.offset shouldBe 0
    }

    /*
     * Compile-time type safety verification — the following lines would fail to compile:
     *
     * Product::category eq 42          // Type mismatch: inferred type is Int but String was expected
     * Product::name gt 100             // Type mismatch: inferred type is Int but Comparable<String> was expected
     * Product::id eq "foo"             // Type mismatch: inferred type is String but Int was expected
     * Product::price lt "cheap"        // Type mismatch: inferred type is String but Comparable<Double> was expected
     */
})