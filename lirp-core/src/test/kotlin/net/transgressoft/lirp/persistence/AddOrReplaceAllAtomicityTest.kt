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

import net.transgressoft.lirp.Person
import net.transgressoft.lirp.arbitraryPerson
import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.CrudEvent.Type.CREATE
import net.transgressoft.lirp.event.CrudEvent.Type.UPDATE
import net.transgressoft.lirp.event.LirpEventSubscriberBase
import net.transgressoft.lirp.event.ReactiveScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.optional.shouldBeEmpty
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import io.kotest.property.arbitrary.next
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Tests the atomic rollback behaviour of [VolatileRepository.addOrReplaceAll].
 *
 * When an exception occurs mid-operation, all entities already processed in the batch
 * are rolled back so the repository returns to its pre-call state. No CRUD events are
 * emitted for a failed batch.
 */
@ExperimentalCoroutinesApi
internal class AddOrReplaceAllAtomicityTest : StringSpec({

    lateinit var repository: VolatileRepository<Int, Person>

    val testDispatcher = UnconfinedTestDispatcher()

    beforeSpec {
        ReactiveScope.flowScope = CoroutineScope(testDispatcher)
        ReactiveScope.ioScope = CoroutineScope(testDispatcher)
    }

    afterSpec {
        ReactiveScope.resetDefaultFlowScope()
        ReactiveScope.resetDefaultIoScope()
    }

    beforeTest {
        repository = VolatileRepository("atomicity-test")
    }

    "addOrReplaceAll rolls back additions and updates when an exception occurs mid-batch" {
        val existing = Person(id = 1, initialName = "Original", money = 100, morals = true)
        repository.add(existing) shouldBe true

        val newEntity = Person(id = 2, initialName = "New", money = 200, morals = false)
        val updateExisting = Person(id = 1, initialName = "Updated", money = 999, morals = true)

        val failingSet = failingSetOf(newEntity, updateExisting, failAfter = 2)

        shouldThrow<RuntimeException> {
            repository.addOrReplaceAll(failingSet)
        }

        repository.size() shouldBe 1
        repository.findById(1).shouldBePresent { it.money shouldBe 100 }
        repository.findById(2).shouldBeEmpty()
    }

    "addOrReplaceAll rolls back all additions on empty repository when exception occurs" {
        val person1 = Person(id = 1, initialName = "Alice", money = 50, morals = true)
        val person2 = Person(id = 2, initialName = "Bob", money = 60, morals = false)

        val failingSet = failingSetOf(person1, person2, failAfter = 2)

        shouldThrow<RuntimeException> {
            repository.addOrReplaceAll(failingSet)
        }

        repository.size() shouldBe 0
        repository.findById(1).shouldBeEmpty()
        repository.findById(2).shouldBeEmpty()
    }

    "addOrReplaceAll does not emit events when the batch fails" {
        val eventCounter = AtomicInteger(0)
        val subscriber =
            object : LirpEventSubscriberBase<Person, CrudEvent.Type, CrudEvent<Int, Person>>("counter") {
                init {
                    addOnNextEventAction(CREATE, UPDATE) { eventCounter.incrementAndGet() }
                }
            }
        repository.subscribe(subscriber)

        val failingSet =
            failingSetOf(
                Person(id = 1, initialName = "A", money = 10, morals = true),
                failAfter = 1
            )

        shouldThrow<RuntimeException> {
            repository.addOrReplaceAll(failingSet)
        }

        testDispatcher.scheduler.advanceUntilIdle()
        eventCounter.get() shouldBe 0
    }

    "addOrReplaceAll repository remains functional after a rolled-back batch" {
        val existing = Person(id = 1, initialName = "Stable", money = 500, morals = true)
        repository.add(existing) shouldBe true

        val failingSet =
            failingSetOf(
                Person(id = 2, initialName = "Fail", money = 0, morals = false),
                failAfter = 1
            )

        shouldThrow<RuntimeException> {
            repository.addOrReplaceAll(failingSet)
        }

        val newPerson = arbitraryPerson().next()
        repository.add(newPerson) shouldBe true
        repository.size() shouldBe 2
        repository.findById(newPerson.id).shouldBePresent()
    }

    "addOrReplaceAll rolls back first element when failure occurs on second" {
        val first = Person(id = 10, initialName = "First", money = 100, morals = true)

        val failingSet = failingSetOf(first, failAfter = 1)

        shouldThrow<RuntimeException> {
            repository.addOrReplaceAll(failingSet)
        }

        repository.size() shouldBe 0
        repository.findById(10).shouldBeEmpty()
    }
})

/**
 * Creates a [Set] whose iterator throws [RuntimeException] after yielding [failAfter] elements.
 */
private fun <T> failingSetOf(vararg elements: T, failAfter: Int): Set<T> {
    val list = elements.toList()
    return object : AbstractSet<T>() {
        override val size = list.size + 1

        override fun iterator() =
            object : Iterator<T> {
                var index = 0

                override fun hasNext() = index <= list.size

                override fun next(): T {
                    if (index == failAfter) throw RuntimeException("Simulated mid-batch failure at element $failAfter")
                    return list[index++]
                }
            }
    }
}