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

import net.transgressoft.lirp.event.ReactiveScope
import net.transgressoft.lirp.persistence.json.lirpSerializer
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import javafx.collections.ObservableList
import javafx.collections.ObservableSet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.json.Json

/**
 * Tests verifying JSON serialization round-trips for entities using [fxAggregateList],
 * [fxAggregateSet], and fx scalar delegates. Fx proxies wrap mutable aggregate delegates whose
 * backing IDs must serialize/deserialize identically to non-fx aggregates. Fx scalar delegates
 * are included in serialization — their values are carried by constructor parameters and
 * serialized/deserialized as part of the entity's JSON representation.
 */
@DisplayName("FxJsonSerializationTest")
@OptIn(ExperimentalCoroutinesApi::class)
class FxJsonSerializationTest : StringSpec({

    val testDispatcher = UnconfinedTestDispatcher()
    val testScope = CoroutineScope(testDispatcher)
    val json = Json { prettyPrint = true }
    val serializer = lirpSerializer(FxAudioPlaylistEntity(0, ""))

    beforeSpec {
        FxToolkitInit.ensureInitialized()
        ReactiveScope.flowScope = testScope
        ReactiveScope.ioScope = testScope
    }

    afterSpec {
        ReactiveScope.resetDefaultIoScope()
        ReactiveScope.resetDefaultFlowScope()
    }

    "serializes entity with fxAggregateList as ID list only" {
        val entity = FxAudioPlaylistEntity(1, "My Playlist", initialAudioItemIds = listOf(10, 20))

        val encoded = json.encodeToString(serializer, entity)

        encoded shouldContain "\"id\": 1"
        encoded shouldContain "\"name\": \"My Playlist\""
        encoded shouldContain "\"audioItems\""
        encoded shouldContain "10"
        encoded shouldContain "20"
        encoded shouldNotContain "\"innerProxy\""
        encoded shouldNotContain "\"localElements\""
    }

    "serializes entity with fxAggregateSet as ID set only" {
        val entity = FxAudioPlaylistEntity(1, "Parent", initialPlaylistIds = setOf(2, 3))

        val encoded = json.encodeToString(serializer, entity)

        encoded shouldContain "\"playlists\""
        encoded shouldContain "2"
        encoded shouldContain "3"
        encoded shouldNotContain "\"innerProxy\""
    }

    "serializes entity with empty fx collections" {
        val entity = FxAudioPlaylistEntity(1, "Empty")

        val encoded = json.encodeToString(serializer, entity)

        encoded shouldContain "\"id\": 1"
        encoded shouldContain "\"name\": \"Empty\""
        encoded shouldContain "\"audioItems\": []"
        encoded shouldContain "\"playlists\": []"
    }

    "deserializes entity preserving fxAggregateList IDs and collection facade" {
        val original = FxAudioPlaylistEntity(5, "Round Trip", initialAudioItemIds = listOf(10, 20, 30))

        val encoded = json.encodeToString(serializer, original)
        val decoded = json.decodeFromString(serializer, encoded)

        decoded.id shouldBe 5
        decoded.name shouldBe "Round Trip"
        decoded.audioItems.referenceIds shouldBe listOf(10, 20, 30)
        decoded.audioItems.shouldBeInstanceOf<ObservableList<*>>()
        decoded.audioItems.shouldBeInstanceOf<FxAggregateList<*, *>>()
    }

    "deserializes entity preserving fxAggregateSet IDs and collection facade" {
        val original = FxAudioPlaylistEntity(1, "With Sets", initialPlaylistIds = setOf(2, 3))

        val encoded = json.encodeToString(serializer, original)
        val decoded = json.decodeFromString(serializer, encoded)

        decoded.id shouldBe 1
        decoded.name shouldBe "With Sets"
        decoded.playlists.referenceIds shouldBe setOf(2, 3)
        decoded.playlists.shouldBeInstanceOf<ObservableSet<*>>()
        decoded.playlists.shouldBeInstanceOf<FxAggregateSet<*, *>>()
    }

    "round-trip preserves list order" {
        val original = FxAudioPlaylistEntity(1, "Ordered", initialAudioItemIds = listOf(30, 10, 20))

        val encoded = json.encodeToString(serializer, original)
        val decoded = json.decodeFromString(serializer, encoded)

        decoded.audioItems.referenceIds shouldBe listOf(30, 10, 20)
    }

    "round-trip after mutation preserves updated state" {
        val entity = FxAudioPlaylistEntity(1, "Mutable", initialAudioItemIds = listOf(10))
        entity.name = "Updated Name"

        val encoded = json.encodeToString(serializer, entity)
        val decoded = json.decodeFromString(serializer, encoded)

        decoded.name shouldBe "Updated Name"
        decoded.audioItems.referenceIds shouldBe listOf(10)
    }

    "round-trip with both collections populated and facade intact" {
        val original = FxAudioPlaylistEntity(1, "Full", initialAudioItemIds = listOf(10, 20), initialPlaylistIds = setOf(2, 3))

        val encoded = json.encodeToString(serializer, original)
        val decoded = json.decodeFromString(serializer, encoded)

        decoded.id shouldBe 1
        decoded.name shouldBe "Full"
        decoded.audioItems.referenceIds shouldBe listOf(10, 20)
        decoded.audioItems.shouldBeInstanceOf<FxAggregateList<*, *>>()
        decoded.playlists.referenceIds shouldBe setOf(2, 3)
        decoded.playlists.shouldBeInstanceOf<FxAggregateSet<*, *>>()
    }

    "serializes fx scalar delegate values in JSON" {
        val entity =
            FxAudioPlaylistEntity(
                1, "Scalars", initialYear = 2025, initialActive = true,
                initialRating = 4.5, initialTag = "rock", initialDescription = "Best of"
            )

        val encoded = json.encodeToString(serializer, entity)

        encoded shouldContain "\"tagProperty\": \"rock\""
        encoded shouldContain "\"yearProperty\": 2025"
        encoded shouldContain "\"activeProperty\": true"
        encoded shouldContain "\"ratingProperty\": 4.5"
        encoded shouldContain "\"descriptionProperty\": \"Best of\""
        encoded shouldContain "\"name\": \"Scalars\""
    }

    "round-trip preserves fx scalar delegate values" {
        val original =
            FxAudioPlaylistEntity(
                1, "Complete", initialYear = 2024, initialActive = true,
                initialRating = 9.5, initialTag = "jazz", initialDescription = "Classic",
                initialAudioItemIds = listOf(10, 20), initialPlaylistIds = setOf(2)
            )

        val encoded = json.encodeToString(serializer, original)
        val decoded = json.decodeFromString(serializer, encoded)

        decoded.id shouldBe 1
        decoded.name shouldBe "Complete"
        decoded.tagProperty.get() shouldBe "jazz"
        decoded.yearProperty.get() shouldBe 2024
        decoded.activeProperty.get() shouldBe true
        decoded.ratingProperty.get() shouldBe 9.5
        decoded.descriptionProperty.get() shouldBe "Classic"
        decoded.audioItems.referenceIds shouldBe listOf(10, 20)
        decoded.playlists.referenceIds shouldBe setOf(2)
    }

    "round-trip preserves null fx object property" {
        val original = FxAudioPlaylistEntity(1, "NullDesc", initialDescription = null)

        val encoded = json.encodeToString(serializer, original)
        val decoded = json.decodeFromString(serializer, encoded)

        decoded.descriptionProperty.get() shouldBe null
    }
})