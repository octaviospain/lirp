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

import net.transgressoft.lirp.entity.TransEntity
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.ClassSerialDescriptorBuilder
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Decoder

/**
 * A serializer interface that enables polymorphic serialization and deserialization
 * of [TransEntity] subclasses.
 *
 * This interface provides a framework for implementing custom serializers that can
 * handle polymorphic hierarchies of [TransEntity] objects. It abstracts away common
 * serialization logic while allowing customization through extension points.
 *
 * Implementation example:
 * ```
 * class MyEntitySerializer : TransEntityPolymorphicSerializer<MyEntity>() {
 *     override val descriptor: SerialDescriptor = ...
 *
 *     override fun additionalElements(classSerialDescriptorBuilder: ClassSerialDescriptorBuilder) {
 *         classSerialDescriptorBuilder.element<String>("myCustomProperty")
 *     }
 *
 *     override fun createInstance(propertiesList: List<Any?>): MyEntity =
 *         MyEntity(propertiesList[0] as Int, propertiesList[1] as String)
 * }
 * ```
 *
 * @param T The specific type of [TransEntity] this serializer handles
 */
interface TransEntityPolymorphicSerializer<T : TransEntity> : KSerializer<T> {
    /**
     * Default implementation of the [KSerializer.deserialize] method that uses
     * [getPropertiesList] and [createInstance] to construct the object.
     *
     * @param decoder The decoder to read data from
     * @return A new instance of T constructed from the decoded data
     */
    override fun deserialize(decoder: Decoder): T = createInstance(getPropertiesList(decoder))

    /**
     * Extension point to define additional elements in the serial descriptor.
     * Override this method to add custom properties specific to your entity type.
     *
     * @param classSerialDescriptorBuilder The builder for the class descriptor
     */
    fun additionalElements(classSerialDescriptorBuilder: ClassSerialDescriptorBuilder) {
        // Do nothing by default
    }

    /**
     * Extension point to serialize additional properties of the entity.
     * Override this method to encode custom properties specific to your entity type.
     *
     * @param compositeEncoder The encoder to write data to
     * @param value The entity being serialized
     */
    fun additionalSerialize(compositeEncoder: CompositeEncoder, value: T) {
        // Do nothing by default
    }

    /**
     * Extension point to deserialize additional properties of the entity.
     * Override this method to decode custom properties specific to your entity type.
     *
     * @param compositeDecoder The decoder to read data from
     * @param propertiesList The list of properties already decoded, which may be modified by this method
     * @param index The index of the current property being decoded
     * @return The decoded value, or null if no value was decoded
     */
    fun additionalDeserialize(compositeDecoder: CompositeDecoder, propertiesList: MutableList<Any?>, index: Int) {
        // Do nothing by default
    }

    /**
     * Extracts the list of property values from the decoder.
     * This method is responsible for decoding all properties of the entity.
     *
     * @param decoder The decoder to read data from
     * @return A list of the decoded property values
     */
    fun getPropertiesList(decoder: Decoder): List<Any?>

    /**
     * Creates a new instance of the entity using the list of property values.
     * This method is responsible for constructing the entity from its decoded properties.
     *
     * @param propertiesList The list of property values to use for constructing the entity
     * @return A new instance of T constructed from the property values
     */
    fun createInstance(propertiesList: List<Any?>): T
}