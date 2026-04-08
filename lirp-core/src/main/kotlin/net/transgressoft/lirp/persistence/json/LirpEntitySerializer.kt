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
import net.transgressoft.lirp.persistence.AggregateCollectionRef
import net.transgressoft.lirp.persistence.MutableAggregateListProxy
import net.transgressoft.lirp.persistence.MutableAggregateSetProxy
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.descriptors.element
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.serializer

/**
 * Runtime [KSerializer] for [ReactiveEntityBase] subclasses that serializes entities
 * by introspecting their LIRP delegate registry instead of relying on KSP-generated code
 * or `@Serializable` annotations.
 *
 * Constructor parameters are serialized first (discovered via [KClass.primaryConstructor]),
 * followed by delegate properties (discovered via [ReactiveEntityBase.delegateRegistry]).
 * JSON field names match property names exactly.
 *
 * Usage: pass a sample entity instance to the [lirpSerializer] factory function to build
 * the serializer, then use it with [MapSerializer] when constructing a [JsonFileRepository]:
 * ```kotlin
 * val serializer = lirpSerializer(MyEntity(defaultId))
 * val repo = JsonFileRepository(file, MapSerializer(Int.serializer(), serializer))
 * ```
 *
 * @param E the entity type
 * @param kClass the entity's [KClass]
 * @param sampleInstance a sample entity used to discover delegate properties at construction time
 */
class LirpEntitySerializer<E : ReactiveEntityBase<*, *>>(
    private val kClass: KClass<E>,
    sampleInstance: E
) : KSerializer<E> {

    /**
     * Describes a constructor parameter that contributes to the serialized form.
     */
    private data class ConstructorParamInfo(
        val param: KParameter,
        val serializer: KSerializer<Any?>
    )

    /**
     * Describes a delegate property that contributes to the serialized form.
     * Uses `KProperty1<Any, Any?>` to avoid referencing the outer type parameter `E` in nested classes.
     */
    private sealed interface DelegateInfo {
        val name: String
        val serializer: KSerializer<Any?>

        data class ReactiveProperty(
            override val name: String,
            override val serializer: KSerializer<Any?>,
            val property: KProperty1<Any, Any?>
        ) : DelegateInfo

        data class AggregateCollection(
            override val name: String,
            override val serializer: KSerializer<Any?>
        ) : DelegateInfo
    }

    private val constructorParams: List<ConstructorParamInfo>
    private val delegateInfos: List<DelegateInfo>

    /**
     * Constructor parameters that are also reactive delegate properties (e.g. `name` passed to
     * `reactiveProperty(name)`). These are serialized as delegate fields but must also be supplied
     * to the primary constructor during deserialization.
     */
    private val constructorDelegateParams: Map<String, KParameter>

    init {
        val registry = sampleInstance.delegateRegistry
        val delegateNames = registry.keys.toSet()
        val memberProps = kClass.memberProperties.associateBy { it.name }
        val allConstructorParams = kClass.primaryConstructor?.parameters ?: emptyList()

        // Only serialize constructor params that have a corresponding member property (not constructor-only params
        // like `initialIds` which are consumed at construction time and have no getter for serialization)
        constructorParams =
            allConstructorParams
                .filter { param -> param.name != null && param.name !in delegateNames && param.name in memberProps }
                .map { param -> ConstructorParamInfo(param, serializer(param.type)) }

        // Track constructor params that are also reactive delegates — they are serialized as
        // delegate fields but must be forwarded to the constructor during deserialization
        constructorDelegateParams =
            allConstructorParams
                .filter { param -> param.name != null && param.name in delegateNames }
                .associateBy { it.name!! }

        // Collect delegate infos preserving the order from memberProperties (kotlin-reflect preserves declaration order)
        delegateInfos =
            registry.entries.map { (name, delegate) ->
                val prop = memberProps[name]
                if (delegate is AggregateCollectionRef<*, *>) {
                    val idSerializer = resolveAggregateIdSerializer(delegate, prop)
                    @Suppress("UNCHECKED_CAST")
                    DelegateInfo.AggregateCollection(name, ListSerializer(idSerializer) as KSerializer<Any?>)
                } else {
                    val typedProp =
                        requireNotNull(prop) {
                            "Cannot find member property '$name' on ${kClass.simpleName}"
                        }
                    @Suppress("UNCHECKED_CAST")
                    DelegateInfo.ReactiveProperty(
                        name,
                        serializer(typedProp.returnType),
                        typedProp as KProperty1<Any, Any?>
                    )
                }
            }
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Suppress("UNCHECKED_CAST")
    private fun resolveAggregateIdSerializer(
        delegate: AggregateCollectionRef<*, *>,
        prop: KProperty1<E, *>?
    ): KSerializer<Any?> {
        // Prefer live IDs type when the collection is non-empty — most reliable source
        val liveIds = delegate.referenceIds
        if (liveIds.isNotEmpty()) {
            val firstId = liveIds.first()
            return serializer(firstId::class, emptyList(), false)
        }
        // Fall back to the first type argument of the property's return type (e.g. List<Int> -> Int)
        val idKType = prop?.returnType?.arguments?.getOrNull(0)?.type
        if (idKType != null) {
            return serializer(idKType)
        }
        error(
            "Could not determine aggregate ID type for property '${prop?.name}' on ${kClass.simpleName}. " +
                "Build the serializer from a sample with at least one backing ID, or expose enough type " +
                "information to resolve the aggregate key serializer."
        )
    }

    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor(kClass.qualifiedName ?: kClass.simpleName ?: "Entity") {
            for (info in constructorParams) {
                element(info.param.name!!, info.serializer.descriptor, isOptional = info.param.isOptional)
            }
            for (info in delegateInfos) {
                element(info.name, info.serializer.descriptor, isOptional = false)
            }
        }

    @Suppress("UNCHECKED_CAST")
    override fun serialize(encoder: Encoder, value: E) {
        val composite = encoder.beginStructure(descriptor)
        var index = 0

        // Encode constructor params via their corresponding member properties
        for (info in constructorParams) {
            val propName = info.param.name!!
            val memberProp =
                kClass.memberProperties.find { it.name == propName }
                    ?: throw IllegalStateException(
                        "Constructor param '$propName' has no corresponding member property on ${kClass.simpleName}"
                    )
            val propValue = memberProp.get(value)
            composite.encodeSerializableElement(descriptor, index++, info.serializer, propValue)
        }

        // Encode delegate properties
        val registry = value.delegateRegistry
        for (info in delegateInfos) {
            when (info) {
                is DelegateInfo.ReactiveProperty -> {
                    val propValue = info.property.get(value as Any)
                    composite.encodeSerializableElement(descriptor, index++, info.serializer, propValue)
                }
                is DelegateInfo.AggregateCollection -> {
                    val delegate =
                        registry[info.name] as? AggregateCollectionRef<*, *>
                            ?: throw IllegalStateException(
                                "Aggregate delegate '${info.name}' not found in registry for ${kClass.simpleName}"
                            )
                    composite.encodeSerializableElement(descriptor, index++, info.serializer, delegate.referenceIds.toList())
                }
            }
        }

        composite.endStructure(descriptor)
    }

    @Suppress("UNCHECKED_CAST")
    override fun deserialize(decoder: Decoder): E {
        val composite = decoder.beginStructure(descriptor)

        val paramByIndex = constructorParams.mapIndexed { i, info -> i to info }.toMap()
        val delegateByIndex = delegateInfos.mapIndexed { i, info -> constructorParams.size + i to info }.toMap()

        val decoded = decodeElements(composite, paramByIndex, delegateByIndex)
        composite.endStructure(descriptor)

        // Merge reactive delegate values that are also constructor params (e.g. `name`)
        val mergedParamValues = decoded.paramValues.toMutableMap()
        for ((name, param) in constructorDelegateParams) {
            if (decoded.reactiveValues.containsKey(name)) {
                mergedParamValues[param] = decoded.reactiveValues[name]
            }
        }

        val entity = constructEntity(mergedParamValues)
        entity.withEventsDisabledForClone {
            restoreReactiveProperties(entity, decoded.reactiveValues)
            restoreAggregateIds(entity, decoded.aggregateIds)
        }
        return entity
    }

    private data class DecodedFields(
        val paramValues: Map<KParameter, Any?>,
        val reactiveValues: Map<String, Any?>,
        val aggregateIds: Map<String, List<Any?>>
    )

    @Suppress("UNCHECKED_CAST")
    private fun decodeElements(
        composite: CompositeDecoder,
        paramByIndex: Map<Int, ConstructorParamInfo>,
        delegateByIndex: Map<Int, DelegateInfo>
    ): DecodedFields {
        val paramValues = mutableMapOf<KParameter, Any?>()
        val aggregateIds = mutableMapOf<String, List<Any?>>()
        val reactiveValues = mutableMapOf<String, Any?>()

        loop@ while (true) {
            val elementIndex = composite.decodeElementIndex(descriptor)
            if (elementIndex == CompositeDecoder.DECODE_DONE) break@loop

            val paramInfo = paramByIndex[elementIndex]
            if (paramInfo != null) {
                paramValues[paramInfo.param] =
                    composite.decodeSerializableElement(descriptor, elementIndex, paramInfo.serializer)
                continue@loop
            }
            when (val delegateInfo = delegateByIndex[elementIndex]) {
                is DelegateInfo.ReactiveProperty ->
                    reactiveValues[delegateInfo.name] =
                        composite.decodeSerializableElement(descriptor, elementIndex, delegateInfo.serializer)
                is DelegateInfo.AggregateCollection ->
                    aggregateIds[delegateInfo.name] =
                        composite.decodeSerializableElement(descriptor, elementIndex, delegateInfo.serializer) as List<Any?>
                null -> composite.decodeSerializableElement(descriptor, elementIndex, serializer<Any?>())
            }
        }
        return DecodedFields(paramValues, reactiveValues, aggregateIds)
    }

    private fun constructEntity(paramValues: Map<KParameter, Any?>): E {
        val constructor =
            kClass.primaryConstructor
                ?: throw IllegalStateException("No primary constructor on ${kClass.simpleName}")
        constructor.isAccessible = true
        return constructor.callBy(paramValues)
    }

    private fun restoreReactiveProperties(entity: E, reactiveValues: Map<String, Any?>) {
        val registry = entity.delegateRegistry
        for ((name, decodedValue) in reactiveValues) {
            val delegate = registry[name] ?: continue
            val prop = kClass.memberProperties.find { it.name == name } ?: continue
            val setMethod = delegate::class.java.methods.find { it.name == "setValue" } ?: continue
            setMethod.isAccessible = true
            setMethod.invoke(delegate, entity, prop, decodedValue)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun restoreAggregateIds(entity: E, aggregateIds: Map<String, List<Any?>>) {
        val registry = entity.delegateRegistry
        for ((name, ids) in aggregateIds) {
            val delegate = registry[name]
            // Unwrap proxy layer to reach the backing ID delegate
            val mutableDelegate =
                when (delegate) {
                    is MutableAggregateListProxy<*, *> -> delegate.innerDelegate
                    is MutableAggregateSetProxy<*, *> -> delegate.innerDelegate
                    is AbstractMutableAggregateCollectionRefDelegate<*, *> -> delegate
                    else -> continue
                }
            mutableDelegate.setBackingIds(ids as Collection<Nothing>)
        }
    }
}

/**
 * Creates a [LirpEntitySerializer] for the given entity type by introspecting a [sample] instance.
 *
 * @param sample any instance of the entity class — used to discover delegate properties
 * @return a [KSerializer] that serializes/deserializes entities via delegate introspection
 */
inline fun <reified E : ReactiveEntityBase<*, *>> lirpSerializer(sample: E): LirpEntitySerializer<E> =
    LirpEntitySerializer(E::class, sample)