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
 * SQL mode detection uses two mechanisms (either triggers `SqlTableDef` generation):
 * 1. `resolver.getClassDeclarationByName` — works when `lirp-sql` is a project dependency (monorepo)
 * 2. KSP option `lirp.sql=true` — required for external Maven consumers where the resolver cannot
 *    see binary dependencies. Set via `ksp { arg("lirp.sql", "true") }` in `build.gradle`.
 *
 * When generating `SqlTableDef` implementations, the processor emits typed `fromRow` and `toParams`
 * methods with correct Java↔Kotlin type conversions for UUID, Date, DateTime, and Enum properties.
 *
 * Both annotation entry points are supported: a class-level `@PersistenceMapping` and a property-level
 * `@PersistenceProperty` on a class without `@PersistenceMapping` both trigger generation.
 */
class TableDefProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String> = emptyMap()
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val unableToProcess = mutableListOf<KSAnnotated>()
        val classes = mutableSetOf<KSClassDeclaration>()

        // Dual-trigger: collect classes from @PersistenceMapping on class declarations
        for (symbol in resolver.getSymbolsWithAnnotation(PERSISTENCE_MAPPING_FQN)) {
            if (symbol !is KSClassDeclaration) continue
            if (!symbol.validate()) {
                unableToProcess.add(symbol)
                continue
            }
            classes.add(symbol)
        }

        // Dual-trigger: collect classes from @PersistenceProperty on property declarations
        for (symbol in resolver.getSymbolsWithAnnotation(PERSISTENCE_PROPERTY_FQN)) {
            if (symbol !is KSPropertyDeclaration) continue
            val parent = symbol.parentDeclaration as? KSClassDeclaration ?: continue
            if (!parent.validate()) {
                unableToProcess.add(symbol)
                continue
            }
            classes.add(parent)
        }

        // Detect SqlTableDef availability via resolver (monorepo) or KSP option (external consumers).
        // The resolver approach works when lirp-sql is a project dependency. For external Maven
        // consumers, the resolver cannot find SqlTableDef from binary JARs; the ksp { arg("lirp.sql", "true") }
        // option acts as an explicit opt-in for SqlTableDef generation.
        val sqlTableDefAvailable =
            resolver.getClassDeclarationByName(
                resolver.getKSNameFromString(SQL_TABLE_DEF_FQN)
            ) != null ||
                options["lirp.sql"]?.toBoolean() == true

        for (classDecl in classes) {
            generateTableDef(classDecl, sqlTableDefAvailable)
        }

        return unableToProcess
    }

    private fun generateTableDef(classDecl: KSClassDeclaration, sqlTableDefAvailable: Boolean) {
        val packageName = classDecl.packageName.asString()
        val className = classDecl.simpleName.asString()
        val tableDefName = "${className}_LirpTableDef"

        val tableName = resolveTableName(classDecl, className)
        val columns = collectColumns(classDecl)
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
                    "ColumnDef(name = \"${col.name}\", type = ${col.typeExpression}, nullable = ${col.nullable}, primaryKey = ${col.isPrimaryKey})"
                }
            appendLine("        $columnsCode")
        }
        appendLine("    )")
        if (canGenerateSqlMapping) {
            appendLine()
            appendFromRow(className, columns, constructorParamNames)
            appendLine()
            appendToParams(className, columns)
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

    private fun resolveTableName(classDecl: KSClassDeclaration, className: String): String {
        val mappingAnnotation =
            classDecl.annotations.firstOrNull {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == PERSISTENCE_MAPPING_FQN
            }
        val customName = mappingAnnotation?.arguments?.firstOrNull { it.name?.asString() == "name" }?.value as? String
        return if (!customName.isNullOrEmpty()) customName else className.toSnakeCase()
    }

    private fun collectColumns(classDecl: KSClassDeclaration): List<ColumnMeta> {
        val columns = mutableListOf<ColumnMeta>()

        // Detect PK: look for a concrete (non-abstract) 'id' property declared directly on the class.
        // Using getDeclaredProperties() avoids the hasBackingField pitfall on abstract interface properties
        // when the implementing class declares a concrete override.
        val hasDeclaredId = classDecl.getDeclaredProperties().any { it.simpleName.asString() == "id" && !it.isAbstract() }

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

            columns.add(ColumnMeta(columnName, propName, typeExpression, typeFqn, nullable, isPrimaryKey, isEnum, isMutable))
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
    val isMutable: Boolean = false
)