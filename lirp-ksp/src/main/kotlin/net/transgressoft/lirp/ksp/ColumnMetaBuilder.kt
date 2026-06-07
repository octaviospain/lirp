/******************************************************************************
 *     Copyright (C) 2026  Octavio Calleya Garcia                             *
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

package net.transgressoft.lirp.ksp

import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate

private const val AGGREGATE_ANNOTATION_FQN = "net.transgressoft.lirp.persistence.Aggregate"

/**
 * Represents the outcome of a column-eligibility check for a KSP property declaration.
 *
 * The three variants are mutually exclusive and encode the complete decision:
 * - [Column] — the property maps to a persistable SQL column; carries the resolved [ColumnMeta].
 * - [Excluded] — the property is intentionally excluded from persistence (e.g. `@Transient`,
 *   `@PersistenceIgnore`, `@Aggregate`, private backing field, or a pure computed property).
 * - [Deferred] — the property type or one of its annotations could not be resolved in the current
 *   KSP round. The enclosing entity [symbol] should be returned in `unableToProcess` so KSP
 *   re-presents it in a later round once the missing type is available.
 */
internal sealed interface Eligibility {

    /** The property resolves to a persistable SQL column described by [meta]. */
    data class Column(val meta: ColumnMeta) : Eligibility

    /** The property is intentionally excluded from persistence and must not become a SQL column. */
    data object Excluded : Eligibility

    /**
     * The property's type or annotation FQN could not be resolved in this KSP round.
     * [symbol] is the enclosing entity class declaration that should be re-queued via
     * `unableToProcess` so the processor retries this entity in the next round.
     * [unresolvedAnnotation] distinguishes an unresolvable annotation FQN (`true`) from an
     * unresolvable property *type* (`false`) so the terminal diagnostic can name the real cause.
     */
    data class Deferred(val symbol: KSClassDeclaration, val unresolvedAnnotation: Boolean = false) : Eligibility
}

/**
 * Builds [ColumnMeta] instances from KSP property declarations. Handles converter resolution,
 * type-expression derivation, and the exclusion rules that determine which properties become
 * SQL columns. Used by [EmbeddableAnalyzer] for both top-level entity scalars and nested
 * `@Embeddable` leaf columns.
 *
 * **Two distinct column-building entry points — not duplicates:**
 * - [buildColumnMeta] handles top-level entity properties: it resolves PK status, `@Version`
 *   identity, aggregate FK exclusion, per-property `isMutable`/`isCtorParam`, and converter
 *   rejection on PK/version/FK columns.
 * - [buildEmbeddedLeafColumn] handles scalar leaves inside an `@Embeddable` hierarchy: it
 *   stamps the concatenated `prefix + snake(leafName)` column name, the entity-rooted
 *   `embeddedPath`, and `isInsideEmbedded = true`, while suppressing PK/version/aggregate
 *   logic that is inapplicable inside an `@Embeddable`. Both entry points share the same
 *   converter resolution and type-expression pipeline; the structural metadata they produce
 *   is intentionally different. The traversal that calls these methods lives entirely in
 *   [EmbeddableAnalyzer], keeping the column-building logic here free of tree-walk concerns.
 */
internal class ColumnMetaBuilder(private val logger: KSPLogger) {

    /**
     * Resolves the [Eligibility] for a property on an entity class. Returns [Eligibility.Column]
     * carrying the resolved [ColumnMeta] when the property maps to a persistable SQL column,
     * [Eligibility.Excluded] when the property is intentionally excluded from persistence, or
     * [Eligibility.Deferred] when the property type or annotation FQN cannot yet be resolved in
     * this KSP round and the enclosing entity should be re-queued for the next round.
     */
    fun buildColumnMeta(
        prop: KSPropertyDeclaration,
        hasDeclaredId: Boolean,
        versionedName: String?,
        ctorParamNames: Set<String>,
        enclosingClass: KSClassDeclaration,
        aggregateBackingScalarNames: Set<String> = emptySet()
    ): Eligibility {
        when (val eligibility = checkEligibility(prop, enclosingClass = enclosingClass)) {
            is Eligibility.Excluded, is Eligibility.Deferred -> return eligibility
            is Eligibility.Column -> { /* property is eligible — proceed to build ColumnMeta */ }
        }

        val propName = prop.simpleName.asString()
        val persistenceAnnotation = resolvePersistenceAnnotations(prop).firstWithFqn(PERSISTENCE_PROPERTY_FQN)
        val resolvedType = prop.type.resolve()
        if (resolvedType.isError) return Eligibility.Deferred(enclosingClass)

        val notNullableType = resolvedType.makeNotNullable()
        val typeFqn = notNullableType.declaration.qualifiedName?.asString() ?: "kotlin.Any"
        val isEnum = (notNullableType.declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS

        val propertyFqn = "${prop.parentDeclaration?.qualifiedName?.asString() ?: ""}.$propName".trimStart('.')

        val isPrimaryKey = propName == "id" && hasDeclaredId && !prop.isAbstract()
        val isVersion = versionedName != null && propName == versionedName
        val isAggregateBackingScalar = propName in aggregateBackingScalarNames

        // reject converter arguments on PK / @Version / @Aggregate single-ref FK columns
        // BEFORE invoking resolveConverter (which carries /). This preserves the
        // one-diagnostic-per-site invariant: a misplaced converter on a rejected target emits
        // the target rejection, not the kind/S diagnostics.
        val converterInfo =
            if (hasNonSentinelConverterArgument(persistenceAnnotation)) {
                when {
                    isPrimaryKey -> {
                        logger.error(
                            "@PersistenceProperty(converter = …) is not allowed on primary key column '$propertyFqn'. " +
                                "Converters apply only to non-PK scalar columns; domain-typed identifiers are deferred to a future phase."
                        )
                        null
                    }
                    isVersion -> {
                        logger.error(
                            "@PersistenceProperty(converter = …) is not allowed on @Version column '$propertyFqn'. " +
                                "@Version columns require a numeric (Long) scalar type; converter routing on optimistic-locking columns is out of scope."
                        )
                        null
                    }
                    isAggregateBackingScalar -> {
                        logger.error(
                            "@PersistenceProperty(converter = …) is not allowed on @Aggregate single-ref FK scalar column '$propertyFqn'. " +
                                "Converter routing on aggregate FK columns is out of scope; the FK column type is dictated by the referenced entity's primary key type."
                        )
                        null
                    }
                    else -> resolveConverter(persistenceAnnotation, propertyFqn)
                }
            } else {
                null
            }

        // When a converter is bound, derive the column type from the converter's declared sqlType
        // (and refine it via compatible @PersistenceProperty hints). Otherwise fall back to the
        // FQN-driven base expression. A null refinement result means a hint/converter mismatch was
        // diagnosed via logger.error — drop the column so codegen does not emit a malformed file.
        // The non-converter branch invokes mapToColumnTypeExpression lazily so a converter-bound
        // column with a non-scalar Kotlin type (e.g. a value class) bypasses the
        // "unsupported column type" diagnostic that path would otherwise emit.
        val hasExplicitConverter = hasNonSentinelConverterArgument(persistenceAnnotation)
        val typeExpression =
            if (converterInfo != null) {
                refineConverterSqlType(
                    converterInfo = converterInfo, hints = extractHints(persistenceAnnotation), propertyFqn = propertyFqn, propName = propName
                ) ?: return Eligibility.Excluded
            } else if (hasExplicitConverter) {
                // Converter declared but unresolved/invalid this round; avoid non-converter fallback.
                // Invalid converters already emitted diagnostics in resolveConverter.
                return Eligibility.Excluded
            } else {
                mapToColumnTypeExpression(prop, persistenceAnnotation) ?: return Eligibility.Excluded
            }

        return Eligibility.Column(
            ColumnMeta(
                name = columnNameFor(persistenceAnnotation, propName),
                propertyName = propName,
                typeExpression = typeExpression,
                typeFqn = typeFqn,
                nullable = resolvedType.isMarkedNullable,
                isPrimaryKey = isPrimaryKey,
                isEnum = isEnum,
                isMutable = prop.isMutable && hasPublicSetter(prop),
                isCtorParam = propName in ctorParamNames,
                isVersion = isVersion,
                converterFqn = converterInfo?.converterFqn,
                converterSqlFqn = converterInfo?.sqlTypeFqn
            )
        )
    }

    /**
     * Resolves the [Eligibility] for a single scalar leaf inside an `@Embeddable`. Routes through
     * the same type/converter resolution path as [buildColumnMeta] but stamps the column with the
     * concatenated `${prefix}${snake(leaf)}` name, the embedded access path, and marks it
     * `isInsideEmbedded = true` so downstream emitters (`applyRow`, `applyScalarRow`) skip it.
     *
     * Returns [Eligibility.Deferred] when the leaf type cannot be resolved in this KSP round,
     * signalling that the enclosing entity should be re-queued for processing in the next round.
     */
    fun buildEmbeddedLeafColumn(
        childProp: KSPropertyDeclaration,
        childParamName: String,
        prefix: String,
        parentPath: String,
        topLevelPropertyName: String,
        enclosingClass: KSClassDeclaration,
        ctorParam: KSValueParameter? = null
    ): Eligibility {
        val persistenceAnnotation = resolvePersistenceAnnotations(childProp, ctorParam).firstWithFqn(PERSISTENCE_PROPERTY_FQN)
        val propertyFqn = "${childProp.parentDeclaration?.qualifiedName?.asString() ?: ""}.$childParamName".trimStart('.')

        val resolvedType = childProp.type.resolve()
        if (resolvedType.isError) return Eligibility.Deferred(enclosingClass)

        val notNullable = resolvedType.makeNotNullable()
        val childTypeFqn = notNullable.declaration.qualifiedName?.asString() ?: "kotlin.Any"
        val isEnum = (notNullable.declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS

        // Reuse the converter resolution + hint-refinement pipeline so an
        // `@PersistenceProperty(converter = X::class)` at a scalar leaf inside an `@Embeddable`
        // produces the same column-type expression and fromRow/toParams casts it would at the
        // top level.
        val converterInfo =
            if (hasNonSentinelConverterArgument(persistenceAnnotation)) {
                resolveConverter(persistenceAnnotation, propertyFqn)
            } else {
                null
            }

        val hasExplicitConverter = hasNonSentinelConverterArgument(persistenceAnnotation)
        val typeExpression =
            if (converterInfo != null) {
                refineConverterSqlType(converterInfo, extractHints(persistenceAnnotation), propertyFqn, childParamName) ?: return Eligibility.Excluded
            } else if (hasExplicitConverter) {
                return Eligibility.Excluded
            } else {
                mapToColumnTypeExpression(childProp, persistenceAnnotation) ?: return Eligibility.Excluded
            }

        val columnName = "$prefix${childParamName.toSnakeCase()}"
        return Eligibility.Column(
            ColumnMeta(
                name = columnName,
                // propertyName carries the top-level entity ctor-param so the mutability gate (which
                // exempts ctor-param val fields) recognises this column as ctor-driven. The actual
                // entity-access path lives in embeddedPath.
                propertyName = topLevelPropertyName,
                typeExpression = typeExpression,
                typeFqn = childTypeFqn,
                nullable = resolvedType.isMarkedNullable,
                isPrimaryKey = false,
                isEnum = isEnum,
                isMutable = false,
                isCtorParam = true,
                isVersion = false,
                converterFqn = converterInfo?.converterFqn,
                converterSqlFqn = converterInfo?.sqlTypeFqn,
                embeddedPath = "$parentPath.$childParamName",
                isInsideEmbedded = true
            )
        )
    }

    /**
     * Resolves the [ColumnMeta] for an `@ElementCollection`-annotated property. Validates the
     * structural constraints in order, emitting a single targeted `logger.error()` at the first
     * violation and returning `null`. A property that passes every check yields a fully-populated
     * [ColumnMeta] with [ColumnMeta.isElementCollection] `true` and [ColumnMeta.defaultExpression]
     * `"[]"`.
     *
     * Both primary-constructor parameters (`val` / `var`) and body-declared properties (typically
     * `var x by reactiveProperty(initial)`) are accepted. [isCtorParam] tells the codegen in
     * `TableDefSourceEmitter` whether to populate the field via the primary-constructor call or via
     * the property setter after construction.
     */
    fun buildElementCollectionColumn(
        prop: KSPropertyDeclaration,
        classFqn: String,
        isCtorParam: Boolean,
        ctorParam: KSValueParameter? = null
    ): ColumnMeta? {
        val propertyFqn = "$classFqn.${prop.simpleName.asString()}"
        val resolvedType = prop.type.resolve()

        val collectionKind = resolveElementCollectionKind(resolvedType, propertyFqn, prop) ?: return null
        if (!elementTypeIsNonNullable(resolvedType, propertyFqn, prop)) return null
        if (rejectsPersistencePropertyComposition(prop, propertyFqn, ctorParam)) return null
        val sFqn = resolveElementConverterSqlType(prop, propertyFqn, ctorParam) ?: return null
        val elementConverterFqn = elementConverterFqn(prop, ctorParam)

        return ColumnMeta(
            name = prop.simpleName.asString().toSnakeCase(),
            propertyName = prop.simpleName.asString(),
            typeExpression = COLUMN_TYPE_TEXT_EXPR,
            typeFqn = resolvedType.makeNotNullable().declaration.qualifiedName!!.asString(),
            nullable = false,
            isPrimaryKey = false,
            isEnum = false,
            // isMutable gates applyRow write-back. Body-declared element collections (isCtorParam=false)
            // are always var (val is rejected before this point), so they must be mutable for
            // post-construction population. Ctor-param collections are mutable only when declared
            // var; val ctor params are populated via the constructor call and need no setter.
            isMutable = !isCtorParam || prop.isMutable,
            isCtorParam = isCtorParam,
            isVersion = false,
            converterFqn = null,
            converterSqlFqn = sFqn,
            isElementCollection = true,
            elementConverterFqn = elementConverterFqn,
            collectionKind = collectionKind,
            defaultExpression = "[]"
        )
    }

    /**
     * Validates that the property is a non-nullable `List<E>` or `Set<E>`, returning the kind
     * (`"List"` / `"Set"`). Rejects nullable collection types, `Map`, and the mutable interfaces.
     */
    private fun resolveElementCollectionKind(
        resolvedType: KSType,
        propertyFqn: String,
        prop: KSPropertyDeclaration
    ): String? {
        if (resolvedType.isMarkedNullable) {
            val nonNullFqn = resolvedType.makeNotNullable().declaration.qualifiedName?.asString()
            // Use exact FQN matching to derive the kind label so types whose name merely contains
            // "Set" are not mislabelled. Non-collection nullable types (e.g. Map?) fall through
            // to the non-null when-block's else branch for a targeted "requires List/Set" diagnostic.
            when (nonNullFqn) {
                KOTLIN_SET_FQN, KOTLIN_LIST_FQN -> {
                    val shortKind = if (nonNullFqn == KOTLIN_SET_FQN) "Set" else "List"
                    logger.error(
                        "@ElementCollection property type must be non-nullable; found `$shortKind<E>?` on '$propertyFqn'. " +
                            "The column carries an empty array '[]' for the empty case.",
                        prop
                    )
                    return null
                }
                else -> {
                    // Delegate to the non-null branch for a precise diagnostic about the unsupported type.
                }
            }
        }
        return when (val collectionFqn = resolvedType.makeNotNullable().declaration.qualifiedName?.asString()) {
            KOTLIN_LIST_FQN -> "List"
            KOTLIN_SET_FQN -> "Set"
            KOTLIN_MUTABLE_LIST_FQN, KOTLIN_MUTABLE_SET_FQN -> {
                val found = if (collectionFqn == KOTLIN_MUTABLE_LIST_FQN) "MutableList" else "MutableSet"
                logger.error(
                    "@ElementCollection requires the immutable interface `List<E>` or `Set<E>`; found `$found<…>` on '$propertyFqn'.",
                    prop
                )
                null
            }
            else -> {
                val found = if (collectionFqn == KOTLIN_MAP_FQN) "Map<…>" else "`${collectionFqn ?: "unknown"}`"
                logger.error(
                    "@ElementCollection requires `List<E>` or `Set<E>`; found $found on '$propertyFqn'. " +
                        "Use `@PersistenceProperty(converter = …)` with a custom Map-flattening converter if you need map-shaped storage.",
                    prop
                )
                null
            }
        }
    }

    /** Rejects a nullable or unresolvable element type `E`; returns `true` when `E` is valid. */
    private fun elementTypeIsNonNullable(
        resolvedType: KSType,
        propertyFqn: String,
        prop: KSPropertyDeclaration
    ): Boolean {
        val elementTypeArg = resolvedType.makeNotNullable().arguments.firstOrNull()?.type?.resolve()
        if (elementTypeArg == null) {
            logger.error(
                "@ElementCollection on '$propertyFqn' has a malformed element type — the first generic argument could not be resolved.",
                prop
            )
            return false
        }
        if (elementTypeArg.isMarkedNullable) {
            logger.error(
                "@ElementCollection element type must be non-nullable; found `<E>?` on '$propertyFqn'. " +
                    "Nullable elements cannot round-trip through JSON without ambiguity.",
                prop
            )
            return false
        }
        return true
    }

    /** Returns `true` (after logging) when the property also carries `@PersistenceProperty`. */
    private fun rejectsPersistencePropertyComposition(
        prop: KSPropertyDeclaration,
        propertyFqn: String,
        ctorParam: KSValueParameter? = null
    ): Boolean {
        val hasPersistenceProperty = resolvePersistenceAnnotations(prop, ctorParam).has(PERSISTENCE_PROPERTY_FQN)
        if (hasPersistenceProperty) {
            logger.error(
                "@ElementCollection and @PersistenceProperty cannot be combined on '$propertyFqn'. " +
                    "@ElementCollection fully owns the persistence shape of the collection's TEXT column.",
                prop
            )
        }
        return hasPersistenceProperty
    }

    /** Reads the `elementConverter` argument FQN from the `@ElementCollection` annotation. */
    private fun elementConverterFqn(prop: KSPropertyDeclaration, ctorParam: KSValueParameter? = null): String? =
        (elementConverterArg(prop, ctorParam)?.declaration?.qualifiedName?.asString())

    private fun elementConverterArg(prop: KSPropertyDeclaration, ctorParam: KSValueParameter? = null): KSType? =
        resolvePersistenceAnnotations(prop, ctorParam)
            .firstWithFqn(ELEMENT_COLLECTION_FQN)
            ?.arguments
            ?.firstOrNull { it.name?.asString() == "elementConverter" }
            ?.value as? KSType

    /**
     * Validates the element converter and returns its persistence-facing scalar type FQN. Requires
     * an explicit `object` converter whose `S` type is one of the eight Kotlin primitives —
     * `String`, `Int`, `Long`, `Short`, `Byte`, `Boolean`, `Double`, `Float`. JDK-bridge types
     * (`UUID`, `BigDecimal`, `LocalDate`, `LocalDateTime`) must be wrapped in a String-shaped
     * converter, since they have no built-in `kotlinx.serialization` JSON-array support.
     */
    private fun resolveElementConverterSqlType(
        prop: KSPropertyDeclaration,
        propertyFqn: String,
        ctorParam: KSValueParameter? = null
    ): String? {
        val elementConverterArg = elementConverterArg(prop, ctorParam)
        val elementConverterFqn = elementConverterArg?.declaration?.qualifiedName?.asString()
        if (elementConverterFqn == null || elementConverterFqn == COLUMN_CONVERTER_FQN) {
            logger.error(
                "@ElementCollection on '$propertyFqn' requires an explicit `elementConverter`. " +
                    "Provide an `object` implementing `ColumnConverter<E, S>` where `E` is the element type.",
                prop
            )
            return null
        }

        val converterDecl = elementConverterArg.declaration as? KSClassDeclaration ?: return null
        if (!converterDecl.validate()) return null
        if (converterDecl.classKind != ClassKind.OBJECT) {
            logger.error(
                "Converter '$elementConverterFqn' for property '$propertyFqn' must be a Kotlin `object` " +
                    "(singleton) so KSP-generated code can reference it without instantiation.",
                prop
            )
            return null
        }

        val sFqn =
            converterDecl.superTypes
                .map { it.resolve() }
                .firstOrNull { it.declaration.qualifiedName?.asString() == COLUMN_CONVERTER_FQN }
                ?.arguments?.getOrNull(1)?.type?.resolve()
                ?.declaration?.qualifiedName?.asString()
        if (sFqn == null) {
            logger.error(
                "Converter '$elementConverterFqn' does not declare ColumnConverter<D, S> as a supertype " +
                    "with both type arguments resolved.",
                prop
            )
            return null
        }

        if (sFqn !in ELEMENT_COLLECTION_S_TYPES) {
            logger.error(
                "@ElementCollection element converter's S type must be one of {String, Int, Long, Short, Byte, Boolean, Double, Float}; " +
                    "found `$sFqn` on '$propertyFqn'. " +
                    "Wrap `<E>` in a String-shaped converter (e.g., `UUID.toString()` / `UUID.fromString()`) to persist it inside @ElementCollection.",
                prop
            )
            return null
        }
        return sFqn
    }

    /**
     * Determines the [Eligibility] of [prop] as a persistable SQL column.
     *
     * The optional [ctorParam] enables cross-module `@PersistenceIgnore` detection: for properties
     * compiled into a dependency jar, annotations live on the `VALUE_PARAMETER` rather than the
     * synthesized property declaration. Callers with access to the matched constructor parameter
     * should always supply it so the [PERSISTENCE_IGNORE_FQN] check is cross-module safe.
     *
     * [enclosingClass] is the entity class declaration owning this property; it is carried by
     * [Eligibility.Deferred] so the caller can re-queue the correct symbol via `unableToProcess`.
     *
     * This is the single authoritative eligibility seam composing visibility, annotation exclusions,
     * delegate kind, and unresolvable-annotation deferral in one place.
     */
    fun checkEligibility(
        prop: KSPropertyDeclaration,
        ctorParam: KSValueParameter? = null,
        enclosingClass: KSClassDeclaration
    ): Eligibility {
        // Private backing fields are encapsulated implementation state — no public surface for
        // persistence to bind through. Mirrors the exclusion applied by `RawInitializerProcessor`
        // so the two processors agree on the persisted column set for a given entity shape.
        if (Modifier.PRIVATE in prop.modifiers) return Eligibility.Excluded
        // PERSISTENCE_IGNORE_FQN routed through resolver so cross-module @PersistenceIgnore
        // on VALUE_PARAMETER is visible.
        if (resolvePersistenceAnnotations(prop, ctorParam).has(PERSISTENCE_IGNORE_FQN)) return Eligibility.Excluded
        // Check annotation FQNs. When an annotation's FQN cannot be resolved this round, defer the
        // entity rather than silently missing the annotation (prevents false-positive column emission
        // for unresolved exclusion annotations).
        for (annotation in prop.annotations) {
            val fqn =
                annotation.annotationType.resolve().declaration.qualifiedName?.asString()
                    ?: return Eligibility.Deferred(enclosingClass, unresolvedAnnotation = true)
            when (fqn) {
                AGGREGATE_ANNOTATION_FQN,
                TRANSIENT_FQN,
                KOTLINX_SERIALIZATION_TRANSIENT_FQN -> return Eligibility.Excluded
            }
        }
        // Exclude computed properties (no backing field, not delegated), but include delegate-backed
        // properties — a body-declared `var x by reactiveProperty(...)` is delegated and persistable.
        if (!prop.hasBackingField && !prop.isDelegated()) return Eligibility.Excluded
        return ELIGIBLE_SENTINEL
    }

    private companion object {
        // Sentinel returned by checkEligibility to signal "property is eligible; proceed to
        // resolve the actual ColumnMeta". Callers must branch on `is Eligibility.Excluded` and
        // `is Eligibility.Deferred` and treat the Column case as "proceed" — the sentinel payload
        // is never forwarded; the real ColumnMeta is built by the calling builder method.
        val ELIGIBLE_SENTINEL =
            Eligibility.Column(
                ColumnMeta(
                    name = "", propertyName = "", typeExpression = "", typeFqn = "",
                    nullable = false, isPrimaryKey = false
                )
            )
    }

    fun columnNameFor(persistenceAnnotation: KSAnnotation?, propName: String): String {
        if (persistenceAnnotation == null) return propName.toSnakeCase()
        val customName = persistenceAnnotation.arguments.firstOrNull { it.name?.asString() == "name" }?.value as? String
        return if (!customName.isNullOrEmpty()) customName else propName.toSnakeCase()
    }

    fun mapToColumnTypeExpression(
        prop: KSPropertyDeclaration,
        persistenceAnnotation: KSAnnotation?
    ): String? {
        val resolvedType = prop.type.resolve()
        val notNullableType = resolvedType.makeNotNullable()
        val fqn = notNullableType.declaration.qualifiedName?.asString()

        val hints = extractHints(persistenceAnnotation)
        val length = hints.length
        val precision = hints.precision
        val scale = hints.scale
        val typeHint = hints.typeHint

        // Explicit type hint takes precedence over FQN-based inference
        if (typeHint.isNotEmpty()) {
            return mapTypeHintToExpression(typeHint, length, precision, scale, prop.simpleName.asString())
        }

        // Short and Byte map to IntType (no dedicated ColumnType variant). SQLite stores all
        // integer affinities as INTEGER regardless of declared width, so introducing SMALLINT /
        // TINYINT variants would force dialect-specific SQL generation for marginal benefit.
        // The Kotlin side narrows on read (`Int.toShort()` / `Int.toByte()`) and widens on write
        // (`Short.toInt()` / `Byte.toInt()`) — see buildRowAccess and buildEntityAccess.
        return when (fqn) {
            KOTLIN_INT_FQN -> COLUMN_TYPE_INT_EXPR
            KOTLIN_SHORT_FQN -> COLUMN_TYPE_INT_EXPR
            KOTLIN_BYTE_FQN -> COLUMN_TYPE_INT_EXPR
            KOTLIN_LONG_FQN -> COLUMN_TYPE_LONG_EXPR
            KOTLIN_STRING_FQN -> if (length > 0) "ColumnType.VarcharType($length)" else COLUMN_TYPE_TEXT_EXPR
            KOTLIN_BOOLEAN_FQN -> COLUMN_TYPE_BOOLEAN_EXPR
            KOTLIN_DOUBLE_FQN -> COLUMN_TYPE_DOUBLE_EXPR
            KOTLIN_FLOAT_FQN -> COLUMN_TYPE_FLOAT_EXPR
            UUID_FQN -> COLUMN_TYPE_UUID_EXPR
            LOCAL_DATE_TIME_FQN -> COLUMN_TYPE_DATETIME_EXPR
            LOCAL_DATE_FQN -> COLUMN_TYPE_DATE_EXPR
            BIG_DECIMAL_FQN -> {
                val p = if (precision > 0) precision else 19
                val s = if (scale >= 0) scale else 2
                "ColumnType.DecimalType($p, $s)"
            }
            else -> {
                val declaration = notNullableType.declaration
                if ((declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS) {
                    "ColumnType.EnumType(\"$fqn\")"
                } else {
                    logger.error("Unsupported column type '$fqn' on property '${prop.simpleName.asString()}' — cannot map to ColumnType")
                    null
                }
            }
        }
    }

    /**
     * Resolves the generated `typeExpression` for a converter-bearing column.
     *
     * The converter's declared `sqlType` (resolved at KSP time via `converterSqlFqn`) is the base.
     * `@PersistenceProperty` hints layer on top:
     *  - non-empty `type=...` always wins (explicit consumer intent — delegates to the shared
     *    [mapTypeHintToExpression] helper used by non-converter columns);
     *  - `length` on a `TextType` base refines to `VarcharType(length)`;
     *  - `precision`/`scale` on a numeric base (Int/Short/Byte/Long/Double/Float/BigDecimal) refines
     *    to `DecimalType(precision, scale)` — overrides the converter's declared precision/scale
     *    when both are present;
     *  - any other base + any hint set is rejected with a KSP error naming the property, the
     *    converter, and the resolved sqlType.
     *
     * Returns null when an incompatible hint was diagnosed; in that case the caller drops the
     * column so codegen does not emit a malformed file.
     */
    fun refineConverterSqlType(
        converterInfo: ConverterInfo,
        hints: PersistencePropertyHints,
        propertyFqn: String,
        propName: String
    ): String? {
        val converterFqn = converterInfo.converterFqn
        val converterSqlFqn = converterInfo.sqlTypeFqn
        val length = hints.length
        val precision = hints.precision
        val scale = hints.scale
        // Non-empty `type = "..."` is explicit consumer intent — delegate to the existing hint
        // mapper so converter columns honor the same vocabulary (TEXT/VARCHAR/INT/...) as
        // non-converter columns.
        if (hints.typeHint.isNotEmpty()) {
            return mapTypeHintToExpression(hints.typeHint, length, precision, scale, propName)
        }

        val baseExpression = "$converterFqn.sqlType"
        val hasLength = length > 0
        val hasPrecisionOrScale = precision > 0 || scale >= 0
        if (!hasLength && !hasPrecisionOrScale) return baseExpression

        val resolvedBase = SUPPORTED_CONVERTER_S_TYPES[converterSqlFqn]
        return when (converterSqlFqn) {
            KOTLIN_STRING_FQN -> {
                if (hasPrecisionOrScale) {
                    logger.error(
                        "@PersistenceProperty hint precision/scale on property '$propertyFqn' is " +
                            "incompatible with converter '$converterFqn' whose sqlType resolves to " +
                            "$resolvedBase. Remove the hint or pick a converter with a numeric sqlType."
                    )
                    return null
                }
                "ColumnType.VarcharType($length)"
            }
            KOTLIN_INT_FQN, KOTLIN_SHORT_FQN, KOTLIN_BYTE_FQN, KOTLIN_LONG_FQN,
            KOTLIN_DOUBLE_FQN, KOTLIN_FLOAT_FQN, BIG_DECIMAL_FQN -> {
                if (hasLength) {
                    logger.error(
                        "@PersistenceProperty hint length on property '$propertyFqn' is " +
                            "incompatible with converter '$converterFqn' whose sqlType resolves to " +
                            "$resolvedBase. Remove the hint or pick a converter with a textual sqlType."
                    )
                    return null
                }
                val p = if (precision > 0) precision else 19
                val s = if (scale >= 0) scale else 2
                "ColumnType.DecimalType($p, $s)"
            }
            else -> {
                logger.error(
                    "@PersistenceProperty hints (length/precision/scale) on property '$propertyFqn' are " +
                        "incompatible with converter '$converterFqn' whose sqlType resolves to " +
                        "$resolvedBase. Remove the hint or pick a converter with a compatible sqlType."
                )
                null
            }
        }
    }

    /**
     * Tests whether the `converter` argument of `@PersistenceProperty` resolves to a non-sentinel
     * class declaration. The sentinel [ColumnConverter][net.transgressoft.lirp.persistence.ColumnConverter]
     * interface FQN means "no converter declared"; any other class triggers /
     * validation downstream.
     */
    fun hasNonSentinelConverterArgument(annotation: KSAnnotation?): Boolean {
        if (annotation == null) return false
        val converterArg = annotation.arguments.firstOrNull { it.name?.asString() == "converter" }?.value as? KSType ?: return false
        val converterFqn = converterArg.declaration.qualifiedName?.asString() ?: return false
        return converterFqn != COLUMN_CONVERTER_FQN
    }

    /**
     * Reads the `converter` argument from a `@PersistenceProperty` annotation and validates
     * the referenced [ColumnConverter][net.transgressoft.lirp.persistence.ColumnConverter]
     * singleton against the structural contract (object kind, supported S type).
     *
     * Returns null when no converter is declared (sentinel), when the converter cannot yet
     * be resolved in this round (validate() guard so cross-round resolution does not produce
     * spurious diagnostics), or when validation fails after a diagnostic has been logged.
     */
    fun resolveConverter(annotation: KSAnnotation?, propertyFqn: String): ConverterInfo? {
        if (annotation == null) return null
        val converterArg = annotation.arguments.firstOrNull { it.name?.asString() == "converter" }?.value as? KSType ?: return null
        val converterDecl = converterArg.declaration as? KSClassDeclaration ?: return null
        val converterFqn = converterDecl.qualifiedName?.asString() ?: return null

        // Sentinel: the interface itself means "no converter declared" — silent skip.
        if (converterFqn == COLUMN_CONVERTER_FQN) return null

        // Defer to a later KSP round when the converter type cannot yet be fully resolved.
        if (!converterDecl.validate()) return null

        if (converterDecl.classKind != ClassKind.OBJECT) {
            logger.error(
                "Converter '$converterFqn' for property '$propertyFqn' must be a Kotlin `object` " +
                    "(singleton) so KSP-generated code can reference it without instantiation."
            )
            return null
        }

        val columnConverterSuperType =
            converterDecl.superTypes
                .map { it.resolve() }
                .firstOrNull { it.declaration.qualifiedName?.asString() == COLUMN_CONVERTER_FQN }

        // S is the SECOND type argument of ColumnConverter<D, S> (index 1, not 0).
        val sTypeArg = columnConverterSuperType?.arguments?.getOrNull(1)?.type?.resolve()
        val sFqn = sTypeArg?.declaration?.qualifiedName?.asString()

        if (columnConverterSuperType == null || sFqn == null) {
            logger.error(
                "Converter '$converterFqn' does not declare ColumnConverter<D, S> as a supertype " +
                    "with both type arguments resolved."
            )
            return null
        }

        if (sFqn !in SUPPORTED_CONVERTER_S_TYPES) {
            logger.error(
                "Converter '$converterFqn' declares S=$sFqn which is not supported. Supported types: " +
                    "kotlin.String, kotlin.Int, kotlin.Long, kotlin.Short, kotlin.Byte, kotlin.Boolean, " +
                    "kotlin.Double, kotlin.Float, java.math.BigDecimal, java.util.UUID, " +
                    "java.time.LocalDate, java.time.LocalDateTime."
            )
            return null
        }

        return ConverterInfo(converterFqn = converterFqn, sqlTypeFqn = sFqn)
    }

    // Mutable for SqlTableDef fromRow purposes means: var property with a public setter.
    private fun hasPublicSetter(prop: KSPropertyDeclaration): Boolean =
        prop.setter?.modifiers?.none {
            it == Modifier.PROTECTED || it == Modifier.PRIVATE || it == Modifier.INTERNAL
        } ?: true

    private fun extractHints(annotation: KSAnnotation?): PersistencePropertyHints =
        PersistencePropertyHints(
            length = annotation?.arguments?.firstOrNull { it.name?.asString() == "length" }?.value as? Int ?: -1,
            precision = annotation?.arguments?.firstOrNull { it.name?.asString() == "precision" }?.value as? Int ?: -1,
            scale = annotation?.arguments?.firstOrNull { it.name?.asString() == "scale" }?.value as? Int ?: -1,
            typeHint = annotation?.arguments?.firstOrNull { it.name?.asString() == "type" }?.value as? String ?: ""
        )

    private fun mapTypeHintToExpression(
        hint: String,
        length: Int,
        precision: Int,
        scale: Int,
        propName: String
    ): String? =
        when (hint.uppercase()) {
            "TEXT" -> COLUMN_TYPE_TEXT_EXPR
            "VARCHAR" -> {
                if (length <= 0) {
                    logger.error("@PersistenceProperty(type=\"VARCHAR\") requires length > 0 on property '$propName'")
                    return null
                }
                "ColumnType.VarcharType($length)"
            }
            "INT" -> COLUMN_TYPE_INT_EXPR
            "BIGINT" -> COLUMN_TYPE_LONG_EXPR
            "BOOLEAN" -> COLUMN_TYPE_BOOLEAN_EXPR
            "DOUBLE" -> COLUMN_TYPE_DOUBLE_EXPR
            "FLOAT" -> COLUMN_TYPE_FLOAT_EXPR
            "UUID" -> COLUMN_TYPE_UUID_EXPR
            "DATE" -> COLUMN_TYPE_DATE_EXPR
            "DATETIME" -> COLUMN_TYPE_DATETIME_EXPR
            "DECIMAL" -> {
                val p = if (precision > 0) precision else 19
                val s = if (scale >= 0) scale else 2
                "ColumnType.DecimalType($p, $s)"
            }
            else -> {
                logger.error("Unknown @PersistenceProperty type hint '$hint' on property '$propName'")
                null
            }
        }
}