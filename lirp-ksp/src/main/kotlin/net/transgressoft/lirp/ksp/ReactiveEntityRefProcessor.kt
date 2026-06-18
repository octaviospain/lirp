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

package net.transgressoft.lirp.ksp

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.validate
import java.io.File
import java.io.OutputStream

private const val TO_MANY_AGGREGATES_ANNOTATION_FQN = "net.transgressoft.lirp.persistence.ToManyAggregates"
private const val TO_ONE_AGGREGATE_ANNOTATION_FQN = "net.transgressoft.lirp.persistence.ToOneAggregate"
private const val PERSISTENCE_MAPPING_FQN_REF = "net.transgressoft.lirp.persistence.PersistenceMapping"
private const val REACTIVE_ENTITY_REFERENCE_FQN = "net.transgressoft.lirp.persistence.ReactiveEntityReference"
private const val AGGREGATE_COLLECTION_REF_FQN = "net.transgressoft.lirp.persistence.AggregateCollectionRef"
private const val AGGREGATE_LIST_REF_DELEGATE_FQN = "net.transgressoft.lirp.persistence.AggregateListRefDelegate"
private const val AGGREGATE_SET_REF_DELEGATE_FQN = "net.transgressoft.lirp.persistence.AggregateSetRefDelegate"

/** Separator between generated `RefEntry` / `CollectionRefEntry` entries inside a `listOf(...)` block. */
private const val ENTRY_SEPARATOR = ",\n        "
private val STDLIB_COLLECTION_FQNS =
    setOf(
        "kotlin.collections.MutableList",
        "kotlin.collections.MutableSet",
        "kotlin.collections.List",
        "kotlin.collections.Set"
    )

/**
 * Matches one `arm<K, E>("label") { scalar }` call in source text. Shares the canonical arm grammar
 * with [TableDefProcessor.ARM_REGEX] (positional or named cascade, `this.`-qualified scalar paths).
 * Capture groups: 1 = K type name, 2 = E type name, 3 = label, 4 = onDelete value (empty → DETACH),
 * 5 = scalar reference path.
 */
private val POLYMORPHIC_ARM_REGEX = buildArmRegex()

/**
 * KSP processor that generates [LirpRefAccessor][net.transgressoft.lirp.persistence.LirpRefAccessor]
 * implementations for entity classes containing
 * [@ToManyAggregates][net.transgressoft.lirp.persistence.ToManyAggregates] or
 * [@ToOneAggregate][net.transgressoft.lirp.persistence.ToOneAggregate] properties.
 *
 * For each entity class, a `{ClassName}_LirpRefAccessor` is generated in the same package, providing:
 * - Direct ID getter lambdas (`idGetter`) via the delegate's `referenceId` property, for single-entity references
 * - Direct IDs getter lambdas (`idsGetter`) via the delegate's `referenceIds` property, for collection references
 * - Direct delegate getter lambdas (`delegateGetter`) for both single and collection references
 * - A `cancelAllBubbleUp` override that cancels all bubble-up subscriptions without any reflection
 * - `collectionEntries` populated with [CollectionRefEntry][net.transgressoft.lirp.persistence.CollectionRefEntry]
 *   instances for all collection-typed `@ToManyAggregates` properties
 *
 * Annotation vocabulary:
 * - `@ToManyAggregates` — collection-only (`aggregateList`/`aggregateSet` delegates). Placing it on a
 *   non-collection property is a compile error; use `@ToOneAggregate` instead.
 * - `@ToOneAggregate` — two supported forms:
 *   1. Scalar FK: `@ToOneAggregate(target = X::class) var xId: K` — generates a navigation extension
 *      accessor and reads the FK scalar directly.
 *   2. Delegate-val: `@ToOneAggregate(target = X::class) val x by aggregate<K, X> { ... }` — uses the
 *      existing delegate directly; no extension accessor is generated; no `Id` suffix is required.
 *
 * Type resolution handles type aliases and intermediate interfaces: if a property's declared type
 * is a type alias or an intermediate interface (not `ReactiveEntityReference` directly), the processor
 * recursively walks the supertype chain to find the `ReactiveEntityReference<K, E>` supertype and
 * extracts `E` from its first type argument.
 *
 * Collection reference detection uses source-text scanning of the property declaration for known
 * aggregate factory calls (`mutableAggregateList`, `mutableAggregateSet`, `aggregateList`,
 * `aggregateSet`) as the primary mechanism. This is required because the factory return types are
 * `MutableList<E>`/`List<E>`/`MutableSet<E>`/`Set<E>` — stdlib types that do not have
 * `AggregateCollectionRef` in their supertype chain. A supertype-walk fallback handles direct
 * delegate usage. Whether the reference is ordered (List) or unordered (Set) is also determined
 * by source-text scanning for the factory call name.
 *
 * For collection-typed properties, `bubbleUp = true` is rejected with a compile error since
 * collection references do not support event propagation.
 */
class ReactiveEntityRefProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(TO_MANY_AGGREGATES_ANNOTATION_FQN)
        val unableToProcess = mutableListOf<KSAnnotated>()

        val classToProperties = mutableMapOf<KSClassDeclaration, MutableList<KSPropertyDeclaration>>()

        for (symbol in symbols) {
            if (symbol !is KSPropertyDeclaration) continue
            val parent = symbol.parentDeclaration as? KSClassDeclaration ?: continue
            if (!parent.validate()) {
                unableToProcess.add(symbol)
                continue
            }
            classToProperties.getOrPut(parent) { mutableListOf() }.add(symbol)
        }

        // Collect @ToOneAggregate-annotated scalar properties per class.
        val toOnePropertiesByClass = mutableMapOf<KSClassDeclaration, MutableList<KSPropertyDeclaration>>()
        for (symbol in resolver.getSymbolsWithAnnotation(TO_ONE_AGGREGATE_ANNOTATION_FQN)) {
            if (symbol !is KSPropertyDeclaration) continue
            val parent = symbol.parentDeclaration as? KSClassDeclaration ?: continue
            if (!parent.validate()) {
                unableToProcess.add(symbol)
                continue
            }
            classToProperties.getOrPut(parent) { mutableListOf() }
            toOnePropertiesByClass.getOrPut(parent) { mutableListOf() }.add(symbol)
        }

        // Collect classes that have polymorphicAggregate() properties but no @ToOneAggregate/@ToManyAggregates annotations.
        // These classes need a _LirpRefAccessor generated even though they have no annotated properties.
        for (classDecl in classToProperties.keys.toSet() +
            collectPolymorphicAggregateClasses(resolver, classToProperties.keys, unableToProcess)) {
            val toManyProperties = classToProperties.getOrDefault(classDecl, mutableListOf())
            val toOneProperties = toOnePropertiesByClass.getOrDefault(classDecl, mutableListOf())
            generateAccessor(classDecl, toManyProperties, toOneProperties, resolver, unableToProcess)
        }

        return unableToProcess
    }

    /**
     * Scans all classes reachable from the current resolver's perspective for `polymorphicAggregate(`
     * declarations, returning the subset that have such properties but are not already in [knownClasses].
     * Only classes that are [KSAnnotated.validate]able are returned.
     */
    private fun collectPolymorphicAggregateClasses(
        resolver: Resolver,
        knownClasses: Set<KSClassDeclaration>,
        unableToProcess: MutableList<KSAnnotated>
    ): Set<KSClassDeclaration> {
        val result = mutableSetOf<KSClassDeclaration>()
        // Use the @PersistenceMapping-annotated classes as the universe to scan, since only mapped
        // entities need a _LirpRefAccessor. We enumerate them via the same FQN the TableDefProcessor uses.
        for (symbol in resolver.getSymbolsWithAnnotation(PERSISTENCE_MAPPING_FQN_REF)) {
            val classDecl = symbol as? KSClassDeclaration ?: continue
            if (classDecl in knownClasses) continue
            // Defer (do not silently skip) classes not yet resolvable so KSP runs another round once
            // their symbols are available, mirroring the @ReactiveEntityRef deferral path in process().
            if (!classDecl.validate()) {
                unableToProcess.add(classDecl)
                continue
            }
            // Quick check: does any property in this class contain polymorphicAggregate(?
            val hasPolymorphic =
                classDecl.getAllProperties().any { prop ->
                    val text = readSourceLines(prop, linesAfter = 1) ?: return@any false
                    text.contains(POLYMORPHIC_AGGREGATE_CALL)
                }
            if (hasPolymorphic) result.add(classDecl)
        }
        return result
    }

    /**
     * Classifies each `@ToOneAggregate` / `@ToManyAggregates`-annotated property as either a single-entity reference or a
     * collection reference, extracting the metadata required for code generation.
     */
    private fun classifyProperties(
        properties: List<KSPropertyDeclaration>,
        ownerDecl: KSClassDeclaration,
        collectionMetas: MutableList<CollectionRefPropertyMeta>
    ) {
        val className = ownerDecl.jvmBinaryName()
        for (prop in properties) {
            val annotation =
                prop.annotations.firstOrNull {
                    it.annotationType.resolve().declaration.qualifiedName?.asString() == TO_MANY_AGGREGATES_ANNOTATION_FQN
                } ?: continue

            val bubbleUp = annotation.arguments.firstOrNull { it.name?.asString() == "bubbleUp" }?.value as? Boolean ?: false
            val onDeleteArg = annotation.arguments.firstOrNull { it.name?.asString() == "onDelete" }
            val onDeleteExplicitInSource = isOnDeleteExplicitInSource(prop)
            val explicitOnDeleteName = if (onDeleteArg != null) extractCascadeActionName(onDeleteArg.value) else null

            val resolvedType = prop.type.resolve()

            if (isCollectionReference(prop, resolvedType)) {
                classifyCollectionProperty(prop, className, bubbleUp, onDeleteExplicitInSource, explicitOnDeleteName, resolvedType, collectionMetas)
            } else {
                logger.error(
                    "@ToManyAggregates must be placed on a collection-typed property (aggregateList / aggregateSet). " +
                        "For a single-entity delegate reference, use @ToOneAggregate(target = ...) instead. " +
                        "Property: '${prop.simpleName.asString()}' in $className",
                    prop
                )
            }
        }
    }

    private fun classifyCollectionProperty(
        prop: KSPropertyDeclaration,
        className: String,
        bubbleUp: Boolean,
        onDeleteExplicitInSource: Boolean,
        explicitOnDeleteName: String?,
        resolvedType: KSType,
        collectionMetas: MutableList<CollectionRefPropertyMeta>
    ) {
        if (bubbleUp) {
            logger.error(
                "bubbleUp = true is not supported on collection-typed @ToManyAggregates properties. " +
                    "Only single refs support bubble-up propagation. " +
                    "Property: '${prop.simpleName.asString()}' in $className"
            )
            return
        }

        val cascadeActionName = if (onDeleteExplicitInSource) explicitOnDeleteName ?: "NONE" else "NONE"

        val referencedClassFqn =
            findReferencedClassFqnFromCollectionType(resolvedType)
                ?: run {
                    logger.warn("Cannot determine referenced class for collection property '${prop.simpleName.asString()}' in $className — skipping")
                    return
                }

        collectionMetas.add(
            CollectionRefPropertyMeta(
                refName = prop.simpleName.asString(),
                propertyName = prop.simpleName.asString(),
                referencedClassFqn = referencedClassFqn,
                cascadeAction = cascadeActionName,
                isOrdered = isOrderedCollectionDelegate(prop)
            )
        )
    }

    private fun classifyToOneProperties(
        properties: List<KSPropertyDeclaration>,
        ownerDecl: KSClassDeclaration,
        toOneEntries: MutableList<ToOneRefPropertyMeta>
    ) {
        val className = ownerDecl.jvmBinaryName()
        for (prop in properties) {
            classifyToOneProperty(prop, className, ownerDecl, toOneEntries)
        }
    }

    /**
     * Validates a single [@ToOneAggregate][net.transgressoft.lirp.persistence.ToOneAggregate]-annotated
     * property and, if valid, adds a [ToOneRefPropertyMeta] to [toOneEntries].
     *
     * Emits a compile error for each of the four misuse conditions, returning early without adding
     * a metadata entry so that downstream code generation does not run for invalid annotations.
     */
    private fun classifyToOneProperty(
        prop: KSPropertyDeclaration,
        className: String,
        ownerDecl: KSClassDeclaration,
        toOneEntries: MutableList<ToOneRefPropertyMeta>
    ) {
        val propName = prop.simpleName.asString()
        val annotation =
            prop.annotations.firstOrNull {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == TO_ONE_AGGREGATE_ANNOTATION_FQN
            } ?: return

        // Detect delegate-val form early — before @PersistenceMapping check — because delegate-val
        // targets do not need to carry @PersistenceMapping (they are plain reactive entities bound
        // at runtime via the existing delegate). Source-text scanning is required because KSP type
        // resolution resolves `val x by aggregate<K, X> { ... }` to AggregateRefDelegate, and we
        // cannot distinguish it from a scalar FK by type alone at this point.
        val sourceText = readSourceLines(prop, linesBefore = 0, linesAfter = 2)
        val isDelegateVal =
            sourceText != null &&
                (
                    sourceText.contains("aggregate {") ||
                        sourceText.contains("aggregate<") ||
                        sourceText.contains("optionalAggregate") ||
                        sourceText.contains("mutableAggregate{") ||
                        sourceText.contains("mutableAggregate<")
                )

        // Extract target: KClass<*> annotation argument via the confirmed KSType cast pattern.
        val targetArg =
            annotation.arguments.firstOrNull { it.name?.asString() == "target" }?.value as? KSType
                ?: run {
                    logger.error("@ToOneAggregate missing required 'target' argument on '$className.$propName'", prop)
                    return
                }
        val targetDecl =
            targetArg.declaration as? KSClassDeclaration
                ?: run {
                    logger.error("@ToOneAggregate 'target' must be a class on '$className.$propName'", prop)
                    return
                }

        if (isDelegateVal) {
            addDelegateValToOneEntry(prop, className, propName, annotation, targetDecl, toOneEntries)
        } else {
            addScalarFkToOneEntry(prop, className, propName, annotation, targetDecl, ownerDecl, toOneEntries)
        }
    }

    /**
     * Handles the delegate-val form `@ToOneAggregate(target = X::class) val x by aggregate<K, X> { ... }`:
     * no `Id` suffix required, no extension accessor emitted, and the generated `RefEntry` reads the id
     * via the existing delegate. Validates that the annotation `target` agrees with the entity type
     * carried by the delegate's type arguments before adding a [ToOneRefPropertyMeta] to [toOneEntries];
     * a mismatch is reported and no entry is added.
     */
    private fun addDelegateValToOneEntry(
        prop: KSPropertyDeclaration,
        className: String,
        propName: String,
        annotation: KSAnnotation,
        targetDecl: KSClassDeclaration,
        toOneEntries: MutableList<ToOneRefPropertyMeta>
    ) {
        // Validate that the declared target matches the actual entity type carried by the delegate's
        // type arguments (e.g. aggregate<K, X> must agree with target = X::class). A mismatch means
        // the RefEntry would silently cascade/bubble-up to the wrong entity class.
        val delegateEntityFqn = findReferencedClassFqnFromType(prop.type.resolve())
        val annotationTargetFqn = targetDecl.qualifiedName?.asString() ?: targetDecl.simpleName.asString()
        if (delegateEntityFqn != null && delegateEntityFqn != annotationTargetFqn) {
            logger.error(
                "@ToOneAggregate 'target' '$annotationTargetFqn' does not match the delegate's entity type " +
                    "'$delegateEntityFqn' on '$className.$propName'. " +
                    "Align target with the type argument of aggregate<K, E>.",
                prop
            )
            return
        }

        val bubbleUp = annotation.arguments.firstOrNull { it.name?.asString() == "bubbleUp" }?.value as? Boolean ?: false
        val onDeleteArg = annotation.arguments.firstOrNull { it.name?.asString() == "onDelete" }
        val cascadeActionName = if (onDeleteArg != null) extractCascadeActionName(onDeleteArg.value) else "DETACH"

        toOneEntries.add(
            ToOneRefPropertyMeta(
                scalarName = propName,
                accessorName = propName,
                referencedClassFqn = annotationTargetFqn,
                bubbleUp = bubbleUp,
                cascadeAction = cascadeActionName,
                isOptional = false,
                isDelegateVal = true
            )
        )
    }

    /**
     * Handles the scalar-FK form `@ToOneAggregate(target = X::class) var xId: K`. Runs the four
     * scalar-only diagnostics — target must carry `@PersistenceMapping` and extend `ReactiveEntity`,
     * the property must not be collection-typed, the scalar base type must match the target PK type,
     * and the scalar must end with `Id` without colliding with an existing `@ToManyAggregates`
     * accessor — emitting a compile error and returning without an entry on the first violation.
     */
    private fun addScalarFkToOneEntry(
        prop: KSPropertyDeclaration,
        className: String,
        propName: String,
        annotation: KSAnnotation,
        targetDecl: KSClassDeclaration,
        ownerDecl: KSClassDeclaration,
        toOneEntries: MutableList<ToOneRefPropertyMeta>
    ) {
        // Diagnostic (a): target must carry @PersistenceMapping and extend ReactiveEntity.
        val hasPersistenceMapping =
            targetDecl.annotations.any {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == PERSISTENCE_MAPPING_FQN_REF
            }
        if (!hasPersistenceMapping || !isLirpEntity(targetDecl)) {
            logger.error(
                "@ToOneAggregate 'target' '${targetDecl.simpleName.asString()}' must be annotated with " +
                    "@PersistenceMapping and extend ReactiveEntity. '$className.$propName'",
                prop
            )
            return
        }

        val scalarType = prop.type.resolve()

        // Diagnostic (c): must not be placed on a collection-typed property.
        val resolvedFqn = scalarType.makeNotNullable().declaration.qualifiedName?.asString()
        val isCollectionType =
            resolvedFqn?.let { fqn ->
                fqn.startsWith("kotlin.collections.") && (fqn.contains("List") || fqn.contains("Set"))
            } ?: false

        // Reject collection-typed property — use @ToManyAggregates instead.
        if (isCollectionType) {
            logger.error(
                "@ToOneAggregate must not be placed on a collection-typed property. " +
                    "Use @ToManyAggregates for collection references. '$className.$propName'",
                prop
            )
            return
        }

        // Diagnostic (b): scalar non-null base type must match target entity's PK type.
        val scalarNonNullFqn = scalarType.makeNotNullable().declaration.qualifiedName?.asString()
        val targetIdProp = targetDecl.getAllProperties().firstOrNull { it.simpleName.asString() == "id" }
        val targetPkFqn = targetIdProp?.type?.resolve()?.makeNotNullable()?.declaration?.qualifiedName?.asString()
        if (scalarNonNullFqn != null && targetPkFqn != null && scalarNonNullFqn != targetPkFqn) {
            logger.error(
                "@ToOneAggregate scalar type '$scalarNonNullFqn' does not match target PK type '$targetPkFqn'. " +
                    "Fix the scalar type for '$className.$propName'.",
                prop
            )
            return
        }

        // Diagnostic (d): scalar must end with 'Id' and the derived accessor name must not collide
        // with an existing @ToManyAggregates property on the same class.
        val accessorName = scalarToAccessorName(propName)
        if (accessorName == null) {
            logger.error(
                "@ToOneAggregate on '$className.$propName': scalar name has no 'Id' suffix. " +
                    "Rename the property to end with 'Id' (e.g. '${propName}Id') for accessor-name derivation.",
                prop
            )
            return
        }
        val collision =
            ownerDecl.getAllProperties().any { p ->
                p.simpleName.asString() == accessorName &&
                    p.annotations.any { ann ->
                        ann.annotationType.resolve().declaration.qualifiedName?.asString() == TO_MANY_AGGREGATES_ANNOTATION_FQN
                    }
            }
        if (collision) {
            logger.error(
                "@ToOneAggregate on '$className.$propName' conflicts with an existing @ToManyAggregates " +
                    "property '$accessorName' on the same class. Remove the hand-written aggregate companion.",
                prop
            )
            return
        }

        val bubbleUp = annotation.arguments.firstOrNull { it.name?.asString() == "bubbleUp" }?.value as? Boolean ?: false
        val onDeleteArg = annotation.arguments.firstOrNull { it.name?.asString() == "onDelete" }
        val cascadeActionName = if (onDeleteArg != null) extractCascadeActionName(onDeleteArg.value) else "DETACH"
        val targetFqn = targetDecl.qualifiedName?.asString() ?: targetDecl.simpleName.asString()
        val isOptional = scalarType.isMarkedNullable

        toOneEntries.add(
            ToOneRefPropertyMeta(
                scalarName = propName,
                accessorName = accessorName,
                referencedClassFqn = targetFqn,
                bubbleUp = bubbleUp,
                cascadeAction = cascadeActionName,
                isOptional = isOptional
            )
        )
    }

    /**
     * Derives the navigation accessor name from a FK scalar name by stripping the trailing `Id` suffix.
     * Returns `null` when the scalar name does not end with `Id`, or when the name consists only of
     * `"Id"` (which would yield an empty accessor name and produce invalid generated code). Both cases
     * trigger diagnostic (d).
     */
    private fun scalarToAccessorName(scalarName: String): String? {
        if (!scalarName.endsWith("Id")) return null
        return scalarName.removeSuffix("Id").takeIf { it.isNotEmpty() }
    }

    /**
     * Scans all properties on [classDecl] for `polymorphicAggregate(` declarations and adds one
     * [PolymorphicArmRefMeta] per arm to [result]. Arm target types are resolved from the file's
     * import list.
     *
     * When a property has an arm whose target type is not yet resolvable, that property is deferred:
     * its partial arms are dropped from [result] and the property is added to [unableToProcess] so KSP
     * runs another round. Returns `true` if any property on the class was deferred, so the caller can
     * skip emitting an incomplete accessor — mirroring the all-or-defer behaviour of `TableDefProcessor`
     * and preventing the generated RefEntry list from drifting from the table definition's foreign keys.
     */
    private fun collectPolymorphicArmMetas(
        classDecl: KSClassDeclaration,
        resolver: Resolver,
        result: MutableList<PolymorphicArmRefMeta>,
        unableToProcess: MutableList<KSAnnotated>
    ): Boolean {
        var deferred = false
        for (prop in classDecl.getAllProperties()) {
            val quickText = readSourceLines(prop, linesAfter = 1) ?: continue
            if (!quickText.contains(POLYMORPHIC_AGGREGATE_CALL)) continue
            // Read to end of file so the balanced-paren walk always reaches the closing ')',
            // regardless of arm count or formatting.
            val fullText = readSourceFromProperty(prop) ?: continue
            val boundedText = extractPolymorphicAggregateSpan(fullText)
            val propName = prop.simpleName.asString()
            val className = classDecl.qualifiedName?.asString() ?: classDecl.simpleName.asString()
            // Fail loud on arm declarations the regex cannot parse so they are not silently dropped
            // from cascade-wiring metadata while the runtime delegate keeps them.
            val unparseable = unparseableArmCalls(boundedText, POLYMORPHIC_ARM_REGEX)
            if (unparseable.isNotEmpty()) {
                logger.error(
                    "Property '$className.$propName' has polymorphic arm declaration(s) the processor cannot parse: " +
                        unparseable.joinToString("; ") + ". Use arm<K, E>(\"label\"[, CascadeAction.X]) { scalar }.",
                    prop
                )
                continue
            }
            val armMatches = POLYMORPHIC_ARM_REGEX.findAll(boundedText).toList()
            if (!validatePolymorphicArmLabels(armMatches.map { it.groupValues[3] }, className, propName, prop)) continue
            val armsForProperty = buildArmsForProperty(armMatches, prop, propName, resolver, unableToProcess)
            if (armsForProperty == null) {
                deferred = true
                continue
            }
            result.addAll(armsForProperty)
        }
        return deferred
    }

    /**
     * Validates that the arm [labels] of a single polymorphic property are distinct and are legal
     * Kotlin identifiers — each label becomes a generated data class name. Emits a [KSPLogger.error]
     * and returns `false` on the first violation, `true` when all labels are valid.
     */
    private fun validatePolymorphicArmLabels(
        labels: List<String>,
        className: String,
        propName: String,
        prop: KSPropertyDeclaration
    ): Boolean {
        val duplicates = labels.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            logger.error("Property '$className.$propName' has duplicate polymorphic arm label(s): $duplicates.", prop)
            return false
        }
        val invalid = labels.filterNot { isValidArmLabel(it) }
        if (invalid.isNotEmpty()) {
            logger.error(
                "Property '$className.$propName' has polymorphic arm label(s) that are not valid Kotlin identifiers: " +
                    "$invalid. Labels become generated data class names and must match [A-Za-z_][A-Za-z_0-9]*.",
                prop
            )
            return false
        }
        return true
    }

    /**
     * Builds the [PolymorphicArmRefMeta] list for one polymorphic property from its [armMatches].
     * Returns `null` — and registers [prop] in [unableToProcess] — when any arm's target type is not
     * resolvable this round, so the caller defers the whole property rather than emitting a partial
     * arm set that would drift from the table definition's per-arm foreign keys.
     */
    private fun buildArmsForProperty(
        armMatches: List<MatchResult>,
        prop: KSPropertyDeclaration,
        propName: String,
        resolver: Resolver,
        unableToProcess: MutableList<KSAnnotated>
    ): List<PolymorphicArmRefMeta>? {
        val armsForProperty = mutableListOf<PolymorphicArmRefMeta>()
        for (m in armMatches) {
            val eTypeName = m.groupValues[2].trim()
            val label = m.groupValues[3]
            val onDelete = m.groupValues[4].takeIf { it.isNotEmpty() } ?: "DETACH"
            val scalarName = armScalarFromPath(m.groupValues[5])
            val referencedFqn =
                resolveArmTargetFqn(eTypeName, prop, resolver) ?: run {
                    unableToProcess.add(prop)
                    return null
                }
            armsForProperty.add(
                PolymorphicArmRefMeta(
                    refName = "$propName.$label",
                    polymorphicPropName = propName,
                    armLabel = label,
                    armScalarName = scalarName,
                    referencedClassFqn = referencedFqn,
                    cascadeAction = onDelete
                )
            )
        }
        return armsForProperty
    }

    /**
     * Resolves the FQN of an arm's target entity type from the source-level simple name by
     * parsing the containing file's import statements. Falls back to the entity's own package.
     * Returns `null` when the class is not resolvable in this round.
     */
    private fun resolveArmTargetFqn(
        eTypeName: String,
        prop: KSPropertyDeclaration,
        resolver: Resolver
    ): String? {
        val location = prop.location as? FileLocation ?: return null
        val sourceFile = File(location.filePath)
        if (sourceFile.exists()) {
            val packageName = prop.packageName.asString()
            val importFqn = resolveImportedFqn(sourceFile.readLines(), eTypeName, packageName)
            if (importFqn != null) {
                val decl = resolver.getClassDeclarationByName(resolver.getKSNameFromString(importFqn))
                if (decl != null) return importFqn
            }
        }
        val packageName = prop.packageName.asString()
        if (packageName.isNotEmpty()) {
            val fqn = "$packageName.$eTypeName"
            val decl = resolver.getClassDeclarationByName(resolver.getKSNameFromString(fqn))
            if (decl != null) return fqn
        }
        return null
    }

    /**
     * Extracts the source span of a `polymorphicAggregate(…)` call from the scanned text by
     * performing a balanced-parenthesis walk starting at the first `(` after `polymorphicAggregate`.
     */
    private fun extractPolymorphicAggregateSpan(text: String): String =
        extractBalancedCallSpan(text, POLYMORPHIC_AGGREGATE_CALL)

    private fun generateAccessor(
        classDecl: KSClassDeclaration,
        properties: List<KSPropertyDeclaration>,
        toOneProperties: List<KSPropertyDeclaration> = emptyList(),
        resolver: Resolver? = null,
        unableToProcess: MutableList<KSAnnotated> = mutableListOf()
    ) {
        val visibility = effectiveVisibilityModifier(classDecl)
        if (visibility == null) {
            val fqn = classDecl.qualifiedName?.asString() ?: classDecl.simpleName.asString()
            logger.error(
                "Entity '$fqn' must be public or internal to generate a persistence companion (_LirpRefAccessor). " +
                    "Private and protected entities cannot have accessible generated code.",
                classDecl
            )
            return
        }
        val packageName = classDecl.packageName.asString()
        val className = classDecl.jvmBinaryName()
        // Kotlin-level name for type references: uses dots for nesting (e.g. Outer.RefEntity)
        val kotlinClassName = classDecl.kotlinNestedName()
        val accessorName = "$className${LirpGenNames.REF_ACCESSOR_SUFFIX}"

        val singleEntries = emptyList<RefPropertyMeta>()
        val collectionMetas = mutableListOf<CollectionRefPropertyMeta>()
        val toOneEntries = mutableListOf<ToOneRefPropertyMeta>()
        classifyProperties(properties, classDecl, collectionMetas)
        classifyToOneProperties(toOneProperties, classDecl, toOneEntries)

        // Synthesise per-arm RefEntry metadata for polymorphicAggregate() properties. These carry no
        // aggregate annotation, so they are discovered via source-text scanning across all properties
        // on the entity class.
        val polymorphicArmEntries = mutableListOf<PolymorphicArmRefMeta>()
        if (resolver != null) {
            val deferred = collectPolymorphicArmMetas(classDecl, resolver, polymorphicArmEntries, unableToProcess)
            // A polymorphic property with an unresolved arm target was deferred to a later round; skip
            // emitting now so the accessor is regenerated once with its complete arm set.
            if (deferred) return
        }

        // Skip generation when there are no entries at all — avoids an empty accessor file.
        if (singleEntries.isEmpty() && toOneEntries.isEmpty() && collectionMetas.isEmpty() && polymorphicArmEntries.isEmpty()) return

        val file =
            codeGenerator.createNewFile(
                dependencies = Dependencies(false, classDecl.containingFile!!),
                packageName = packageName,
                fileName = accessorName
            )

        // Collect import statements for all referenced entity classes (single + to-one + collection + arms)
        val allReferencedFqns =
            (
                singleEntries.map { it.referencedClassFqn } +
                    toOneEntries.map { it.referencedClassFqn } +
                    collectionMetas.map { it.referencedClassFqn } +
                    polymorphicArmEntries.map { it.referencedClassFqn }
            )
                .distinct()
                .filter { it.contains('.') }
                .sorted()

        val entriesCode = buildSingleEntriesCode(singleEntries, polymorphicArmEntries, toOneEntries)
        val collectionEntriesCode = buildCollectionEntriesCode(collectionMetas)

        writeAccessorFile(
            file = file,
            packageName = packageName,
            className = className,
            kotlinClassName = kotlinClassName,
            accessorName = accessorName,
            visibility = visibility,
            allReferencedFqns = allReferencedFqns,
            entriesCode = entriesCode,
            collectionEntriesCode = collectionEntriesCode,
            collectionMetas = collectionMetas,
            toOneEntries = toOneEntries
        )

        logger.info("Generated $packageName.$accessorName for $className")

        // Emit extension accessor only for scalar @ToOneAggregate entries (not delegate-val — those already have the navigation val).
        val scalarToOneEntries = toOneEntries.filter { !it.isDelegateVal }
        if (scalarToOneEntries.isNotEmpty()) {
            emitToOneExtAccessor(classDecl, kotlinClassName, packageName, visibility, scalarToOneEntries)
        }
    }

    /**
     * Builds the combined `RefEntry` source for the single-valued `entries` list: standard single
     * refs, per-arm polymorphic refs, and `@ToOneAggregate` refs, in that order. Blank sections are
     * dropped so the joined output never contains an empty entry slot.
     */
    private fun buildSingleEntriesCode(
        singleEntries: List<RefPropertyMeta>,
        polymorphicArmEntries: List<PolymorphicArmRefMeta>,
        toOneEntries: List<ToOneRefPropertyMeta>
    ): String {
        // Build RefEntry named constructor calls with delegateGetter and typed idGetter.
        // Since AggregateRefDelegate.getValue() returns `this`, accessing `it.propName` at runtime
        // returns the delegate itself typed as ReactiveEntityReference<K, E>. Casting to
        // AggregateRefDelegate<*, *> is safe — the only aggregate<K,E> implementation is
        // AggregateRefDelegate. The UNCHECKED_CAST suppression is placed on each RefEntry call.
        val standardEntriesCode =
            singleEntries.joinToString(ENTRY_SEPARATOR) { meta ->
                val referencedSimpleName = meta.referencedClassFqn.substringAfterLast('.')
                """
                @Suppress("UNCHECKED_CAST")
                RefEntry(
                    refName = "${meta.refName}",
                    idGetter = { it.${meta.propertyName}.referenceId },
                    delegateGetter = { it.${meta.propertyName} as AggregateRefDelegate<*, *> },
                    referencedClass = $referencedSimpleName::class.java,
                    bubbleUp = ${meta.bubbleUp},
                    cascadeAction = CascadeAction.${meta.cascadeAction}
                )
                """.trimIndent()
            }

        // Per-arm RefEntry — idGetter uses the arm delegate's referenceId (throws if null, matching
        // the contract of non-optional aggregates). The arm delegate is reached via armDelegate(label)
        // and cast to AggregateRefDelegate<*, *>. Both casts are suppressed because the arm delegate
        // is always an AggregateRefDelegate and the K type is erased at this star-projection level.
        val armEntriesCode =
            polymorphicArmEntries.joinToString(ENTRY_SEPARATOR) { meta ->
                val referencedSimpleName = meta.referencedClassFqn.substringAfterLast('.')
                """
                @Suppress("UNCHECKED_CAST")
                RefEntry(
                    refName = "${meta.refName}",
                    idGetter = { it.${meta.polymorphicPropName}.armDelegate("${meta.armLabel}").referenceId as Comparable<Any> },
                    delegateGetter = { it.${meta.polymorphicPropName}.armDelegate("${meta.armLabel}") as AggregateRefDelegate<*, *> },
                    referencedClass = $referencedSimpleName::class.java,
                    bubbleUp = false,
                    cascadeAction = CascadeAction.${meta.cascadeAction}
                )
                """.trimIndent()
            }

        // @ToOneAggregate RefEntry — two forms:
        // - Delegate-val: idGetter reads referenceId from the existing delegate; delegateGetter casts directly.
        // - Scalar FK: idGetter reads the FK scalar; delegateGetter creates/retrieves via getOrComputeToOneRef.
        val toOneEntriesCode =
            toOneEntries.joinToString(ENTRY_SEPARATOR) { meta ->
                val referencedSimpleName = meta.referencedClassFqn.substringAfterLast('.')
                if (meta.isDelegateVal) {
                    """
                    @Suppress("UNCHECKED_CAST")
                    RefEntry(
                        refName = "${meta.accessorName}",
                        idGetter = { it.${meta.accessorName}.referenceId },
                        delegateGetter = { it.${meta.accessorName} as AggregateRefDelegate<*, *> },
                        referencedClass = $referencedSimpleName::class.java,
                        bubbleUp = ${meta.bubbleUp},
                        cascadeAction = CascadeAction.${meta.cascadeAction}
                    )
                    """.trimIndent()
                } else {
                    val castExpr =
                        if (meta.isOptional) {
                            "{ it.${meta.scalarName} as Comparable<Any>? }"
                        } else {
                            "{ it.${meta.scalarName} as Comparable<Any> }"
                        }
                    """
                    @Suppress("UNCHECKED_CAST")
                    RefEntry(
                        refName = "${meta.accessorName}",
                        idGetter = $castExpr,
                        delegateGetter = { it.getOrComputeToOneRef("${meta.accessorName}", ${meta.isOptional}) $castExpr as AggregateRefDelegate<*, *> },
                        referencedClass = $referencedSimpleName::class.java,
                        bubbleUp = ${meta.bubbleUp},
                        cascadeAction = CascadeAction.${meta.cascadeAction}
                    )
                    """.trimIndent()
                }
            }

        val allSingleEntryParts =
            buildList {
                if (standardEntriesCode.isNotBlank()) add(standardEntriesCode)
                if (armEntriesCode.isNotBlank()) add(armEntriesCode)
                if (toOneEntriesCode.isNotBlank()) add(toOneEntriesCode)
            }
        return allSingleEntryParts.joinToString(ENTRY_SEPARATOR)
    }

    /** Builds the `CollectionRefEntry` source for the `collectionEntries` list from [collectionMetas]. */
    private fun buildCollectionEntriesCode(collectionMetas: List<CollectionRefPropertyMeta>): String =
        collectionMetas.joinToString(ENTRY_SEPARATOR) { meta ->
            val referencedSimpleName = meta.referencedClassFqn.substringAfterLast('.')
            """
            @Suppress("UNCHECKED_CAST")
            CollectionRefEntry(
                refName = "${meta.refName}",
                idsGetter = { (it.${meta.propertyName} as AggregateCollectionRef<*, *>).referenceIds },
                delegateGetter = { it.${meta.propertyName} as AggregateCollectionRef<*, *> },
                referencedClass = $referencedSimpleName::class.java,
                cascadeAction = CascadeAction.${meta.cascadeAction},
                isOrdered = ${meta.isOrdered}
            )
            """.trimIndent()
        }

    /**
     * Writes the `_LirpRefAccessor` source: package, imports, the generated KDoc header, the
     * `entries` and `collectionEntries` properties (each `emptyList()` when its section is blank),
     * and the `cancelAllBubbleUp` override. The accessor-name set for [toOneEntries] scalar refs lets
     * `cancelAllBubbleUp` avoid allocating a to-one delegate that was never created.
     */
    private fun writeAccessorFile(
        file: OutputStream,
        packageName: String,
        className: String,
        kotlinClassName: String,
        accessorName: String,
        visibility: String,
        allReferencedFqns: List<String>,
        entriesCode: String,
        collectionEntriesCode: String,
        collectionMetas: List<CollectionRefPropertyMeta>,
        toOneEntries: List<ToOneRefPropertyMeta>
    ) {
        // Set of accessor names for scalar @ToOneAggregate entries — used in cancelAllBubbleUp to avoid
        // allocating a new delegate when none was created yet (e.g. entity closed before repo bind).
        // Delegate-val entries are excluded: their delegates are accessed directly without getOrComputeToOneRef.
        val toOneRefNames = toOneEntries.filter { !it.isDelegateVal }.joinToString(", ") { "\"${it.accessorName}\"" }
        val cancelAllBubbleUpCode =
            """
            override fun cancelAllBubbleUp(entity: $kotlinClassName) {
                val toOneRefNames = setOf<String>($toOneRefNames)
                entries.forEach { entry ->
                    if (entry.refName in toOneRefNames) {
                        // Only cancel on already-created to-one delegates; do not allocate during teardown.
                        entity.getExistingToOneRef(entry.refName)?.cancelBubbleUp()
                    } else {
                        entry.delegateGetter(entity).cancelBubbleUp()
                    }
                }
            }
            """.trimIndent()

        file.write(
            buildString {
                if (packageName.isNotEmpty()) {
                    appendLine("package $packageName")
                    appendLine()
                }
                appendLine("import net.transgressoft.lirp.entity.CascadeAction")
                appendLine("import net.transgressoft.lirp.persistence.AggregateRefDelegate")
                appendLine("import net.transgressoft.lirp.persistence.CollectionRefEntry")
                appendLine("import net.transgressoft.lirp.persistence.LirpRefAccessor")
                appendLine("import net.transgressoft.lirp.persistence.AggregateCollectionRef")
                appendLine("import net.transgressoft.lirp.persistence.RefEntry")
                for (importFqn in allReferencedFqns) {
                    appendLine("import $importFqn")
                }
                appendLine()
                appendLine("/**")
                appendLine(" * KSP-generated aggregate reference accessor for [$className].")
                appendLine(" * Provides direct ID getter and delegate getter lambdas — no runtime reflection.")
                appendLine(" */")
                appendLine("$visibility class `$accessorName` : LirpRefAccessor<$kotlinClassName> {")
                if (entriesCode.isBlank()) {
                    appendLine("    override val entries: List<RefEntry<*, $kotlinClassName>> = emptyList()")
                } else {
                    appendLine("    override val entries: List<RefEntry<*, $kotlinClassName>> = listOf(")
                    appendLine("        $entriesCode")
                    appendLine("    )")
                }
                appendLine()
                if (collectionMetas.isEmpty()) {
                    appendLine("    override val collectionEntries: List<CollectionRefEntry<*, $kotlinClassName>> = emptyList()")
                } else {
                    appendLine("    override val collectionEntries: List<CollectionRefEntry<*, $kotlinClassName>> = listOf(")
                    appendLine("        $collectionEntriesCode")
                    appendLine("    )")
                }
                appendLine()
                appendLine(cancelAllBubbleUpCode)
                appendLine("}")
            }.toByteArray()
        )
        file.close()
    }

    /**
     * Emits a `{ClassName}_LirpToOneExtAccessor.kt` file containing one Kotlin extension property
     * per `@ToOneAggregate` scalar. Each extension delegates to [getToOneRef], exposing the stored
     * [net.transgressoft.lirp.persistence.AggregateRefDelegate] as a
     * [net.transgressoft.lirp.persistence.ReactiveEntityReference] to consumers.
     *
     * The generated file lives in the same package as the entity so that it is automatically
     * visible from any module that imports the package — no explicit star-import required. The
     * return type is the concrete [net.transgressoft.lirp.persistence.AggregateRefDelegate] cast
     * to [net.transgressoft.lirp.persistence.ReactiveEntityReference], giving callers access to
     * `resolve()` and `referenceId` without further casting.
     */
    private fun emitToOneExtAccessor(
        classDecl: KSClassDeclaration,
        kotlinClassName: String,
        packageName: String,
        visibility: String,
        toOneEntries: List<ToOneRefPropertyMeta>
    ) {
        val className = classDecl.jvmBinaryName()
        val extFileName = "$className${LirpGenNames.TO_ONE_EXT_ACCESSOR_SUFFIX}"

        val extFile =
            codeGenerator.createNewFile(
                dependencies = Dependencies(false, classDecl.containingFile!!),
                packageName = packageName,
                fileName = extFileName
            )

        val importFqns =
            toOneEntries.map { it.referencedClassFqn }
                .distinct()
                .filter { it.contains('.') }
                .sorted()

        extFile.write(
            buildString {
                if (packageName.isNotEmpty()) {
                    appendLine("package $packageName")
                    appendLine()
                }
                appendLine("import net.transgressoft.lirp.persistence.AggregateRefDelegate")
                appendLine("import net.transgressoft.lirp.persistence.ReactiveEntityReference")
                for (importFqn in importFqns) {
                    appendLine("import $importFqn")
                }
                appendLine()
                appendLine("/**")
                appendLine(" * KSP-generated navigation extension accessors for [$className].")
                appendLine(" * Each property delegates to the bound [AggregateRefDelegate] stored on the entity,")
                appendLine(" * exposing [ReactiveEntityReference.resolve] and [ReactiveEntityReference.referenceId]")
                appendLine(" * without runtime reflection.")
                appendLine(" */")
                for (meta in toOneEntries) {
                    val targetSimple = meta.referencedClassFqn.substringAfterLast('.')
                    appendLine("@Suppress(\"UNCHECKED_CAST\")")
                    appendLine("$visibility val $kotlinClassName.${meta.accessorName}: ReactiveEntityReference<*, $targetSimple>")
                    appendLine("    get() = getToOneRef(\"${meta.accessorName}\") as AggregateRefDelegate<*, $targetSimple>")
                    appendLine()
                }
            }.toByteArray()
        )
        extFile.close()

        logger.info("Generated $packageName.$extFileName for $className")
    }

    /**
     * Determines whether a property represents a collection reference, using source-text detection
     * of aggregate factory calls as the primary mechanism, with supertype-walking as a fallback.
     *
     * Source-text detection is preferred because after the return type changes to
     * `MutableList<E>`/`List<E>`/`MutableSet<E>`/`Set<E>`, the stdlib types do not have
     * [AGGREGATE_COLLECTION_REF_FQN] in their supertype chain.
     */
    private fun isCollectionReference(prop: KSPropertyDeclaration, type: KSType): Boolean {
        // Read only the property line plus 1 continuation line to avoid false-positive matches
        // from factory calls in adjacent property declarations (e.g. 'aggregateList' in the next
        // property within the default linesAfter window).
        val text = readSourceLines(prop, linesBefore = 0, linesAfter = 1)
        if (text != null && containsAggregateFactoryCall(text)) return true
        return isCollectionReferenceByType(type)
    }

    private fun containsAggregateFactoryCall(text: String): Boolean =
        text.contains("mutableAggregateList") ||
            text.contains("mutableAggregateSet") ||
            text.contains("aggregateList") ||
            text.contains("aggregateSet")

    /**
     * Fallback type-walk for collection reference detection, used when source text is unavailable
     * or does not contain a known factory call. Walks the supertype chain to find
     * [AGGREGATE_COLLECTION_REF_FQN].
     */
    private fun isCollectionReferenceByType(type: KSType): Boolean {
        val declaration = type.declaration
        if (declaration is KSTypeAlias) {
            return isCollectionReferenceByType(declaration.type.resolve())
        }
        if (isCollectionReferenceFqn(declaration.qualifiedName?.asString())) return true

        if (declaration is KSClassDeclaration) {
            for (superType in declaration.superTypes) {
                if (isCollectionReferenceByType(superType.resolve())) return true
            }
        }
        return false
    }

    private fun isCollectionReferenceFqn(fqn: String?): Boolean =
        fqn == AGGREGATE_COLLECTION_REF_FQN ||
            fqn == AGGREGATE_LIST_REF_DELEGATE_FQN ||
            fqn == AGGREGATE_SET_REF_DELEGATE_FQN

    /**
     * Determines whether the collection reference is ordered (List semantics) by inspecting the
     * property's source declaration text for the `aggregateList` vs `aggregateSet` factory call.
     *
     * Because KSP resolves delegated property types to the interface (`AggregateCollectionRef`)
     * rather than the concrete delegate class, the type system alone cannot distinguish list from set.
     * Reading the source file around the property declaration is the most reliable way to detect
     * which factory function is used.
     *
     * Falls back to `false` (Set semantics) if the source is unavailable or the line contains neither.
     */
    private fun isOrderedCollectionDelegate(prop: KSPropertyDeclaration): Boolean {
        val text = readSourceLines(prop, linesBefore = 0, linesAfter = 5) ?: return false
        // Note: mutableAggregateList uses camelCase capital 'A', so "aggregateList" (lowercase)
        // does NOT match as a substring. Check mutable variants explicitly before the lowercase check.
        return when {
            text.contains("mutableAggregateList") -> true
            text.contains("mutableAggregateSet") -> false
            text.contains("aggregateList") -> true
            text.contains("aggregateSet") -> false
            else -> {
                val fqn = prop.type.resolve().declaration.qualifiedName?.asString() ?: return false
                fqn.contains("List")
            }
        }
    }

    private fun isOnDeleteExplicitInSource(prop: KSPropertyDeclaration): Boolean {
        val text = readSourceLines(prop, linesBefore = 5, linesAfter = 0) ?: return false
        return Regex("""\bonDelete\s*=""").containsMatchIn(text)
    }

    /**
     * Reads source lines around a [KSPropertyDeclaration] from its originating file.
     *
     * Returns `null` if the source location is unavailable or the file does not exist.
     * Used by [isOrderedCollectionDelegate] and [isOnDeleteExplicitInSource] to inspect
     * source text that KSP's type system cannot distinguish (delegate factory names,
     * explicit vs default annotation arguments).
     */
    private fun readSourceLines(prop: KSPropertyDeclaration, linesBefore: Int = 0, linesAfter: Int = 5): String? {
        val location = prop.location as? FileLocation ?: return null
        val file = File(location.filePath)
        if (!file.exists()) return null
        val lines = file.readLines()
        val propLine = (location.lineNumber - 1).coerceAtLeast(0)
        val startLine = (propLine - linesBefore).coerceAtLeast(0)
        val endLine = (propLine + linesAfter + 1).coerceAtMost(lines.size)
        return lines.subList(startLine, endLine).joinToString("\n")
    }

    /**
     * Reads source text from a property's declaration line to end of file, used for
     * `polymorphicAggregate(...)` declarations whose arm count or formatting cannot be bounded by a
     * fixed line window; the caller bounds the result with [extractPolymorphicAggregateSpan].
     */
    private fun readSourceFromProperty(prop: KSPropertyDeclaration): String? {
        val location = prop.location as? FileLocation ?: return null
        val file = File(location.filePath)
        if (!file.exists()) return null
        val lines = file.readLines()
        val propLine = (location.lineNumber - 1).coerceAtLeast(0)
        return lines.subList(propLine, lines.size).joinToString("\n")
    }

    /**
     * Extracts the referenced entity FQN from a collection reference type.
     *
     * Resolution strategy (in priority order):
     * 1. If the type is a [KSTypeAlias], unwrap and recurse.
     * 2. If the type is a stdlib collection (`MutableList<E>`, `List<E>`, `MutableSet<E>`, `Set<E>`),
     *    extract the entity FQN directly from the single type argument.
     * 3. If the type has two or more type arguments (e.g., `AggregateListRefDelegate<K, E>`),
     *    extract the entity FQN from the second type argument (K-first ordering).
     * 4. Walk the supertype chain looking for [AGGREGATE_COLLECTION_REF_FQN] and extract from there.
     */
    private fun findReferencedClassFqnFromCollectionType(type: KSType): String? {
        val declaration = type.declaration

        if (declaration is KSTypeAlias) {
            return findReferencedClassFqnFromCollectionType(declaration.type.resolve())
        }

        val typeArgs = type.arguments
        val fqn = declaration.qualifiedName?.asString()

        // Direct type argument extraction for stdlib collection types (MutableList<E>, List<E>, MutableSet<E>, Set<E>)
        if (fqn in STDLIB_COLLECTION_FQNS && typeArgs.size == 1) {
            val entityArg = typeArgs[0].type?.resolve()
            return entityArg?.declaration?.qualifiedName?.asString()
        }

        // Direct type arguments on the resolved type (e.g., AggregateListRefDelegate<Int, Track>)
        // E is the second type argument after the K-first ordering
        if (typeArgs.size >= 2) {
            val entityArg = typeArgs[1].type?.resolve()
            return entityArg?.declaration?.qualifiedName?.asString()
        }

        // Walk supertypes for AggregateCollectionRef<K, E>
        if (declaration is KSClassDeclaration) {
            for (superType in declaration.superTypes) {
                val resolvedSuperType = superType.resolve()
                val superFqn = resolvedSuperType.declaration.qualifiedName?.asString()
                if (superFqn == AGGREGATE_COLLECTION_REF_FQN) {
                    val entityArg = resolvedSuperType.arguments.getOrNull(1)?.type?.resolve()
                    return entityArg?.declaration?.qualifiedName?.asString()
                }
                val deepResult = findReferencedClassFqnFromCollectionType(resolvedSuperType)
                if (deepResult != null) return deepResult
            }
        }

        return null
    }

    /**
     * Recursively extracts the fully qualified name of the referenced entity class from a [KSType].
     *
     * Resolution strategy (in priority order):
     * 1. If the type is a [KSTypeAlias], unwrap and recurse on the aliased type.
     * 2. If the type has type arguments (direct generic such as `ReactiveEntityReference<Int, Customer>`),
     *    extract the FQN of the second type argument (`Customer`, the entity type after K-first ordering).
     * 3. If the type is a class declaration (possibly an intermediate interface), walk its supertype
     *    chain looking for a supertype whose FQN matches [REACTIVE_ENTITY_REFERENCE_FQN] and extract
     *    the second type argument from that supertype.
     *
     * Returns `null` if no referenced entity class can be determined.
     */
    private fun findReferencedClassFqnFromType(type: KSType): String? {
        val declaration = type.declaration

        // Case 1: type alias — unwrap and recurse
        if (declaration is KSTypeAlias) {
            return findReferencedClassFqnFromType(declaration.type.resolve())
        }

        // Case 2: type has direct type arguments (ReactiveEntityReference<K, E> or AggregateRefDelegate<K, E>)
        // E is the second type argument after the K-first ordering
        val typeArgs = type.arguments
        if (typeArgs.size >= 2) {
            val entityArg = typeArgs[1].type?.resolve()
            return entityArg?.declaration?.qualifiedName?.asString()
        }

        // Case 3: walk supertype chain for ReactiveEntityReference<K, E>
        if (declaration is KSClassDeclaration) {
            for (superType in declaration.superTypes) {
                val resolvedSuperType = superType.resolve()
                val superFqn = resolvedSuperType.declaration.qualifiedName?.asString()
                if (superFqn == REACTIVE_ENTITY_REFERENCE_FQN) {
                    val entityArg = resolvedSuperType.arguments.getOrNull(1)?.type?.resolve()
                    return entityArg?.declaration?.qualifiedName?.asString()
                }
                // Recurse deeper for multi-level inheritance chains
                val deepResult = findReferencedClassFqnFromType(resolvedSuperType)
                if (deepResult != null) return deepResult
            }
        }

        return null
    }

    private fun extractCascadeActionName(value: Any?): String =
        when {
            value is KSType -> value.declaration.simpleName.asString()
            value != null -> {
                val str = value.toString()
                when {
                    str.endsWith("CASCADE") -> "CASCADE"
                    str.endsWith("NONE") -> "NONE"
                    str.endsWith("RESTRICT") -> "RESTRICT"
                    else -> "DETACH"
                }
            }
            else -> "DETACH"
        }
}

private data class RefPropertyMeta(
    val refName: String,
    val propertyName: String,
    val referencedClassFqn: String,
    val bubbleUp: Boolean,
    val cascadeAction: String
)

private data class CollectionRefPropertyMeta(
    val refName: String,
    val propertyName: String,
    val referencedClassFqn: String,
    val cascadeAction: String,
    val isOrdered: Boolean
)

private data class ToOneRefPropertyMeta(
    val scalarName: String,
    val accessorName: String,
    val referencedClassFqn: String,
    val bubbleUp: Boolean,
    val cascadeAction: String,
    val isOptional: Boolean = false,
    val isDelegateVal: Boolean = false
)

/**
 * Resolved metadata for one arm of a `polymorphicAggregate` property. The [refName] is
 * dot-namespaced (`"${polymorphicPropName}.${armLabel}"`) so that cascade dispatch can address
 * each arm individually. The [delegateGetter] in the generated code reaches the arm's inner
 * [net.transgressoft.lirp.persistence.AggregateRefDelegate] via
 * `entity.${polymorphicPropName}.armDelegate("${armLabel}")`.
 */
private data class PolymorphicArmRefMeta(
    val refName: String,
    val polymorphicPropName: String,
    val armLabel: String,
    val armScalarName: String,
    val referencedClassFqn: String,
    val cascadeAction: String
)