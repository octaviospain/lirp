package net.transgressoft.lirp.persistence

import net.transgressoft.lirp.Person
import net.transgressoft.lirp.arbitraryPerson
import net.transgressoft.lirp.event.CrudEvent.Type.READ
import net.transgressoft.lirp.event.ReactiveScope
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainOnly
import io.kotest.matchers.ints.shouldBeGreaterThan
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
 * Tests for lazy search methods added to [RegistryBase] via the [net.transgressoft.lirp.persistence.Registry] interface.
 *
 * Covers lazy evaluation through [Registry.lazySearch], Java Stream interop through [Registry.searchStream],
 * early termination, and backward compatibility of existing [Registry.search] overloads.
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
})