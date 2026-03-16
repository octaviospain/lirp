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
import net.transgressoft.lirp.event.ReactiveScope
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.property.arbitrary.next
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Concurrency tests verifying that [RegistryBase] batch operations are safe under concurrent
 * modification of the underlying [java.util.concurrent.ConcurrentHashMap].
 *
 * These tests prove that [RegistryBase.runForMany], [RegistryBase.runMatching],
 * [RegistryBase.runForAll], and [RegistryBase.search] do not throw
 * [java.util.ConcurrentModificationException] when entities are added or removed
 * concurrently via another coroutine.
 */
@ExperimentalCoroutinesApi
internal class RegistryBaseConcurrencyTest : StringSpec({

    val testDispatcher = UnconfinedTestDispatcher()
    val testScope = CoroutineScope(testDispatcher)

    beforeSpec {
        ReactiveScope.flowScope = testScope
        ReactiveScope.ioScope = testScope
    }

    afterSpec {
        ReactiveScope.resetDefaultIoScope()
        ReactiveScope.resetDefaultFlowScope()
    }

    "RegistryBase runForMany completes without error under concurrent entity additions" {
        val concurrentCoroutines = 50
        val iterationsPerCoroutine = 100
        val initialSize = 100

        val repository = VolatileRepository<Int, Person>("runForMany-concurrency-test")
        val initialEntities = (1..initialSize).map { arbitraryPerson(it).next() }
        initialEntities.forEach { repository.add(it) }

        val targetIds = initialEntities.map { it.id }.toSet()

        shouldNotThrowAny {
            val writerJobs =
                (1..concurrentCoroutines).map { coroutineIndex ->
                    launch(Dispatchers.Default) {
                        repeat(iterationsPerCoroutine) { i ->
                            val id = initialSize + coroutineIndex * iterationsPerCoroutine + i
                            val extra = arbitraryPerson(id).next()
                            repository.add(extra)
                        }
                    }
                }

            val readerJob =
                launch(Dispatchers.Default) {
                    repeat(iterationsPerCoroutine) {
                        repository.runForMany(targetIds) { /* read-only action */ }
                    }
                }

            writerJobs.joinAll()
            readerJob.join()
        }
    }

    "RegistryBase runMatching completes without error under concurrent entity removals" {
        val concurrentCoroutines = 50
        val iterationsPerCoroutine = 100
        val initialSize = 100

        val repository = VolatileRepository<Int, Person>("runMatching-concurrency-test")
        val allEntities =
            (1..initialSize + concurrentCoroutines * iterationsPerCoroutine)
                .map { arbitraryPerson(it).next() }
        allEntities.forEach { repository.add(it) }

        shouldNotThrowAny {
            val removerJobs =
                (1..concurrentCoroutines).map { coroutineIndex ->
                    launch(Dispatchers.Default) {
                        repeat(iterationsPerCoroutine) { i ->
                            val entity = allEntities[coroutineIndex * iterationsPerCoroutine + i]
                            repository.remove(entity)
                        }
                    }
                }

            val readerJob =
                launch(Dispatchers.Default) {
                    repeat(iterationsPerCoroutine) {
                        repository.runMatching({ true }) { /* read-only action */ }
                    }
                }

            removerJobs.joinAll()
            readerJob.join()
        }
    }

    "RegistryBase runForAll completes without error under concurrent modifications" {
        val concurrentCoroutines = 50
        val iterationsPerCoroutine = 100
        val initialSize = 100

        val repository = VolatileRepository<Int, Person>("runForAll-concurrency-test")
        val initialEntities = (1..initialSize).map { arbitraryPerson(it).next() }
        initialEntities.forEach { repository.add(it) }

        shouldNotThrowAny {
            val writerJobs =
                (1..concurrentCoroutines).map { coroutineIndex ->
                    launch(Dispatchers.Default) {
                        repeat(iterationsPerCoroutine) { i ->
                            val id = initialSize + coroutineIndex * iterationsPerCoroutine + i
                            val extra = arbitraryPerson(id).next()
                            repository.add(extra)
                        }
                    }
                }

            val readerJob =
                launch(Dispatchers.Default) {
                    repeat(iterationsPerCoroutine) {
                        repository.runForAll { /* read-only action */ }
                    }
                }

            writerJobs.joinAll()
            readerJob.join()
        }
    }

    "RegistryBase search completes without error under concurrent modifications" {
        val concurrentCoroutines = 50
        val iterationsPerCoroutine = 100
        val initialSize = 100

        val repository = VolatileRepository<Int, Person>("search-concurrency-test")
        val initialEntities = (1..initialSize).map { arbitraryPerson(it).next() }
        initialEntities.forEach { repository.add(it) }

        shouldNotThrowAny {
            val modifierJobs =
                (1..concurrentCoroutines).map { coroutineIndex ->
                    launch(Dispatchers.Default) {
                        repeat(iterationsPerCoroutine) { i ->
                            if (i % 2 == 0) {
                                val id = initialSize + coroutineIndex * iterationsPerCoroutine + i
                                repository.add(arbitraryPerson(id).next())
                            } else {
                                val entity = initialEntities[(coroutineIndex + i) % initialSize]
                                repository.remove(entity)
                            }
                        }
                    }
                }

            val searchJob =
                launch(Dispatchers.Default) {
                    repeat(iterationsPerCoroutine) {
                        repository.search { true }
                    }
                }

            modifierJobs.joinAll()
            searchJob.join()
        }
    }
})