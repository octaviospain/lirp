package net.transgressoft.lirp.persistence

import net.transgressoft.lirp.Person
import net.transgressoft.lirp.arbitraryPerson
import net.transgressoft.lirp.entity.IdentifiableEntity
import net.transgressoft.lirp.event.CrudEvent.Type.READ
import net.transgressoft.lirp.event.ReactiveScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.optional.shouldBeEmpty
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.set
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Collectors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * A minimal entity with `@Indexed` properties for secondary-index tests.
 *
 * [category] is indexed by property name; [nullableTag] is indexed with a custom name and may be null,
 * exercising the null-skip path in the index infrastructure.
 */
data class IndexedProduct(
    override val id: Int,
    @Indexed val category: String,
    @Indexed(name = "tag") val nullableTag: String?,
    override val uniqueId: String = "$id-$category"
) : IdentifiableEntity<Int> {
    override fun clone() = copy()
}

/**
 * Tests for lazy search methods added to [RegistryBase] via the [net.transgressoft.lirp.persistence.Registry] interface.
 *
 * Covers lazy evaluation through [Registry.lazySearch], Java Stream interop through [Registry.searchStream],
 * early termination, backward compatibility of existing [Registry.search] overloads, and secondary index
 * O(1) lookup via [Registry.findByIndex] and [Registry.findFirstByIndex].
 */
@ExperimentalCoroutinesApi
internal class SearchPerformanceTest : StringSpec({

    lateinit var repository: VolatileRepository<Int, Person>

    val testDispatcher = UnconfinedTestDispatcher()
    val testScope = CoroutineScope(testDispatcher)

    beforeSpec {
        ReactiveScope.flowScope = testScope
        ReactiveScope.ioScope = testScope
    }

    beforeTest {
        repository =
            VolatileRepository<Int, Person>("SearchPerformanceRepository").apply {
                activateEvents(READ)
            }
        val people = Arb.set(arbitraryPerson(), 10..10).next()
        repository.addOrReplaceAll(people)
    }

    afterSpec {
        ReactiveScope.resetDefaultIoScope()
        ReactiveScope.resetDefaultFlowScope()
    }

    "VolatileRepository lazySearch returns matching entities as Sequence" {
        val expected = repository.search { true }

        val result = repository.lazySearch { true }.toSet()

        result shouldContainOnly expected
    }

    "VolatileRepository lazySearch with take(n) returns at most n results" {
        val n = 3
        val result = repository.lazySearch { true }.take(n).toSet()

        result.size shouldBe n
    }

    "VolatileRepository lazySearch terminates early via take without evaluating all elements" {
        val evaluatedCount = AtomicInteger(0)
        val n = 2

        repository.lazySearch {
            evaluatedCount.incrementAndGet()
            true
        }.take(n).toList()

        // Only n predicates should have been evaluated due to early termination
        evaluatedCount.get() shouldBe n
    }

    "VolatileRepository lazySearch on empty repository returns empty Sequence" {
        val emptyRepository = VolatileRepository<Int, Person>("EmptyRepo")

        val result = emptyRepository.lazySearch { true }.toSet()

        result shouldBe emptySet()
    }

    "VolatileRepository lazySearch does not emit Read events" {
        val readEventEmitted = AtomicBoolean(false)
        repository.subscribe(READ) { readEventEmitted.set(true) }

        repository.lazySearch { true }.toSet()

        testDispatcher.scheduler.advanceUntilIdle()

        readEventEmitted.get() shouldBe false
    }

    "VolatileRepository searchStream returns matching entities as Java Stream" {
        val expected = repository.search { true }

        val result = repository.searchStream { true }.collect(Collectors.toSet())

        result shouldContainOnly expected
    }

    "VolatileRepository searchStream with limit and findFirst terminates early" {
        val evaluatedCount = AtomicInteger(0)

        val found =
            repository.searchStream {
                evaluatedCount.incrementAndGet()
                true
            }
                .limit(1)
                .findFirst()

        found.isPresent.shouldBeTrue()
        evaluatedCount.get() shouldBe 1
    }

    "VolatileRepository searchStream does not emit Read events" {
        val readEventEmitted = AtomicBoolean(false)
        repository.subscribe(READ) { readEventEmitted.set(true) }

        repository.searchStream { true }.collect(Collectors.toSet())

        testDispatcher.scheduler.advanceUntilIdle()

        readEventEmitted.get() shouldBe false
    }

    "VolatileRepository search(predicate) returns correct Set and emits Read event" {
        val readEventCount = AtomicInteger(0)
        repository.subscribe(READ) { readEventCount.incrementAndGet() }

        val expected = repository.toList().take(3).toSet()
        val ids = expected.map { it.id }.toSet()

        val result = repository.search { it.id in ids }

        testDispatcher.scheduler.advanceUntilIdle()

        result shouldContainOnly expected
        readEventCount.get() shouldBeGreaterThan 0
    }

    "VolatileRepository search(size, predicate) returns limited Set and emits Read event" {
        val readEventCount = AtomicInteger(0)
        repository.subscribe(READ) { readEventCount.incrementAndGet() }

        val result = repository.search(3) { true }

        testDispatcher.scheduler.advanceUntilIdle()

        result.size shouldBe 3
        readEventCount.get() shouldBeGreaterThan 0
    }

    // Secondary index tests

    "VolatileRepository findByIndex returns entities matching the indexed property value" {
        val indexRepo = VolatileRepository<Int, IndexedProduct>("IndexedRepo")
        val electronics1 = IndexedProduct(1, "electronics", null)
        val electronics2 = IndexedProduct(2, "electronics", null)
        val clothing = IndexedProduct(3, "clothing", null)
        indexRepo.add(electronics1)
        indexRepo.add(electronics2)
        indexRepo.add(clothing)

        val result = indexRepo.findByIndex("category", "electronics")

        result shouldContainOnly setOf(electronics1, electronics2)
    }

    "VolatileRepository findFirstByIndex returns Optional with first matching entity" {
        val indexRepo = VolatileRepository<Int, IndexedProduct>("IndexedRepo")
        val product = IndexedProduct(1, "books", null)
        indexRepo.add(product)

        val result = indexRepo.findFirstByIndex("category", "books")

        result.shouldBePresent { it shouldBe product }
    }

    "VolatileRepository findByIndex returns empty set when no entities match the value" {
        val indexRepo = VolatileRepository<Int, IndexedProduct>("IndexedRepo")
        indexRepo.add(IndexedProduct(1, "electronics", null))

        val result = indexRepo.findByIndex("category", "furniture")

        result.shouldBeEmpty()
    }

    "VolatileRepository findFirstByIndex returns empty Optional when no entities match" {
        val indexRepo = VolatileRepository<Int, IndexedProduct>("IndexedRepo")
        indexRepo.add(IndexedProduct(1, "electronics", null))

        val result = indexRepo.findFirstByIndex("category", "furniture")

        result.shouldBeEmpty()
    }

    "VolatileRepository findByIndex throws IllegalArgumentException for undeclared index name" {
        val indexRepo = VolatileRepository<Int, IndexedProduct>("IndexedRepo")
        indexRepo.add(IndexedProduct(1, "electronics", null))

        shouldThrow<IllegalArgumentException> {
            indexRepo.findByIndex("nonExistentIndex", "any")
        }
    }

    "VolatileRepository findFirstByIndex throws IllegalArgumentException for undeclared index name" {
        val indexRepo = VolatileRepository<Int, IndexedProduct>("IndexedRepo")
        indexRepo.add(IndexedProduct(1, "electronics", null))

        shouldThrow<IllegalArgumentException> {
            indexRepo.findFirstByIndex("nonExistentIndex", "any")
        }
    }

    "VolatileRepository index updates correctly on add — new entity appears in index" {
        val indexRepo = VolatileRepository<Int, IndexedProduct>("IndexedRepo")
        val product = IndexedProduct(1, "tools", null)
        indexRepo.add(product)

        val result = indexRepo.findByIndex("category", "tools")

        result shouldContain product
    }

    "VolatileRepository index updates correctly on remove — removed entity disappears from index" {
        val indexRepo = VolatileRepository<Int, IndexedProduct>("IndexedRepo")
        val product = IndexedProduct(1, "tools", null)
        indexRepo.add(product)
        indexRepo.remove(product)

        val result = indexRepo.findByIndex("category", "tools")

        result.shouldBeEmpty()
    }

    "VolatileRepository index updates correctly on addOrReplace — old value deindexed, new value indexed" {
        val indexRepo = VolatileRepository<Int, IndexedProduct>("IndexedRepo")
        val original = IndexedProduct(1, "gadgets", null)
        val replacement = IndexedProduct(1, "appliances", null)
        indexRepo.add(original)
        indexRepo.addOrReplace(replacement)

        val gadgets = indexRepo.findByIndex("category", "gadgets")
        val appliances = indexRepo.findByIndex("category", "appliances")

        gadgets.shouldBeEmpty()
        appliances shouldContain replacement
    }

    "VolatileRepository index clears correctly on clear — all index maps emptied" {
        val indexRepo = VolatileRepository<Int, IndexedProduct>("IndexedRepo")
        indexRepo.add(IndexedProduct(1, "electronics", null))
        indexRepo.add(IndexedProduct(2, "electronics", null))
        indexRepo.clear()

        val result = indexRepo.findByIndex("category", "electronics")

        result.shouldBeEmpty()
    }

    "VolatileRepository null indexed property value does not cause NPE — entity not indexed for that property" {
        val indexRepo = VolatileRepository<Int, IndexedProduct>("IndexedRepo")
        // nullableTag is null — should not be indexed under "tag"
        val product = IndexedProduct(1, "electronics", null)
        indexRepo.add(product)

        // The "tag" index should exist but have no entry for any value (entity skipped)
        val result = indexRepo.findByIndex("tag", "anything")

        result.shouldBeEmpty()
    }

    "VolatileRepository null indexed property does not prevent indexing of non-null properties" {
        val indexRepo = VolatileRepository<Int, IndexedProduct>("IndexedRepo")
        val product = IndexedProduct(1, "electronics", null)
        indexRepo.add(product)

        val result = indexRepo.findByIndex("category", "electronics")

        result shouldContain product
    }

    "VolatileRepository index works correctly after addOrReplaceAll with mixed new and replacement entities" {
        val indexRepo = VolatileRepository<Int, IndexedProduct>("IndexedRepo")
        val existing = IndexedProduct(1, "books", null)
        indexRepo.add(existing)

        val replacement = IndexedProduct(1, "magazines", null)
        val newEntity = IndexedProduct(2, "books", null)
        indexRepo.addOrReplaceAll(setOf(replacement, newEntity))

        val books = indexRepo.findByIndex("category", "books")
        val magazines = indexRepo.findByIndex("category", "magazines")

        // original entity (id=1) moved from "books" to "magazines"
        books shouldContainOnly setOf(newEntity)
        magazines shouldContainOnly setOf(replacement)
    }

    "VolatileRepository index with named @Indexed annotation resolves by custom name" {
        val indexRepo = VolatileRepository<Int, IndexedProduct>("IndexedRepo")
        val product = IndexedProduct(1, "electronics", "premium")
        indexRepo.add(product)

        val result = indexRepo.findByIndex("tag", "premium")

        result shouldContain product
    }

    "VolatileRepository index maintains correct state after removeAll" {
        val indexRepo = VolatileRepository<Int, IndexedProduct>("IndexedRepo")
        val product1 = IndexedProduct(1, "sports", null)
        val product2 = IndexedProduct(2, "sports", null)
        val product3 = IndexedProduct(3, "sports", null)
        indexRepo.add(product1)
        indexRepo.add(product2)
        indexRepo.add(product3)

        indexRepo.removeAll(listOf(product1, product2))

        val result = indexRepo.findByIndex("category", "sports")

        result shouldContainOnly setOf(product3)
    }

    "VolatileRepository findByIndex returns defensive copy — mutations to result do not affect index" {
        val indexRepo = VolatileRepository<Int, IndexedProduct>("IndexedRepo")
        val product = IndexedProduct(1, "electronics", null)
        indexRepo.add(product)

        val result = indexRepo.findByIndex("category", "electronics").toMutableSet()
        result.clear()

        // Index should still contain the entity
        val afterMutation = indexRepo.findByIndex("category", "electronics")
        afterMutation shouldContain product
    }
})