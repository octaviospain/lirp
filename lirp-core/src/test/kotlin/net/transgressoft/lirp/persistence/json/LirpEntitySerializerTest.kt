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
import net.transgressoft.lirp.persistence.MutableAggregateList
import net.transgressoft.lirp.persistence.MutableAggregateSet
import net.transgressoft.lirp.persistence.mutableAggregateList
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

// --- Fixture entities for LirpEntitySerializer tests ---

/**
 * Minimal entity with a single reactive property — proves no @Serializable or backing field needed.
 */
private class SimpleDelegate(override val id: Int) : ReactiveEntityBase<Int, SimpleDelegate>() {
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
private class NullableDelegate(override val id: Int) : ReactiveEntityBase<Int, NullableDelegate>() {
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
private class CombinedDelegate(override val id: Int) : ReactiveEntityBase<Int, CombinedDelegate>() {
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
})

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