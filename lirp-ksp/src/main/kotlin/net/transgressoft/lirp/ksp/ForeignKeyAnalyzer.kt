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

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType

/**
 * Analyzes `@Aggregate` properties to produce foreign-key constraints and junction-table
 * descriptors. Handles:
 * - Single-entity aggregates (`aggregate { … }`): resolved to [ForeignKeyMeta] entries that drive
 *   the `foreignKeys()` override on the parent's `_LirpTableDef`.
 * - Collection aggregates (`aggregateList(…)` / `aggregateSet(…)`): resolved to [JunctionRefInfo]
 *   entries and generates the `{Parent}_{Property}_LirpJunctionTableDef` companion objects.
 *
 * All structural validation (FK-04, FK-05 error codes) is performed here so that the processor
 * orchestrator receives only validated, ready-to-emit metadata.
 */
internal class ForeignKeyAnalyzer(
    private val logger: KSPLogger,
    private val codeGenerator: CodeGenerator,
    private val columnMetaBuilder: ColumnMetaBuilder
) {

    /**
     * Builds the [ForeignKeyMeta] list for an entity by walking its single-entity `@Aggregate`
     * properties. Collection refs are skipped — they are handled by junction-table descriptors.
     *
     * Validates each single-entity ref:
     *  - The lambda body of `aggregate { … }` must be a bare identifier naming the backing scalar.
     *  - The backing scalar property must exist on the same class.
     *  - `@Aggregate(onDelete = DETACH)` requires the backing scalar to be nullable (Spike 006).
     *
     * Drops entries with `onDelete = NONE` — by convention, NONE means "no FK clause at all".
     */
    fun collectForeignKeys(
        classDecl: KSClassDeclaration,
        aggregates: List<AggregatePropertyMeta>
    ): List<ForeignKeyMeta> {
        if (aggregates.isEmpty()) return emptyList()

        val foreignKeys = mutableListOf<ForeignKeyMeta>()
        val propertiesByName = classDecl.getAllProperties().associateBy { it.simpleName.asString() }

        for (agg in aggregates.filter { !it.isCollection }) {
            val propName = agg.propertyName
            val scalarName =
                agg.backingScalarName
                    ?: run {
                        logger.error(
                            "Cannot determine backing scalar for @Aggregate property '$propName'. " +
                                "The aggregate { … } lambda must reference exactly one scalar property.",
                            agg.property
                        )
                        continue
                    }

            val scalarProp = propertiesByName[scalarName]
            if (scalarProp == null) {
                logger.error(
                    "@Aggregate property '$propName' references unknown scalar '$scalarName'.",
                    agg.property
                )
                continue
            }

            val onDelete = agg.onDeleteName
            if (onDelete == "DETACH" && !scalarProp.type.resolve().isMarkedNullable) {
                logger.error(
                    "@Aggregate(onDelete = DETACH) on property '$propName' requires a nullable backing scalar. " +
                        "Make '$scalarName' nullable (e.g., 'Long?') or choose a different CascadeAction " +
                        "(RESTRICT, CASCADE, NONE).",
                    agg.property
                )
                continue
            }

            // NONE => emit no FK clause at all (preserves backwards compatibility per Spike 006).
            if (onDelete == "NONE") continue

            val referencedTableName = resolveTableName(agg.referencedClass, agg.referencedClass.simpleName.asString())

            // Resolve the local FK column name using @PersistenceProperty(name=...) on the backing
            // scalar, mirroring the column-name resolution logic in collectColumns().
            val scalarPersistenceAnnotation =
                scalarProp.annotations.firstOrNull {
                    it.annotationType.resolve().declaration.qualifiedName?.asString() == PERSISTENCE_PROPERTY_FQN
                }
            val localColumnName =
                (
                    scalarPersistenceAnnotation
                        ?.arguments
                        ?.firstOrNull { it.name?.asString() == "name" }
                        ?.value as? String
                )
                    ?.takeIf { it.isNotEmpty() }
                    ?: scalarName.toSnakeCase()

            // Resolve the referenced entity's PK column name using @PersistenceProperty(name=...)
            // on the 'id' property of the referenced entity class.
            val referencedIdPropForFk =
                agg.referencedClass.getAllProperties()
                    .firstOrNull { it.simpleName.asString() == "id" }
            val referencedPkAnnotation =
                referencedIdPropForFk?.annotations?.firstOrNull {
                    it.annotationType.resolve().declaration.qualifiedName?.asString() == PERSISTENCE_PROPERTY_FQN
                }
            val referencedColumnName =
                (
                    referencedPkAnnotation
                        ?.arguments
                        ?.firstOrNull { it.name?.asString() == "name" }
                        ?.value as? String
                )
                    ?.takeIf { it.isNotEmpty() }
                    ?: "id"

            foreignKeys.add(
                ForeignKeyMeta(
                    columnName = localColumnName,
                    referencedTable = referencedTableName,
                    referencedColumn = referencedColumnName,
                    onDelete = onDelete
                )
            )
        }
        return foreignKeys
    }

    /**
     * Builds the [JunctionRefInfo] list for one entity by walking its collection-typed `@Aggregate`
     * properties.
     *
     * Validates each collection ref:
     *  - The first positional argument of `aggregateList(…)` / `aggregateSet(…)` must be a bare
     *    identifier naming a property on the same class.
     *  - That property must be `var`, with a stdlib `kotlin.collections.List`,
     *    `kotlin.collections.MutableList`, `kotlin.collections.Set`, or
     *    `kotlin.collections.MutableSet` type whose element type matches the item entity's `id`
     *    type.
     *  - For `aggregateList`, the property must be a `List` / `MutableList`. For `aggregateSet`,
     *    it must be a `Set` / `MutableSet`.
     *
     * Failures emit `KSP[FK-04]` errors and skip emission for the affected entity. The successful
     * entries drive the `junctionTableDefs` / `junctionAccessors` / `applyJunctionRows` overrides
     * on the parent's `_LirpTableDef`.
     */
    fun collectJunctionRefs(
        classDecl: KSClassDeclaration,
        aggregates: List<AggregatePropertyMeta>
    ): List<JunctionRefInfo> {
        val collectionAggs = aggregates.filter { it.isCollection }
        if (collectionAggs.isEmpty()) return emptyList()

        val propertiesByName = classDecl.getAllProperties().associateBy { it.simpleName.asString() }
        val parentSimpleName = classDecl.simpleName.asString()
        return collectionAggs.mapNotNull { buildJunctionRefInfo(it, parentSimpleName, propertiesByName) }
    }

    /**
     * Emits a `{Parent}_{Property}_LirpJunctionTableDef` object that implements `JunctionTableDef`
     * for one collection-typed `@Aggregate` property.
     *
     * The descriptor is the SQL-side companion of the parent's `_LirpTableDef` and lives in the
     * same package. Its column shape is fixed: `(parent_id, item_id)` always form the composite
     * primary key; `position` is appended for `aggregateList` and omitted for `aggregateSet`.
     */
    fun generateJunctionTableDef(
        parentClass: KSClassDeclaration,
        agg: AggregatePropertyMeta
    ) {
        val packageName = parentClass.packageName.asString()
        val parentSimpleName = parentClass.simpleName.asString()
        val itemSimpleName = agg.referencedClass.simpleName.asString()
        val propertyCapitalized = agg.propertyName.replaceFirstChar { it.uppercase() }
        val descriptorName = "${parentSimpleName}_${propertyCapitalized}_LirpJunctionTableDef"

        val parentTableName = resolveTableName(parentClass, parentSimpleName)
        val itemTableName = resolveTableName(agg.referencedClass, itemSimpleName)
        val junctionTableName = "${parentTableName}_${agg.propertyName.toSnakeCase()}"

        val parentPkType = pkColumnTypeExpression(parentClass) ?: COLUMN_TYPE_INT_EXPR
        val itemPkType = pkColumnTypeExpression(agg.referencedClass) ?: COLUMN_TYPE_INT_EXPR

        val file =
            codeGenerator.createNewFile(
                dependencies = Dependencies(false, parentClass.containingFile!!),
                packageName = packageName,
                fileName = descriptorName
            )

        // Item-side cascade action defaults to DETACH per @Aggregate's annotation default; that
        // mirrors the existing in-memory behaviour for collection refs and is what consumers see
        // when they add @Aggregate without arguments.
        val itemOnDelete = agg.onDeleteName

        file.write(
            buildString {
                if (packageName.isNotEmpty()) {
                    appendLine("package $packageName")
                    appendLine()
                }
                appendLine("import net.transgressoft.lirp.entity.CascadeAction")
                appendLine("import net.transgressoft.lirp.persistence.ColumnType")
                appendLine("import net.transgressoft.lirp.persistence.sql.JunctionColumnDef")
                appendLine("import net.transgressoft.lirp.persistence.sql.JunctionTableDef")
                appendLine()
                appendLine("/** KSP-generated junction table descriptor for $parentSimpleName.${agg.propertyName} → $itemSimpleName. */")
                appendLine("public object $descriptorName : JunctionTableDef {")
                appendLine("    override val tableName: String = \"$junctionTableName\"")
                appendLine("    override val parentTableName: String = \"$parentTableName\"")
                appendLine("    override val itemTableName: String = \"$itemTableName\"")
                appendLine("    override val isOrdered: Boolean = ${agg.isOrdered}")
                appendLine("    override val parentFkOnDelete: CascadeAction = CascadeAction.CASCADE")
                appendLine("    override val itemFkOnDelete: CascadeAction = CascadeAction.$itemOnDelete")
                appendLine("    override val columns: List<JunctionColumnDef> = listOf(")
                appendLine("        JunctionColumnDef(name = \"parent_id\", type = $parentPkType, primaryKey = true),")
                if (agg.isOrdered) {
                    appendLine("        JunctionColumnDef(name = \"item_id\", type = $itemPkType, primaryKey = true),")
                    appendLine("        JunctionColumnDef(name = \"position\", type = ColumnType.IntType)")
                } else {
                    appendLine("        JunctionColumnDef(name = \"item_id\", type = $itemPkType, primaryKey = true)")
                }
                appendLine("    )")
                appendLine("}")
            }.toByteArray()
        )
        file.close()

        logger.info("Generated $packageName.$descriptorName for $parentSimpleName.${agg.propertyName}")
    }

    fun extractCascadeActionName(value: Any?): String =
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

    private fun buildJunctionRefInfo(
        agg: AggregatePropertyMeta,
        parentSimpleName: String,
        propertiesByName: Map<String, KSPropertyDeclaration>
    ): JunctionRefInfo? {
        val backingName =
            agg.backingCollectionName
                ?: return logFk04MissingBacking(agg, parentSimpleName).let { null }
        val backingProp =
            propertiesByName[backingName]
                ?: return logFk04MissingBackingProp(agg, parentSimpleName, backingName).let { null }
        if (!backingProp.isMutable) return logFk04ImmutableBacking(agg, parentSimpleName, backingName).let { null }

        val resolvedType = backingProp.type.resolve()
        val typeFqn = resolvedType.makeNotNullable().declaration.qualifiedName?.asString()
        if (!validateBackingShape(agg, parentSimpleName, backingName, typeFqn)) return null

        val elementFqn =
            resolvedType.arguments.firstOrNull()?.type?.resolve()
                ?.makeNotNullable()?.declaration?.qualifiedName?.asString()
        if (!validateElementMatchesReferencedId(agg, backingName, elementFqn)) return null

        val itemSimpleName = elementFqnToSimpleName(elementFqn)
        val propertyCapitalized = agg.propertyName.replaceFirstChar { it.uppercase() }
        val descriptorName = "${parentSimpleName}_${propertyCapitalized}_LirpJunctionTableDef"
        val isMutableCollection = typeFqn == "kotlin.collections.MutableList" || typeFqn == "kotlin.collections.MutableSet"

        return JunctionRefInfo(
            propertyName = agg.propertyName,
            backingFieldName = backingName,
            junctionObjectName = descriptorName,
            isOrdered = agg.isOrdered,
            itemKeyTypeSimpleName = itemSimpleName,
            isMutableList = isMutableCollection
        )
    }

    private fun logFk04MissingBacking(agg: AggregatePropertyMeta, parentSimpleName: String) {
        logger.error(
            "KSP[FK-04]: @Aggregate collection property '${agg.propertyName}' on " +
                "'$parentSimpleName' must be a 'var List<K>'/'var Set<K>' bound to a writable " +
                "backing field passed as the first positional argument to " +
                "${if (agg.isOrdered) "aggregateList" else "aggregateSet"}(<field>). " +
                "Anonymous initialisers like 'emptyList()' or 'setOf(...)' are not supported.",
            agg.property
        )
    }

    private fun logFk04MissingBackingProp(agg: AggregatePropertyMeta, parentSimpleName: String, backingName: String) {
        logger.error(
            "KSP[FK-04]: backing field '$backingName' for @Aggregate property " +
                "'${agg.propertyName}' on '$parentSimpleName' must be a 'var List<K>'/" +
                "'var Set<K>' declared on the same class.",
            agg.property
        )
    }

    private fun logFk04ImmutableBacking(agg: AggregatePropertyMeta, parentSimpleName: String, backingName: String) {
        logger.error(
            "KSP[FK-04]: backing field '$backingName' for @Aggregate property " +
                "'${agg.propertyName}' on '$parentSimpleName' must be a 'var List<K>'/" +
                "'var Set<K>' (declared 'val').",
            agg.property
        )
    }

    private fun validateBackingShape(
        agg: AggregatePropertyMeta,
        parentSimpleName: String,
        backingName: String,
        typeFqn: String?
    ): Boolean {
        val isList = typeFqn == "kotlin.collections.List" || typeFqn == "kotlin.collections.MutableList"
        val isSet = typeFqn == "kotlin.collections.Set" || typeFqn == "kotlin.collections.MutableSet"
        if (agg.isOrdered && !isList) {
            logger.error(
                "KSP[FK-04]: backing field '$backingName' for @Aggregate property " +
                    "'${agg.propertyName}' on '$parentSimpleName' must be a 'var List<K>' for " +
                    "aggregateList; found '$typeFqn'.",
                agg.property
            )
            return false
        }
        if (!agg.isOrdered && !isSet) {
            logger.error(
                "KSP[FK-04]: backing field '$backingName' for @Aggregate property " +
                    "'${agg.propertyName}' on '$parentSimpleName' must be a 'var Set<K>' for " +
                    "aggregateSet; found '$typeFqn'.",
                agg.property
            )
            return false
        }
        return true
    }

    private fun validateElementMatchesReferencedId(
        agg: AggregatePropertyMeta,
        backingName: String,
        elementFqn: String?
    ): Boolean {
        // KSP[FK-05]: verify the backing collection's element type matches the referenced
        // entity's ID type. A mismatch (e.g. List<Int> backing an aggregate whose target uses
        // Long ids) is silently accepted by the compiler but causes filterIsInstance to drop
        // all loaded IDs at runtime.
        val referencedIdProp =
            agg.referencedClass.getAllProperties()
                .firstOrNull { it.simpleName.asString() == "id" } ?: return true
        val referencedIdFqn =
            referencedIdProp.type.resolve().makeNotNullable()
                .declaration.qualifiedName?.asString() ?: return true
        if (elementFqn == null || elementFqn == referencedIdFqn) return true

        // Normalize kotlin.UUID alias to java.util.UUID for comparison.
        val normalizedElement = if (elementFqn == KOTLIN_UUID_FQN) UUID_FQN else elementFqn
        val normalizedRefId = if (referencedIdFqn == KOTLIN_UUID_FQN) UUID_FQN else referencedIdFqn
        if (normalizedElement == normalizedRefId) return true

        logger.error(
            "KSP[FK-05]: backing field '$backingName' element type '$elementFqn' does not match " +
                "the referenced entity '${agg.referencedClass.simpleName.asString()}' ID type " +
                "'$referencedIdFqn'. Fix the backing collection's type parameter to match.",
            agg.property
        )
        return false
    }

    private fun elementFqnToSimpleName(elementFqn: String?): String =
        when (elementFqn) {
            KOTLIN_INT_FQN -> "Int"
            KOTLIN_LONG_FQN -> "Long"
            KOTLIN_STRING_FQN -> "String"
            KOTLIN_UUID_FQN, UUID_FQN -> "java.util.UUID"
            null -> "Any"
            else -> elementFqn.substringAfterLast(".")
        }

    private fun pkColumnTypeExpression(classDecl: KSClassDeclaration): String? {
        val idProp = classDecl.getAllProperties().firstOrNull { it.simpleName.asString() == "id" } ?: return null
        return columnMetaBuilder.mapToColumnTypeExpression(idProp, persistenceAnnotation = null)
    }

    private fun resolveTableName(classDecl: KSClassDeclaration, className: String): String {
        val mappingAnnotation =
            classDecl.annotations.firstOrNull {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == PERSISTENCE_MAPPING_FQN
            }
        val customName = mappingAnnotation?.arguments?.firstOrNull { it.name?.asString() == "name" }?.value as? String
        return if (!customName.isNullOrEmpty()) customName else className.toSnakeCase()
    }
}