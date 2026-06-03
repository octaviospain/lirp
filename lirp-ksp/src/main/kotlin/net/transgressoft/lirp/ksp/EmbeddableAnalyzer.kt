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
import com.google.devtools.ksp.symbol.Origin

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
        val embeddableFiles: Set<KSFile> = emptySet()
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
            processCtorParam(
                param, classDecl, propertiesByName, excludedBackingFields,
                hasDeclaredId, versionedName, ctorParamNames, aggregateBackingScalarNames,
                columns, ctorSlots, embeddableFiles
            )
        }

        collectNonCtorScalarColumns(
            classDecl, ctorParamNames, excludedBackingFields,
            hasDeclaredId, versionedName, aggregateBackingScalarNames, columns, setterSlots, embeddableFiles
        )

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
            val hasEmbedded =
                prop.annotations.any {
                    it.annotationType.resolve().declaration.qualifiedName?.asString() == EMBEDDED_FQN
                }
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
     * path, mutating [columns] and [ctorSlots] in place. Parameters that are excluded,
     * unresolvable, or structurally invalid are skipped without side effects.
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
    ) {
        val paramName = param.name?.asString() ?: return
        if (paramName in excludedBackingFields) return
        val prop = propertiesByName[paramName] ?: return
        if (columnMetaBuilder.isExcluded(prop)) return

        // Dispatch order: @ElementCollection BEFORE @Embedded BEFORE plain scalar.
        // An @ElementCollection annotation preempts the plain-scalar branch so a List/Set property
        // with the annotation is never silently passed through buildColumnMeta as an unsupported type.
        val elementCollectionAnnotation =
            prop.annotations.firstOrNull {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == ELEMENT_COLLECTION_FQN
            }
        val embeddedAnnotation =
            prop.annotations.firstOrNull {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == EMBEDDED_FQN
            }

        if (elementCollectionAnnotation != null) {
            val col =
                columnMetaBuilder.buildElementCollectionColumn(
                    prop,
                    classDecl.qualifiedName?.asString() ?: classDecl.simpleName.asString(),
                    isCtorParam = true
                ) ?: return
            columns += col
            ctorSlots += ScalarCtorSlot(paramName, col)
        } else if (embeddedAnnotation != null) {
            if (!validateEmbeddedTargetStrictness(classDecl, prop)) return
            val slot =
                buildEmbeddedSlot(
                    prop = prop,
                    ctorParamName = paramName,
                    embeddedAnnotation = embeddedAnnotation,
                    columnsAccumulator = columns,
                    parentPrefix = autoDerivedPrefix(paramName),
                    parentPath = paramName,
                    topLevelPropertyName = paramName,
                    embeddableFiles = embeddableFiles
                )
            if (slot != null) ctorSlots += slot
        } else {
            val col = columnMetaBuilder.buildColumnMeta(prop, hasDeclaredId, versionedName, ctorParamNames, aggregateBackingScalarNames) ?: return
            columns += col
            ctorSlots += ScalarCtorSlot(paramName, col)
        }
    }

    /**
     * Scans non-constructor properties for setter columns (body-declared `var` fields). Dispatches
     * each property to [processNonCtorProperty] which routes to one of three paths: `@Embedded var`,
     * `@ElementCollection`, or scalar column. Body-declared `val` with `@Embedded` is already
     * diagnosed in [reportBodyDeclaredEmbedded]; only mutable `var` reaches this point.
     */
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
    ) {
        val classFqn = classDecl.qualifiedName?.asString() ?: classDecl.simpleName.asString()
        for (prop in classDecl.getAllProperties()) {
            val propName = prop.simpleName.asString()
            if (propName in ctorParamNames) continue
            if (columnMetaBuilder.isExcluded(prop) || propName in excludedBackingFields) continue
            processNonCtorProperty(
                prop, propName, classFqn, hasDeclaredId, versionedName, ctorParamNames, aggregateBackingScalarNames,
                columns, setterSlots, embeddableFiles
            )
        }
    }

    /**
     * Routes a single non-constructor property to its handling path: `@Embedded var` is passed to
     * [processBodyDeclaredEmbedded]; `@ElementCollection` and scalars to [buildNonCtorScalarColumn].
     * Dispatcher logic is isolated from loop mechanics for readability.
     */
    @Suppress("kotlin:S107")
    private fun processNonCtorProperty(
        prop: KSPropertyDeclaration,
        propName: String,
        classFqn: String,
        hasDeclaredId: Boolean,
        versionedName: String?,
        ctorParamNames: Set<String>,
        aggregateBackingScalarNames: Set<String>,
        columns: MutableList<ColumnMeta>,
        setterSlots: MutableList<EmbeddedSetterSlot>,
        embeddableFiles: MutableSet<KSFile>
    ) {
        val hasEmbedded =
            prop.annotations.any {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == EMBEDDED_FQN
            }
        if (hasEmbedded) {
            processBodyDeclaredEmbedded(prop, propName, classFqn, columns, setterSlots, embeddableFiles)
            return
        }
        buildNonCtorScalarColumn(prop, propName, classFqn, hasDeclaredId, versionedName, ctorParamNames, aggregateBackingScalarNames, columns)
    }

    /**
     * Handles body-declared `@Embedded var` properties. Validates the custom-getter constraint,
     * builds the embedded slot tree, and appends an [EmbeddedSetterSlot]. Returns early on custom
     * getter (already diagnosed) so the outer loop skips further processing.
     */
    private fun processBodyDeclaredEmbedded(
        prop: KSPropertyDeclaration,
        propName: String,
        classFqn: String,
        columns: MutableList<ColumnMeta>,
        setterSlots: MutableList<EmbeddedSetterSlot>,
        embeddableFiles: MutableSet<KSFile>
    ) {
        if (!prop.isMutable) return
        val getter = prop.getter
        if (getter != null && getter.origin != Origin.SYNTHETIC) {
            logger.error(
                "@Embedded property must not have a custom getter: $classFqn.$propName",
                prop
            )
            return
        }
        if (!prop.isDelegated()) {
            logger.error(
                "@Embedded on a body-declared property must use a delegated reactive backing field: $classFqn.$propName",
                prop
            )
            return
        }
        val embeddedAnnotation =
            prop.annotations.first {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == EMBEDDED_FQN
            }
        val slot =
            buildEmbeddedSlot(
                prop = prop,
                ctorParamName = propName,
                embeddedAnnotation = embeddedAnnotation,
                columnsAccumulator = columns,
                parentPrefix = autoDerivedPrefix(propName),
                parentPath = propName,
                topLevelPropertyName = propName,
                embeddableFiles = embeddableFiles
            )
        if (slot != null) setterSlots += EmbeddedSetterSlot(slot.ctorParamName, slot.embeddableTypeFqn, slot.children)
    }

    /**
     * Handles body-declared `@ElementCollection` and scalar properties. Validates that
     * `@ElementCollection` properties are mutable (so `fromRow` can populate them post-construction),
     * then dispatches to the appropriate column builder. Emits a diagnostic and skips on immutable
     * `@ElementCollection val` (would produce non-compiling generated code).
     */
    @Suppress("kotlin:S107")
    private fun buildNonCtorScalarColumn(
        prop: KSPropertyDeclaration,
        propName: String,
        classFqn: String,
        hasDeclaredId: Boolean,
        versionedName: String?,
        ctorParamNames: Set<String>,
        aggregateBackingScalarNames: Set<String>,
        columns: MutableList<ColumnMeta>
    ) {
        val hasElementCollection =
            prop.annotations.any {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == ELEMENT_COLLECTION_FQN
            }
        if (hasElementCollection && !prop.isMutable) {
            logger.error(
                "@ElementCollection on a body-declared property requires a mutable `var` " +
                    "(typically `var x by reactiveProperty(...)`); found a read-only `val` on " +
                    "'$classFqn.$propName'. Use a constructor `val` parameter or a reactive `var`.",
                prop
            )
            return
        }
        val col =
            if (hasElementCollection) {
                columnMetaBuilder.buildElementCollectionColumn(prop, classFqn, isCtorParam = false) ?: return
            } else {
                columnMetaBuilder.buildColumnMeta(prop, hasDeclaredId, versionedName, ctorParamNames, aggregateBackingScalarNames) ?: return
            }
        columns += col
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
        // KSP synthesizes a getter for every property (including data-class ctor `val`s); a
        // user-authored custom getter is distinguished by its declaration origin being one of the
        // source-language origins rather than SYNTHETIC. Filter on Origin so we only reject
        // explicitly-declared getters.
        val getter = prop.getter
        if (getter != null && getter.origin != Origin.SYNTHETIC) {
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
    @Suppress("kotlin:S107")
    private fun buildEmbeddedSlot(
        prop: KSPropertyDeclaration,
        ctorParamName: String,
        embeddedAnnotation: KSAnnotation,
        columnsAccumulator: MutableList<ColumnMeta>,
        parentPrefix: String,
        parentPath: String,
        topLevelPropertyName: String,
        embeddableFiles: MutableSet<KSFile> = mutableSetOf()
    ): EmbeddedCtorSlot? {
        // Kind checks run before descent so the recursion only ever visits well-formed embeddables.
        val typeDecl = validateEmbeddableTarget(prop) ?: return null
        val typeFqn = typeDecl.qualifiedName?.asString() ?: return null
        typeDecl.containingFile?.let { embeddableFiles += it }

        val effectivePrefix = effectivePrefix(embeddedAnnotation, ctorParamName, parentPrefix)
        reportBodyDeclaredEmbeddedInEmbeddable(typeDecl, typeFqn)

        val childSlots = mutableListOf<CtorSlot>()
        var anyChildFailed = false
        for (childParam in typeDecl.primaryConstructor?.parameters.orEmpty()) {
            val slot =
                buildEmbeddableChildSlot(
                    typeDecl, typeFqn, childParam, effectivePrefix,
                    parentPath, topLevelPropertyName, columnsAccumulator, embeddableFiles
                )
            if (slot == null) anyChildFailed = true else childSlots += slot
        }
        // When any child fails, abort the whole slot so the outer entity is reported as unmapped
        // rather than silently emitting partial codegen with an incomplete constructor tree.
        if (anyChildFailed) return null
        return EmbeddedCtorSlot(ctorParamName, typeFqn, childSlots)
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
            if (childProp.simpleName.asString() in childCtorNames) continue
            val hasEmbedded =
                childProp.annotations.any {
                    it.annotationType.resolve().declaration.qualifiedName?.asString() == EMBEDDED_FQN
                }
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
    @Suppress("kotlin:S107")
    private fun buildEmbeddableChildSlot(
        typeDecl: KSClassDeclaration,
        typeFqn: String,
        childParam: KSValueParameter,
        effectivePrefix: String,
        parentPath: String,
        topLevelPropertyName: String,
        columnsAccumulator: MutableList<ColumnMeta>,
        embeddableFiles: MutableSet<KSFile>
    ): CtorSlot? {
        val childParamName = childParam.name?.asString() ?: return null
        val childProp =
            typeDecl.getDeclaredProperties().firstOrNull { it.simpleName.asString() == childParamName } ?: return null

        val childHasElementCollection =
            childProp.annotations.any {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == ELEMENT_COLLECTION_FQN
            }
        if (childHasElementCollection) {
            logger.error(
                "@ElementCollection is not supported inside an @Embeddable. " +
                    "Move the property to the parent entity, or declare a dedicated @Aggregate child entity. " +
                    "Offending property: $typeFqn.$childParamName.",
                childProp
            )
            return null
        }

        val childEmbedded =
            childProp.annotations.firstOrNull {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == EMBEDDED_FQN
            }
        if (childEmbedded != null) {
            if (!validateEmbeddedTargetStrictness(typeDecl, childProp)) return null
            return buildEmbeddedSlot(
                prop = childProp,
                ctorParamName = childParamName,
                embeddedAnnotation = childEmbedded,
                columnsAccumulator = columnsAccumulator,
                parentPrefix = "${effectivePrefix}${autoDerivedPrefix(childParamName)}",
                parentPath = "$parentPath.$childParamName",
                topLevelPropertyName = topLevelPropertyName,
                embeddableFiles = embeddableFiles
            )
        }

        val leafCol =
            columnMetaBuilder.buildEmbeddedLeafColumn(
                childProp = childProp,
                childParamName = childParamName,
                prefix = effectivePrefix,
                parentPath = parentPath,
                topLevelPropertyName = topLevelPropertyName
            ) ?: return null
        columnsAccumulator += leafCol
        return ScalarCtorSlot(childParamName, leafCol)
    }
}