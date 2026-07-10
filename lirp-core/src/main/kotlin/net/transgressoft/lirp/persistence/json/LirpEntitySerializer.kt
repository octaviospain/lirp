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
import net.transgressoft.lirp.persistence.LirpReactivePropertyAccessor
import net.transgressoft.lirp.persistence.MutableAggregateList
import net.transgressoft.lirp.persistence.MutableAggregateSet
import net.transgressoft.lirp.persistence.ReactivePropertyDelegate
import net.transgressoft.lirp.persistence.ReactivePropertyDelegateWithAccessors
import net.transgressoft.lirp.persistence.ReactivePropertyEntry
import net.transgressoft.lirp.persistence.writeReactivePropertyBackingField
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.reflect.KClass
import kotlin.reflect.KParameter
import kotlin.reflect.KProperty1
import kotlin.reflect.full.createType
import kotlin.reflect.full.memberProperties
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.isAccessible
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.serializer

/**
 * Runtime [KSerializer] for [ReactiveEntityBase] subclasses that serializes entities
 * by introspecting their LIRP delegate registry and KSP-generated accessor classes.
 *
 * Constructor parameters are serialized first (discovered via [KClass.primaryConstructor]),
 * followed by delegate properties (discovered via [ReactiveEntityBase.delegateRegistry]).
 * JSON field names match property names exactly.
 *
 * Reactive-property-backed fields are read and written through the KSP-generated
 * `{EntityName}_LirpReactivePropertyAccessor` (looked up via [Class.forName]) when present.
 * FxScalar-backed fields use the analogous `{EntityName}_LirpFxScalarAccessor`. When an entity's
 * module does not apply lirp-ksp, both fall back to a reflection-based accessor so JSON
 * round-tripping still works without code generation — at the cost of reflection on the property
 * getters. Applying lirp-ksp restores the zero-reflection direct-call path.
 *
 * Serializers for nested constructor-parameter and reactive-property field types are resolved
 * through the supplied [serializersModule], so an entity whose field types are not `@Serializable`
 * can still be persisted by registering a contextual serializer for each such type. With the default
 * empty module, resolution falls back to the reflective built-in/`@Serializable` lookup — identical
 * to the behavior of consumers that register no contextual serializers. This mirrors the
 * `ColumnConverter` escape hatch the SQL persistence layer already offers.
 *
 * Usage: pass a sample entity instance to the [lirpSerializer] factory function to build
 * the serializer, then use it with [MapSerializer] when constructing a [JsonFileRepository]:
 * ```kotlin
 * val module = SerializersModule { contextual(Artist::class, ArtistSerializer) }
 * val serializer = lirpSerializer(MyEntity(defaultId), module)
 * val repo = JsonFileRepository(file, MapSerializer(Int.serializer(), serializer))
 * ```
 *
 * @param E the entity type
 * @param kClass the entity's [KClass]
 * @param sampleInstance a sample entity used to discover delegate properties at construction time
 * @param serializersModule module consulted to resolve serializers for nested field types, enabling
 *   contextual serializers for types that are not `@Serializable`; defaults to an empty module
 */
class LirpEntitySerializer<E : ReactiveEntityBase<*, *>>(
    private val kClass: KClass<E>,
    sampleInstance: E,
    private val serializersModule: SerializersModule = EmptySerializersModule()
) : KSerializer<E> {

    private val log = KotlinLogging.logger {}

    /**
     * Describes a constructor parameter that contributes to the serialized form.
     */
    private data class ConstructorParamInfo(
        val param: KParameter,
        val serializer: KSerializer<Any?>,
        val property: KProperty1<*, *>
    )

    /**
     * Describes a delegate property that contributes to the serialized form.
     */
    private sealed interface DelegateInfo {
        val name: String
        val serializer: KSerializer<Any?>

        data class ReactivePropertyKsp(
            override val name: String,
            override val serializer: KSerializer<Any?>,
            val getter: (Any) -> Any?,
            val silentSetter: (Any, Any?) -> Unit
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
            // True when the get/set lambdas accept the entity instance (KSP-generated path);
            // false when they accept the delegate instance (reflection fallback for JavaFX
            // *Property-typed delegates the KSP processor cannot detect).
            val kspBacked: Boolean
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
        val registry = sampleInstance.delegateRegistry
        val delegateNames = registry.keys.toSet()
        val memberProps = kClass.memberProperties.associateBy { it.name }
        val allConstructorParams = kClass.primaryConstructor?.parameters ?: emptyList()

        // Only serialize constructor params that have a corresponding member property (not constructor-only params
        // like `initialIds` which are consumed at construction time and have no getter for serialization)
        constructorParams =
            allConstructorParams
                .filter { param -> param.name != null && param.name !in delegateNames && param.name in memberProps }
                .map { param ->
                    val prop =
                        memberProps[param.name!!]
                            ?: error("Constructor param '${param.name}' has no corresponding member property on ${kClass.simpleName}")
                    ConstructorParamInfo(param, serializersModule.serializer(param.type), prop)
                }

        // Track constructor params that are also reactive delegates — they are serialized as
        // delegate fields but must be forwarded to the constructor during deserialization
        constructorDelegateParams =
            allConstructorParams
                .filter { param -> param.name != null && param.name in delegateNames }
                .associateBy { it.name!! }

        val hasReactiveDelegate =
            registry.values.any { it is ReactivePropertyDelegate<*> || it is ReactivePropertyDelegateWithAccessors<*> }
        val hasFxScalarDelegate = registry.values.any { it is FxScalarPropertyDelegate }

        val reactivePropertyAccessor: LirpReactivePropertyAccessor<E>? =
            tryLoadReactivePropertyAccessor()
                ?: if (hasReactiveDelegate) reflectionReactivePropertyAccessor(registry, memberProps) else null
        val fxScalarAccessor: LirpFxScalarAccessor<E>? = tryLoadFxScalarAccessor(hasFxScalarDelegate)

        val reactiveEntriesByName: Map<String, ReactivePropertyEntry<E>> =
            reactivePropertyAccessor?.entries?.associateBy { it.name } ?: emptyMap()

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
                            // Fallback retained for FxScalar properties whose declared type is a
                            // JavaFX `*Property` interface (e.g. IntegerProperty). The KSP FxScalar
                            // processor cannot resolve the delegate-expression type and so does not
                            // emit an accessor entry for those — reach the delegate's get/set via
                            // reflection on the underlying JavaFX `*Property` value.
                            val getMethod = requireDelegateMethod(delegate.javaClass, "get", 0)
                            val setMethod = requireDelegateMethod(delegate.javaClass, "set", 1)
                            getMethod.isAccessible = true
                            setMethod.isAccessible = true
                            @Suppress("UNCHECKED_CAST")
                            DelegateInfo.FxScalar(
                                name,
                                resolveFxScalarSerializer(delegate, prop) as KSerializer<Any?>,
                                getValue = { d -> getMethod.invoke(d) },
                                setValue = { d, v -> setMethod.invoke(d, v) },
                                kspBacked = false
                            )
                        }
                    }
                    else -> {
                        val kspEntry =
                            reactiveEntriesByName[name]
                                ?: error(
                                    "Entity '${kClass.simpleName}' reactive-property '$name' has no generated " +
                                        "LirpReactivePropertyAccessor entry — apply the net.transgressoft.lirp.sql Gradle plugin or add lirp-ksp to your build.gradle dependencies block to configure KSP."
                                )
                        @Suppress("UNCHECKED_CAST")
                        DelegateInfo.ReactivePropertyKsp(
                            name,
                            kspEntry.serializer,
                            getter = { entity -> kspEntry.getter(entity as E) },
                            silentSetter = { entity, value -> kspEntry.silentSetter(entity as E, value) }
                        )
                    }
                }
            }
        delegateInfosByName = delegateInfos.associateBy { it.name }
    }

    // Safe: return type is erased to KSerializer<Any?> for the composite encoder. The actual serializer
    // is resolved from the aggregate's declared ID type (Int, Long, String, UUID) — runtime match is guaranteed.
    @Suppress("UNCHECKED_CAST")
    private fun resolveAggregateIdSerializer(
        delegate: AggregateCollectionRef<*, *>,
        prop: kotlin.reflect.KProperty1<E, *>?
    ): KSerializer<Any?> {
        // Prefer the declared return type first (e.g. MutableAggregateList<Int, AudioItem> -> Int),
        // so the resolved serializer is stable regardless of whether the collection is populated.
        val idKType = prop?.returnType?.arguments?.getOrNull(0)?.type
        if (idKType != null) {
            return serializersModule.serializer(idKType)
        }
        // Fallback: reflect from the runtime class of the first live ID. Only reached when the member
        // property's declared return type is star-projected, making the first type argument unresolvable.
        val liveIds = delegate.referenceIds
        if (liveIds.isNotEmpty()) {
            val firstId = liveIds.first()
            return serializer(firstId::class, emptyList(), false)
        }
        error(
            "Could not determine aggregate ID type for property '${prop?.name}' on ${kClass.simpleName}. " +
                "Build the serializer from a sample with at least one backing ID, or expose enough type " +
                "information to resolve the aggregate key serializer."
        )
    }

    /**
     * Locates the single declared method named [name] with [parameterCount] parameters on a
     * delegate class, used by the FxScalar reflection fallback when the KSP processor cannot
     * detect a JavaFX `*Property`-typed delegate. Bridge and synthetic methods are filtered out.
     */
    private fun requireDelegateMethod(delegateClass: Class<*>, name: String, parameterCount: Int): java.lang.reflect.Method =
        delegateClass.methods.singleOrNull { method ->
            method.name == name &&
                method.parameterCount == parameterCount &&
                !method.isBridge &&
                !method.isSynthetic
        } ?: error("Expected exactly one '$name' method with $parameterCount parameters on ${delegateClass.name}")

    /**
     * Resolves the value-level serializer for an [FxScalarPropertyDelegate] in the reflection
     * fallback path. The delegate wraps a JavaFX property whose underlying value type is determined
     * from the property return type name; falls back to the declared payload type argument when
     * the property type isn't recognized, and only reflects the live value's runtime type as a
     * last resort when no declared type information is available.
     */
    private fun resolveFxScalarSerializer(
        delegate: FxScalarPropertyDelegate,
        prop: kotlin.reflect.KProperty1<E, *>?
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
                if (typeArg != null) serializersModule.serializer(typeArg) else serializer<String?>()
            }
            else -> {
                // Resolve from the declared property return type first — avoids defaulting to
                // String? when the sample value is null for a non-String FxScalar delegate.
                if (prop != null) {
                    val typeArg = prop.returnType.arguments.firstOrNull()?.type
                    if (typeArg != null) return serializersModule.serializer(typeArg)
                    // No type argument: attempt resolution via the declared return type itself.
                    return serializersModule.serializer(prop.returnType)
                }
                // Last resort: reflect from the live value's runtime type. Only reached when prop
                // is null (should not occur in practice but retained for safety).
                val value = delegate.javaClass.getMethod("get").invoke(delegate)
                if (value != null) serializersModule.serializer(value::class.createType()) else serializer<String?>()
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

        // Encode constructor params via their memoized member properties (resolved once in init)
        for (info in constructorParams) {
            @Suppress("UNCHECKED_CAST")
            val propValue = (info.property as KProperty1<E, *>).get(value)
            composite.encodeSerializableElement(descriptor, index++, info.serializer, propValue)
        }

        // Encode delegate properties
        val registry = value.delegateRegistry
        for (info in delegateInfos) {
            when (info) {
                is DelegateInfo.ReactivePropertyKsp -> {
                    val propValue = info.getter(value as Any)
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
                is DelegateInfo.ReactivePropertyKsp ->
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
        for ((name, decodedValue) in reactiveValues) {
            val info = delegateInfosByName[name] as? DelegateInfo.ReactivePropertyKsp ?: continue
            info.silentSetter(entity as Any, decodedValue)
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
     * Attempts to load the KSP-generated reactive-property accessor for the entity class.
     *
     * Returns `null` when no generated accessor exists (the entity's module did not apply lirp-ksp,
     * or the entity carries no reactive-property delegates), or when the accessor's constructor
     * throws. Two distinct failure modes are distinguished:
     *
     * - **Constructor threw a serialization error:** the expected case when a reactive property's
     *   type (e.g. `java.time.Instant`) requires a contextual serializer that inline `serializer<T>()`
     *   cannot resolve at construction time. `newInstance()` wraps it in [InvocationTargetException];
     *   only a wrapped `SerializationException` is treated as expected and logged at DEBUG. The caller
     *   substitutes [reflectionReactivePropertyAccessor] which honours [serializersModule] entries.
     * - **Constructor threw anything else, or reflective access failed ([ReflectiveOperationException]):**
     *   an unexpected codegen regression such as a renamed constructor or a visibility change. Logged
     *   at WARN so the failure is auditable above DEBUG; the reflection fallback still takes over so
     *   the entity remains usable.
     */
    @Suppress("UNCHECKED_CAST")
    private fun tryLoadReactivePropertyAccessor(): LirpReactivePropertyAccessor<E>? =
        try {
            val accessorClass =
                Class.forName(
                    "${kClass.java.name}_LirpReactivePropertyAccessor",
                    true,
                    kClass.java.classLoader
                )
            accessorClass.getDeclaredConstructor().newInstance() as LirpReactivePropertyAccessor<E>
        } catch (_: ClassNotFoundException) {
            null
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // newInstance() wraps every constructor exception in InvocationTargetException, so the
            // wrapped cause must be inspected: only a SerializationException is the expected
            // contextual-serializer case (a reactive property's type, e.g. java.time.Instant, needs a
            // contextual serializer that inline serializer<T>() cannot resolve at construction time).
            // Any other cause is an unexpected codegen bug and must not hide at DEBUG.
            if (e.targetException is kotlinx.serialization.SerializationException) {
                log.debug(e) { "KSP accessor for ${kClass.simpleName} constructor threw (contextual-serializer type); falling back to reflection accessor" }
            } else {
                log.warn(e) {
                    "KSP accessor for ${kClass.simpleName} constructor threw unexpectedly (${e.targetException?.javaClass?.simpleName}) — check that lirp-ksp generated code is up to date"
                }
            }
            null
        } catch (e: ReflectiveOperationException) {
            // Unexpected: NoSuchMethodException, InstantiationException, or IllegalAccessException —
            // not the contextual-serializer case. This may indicate stale lirp-ksp generated code
            // (e.g. a renamed constructor or a visibility change). Surface at WARN so a genuine
            // codegen regression is not invisible above DEBUG.
            log.warn(e) {
                "KSP accessor for ${kClass.simpleName} failed to load due to a reflective operation error — check that lirp-ksp generated code is up to date"
            }
            null
        }

    /**
     * Builds a reflection-based [LirpReactivePropertyAccessor] for entities whose module does not
     * apply lirp-ksp, so JSON round-tripping of reactive-property fields works without code
     * generation. Each entry reads through the property's getter and writes via
     * [writeReactivePropertyBackingField] — the same delegate-registry path the generated accessor
     * uses, bypassing event emission and `lastDateModified` bumping — and resolves the value
     * serializer from the property's declared type.
     *
     * This trades the generated direct-call accessor for reflection on the property getters; applying
     * lirp-ksp restores the zero-reflection path. It needs no `--add-opens`, as values are written
     * through the delegate registry rather than direct backing-field reflection.
     */
    private fun reflectionReactivePropertyAccessor(
        registry: Map<String, *>,
        memberProps: Map<String, KProperty1<E, *>>
    ): LirpReactivePropertyAccessor<E> {
        val reflectionEntries =
            registry.entries
                .filter { (_, delegate) ->
                    delegate is ReactivePropertyDelegate<*> || delegate is ReactivePropertyDelegateWithAccessors<*>
                }
                .map { (name, _) ->
                    val prop =
                        memberProps[name]
                            ?: error("Reactive property '$name' on ${kClass.simpleName} has no corresponding member property")
                    prop.isAccessible = true
                    ReactivePropertyEntry<E>(
                        name = name,
                        getter = { entity: E -> prop.get(entity) },
                        silentSetter = { entity: E, value -> writeReactivePropertyBackingField<Any?>(entity, name, value) },
                        serializer = serializersModule.serializer(prop.returnType)
                    )
                }
        return object : LirpReactivePropertyAccessor<E> {
            override val entries: List<ReactivePropertyEntry<E>> = reflectionEntries
        }
    }

    /**
     * Attempts to load the KSP-generated FxScalar accessor for the entity class.
     *
     * Returns `null` when no accessor is generated. The FxScalar KSP processor currently misses
     * properties whose declared type is a JavaFX `*Property` interface (e.g. `IntegerProperty`)
     * even when the delegate is `LirpIntegerProperty` (which implements `FxScalarPropertyDelegate`).
     * Generating an accessor for those cases requires resolving the delegate-expression type,
     * which KSP does not expose. Until that gap is closed, FxScalar continues to fall through
     * to the per-delegate reflection path inside [populateDelegateInfos] / [resolveFxScalarSerializer].
     */
    @Suppress("UNCHECKED_CAST", "UNUSED_PARAMETER")
    private fun tryLoadFxScalarAccessor(hasFxScalarDelegate: Boolean): LirpFxScalarAccessor<E>? =
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
 * @param serializersModule module consulted to resolve serializers for nested field types, enabling
 *   contextual serializers for field types that are not `@Serializable`; defaults to an empty module
 * @return a [KSerializer] that serializes/deserializes entities via delegate introspection
 */
inline fun <reified E : ReactiveEntityBase<*, *>> lirpSerializer(
    sample: E,
    serializersModule: SerializersModule = EmptySerializersModule()
): LirpEntitySerializer<E> = LirpEntitySerializer(E::class, sample, serializersModule)