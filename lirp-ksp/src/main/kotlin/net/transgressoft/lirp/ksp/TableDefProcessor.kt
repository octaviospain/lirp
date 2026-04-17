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

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate

private const val PERSISTENCE_MAPPING_FQN = "net.transgressoft.lirp.persistence.PersistenceMapping"
private const val PERSISTENCE_PROPERTY_FQN = "net.transgressoft.lirp.persistence.PersistenceProperty"
private const val PERSISTENCE_IGNORE_FQN = "net.transgressoft.lirp.persistence.PersistenceIgnore"
private const val AGGREGATE_ANNOTATION_FQN = "net.transgressoft.lirp.persistence.Aggregate"
private const val VERSION_FQN = "net.transgressoft.lirp.persistence.Version"
private const val TRANSIENT_FQN = "kotlin.jvm.Transient"
private const val SQL_TABLE_DEF_FQN = "net.transgressoft.lirp.persistence.sql.SqlTableDef"
private const val UUID_FQN = "java.util.UUID"
private const val LOCAL_DATE_FQN = "java.time.LocalDate"
private const val LOCAL_DATE_TIME_FQN = "java.time.LocalDateTime"

/**
 * KSP processor that generates `_LirpTableDef` descriptor objects for entity classes annotated with
 * [@PersistenceMapping][net.transgressoft.lirp.persistence.PersistenceMapping] or containing
 * properties annotated with [@PersistenceProperty][net.transgressoft.lirp.persistence.PersistenceProperty].
 *
 * The generated objects conditionally implement either [SqlTableDef][net.transgressoft.lirp.persistence.sql.SqlTableDef]
 * (when `lirp-sql` is available) or [LirpTableDef][net.transgressoft.lirp.persistence.LirpTableDef]
 * (descriptor-only, when `lirp-sql` is absent).
 *
 * SQL mode detection relies solely on `resolver.getClassDeclarationByName` to check if
 * [SqlTableDef][net.transgressoft.lirp.persistence.sql.SqlTableDef] is on the KSP resolver's classpath.
 * For monorepo consumers, the resolver finds `SqlTableDef` because `lirp-sql` is a project dependency.
 * For external consumers, the `net.transgressoft.lirp.sql` Gradle plugin adds `lirp-sql` to the `ksp`
 * configuration, making the resolver find it as well.
 *
 * When `lirp-sql` is not detected, an info-level diagnostic is logged once per processing round
 * and `LirpTableDef` generation is used as fallback.
 *
 * When generating `SqlTableDef` implementations, the processor emits typed `fromRow` and `toParams`
 * methods with correct Java-to-Kotlin type conversions for UUID, Date, DateTime, and Enum properties.
 *
 * Both annotation entry points are supported: a class-level `@PersistenceMapping` and a property-level
 * `@PersistenceProperty` on a class without `@PersistenceMapping` both trigger generation.
 */
class TableDefProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val unableToProcess = mutableListOf<KSAnnotated>()
        val classes = mutableSetOf<KSClassDeclaration>()

        collectPersistenceMappingClasses(resolver, classes, unableToProcess)
        collectPersistencePropertyClasses(resolver, classes, unableToProcess)
        val versionedByClass = collectVersionedProperties(resolver, classes)

        val sqlTableDefAvailable = detectSqlTableDefAvailability(resolver)

        for (classDecl in classes) {
            generateTableDef(classDecl, sqlTableDefAvailable, versionedByClass[classDecl])
        }

        return unableToProcess
    }

    // Dual-trigger: collect classes from @PersistenceMapping on class declarations.
    private fun collectPersistenceMappingClasses(
        resolver: Resolver,
        classes: MutableSet<KSClassDeclaration>,
        unableToProcess: MutableList<KSAnnotated>
    ) {
        for (symbol in resolver.getSymbolsWithAnnotation(PERSISTENCE_MAPPING_FQN)) {
            if (symbol !is KSClassDeclaration) continue
            if (!symbol.validate()) {
                unableToProcess.add(symbol)
                continue
            }
            classes.add(symbol)
        }
    }

    // Dual-trigger: collect classes from @PersistenceProperty on property declarations.
    private fun collectPersistencePropertyClasses(
        resolver: Resolver,
        classes: MutableSet<KSClassDeclaration>,
        unableToProcess: MutableList<KSAnnotated>
    ) {
        for (symbol in resolver.getSymbolsWithAnnotation(PERSISTENCE_PROPERTY_FQN)) {
            if (symbol !is KSPropertyDeclaration) continue
            val parent = symbol.parentDeclaration as? KSClassDeclaration ?: continue
            if (!parent.validate()) {
                unableToProcess.add(symbol)
                continue
            }
            classes.add(parent)
        }
    }

    // Scan for @Version-annotated properties and validate them per D-15. Invalid @Version
    // declarations emit a KSP compile error and are not added to the returned map. Classes using
    // only @Version (no @PersistenceMapping / @PersistenceProperty) are also added to [classes].
    private fun collectVersionedProperties(
        resolver: Resolver,
        classes: MutableSet<KSClassDeclaration>
    ): Map<KSClassDeclaration, KSPropertyDeclaration> {
        val versionedByClass = mutableMapOf<KSClassDeclaration, KSPropertyDeclaration>()
        for (symbol in resolver.getSymbolsWithAnnotation(VERSION_FQN)) {
            if (symbol !is KSPropertyDeclaration) continue
            val parent = symbol.parentDeclaration as? KSClassDeclaration ?: continue
            if (!validateVersionProperty(symbol, parent, versionedByClass)) continue
            versionedByClass[parent] = symbol
            classes.add(parent)
        }
        return versionedByClass
    }

    // Detect SqlTableDef availability via resolver only. The resolver finds SqlTableDef when
    // lirp-sql is a project dependency (monorepo) or when the net.transgressoft.lirp.sql
    // Gradle plugin adds lirp-sql to the ksp configuration (external consumers). Maven users
    // must add lirp-sql as a processor dependency in the KSP Maven plugin config.
    private fun detectSqlTableDefAvailability(resolver: Resolver): Boolean {
        val available =
            resolver.getClassDeclarationByName(
                resolver.getKSNameFromString(SQL_TABLE_DEF_FQN)
            ) != null
        if (!available) {
            logger.info(
                "lirp-sql not detected on classpath — generating LirpTableDef. " +
                    "Add lirp-sql dependency and apply the net.transgressoft.lirp.sql Gradle plugin for SqlTableDef generation."
            )
        }
        return available
    }

    /**
     * Validates a @Version property per D-15 — type must be non-nullable `kotlin.Long`, must be
     * declared with `var`, must be delegated (reactiveProperty or equivalent), and at most one
     * @Version per class. Emits [KSPLogger.error] on violation and returns `false`.
     */
    private fun validateVersionProperty(
        prop: KSPropertyDeclaration,
        parent: KSClassDeclaration,
        alreadyFound: Map<KSClassDeclaration, KSPropertyDeclaration>
    ): Boolean {
        val className = parent.simpleName.asString()
        val propName = prop.simpleName.asString()

        // D-15: at most one @Version per class.
        if (parent in alreadyFound) {
            logger.error(
                "Class '$className' has multiple @Version properties " +
                    "('${alreadyFound.getValue(parent).simpleName.asString()}' and '$propName'); only one is allowed.",
                prop
            )
            return false
        }

        // D-15: type must be exactly kotlin.Long (non-nullable).
        val resolved = prop.type.resolve()
        val typeFqn = resolved.makeNotNullable().declaration.qualifiedName?.asString()
        if (typeFqn != "kotlin.Long" || resolved.isMarkedNullable) {
            val found = typeFqn ?: "unresolved"
            val suffix = if (resolved.isMarkedNullable) "?" else ""
            logger.error(
                "@Version property '$className.$propName' must be of type 'Long' (not nullable). Found: '$found$suffix'.",
                prop
            )
            return false
        }

        // D-15: must be var.
        if (!prop.isMutable) {
            logger.error(
                "@Version property '$className.$propName' must be declared with 'var' (not 'val').",
                prop
            )
            return false
        }

        // D-15: must use the reactiveProperty delegate (enforcement via isDelegated as a
        // necessary-but-not-sufficient check per RESEARCH.md Example 2).
        if (!prop.isDelegated()) {
            logger.error(
                "@Version property '$className.$propName' must use the 'reactiveProperty' delegate " +
                    "(e.g., 'var version: Long by reactiveProperty(0L)').",
                prop
            )
            return false
        }

        return true
    }

    private fun generateTableDef(
        classDecl: KSClassDeclaration,
        sqlTableDefAvailable: Boolean,
        versionedProperty: KSPropertyDeclaration?
    ) {
        val packageName = classDecl.packageName.asString()
        val className = classDecl.simpleName.asString()
        val tableDefName = "${className}_LirpTableDef"

        val tableName = resolveTableName(classDecl, className)
        val columns = collectColumns(classDecl, versionedProperty)
        // Ordered constructor parameter names — preserves declaration order for correct fromRow() generation.
        val constructorParamNames =
            classDecl.primaryConstructor?.parameters
                ?.mapNotNull { it.name?.asString() } ?: emptyList()
        val columnNames = columns.map { it.propertyName }.toSet()
        val unmappedCtorParams = constructorParamNames.filter { it !in columnNames }

        // Generate SqlTableDef only when: (1) all non-PK columns are mutable, and (2) every constructor
        // parameter maps to a known column. Unmapped params would produce invalid constructor calls.
        val canGenerateSqlMapping =
            sqlTableDefAvailable &&
                columns.filter { !it.isPrimaryKey }.all { it.isMutable } &&
                unmappedCtorParams.isEmpty()
        if (unmappedCtorParams.isNotEmpty() && sqlTableDefAvailable) {
            logger.warn("$className: constructor params $unmappedCtorParams have no matching columns; falling back to LirpTableDef")
        }

        val file =
            codeGenerator.createNewFile(
                dependencies = Dependencies(false, classDecl.containingFile!!),
                packageName = packageName,
                fileName = tableDefName
            )

        file.write(
            buildString {
                appendPackageAndImports(packageName, canGenerateSqlMapping, columns)
                appendObjectBody(tableDefName, className, tableName, canGenerateSqlMapping, columns, constructorParamNames)
            }.toByteArray()
        )
        file.close()

        logger.info("Generated $packageName.$tableDefName for $className (sqlTableDef=$canGenerateSqlMapping)")
    }

    private fun StringBuilder.appendPackageAndImports(
        packageName: String,
        canGenerateSqlMapping: Boolean,
        columns: List<ColumnMeta>
    ) {
        if (packageName.isNotEmpty()) {
            appendLine("package $packageName")
            appendLine()
        }
        appendLine("import net.transgressoft.lirp.persistence.ColumnDef")
        appendLine("import net.transgressoft.lirp.persistence.ColumnType")
        if (canGenerateSqlMapping) {
            appendLine("import net.transgressoft.lirp.persistence.LirpTableDef")
            appendLine("import net.transgressoft.lirp.persistence.sql.SqlTableDef")
            appendLine("import org.jetbrains.exposed.v1.core.Column")
            appendLine("import org.jetbrains.exposed.v1.core.ResultRow")
            appendLine("import org.jetbrains.exposed.v1.core.Table")
            appendConditionalTypeImports(columns)
        } else {
            appendLine("import net.transgressoft.lirp.persistence.LirpTableDef")
        }
        appendLine()
    }

    private fun StringBuilder.appendConditionalTypeImports(columns: List<ColumnMeta>) {
        if (columns.any { it.typeFqn == UUID_FQN }) {
            appendLine("import kotlin.uuid.ExperimentalUuidApi")
            appendLine("import kotlin.uuid.toJavaUuid")
            appendLine("import kotlin.uuid.toKotlinUuid")
        }
        if (columns.any { it.typeFqn == LOCAL_DATE_FQN }) {
            appendLine("import kotlinx.datetime.toJavaLocalDate")
            appendLine("import kotlinx.datetime.toKotlinLocalDate")
        }
        if (columns.any { it.typeFqn == LOCAL_DATE_TIME_FQN }) {
            appendLine("import kotlinx.datetime.toJavaLocalDateTime")
            appendLine("import kotlinx.datetime.toKotlinLocalDateTime")
        }
        if (columns.any { it.typeExpression.startsWith("ColumnType.DecimalType") }) {
            appendLine("import java.math.BigDecimal")
        }
        columns.filter { it.isEnum }.map { it.typeFqn }.distinct().forEach { fqn ->
            appendLine("import $fqn")
        }
    }

    private fun StringBuilder.appendObjectBody(
        tableDefName: String,
        className: String,
        tableName: String,
        canGenerateSqlMapping: Boolean,
        columns: List<ColumnMeta>,
        constructorParamNames: List<String> = emptyList()
    ) {
        appendLine("/** KSP-generated table descriptor for [$className]. */")
        if (canGenerateSqlMapping && columns.any { it.typeFqn == UUID_FQN }) {
            appendLine("@OptIn(ExperimentalUuidApi::class)")
        }
        val superType = if (canGenerateSqlMapping) "SqlTableDef<$className>" else "LirpTableDef<$className>"
        appendLine("public object $tableDefName : $superType {")
        appendLine("    override val tableName: String = \"$tableName\"")
        appendLine("    override val columns: List<ColumnDef> = listOf(")
        if (columns.isNotEmpty()) {
            val columnsCode =
                columns.joinToString(",\n        ") { col ->
                    "ColumnDef(name = \"${col.name}\", type = ${col.typeExpression}, " +
                        "nullable = ${col.nullable}, primaryKey = ${col.isPrimaryKey}, isVersion = ${col.isVersion})"
                }
            appendLine("        $columnsCode")
        }
        appendLine("    )")
        if (canGenerateSqlMapping) {
            appendLine()
            appendFromRow(className, columns, constructorParamNames)
            appendLine()
            appendToParams(className, columns)
            appendLine()
            appendApplyRow(className, columns)
            appendBumpVersion(className, columns)
        }
        appendLine("}")
    }

    private fun StringBuilder.appendFromRow(
        className: String,
        columns: List<ColumnMeta>,
        constructorParamNames: List<String>
    ) {
        val columnsByName = columns.associateBy { it.propertyName }
        // Preserve constructor parameter declaration order for correct positional arguments
        val orderedCtorCols = constructorParamNames.mapNotNull { columnsByName[it] }
        val ctorParamNameSet = constructorParamNames.toSet()
        val setterCols = columns.filter { it.propertyName !in ctorParamNameSet }

        appendLine("    override fun fromRow(row: ResultRow, table: Table): $className {")
        val ctorArgs = orderedCtorCols.joinToString(", ") { buildRowAccess(it) }
        appendLine("        val entity = $className($ctorArgs)")
        for (col in setterCols) {
            val rowAccess = buildRowAccess(col)
            appendLine("        entity.${col.propertyName} = $rowAccess")
        }
        appendLine("        return entity")
        appendLine("    }")
    }

    private fun buildRowAccess(col: ColumnMeta): String {
        val rawAccess = "row[table.columns.first { it.name == \"${col.name}\" }]"
        return when {
            col.typeFqn == UUID_FQN && col.nullable -> "($rawAccess as? kotlin.uuid.Uuid)?.toJavaUuid()"
            col.typeFqn == UUID_FQN -> "($rawAccess as kotlin.uuid.Uuid).toJavaUuid()"
            col.typeFqn == LOCAL_DATE_FQN && col.nullable -> "($rawAccess as? kotlinx.datetime.LocalDate)?.toJavaLocalDate()"
            col.typeFqn == LOCAL_DATE_FQN -> "($rawAccess as kotlinx.datetime.LocalDate).toJavaLocalDate()"
            col.typeFqn == LOCAL_DATE_TIME_FQN && col.nullable -> "($rawAccess as? kotlinx.datetime.LocalDateTime)?.toJavaLocalDateTime()"
            col.typeFqn == LOCAL_DATE_TIME_FQN -> "($rawAccess as kotlinx.datetime.LocalDateTime).toJavaLocalDateTime()"
            col.isEnum && col.nullable -> {
                val enumSimpleName = col.typeFqn.substringAfterLast(".")
                "($rawAccess as? String)?.let { enumValueOf<$enumSimpleName>(it) }"
            }
            col.isEnum -> {
                val enumSimpleName = col.typeFqn.substringAfterLast(".")
                "enumValueOf<$enumSimpleName>($rawAccess as String)"
            }
            col.nullable -> "$rawAccess as? ${col.typeFqn.substringAfterLast(".")}"
            else -> "$rawAccess as ${col.typeFqn.substringAfterLast(".")}"
        }
    }

    private fun StringBuilder.appendToParams(className: String, columns: List<ColumnMeta>) {
        appendLine("    override fun toParams(entity: $className, table: Table): Map<Column<*>, Any?> {")
        appendLine("        val cols = table.columns.associateBy { it.name }")
        appendLine("        return mapOf(")
        val paramEntries =
            columns.joinToString(",\n            ") { col ->
                val valueAccess = buildEntityAccess(col)
                "cols[\"${col.name}\"]!! to $valueAccess"
            }
        appendLine("            $paramEntries")
        appendLine("        )")
        appendLine("    }")
    }

    private fun buildEntityAccess(col: ColumnMeta): String {
        val prop = "entity.${col.propertyName}"
        return when {
            col.typeFqn == UUID_FQN && col.nullable -> "$prop?.toKotlinUuid()"
            col.typeFqn == UUID_FQN -> "$prop.toKotlinUuid()"
            col.typeFqn == LOCAL_DATE_FQN && col.nullable -> "$prop?.toKotlinLocalDate()"
            col.typeFqn == LOCAL_DATE_FQN -> "$prop.toKotlinLocalDate()"
            col.typeFqn == LOCAL_DATE_TIME_FQN && col.nullable -> "$prop?.toKotlinLocalDateTime()"
            col.typeFqn == LOCAL_DATE_TIME_FQN -> "$prop.toKotlinLocalDateTime()"
            col.isEnum && col.nullable -> "$prop?.name"
            col.isEnum -> "$prop.name"
            else -> prop
        }
    }

    private fun StringBuilder.appendApplyRow(className: String, columns: List<ColumnMeta>) {
        // applyRow overwrites the state of an existing entity — skip primary-key columns
        // (they are immutable post-construction) and any non-mutable property. Reuse the same
        // `buildRowAccess` helper as fromRow so UUID/LocalDate/Enum conversions stay consistent.
        val mutableNonPk = columns.filter { !it.isPrimaryKey && it.isMutable }
        appendLine("    override fun applyRow(entity: $className, row: ResultRow, table: Table) {")
        if (mutableNonPk.isEmpty()) {
            appendLine("        // No mutable non-PK columns — applyRow is a no-op.")
        } else {
            for (col in mutableNonPk) {
                val rowAccess = buildRowAccess(col)
                appendLine("        entity.${col.propertyName} = $rowAccess")
            }
        }
        appendLine("    }")
    }

    private fun StringBuilder.appendBumpVersion(className: String, columns: List<ColumnMeta>) {
        // Emit a non-default bumpVersion override only when the entity declares a @Version
        // column. Unversioned entities inherit the interface no-op default, so no emission keeps
        // the generated file minimal.
        val versionCol = columns.singleOrNull { it.isVersion } ?: return
        appendLine()
        appendLine("    override fun bumpVersion(entity: $className, newVersion: Long) {")
        appendLine("        entity.${versionCol.propertyName} = newVersion")
        appendLine("    }")
    }

    private fun resolveTableName(classDecl: KSClassDeclaration, className: String): String {
        val mappingAnnotation =
            classDecl.annotations.firstOrNull {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == PERSISTENCE_MAPPING_FQN
            }
        val customName = mappingAnnotation?.arguments?.firstOrNull { it.name?.asString() == "name" }?.value as? String
        return if (!customName.isNullOrEmpty()) customName else className.toSnakeCase()
    }

    private fun collectColumns(
        classDecl: KSClassDeclaration,
        versionedProperty: KSPropertyDeclaration?
    ): List<ColumnMeta> {
        val columns = mutableListOf<ColumnMeta>()

        // Detect PK: look for a concrete (non-abstract) 'id' property declared directly on the class.
        // Using getDeclaredProperties() avoids the hasBackingField pitfall on abstract interface properties
        // when the implementing class declares a concrete override.
        val hasDeclaredId = classDecl.getDeclaredProperties().any { it.simpleName.asString() == "id" && !it.isAbstract() }

        val versionedName = versionedProperty?.simpleName?.asString()

        for (prop in classDecl.getAllProperties()) {
            if (prop.isExcluded()) continue

            val propName = prop.simpleName.asString()
            val isPrimaryKey = propName == "id" && hasDeclaredId && !prop.isAbstract()

            val persistenceAnnotation =
                prop.annotations.firstOrNull {
                    it.annotationType.resolve().declaration.qualifiedName?.asString() == PERSISTENCE_PROPERTY_FQN
                }

            val columnName =
                if (persistenceAnnotation != null) {
                    val customName = persistenceAnnotation.arguments.firstOrNull { it.name?.asString() == "name" }?.value as? String
                    if (!customName.isNullOrEmpty()) customName else propName.toSnakeCase()
                } else {
                    propName.toSnakeCase()
                }

            val resolvedType = prop.type.resolve()
            val nullable = resolvedType.isMarkedNullable
            val notNullableType = resolvedType.makeNotNullable()
            val typeFqn = notNullableType.declaration.qualifiedName?.asString() ?: "kotlin.Any"
            val isEnum = (notNullableType.declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS
            // Mutable for SqlTableDef fromRow purposes means: var property with a public setter
            val setterIsPublic =
                prop.setter?.modifiers?.none {
                    it == Modifier.PROTECTED || it == Modifier.PRIVATE || it == Modifier.INTERNAL
                } ?: true
            val isMutable = prop.isMutable && setterIsPublic
            val typeExpression = mapToColumnTypeExpression(prop, persistenceAnnotation) ?: continue

            val isVersion = versionedName != null && propName == versionedName

            columns.add(ColumnMeta(columnName, propName, typeExpression, typeFqn, nullable, isPrimaryKey, isEnum, isMutable, isVersion))
        }

        return columns
    }

    private fun KSPropertyDeclaration.isExcluded(): Boolean {
        val annotationFqns =
            annotations
                .map { it.annotationType.resolve().declaration.qualifiedName?.asString() }
                .toSet()
        if (PERSISTENCE_IGNORE_FQN in annotationFqns) return true
        if (AGGREGATE_ANNOTATION_FQN in annotationFqns) return true
        if (TRANSIENT_FQN in annotationFqns) return true
        // Exclude computed properties (no backing field, not delegated), but include delegate-backed properties
        if (!hasBackingField && !isDelegated()) return true
        return false
    }

    private fun mapToColumnTypeExpression(
        prop: KSPropertyDeclaration,
        persistenceAnnotation: KSAnnotation?
    ): String? {
        val resolvedType = prop.type.resolve()
        val notNullableType = resolvedType.makeNotNullable()
        val fqn = notNullableType.declaration.qualifiedName?.asString()

        val length = persistenceAnnotation?.arguments?.firstOrNull { it.name?.asString() == "length" }?.value as? Int ?: -1
        val precision = persistenceAnnotation?.arguments?.firstOrNull { it.name?.asString() == "precision" }?.value as? Int ?: -1
        val scale = persistenceAnnotation?.arguments?.firstOrNull { it.name?.asString() == "scale" }?.value as? Int ?: -1
        val typeHint = persistenceAnnotation?.arguments?.firstOrNull { it.name?.asString() == "type" }?.value as? String ?: ""

        // Explicit type hint takes precedence over FQN-based inference
        if (typeHint.isNotEmpty()) {
            return mapTypeHintToExpression(typeHint, length, precision, scale, prop.simpleName.asString())
        }

        return when (fqn) {
            "kotlin.Int" -> "ColumnType.IntType"
            "kotlin.Long" -> "ColumnType.LongType"
            "kotlin.String" -> if (length > 0) "ColumnType.VarcharType($length)" else "ColumnType.TextType"
            "kotlin.Boolean" -> "ColumnType.BooleanType"
            "kotlin.Double" -> "ColumnType.DoubleType"
            "kotlin.Float" -> "ColumnType.FloatType"
            UUID_FQN -> "ColumnType.UuidType"
            LOCAL_DATE_TIME_FQN -> "ColumnType.DateTimeType"
            LOCAL_DATE_FQN -> "ColumnType.DateType"
            "java.math.BigDecimal" -> {
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

    private fun mapTypeHintToExpression(
        hint: String,
        length: Int,
        precision: Int,
        scale: Int,
        propName: String
    ): String? =
        when (hint.uppercase()) {
            "TEXT" -> "ColumnType.TextType"
            "VARCHAR" -> {
                if (length <= 0) {
                    logger.error("@PersistenceProperty(type=\"VARCHAR\") requires length > 0 on property '$propName'")
                    return null
                }
                "ColumnType.VarcharType($length)"
            }
            "INT" -> "ColumnType.IntType"
            "BIGINT" -> "ColumnType.LongType"
            "BOOLEAN" -> "ColumnType.BooleanType"
            "DOUBLE" -> "ColumnType.DoubleType"
            "FLOAT" -> "ColumnType.FloatType"
            "UUID" -> "ColumnType.UuidType"
            "DATE" -> "ColumnType.DateType"
            "DATETIME" -> "ColumnType.DateTimeType"
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

private fun String.toSnakeCase(): String =
    replace(Regex("([a-z\\d])([A-Z])"), "$1_$2")
        .replace(Regex("([A-Z]+)([A-Z][a-z])"), "$1_$2")
        .lowercase()

private data class ColumnMeta(
    val name: String,
    val propertyName: String,
    val typeExpression: String,
    val typeFqn: String,
    val nullable: Boolean,
    val isPrimaryKey: Boolean,
    val isEnum: Boolean = false,
    val isMutable: Boolean = false,
    val isVersion: Boolean = false
)