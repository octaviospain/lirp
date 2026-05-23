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

import net.transgressoft.lirp.entity.IdentifiableEntity
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap

/**
 * Test fixture entity with both a hash-indexed and a sorted-indexed property.
 *
 * [label] uses the default hash bucket; [age] declares `sorted = true` and must be stored
 * in a [ConcurrentSkipListMap] bucket for range-slice access.
 */
data class IndexFixture(
    override val id: Int,
    val label: String,
    val age: Int,
    override val uniqueId: String = "fixture-$id"
) : IdentifiableEntity<Int> {
    override fun clone() = copy()
}

/**
 * Hand-written [LirpIndexAccessor] for [IndexFixture], bypassing KSP generation.
 *
 * Registers a hash-bucketed index on [IndexFixture.label] and a sorted-bucketed index on
 * [IndexFixture.age], matching the [IndexEntry.sorted] flag added in plan 54.6-01.
 */
@Suppress("ClassName")
class `IndexFixture_LirpIndexAccessor` : LirpIndexAccessor<IndexFixture> {
    override val entries: List<IndexEntry<IndexFixture>> =
        listOf(
            IndexEntry("label") { it.label },
            IndexEntry("age", "age", sorted = true) { it.age }
        )
}

/**
 * Test-only [VolatileRepository] subclass that exposes protected members for white-box assertion.
 *
 * [clearIndexes] forwards to [RegistryBase.clearSecondaryIndexes] so that tests can trigger
 * the bulk-clear path. [sortedBucketForIndex] and [isSortedIndexed] expose the internal
 * accessors introduced in plan 54.6-02 for direct inspection.
 */
class TestVolatileRepo : VolatileRepository<Int, IndexFixture>("TestRepo") {
    fun clearIndexes() = clearSecondaryIndexes()

    fun sortedBucketForIndex(indexName: String) = sortedBucketFor(indexName)

    fun isSortedIndexed(prop: kotlin.reflect.KProperty1<IndexFixture, *>) = isPropertySortedIndexed(prop)
}

/**
 * Unit tests for dual-kind secondary index storage invariants in [RegistryBase].
 *
 * Asserts that [IndexEntry.sorted] drives bucket-kind selection at discovery time, that both
 * hash and sorted buckets handle entity add/remove/clear correctly, and that the internal
 * accessors [RegistryBase.sortedBucketFor] and [RegistryBase.isPropertySortedIndexed] behave
 * as documented.
 */
@DisplayName("SortedIndexBucket")
internal class SortedIndexBucketTest : StringSpec({

    "sorted-indexed property populates ConcurrentSkipListMap bucket" {
        val repo = TestVolatileRepo()
        val f1 = IndexFixture(1, "a", 30)
        val f2 = IndexFixture(2, "b", 30)
        repo.add(f1)
        repo.add(f2)

        repo.findByIndex("age", 30) shouldBe setOf(f1, f2)

        // Verify the underlying map type is ConcurrentSkipListMap
        val sortedField =
            RegistryBase::class.java.declaredFields
                .first { it.name == "sortedIndexes" }
                .apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val sortedIndexes = sortedField.get(repo) as Map<String, Any>
        val ageBucket = sortedIndexes["age"]
        ageBucket.shouldBeInstanceOf<ConcurrentSkipListMap<*, *>>()
        @Suppress("UNCHECKED_CAST")
        (ageBucket as ConcurrentSkipListMap<*, Set<*>>)[30]?.size shouldBe 2
    }

    "hash-indexed property continues to populate ConcurrentHashMap bucket" {
        val repo = TestVolatileRepo()
        val f1 = IndexFixture(1, "alpha", 20)
        val f2 = IndexFixture(2, "beta", 25)
        repo.add(f1)
        repo.add(f2)

        repo.findByIndex("label", "alpha") shouldBe setOf(f1)

        // Verify the underlying map type is ConcurrentHashMap
        val hashField =
            RegistryBase::class.java.declaredFields
                .first { it.name == "hashIndexes" }
                .apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val hashIndexes = hashField.get(repo) as Map<String, Any>
        val labelBucket = hashIndexes["label"]
        labelBucket.shouldBeInstanceOf<ConcurrentHashMap<*, *>>()
    }

    "findByIndex returns equivalent results for sorted-indexed and hash-indexed properties" {
        val repo = TestVolatileRepo()
        val fixtures =
            listOf(
                IndexFixture(1, "x", 10),
                IndexFixture(2, "y", 20),
                IndexFixture(3, "x", 20),
                IndexFixture(4, "z", 30),
                IndexFixture(5, "y", 10),
                IndexFixture(6, "x", 40),
                IndexFixture(7, "z", 20),
                IndexFixture(8, "w", 50),
                IndexFixture(9, "x", 10),
                IndexFixture(10, "y", 30)
            )
        fixtures.forEach { repo.add(it) }

        // Verify sorted-indexed age lookups
        repo.findByIndex("age", 10) shouldBe fixtures.filter { it.age == 10 }.toSet()
        repo.findByIndex("age", 20) shouldBe fixtures.filter { it.age == 20 }.toSet()
        repo.findByIndex("age", 30) shouldBe fixtures.filter { it.age == 30 }.toSet()

        // Verify hash-indexed label lookups
        repo.findByIndex("label", "x") shouldBe fixtures.filter { it.label == "x" }.toSet()
        repo.findByIndex("label", "y") shouldBe fixtures.filter { it.label == "y" }.toSet()
    }

    "deindexEntity removes from the correct bucket kind" {
        val repo = TestVolatileRepo()
        val f1 = IndexFixture(1, "remove-me", 99)
        val f2 = IndexFixture(2, "keep", 99)
        repo.add(f1)
        repo.add(f2)
        repo.remove(f1)

        repo.findByIndex("age", 99) shouldNotContain f1
        repo.findByIndex("age", 99) shouldContain f2
        repo.findByIndex("label", "remove-me") shouldNotContain f1
    }

    "clearSecondaryIndexes clears both bucket kinds" {
        val repo = TestVolatileRepo()
        (1..5).forEach { i -> repo.add(IndexFixture(i, "label-$i", i * 10)) }
        repo.clearIndexes()

        repo.findByIndex("age", 10) shouldBe emptySet()
        repo.findByIndex("label", "label-1") shouldBe emptySet()
    }

    "sortedBucketFor returns NavigableMap with natural ordering for sorted-indexed property and null for hash-indexed property" {
        val repo = TestVolatileRepo()
        val ages = listOf(50, 10, 30, 20, 40)
        ages.forEachIndexed { i, age -> repo.add(IndexFixture(i + 1, "l$i", age)) }

        val bucket = repo.sortedBucketForIndex("age")
        bucket.shouldNotBeNull()
        bucket.firstKey() shouldBe 10
        bucket.lastKey() shouldBe 50

        repo.sortedBucketForIndex("label").shouldBeNull()
    }

    "isPropertySortedIndexed reflects IndexEntry.sorted flag" {
        val repo = TestVolatileRepo()
        repo.add(IndexFixture(1, "a", 1))

        repo.isSortedIndexed(IndexFixture::age).shouldBeTrue()
        repo.isSortedIndexed(IndexFixture::label).shouldBeFalse()
    }
})