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
import net.transgressoft.lirp.persistence.VolatileRepository
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe

/**
 * Test fixture entity with indexed `category` and `sku` properties used for `isIn` operator tests.
 *
 * Both properties are resolved via the hand-written [IsInFixture_LirpIndexAccessor] below,
 * which is discovered at runtime by [net.transgressoft.lirp.persistence.RegistryBase.discoverIndexes]
 * using the naming convention `{EntityName}_LirpIndexAccessor`.
 */
data class IsInFixture(
    override val id: Int,
    val category: String?,
    val sku: String,
    override val uniqueId: String = "isin-$id"
) : IdentifiableEntity<Int> {
    override fun clone() = copy()
}

/**
 * Hand-written index accessor for [IsInFixture], providing index entries for `category` and `sku`.
 *
 * The naming convention `{EntityName}_LirpIndexAccessor` is required for runtime discovery by
 * [net.transgressoft.lirp.persistence.RegistryBase.discoverIndexes].
 */
@Suppress("ClassName")
class IsInFixture_LirpIndexAccessor : LirpIndexAccessor<IsInFixture> {
    override val entries: List<IndexEntry<IsInFixture>> =
        listOf(
            IndexEntry("category", "category") { it.category },
            IndexEntry("sku", "sku") { it.sku }
        )
}

/**
 * Test repository for [IsInFixture] entities.
 */
class IsInFixtureRepo : VolatileRepository<Int, IsInFixture>("IsInFixtureRepo")

/**
 * Tests for [Predicate.In] semantics, [isIn] infix operator, and [QueryPlanner] integration.
 *
 * Covers construction, empty-set short-circuit, null semantics, indexed union-of-lookups,
 * fallback-to-scan on non-indexed properties, and `And`/`Or` composition.
 */
internal class IsInOperatorTest : StringSpec({

    lateinit var repo: IsInFixtureRepo

    beforeTest {
        repo = IsInFixtureRepo()
    }

    "isIn freezes values into a Set at construction" {
        val original = mutableListOf("a", "b")
        val pred = IsInFixture::sku isIn original
        val inPred = pred as Predicate.In<*, *>
        inPred.values shouldBe setOf("a", "b")
        // Mutating the original list does not affect the frozen set.
        original.add("c")
        inPred.values shouldBe setOf("a", "b")
    }

    "isIn with empty list short-circuits to empty result without scanning" {
        repo.add(IsInFixture(1, "electronics", "sku-1"))
        repo.add(IsInFixture(2, "books", "sku-2"))

        val planner =
            QueryPlanner(
                isIndexed = { repo.isPropertyIndexed(it) },
                indexNameFor = { repo.indexNameFor(it) ?: it.name }
            )
        val plan = planner.execute(query { where { IsInFixture::category isIn emptyList() } }, repo)

        plan.strategy shouldBe Strategy.INDEX_ONLY
        plan.results.toList().shouldBeEmpty()
    }

    "isIn with null in values matches entities whose property value is null" {
        repo.add(IsInFixture(1, null, "sku-1"))
        repo.add(IsInFixture(2, "a", "sku-2"))
        repo.add(IsInFixture(3, "b", "sku-3"))

        val results = repo.query { where { IsInFixture::category isIn listOf(null, "x") } }.toList()

        results.map { it.id } shouldContainExactlyInAnyOrder listOf(1)
    }

    "isIn on non-indexed property scans and matches by Set.contains" {
        repo.add(IsInFixture(1, "electronics", "sku-a"))
        repo.add(IsInFixture(2, "books", "sku-b"))
        repo.add(IsInFixture(3, "furniture", "sku-c"))

        // sku is not indexed via query extension path — but it IS indexed via the accessor.
        // We test a truly non-indexed property by using a planner with isIndexed = { false }.
        val planner =
            QueryPlanner<IsInFixture>(
                isIndexed = { false },
                indexNameFor = { it.name }
            )
        val plan =
            planner.execute(
                query { where { IsInFixture::sku isIn listOf("sku-a", "sku-b") } },
                repo
            )
        plan.strategy shouldBe Strategy.SCAN_ONLY
        plan.results.toList().map { it.sku } shouldContainExactlyInAnyOrder listOf("sku-a", "sku-b")
    }

    "isIn on indexed property executes union of findByIndex lookups" {
        repo.add(IsInFixture(1, "electronics", "sku-1"))
        repo.add(IsInFixture(2, "books", "sku-2"))
        repo.add(IsInFixture(3, "electronics", "sku-3"))
        repo.add(IsInFixture(4, "furniture", "sku-4"))

        val planner =
            QueryPlanner(
                isIndexed = { repo.isPropertyIndexed(it) },
                indexNameFor = { repo.indexNameFor(it) ?: it.name }
            )
        val plan =
            planner.execute(
                query { where { IsInFixture::category isIn listOf("electronics", "books") } },
                repo
            )
        plan.strategy shouldBe Strategy.INDEX_ONLY
        plan.results.toList().map { it.id } shouldContainExactlyInAnyOrder listOf(1, 2, 3)
    }

    "isIn on indexed property with null in values falls back to INDEX_THEN_FILTER" {
        repo.add(IsInFixture(1, "electronics", "sku-1"))
        repo.add(IsInFixture(2, null, "sku-2"))
        repo.add(IsInFixture(3, "books", "sku-3"))

        val planner =
            QueryPlanner(
                isIndexed = { repo.isPropertyIndexed(it) },
                indexNameFor = { repo.indexNameFor(it) ?: it.name }
            )
        val plan =
            planner.execute(
                query { where { IsInFixture::category isIn listOf("electronics", null) } },
                repo
            )
        plan.strategy shouldBe Strategy.INDEX_THEN_FILTER
        plan.results.toList().map { it.id } shouldContainExactlyInAnyOrder listOf(1, 2)
    }

    "And of indexed Eq and indexed isIn intersects candidate sets" {
        repo.add(IsInFixture(1, "electronics", "sku-a"))
        repo.add(IsInFixture(2, "electronics", "sku-b"))
        repo.add(IsInFixture(3, "electronics", "sku-c"))
        repo.add(IsInFixture(4, "books", "sku-a"))

        val planner =
            QueryPlanner(
                isIndexed = { repo.isPropertyIndexed(it) },
                indexNameFor = { repo.indexNameFor(it) ?: it.name }
            )
        val plan =
            planner.execute(
                query { where { (IsInFixture::category eq "electronics") and (IsInFixture::sku isIn listOf("sku-a", "sku-b")) } },
                repo
            )
        plan.strategy shouldBe Strategy.INDEX_ONLY
        plan.results.toList().map { it.id } shouldContainExactlyInAnyOrder listOf(1, 2)
    }

    "And of indexed Eq and Or(isIn with null, Eq) falls back to INDEX_THEN_FILTER and returns null-valued entities" {
        // Predicate shape: And(Eq(category,"X"), Or(In([null,"a"], category), Eq(sku,"sku-c")))
        // The Or arm contains an In with null — containsInWithNull must recurse into Or to detect it.
        // id=1: category="X" passes Eq, but Or: In([null,"a"]) fails and sku≠sku-c -> false
        repo.add(IsInFixture(1, "X", "sku-1"))
        // id=2: Or: In fails (not null/"a"), sku≠sku-c -> false
        repo.add(IsInFixture(2, "X", "sku-2"))
        // id=3: category="X" and sku="sku-c" -> Or: Eq matches -> true, included
        repo.add(IsInFixture(3, "X", "sku-c"))
        // id=4: category=null -> Eq(category,"X") fails -> false
        repo.add(IsInFixture(4, null, "sku-4"))
        // id=5: category="X" and sku≠sku-c -> false
        repo.add(IsInFixture(5, "X", "sku-5"))

        val planner =
            QueryPlanner(
                isIndexed = { repo.isPropertyIndexed(it) },
                indexNameFor = { repo.indexNameFor(it) ?: it.name }
            )
        val pred =
            (IsInFixture::category eq "X") and
                ((IsInFixture::category isIn listOf(null, "a")) or (IsInFixture::sku eq "sku-c"))
        val plan = planner.execute(query { where { pred } }, repo)

        // The Or arm contains In(null,...) nested inside the And — planner must detect null and use INDEX_THEN_FILTER.
        plan.strategy shouldBe Strategy.INDEX_THEN_FILTER
        plan.results.toList().map { it.id } shouldContainExactlyInAnyOrder listOf(3)
    }

    "And of indexed Eq and empty indexed isIn returns no rows" {
        // Regression: an empty isIn combined with a matching indexed Eq used to leak the Eq's
        // candidates through INDEX_ONLY because the empty leaf was classified as resolved but
        // contributed no candidates. The fix emits an empty IndexableLeaf.Multi so the
        // candidate-set intersection collapses to ∅.
        repo.add(IsInFixture(1, "electronics", "sku-A"))
        repo.add(IsInFixture(2, "electronics", "sku-B"))

        val planner =
            QueryPlanner(
                isIndexed = { repo.isPropertyIndexed(it) },
                indexNameFor = { repo.indexNameFor(it) ?: it.name }
            )
        val plan =
            planner.execute(
                query { where { (IsInFixture::category eq "electronics") and (IsInFixture::sku isIn emptyList()) } },
                repo
            )
        plan.strategy shouldBe Strategy.INDEX_ONLY
        plan.results.toList().shouldBeEmpty()
    }

    "Or of two isIn leaves falls back to SCAN_ONLY" {
        repo.add(IsInFixture(1, "electronics", "sku-a"))
        repo.add(IsInFixture(2, "books", "sku-b"))
        repo.add(IsInFixture(3, "furniture", "sku-c"))

        val planner =
            QueryPlanner(
                isIndexed = { repo.isPropertyIndexed(it) },
                indexNameFor = { repo.indexNameFor(it) ?: it.name }
            )
        val plan =
            planner.execute(
                query {
                    where {
                        (IsInFixture::category isIn listOf("electronics")) or
                            (IsInFixture::sku isIn listOf("sku-b"))
                    }
                },
                repo
            )
        plan.strategy shouldBe Strategy.SCAN_ONLY
        plan.results.toList().map { it.id } shouldContainExactlyInAnyOrder listOf(1, 2)
    }
})