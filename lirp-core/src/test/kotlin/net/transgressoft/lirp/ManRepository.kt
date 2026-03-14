package net.transgressoft.lirp

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.event.FlowEventPublisher
import net.transgressoft.lirp.persistence.json.JsonFileRepository
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.ClassSerialDescriptorBuilder
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

interface Manly : Human<Manly> {
    val beard: Boolean
}

@Serializable
@SerialName("Man")
data class Man(
    override val id: Int,
    override var name: String?,
    override val money: Long?,
    override val beard: Boolean = false
) : Manly, ReactiveEntityBase<Int, Manly>({ _ -> FlowEventPublisher("$id", closeOnEmpty = true) }) {
    override val uniqueId: String = "$id-$name-$money"

    override fun clone(): Man = copy()
}

open class ManJsonFileRepository(file: File) :
    HumanGenericJsonFileRepositoryBase<Manly>(
        JsonFileRepository(
            file,
            MapSerializer(Int.serializer(), ManlySerializer()),
            SerializersModule {
                polymorphic(Human::class) {
                    subclass(Person::class, Person.serializer())
                }
            }
        )
    )

class ManlySerializer : HumanSerializer<Manly>() {
    override fun additionalElements(classSerialDescriptorBuilder: ClassSerialDescriptorBuilder) {
        classSerialDescriptorBuilder.element<Boolean>("beard")
    }

    override fun additionalSerialize(compositeEncoder: CompositeEncoder, value: Manly) {
        compositeEncoder.encodeBooleanElement(descriptor, 4, value.beard)
    }

    override fun additionalDeserialize(compositeDecoder: CompositeDecoder, index: Int): Any? =
        if (index == 4) compositeDecoder.decodeBooleanElement(descriptor, 4) else null

    override fun createInstance(propertiesList: List<Any?>): Manly =
        Man(propertiesList[0] as Int, propertiesList[1] as String?, propertiesList[2] as Long?, propertiesList[3] as Boolean)
}