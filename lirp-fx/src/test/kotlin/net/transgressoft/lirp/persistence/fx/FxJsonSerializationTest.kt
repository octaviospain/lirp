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

package net.transgressoft.lirp.persistence.fx

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.json.lirpSerializer
import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.datatest.withData
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import javafx.beans.property.ObjectProperty
import javafx.collections.ObservableList
import javafx.collections.ObservableSet
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

/**
 * Tests verifying JSON serialization round-trips for entities using [fxAggregateList],
 * [fxAggregateSet], and fx scalar delegates. Fx proxies wrap mutable aggregate delegates whose
 * backing IDs must serialize/deserialize identically to non-fx aggregates. Fx scalar delegates
 * are included in serialization — their values are carried by constructor parameters and
 * serialized/deserialized as part of the entity's JSON representation.
 */
@DisplayName("FxJsonSerializationTest")
class FxJsonSerializationTest : StringSpec({

    reactiveScope()

    val json = Json { prettyPrint = true }
    val serializer = lirpSerializer(FxAudioPlaylistEntity(0, ""))

    data class EncodeCase(val name: String, val build: () -> FxAudioPlaylistEntity, val verify: (String) -> Unit)

    withData(
        nameFn = EncodeCase::name,
        EncodeCase(
            "serializes entity with fxAggregateList as ID list only",
            { FxAudioPlaylistEntity(1, "My Playlist", initialAudioItemIds = listOf(10, 20)) }
        ) { encoded ->
            encoded shouldContain "\"id\": 1"
            encoded shouldContain "\"name\": \"My Playlist\""
            encoded shouldContain "\"audioItems\""
            encoded shouldContain "10"
            encoded shouldContain "20"
            encoded shouldNotContain "\"innerProxy\""
            encoded shouldNotContain "\"localElements\""
        },
        EncodeCase(
            "serializes entity with fxAggregateSet as ID set only",
            { FxAudioPlaylistEntity(1, "Parent", initialPlaylistIds = setOf(2, 3)) }
        ) { encoded ->
            encoded shouldContain "\"playlists\""
            encoded shouldContain "2"
            encoded shouldContain "3"
            encoded shouldNotContain "\"innerProxy\""
        },
        EncodeCase(
            "serializes entity with empty fx collections",
            { FxAudioPlaylistEntity(1, "Empty") }
        ) { encoded ->
            encoded shouldContain "\"id\": 1"
            encoded shouldContain "\"name\": \"Empty\""
            encoded shouldContain "\"audioItems\": []"
            encoded shouldContain "\"playlists\": []"
        },
        EncodeCase(
            "serializes fx scalar delegate values in JSON",
            {
                FxAudioPlaylistEntity(
                    1, "Scalars", initialYear = 2025, initialActive = true,
                    initialRating = 4.5, initialTag = "rock", initialDescription = "Best of"
                )
            }
        ) { encoded ->
            encoded shouldContain "\"tagProperty\": \"rock\""
            encoded shouldContain "\"yearProperty\": 2025"
            encoded shouldContain "\"activeProperty\": true"
            encoded shouldContain "\"ratingProperty\": 4.5"
            encoded shouldContain "\"descriptionProperty\": \"Best of\""
            encoded shouldContain "\"name\": \"Scalars\""
        }
    ) { case -> case.verify(json.encodeToString(serializer, case.build())) }

    data class RoundTripCase(val name: String, val build: () -> FxAudioPlaylistEntity, val verify: (FxAudioPlaylistEntity) -> Unit)

    withData(
        nameFn = RoundTripCase::name,
        RoundTripCase(
            "deserializes entity preserving fxAggregateList IDs and collection facade",
            { FxAudioPlaylistEntity(5, "Round Trip", initialAudioItemIds = listOf(10, 20, 30)) }
        ) { decoded ->
            decoded.id shouldBe 5
            decoded.name shouldBe "Round Trip"
            decoded.audioItems.referenceIds shouldBe listOf(10, 20, 30)
            decoded.audioItems.shouldBeInstanceOf<ObservableList<*>>()
            decoded.audioItems.shouldBeInstanceOf<FxAggregateList<*, *>>()
        },
        RoundTripCase(
            "deserializes entity preserving fxAggregateSet IDs and collection facade",
            { FxAudioPlaylistEntity(1, "With Sets", initialPlaylistIds = setOf(2, 3)) }
        ) { decoded ->
            decoded.id shouldBe 1
            decoded.name shouldBe "With Sets"
            decoded.playlists.referenceIds shouldBe setOf(2, 3)
            decoded.playlists.shouldBeInstanceOf<ObservableSet<*>>()
            decoded.playlists.shouldBeInstanceOf<FxAggregateSet<*, *>>()
        },
        RoundTripCase(
            "round-trip preserves list order",
            { FxAudioPlaylistEntity(1, "Ordered", initialAudioItemIds = listOf(30, 10, 20)) }
        ) { decoded -> decoded.audioItems.referenceIds shouldBe listOf(30, 10, 20) },
        RoundTripCase(
            "round-trip after mutation preserves updated state",
            { FxAudioPlaylistEntity(1, "Mutable", initialAudioItemIds = listOf(10)).apply { name = "Updated Name" } }
        ) { decoded ->
            decoded.name shouldBe "Updated Name"
            decoded.audioItems.referenceIds shouldBe listOf(10)
        },
        RoundTripCase(
            "round-trip with both collections populated and facade intact",
            { FxAudioPlaylistEntity(1, "Full", initialAudioItemIds = listOf(10, 20), initialPlaylistIds = setOf(2, 3)) }
        ) { decoded ->
            decoded.id shouldBe 1
            decoded.name shouldBe "Full"
            decoded.audioItems.referenceIds shouldBe listOf(10, 20)
            decoded.audioItems.shouldBeInstanceOf<FxAggregateList<*, *>>()
            decoded.playlists.referenceIds shouldBe setOf(2, 3)
            decoded.playlists.shouldBeInstanceOf<FxAggregateSet<*, *>>()
        },
        RoundTripCase(
            "round-trip preserves fx scalar delegate values",
            {
                FxAudioPlaylistEntity(
                    1, "Complete", initialYear = 2024, initialActive = true,
                    initialRating = 9.5, initialTag = "jazz", initialDescription = "Classic",
                    initialAudioItemIds = listOf(10, 20), initialPlaylistIds = setOf(2)
                )
            }
        ) { decoded ->
            decoded.id shouldBe 1
            decoded.name shouldBe "Complete"
            decoded.tagProperty.get() shouldBe "jazz"
            decoded.yearProperty.get() shouldBe 2024
            decoded.activeProperty.get() shouldBe true
            decoded.ratingProperty.get() shouldBe 9.5
            decoded.descriptionProperty.get() shouldBe "Classic"
            decoded.audioItems.referenceIds shouldBe listOf(10, 20)
            decoded.playlists.referenceIds shouldBe setOf(2)
        },
        RoundTripCase(
            "round-trip preserves null fx object property",
            { FxAudioPlaylistEntity(1, "NullDesc", initialDescription = null) }
        ) { decoded -> decoded.descriptionProperty.get() shouldBe null }
    ) { case ->
        val encoded = json.encodeToString(serializer, case.build())
        case.verify(json.decodeFromString(serializer, encoded))
    }

    "fx object property holding a non-serializable type round-trips when registered contextually" {
        val module = SerializersModule { contextual(Tempo::class, TempoSerializer) }
        val tempoSerializer = lirpSerializer(TempoEntity(0), module)
        val original = TempoEntity(7).apply { tempoProperty.set(Tempo(128.5)) }

        val encoded = json.encodeToString(tempoSerializer, original)
        encoded shouldContain "\"tempoProperty\""
        encoded shouldContain "128.5"

        val decoded = json.decodeFromString(tempoSerializer, encoded)
        decoded.id shouldBe 7
        decoded.tempoProperty.get() shouldBe Tempo(128.5)
    }
})

/**
 * A non-`@Serializable` value type carried in a JavaFX [ObjectProperty], modeling a third-party or
 * domain type the consumer does not annotate. Resolved through a contextual [TempoSerializer].
 */
class Tempo(val beatsPerMinute: Double) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is Tempo && beatsPerMinute == other.beatsPerMinute)

    override fun hashCode(): Int = beatsPerMinute.hashCode()
}

/** Hand-written contextual serializer for the non-`@Serializable` [Tempo]. */
object TempoSerializer : KSerializer<Tempo> {
    override val descriptor = PrimitiveSerialDescriptor("Tempo", PrimitiveKind.DOUBLE)

    override fun serialize(encoder: Encoder, value: Tempo) = encoder.encodeDouble(value.beatsPerMinute)

    override fun deserialize(decoder: Decoder): Tempo = Tempo(decoder.decodeDouble())
}

/**
 * A `private` entity whose `fxObject` scalar holds the non-`@Serializable` [Tempo]. The declared
 * `ObjectProperty<Tempo>` type keeps it off the KSP FxScalar accessor, so [LirpEntitySerializer]
 * resolves the value serializer through the reflection fallback's `ObjectProperty` branch — the
 * third nested-field site threaded through the supplied module.
 */
private class TempoEntity(override val id: Int, initialTempo: Tempo? = null) : ReactiveEntityBase<Int, TempoEntity>() {
    override val uniqueId: String get() = "tempo-$id"

    val tempoProperty: ObjectProperty<Tempo?> by fxObject<Tempo>(initialTempo, dispatchToFxThread = false)

    override fun clone(): TempoEntity = TempoEntity(id, tempoProperty.get())

    override fun equals(other: Any?): Boolean =
        this === other || (other is TempoEntity && id == other.id && tempoProperty.get() == other.tempoProperty.get())

    override fun hashCode(): Int = 31 * id + (tempoProperty.get()?.hashCode() ?: 0)
}