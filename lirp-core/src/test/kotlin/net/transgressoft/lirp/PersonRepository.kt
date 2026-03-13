package net.transgressoft.lirp

import net.transgressoft.lirp.entity.ReactiveEntity
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.event.EventType
import net.transgressoft.lirp.event.TransEvent
import net.transgressoft.lirp.persistence.json.JsonFileRepository
import net.transgressoft.lirp.persistence.json.JsonRepository
import net.transgressoft.lirp.persistence.json.TransEntityPolymorphicSerializer
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.positiveInt
import io.kotest.property.arbitrary.stringPattern
import java.io.File
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

interface Human<H: Human<H>>: ReactiveEntity<Int, H> {

    override val id: Int
    var name: String?
    val money: Long?
}

interface Personly: Human<Personly> {

    val morals: Boolean
}

@Serializable
@SerialName("Person")
data class Person(
    override val id: Int,
    @SerialName("name") private var initialName: String? = null,
    override var money: Long?,
    override val morals: Boolean
): Personly, ReactiveEntityBase<Int, Personly>() {

    @Transient
    override var name: String? = initialName
        get() = initialName
        set(value) {
            mutateAndPublish(value, field) { initialName = value }
        }

    override val uniqueId: String
        get() = "$id-$name-$money-$morals"

    override fun clone(): Person = copy()

    override fun toString(): String = "Person(id=$id, name=$name, money=$money, morals=$morals)"
}

sealed class PersonEventType {
    enum class Type(override val code: Int): EventType {
        BORN(201)
    }

    private data class Born<T: Personly>(val genre: Boolean): TransEvent<Type> {

        override val type: PersonEventType.Type = Type.BORN
    }
}

private val defaultId = -1
private val defaultMoney = -1L

fun arbitraryPerson(id: Int = defaultId, money: Long = defaultMoney) =
    arbitrary {
        Person(
            id = if (id == defaultId) Arb.positiveInt(500_000).bind() else id,
            initialName = Arb.stringPattern("[a-z]{5} [a-z]{5}").bind(),
            money = if (money == defaultMoney) Arb.Companion.long(0, Long.MAX_VALUE - 1).bind() else money,
            morals = Arb.boolean().bind()
        )
    }

abstract class HumanGenericJsonFileRepositoryBase<H: Human<H>>(private val repository: JsonFileRepository<Int, H>): JsonRepository<Int, H> by repository {

    override fun hashCode(): Int = repository.hashCode()

    override fun equals(other: Any?): Boolean =
        if (this === other) true
        else if (other is HumanGenericJsonFileRepositoryBase<*>) repository == other.repository
        else false
}

class PersonJsonFileRepository(file: File): HumanGenericJsonFileRepositoryBase<Personly>(
    JsonFileRepository(
        file,
        MapSerializer(Int.serializer(), PersonlySerializer()),
        SerializersModule {
            polymorphic(Human::class) {
                subclass(Person::class, Person.serializer())
            }
        }
    )
) {

    override fun hashCode(): Int = super.hashCode()

    override fun equals(other: Any?): Boolean = super.equals(other)
}

class PersonlySerializer: HumanSerializer<Personly>() {

    override fun additionalElements(classSerialDescriptorBuilder: ClassSerialDescriptorBuilder) {
        classSerialDescriptorBuilder.element<Boolean>("morals")
    }

    override fun additionalSerialize(compositeEncoder: CompositeEncoder, value: Personly) {
        compositeEncoder.encodeBooleanElement(descriptor, 4, value.morals)
    }

    override fun additionalDeserialize(compositeDecoder: CompositeDecoder, propertiesList: MutableList<Any?>, index: Int) {
        if (index == 4) {
            propertiesList.add(compositeDecoder.decodeBooleanElement(descriptor, 4))
        }
    }

    override fun createInstance(propertiesList: List<Any?>): Personly =
        Person(propertiesList[0] as Int, propertiesList[1] as String?, propertiesList[2] as Long?, propertiesList[3] as Boolean)
}

abstract class HumanSerializer<H : Human<H>> : TransEntityPolymorphicSerializer<H> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("Human") {
            element<String>("type")
            element<Int>("id")
            element<String?>("name")
            element<Long?>("money")
            additionalElements(this)
        }

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: H) {
        val compositeEncoder = encoder.beginStructure(descriptor)
        compositeEncoder.encodeStringElement(descriptor, 0, value::class.simpleName ?: "Unknown")
        compositeEncoder.encodeIntElement(descriptor, 1, value.id)
        compositeEncoder.encodeNullableSerializableElement(descriptor, 2, String.serializer().nullable, value.name)
        compositeEncoder.encodeNullableSerializableElement(descriptor, 3, Long.serializer().nullable, value.money)
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
                3 -> propertiesList.add(compositeDecoder.decodeNullableSerializableElement(descriptor, index, Long.serializer().nullable))
                4 -> propertiesList.add(compositeDecoder.decodeBooleanElement(descriptor, index))
                else -> propertiesList.add(additionalDeserialize(compositeDecoder, propertiesList, index))
            }
        }
        compositeDecoder.endStructure(descriptor)
        return propertiesList
    }
}