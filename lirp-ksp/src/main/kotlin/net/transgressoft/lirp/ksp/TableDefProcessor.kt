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
import com.google.devtools.ksp.validate

private const val PERSISTENCE_MAPPING_FQN = "net.transgressoft.lirp.persistence.PersistenceMapping"
private const val PERSISTENCE_PROPERTY_FQN = "net.transgressoft.lirp.persistence.PersistenceProperty"
private const val PERSISTENCE_IGNORE_FQN = "net.transgressoft.lirp.persistence.PersistenceIgnore"
private const val REACTIVE_ENTITY_REF_FQN = "net.transgressoft.lirp.persistence.ReactiveEntityRef"
private const val TRANSIENT_FQN = "kotlin.jvm.Transient"

/**
 * KSP processor that generates `_LirpTableDef` descriptor objects for entity classes annotated with
 * [@PersistenceMapping][net.transgressoft.lirp.persistence.PersistenceMapping] or containing
 * properties annotated with [@PersistenceProperty][net.transgressoft.lirp.persistence.PersistenceProperty].
 *
 * The generated objects implement [LirpTableDef][net.transgressoft.lirp.persistence.LirpTableDef]
 * and carry only lirp-api types — no JetBrains Exposed or other SQL dependency is referenced in
 * generated code. The `lirp-sql` module interprets these descriptors at runtime.
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

        for (classDecl in classes) {
            generateTableDef(classDecl)
        }

        return unableToProcess
    }

    private fun generateTableDef(classDecl: KSClassDeclaration) {
        val packageName = classDecl.packageName.asString()
        val className = classDecl.simpleName.asString()
        val tableDefName = "${className}_LirpTableDef"

        val tableName = resolveTableName(classDecl, className)
        val columns = collectColumns(classDecl)

        val file =
            codeGenerator.createNewFile(
                dependencies = Dependencies(false, classDecl.containingFile!!),
                packageName = packageName,
                fileName = tableDefName
            )

        val columnsCode =
            columns.joinToString(",\n        ") { col ->
                "ColumnDef(name = \"${col.name}\", type = ${col.typeExpression}, nullable = ${col.nullable}, primaryKey = ${col.isPrimaryKey})"
            }

        file.write(
            buildString {
                if (packageName.isNotEmpty()) {
                    appendLine("package $packageName")
                    appendLine()
                }
                appendLine("import net.transgressoft.lirp.persistence.ColumnDef")
                appendLine("import net.transgressoft.lirp.persistence.ColumnType")
                appendLine("import net.transgressoft.lirp.persistence.LirpTableDef")
                appendLine()
                appendLine("/** KSP-generated table descriptor for [$className]. */")
                appendLine("public object $tableDefName : LirpTableDef<$className> {")
                appendLine("    override val tableName: String = \"$tableName\"")
                appendLine("    override val columns: List<ColumnDef> = listOf(")
                if (columns.isNotEmpty()) {
                    appendLine("        $columnsCode")
                }
                appendLine("    )")
                appendLine("}")
            }.toByteArray()
        )
        file.close()

        logger.info("Generated $packageName.$tableDefName for $className")
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

            val nullable = prop.type.resolve().isMarkedNullable
            val typeExpression = mapToColumnTypeExpression(prop, persistenceAnnotation) ?: continue

            columns.add(ColumnMeta(columnName, typeExpression, nullable, isPrimaryKey))
        }

        return columns
    }

    private fun KSPropertyDeclaration.isExcluded(): Boolean {
        val annotationFqns =
            annotations
                .map { it.annotationType.resolve().declaration.qualifiedName?.asString() }
                .toSet()
        if (PERSISTENCE_IGNORE_FQN in annotationFqns) return true
        if (REACTIVE_ENTITY_REF_FQN in annotationFqns) return true
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
            "java.util.UUID" -> "ColumnType.UuidType"
            "java.time.LocalDateTime" -> "ColumnType.DateTimeType"
            "java.time.LocalDate" -> "ColumnType.DateType"
            "java.math.BigDecimal" -> "ColumnType.DecimalType($precision, $scale)"
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
            "VARCHAR" -> "ColumnType.VarcharType($length)"
            "INT" -> "ColumnType.IntType"
            "BIGINT" -> "ColumnType.LongType"
            "BOOLEAN" -> "ColumnType.BooleanType"
            "DOUBLE" -> "ColumnType.DoubleType"
            "FLOAT" -> "ColumnType.FloatType"
            "UUID" -> "ColumnType.UuidType"
            "DATE" -> "ColumnType.DateType"
            "DATETIME" -> "ColumnType.DateTimeType"
            "DECIMAL" -> "ColumnType.DecimalType($precision, $scale)"
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
    val typeExpression: String,
    val nullable: Boolean,
    val isPrimaryKey: Boolean
)