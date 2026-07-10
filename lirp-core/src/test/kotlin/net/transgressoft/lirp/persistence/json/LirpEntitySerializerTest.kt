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
import net.transgressoft.lirp.persistence.AbstractMutableAggregateCollectionRefDelegate
import net.transgressoft.lirp.persistence.AudioItem
import net.transgressoft.lirp.persistence.DefaultAudioPlaylist
import net.transgressoft.lirp.persistence.FxScalarPropertyDelegate
import net.transgressoft.lirp.persistence.LirpDelegate
import net.transgressoft.lirp.persistence.MutableAggregateList
import net.transgressoft.lirp.persistence.MutableAggregateSet
import net.transgressoft.lirp.persistence.mutableAggregateList
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.encoding.decodeStructure
import kotlinx.serialization.encoding.encodeStructure
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual

// --- Fixture entities for LirpEntitySerializer tests ---

/**
 * Minimal entity with a single reactive property — proves no @Serializable or backing field needed.
 */
class SimpleDelegate(override val id: Int) : ReactiveEntityBase<Int, SimpleDelegate>() {
    var name by reactiveProperty("default")
    override val uniqueId: String get() = id.toString()

    override fun clone(): SimpleDelegate =
        SimpleDelegate(id).also {
            it.withEventsDisabled { it.name = name }
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SimpleDelegate) return false
        return id == other.id && name == other.name
    }

    override fun hashCode(): Int = 31 * id + name.hashCode()
}

/**
 * Entity with a nullable reactive property — proves nullable KSER-01 variant.
 */
class NullableDelegate(override val id: Int) : ReactiveEntityBase<Int, NullableDelegate>() {
    var name by reactiveProperty<String?>(null)
    override val uniqueId: String get() = id.toString()

    override fun clone(): NullableDelegate =
        NullableDelegate(id).also {
            it.withEventsDisabled { it.name = name }
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NullableDelegate) return false
        return id == other.id && name == other.name
    }

    override fun hashCode(): Int = 31 * id + (name?.hashCode() ?: 0)
}

/**
 * Entity with a mutable aggregate collection delegate — proves KSER-02: backing IDs serialized under
 * the delegate property name without requiring a corresponding constructor field.
 */
private class DelegateWithCollection(override val id: Int) : ReactiveEntityBase<Int, DelegateWithCollection>() {
    val tracks by mutableAggregateList<Int, AudioItem>()
    override val uniqueId: String get() = id.toString()

    // Clone does not copy tracks — serialization tests don't use mutation events.
    // In production entities, pass tracks.referenceIds.toList() through the constructor.
    override fun clone(): DelegateWithCollection = DelegateWithCollection(id)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DelegateWithCollection) return false
        return id == other.id && tracks.referenceIds == other.tracks.referenceIds
    }

    override fun hashCode(): Int = 31 * id + tracks.referenceIds.hashCode()
}

/**
 * Entity combining a constructor param, a reactive property, and an aggregate delegate — tests combined round-trip.
 */
class CombinedDelegate(override val id: Int) : ReactiveEntityBase<Int, CombinedDelegate>() {
    var name by reactiveProperty("combined")
    val tracks by mutableAggregateList<Int, AudioItem>()
    override val uniqueId: String get() = id.toString()

    override fun clone(): CombinedDelegate =
        CombinedDelegate(id).also {
            it.withEventsDisabled { it.name = name }
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CombinedDelegate) return false
        return id == other.id && name == other.name && tracks.referenceIds == other.tracks.referenceIds
    }

    override fun hashCode(): Int = 31 * (31 * id + name.hashCode()) + tracks.referenceIds.hashCode()
}

/**
 * A plain value type that is deliberately NOT `@Serializable`, modeling a domain type a consumer owns
 * but chooses not to annotate (or cannot, e.g. a third-party type).
 */
class Coordinate(val latitude: Double, val longitude: Double) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is Coordinate && latitude == other.latitude && longitude == other.longitude)

    override fun hashCode(): Int = 31 * latitude.hashCode() + longitude.hashCode()
}

/**
 * Hand-written contextual serializer for the non-`@Serializable` [Coordinate], registered in a
 * [SerializersModule] and resolved by [LirpEntitySerializer] without annotating the domain type.
 */
object CoordinateSerializer : KSerializer<Coordinate> {
    override val descriptor =
        buildClassSerialDescriptor("Coordinate") {
            element<Double>("latitude")
            element<Double>("longitude")
        }

    override fun serialize(encoder: Encoder, value: Coordinate) {
        encoder.encodeStructure(descriptor) {
            encodeDoubleElement(descriptor, 0, value.latitude)
            encodeDoubleElement(descriptor, 1, value.longitude)
        }
    }

    override fun deserialize(decoder: Decoder): Coordinate =
        decoder.decodeStructure(descriptor) {
            var latitude = 0.0
            var longitude = 0.0
            while (true) {
                when (val index = decodeElementIndex(descriptor)) {
                    0 -> latitude = decodeDoubleElement(descriptor, 0)
                    1 -> longitude = decodeDoubleElement(descriptor, 1)
                    CompositeDecoder.DECODE_DONE -> break
                    else -> error("Unexpected element index $index")
                }
            }
            Coordinate(latitude, longitude)
        }
}

/** Entity with a constructor parameter whose type is the non-`@Serializable` [Coordinate]. */
class OriginEntity(override val id: Int, val origin: Coordinate) : ReactiveEntityBase<Int, OriginEntity>() {
    override val uniqueId: String get() = id.toString()

    override fun clone(): OriginEntity = OriginEntity(id, origin)

    override fun equals(other: Any?): Boolean =
        this === other || (other is OriginEntity && id == other.id && origin == other.origin)

    override fun hashCode(): Int = 31 * id + origin.hashCode()
}

/**
 * A `private` entity whose reactive property holds the non-`@Serializable` [Coordinate]. The KSP
 * structural processors skip private classes, so no `_LirpReactivePropertyAccessor` is generated and
 * the reflection-based reactive-property fallback resolves the field serializer through the supplied
 * module — exercising the contextual path on a site distinct from [OriginEntity]'s constructor
 * parameter. (Were KSP to process this class, its codegen could not resolve a serializer for the
 * non-`@Serializable` field, so its absence is what keeps the fallback under test.)
 */
private class WaypointEntity(override val id: Int) : ReactiveEntityBase<Int, WaypointEntity>() {
    var waypoint: Coordinate by reactiveProperty(Coordinate(0.0, 0.0))
    override val uniqueId: String get() = id.toString()

    override fun clone(): WaypointEntity =
        WaypointEntity(id).also { copy -> copy.withEventsDisabled { copy.waypoint = waypoint } }

    override fun equals(other: Any?): Boolean =
        this === other || (other is WaypointEntity && id == other.id && waypoint == other.waypoint)

    override fun hashCode(): Int = 31 * id + waypoint.hashCode()
}

/**
 * Tests for [LirpEntitySerializer] covering reactive property (KSER-01) and aggregate delegate (KSER-02)
 * serialization scenarios.
 */
class LirpEntitySerializerTest : StringSpec({
    val json = Json { encodeDefaults = true }

    "reactive property entity serializes with property name as JSON field" {
        val entity = SimpleDelegate(1).apply { name = "Alice" }
        val serializer = lirpSerializer(entity)
        val jsonStr = json.encodeToString(serializer, entity)
        jsonStr shouldContain "\"id\""
        jsonStr shouldContain "\"name\""
        jsonStr shouldContain "Alice"
    }

    "reactive property entity round-trips through JSON" {
        val original = SimpleDelegate(42).apply { name = "Bob" }
        val serializer = lirpSerializer(original)
        val jsonStr = json.encodeToString(serializer, original)
        val decoded = json.decodeFromString(serializer, jsonStr)
        decoded.id shouldBe 42
        decoded.name shouldBe "Bob"
    }

    "nullable reactive property serializes null correctly" {
        val entity = NullableDelegate(7)
        val serializer = lirpSerializer(entity)
        val jsonStr =
            Json {
                encodeDefaults = true
                explicitNulls = true
            }.encodeToString(serializer, entity)
        jsonStr shouldContain "\"name\""
        jsonStr shouldContain "null"
    }

    "nullable reactive property round-trips non-null value through JSON" {
        val entity = NullableDelegate(8).apply { name = "Carol" }
        val serializer = lirpSerializer(entity)
        val jsonStr = json.encodeToString(serializer, entity)
        val decoded = json.decodeFromString(serializer, jsonStr)
        decoded.id shouldBe 8
        decoded.name shouldBe "Carol"
    }

    "aggregate delegate entity serializes backing IDs under property name" {
        val entity = DelegateWithCollection(5)
        entity.setDelegateIds("tracks", listOf(10, 20, 30))
        val serializer = lirpSerializer(entity)
        val jsonStr = json.encodeToString(serializer, entity)
        jsonStr shouldContain "\"tracks\""
        jsonStr shouldContain "10"
        jsonStr shouldContain "20"
        jsonStr shouldContain "30"
    }

    "aggregate delegate entity round-trips through JSON" {
        val original = DelegateWithCollection(3)
        original.setDelegateIds("tracks", listOf(1, 2, 3))
        val serializer = lirpSerializer(original)
        val jsonStr = json.encodeToString(serializer, original)
        val decoded = json.decodeFromString(serializer, jsonStr)
        decoded.id shouldBe 3
        decoded.tracks.referenceIds.toList() shouldBe listOf(1, 2, 3)
    }

    "descriptor contains correct element names and count" {
        val entity = CombinedDelegate(1)
        val serializer = lirpSerializer(entity)
        val descriptor = serializer.descriptor
        // id (constructor param), name (reactive prop), tracks (aggregate delegate)
        descriptor.elementsCount shouldBe 3
        val names = (0 until descriptor.elementsCount).map { descriptor.getElementName(it) }
        names shouldContainExactly listOf("id", "name", "tracks")
    }

    "combined entity with constructor param, reactive property, and aggregate delegate round-trips" {
        val original = CombinedDelegate(99).apply { name = "TestName" }
        original.setDelegateIds("tracks", listOf(5, 6))
        val serializer = lirpSerializer(original)
        val jsonStr = json.encodeToString(serializer, original)
        val decoded = json.decodeFromString(serializer, jsonStr)
        decoded.id shouldBe 99
        decoded.name shouldBe "TestName"
        decoded.tracks.referenceIds.toList() shouldBe listOf(5, 6)
    }

    "serializer does not include unrelated entity fields" {
        val entity = SimpleDelegate(1).apply { name = "Alice" }
        val serializer = lirpSerializer(entity)
        val jsonStr = json.encodeToString(serializer, entity)
        jsonStr shouldNotContain "lastDateModified"
        jsonStr shouldNotContain "isClosed"
    }

    "MapSerializer with LirpEntitySerializer round-trips a map of entities" {
        val sample = SimpleDelegate(0)
        val mapSerializer = MapSerializer(Int.serializer(), lirpSerializer(sample))
        val entities =
            mapOf(
                1 to SimpleDelegate(1).apply { name = "Alice" },
                2 to SimpleDelegate(2).apply { name = "Bob" }
            )
        val jsonStr = json.encodeToString(mapSerializer, entities)
        val decoded = json.decodeFromString(mapSerializer, jsonStr)
        decoded[1]?.name shouldBe "Alice"
        decoded[2]?.name shouldBe "Bob"
    }

    "LirpEntitySerializer init fails when FxScalar delegate lacks expected get method" {
        val entity = SimpleDelegate(1)
        val brokenFxDelegate = BrokenFxScalarDelegate()
        injectDelegate(entity, "broken", brokenFxDelegate)

        shouldThrow<IllegalStateException> {
            lirpSerializer(entity)
        }.message shouldContain "Expected exactly one 'get' method with 0 parameters"
    }

    "LirpEntitySerializer init fails with configure KSP message when reactive delegate has no generated accessor entry" {
        val entity = SimpleDelegate(1)
        val orphanDelegate = OrphanReactiveDelegate()
        injectDelegate(entity, "ghost", orphanDelegate)

        shouldThrow<IllegalStateException> {
            lirpSerializer(entity)
        }.message shouldContain "configure KSP"
    }

    "entity with a non-serializable field type fails to build a serializer without a contextual module" {
        shouldThrow<SerializationException> {
            lirpSerializer(OriginEntity(1, Coordinate(40.0, -3.0)))
        }
    }

    "entity with a non-serializable field type round-trips when its serializer is registered contextually" {
        val module = SerializersModule { contextual(Coordinate::class, CoordinateSerializer) }
        val original = OriginEntity(7, Coordinate(40.4168, -3.7038))
        val serializer = lirpSerializer(original, module)

        val jsonStr = json.encodeToString(serializer, original)
        jsonStr shouldContain "\"origin\""
        jsonStr shouldContain "40.4168"

        val decoded = json.decodeFromString(serializer, jsonStr)
        decoded.id shouldBe 7
        decoded.origin shouldBe Coordinate(40.4168, -3.7038)
    }

    "MapSerializer round-trips entities whose field is resolved by a contextual serializer" {
        val module = SerializersModule { contextual(Coordinate::class, CoordinateSerializer) }
        val mapSerializer = MapSerializer(Int.serializer(), lirpSerializer(OriginEntity(0, Coordinate(0.0, 0.0)), module))
        val entities =
            mapOf(
                1 to OriginEntity(1, Coordinate(1.0, 2.0)),
                2 to OriginEntity(2, Coordinate(3.0, 4.0))
            )
        val jsonStr = json.encodeToString(mapSerializer, entities)
        val decoded = json.decodeFromString(mapSerializer, jsonStr)
        decoded[1]?.origin shouldBe Coordinate(1.0, 2.0)
        decoded[2]?.origin shouldBe Coordinate(3.0, 4.0)
    }

    "reactive property of a non-serializable type round-trips via the reflection fallback when registered contextually" {
        val module = SerializersModule { contextual(Coordinate::class, CoordinateSerializer) }
        val original = WaypointEntity(3).apply { waypoint = Coordinate(51.5074, -0.1278) }
        val serializer = lirpSerializer(original, module)

        val jsonStr = json.encodeToString(serializer, original)
        jsonStr shouldContain "\"waypoint\""
        jsonStr shouldContain "51.5074"

        val decoded = json.decodeFromString(serializer, jsonStr)
        decoded.id shouldBe 3
        decoded.waypoint shouldBe Coordinate(51.5074, -0.1278)
    }

    "reactive property of a non-serializable type fails to build a serializer without a contextual module" {
        shouldThrow<SerializationException> {
            lirpSerializer(WaypointEntity(1))
        }
    }

    // Regression: #342 — empty aggregate collection must not crash serializer construction
    "LirpEntitySerializer does not throw when built from a DefaultAudioPlaylist sample with an empty audioItems collection" {
        // This is the first-run scenario: the sample entity has no items yet. Before the fix,
        // the declared-type fallback was only reached after the live-IDs check, making an empty
        // collection fail with "Could not determine aggregate ID type".
        val emptySample = DefaultAudioPlaylist(0, "")
        lirpSerializer(emptySample) // must not throw
    }

    // Regression: #338 — aggregate-ID serializer must be derived from the declared type, not the
    // runtime class of the first live ID, so a serializer built on one sample round-trips another.
    "LirpEntitySerializer built from a populated DefaultAudioPlaylist sample round-trips a second playlist's audioItem IDs" {
        val populatedSample = DefaultAudioPlaylist(1, "Sample")
        populatedSample.setDelegateIds("audioItems", listOf(10, 20))

        val serializer = lirpSerializer(populatedSample)

        // A second entity whose IDs were not present when the serializer was built must still
        // encode and decode without coercion or loss — proving resolution is from the declared
        // type (Int), not the runtime class of ID 10 or 20.
        val other = DefaultAudioPlaylist(2, "Other")
        other.setDelegateIds("audioItems", listOf(30, 40))

        val jsonStr = json.encodeToString(serializer, other)
        val decoded = json.decodeFromString(serializer, jsonStr)
        decoded.id shouldBe 2
        decoded.audioItems.referenceIds.toList() shouldBe listOf(30, 40)
    }
})

/**
 * FxScalarPropertyDelegate without get()/set() — triggers requireMethod error during serializer init.
 */
private class BrokenFxScalarDelegate : FxScalarPropertyDelegate, LirpDelegate {
    override fun bindMutationCallback(callback: (Any?, Any?, () -> Unit) -> Unit) {}
}

/**
 * A non-FxScalar, non-aggregate LirpDelegate with no matching member property — triggers requireNotNull error.
 */
private class OrphanReactiveDelegate : LirpDelegate

/** Injects a fake delegate into an entity's delegate registry via reflection. */
private fun injectDelegate(entity: ReactiveEntityBase<*, *>, name: String, delegate: LirpDelegate) {
    // Force the registry to be built first
    entity.delegateRegistry
    // Replace the cached _delegateRegistry with a copy that includes the fake delegate
    val field = ReactiveEntityBase::class.java.getDeclaredField("_delegateRegistry")
    field.isAccessible = true
    @Suppress("UNCHECKED_CAST")
    val existing = field.get(entity) as Map<String, LirpDelegate>
    field.set(entity, existing + (name to delegate))
}

/** Test helper to set backing IDs on a named delegate via the entity's delegateRegistry. */
@Suppress("UNCHECKED_CAST")
private fun <K : Comparable<K>> ReactiveEntityBase<*, *>.setDelegateIds(delegateName: String, ids: List<K>) {
    val raw = delegateRegistry[delegateName]
    val delegate: AbstractMutableAggregateCollectionRefDelegate<K, *>? =
        when (raw) {
            is MutableAggregateList<*, *> -> raw.innerDelegate as AbstractMutableAggregateCollectionRefDelegate<K, *>
            is MutableAggregateSet<*, *> -> raw.innerDelegate as AbstractMutableAggregateCollectionRefDelegate<K, *>
            is AbstractMutableAggregateCollectionRefDelegate<*, *> -> raw as AbstractMutableAggregateCollectionRefDelegate<K, *>
            else -> null
        }
    delegate?.setBackingIds(ids) ?: error("No mutable aggregate delegate named '$delegateName'")
}