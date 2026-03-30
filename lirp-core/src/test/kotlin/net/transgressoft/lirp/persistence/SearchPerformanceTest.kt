package net.transgressoft.lirp.persistence

import net.transgressoft.lirp.entity.IdentifiableEntity
import net.transgressoft.lirp.event.CrudEvent.Type.READ
import net.transgressoft.lirp.event.ReactiveScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
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
import io.kotest.property.arbitrary.positiveInt
import io.kotest.property.arbitrary.set
import io.kotest.property.arbitrary.stringPattern
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.stream.Collectors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

private fun arbitraryCustomer(id: Int = -1) =
    io.kotest.property.arbitrary.arbitrary {
        Customer(
            id = if (id == -1) Arb.positiveInt(500_000).bind() else id,
            initialName = Arb.stringPattern("[a-z]{5} [a-z]{5}").bind()
        )
    }

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
 * Test-local repository subclass for [IndexedProduct] entities.
 *
 * Exposes typed factory and mutation methods for use in index-related tests.
 */
class IndexedProductVolatileRepo : VolatileRepository<Int, IndexedProduct>("IndexedRepo") {
    fun create(product: IndexedProduct): IndexedProduct = product.also { add(it) }

    fun replace(product: IndexedProduct): Boolean {
        val existing = findById(product.id)
        if (existing.isPresent) {
            if (existing.get() == product) return false
            remove(existing.get())
        }
        val sizeBefore = size()
        add(product)
        return size() > sizeBefore
    }
}

/**
 * Tests for lazy search methods added to [RegistryBase] via the [net.transgressoft.lirp.persistence.Registry] interface.
 *
 * Covers lazy evaluation through [Registry.lazySearch], Java Stream interop through [Registry.searchStream],
 * early termination, backward compatibility of existing [Registry.search] overloads, and secondary index
 * O(1) lookup via [Registry.findByIndex] and [Registry.findFirstByIndex].
 */
@ExperimentalCoroutinesApi
@DisplayName("VolatileRepository search performance")
internal class SearchPerformanceTest : StringSpec({

    lateinit var ctx: LirpContext
    lateinit var repository: CustomerVolatileRepo

    val testDispatcher = UnconfinedTestDispatcher()
    val testScope = CoroutineScope(testDispatcher)

    beforeSpec {
        ReactiveScope.flowScope = testScope
        ReactiveScope.ioScope = testScope
    }

    beforeTest {
        ctx = LirpContext()
        repository =
            CustomerVolatileRepo(ctx).apply {
                activateEvents(READ)
            }
        val customers = Arb.set(arbitraryCustomer(), 10..10).next()
        customers.forEach { repository.create(it.id, it.name) }
    }

    afterTest {
        ctx.close()
    }

    afterSpec {
        ReactiveScope.resetDefaultIoScope()
        ReactiveScope.resetDefaultFlowScope()
    }

    "lazySearch returns matching entities as Sequence" {
        val expected = repository.search { true }

        val result = repository.lazySearch { true }.toSet()

        result shouldContainOnly expected
    }

    "lazySearch with take(n) returns at most n results" {
        val n = 3
        val result = repository.lazySearch { true }.take(n).toSet()

        result.size shouldBe n
    }

    "lazySearch terminates early via take without evaluating all elements" {
        val evaluatedCount = AtomicInteger(0)
        val n = 2

        repository.lazySearch {
            evaluatedCount.incrementAndGet()
            true
        }.take(n).toList()

        evaluatedCount.get() shouldBe n
    }

    "lazySearch on empty repository returns empty Sequence" {
        val emptyRepository = VolatileRepository<Int, Customer>("EmptyRepo")

        val result = emptyRepository.lazySearch { true }.toSet()

        result shouldBe emptySet()
    }

    "lazySearch does not emit Read events" {
        val readEventEmitted = AtomicBoolean(false)
        repository.subscribe(READ) { readEventEmitted.set(true) }

        repository.lazySearch { true }.toSet()

        testDispatcher.scheduler.advanceUntilIdle()

        readEventEmitted.get() shouldBe false
    }

    "searchStream returns matching entities as Java Stream" {
        val expected = repository.search { true }

        val result = repository.searchStream { true }.collect(Collectors.toSet())

        result shouldContainOnly expected
    }

    "searchStream with limit and findFirst terminates early" {
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

    "searchStream does not emit Read events" {
        val readEventEmitted = AtomicBoolean(false)
        repository.subscribe(READ) { readEventEmitted.set(true) }

        repository.searchStream { true }.collect(Collectors.toSet())

        testDispatcher.scheduler.advanceUntilIdle()

        readEventEmitted.get() shouldBe false
    }

    "search(predicate) returns correct Set and emits Read event" {
        val readEventCount = AtomicInteger(0)
        repository.subscribe(READ) { readEventCount.incrementAndGet() }

        val expected = repository.toList().take(3).toSet()
        val ids = expected.map { it.id }.toSet()

        val result = repository.search { it.id in ids }

        testDispatcher.scheduler.advanceUntilIdle()

        result shouldContainOnly expected
        readEventCount.get() shouldBeGreaterThan 0
    }

    "search(size, predicate) returns limited Set and emits Read event" {
        val readEventCount = AtomicInteger(0)
        repository.subscribe(READ) { readEventCount.incrementAndGet() }

        val result = repository.search(3) { true }

        testDispatcher.scheduler.advanceUntilIdle()

        result.size shouldBe 3
        readEventCount.get() shouldBeGreaterThan 0
    }

    // Secondary index tests

    "findByIndex returns entities matching the indexed property value" {
        val indexRepo = IndexedProductVolatileRepo()
        val electronics1 = IndexedProduct(1, "electronics", null)
        val electronics2 = IndexedProduct(2, "electronics", null)
        val clothing = IndexedProduct(3, "clothing", null)
        indexRepo.create(electronics1)
        indexRepo.create(electronics2)
        indexRepo.create(clothing)

        val result = indexRepo.findByIndex("category", "electronics")

        result shouldContainOnly setOf(electronics1, electronics2)
    }

    "findFirstByIndex returns Optional with first matching entity" {
        val indexRepo = IndexedProductVolatileRepo()
        val product = IndexedProduct(1, "books", null)
        indexRepo.create(product)

        val result = indexRepo.findFirstByIndex("category", "books")

        result.shouldBePresent { it shouldBe product }
    }

    "findByIndex returns empty set when no entities match the value" {
        val indexRepo = IndexedProductVolatileRepo()
        indexRepo.create(IndexedProduct(1, "electronics", null))

        val result = indexRepo.findByIndex("category", "furniture")

        result.shouldBeEmpty()
    }

    "findFirstByIndex returns empty Optional when no entities match" {
        val indexRepo = IndexedProductVolatileRepo()
        indexRepo.create(IndexedProduct(1, "electronics", null))

        val result = indexRepo.findFirstByIndex("category", "furniture")

        result.shouldBeEmpty()
    }

    "findByIndex throws IllegalArgumentException for undeclared index name" {
        val indexRepo = IndexedProductVolatileRepo()
        indexRepo.create(IndexedProduct(1, "electronics", null))

        shouldThrow<IllegalArgumentException> {
            indexRepo.findByIndex("nonExistentIndex", "any")
        }
    }

    "findFirstByIndex throws IllegalArgumentException for undeclared index name" {
        val indexRepo = IndexedProductVolatileRepo()
        indexRepo.create(IndexedProduct(1, "electronics", null))

        shouldThrow<IllegalArgumentException> {
            indexRepo.findFirstByIndex("nonExistentIndex", "any")
        }
    }

    "index updates correctly on add — new entity appears in index" {
        val indexRepo = IndexedProductVolatileRepo()
        val product = IndexedProduct(1, "tools", null)
        indexRepo.create(product)

        val result = indexRepo.findByIndex("category", "tools")

        result shouldContain product
    }

    "index updates correctly on remove — removed entity disappears from index" {
        val indexRepo = IndexedProductVolatileRepo()
        val product = IndexedProduct(1, "tools", null)
        indexRepo.create(product)
        indexRepo.remove(product)

        val result = indexRepo.findByIndex("category", "tools")

        result.shouldBeEmpty()
    }

    "index updates correctly on replace — old value deindexed, new value indexed" {
        val indexRepo = IndexedProductVolatileRepo()
        val original = IndexedProduct(1, "gadgets", null)
        val replacement = IndexedProduct(1, "appliances", null)
        indexRepo.create(original)
        indexRepo.replace(replacement)

        val gadgets = indexRepo.findByIndex("category", "gadgets")
        val appliances = indexRepo.findByIndex("category", "appliances")

        gadgets.shouldBeEmpty()
        appliances shouldContain replacement
    }

    "index clears correctly on clear — all index maps emptied" {
        val indexRepo = IndexedProductVolatileRepo()
        indexRepo.create(IndexedProduct(1, "electronics", null))
        indexRepo.create(IndexedProduct(2, "electronics", null))
        indexRepo.clear()

        val result = indexRepo.findByIndex("category", "electronics")

        result.shouldBeEmpty()
    }

    "null indexed property value does not cause NPE — entity not indexed for that property" {
        val indexRepo = IndexedProductVolatileRepo()
        val product = IndexedProduct(1, "electronics", null)
        indexRepo.create(product)

        val result = indexRepo.findByIndex("tag", "anything")

        result.shouldBeEmpty()
    }

    "null indexed property does not prevent indexing of non-null properties" {
        val indexRepo = IndexedProductVolatileRepo()
        val product = IndexedProduct(1, "electronics", null)
        indexRepo.create(product)

        val result = indexRepo.findByIndex("category", "electronics")

        result shouldContain product
    }

    "index works correctly after batch replace with mixed new and replacement entities" {
        val indexRepo = IndexedProductVolatileRepo()
        val existing = IndexedProduct(1, "books", null)
        indexRepo.create(existing)

        val replacement = IndexedProduct(1, "magazines", null)
        val newEntity = IndexedProduct(2, "books", null)
        indexRepo.replace(replacement)
        indexRepo.create(newEntity)

        val books = indexRepo.findByIndex("category", "books")
        val magazines = indexRepo.findByIndex("category", "magazines")

        books shouldContainOnly setOf(newEntity)
        magazines shouldContainOnly setOf(replacement)
    }

    "index with named @Indexed annotation resolves by custom name" {
        val indexRepo = IndexedProductVolatileRepo()
        val product = IndexedProduct(1, "electronics", "premium")
        indexRepo.create(product)

        val result = indexRepo.findByIndex("tag", "premium")

        result shouldContain product
    }

    "index maintains correct state after removeAll" {
        val indexRepo = IndexedProductVolatileRepo()
        val product1 = IndexedProduct(1, "sports", null)
        val product2 = IndexedProduct(2, "sports", null)
        val product3 = IndexedProduct(3, "sports", null)
        indexRepo.create(product1)
        indexRepo.create(product2)
        indexRepo.create(product3)

        indexRepo.removeAll(listOf(product1, product2))

        val result = indexRepo.findByIndex("category", "sports")

        result shouldContainOnly setOf(product3)
    }

    "findByIndex returns defensive copy — mutations to result do not affect index" {
        val indexRepo = IndexedProductVolatileRepo()
        val product = IndexedProduct(1, "electronics", null)
        indexRepo.create(product)

        val result = indexRepo.findByIndex("category", "electronics").toMutableSet()
        result.clear()

        val afterMutation = indexRepo.findByIndex("category", "electronics")
        afterMutation shouldContain product
    }
})