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

import net.transgressoft.lirp.entity.ReactiveEntity
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.persistence.LirpRepository
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.positiveInt
import io.kotest.property.arbitrary.stringPattern
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.ClassSerialDescriptorBuilder
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Base interface for the polymorphic customer type hierarchy used in JSON persistence tests.
 *
 * Serves as the sealed base for both [StandardCustomer] and [PremiumCustomer] concrete types,
 * enabling polymorphic serialization via [StandardCustomerJsonFileRepository] and [PremiumCustomerJsonFileRepository].
 */
interface PolymorphicCustomer : ReactiveEntity<Int, PolymorphicCustomer> {

    override val id: Int
    var name: String?
    val email: String?
}

/**
 * Sub-interface extending [PolymorphicCustomer] with a loyalty program contract.
 *
 * Implemented by [PremiumCustomer], demonstrating the interface-subclassing pattern in the
 * polymorphic hierarchy alongside the [StandardCustomer] class-extending pattern.
 */
interface PremiumCustomerContract : PolymorphicCustomer {

    var loyaltyPoints: Int
}

/**
 * Concrete implementation of [PolymorphicCustomer] for standard (non-premium) customers.
 *
 * The [name] property uses a `@Transient` backed-field delegate pattern to emit events
 * while keeping [initialName] as the serialized field name.
 */
@Serializable
@SerialName("StandardCustomer")
data class StandardCustomer(
    override val id: Int,
    @SerialName("name") private var initialName: String? = null,
    override val email: String? = null
) : PolymorphicCustomer, ReactiveEntityBase<Int, PolymorphicCustomer>() {

    @Transient
    override var name: String? by reactiveProperty({ initialName }, { initialName = it })

    override val uniqueId: String get() = "standard-customer-$id"

    override fun clone(): StandardCustomer = copy()

    fun updateName(newName: String) {
        name = newName
    }
}

/**
 * Concrete implementation of [PremiumCustomerContract] for premium customers with loyalty points.
 *
 * Extends [PremiumCustomerContract] while using [ReactiveEntityBase] parameterised on
 * [PolymorphicCustomer], demonstrating the class-extending pattern in the polymorphic hierarchy.
 */
@Serializable
@SerialName("PremiumCustomer")
data class PremiumCustomer(
    override val id: Int,
    @SerialName("name") private var initialName: String? = null,
    override val email: String? = null,
    @SerialName("loyaltyPoints") private var _loyaltyPoints: Int = 0
) : PremiumCustomerContract, ReactiveEntityBase<Int, PolymorphicCustomer>() {

    @Transient
    override var name: String? by reactiveProperty({ initialName }, { initialName = it })

    @Transient
    override var loyaltyPoints: Int by reactiveProperty({ _loyaltyPoints }, { _loyaltyPoints = it })

    override val uniqueId: String get() = "premium-customer-$id"

    override fun clone(): PremiumCustomer = copy()

    fun updateName(newName: String) {
        name = newName
    }

    fun updateLoyaltyPoints(newPoints: Int) {
        loyaltyPoints = newPoints
    }
}

/**
 * Abstract base serializer for [PolymorphicCustomer] subtypes.
 *
 * Provides the shared descriptor with `type`, `id`, `name`, and `email` fields,
 * plus hooks for subtype-specific additional elements. Follows the same pattern as
 * [net.transgressoft.lirp.HumanSerializer] in the legacy test fixtures.
 */
abstract class PolymorphicCustomerSerializer<C : PolymorphicCustomer> : LirpEntityPolymorphicSerializer<C> {

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("PolymorphicCustomer") {
            element<String>("type")
            element<Int>("id")
            element<String?>("name")
            element<String?>("email")
            additionalElements(this)
        }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: C) {
        val compositeEncoder = encoder.beginStructure(descriptor)
        compositeEncoder.encodeStringElement(descriptor, 0, value::class.simpleName ?: "Unknown")
        compositeEncoder.encodeIntElement(descriptor, 1, value.id)
        compositeEncoder.encodeNullableSerializableElement(descriptor, 2, String.serializer().nullable, value.name)
        compositeEncoder.encodeNullableSerializableElement(descriptor, 3, String.serializer().nullable, value.email)
        additionalSerialize(compositeEncoder, value)
        compositeEncoder.endStructure(descriptor)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun getPropertiesList(decoder: Decoder): List<Any?> {
        val compositeDecoder = decoder.beginStructure(descriptor)
        val propertiesList: MutableList<Any?> = mutableListOf()

        loop@ while (true) {
            when (val index = compositeDecoder.decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break@loop
                0 -> compositeDecoder.decodeStringElement(descriptor, index)
                1 -> propertiesList.add(compositeDecoder.decodeIntElement(descriptor, index))
                2 -> propertiesList.add(compositeDecoder.decodeNullableSerializableElement(descriptor, index, String.serializer().nullable))
                3 -> propertiesList.add(compositeDecoder.decodeNullableSerializableElement(descriptor, index, String.serializer().nullable))
                else -> additionalDeserialize(compositeDecoder, index)?.let { propertiesList.add(it) }
            }
        }
        compositeDecoder.endStructure(descriptor)
        return propertiesList
    }
}

/**
 * Serializer for [StandardCustomer] (no additional fields beyond the base descriptor).
 */
class StandardCustomerSerializer : PolymorphicCustomerSerializer<PolymorphicCustomer>() {

    override fun createInstance(propertiesList: List<Any?>): PolymorphicCustomer =
        StandardCustomer(propertiesList[0] as Int, propertiesList[1] as String?, propertiesList[2] as String?)
}

/**
 * Serializer for [PremiumCustomer], extending the base descriptor with a `loyaltyPoints` field.
 */
class PremiumCustomerSerializer : PolymorphicCustomerSerializer<PolymorphicCustomer>() {

    override fun additionalElements(classSerialDescriptorBuilder: ClassSerialDescriptorBuilder) {
        classSerialDescriptorBuilder.element<Int>("loyaltyPoints")
    }

    override fun additionalSerialize(compositeEncoder: CompositeEncoder, value: PolymorphicCustomer) {
        if (value is PremiumCustomer) {
            compositeEncoder.encodeIntElement(descriptor, 4, value.loyaltyPoints)
        }
    }

    override fun additionalDeserialize(compositeDecoder: CompositeDecoder, index: Int): Any? =
        if (index == 4) compositeDecoder.decodeIntElement(descriptor, index) else null

    override fun createInstance(propertiesList: List<Any?>): PolymorphicCustomer =
        PremiumCustomer(propertiesList[0] as Int, propertiesList[1] as String?, propertiesList[2] as String?, propertiesList[3] as Int)
}

/**
 * Dispatch serializer for [PolymorphicCustomer] that routes to [StandardCustomerSerializer] or
 * [PremiumCustomerSerializer] based on the `type` discriminator field in the JSON.
 *
 * Uses a unified descriptor that includes the `loyaltyPoints` field from [PremiumCustomer]
 * as an optional extra element (index 4), serializing and deserializing both subtypes correctly.
 */
class PolymorphicCustomerDispatchSerializer : LirpEntityPolymorphicSerializer<PolymorphicCustomer> {

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("PolymorphicCustomer") {
            element<String>("type")
            element<Int>("id")
            element<String?>("name")
            element<String?>("email")
            element<Int>("loyaltyPoints", isOptional = true)
        }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: PolymorphicCustomer) {
        val compositeEncoder = encoder.beginStructure(descriptor)
        compositeEncoder.encodeStringElement(descriptor, 0, value::class.simpleName ?: "Unknown")
        compositeEncoder.encodeIntElement(descriptor, 1, value.id)
        compositeEncoder.encodeNullableSerializableElement(descriptor, 2, String.serializer().nullable, value.name)
        compositeEncoder.encodeNullableSerializableElement(descriptor, 3, String.serializer().nullable, value.email)
        if (value is PremiumCustomer) {
            compositeEncoder.encodeIntElement(descriptor, 4, value.loyaltyPoints)
        }
        compositeEncoder.endStructure(descriptor)
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun getPropertiesList(decoder: Decoder): List<Any?> {
        val compositeDecoder = decoder.beginStructure(descriptor)
        val propertiesList: MutableList<Any?> = mutableListOf(null, null, null, null, null)

        loop@ while (true) {
            when (val index = compositeDecoder.decodeElementIndex(descriptor)) {
                CompositeDecoder.DECODE_DONE -> break@loop
                0 -> propertiesList[0] = compositeDecoder.decodeStringElement(descriptor, index)
                1 -> propertiesList[1] = compositeDecoder.decodeIntElement(descriptor, index)
                2 -> propertiesList[2] = compositeDecoder.decodeNullableSerializableElement(descriptor, index, String.serializer().nullable)
                3 -> propertiesList[3] = compositeDecoder.decodeNullableSerializableElement(descriptor, index, String.serializer().nullable)
                4 -> propertiesList[4] = compositeDecoder.decodeIntElement(descriptor, index)
            }
        }
        compositeDecoder.endStructure(descriptor)
        return propertiesList
    }

    override fun createInstance(propertiesList: List<Any?>): PolymorphicCustomer {
        val type = propertiesList[0] as? String ?: "StandardCustomer"
        val id = propertiesList[1] as Int
        val name = propertiesList[2] as String?
        val email = propertiesList[3] as String?
        return when (type) {
            "PremiumCustomer" -> PremiumCustomer(id, name, email, (propertiesList[4] as? Int) ?: 0)
            else -> StandardCustomer(id, name, email)
        }
    }
}

private val customerSerializersModule =
    SerializersModule {
        polymorphic(PolymorphicCustomer::class) {
            subclass(StandardCustomer::class, StandardCustomer.serializer())
            subclass(PremiumCustomer::class, PremiumCustomer.serializer())
        }
    }

private val customerMapSerializer = MapSerializer(Int.serializer(), PolymorphicCustomerDispatchSerializer())

/**
 * Test JSON repository for [StandardCustomer] entities with polymorphic serialization support.
 *
 * Annotated with [@LirpRepository][LirpRepository] so the KSP processor generates
 * auto-registration code for the provided [LirpContext].
 */
@LirpRepository
class StandardCustomerJsonFileRepository internal constructor(
    context: LirpContext,
    file: File,
    serializationDelayMs: Long = 300L
) : JsonFileRepository<Int, PolymorphicCustomer>(
        context,
        file,
        customerMapSerializer,
        customerSerializersModule,
        serializationDelay = serializationDelayMs.milliseconds
    ) {
    constructor(file: File, serializationDelayMs: Long = 300L) : this(LirpContext.default, file, serializationDelayMs)

    /** Creates and adds a [StandardCustomer] with the given properties. */
    fun create(id: Int, name: String?, email: String?): StandardCustomer =
        StandardCustomer(id, name, email).also { add(it) }
}

/**
 * Test JSON repository for [PremiumCustomer] entities with polymorphic serialization support.
 *
 * Annotated with [@LirpRepository][LirpRepository] so the KSP processor generates
 * auto-registration code for the provided [LirpContext].
 */
@LirpRepository
class PremiumCustomerJsonFileRepository internal constructor(
    context: LirpContext,
    file: File,
    serializationDelayMs: Long = 300L
) : JsonFileRepository<Int, PolymorphicCustomer>(
        context,
        file,
        customerMapSerializer,
        customerSerializersModule,
        serializationDelay = serializationDelayMs.milliseconds
    ) {
    constructor(file: File, serializationDelayMs: Long = 300L) : this(LirpContext.default, file, serializationDelayMs)

    /** Creates and adds a [PremiumCustomer] with the given properties. */
    fun create(id: Int, name: String?, email: String?): PremiumCustomer =
        PremiumCustomer(id, name, email).also { add(it) }

    /** Creates and adds a [PremiumCustomer] with the given properties and loyalty points. */
    fun create(id: Int, name: String?, email: String?, loyaltyPoints: Int): PremiumCustomer =
        PremiumCustomer(id, name, email, loyaltyPoints).also { add(it) }
}

private val defaultCustomerId = -1

/**
 * Kotest [Arb] generator for [StandardCustomer] instances with random name and email.
 *
 * @param id Fixed entity ID, or `-1` to generate a random positive ID.
 */
fun arbitraryStandardCustomer(id: Int = defaultCustomerId) =
    arbitrary {
        StandardCustomer(
            id = if (id == defaultCustomerId) Arb.positiveInt(500_000).bind() else id,
            initialName = Arb.stringPattern("[a-z]{5} [a-z]{5}").bind(),
            email = Arb.stringPattern("[a-z]{5}@[a-z]{4}\\.[a-z]{3}").bind()
        )
    }

/**
 * Kotest [Arb] generator for [PremiumCustomer] instances with random name, email, and loyalty points.
 *
 * @param id Fixed entity ID, or `-1` to generate a random positive ID.
 */
fun arbitraryPremiumCustomer(id: Int = defaultCustomerId) =
    arbitrary {
        PremiumCustomer(
            id = if (id == defaultCustomerId) Arb.positiveInt(500_000).bind() else id,
            initialName = Arb.stringPattern("[a-z]{5} [a-z]{5}").bind(),
            email = Arb.stringPattern("[a-z]{5}@[a-z]{4}\\.[a-z]{3}").bind(),
            _loyaltyPoints = Arb.int(0, 100_000).bind()
        )
    }