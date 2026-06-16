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

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier

/**
 * Analyzes `@Embedded` / `@Embeddable` sites on entity primary-constructor parameters.
 *
 * Responsibilities:
 * - Validates the structural contracts (constructor-only placement, kind checks on the
 *   referenced `@Embeddable` type).
 * - Recursively walks nested `@Embeddable` hierarchies, flattening each scalar leaf into a
 *   [ColumnMeta] appended to the caller-supplied accumulator.
 * - Builds the parallel [EmbeddedCtorSlot] tree that `fromRow` uses to emit nested constructor
 *   expressions.
 *
 * Column-name collision detection is intentionally left to the caller ([TableDefProcessor])
 * so it runs once on the fully flattened list rather than per-embeddable.
 *
 * **Relationship with [ColumnMetaBuilder]:**
 * This class owns the tree-traversal logic — walking ctor params and body-declared properties,
 * dispatching `@Embedded` sites into recursive descent, and building [CtorSlot] structures for
 * code generation. [ColumnMetaBuilder] is a collaborator, not a duplicate: scalar leaf columns
 * inside an `@Embeddable` hierarchy are resolved via [ColumnMetaBuilder.buildEmbeddedLeafColumn],
 * which stamps the concatenated prefix-name, embedded access path, and `isInsideEmbedded = true`
 * markers that distinguish embedded scalars from top-level entity columns. Top-level scalars use
 * [ColumnMetaBuilder.buildColumnMeta], which additionally handles PK detection, `@Version`,
 * aggregate FK exclusion, and per-property `isMutable`/`isCtorParam` flags that are meaningless
 * inside an `@Embeddable`. The two methods serve structurally different column shapes; hoisting a
 * shared walk helper is not safe and the split is kept deliberate.
 */
internal class EmbeddableAnalyzer(
    private val logger: KSPLogger,
    private val columnMetaBuilder: ColumnMetaBuilder
) {

    /**
     * Output of [collectColumnsAndSlots] — the flat column list (what `columns`, `applyRow`,
     * `applyScalarRow`, `toParams` consume), the structured ctor-slot tree (what `fromRow`
     * consumes to emit nested constructor expressions for `@Embedded` parameters), and the set
     * of `@Embeddable` source files visited during recursive descent (used to populate
     * [com.google.devtools.ksp.processing.Dependencies] for incremental KSP re-generation).
     */
    data class CollectedShape(
        val columns: List<ColumnMeta>,
        val ctorSlots: List<CtorSlot>,
        val setterSlots: List<EmbeddedSetterSlot> = emptyList(),
        val embeddableFiles: Set<KSFile> = emptySet(),
        /** Non-null when any property type could not be resolved in this KSP round. The value is the
         * enclosing entity class that should be added to `unableToProcess` for re-queuing. */
        val deferredSymbol: KSClassDeclaration? = null,
        /** Detail triple `(entityFqn, propertyName, typeFqn)` populated when [deferredSymbol] is
         * non-null, used by [TableDefProcessor] to emit the terminal diagnostic. */
        val deferredDetail: Triple<String, String, String>? = null
    )

    /**
     * Carries the state that stays constant across a single `@Embedded` recursive descent — the
     * owning entity ([rootClass]), the flat column accumulator, the visited-`@Embeddable`-files set,
     * and the deferral slot. Bundling these into one object keeps the recursive
     * [buildEmbeddedSlot] / [buildEmbeddableChildSlot] signatures small.
     */
    private class EmbeddedDescent(
        val rootClass: KSClassDeclaration,
        val columnsAccumulator: MutableList<ColumnMeta>,
        val embeddableFiles: MutableSet<KSFile>,
        val deferralHolder: Array<Pair<KSClassDeclaration, Triple<String, String, String>>?>?
    )

    /**
     * Walks the entity's primary constructor in declaration order, routing each parameter to one
     * of three paths: an `@Embedded` parameter triggers recursive descent into the referenced
     * `@Embeddable` (flattening its scalars and recording an [EmbeddedCtorSlot]); a regular
     * parameter goes through [ColumnMetaBuilder.buildColumnMeta] and yields a [ScalarCtorSlot];
     * non-ctor properties (setter cols) are scanned afterwards. The result preserves ctor-arg
     * order so the generated `fromRow` can emit positional constructor calls.
     */
    fun collectColumnsAndSlots(
        classDecl: KSClassDeclaration,
        versionedProperty: KSPropertyDeclaration?,
        excludedBackingFields: Set<String> = emptySet(),
        aggregateBackingScalarNames: Set<String> = emptySet()
    ): CollectedShape {
        val hasDeclaredId = classDecl.getDeclaredProperties().any { it.simpleName.asString() == "id" && !it.isAbstract() }
        val versionedName = versionedProperty?.simpleName?.asString()
        val ctorParams = classDecl.primaryConstructor?.parameters.orEmpty()
        val ctorParamNames = ctorParams.mapNotNull { it.name?.asString() }.toSet()
        val propertiesByName = classDecl.getAllProperties().associateBy { it.simpleName.asString() }

        val columns = mutableListOf<ColumnMeta>()
        val ctorSlots = mutableListOf<CtorSlot>()
        val setterSlots = mutableListOf<EmbeddedSetterSlot>()
        val embeddableFiles = mutableSetOf<KSFile>()

        reportBodyDeclaredEmbedded(classDecl, ctorParamNames)

        for (param in ctorParams) {
            val deferred =
                processCtorParam(
                    param, classDecl, propertiesByName, excludedBackingFields,
                    hasDeclaredId, versionedName, ctorParamNames, aggregateBackingScalarNames,
                    columns, ctorSlots, embeddableFiles
                )
            if (deferred != null) return CollectedShape(emptyList(), emptyList(), deferredSymbol = deferred.first, deferredDetail = deferred.second)
        }

        val nonCtorDeferred =
            collectNonCtorScalarColumns(
                classDecl, ctorParamNames, excludedBackingFields,
                hasDeclaredId, versionedName, aggregateBackingScalarNames, columns, setterSlots, embeddableFiles
            )
        if (nonCtorDeferred !=
            null
        ) return CollectedShape(emptyList(), emptyList(), deferredSymbol = nonCtorDeferred.first, deferredDetail = nonCtorDeferred.second)

        return CollectedShape(columns, ctorSlots, setterSlots, embeddableFiles)
    }

    /**
     * `@Embedded` is constructor-only. Any non-ctor property carrying `@Embedded` is rejected with
     * a single diagnostic per occurrence so it does not silently fall through
     * [ColumnMetaBuilder.buildColumnMeta] as an unsupported type.
     */
    private fun reportBodyDeclaredEmbedded(classDecl: KSClassDeclaration, ctorParamNames: Set<String>) {
        for (prop in classDecl.getAllProperties()) {
            val propName = prop.simpleName.asString()
            if (propName in ctorParamNames) continue
            val hasEmbedded = resolvePersistenceAnnotations(prop).has(EMBEDDED_FQN)
            if (!hasEmbedded) continue
            // Body-declared mutable var is accepted — routed through collectNonCtorScalarColumns.
            if (prop.isMutable) continue
            // Body-declared read-only val has no setter to populate from a row; reject with a
            // targeted diagnostic.
            logger.error(
                "@Embedded on a body-declared property requires a mutable `var` (typically " +
                    "`var x by reactiveProperty(...)`); found a read-only `val` on " +
                    "'${classDecl.qualifiedName?.asString() ?: classDecl.simpleName.asString()}.$propName'. " +
                    "Use a constructor parameter or a reactive `var`.",
                prop
            )
        }
    }

    /**
     * Dispatches a single primary-constructor parameter to either the embedded or the scalar
     * path, mutating [columns] and [ctorSlots] in place. Parameters that are excluded or
     * structurally invalid are skipped without side effects. Returns a deferral pair
     * `(entityClass, (entityFqn, propertyName, typeFqn))` when the property type is unresolvable
     * in this KSP round, so the caller can short-circuit and return an unprocessed [CollectedShape].
     */
    @Suppress("kotlin:S107")
    private fun processCtorParam(
        param: KSValueParameter,
        classDecl: KSClassDeclaration,
        propertiesByName: Map<String, KSPropertyDeclaration>,
        excludedBackingFields: Set<String>,
        hasDeclaredId: Boolean,
        versionedName: String?,
        ctorParamNames: Set<String>,
        aggregateBackingScalarNames: Set<String>,
        columns: MutableList<ColumnMeta>,
        ctorSlots: MutableList<CtorSlot>,
        embeddableFiles: MutableSet<KSFile>
    ): Pair<KSClassDeclaration, Triple<String, String, String>>? {
        val paramName = param.name?.asString() ?: return null
        if (paramName in excludedBackingFields) return null
        val prop = propertiesByName[paramName] ?: return null
        when (val eligibility = columnMetaBuilder.checkEligibility(prop, param, classDecl)) {
            is Eligibility.Deferred -> return eligibility.symbol to deferralDetail(classDecl, paramName, prop, eligibility.unresolvedAnnotation)
            is Eligibility.Excluded -> return null
            is Eligibility.Column -> { /* proceed */ }
        }

        // Dispatch order: @ElementCollection BEFORE @Embedded BEFORE plain scalar.
        // An @ElementCollection annotation preempts the plain-scalar branch so a List/Set property
        // with the annotation is never silently passed through buildColumnMeta as an unsupported type.
        val elementCollectionAnnotation = resolvePersistenceAnnotations(prop, param).firstWithFqn(ELEMENT_COLLECTION_FQN)
        val embeddedAnnotation = resolvePersistenceAnnotations(prop, param).firstWithFqn(EMBEDDED_FQN)

        if (elementCollectionAnnotation != null) {
            val col =
                columnMetaBuilder.buildElementCollectionColumn(
                    prop,
                    classDecl.qualifiedName?.asString() ?: classDecl.simpleName.asString(),
                    isCtorParam = true
                ) ?: return null
            columns += col
            ctorSlots += ScalarCtorSlot(paramName, col)
        } else if (embeddedAnnotation != null) {
            if (!validateEmbeddedTargetStrictness(classDecl, prop)) return null
            val deferralHolder = arrayOfNulls<Pair<KSClassDeclaration, Triple<String, String, String>>>(1)
            val slot =
                buildEmbeddedSlot(
                    prop = prop,
                    ctorParamName = paramName,
                    embeddedAnnotation = embeddedAnnotation,
                    parentPrefix = autoDerivedPrefix(paramName),
                    parentPath = paramName,
                    topLevelPropertyName = paramName,
                    descent = EmbeddedDescent(classDecl, columns, embeddableFiles, deferralHolder)
                )
            deferralHolder[0]?.let { return it }
            if (slot != null) ctorSlots += slot
        } else {
            val eligibility = columnMetaBuilder.buildColumnMeta(prop, hasDeclaredId, versionedName, ctorParamNames, classDecl, aggregateBackingScalarNames)
            when (eligibility) {
                is Eligibility.Deferred -> return eligibility.symbol to deferralDetail(classDecl, paramName, prop, eligibility.unresolvedAnnotation)
                is Eligibility.Excluded -> return null
                is Eligibility.Column -> {
                    columns += eligibility.meta
                    ctorSlots += ScalarCtorSlot(paramName, eligibility.meta)
                }
            }
        }
        return null
    }

    /**
     * Scans non-constructor properties for setter columns (body-declared `var` fields). Dispatches
     * each property to [processNonCtorProperty] which routes to one of three paths: `@Embedded var`,
     * `@ElementCollection`, or scalar column. Body-declared `val` with `@Embedded` is already
     * diagnosed in [reportBodyDeclaredEmbedded]; only mutable `var` reaches this point.
     *
     * Returns a deferral pair when any property type cannot be resolved in this KSP round, so the
     * caller can short-circuit and re-queue the enclosing entity.
     */
    @Suppress("kotlin:S107")
    private fun collectNonCtorScalarColumns(
        classDecl: KSClassDeclaration,
        ctorParamNames: Set<String>,
        excludedBackingFields: Set<String>,
        hasDeclaredId: Boolean,
        versionedName: String?,
        aggregateBackingScalarNames: Set<String>,
        columns: MutableList<ColumnMeta>,
        setterSlots: MutableList<EmbeddedSetterSlot>,
        embeddableFiles: MutableSet<KSFile>
    ): Pair<KSClassDeclaration, Triple<String, String, String>>? {
        val classFqn = classDecl.qualifiedName?.asString() ?: classDecl.simpleName.asString()
        for (prop in classDecl.getAllProperties()) {
            val propName = prop.simpleName.asString()
            if (propName in ctorParamNames) continue
            when (val eligibility = columnMetaBuilder.checkEligibility(prop, enclosingClass = classDecl)) {
                is Eligibility.Deferred -> return eligibility.symbol to deferralDetail(classDecl, propName, prop, eligibility.unresolvedAnnotation)
                is Eligibility.Excluded -> continue
                is Eligibility.Column -> { /* proceed */ }
            }
            if (propName in excludedBackingFields) continue
            val deferred =
                processNonCtorProperty(
                    prop, propName, classFqn, classDecl, hasDeclaredId, versionedName, ctorParamNames, aggregateBackingScalarNames,
                    columns, setterSlots, embeddableFiles
                )
            if (deferred != null) return deferred
        }
        return null
    }

    /**
     * Routes a single non-constructor property to its handling path: `@Embedded var` is passed to
     * [processBodyDeclaredEmbedded]; `@ElementCollection` and scalars to [buildNonCtorScalarColumn].
     * Dispatcher logic is isolated from loop mechanics for readability.
     *
     * Returns a deferral pair when the property type is unresolvable in this KSP round.
     */
    @Suppress("kotlin:S107")
    private fun processNonCtorProperty(
        prop: KSPropertyDeclaration,
        propName: String,
        classFqn: String,
        classDecl: KSClassDeclaration,
        hasDeclaredId: Boolean,
        versionedName: String?,
        ctorParamNames: Set<String>,
        aggregateBackingScalarNames: Set<String>,
        columns: MutableList<ColumnMeta>,
        setterSlots: MutableList<EmbeddedSetterSlot>,
        embeddableFiles: MutableSet<KSFile>
    ): Pair<KSClassDeclaration, Triple<String, String, String>>? {
        val hasEmbedded = resolvePersistenceAnnotations(prop).has(EMBEDDED_FQN)
        if (hasEmbedded) {
            return processBodyDeclaredEmbedded(prop, propName, classFqn, classDecl, columns, setterSlots, embeddableFiles)
        }
        return buildNonCtorScalarColumn(prop, propName, classFqn, classDecl, hasDeclaredId, versionedName, ctorParamNames, aggregateBackingScalarNames, columns)
    }

    /**
     * Handles body-declared `@Embedded var` properties. Validates the custom-getter constraint,
     * builds the embedded slot tree, and appends an [EmbeddedSetterSlot]. Returns early on custom
     * getter (already diagnosed) so the outer loop skips further processing.
     *
     * Returns a deferral pair when a nested leaf type is unresolvable in this KSP round, so the
     * enclosing entity is re-queued — mirroring the ctor-`@Embedded` path in [processCtorParam].
     */
    private fun processBodyDeclaredEmbedded(
        prop: KSPropertyDeclaration,
        propName: String,
        classFqn: String,
        classDecl: KSClassDeclaration,
        columns: MutableList<ColumnMeta>,
        setterSlots: MutableList<EmbeddedSetterSlot>,
        embeddableFiles: MutableSet<KSFile>
    ): Pair<KSClassDeclaration, Triple<String, String, String>>? {
        if (!prop.isMutable) return null
        if (isSourceDeclaredCustomGetter(prop)) {
            logger.error(
                "@Embedded property must not have a custom getter: $classFqn.$propName",
                prop
            )
            return null
        }
        if (!prop.isDelegated()) {
            logger.error(
                "@Embedded on a body-declared property must use a delegated reactive backing field: $classFqn.$propName",
                prop
            )
            return null
        }
        val embeddedAnnotation =
            resolvePersistenceAnnotations(prop).firstWithFqn(EMBEDDED_FQN)
                ?: return null
        val deferralHolder = arrayOfNulls<Pair<KSClassDeclaration, Triple<String, String, String>>>(1)
        val slot =
            buildEmbeddedSlot(
                prop = prop,
                ctorParamName = propName,
                embeddedAnnotation = embeddedAnnotation,
                parentPrefix = autoDerivedPrefix(propName),
                parentPath = propName,
                topLevelPropertyName = propName,
                descent = EmbeddedDescent(classDecl, columns, embeddableFiles, deferralHolder)
            )
        deferralHolder[0]?.let { return it }
        // Propagate the resolved @PersistenceCreator call expression so a body-declared @Embedded var
        // reconstructs through the factory just like a ctor-param @Embedded; dropping it here would
        // fall back to the (possibly non-public) primary constructor.
        if (slot != null) {
            setterSlots += EmbeddedSetterSlot(slot.ctorParamName, slot.embeddableTypeFqn, slot.children, slot.creatorCallExpression)
        }
        return null
    }

    /**
     * Handles body-declared `@ElementCollection` and scalar properties. Validates that
     * `@ElementCollection` properties are mutable (so `fromRow` can populate them post-construction),
     * then dispatches to the appropriate column builder. Emits a diagnostic and skips on immutable
     * `@ElementCollection val` (would produce non-compiling generated code).
     *
     * Returns a deferral pair when the property type is unresolvable in this KSP round.
     */
    @Suppress("kotlin:S107")
    private fun buildNonCtorScalarColumn(
        prop: KSPropertyDeclaration,
        propName: String,
        classFqn: String,
        classDecl: KSClassDeclaration,
        hasDeclaredId: Boolean,
        versionedName: String?,
        ctorParamNames: Set<String>,
        aggregateBackingScalarNames: Set<String>,
        columns: MutableList<ColumnMeta>
    ): Pair<KSClassDeclaration, Triple<String, String, String>>? {
        val hasElementCollection = resolvePersistenceAnnotations(prop).has(ELEMENT_COLLECTION_FQN)
        if (hasElementCollection && !prop.isMutable) {
            logger.error(
                "@ElementCollection on a body-declared property requires a mutable `var` " +
                    "(typically `var x by reactiveProperty(...)`); found a read-only `val` on " +
                    "'$classFqn.$propName'. Use a constructor `val` parameter or a reactive `var`.",
                prop
            )
            return null
        }
        val col =
            if (hasElementCollection) {
                columnMetaBuilder.buildElementCollectionColumn(prop, classFqn, isCtorParam = false) ?: return null
            } else {
                val eligibility = columnMetaBuilder.buildColumnMeta(prop, hasDeclaredId, versionedName, ctorParamNames, classDecl, aggregateBackingScalarNames)
                when (eligibility) {
                    is Eligibility.Deferred -> return eligibility.symbol to deferralDetail(classDecl, propName, prop, eligibility.unresolvedAnnotation)
                    is Eligibility.Excluded -> return null
                    is Eligibility.Column -> eligibility.meta
                }
            }
        columns += col
        return null
    }

    /**
     * After the recursive `@Embedded` flatten, the fully accumulated `ColumnMeta`
     * list is grouped by SQL column name. Any duplicate-name group emits a single
     * `logger.error()` naming every colliding property's entity-rooted access path so the
     * diagnostic surfaces every angle of the collision (e.g. `album.performer.name` vs
     * `label.name`). Paths are sorted before printing so the message is deterministic and test
     * assertions remain stable.
     *
     * Returns `true` when at least one collision was reported (the caller must suppress codegen
     * for the entity so consumers do not see partially-formed `_LirpTableDef` source).
     */
    fun detectColumnCollisions(
        classDecl: KSClassDeclaration,
        columns: List<ColumnMeta>
    ): Boolean {
        val byColumnName = columns.groupBy { it.name }
        val classFqn = classDecl.qualifiedName?.asString() ?: classDecl.simpleName.asString()
        var collided = false
        for ((columnName, group) in byColumnName) {
            if (group.size <= 1) continue
            val paths = group.map { "$classFqn.${it.embeddedPath}" }.sorted()
            logger.error(
                "Column name collision: '$columnName' is produced by multiple properties: " +
                    paths.joinToString(", "),
                classDecl
            )
            collided = true
        }
        return collided
    }

    private fun autoDerivedPrefix(propertyName: String): String = "${propertyName.toSnakeCase()}_"

    /**
     * Applies target strictness to a ctor-param `@Embedded` site. Rejects properties with custom
     * getters. Both `val` and `var` constructor parameters are accepted. Body-declared placement
     * is handled separately in [collectNonCtorScalarColumns]. Returns `true` when the site is
     * valid; emits one `logger.error()` per violation and returns `false` otherwise so the
     * recursive descent skips the malformed slot.
     */
    private fun validateEmbeddedTargetStrictness(
        ownerClass: KSClassDeclaration,
        prop: KSPropertyDeclaration
    ): Boolean {
        val propertyFqn =
            "${ownerClass.qualifiedName?.asString() ?: ownerClass.simpleName.asString()}.${prop.simpleName.asString()}"
        var ok = true
        // isSourceDeclaredCustomGetter detects only getters written in source (Origin.KOTLIN).
        // Synthesized data-class getters (SYNTHETIC) and cross-module compiled accessors
        // (KOTLIN_LIB) are both accepted — see isSourceDeclaredCustomGetter KDoc for ceiling note.
        if (isSourceDeclaredCustomGetter(prop)) {
            logger.error(
                "@Embedded property must not have a custom getter: $propertyFqn",
                prop
            )
            ok = false
        }
        return ok
    }

    /**
     * Applies the trio of `@Embeddable` kind diagnostics to an `@Embedded` consuming property.
     * Returns the referenced [KSClassDeclaration] when all checks pass, `null` (after emitting a
     * single `logger.error()`) otherwise. Kind checks fire before the recursive descent visits
     * child parameters.
     */
    private fun validateEmbeddableTarget(prop: KSPropertyDeclaration): KSClassDeclaration? {
        val propertyFqn =
            "${prop.parentDeclaration?.qualifiedName?.asString() ?: ""}.${prop.simpleName.asString()}".trimStart('.')
        val resolved = prop.type.resolve()
        val declaration = resolved.declaration

        // The target must be a class declaration (not interface/type-alias/type-parameter).
        val classDecl = declaration as? KSClassDeclaration
        if (classDecl == null) {
            val symbolName = declaration.qualifiedName?.asString() ?: declaration.simpleName.asString()
            logger.error(
                "@Embedded property must reference a class type: $propertyFqn references $symbolName",
                prop
            )
            return null
        }

        // The referenced class must carry @Embeddable.
        val hasEmbeddable =
            classDecl.annotations.any {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == EMBEDDABLE_FQN
            }
        if (!hasEmbeddable) {
            val referencedFqn = classDecl.qualifiedName?.asString() ?: classDecl.simpleName.asString()
            logger.error(
                "@Embedded property must reference an @Embeddable type: $propertyFqn references $referencedFqn",
                prop
            )
            return null
        }

        // @Embeddable must be a concrete data class with a non-empty primary constructor.
        // Single unified diagnostic covering non-class kinds, abstract/sealed, non-data, and
        // no-primary-ctor variants.
        val isConcreteDataClass =
            classDecl.classKind == ClassKind.CLASS &&
                Modifier.DATA in classDecl.modifiers &&
                Modifier.ABSTRACT !in classDecl.modifiers &&
                Modifier.SEALED !in classDecl.modifiers &&
                (classDecl.primaryConstructor?.parameters?.isNotEmpty() == true)
        if (!isConcreteDataClass) {
            val classFqn = classDecl.qualifiedName?.asString() ?: classDecl.simpleName.asString()
            logger.error(
                "@Embeddable must be a concrete data class: $classFqn",
                classDecl
            )
            return null
        }

        // Nullable @Embedded container semantics are deferred — nullable containers with non-null
        // leaves would be treated as fully-present, which produces incorrect SQL column shapes.
        if (resolved.isMarkedNullable) {
            logger.error("`@Embedded` nullable properties are not supported yet: $propertyFqn", prop)
            return null
        }

        return classDecl
    }

    /**
     * Recursively expands an `@Embedded` parameter into one [ColumnMeta] per scalar leaf (appended
     * to [columnsAccumulator]) plus a parallel [EmbeddedCtorSlot] that the generated `fromRow` uses
     * to emit a nested constructor expression. Prefixes concatenate top-down: parent prefix is
     * prepended, child prefix is appended (`album_performer_name`). Composition with
     * `@PersistenceProperty(converter = …)` at scalar leaves works because each leaf goes through
     * the standard [ColumnMetaBuilder.buildColumnMeta] pipeline.
     *
     * Returns `null` when the referenced type is not a class or lacks a qualified name.
     */
    private fun buildEmbeddedSlot(
        prop: KSPropertyDeclaration,
        ctorParamName: String,
        embeddedAnnotation: KSAnnotation,
        parentPrefix: String,
        parentPath: String,
        topLevelPropertyName: String,
        descent: EmbeddedDescent
    ): EmbeddedCtorSlot? {
        // An @Embedded container type that is still a KSP error type this round must be deferred
        // (re-queued for a later round), not run through structural validation — which would treat
        // it as "must reference a class type" and emit a spurious diagnostic while skipping the
        // deferral path entirely. Mirrors the scalar/leaf error-type deferral in ColumnMetaBuilder.
        if (prop.type.resolve().isError) {
            descent.deferralHolder?.set(
                0,
                descent.rootClass to deferralDetail(descent.rootClass, topLevelPropertyName, prop, unresolvedAnnotation = false)
            )
            return null
        }
        // Kind checks run before descent so the recursion only ever visits well-formed embeddables.
        val typeDecl = validateEmbeddableTarget(prop) ?: return null
        val typeFqn = typeDecl.qualifiedName?.asString() ?: return null
        typeDecl.containingFile?.let { descent.embeddableFiles += it }

        val effectivePrefix = effectivePrefix(embeddedAnnotation, ctorParamName, parentPrefix)
        reportBodyDeclaredEmbeddedInEmbeddable(typeDecl, typeFqn)

        val childSlots =
            buildChildSlots(typeDecl, typeFqn, effectivePrefix, parentPath, topLevelPropertyName, descent)
                ?: return null
        return applyCreatorToEmbeddedSlot(typeDecl, typeFqn, ctorParamName, childSlots)
    }

    /**
     * Builds one [CtorSlot] per primary-constructor parameter of [typeDecl], appending flattened
     * leaf columns to the accumulator. Returns `null` when any child parameter fails to resolve so
     * the caller aborts the whole slot rather than emitting an incomplete constructor tree. All
     * children are visited even after a failure so every malformed parameter is diagnosed.
     */
    private fun buildChildSlots(
        typeDecl: KSClassDeclaration,
        typeFqn: String,
        effectivePrefix: String,
        parentPath: String,
        topLevelPropertyName: String,
        descent: EmbeddedDescent
    ): List<CtorSlot>? {
        val childSlots = mutableListOf<CtorSlot>()
        var anyChildFailed = false
        for (childParam in typeDecl.primaryConstructor?.parameters.orEmpty()) {
            val slot =
                buildEmbeddableChildSlot(typeDecl, typeFqn, childParam, effectivePrefix, parentPath, topLevelPropertyName, descent)
            if (slot == null) anyChildFailed = true else childSlots += slot
        }
        return if (anyChildFailed) null else childSlots
    }

    /**
     * Routes the embeddable's reconstruction through its `@PersistenceCreator` when present and
     * falls back to the primary constructor otherwise. A present creator's parameters are matched
     * by name against [childSlots]; an ambiguous creator or an unmatched non-defaulted parameter is
     * a hard error (returns `null` to abort). A non-public reconstruction seam on an internal type
     * is a warning, since the descriptor still compiles inside the declaring module.
     */
    private fun applyCreatorToEmbeddedSlot(
        typeDecl: KSClassDeclaration,
        typeFqn: String,
        ctorParamName: String,
        childSlots: List<CtorSlot>
    ): EmbeddedCtorSlot? {
        return when (val resolution = resolveCreator(typeDecl)) {
            is CreatorResolution.Ambiguous -> {
                logger.error(
                    "Multiple @PersistenceCreator targets on ${typeDecl.qualifiedName?.asString()}: " +
                        "${resolution.conflicting.formatCreatorOffenders()}; exactly one is required.",
                    typeDecl
                )
                null
            }
            is CreatorResolution.Found -> {
                val creatorChildren = matchCreatorChildren(typeDecl, resolution, childSlots) ?: return null
                if (typeDecl.hasInternalNonPublicCreator()) {
                    logger.warn(
                        "${typeDecl.qualifiedName?.asString()} is internal and its @PersistenceCreator " +
                            "'${resolution.callExpression}' is not public; the generated descriptor may not compile " +
                            "outside its own module. Add a public @PersistenceCreator to make it cross-module usable."
                    )
                }
                EmbeddedCtorSlot(ctorParamName, typeFqn, creatorChildren, resolution.callExpression)
            }
            CreatorResolution.None -> {
                if (typeDecl.hasNonPublicPrimaryConstructor()) {
                    logger.warn(
                        "${typeDecl.qualifiedName?.asString()} has a non-public primary constructor and no " +
                            "@PersistenceCreator; the generated descriptor may not compile outside its own module."
                    )
                }
                EmbeddedCtorSlot(ctorParamName, typeFqn, childSlots)
            }
        }
    }

    /**
     * Matches creator parameters by name against [childSlots], preserving creator-parameter order;
     * child slots absent from the creator signature are dropped. A defaulted parameter with no
     * mapped slot is omitted so its default applies at instantiation; a non-defaulted one with no
     * mapped slot is a hard error and returns `null` to abort.
     */
    private fun matchCreatorChildren(
        typeDecl: KSClassDeclaration,
        resolution: CreatorResolution.Found,
        childSlots: List<CtorSlot>
    ): List<CtorSlot>? {
        val slotByName = childSlots.associateBy { it.ctorParamName }
        val creatorChildren = mutableListOf<CtorSlot>()
        for (param in resolution.params) {
            val paramName = param.name?.asString() ?: continue
            when (val slot = slotByName[paramName]) {
                is ScalarCtorSlot, is EmbeddedCtorSlot -> creatorChildren += slot
                else ->
                    if (!param.hasDefault) {
                        logger.error(
                            "@PersistenceCreator param '$paramName' on ${typeDecl.qualifiedName?.asString()} " +
                                "has no mapped column source.",
                            typeDecl
                        )
                        return null
                    }
            }
        }
        return creatorChildren
    }

    /**
     * Resolves the prefix applied to this embeddable's flattened columns. An empty or absent
     * `prefix` argument reverts to the auto-derived segment; an explicit non-empty prefix overrides
     * only the current segment while preserving any ancestor prefix, so a nested `prefix = "geo_"`
     * under a parent whose auto-derived prefix is `address_` still produces `address_geo_lat`.
     */
    private fun effectivePrefix(embeddedAnnotation: KSAnnotation, ctorParamName: String, parentPrefix: String): String {
        val explicitPrefix = embeddedAnnotation.arguments.firstOrNull { it.name?.asString() == "prefix" }?.value as? String
        if (explicitPrefix.isNullOrEmpty()) return parentPrefix
        val ancestorPrefix = parentPrefix.removeSuffix(autoDerivedPrefix(ctorParamName))
        return "$ancestorPrefix$explicitPrefix"
    }

    /**
     * Rejects `@Embedded` on any non-constructor property of an embeddable. `@Embedded` is
     * constructor-only; a body-declared placement has no addressable slot in the reconstructing
     * constructor call.
     */
    private fun reportBodyDeclaredEmbeddedInEmbeddable(typeDecl: KSClassDeclaration, typeFqn: String) {
        val childCtorNames =
            typeDecl.primaryConstructor?.parameters.orEmpty().mapNotNull { it.name?.asString() }.toSet()
        for (childProp in typeDecl.getAllProperties()) {
            val childPropName = childProp.simpleName.asString()
            if (childPropName in childCtorNames) continue
            val childParam = typeDecl.primaryConstructor?.parameters?.firstOrNull { it.name?.asString() == childPropName }
            val hasEmbedded = resolvePersistenceAnnotations(childProp, childParam).has(EMBEDDED_FQN)
            if (!hasEmbedded) continue
            logger.error(
                "@Embedded must be on a primary-constructor parameter (found body-declared property): " +
                    "$typeFqn.${childProp.simpleName.asString()}",
                childProp
            )
        }
    }

    /**
     * Resolves a single embeddable constructor parameter into its [CtorSlot] — either a nested
     * [EmbeddedCtorSlot] for a child `@Embedded`, or a [ScalarCtorSlot] for a flattened leaf.
     * Returns `null` (after logging where applicable) when the parameter is unresolvable, carries an
     * unsupported `@ElementCollection`, or fails leaf-column construction.
     */
    private fun buildEmbeddableChildSlot(
        typeDecl: KSClassDeclaration,
        typeFqn: String,
        childParam: KSValueParameter,
        effectivePrefix: String,
        parentPath: String,
        topLevelPropertyName: String,
        descent: EmbeddedDescent
    ): CtorSlot? {
        val childParamName = childParam.name?.asString() ?: return null
        val childProp =
            typeDecl.getDeclaredProperties().firstOrNull { it.simpleName.asString() == childParamName } ?: return null

        // Check exclusion via the centralized resolver so cross-module @PersistenceIgnore on
        // VALUE_PARAMETER is visible.
        when (val childEligibility = columnMetaBuilder.checkEligibility(childProp, childParam, typeDecl)) {
            is Eligibility.Deferred -> {
                // Attribute the deferral to the owning @PersistenceMapping entity (rootClass), not
                // the nested @Embeddable: TableDefProcessor re-queues and prunes by the entity FQN.
                descent.deferralHolder?.set(
                    0,
                    descent.rootClass to deferralDetail(descent.rootClass, childParamName, childProp, childEligibility.unresolvedAnnotation)
                )
                return null
            }
            is Eligibility.Excluded -> {
                // IgnoredCtorSlot emits `null` for the param in generated fromRow. That is only safe
                // when the param is nullable or has a default value — a non-nullable no-default param
                // with null would produce code that either fails to compile or throws NullPointerException
                // at instantiation time. Reject the embeddable early with a clear diagnostic so the
                // developer is informed rather than getting cryptic downstream failures.
                val isNullable = childParam.type.resolve().isMarkedNullable
                if (!isNullable && !childParam.hasDefault) {
                    logger.error(
                        "@PersistenceIgnore on '$typeFqn.$childParamName' cannot be applied: the parameter " +
                            "is non-nullable and has no default value. Emitting null for it in `fromRow` would " +
                            "produce non-compiling or crashing generated code. Make the parameter nullable, " +
                            "provide a default value, or remove @PersistenceIgnore.",
                        childProp
                    )
                    return null
                }
                // Non-null param with default: omit from the named-arg call so the default applies.
                if (!isNullable && childParam.hasDefault) return OmittedCtorSlot
                // Nullable param: emit null.
                return IgnoredCtorSlot(childParamName)
            }
            is Eligibility.Column -> { /* property is eligible — proceed */ }
        }

        val childHasElementCollection = resolvePersistenceAnnotations(childProp, childParam).has(ELEMENT_COLLECTION_FQN)
        if (childHasElementCollection) {
            logger.error(
                "@ElementCollection is not supported inside an @Embeddable. " +
                    "Move the property to the parent entity, or declare a dedicated child entity with `@ToOneAggregate`. " +
                    "Offending property: $typeFqn.$childParamName.",
                childProp
            )
            return null
        }

        val childEmbedded = resolvePersistenceAnnotations(childProp, childParam).firstWithFqn(EMBEDDED_FQN)
        if (childEmbedded != null) {
            if (!validateEmbeddedTargetStrictness(typeDecl, childProp)) return null
            return buildEmbeddedSlot(
                prop = childProp,
                ctorParamName = childParamName,
                embeddedAnnotation = childEmbedded,
                parentPrefix = "${effectivePrefix}${autoDerivedPrefix(childParamName)}",
                parentPath = "$parentPath.$childParamName",
                topLevelPropertyName = topLevelPropertyName,
                descent = descent
            )
        }

        val leafEligibility =
            columnMetaBuilder.buildEmbeddedLeafColumn(
                childProp = childProp,
                childParamName = childParamName,
                ctorParam = childParam,
                prefix = effectivePrefix,
                parentPath = parentPath,
                topLevelPropertyName = topLevelPropertyName,
                enclosingClass = typeDecl
            )
        return when (leafEligibility) {
            is Eligibility.Deferred -> {
                descent.deferralHolder?.set(
                    0,
                    descent.rootClass to deferralDetail(descent.rootClass, childParamName, childProp, leafEligibility.unresolvedAnnotation)
                )
                null
            }
            is Eligibility.Excluded -> null
            is Eligibility.Column -> {
                descent.columnsAccumulator += leafEligibility.meta
                ScalarCtorSlot(childParamName, leafEligibility.meta)
            }
        }
    }

    /**
     * Builds the `(entityFqn, propertyName, detail)` record for a deferred property. [detail]
     * describes what could not be resolved — either `type '<fqn>'` or `an annotation` — so the
     * terminal diagnostic does not misreport an unresolved annotation as an unresolved type.
     */
    private fun deferralDetail(
        classDecl: KSClassDeclaration,
        propName: String,
        prop: KSPropertyDeclaration,
        unresolvedAnnotation: Boolean
    ): Triple<String, String, String> {
        val entityFqn = classDecl.qualifiedName?.asString() ?: classDecl.simpleName.asString()
        val detail =
            if (unresolvedAnnotation) {
                "an annotation"
            } else {
                "type '${prop.type.resolve().declaration.qualifiedName?.asString() ?: prop.type}'"
            }
        return Triple(entityFqn, propName, detail)
    }
}