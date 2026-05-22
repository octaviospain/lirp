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

package net.transgressoft.lirp.persistence.json

import net.transgressoft.lirp.entity.ReactiveEntityBase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Round-trip-style entity exercised by the bulk-load tests. A pre-populated JSON file is loaded
 * by a fresh repository; subscribers attached after construction must observe no retroactive
 * MutationEvent or CrudEvent firing.
 */
class BulkLoadEntity(override val id: Int) : ReactiveEntityBase<Int, BulkLoadEntity>() {
    var label: String by reactiveProperty("default")
    var counter: Int by reactiveProperty(0)
    override val uniqueId: String get() = "bulk-load-$id"

    override fun clone(): BulkLoadEntity =
        BulkLoadEntity(id).also { copy ->
            copy.withEventsDisabled {
                copy.label = label
                copy.counter = counter
            }
        }
}

class JsonFileRepositoryRawInitLoadTest : StringSpec({

    fun freshJsonFile(): File {
        val dir = Files.createTempDirectory("lirp-raw-init-test").toFile()
        return File(dir, "entities.json").also { it.createNewFile() }
    }

    fun bulkSerializer() =
        MapSerializer(Int.serializer(), lirpSerializer(BulkLoadEntity(0)))

    "[JsonFileRepository] bulk load via raw initializer produces semantically equivalent entities" {
        val file = freshJsonFile()
        // Pre-populate the JSON file by writing through a first repository instance.
        val seedRepo = JsonFileRepository(file, bulkSerializer())
        repeat(10) { i ->
            seedRepo.add(
                BulkLoadEntity(i).apply {
                    label = "entity-$i"
                    counter = i * 100
                }
            )
        }
        seedRepo.close()

        // Construct a fresh repository — load reads from the populated file via deserialize +
        // raw initializer.
        val repo = JsonFileRepository(file, bulkSerializer())
        repo.size() shouldBe 10
        for (i in 0 until 10) {
            val loaded = repo.findById(i).orElseThrow()
            loaded.id shouldBe i
            loaded.label shouldBe "entity-$i"
            loaded.counter shouldBe i * 100
        }
        repo.close()
        file.parentFile?.deleteRecursively()
    }

    "[JsonFileRepository] bulk load emits no MutationEvent during load" {
        val file = freshJsonFile()
        val seedRepo = JsonFileRepository(file, bulkSerializer())
        repeat(5) { i ->
            seedRepo.add(
                BulkLoadEntity(i).apply {
                    label = "preset-$i"
                    counter = i
                }
            )
        }
        seedRepo.close()

        val repo = JsonFileRepository(file, bulkSerializer())
        // Attach a per-entity subscriber AFTER load completes; any retroactive event would surface here.
        val events = CopyOnWriteArrayList<String>()
        for (entity in repo) {
            entity.subscribe { event -> events += event.toString() }
        }
        // Bulk-load itself is a no-op for subscriber notifications. Sanity-check the first mutation
        // emits exactly one event so we know the subscription is wired.
        val one = repo.findById(0).orElseThrow()
        one.label = "mutated"
        // Give the reactive dispatch a tick to settle.
        Thread.sleep(50)
        events.shouldHaveSize(1)
        repo.close()
        file.parentFile?.deleteRecursively()
    }

    "[JsonFileRepository] bulk load restores reactive backing fields without java.base reflection" {
        // Behavioral guarantee for the deleted reflection fallback: a fresh load must populate
        // every reactive-backed property to its persisted value via the KSP-generated silent
        // setter. Under the old `KProperty1.get` + `Method.invoke(setValueMethod)` path this
        // required `--add-opens=java.base/java.lang` at JVM start; the test process here runs
        // without that flag, so any retained reflection in the load path would surface as an
        // InaccessibleObjectException rather than as a quiet correctness failure.
        val file = freshJsonFile()
        val seedRepo = JsonFileRepository(file, bulkSerializer())
        repeat(5) { i ->
            seedRepo.add(
                BulkLoadEntity(i).apply {
                    label = "reflection-free-$i"
                    counter = i * 7
                }
            )
        }
        seedRepo.close()

        val repo = JsonFileRepository(file, bulkSerializer())
        repo.size() shouldBe 5
        for (i in 0 until 5) {
            val loaded = repo.findById(i).orElseThrow()
            loaded.label shouldBe "reflection-free-$i"
            loaded.counter shouldBe i * 7
        }
        repo.close()
        file.parentFile?.deleteRecursively()
    }
})