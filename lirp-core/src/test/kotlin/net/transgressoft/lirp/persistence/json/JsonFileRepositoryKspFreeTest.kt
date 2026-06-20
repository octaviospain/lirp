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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * A `private` reactive entity. The KSP structural processors skip private/protected classes, so no
 * `<Entity>_LirpReactivePropertyAccessor` or `<Entity>_LirpRawInitializer` is generated for it —
 * even though this module applies lirp-ksp. It therefore stands in for an entity whose own module
 * deliberately keeps lirp-ksp out, exercising the reflection-based deserialization fallback.
 */
private class KspFreeEntity(override val id: Int) : ReactiveEntityBase<Int, KspFreeEntity>() {
    var label: String by reactiveProperty("default")
    var count: Int by reactiveProperty(0)
    override val uniqueId: String get() = "ksp-free-$id"

    override fun clone(): KspFreeEntity =
        KspFreeEntity(id).also { copy ->
            copy.withEventsDisabled {
                copy.label = label
                copy.count = count
            }
        }
}

/**
 * Verifies that [JsonFileRepository] persists and reloads reactive entities whose module does not
 * apply lirp-ksp, using the reflection-based reactive-property accessor fallback in
 * [LirpEntitySerializer] rather than a generated accessor or a hand-authored `_LirpRawInitializer`.
 */
class JsonFileRepositoryKspFreeTest : StringSpec({

    fun freshJsonFile(): File {
        val dir = Files.createTempDirectory("lirp-ksp-free-test").toFile()
        return File(dir, "entities.json").also { it.createNewFile() }
    }

    fun kspFreeSerializer() =
        MapSerializer(Int.serializer(), lirpSerializer(KspFreeEntity(0)))

    "the test entity has no KSP-generated accessors, so the reflection fallback is what is exercised" {
        // If KSP had generated an accessor for KspFreeEntity, the round-trip below would pass via the
        // generated path instead of the fallback. Asserting its absence keeps this suite honest.
        shouldThrow<ClassNotFoundException> {
            Class.forName("${KspFreeEntity::class.java.name}_LirpReactivePropertyAccessor")
        }
        shouldThrow<ClassNotFoundException> {
            Class.forName("${KspFreeEntity::class.java.name}_LirpRawInitializer")
        }
    }

    "JsonFileRepository round-trips a reactive entity without KSP via the reflection fallback" {
        val file = freshJsonFile()
        val seedRepo = JsonFileRepository(file, kspFreeSerializer())
        repeat(5) { i ->
            seedRepo.add(
                KspFreeEntity(i).apply {
                    label = "ksp-free-$i"
                    count = i * 11
                }
            )
        }
        seedRepo.close()

        // A fresh repository loads from the populated file. Previously this threw
        // "Entity ... has no generated LirpRawInitializer"; now it deserializes via reflection.
        val repo = JsonFileRepository(file, kspFreeSerializer())
        repo.size() shouldBe 5
        for (i in 0 until 5) {
            val loaded = repo.findById(i).orElseThrow()
            loaded.id shouldBe i
            loaded.label shouldBe "ksp-free-$i"
            loaded.count shouldBe i * 11
        }
        repo.close()
        file.parentFile?.deleteRecursively()
    }

    "KSP-free bulk load restores reactive fields silently, firing no retroactive MutationEvent" {
        val file = freshJsonFile()
        val seedRepo = JsonFileRepository(file, kspFreeSerializer())
        repeat(3) { i ->
            seedRepo.add(
                KspFreeEntity(i).apply {
                    label = "preset-$i"
                    count = i
                }
            )
        }
        seedRepo.close()

        val repo = JsonFileRepository(file, kspFreeSerializer())
        val events = CopyOnWriteArrayList<String>()
        val firstEvent = CountDownLatch(1)
        for (entity in repo) {
            entity.subscribeAsync { event ->
                events += event.toString()
                firstEvent.countDown()
            }
        }
        // The silent fallback setter bypasses event emission during load; the first real mutation
        // afterwards confirms the subscription is live and the load itself stayed quiet.
        repo.findById(0).orElseThrow().label = "mutated"
        firstEvent.await(2, TimeUnit.SECONDS) shouldBe true
        events.size shouldBe 1
        repo.close()
        file.parentFile?.deleteRecursively()
    }
})