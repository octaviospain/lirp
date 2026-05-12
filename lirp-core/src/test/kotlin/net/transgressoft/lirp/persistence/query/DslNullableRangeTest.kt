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
import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.persistence.VolatileRepository
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import java.math.BigDecimal

/**
 * Verifies the range operators (`gt` / `gte` / `lt` / `lte`) accept nullable `Comparable`
 * properties and treat null property values as non-matching (SQL three-valued-logic style).
 */
@DisplayName("Query DSL — nullable range operators")
internal class DslNullableRangeTest : StringSpec({

    val items =
        listOf(
            OptionalPriceItem(1, "cheap", BigDecimal("9.99")),
            OptionalPriceItem(2, "mid", BigDecimal("29.99")),
            OptionalPriceItem(3, "premium", BigDecimal("99.99")),
            OptionalPriceItem(4, "unknown", null)
        )

    "gte accepts nullable property and excludes rows whose value is null" {
        val repo = OptionalPriceVolatileRepo()
        items.forEach { repo.add(it) }

        repo.query {
            where { OptionalPriceItem::price gte BigDecimal("25.00") }
        }.map { it.label }.toList() shouldContainExactlyInAnyOrder listOf("mid", "premium")
    }

    "lt accepts nullable property and excludes rows whose value is null" {
        val repo = OptionalPriceVolatileRepo()
        items.forEach { repo.add(it) }

        repo.query {
            where { OptionalPriceItem::price lt BigDecimal("30.00") }
        }.map { it.label }.toList() shouldContainExactlyInAnyOrder listOf("cheap", "mid")
    }

    "compound range gte and lt brackets a window and drops nulls" {
        val repo = OptionalPriceVolatileRepo()
        items.forEach { repo.add(it) }

        repo.query {
            where {
                (OptionalPriceItem::price gte BigDecimal("10.00")) and
                    (OptionalPriceItem::price lt BigDecimal("50.00"))
            }
        }.map { it.label }.toList() shouldContainExactlyInAnyOrder listOf("mid")
    }

    "gt accepts nullable property and excludes rows whose value is null" {
        val repo = OptionalPriceVolatileRepo()
        items.forEach { repo.add(it) }

        repo.query {
            where { OptionalPriceItem::price gt BigDecimal("29.99") }
        }.map { it.label }.toList() shouldContainExactlyInAnyOrder listOf("premium")
    }

    "lte accepts nullable property and excludes rows whose value is null" {
        val repo = OptionalPriceVolatileRepo()
        items.forEach { repo.add(it) }

        repo.query {
            where { OptionalPriceItem::price lte BigDecimal("29.99") }
        }.map { it.label }.toList() shouldContainExactlyInAnyOrder listOf("cheap", "mid")
    }
})

private data class OptionalPriceItem(
    override val id: Int,
    val label: String,
    val price: BigDecimal?,
    override val uniqueId: String = "item-$id"
) : IdentifiableEntity<Int> {
    override fun clone() = copy()
}

private class OptionalPriceVolatileRepo(context: LirpContext = LirpContext.default) :
    VolatileRepository<Int, OptionalPriceItem>(context, "OptionalPriceItems")