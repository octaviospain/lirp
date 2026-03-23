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

import net.transgressoft.lirp.event.ReactiveScope
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.property.Arb
import io.kotest.property.arbitrary.next
import io.kotest.property.arbitrary.positiveInt
import io.kotest.property.arbitrary.stringPattern
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher

private fun arbitraryCustomer(id: Int = -1) =
    io.kotest.property.arbitrary.arbitrary {
        Customer(
            id = if (id == -1) Arb.positiveInt(500_000).bind() else id,
            name = Arb.stringPattern("[a-z]{5} [a-z]{5}").bind()
        )
    }

/**
 * Concurrency tests verifying that [RegistryBase] iteration and search operations are safe
 * under concurrent modification of the underlying [java.util.concurrent.ConcurrentHashMap].
 *
 * These tests prove that [RegistryBase.iterator] and [RegistryBase.search] do not throw
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

    "RegistryBase iterator completes without error under concurrent entity additions" {
        val concurrentCoroutines = 50
        val iterationsPerCoroutine = 100
        val initialSize = 100

        val ctx = LirpContext()
        val repository = CustomerVolatileRepo(ctx)
        val initialEntities = (1..initialSize).map { arbitraryCustomer(it).next() }
        initialEntities.forEach { repository.create(it.id, it.name) }

        shouldNotThrowAny {
            val writerJobs =
                (1..concurrentCoroutines).map { coroutineIndex ->
                    launch(Dispatchers.Default) {
                        repeat(iterationsPerCoroutine) { i ->
                            val id = initialSize + coroutineIndex * iterationsPerCoroutine + i
                            val extra = arbitraryCustomer(id).next()
                            repository.create(extra.id, extra.name)
                        }
                    }
                }

            val readerJob =
                launch(Dispatchers.Default) {
                    repeat(iterationsPerCoroutine) {
                        repository.forEach { /* read-only iteration */ }
                    }
                }

            writerJobs.joinAll()
            readerJob.join()
        }
        ctx.close()
    }

    "RegistryBase iterator completes without error under concurrent entity removals" {
        val concurrentCoroutines = 50
        val iterationsPerCoroutine = 100
        val initialSize = 100

        val ctx = LirpContext()
        val repository = CustomerVolatileRepo(ctx)
        val allEntities =
            (1..initialSize + concurrentCoroutines * iterationsPerCoroutine)
                .map { arbitraryCustomer(it).next() }
        allEntities.forEach { repository.create(it.id, it.name) }

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
                        repository.forEach { /* read-only iteration */ }
                    }
                }

            removerJobs.joinAll()
            readerJob.join()
        }
        ctx.close()
    }

    "RegistryBase iterator completes without error under concurrent add and remove" {
        val concurrentCoroutines = 50
        val iterationsPerCoroutine = 100
        val initialSize = 100

        val ctx = LirpContext()
        val repository = CustomerVolatileRepo(ctx)
        val initialEntities = (1..initialSize).map { arbitraryCustomer(it).next() }
        initialEntities.forEach { repository.create(it.id, it.name) }

        shouldNotThrowAny {
            val writerJobs =
                (1..concurrentCoroutines).map { coroutineIndex ->
                    launch(Dispatchers.Default) {
                        repeat(iterationsPerCoroutine) { i ->
                            val id = initialSize + coroutineIndex * iterationsPerCoroutine + i
                            val extra = arbitraryCustomer(id).next()
                            repository.create(extra.id, extra.name)
                        }
                    }
                }

            val readerJob =
                launch(Dispatchers.Default) {
                    repeat(iterationsPerCoroutine) {
                        repository.forEach { /* read-only iteration */ }
                    }
                }

            writerJobs.joinAll()
            readerJob.join()
        }
        ctx.close()
    }

    "RegistryBase search completes without error under concurrent modifications" {
        val concurrentCoroutines = 50
        val iterationsPerCoroutine = 100
        val initialSize = 100

        val ctx = LirpContext()
        val repository = CustomerVolatileRepo(ctx)
        val initialEntities = (1..initialSize).map { arbitraryCustomer(it).next() }
        initialEntities.forEach { repository.create(it.id, it.name) }

        shouldNotThrowAny {
            val modifierJobs =
                (1..concurrentCoroutines).map { coroutineIndex ->
                    launch(Dispatchers.Default) {
                        repeat(iterationsPerCoroutine) { i ->
                            if (i % 2 == 0) {
                                val id = initialSize + coroutineIndex * iterationsPerCoroutine + i
                                val extra = arbitraryCustomer(id).next()
                                repository.create(extra.id, extra.name)
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
        ctx.close()
    }
})