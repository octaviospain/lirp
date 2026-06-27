/******************************************************************************
 *     Copyright (C) 2026  Octavio Calleya Garcia                             *
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

import net.transgressoft.lirp.entity.SoftDeletable
import net.transgressoft.lirp.persistence.AudioItem
import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.persistence.SoftDeletableMutableAudioItem
import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempfile
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.optional.shouldNotBePresent
import io.kotest.matchers.shouldBe
import java.io.File
import java.time.Instant
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

/**
 * Serializer for [java.time.Instant] using ISO-8601 string form — the same encoding used by
 * [net.transgressoft.lirp.persistence.InstantColumnConverter] for SQL persistence, ensuring
 * consistent text representation across all durable backends.
 */
private object InstantAsStringSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeString(value.toString())
    }

    override fun deserialize(decoder: Decoder): Instant = Instant.parse(decoder.decodeString())
}

/** [SerializersModule] registering a contextual [Instant] serializer. */
private val instantModule: SerializersModule = SerializersModule { contextual(InstantAsStringSerializer) }

/**
 * JSON-backed repository for [SoftDeletableMutableAudioItem] entities typed as [AudioItem],
 * used in soft-delete persistence tests.
 *
 * Wraps [JsonFileRepository] with the ISO-8601 [instantModule] so the `deletedAt` reactive
 * property is included in the serialized form and round-trips correctly through the JSON file.
 */
private class SoftDeletableMutableAudioItemJsonRepository(
    context: LirpContext,
    file: File,
    serializationDelayMs: Long = 50L
) : JsonFileRepository<Int, AudioItem>(
        context = context,
        file = file,
        mapSerializer =
            @Suppress("UNCHECKED_CAST")
            (
                MapSerializer(
                    Int.serializer(),
                    lirpSerializer(SoftDeletableMutableAudioItem(0, ""), instantModule)
                ) as KSerializer<Map<Int, AudioItem>>
            ),
        repositorySerializersModule = instantModule,
        serializationDelay = serializationDelayMs.milliseconds
    )

/**
 * Persistence round-trip tests verifying that the `deletedAt` field of [SoftDeletableMutableAudioItem]
 * survives a flush-to-disk / reload cycle in [JsonFileRepository] and that the default read path
 * excludes soft-deleted entities after reload — matching SQL load-all-then-filter semantics.
 */
internal class SoftDeleteJsonRoundTripTest : StringSpec({

    val reactive = reactiveScope()

    lateinit var ctx: LirpContext
    lateinit var jsonFile: File

    beforeEach {
        ctx = LirpContext()
        jsonFile = tempfile("soft-delete-json-rt", ".json").also { it.deleteOnExit() }
    }

    afterEach {
        ctx.close()
    }

    "SoftDeleteJsonRoundTripTest flush→reload preserves non-null deletedAt" {
        val repo = SoftDeletableMutableAudioItemJsonRepository(ctx, jsonFile)
        val entity = SoftDeletableMutableAudioItem(id = 1, title = "Ghost Track")
        repo.add(entity)
        repo.softDelete(entity)
        // Flush to disk
        repo.close()

        // Reload in a fresh context
        val ctx2 = LirpContext()
        val repo2 = SoftDeletableMutableAudioItemJsonRepository(ctx2, jsonFile)
        val reloaded = repo2.rawIterator().asSequence().find { it.id == 1 } as? SoftDeletable
        reloaded.shouldNotBeNull()
        reloaded.deletedAt.shouldNotBeNull()
        reloaded.deletedAt shouldBe entity.deletedAt
        repo2.close()
        ctx2.close()
    }

    "SoftDeleteJsonRoundTripTest soft-deleted entity excluded from default findById after reload" {
        val repo = SoftDeletableMutableAudioItemJsonRepository(ctx, jsonFile)
        val entity = SoftDeletableMutableAudioItem(id = 2, title = "Hidden Track")
        repo.add(entity)
        repo.softDelete(entity)
        repo.close()

        val ctx2 = LirpContext()
        val repo2 = SoftDeletableMutableAudioItemJsonRepository(ctx2, jsonFile)
        // Default read path excludes the soft-deleted entity
        repo2.findById(2).shouldNotBePresent()
        // But the entity IS resident in memory (load-all-then-filter)
        repo2.rawIterator().asSequence().any { it.id == 2 } shouldBe true
        repo2.close()
        ctx2.close()
    }

    "SoftDeleteJsonRoundTripTest restore clears deletedAt and entity visible again after reload" {
        val repo = SoftDeletableMutableAudioItemJsonRepository(ctx, jsonFile)
        val entity = SoftDeletableMutableAudioItem(id = 3, title = "Restored Track")
        repo.add(entity)
        repo.softDelete(entity)
        reactive.advance() // flush soft-delete

        repo.restore(entity)
        repo.close() // flush restore + close

        val ctx2 = LirpContext()
        val repo2 = SoftDeletableMutableAudioItemJsonRepository(ctx2, jsonFile)
        // After restore, deletedAt is null and the entity is visible via default reads
        repo2.findById(3).shouldBePresent { reloaded ->
            (reloaded as? SoftDeletable)?.deletedAt.shouldBeNull()
        }
        repo2.close()
        ctx2.close()
    }
})