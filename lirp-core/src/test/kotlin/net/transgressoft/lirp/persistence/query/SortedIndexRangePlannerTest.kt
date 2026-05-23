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
import net.transgressoft.lirp.persistence.IndexEntry
import net.transgressoft.lirp.persistence.LirpIndexAccessor
import net.transgressoft.lirp.persistence.RegistryBase
import net.transgressoft.lirp.persistence.VolatileRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Test fixture entity combining a hash-indexed [category], a sorted-indexed [age], and a
 * sorted-indexed nullable [nick] property. Used to verify range-slice query acceleration.
 */
data class RangeFixture(
    override val id: Int,
    val category: String,
    val age: Int,
    val nick: String?,
    override val uniqueId: String = "range-$id"
) : IdentifiableEntity<Int> {
    override fun clone() = copy()
}

/**
 * Hand-written [LirpIndexAccessor] for [RangeFixture], bypassing KSP generation.
 *
 * Provides three index entries:
 * - `category` — hash-indexed (default, `sorted = false`)
 * - `age` — sorted-indexed (`sorted = true`)
 * - `nick` — sorted-indexed (`sorted = true`), nullable property
 */
@Suppress("ClassName")
class `RangeFixture_LirpIndexAccessor` : LirpIndexAccessor<RangeFixture> {
    override val entries: List<IndexEntry<RangeFixture>> =
        listOf(
            IndexEntry("category") { it.category },
            IndexEntry("age", "age", sorted = true) { it.age },
            IndexEntry("nick", "nick", sorted = true) { it.nick }
        )
}

/**
 * Tests for sorted-index range-slice acceleration in [QueryPlanner].
 *
 * Verifies that `Gt`/`Gte`/`Lt`/`Lte` leaves on `@Indexed(sorted = true)` properties are
 * rewritten to `NavigableMap` slices (O(log N + |result|)), while non-sorted and non-indexed
 * properties retain current scan behavior. Also covers `Eq` fast-path, `And`/`Or` composition,
 * empty-range correctness, and null-skip semantics.
 */
internal class SortedIndexRangePlannerTest : StringSpec({

    // Deterministic fixture: 10 entities with age ∈ {20, 24, 28, 32, 36, 40, 44, 48, 52, 56}
    // and nicks: first 8 have non-null nicks, last 2 have null
    val fixtures =
        (0 until 10).map { i ->
            RangeFixture(
                id = i + 1,
                category = if (i % 3 == 0) "X" else if (i % 3 == 1) "Y" else "Z",
                age = 20 + i * 4,
                nick = if (i < 8) "nick-$i" else null
            )
        }

    fun withFreshRepo(block: (VolatileRepository<Int, RangeFixture>) -> Unit) {
        val repo = object : VolatileRepository<Int, RangeFixture>("RangePlannerTestRepo") {}
        fixtures.forEach { repo.add(it) }
        repo.use { repo ->
            block(repo)
        }
    }

    fun planner(repo: VolatileRepository<Int, RangeFixture>): QueryPlanner<RangeFixture> {
        val base = repo as RegistryBase<Int, RangeFixture>
        return QueryPlanner(
            isIndexed = { base.isPropertyIndexed(it) },
            indexNameFor = { base.indexNameFor(it) ?: it.name },
            isSortedIndexed = { base.isPropertySortedIndexed(it) },
            sortedBucketFor = { base.sortedBucketFor(it) }
        )
    }

    "gt on sorted-indexed property executes tailMap slice and reports INDEX_ONLY" {
        withFreshRepo { repo ->
            val plan = planner(repo).execute(query { where { RangeFixture::age gt 30 } }, repo)

            plan.strategy shouldBe QueryPlanner.Strategy.INDEX_ONLY
            plan.results.toSet() shouldBe fixtures.filter { it.age > 30 }.toSet()
        }
    }

    "gte on sorted-indexed property includes the boundary value" {
        withFreshRepo { repo ->
            val plan = planner(repo).execute(query { where { RangeFixture::age gte 32 } }, repo)

            plan.strategy shouldBe QueryPlanner.Strategy.INDEX_ONLY
            plan.results.toSet() shouldBe fixtures.filter { it.age >= 32 }.toSet()
        }
    }

    "lt on sorted-indexed property executes headMap slice and reports INDEX_ONLY" {
        withFreshRepo { repo ->
            val plan = planner(repo).execute(query { where { RangeFixture::age lt 36 } }, repo)

            plan.strategy shouldBe QueryPlanner.Strategy.INDEX_ONLY
            plan.results.toSet() shouldBe fixtures.filter { it.age < 36 }.toSet()
        }
    }

    "lte on sorted-indexed property includes the boundary value" {
        withFreshRepo { repo ->
            val plan = planner(repo).execute(query { where { RangeFixture::age lte 36 } }, repo)

            plan.strategy shouldBe QueryPlanner.Strategy.INDEX_ONLY
            plan.results.toSet() shouldBe fixtures.filter { it.age <= 36 }.toSet()
        }
    }

    "eq on sorted-indexed property uses point lookup and reports INDEX_ONLY" {
        withFreshRepo { repo ->
            val plan = planner(repo).execute(query { where { RangeFixture::age eq 32 } }, repo)

            plan.strategy shouldBe QueryPlanner.Strategy.INDEX_ONLY
            plan.results.toSet() shouldBe fixtures.filter { it.age == 32 }.toSet()
        }
    }

    "range on non-sorted indexed property falls back to SCAN_ONLY" {
        withFreshRepo { repo ->
            // category is hash-indexed (not sorted), so range queries must scan
            val plan = planner(repo).execute(query { where { RangeFixture::category gt "books" } }, repo)

            plan.strategy shouldBe QueryPlanner.Strategy.SCAN_ONLY
            plan.results.toSet() shouldBe fixtures.filter { it.category > "books" }.toSet()
        }
    }

    "range on non-indexed property falls back to SCAN_ONLY" {
        withFreshRepo { repo ->
            // id is not in the accessor — not indexed at all
            val plan = planner(repo).execute(query { where { RangeFixture::id gt 5 } }, repo)

            plan.strategy shouldBe QueryPlanner.Strategy.SCAN_ONLY
            plan.results.toSet() shouldBe fixtures.filter { it.id > 5 }.toSet()
        }
    }

    "And of two range leaves on sorted-indexed properties intersects candidates" {
        withFreshRepo { repo ->
            // age ∈ (25, 40) exclusive-exclusive
            val plan = planner(repo).execute(query { where { (RangeFixture::age gt 25) and (RangeFixture::age lt 40) } }, repo)

            plan.strategy shouldBe QueryPlanner.Strategy.INDEX_ONLY
            plan.results.toSet() shouldBe fixtures.filter { it.age in 26..<40 }.toSet()
        }
    }

    "And of indexed Eq and sorted-indexed range intersects candidates" {
        withFreshRepo { repo ->
            // category eq "X" and age gte 30
            val plan =
                planner(repo).execute(
                    query { where { (RangeFixture::category eq "X") and (RangeFixture::age gte 30) } }, repo
                )

            plan.strategy shouldBe QueryPlanner.Strategy.INDEX_ONLY
            plan.results.toSet() shouldBe fixtures.filter { it.category == "X" && it.age >= 30 }.toSet()
        }
    }

    "Empty range (tailMap above max) returns empty result without throwing" {
        withFreshRepo { repo ->
            val plan = planner(repo).execute(query { where { RangeFixture::age gt 99999 } }, repo)

            plan.strategy shouldBe QueryPlanner.Strategy.INDEX_ONLY
            plan.results.toList().shouldBeEmpty()
        }
    }

    "Or of two range leaves falls back to SCAN_ONLY" {
        withFreshRepo { repo ->
            val plan =
                planner(repo).execute(
                    query { where { (RangeFixture::age gt 48) or (RangeFixture::age lt 24) } }, repo
                )

            plan.strategy shouldBe QueryPlanner.Strategy.SCAN_ONLY
            plan.results.toSet() shouldBe fixtures.filter { it.age !in 24..48 }.toSet()
        }
    }

    "Range slice ignores entities with null property value" {
        withFreshRepo { repo ->
            // Verify fixture setup: last 2 entities have null nick
            fixtures.filter { it.nick == null }.size shouldBe 2

            val plan = planner(repo).execute(query { where { RangeFixture::nick gt "a" } }, repo)

            plan.strategy shouldBe QueryPlanner.Strategy.INDEX_ONLY
            // Only entities with non-null nick > "a" are returned; null-nick entities are excluded
            val expected = fixtures.filter { it.nick != null && it.nick > "a" }.toSet()
            plan.results.toSet() shouldBe expected
        }
    }
})