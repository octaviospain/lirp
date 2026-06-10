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

import net.transgressoft.lirp.testing.Stress
import net.transgressoft.lirp.testing.arbitraryAudioItem
import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.core.spec.style.StringSpec
import io.kotest.property.arbitrary.next
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch

/**
 * Concurrency tests verifying that [RegistryBase] iteration and search operations are safe
 * under concurrent modification of the underlying [java.util.concurrent.ConcurrentHashMap].
 *
 * These tests prove that [RegistryBase.iterator] and [RegistryBase.search] do not throw
 * [java.util.ConcurrentModificationException] when entities are added or removed
 * concurrently via another coroutine.
 */
internal class RegistryBaseConcurrencyTest : StringSpec({
    tags(Stress)

    val reactive = reactiveScope()

    "iterator completes without error under concurrent entity additions" {
        val concurrentCoroutines = 50
        val iterationsPerCoroutine = 100
        val initialSize = 100

        val ctx = LirpContext()
        val repository = AudioItemVolatileRepository(ctx)
        val initialEntities = (1..initialSize).map { arbitraryAudioItem(it).next() }
        initialEntities.forEach { repository.create(it.id, it.title) }

        shouldNotThrowAny {
            val writerJobs =
                (1..concurrentCoroutines).map { coroutineIndex ->
                    launch(Dispatchers.Default) {
                        repeat(iterationsPerCoroutine) { i ->
                            val id = initialSize + coroutineIndex * iterationsPerCoroutine + i
                            val extra = arbitraryAudioItem(id).next()
                            repository.create(extra.id, extra.title)
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

    "iterator completes without error under concurrent entity removals" {
        val concurrentCoroutines = 50
        val iterationsPerCoroutine = 100
        val initialSize = 100

        val ctx = LirpContext()
        val repository = AudioItemVolatileRepository(ctx)
        val allEntities =
            (1..initialSize + concurrentCoroutines * iterationsPerCoroutine)
                .map { arbitraryAudioItem(it).next() }
        allEntities.forEach { repository.create(it.id, it.title) }

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

    "iterator completes without error under concurrent add and remove" {
        val concurrentCoroutines = 50
        val iterationsPerCoroutine = 100
        val initialSize = 100

        val ctx = LirpContext()
        val repository = AudioItemVolatileRepository(ctx)
        val initialEntities = (1..initialSize).map { arbitraryAudioItem(it).next() }
        initialEntities.forEach { repository.create(it.id, it.title) }

        shouldNotThrowAny {
            val writerJobs =
                (1..concurrentCoroutines).map { coroutineIndex ->
                    launch(Dispatchers.Default) {
                        repeat(iterationsPerCoroutine) { i ->
                            val id = initialSize + coroutineIndex * iterationsPerCoroutine + i
                            val extra = arbitraryAudioItem(id).next()
                            repository.create(extra.id, extra.title)
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

    "search completes without error under concurrent modifications" {
        val concurrentCoroutines = 50
        val iterationsPerCoroutine = 100
        val initialSize = 100

        val ctx = LirpContext()
        val repository = AudioItemVolatileRepository(ctx)
        val initialEntities = (1..initialSize).map { arbitraryAudioItem(it).next() }
        initialEntities.forEach { repository.create(it.id, it.title) }

        shouldNotThrowAny {
            val modifierJobs =
                (1..concurrentCoroutines).map { coroutineIndex ->
                    launch(Dispatchers.Default) {
                        repeat(iterationsPerCoroutine) { i ->
                            if (i % 2 == 0) {
                                val id = initialSize + coroutineIndex * iterationsPerCoroutine + i
                                val extra = arbitraryAudioItem(id).next()
                                repository.create(extra.id, extra.title)
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