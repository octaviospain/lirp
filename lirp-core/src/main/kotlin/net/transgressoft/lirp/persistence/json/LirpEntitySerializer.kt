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
import net.transgressoft.lirp.persistence.FxScalarPropertyDelegate
import net.transgressoft.lirp.persistence.LirpFxScalarAccessor
import net.transgressoft.lirp.persistence.MutableAggregateList
import net.transgressoft.lirp.persistence.MutableAggregateSet
import io.github.oshai.kotlinlogging.KotlinLogging
import java.lang.reflect.Method
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.full.createType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
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

    private companion object {
        private val log = KotlinLogging.logger {}
    }

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
            val property: KProperty1<Any, Any?>,
            val setValueMethod: Method
        ) : DelegateInfo

        data class AggregateCollection(
            override val name: String,
            override val serializer: KSerializer<Any?>
        ) : DelegateInfo

        data class FxScalar(
            override val name: String,
            override val serializer: KSerializer<Any?>,
            val getValue: (Any) -> Any?,
            val setValue: (Any, Any?) -> Unit,
            val kspBacked: Boolean = false
        ) : DelegateInfo
    }

    private val constructorParams: List<ConstructorParamInfo>
    private val delegateInfos: List<DelegateInfo>
    private val delegateInfosByName: Map<String, DelegateInfo>

    /**
     * Constructor parameters that are also reactive delegate properties (e.g. `name` passed to
     * `reactiveProperty(name)`). These are serialized as delegate fields but must also be supplied
     * to the primary constructor during deserialization.
     */
    private val constructorDelegateParams: Map<String, KParameter>

    init {
        fun Class<*>.requireMethod(name: String, parameterCount: Int): Method =
            methods.singleOrNull { method ->
                method.name == name &&
                    method.parameterCount == parameterCount &&
                    !method.isBridge &&
                    !method.isSynthetic
            } ?: error("Expected exactly one '$name' method with $parameterCount parameters on ${this.name}")

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

        val fxScalarAccessor: LirpFxScalarAccessor<E>? = tryLoadFxScalarAccessor()

        // Collect delegate infos preserving the order from memberProperties (kotlin-reflect preserves declaration order).
        delegateInfos =
            registry.entries.map { (name, delegate) ->
                val prop = memberProps[name]
                when {
                    delegate is AggregateCollectionRef<*, *> -> {
                        val idSerializer = resolveAggregateIdSerializer(delegate, prop)
                        // Safe: idSerializer resolves the aggregate's declared ID type. kotlinx-serialization's composite
                        // encoder accepts KSerializer<Any?> at the element level — the runtime value matches the declared type.
                        @Suppress("UNCHECKED_CAST")
                        DelegateInfo.AggregateCollection(name, ListSerializer(idSerializer) as KSerializer<Any?>)
                    }
                    delegate is FxScalarPropertyDelegate -> {
                        val kspEntry = fxScalarAccessor?.entries?.find { it.name == name }
                        if (kspEntry != null) {
                            // Safe: fxScalarAccessor is LirpFxScalarAccessor<E>, so entity is always E at runtime
                            @Suppress("UNCHECKED_CAST")
                            DelegateInfo.FxScalar(
                                name,
                                kspEntry.serializer,
                                getValue = { entity -> kspEntry.getter(entity as E) },
                                setValue = { entity, value -> kspEntry.setter(entity as E, value) },
                                kspBacked = true
                            )
                        } else {
                            log.warn {
                                "Entity '${kClass.simpleName}' FxScalar property '$name' using reflection fallback. " +
                                    "Apply lirp-ksp to eliminate --add-opens requirement."
                            }
                            val getMethod = delegate.javaClass.requireMethod("get", 0)
                            val setMethod = delegate.javaClass.requireMethod("set", 1)
                            getMethod.isAccessible = true
                            setMethod.isAccessible = true
                            // Safe: resolveFxScalarSerializer returns a serializer matching the delegate's declared value type.
                            // KSerializer<Any?> is required by the composite encoder; the runtime type is always correct.
                            @Suppress("UNCHECKED_CAST")
                            DelegateInfo.FxScalar(
                                name,
                                resolveFxScalarSerializer(delegate, prop) as KSerializer<Any?>,
                                getValue = { d -> getMethod.invoke(d) },
                                setValue = { d, v -> setMethod.invoke(d, v) }
                            )
                        }
                    }
                    else -> {
                        val typedProp =
                            requireNotNull(prop) {
                                "Cannot find member property '$name' on ${kClass.simpleName}"
                            }
                        val setValueMethod = delegate::class.java.requireMethod("setValue", 3)
                        setValueMethod.isAccessible = true
                        // Safe: typedProp is looked up from kClass.memberProperties by name. The entity instance passed to
                        // property.get() is always of type E, so KProperty1<Any, Any?> is a safe widening cast.
                        @Suppress("UNCHECKED_CAST")
                        DelegateInfo.ReactiveProperty(
                            name,
                            serializer(typedProp.returnType),
                            typedProp as KProperty1<Any, Any?>,
                            setValueMethod
                        )
                    }
                }
            }
        delegateInfosByName = delegateInfos.associateBy { it.name }
    }

    @OptIn(ExperimentalSerializationApi::class)
    // Safe: return type is erased to KSerializer<Any?> for the composite encoder. The actual serializer
    // is resolved from the aggregate's declared ID type (Int, Long, String, UUID) — runtime match is guaranteed.
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

    /**
     * Resolves the value-level serializer for an [FxScalarPropertyDelegate]. The delegate wraps a
     * JavaFX property whose underlying value type is determined from the property return type name.
     * Falls back to the current value's runtime type when the property type isn't recognized.
     */
    private fun resolveFxScalarSerializer(
        delegate: FxScalarPropertyDelegate,
        prop: KProperty1<E, *>?
    ): KSerializer<*> {
        val qualifiedName = (prop?.returnType?.classifier as? KClass<*>)?.qualifiedName ?: ""
        return when {
            qualifiedName.endsWith("StringProperty") -> serializer<String?>()
            qualifiedName.endsWith("IntegerProperty") -> serializer<Int>()
            qualifiedName.endsWith("DoubleProperty") -> serializer<Double>()
            qualifiedName.endsWith("FloatProperty") -> serializer<Float>()
            qualifiedName.endsWith("LongProperty") -> serializer<Long>()
            qualifiedName.endsWith("BooleanProperty") -> serializer<Boolean>()
            qualifiedName.endsWith("ObjectProperty") -> {
                val typeArg = prop?.returnType?.arguments?.firstOrNull()?.type
                if (typeArg != null) serializer(typeArg) else serializer<String?>()
            }
            else -> {
                val value = delegate.javaClass.getMethod("get").invoke(delegate)
                if (value != null) serializer(value::class.createType()) else serializer<String?>()
            }
        }
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

    // Safe: constructorParams and delegateInfos are built from the same entity class E during init.
    // All property reads and serializer invocations operate on the concrete entity type.
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
                is DelegateInfo.FxScalar -> {
                    val target =
                        if (info.kspBacked) {
                            value
                        } else {
                            registry[info.name]
                                ?: throw IllegalStateException(
                                    "Fx scalar delegate '${info.name}' not found in registry for ${kClass.simpleName}"
                                )
                        }
                    val fxValue = info.getValue(target)
                    composite.encodeSerializableElement(descriptor, index++, info.serializer, fxValue)
                }
            }
        }

        composite.endStructure(descriptor)
    }

    // Safe: symmetric to serialize — decoder uses the same descriptor and serializers built from class E.
    // Decoded values are passed to the primary constructor which enforces the correct types.
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
            restoreFxScalarProperties(entity, decoded.fxScalarValues)
        }
        return entity
    }

    private data class DecodedFields(
        val paramValues: Map<KParameter, Any?>,
        val reactiveValues: Map<String, Any?>,
        val aggregateIds: Map<String, List<Any?>>,
        val fxScalarValues: Map<String, Any?>
    )

    // Safe: each decodeSerializableElement call uses the serializer from the matching DelegateInfo/ConstructorParamInfo,
    // which was built from the declared property types. The cast to List<Any?> / Any? matches the serializer's output type.
    @Suppress("UNCHECKED_CAST")
    private fun decodeElements(
        composite: CompositeDecoder,
        paramByIndex: Map<Int, ConstructorParamInfo>,
        delegateByIndex: Map<Int, DelegateInfo>
    ): DecodedFields {
        val paramValues = mutableMapOf<KParameter, Any?>()
        val aggregateIds = mutableMapOf<String, List<Any?>>()
        val reactiveValues = mutableMapOf<String, Any?>()
        val fxScalarValues = mutableMapOf<String, Any?>()

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
                is DelegateInfo.FxScalar ->
                    fxScalarValues[delegateInfo.name] =
                        composite.decodeSerializableElement(descriptor, elementIndex, delegateInfo.serializer)
                null -> composite.decodeSerializableElement(descriptor, elementIndex, serializer<Any?>())
            }
        }
        return DecodedFields(paramValues, reactiveValues, aggregateIds, fxScalarValues)
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
            val info = delegateInfosByName[name] as? DelegateInfo.ReactiveProperty ?: continue
            val delegate = registry[name] ?: continue
            info.setValueMethod.invoke(delegate, entity, info.property, decodedValue)
        }
    }

    // Safe: Nothing is used as the bottom type to satisfy Collection<K> with erased K. The actual collection
    // contains correctly-typed ID values verified by resolveAggregateIdSerializer during init.
    @Suppress("UNCHECKED_CAST")
    private fun restoreAggregateIds(entity: E, aggregateIds: Map<String, List<Any?>>) {
        val registry = entity.delegateRegistry
        for ((name, ids) in aggregateIds) {
            val delegate = registry[name]
            // Unwrap proxy layer to reach the backing ID delegate
            val mutableDelegate =
                when (delegate) {
                    is MutableAggregateList<*, *> -> delegate.innerDelegate
                    is MutableAggregateSet<*, *> -> delegate.innerDelegate
                    is AbstractMutableAggregateCollectionRefDelegate<*, *> -> delegate
                    else -> continue
                }
            mutableDelegate.setBackingIds(ids as Collection<Nothing>)
        }
    }

    private fun restoreFxScalarProperties(entity: E, fxScalarValues: Map<String, Any?>) {
        val registry = entity.delegateRegistry
        for ((name, decodedValue) in fxScalarValues) {
            val info = delegateInfosByName[name] as? DelegateInfo.FxScalar ?: continue
            val target: Any =
                if (info.kspBacked) {
                    entity
                } else {
                    val delegate = registry[name] ?: continue
                    if (delegate !is FxScalarPropertyDelegate) continue
                    delegate
                }
            info.setValue(target, decodedValue)
        }
    }

    /**
     * Attempts to load the KSP-generated FxScalar accessor for the entity class.
     * Returns null if no accessor was generated (KSP not applied to this entity).
     */
    @Suppress("UNCHECKED_CAST")
    private fun tryLoadFxScalarAccessor(): LirpFxScalarAccessor<E>? =
        try {
            val accessorClass =
                Class.forName(
                    "${kClass.java.name}_LirpFxScalarAccessor",
                    true,
                    kClass.java.classLoader
                )
            accessorClass.getDeclaredConstructor().newInstance() as LirpFxScalarAccessor<E>
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: LinkageError) {
            null
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