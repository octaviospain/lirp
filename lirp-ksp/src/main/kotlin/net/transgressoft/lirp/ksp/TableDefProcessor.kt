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
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate
import java.io.File

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
private const val KOTLIN_STRING_FQN = "kotlin.String"
private const val KOTLIN_INT_FQN = "kotlin.Int"
private const val KOTLIN_LONG_FQN = "kotlin.Long"
private const val KOTLIN_SHORT_FQN = "kotlin.Short"
private const val KOTLIN_BYTE_FQN = "kotlin.Byte"
private const val KOTLIN_BOOLEAN_FQN = "kotlin.Boolean"
private const val KOTLIN_DOUBLE_FQN = "kotlin.Double"
private const val KOTLIN_FLOAT_FQN = "kotlin.Float"
private const val KOTLIN_UUID_FQN = "kotlin.UUID"
private const val BIG_DECIMAL_FQN = "java.math.BigDecimal"
private const val COLUMN_TYPE_INT_EXPR = "ColumnType.IntType"
private const val COLUMN_TYPE_TEXT_EXPR = "ColumnType.TextType"
private const val COLUMN_TYPE_LONG_EXPR = "ColumnType.LongType"
private const val COLUMN_TYPE_BOOLEAN_EXPR = "ColumnType.BooleanType"
private const val COLUMN_TYPE_DOUBLE_EXPR = "ColumnType.DoubleType"
private const val COLUMN_TYPE_FLOAT_EXPR = "ColumnType.FloatType"
private const val COLUMN_TYPE_UUID_EXPR = "ColumnType.UuidType"
private const val COLUMN_TYPE_DATE_EXPR = "ColumnType.DateType"
private const val COLUMN_TYPE_DATETIME_EXPR = "ColumnType.DateTimeType"
private const val LIST_ITEM_SEPARATOR = ",\n        "
private const val COLUMN_CONVERTER_FQN = "net.transgressoft.lirp.persistence.ColumnConverter"

// Allow-list of supported S type FQNs for ColumnConverter<D, S>, mapped to the canonical
// ColumnType expression. Keys gate D-08 validation; values seed the codegen path that
// emits the column type from the converter's base scalar.
private val SUPPORTED_CONVERTER_S_TYPES: Map<String, String> =
    mapOf(
        KOTLIN_STRING_FQN to COLUMN_TYPE_TEXT_EXPR,
        KOTLIN_INT_FQN to COLUMN_TYPE_INT_EXPR,
        KOTLIN_LONG_FQN to COLUMN_TYPE_LONG_EXPR,
        KOTLIN_SHORT_FQN to COLUMN_TYPE_INT_EXPR,
        KOTLIN_BYTE_FQN to COLUMN_TYPE_INT_EXPR,
        KOTLIN_BOOLEAN_FQN to COLUMN_TYPE_BOOLEAN_EXPR,
        KOTLIN_DOUBLE_FQN to COLUMN_TYPE_DOUBLE_EXPR,
        KOTLIN_FLOAT_FQN to COLUMN_TYPE_FLOAT_EXPR,
        BIG_DECIMAL_FQN to "ColumnType.DecimalType",
        UUID_FQN to COLUMN_TYPE_UUID_EXPR,
        LOCAL_DATE_FQN to COLUMN_TYPE_DATE_EXPR,
        LOCAL_DATE_TIME_FQN to COLUMN_TYPE_DATETIME_EXPR
    )

internal data class ConverterInfo(
    val converterFqn: String,
    val sqlTypeFqn: String
)

/**
 * Bundle of `@PersistenceProperty` hint values consumed by the codegen path that derives
 * a column's `typeExpression`. Carrying them as a single value keeps refinement-helper
 * signatures focused — see [TableDefProcessor.refineConverterSqlType].
 */
internal data class PersistencePropertyHints(
    val length: Int,
    val precision: Int,
    val scale: Int,
    val typeHint: String
)

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

        // Collect @Aggregate properties per class, classifying each as collection (junction
        // descriptor target) or single (FK constraint target). Performed once per round so the
        // per-entity codegen below has everything it needs without re-scanning.
        val aggregatesByClass = collectAggregateProperties(resolver, classes)

        for (classDecl in classes) {
            val aggregates = aggregatesByClass[classDecl].orEmpty()
            val foreignKeys = sqlTableDefAvailable.let { collectForeignKeys(classDecl, aggregates) }
            val junctionRefs =
                if (sqlTableDefAvailable) collectJunctionRefs(classDecl, aggregates) else emptyList()
            // Backing collection fields for @Aggregate collection refs are never SQL columns —
            // they are the in-memory mirror of junction-table rows. Exclude them from column
            // collection so the column-type mapper doesn't emit "Unsupported column type
            // 'kotlin.collections.List'" errors.
            val excludedBackingFields =
                aggregates.mapNotNullTo(mutableSetOf()) { if (it.isCollection) it.backingCollectionName else null }
            // D-06 inputs: backing scalar names for single-entity aggregates (FK columns). These are
            // excluded from converter routing because the FK column type is dictated by the
            // referenced entity's primary key type, not by a domain-to-scalar converter.
            val aggregateBackingScalarNames =
                aggregates.filter { !it.isCollection }.mapNotNull { it.backingScalarName }.toSet()
            generateTableDef(
                classDecl,
                sqlTableDefAvailable,
                versionedByClass[classDecl],
                foreignKeys,
                junctionRefs,
                excludedBackingFields,
                aggregateBackingScalarNames
            )

            if (sqlTableDefAvailable) {
                for (collectionAgg in aggregates.filter { it.isCollection }) {
                    generateJunctionTableDef(classDecl, collectionAgg)
                }
            }
        }

        return unableToProcess
    }

    /**
     * Scans every entity class for `@Aggregate` properties, classifies each as collection-typed or
     * single-entity, and records the metadata needed to emit junction tables (collection refs) and
     * foreign-key constraints (single refs).
     *
     * Detection mirrors [ReactiveEntityRefProcessor]: source-text scanning of factory call names
     * (`aggregateList`, `aggregateSet` and their mutable variants) is the primary mechanism because
     * the delegate factories return stdlib `List<E>` / `Set<E>`, which do not expose the
     * `AggregateCollectionRef` supertype to KSP's type-resolution. The lambda body of single-entity
     * `aggregate { … }` is also extracted from the same source-text view and used to identify the
     * scalar property that backs the foreign key.
     */
    private fun collectAggregateProperties(
        resolver: Resolver,
        classes: Set<KSClassDeclaration>
    ): Map<KSClassDeclaration, List<AggregatePropertyMeta>> {
        val byClass = mutableMapOf<KSClassDeclaration, MutableList<AggregatePropertyMeta>>()
        for (symbol in resolver.getSymbolsWithAnnotation(AGGREGATE_ANNOTATION_FQN)) {
            if (symbol !is KSPropertyDeclaration) continue
            val parent = symbol.parentDeclaration as? KSClassDeclaration ?: continue
            if (parent !in classes) continue
            val meta = analyzeAggregateProperty(symbol) ?: continue
            byClass.getOrPut(parent) { mutableListOf() }.add(meta)
        }
        return byClass
    }

    private fun analyzeAggregateProperty(prop: KSPropertyDeclaration): AggregatePropertyMeta? {
        val annotation =
            prop.annotations.firstOrNull {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == AGGREGATE_ANNOTATION_FQN
            } ?: return null
        val onDeleteName = extractCascadeActionName(annotation.arguments.firstOrNull { it.name?.asString() == "onDelete" }?.value)

        val sourceText = readSourceLines(prop, linesAfter = 2) ?: ""
        val isCollection = containsAggregateFactoryCall(sourceText)
        val isOrdered =
            when {
                sourceText.contains("mutableAggregateList") -> true
                sourceText.contains("mutableAggregateSet") -> false
                sourceText.contains("aggregateList") -> true
                sourceText.contains("aggregateSet") -> false
                else -> false
            }

        val resolvedType = prop.type.resolve()
        val referencedClass = findReferencedClassDeclaration(resolvedType, isCollection) ?: return null

        val backingScalarName = if (!isCollection) extractAggregateLambdaIdentifier(sourceText) else null
        val backingCollectionName = if (isCollection) extractAggregateCollectionArgument(sourceText) else null

        return AggregatePropertyMeta(
            property = prop,
            propertyName = prop.simpleName.asString(),
            isCollection = isCollection,
            isOrdered = isOrdered,
            onDeleteName = onDeleteName,
            referencedClass = referencedClass,
            backingScalarName = backingScalarName,
            backingCollectionName = backingCollectionName
        )
    }

    /**
     * Extracts the backing-collection identifier passed as the first positional argument to
     * `aggregateList(initialTrackIds)` / `aggregateSet(initialTagIds)` (and their `mutable*`
     * variants). Returns `null` when the argument is not a bare identifier (e.g. `emptyList()`,
     * `setOf(1, 2)`), which signals that the entity has no writable backing field for the
     * collection — the validation in [collectJunctionRefs] surfaces a clear error in that case.
     */
    private fun extractAggregateCollectionArgument(text: String): String? {
        // Match `aggregateList<...>(IDENT)` / `aggregateSet<...>(IDENT)` and their mutable
        // variants, where IDENT is a bare identifier (no parentheses or dots). The regex tolerates
        // leading whitespace inside the parentheses but stops at the first non-identifier token.
        val regex =
            Regex(
                """\b(?:mutable)?[Aa]ggregate(?:List|Set)\b[^(]*\(\s*([A-Za-z_][A-Za-z_0-9]*)\s*\)"""
            )
        return regex.find(text)?.groupValues?.getOrNull(1)
    }

    private fun containsAggregateFactoryCall(text: String): Boolean =
        text.contains("mutableAggregateList") ||
            text.contains("mutableAggregateSet") ||
            text.contains("aggregateList") ||
            text.contains("aggregateSet")

    /**
     * Extracts the backing scalar identifier referenced by the lambda passed to a
     * single-entity aggregate factory (`aggregate { … }`, `optionalAggregate { … }`,
     * or any future `mutableAggregate { … }` variant).
     *
     * Single-entity aggregate factories take an `idProvider: () -> K` lambda whose body is
     * conventionally a property reference, optionally with non-null assertion (`!!`) or an
     * elvis fallback. KSP's resolved type model does not expose lambda bodies, so source-text
     * scanning is the only avenue. The first identifier inside the lambda braces names the
     * backing scalar.
     */
    private fun extractAggregateLambdaIdentifier(text: String): String? {
        // Capture the first identifier inside the lambda braces for the single-entity
        // factory family. The first identifier is the backing scalar in conventional usage:
        //   aggregate         { customerId }       → customerId
        //   aggregate         { customerId!! }     → customerId
        //   aggregate         { customerId ?: 0 }  → customerId
        //   optionalAggregate { parentTenantId }   → parentTenantId
        val regex = Regex("""\b(?:optional|mutable)?[Aa]ggregate\b[^{]*\{\s*([A-Za-z_][A-Za-z_0-9]*)\b""")
        return regex.find(text)?.groupValues?.getOrNull(1)
    }

    /**
     * Reads source lines around a [KSPropertyDeclaration] from its originating file. Returns
     * `null` when the source is unavailable. Mirrors the helper used by [ReactiveEntityRefProcessor].
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
     * Resolves the referenced entity class from the property's declared type.
     *
     * Single-entity refs declare `aggregate<K, E>` so the second type parameter holds the entity.
     * Collection refs declare `aggregateList<K, E>` / `aggregateSet<K, E>` whose return type is a
     * stdlib `List<E>` / `Set<E>` — in that case the single type argument is the entity.
     */
    private fun findReferencedClassDeclaration(type: KSType, isCollection: Boolean): KSClassDeclaration? {
        val args = type.arguments
        // Stdlib List<E> / Set<E> returned by aggregateList/aggregateSet
        if (isCollection && args.size == 1) {
            return args[0].type?.resolve()?.declaration as? KSClassDeclaration
        }
        // ReactiveEntityReference<K, E> / AggregateRefDelegate<K, E>
        if (args.size >= 2) {
            return args[1].type?.resolve()?.declaration as? KSClassDeclaration
        }
        return null
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
        if (typeFqn != KOTLIN_LONG_FQN || resolved.isMarkedNullable) {
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
        versionedProperty: KSPropertyDeclaration?,
        foreignKeys: List<ForeignKeyMeta> = emptyList(),
        junctionRefs: List<JunctionRefInfo> = emptyList(),
        excludedBackingFields: Set<String> = emptySet(),
        aggregateBackingScalarNames: Set<String> = emptySet()
    ) {
        val packageName = classDecl.packageName.asString()
        val className = classDecl.simpleName.asString()
        val tableDefName = "${className}_LirpTableDef"

        val tableName = resolveTableName(classDecl, className)
        val columns = collectColumns(classDecl, versionedProperty, excludedBackingFields, aggregateBackingScalarNames)
        // Ordered constructor parameter names — preserves declaration order for correct fromRow() generation.
        val constructorParamNames =
            classDecl.primaryConstructor?.parameters
                ?.mapNotNull { it.name?.asString() } ?: emptyList()
        val columnNames = columns.map { it.propertyName }.toSet()
        val unmappedCtorParams = constructorParamNames.filter { it !in columnNames }

        // Generate SqlTableDef only when (1) every non-PK column that is NOT a primary-constructor
        // parameter is mutable, and (2) every constructor parameter maps to a known column.
        // Ctor-param `val` columns are exempt from the mutability requirement: `fromRow` rebuilds
        // them through the primary-constructor invocation, and `applyRow`'s existing
        // `mutableNonPk` filter already excludes non-mutable properties — so the runtime invariant
        // "applyRow never reassigns a `val`" is preserved without the gate.
        val canGenerateSqlMapping =
            sqlTableDefAvailable &&
                columns.filter { !it.isPrimaryKey && !it.isCtorParam }.all { it.isMutable } &&
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

        val emitJunctions = canGenerateSqlMapping && junctionRefs.isNotEmpty()
        file.write(
            buildString {
                appendPackageAndImports(packageName, canGenerateSqlMapping, columns, foreignKeys.isNotEmpty(), emitJunctions)
                appendObjectBody(
                    tableDefName,
                    className,
                    ObjectBodyParams(
                        tableName = tableName,
                        canGenerateSqlMapping = canGenerateSqlMapping,
                        columns = columns,
                        constructorParamNames = constructorParamNames,
                        foreignKeys = foreignKeys,
                        junctionRefs = if (emitJunctions) junctionRefs else emptyList()
                    )
                )
            }.toByteArray()
        )
        file.close()

        logger.info("Generated $packageName.$tableDefName for $className (sqlTableDef=$canGenerateSqlMapping)")
    }

    private fun StringBuilder.appendPackageAndImports(
        packageName: String,
        canGenerateSqlMapping: Boolean,
        columns: List<ColumnMeta>,
        emitsForeignKeys: Boolean = false,
        emitsJunctions: Boolean = false
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
            if (emitsForeignKeys) {
                appendLine("import net.transgressoft.lirp.entity.CascadeAction")
                appendLine("import net.transgressoft.lirp.persistence.sql.ForeignKeyDef")
            }
            if (emitsJunctions) {
                appendLine("import net.transgressoft.lirp.persistence.sql.JunctionAccessor")
                appendLine("import net.transgressoft.lirp.persistence.sql.JunctionTableDef")
            }
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

    /** Aggregates the descriptor-shape inputs the table-def emitter consumes, keeping the
     *  emitter API below Sonar's 7-parameter ceiling without inlining the structure at every
     *  call site. */
    private data class ObjectBodyParams(
        val tableName: String,
        val canGenerateSqlMapping: Boolean,
        val columns: List<ColumnMeta>,
        val constructorParamNames: List<String> = emptyList(),
        val foreignKeys: List<ForeignKeyMeta> = emptyList(),
        val junctionRefs: List<JunctionRefInfo> = emptyList()
    )

    private fun StringBuilder.appendObjectBody(
        tableDefName: String,
        className: String,
        params: ObjectBodyParams
    ) {
        val tableName = params.tableName
        val canGenerateSqlMapping = params.canGenerateSqlMapping
        val columns = params.columns
        val constructorParamNames = params.constructorParamNames
        val foreignKeys = params.foreignKeys
        val junctionRefs = params.junctionRefs
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
                columns.joinToString(LIST_ITEM_SEPARATOR) { col ->
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
            appendLine()
            appendApplyScalarRow(className, columns)
            appendBumpVersion(className, columns)
            appendForeignKeys(foreignKeys)
            appendJunctionOverrides(className, junctionRefs)
        }
        appendLine("}")
    }

    private fun StringBuilder.appendJunctionOverrides(
        className: String,
        junctionRefs: List<JunctionRefInfo>
    ) {
        if (junctionRefs.isEmpty()) return
        appendLine()
        appendLine("    override val junctionTableDefs: List<JunctionTableDef> = listOf(")
        appendLine("        ${junctionRefs.joinToString(LIST_ITEM_SEPARATOR) { it.junctionObjectName }}")
        appendLine("    )")
        appendLine()
        appendLine("    override val junctionAccessors: List<JunctionAccessor<$className>> = listOf(")
        junctionRefs.forEachIndexed { idx, ref ->
            val trailingComma = if (idx == junctionRefs.lastIndex) "" else ","
            appendLine("        object : JunctionAccessor<$className> {")
            appendLine("            override val descriptor: JunctionTableDef = ${ref.junctionObjectName}")
            appendLine("            override fun idsOf(entity: $className): Collection<Any> = entity.${ref.backingFieldName}")
            appendLine("        }$trailingComma")
        }
        appendLine("    )")
        appendLine()
        appendLine("    override fun applyJunctionRows(")
        appendLine("        entity: $className,")
        appendLine("        descriptor: JunctionTableDef,")
        appendLine("        ids: List<Any>,")
        appendLine("    ) {")
        appendLine("        entity.withEventsDisabled {")
        appendLine("            when (descriptor) {")
        for (ref in junctionRefs) {
            val rhs =
                if (ref.isOrdered) {
                    "ids.filterIsInstance<${ref.itemKeyTypeSimpleName}>()"
                } else {
                    "ids.filterIsInstance<${ref.itemKeyTypeSimpleName}>().toSet()"
                }
            appendLine("                ${ref.junctionObjectName} ->")
            appendLine("                    entity.${ref.backingFieldName} = $rhs")
        }
        appendLine("            }")
        appendLine("        }")
        appendLine("    }")
    }

    private fun StringBuilder.appendForeignKeys(foreignKeys: List<ForeignKeyMeta>) {
        if (foreignKeys.isEmpty()) return
        appendLine()
        appendLine("    override fun foreignKeys(): List<ForeignKeyDef> = listOf(")
        val entries =
            foreignKeys.joinToString(LIST_ITEM_SEPARATOR) { fk ->
                "ForeignKeyDef(columnName = \"${fk.columnName}\", " +
                    "referencedTable = \"${fk.referencedTable}\", " +
                    "referencedColumn = \"${fk.referencedColumn}\", " +
                    "onDelete = CascadeAction.${fk.onDelete})"
            }
        appendLine("        $entries")
        appendLine("    )")
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
        return buildConverterRowAccess(col, rawAccess)
            ?: buildNarrowingIntRowAccess(col, rawAccess)
            ?: buildBuiltInRowAccess(col, rawAccess)
    }

    // Converter-routed columns short-circuit the FQN-driven cast table: read the raw scalar,
    // cast to the converter's S type, then route through the consumer's fromSql. Short/Byte
    // converters need the same INT → narrow-typed conversion that non-converter columns get
    // (the JDBC layer boxes the column value as Int regardless of declared width), or the cast
    // would throw ClassCastException at row time.
    private fun buildConverterRowAccess(col: ColumnMeta, rawAccess: String): String? {
        if (col.converterFqn == null || col.converterSqlFqn == null) return null
        val converterInput =
            when (col.converterSqlFqn) {
                KOTLIN_SHORT_FQN ->
                    if (col.nullable) "($rawAccess as? Int)?.toShort()" else "($rawAccess as Int).toShort()"
                KOTLIN_BYTE_FQN ->
                    if (col.nullable) "($rawAccess as? Int)?.toByte()" else "($rawAccess as Int).toByte()"
                else ->
                    if (col.nullable) "($rawAccess as? ${col.converterSqlFqn})" else "($rawAccess as ${col.converterSqlFqn})"
            }
        return if (col.nullable) {
            "$converterInput?.let { ${col.converterFqn}.fromSql(it) }"
        } else {
            "${col.converterFqn}.fromSql($converterInput)"
        }
    }

    // Short / Byte are stored as INT; narrow on read via Kotlin's truncating conversion.
    // A raw `as Short` would fail at runtime because the JDBC value is boxed as Int.
    private fun buildNarrowingIntRowAccess(col: ColumnMeta, rawAccess: String): String? =
        when (col.typeFqn) {
            KOTLIN_SHORT_FQN ->
                if (col.nullable) "($rawAccess as? Int)?.toShort()" else "($rawAccess as Int).toShort()"
            KOTLIN_BYTE_FQN ->
                if (col.nullable) "($rawAccess as? Int)?.toByte()" else "($rawAccess as Int).toByte()"
            else -> null
        }

    private fun buildBuiltInRowAccess(col: ColumnMeta, rawAccess: String): String =
        when {
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
        // Converter-routed columns short-circuit the FQN-driven write table: route the domain
        // value through toSql before binding. Short/Byte converters then widen to Int to match
        // the IntType column the JDBC layer expects (symmetric to buildConverterRowAccess).
        if (col.converterFqn != null) {
            val sqlValue =
                if (col.nullable) "$prop?.let { ${col.converterFqn}.toSql(it) }"
                else "${col.converterFqn}.toSql($prop)"
            return when (col.converterSqlFqn) {
                KOTLIN_SHORT_FQN, KOTLIN_BYTE_FQN ->
                    if (col.nullable) "$sqlValue?.toInt()" else "$sqlValue.toInt()"
                else -> sqlValue
            }
        }
        // Short / Byte are stored as INT — widen on write so the bound parameter matches the column.
        if (col.typeFqn == KOTLIN_SHORT_FQN) {
            return if (col.nullable) "$prop?.toInt()" else "$prop.toInt()"
        }
        if (col.typeFqn == KOTLIN_BYTE_FQN) {
            return if (col.nullable) "$prop?.toInt()" else "$prop.toInt()"
        }
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

    private fun StringBuilder.appendApplyScalarRow(className: String, columns: List<ColumnMeta>) {
        // Override the default applyScalarRow on SqlTableDef. The default body throws — the override
        // walks the supplied LirpRawInitializer entries, resolves each entry's Kotlin property name
        // to its column on the table, reads the row value with the same conversion semantics as
        // fromRow / applyRow (UUID, LocalDate, LocalDateTime, Enum), and dispatches to the entry's
        // silentSetter so reactive backing fields are written without firing events.
        appendLine("    override fun applyScalarRow(")
        appendLine("        entity: $className,")
        appendLine("        row: org.jetbrains.exposed.v1.core.ResultRow,")
        appendLine("        table: org.jetbrains.exposed.v1.core.Table,")
        appendLine("        rawInit: net.transgressoft.lirp.persistence.LirpRawInitializer<$className>")
        appendLine("    ) {")
        if (columns.isEmpty()) {
            appendLine("        // No mapped columns — applyScalarRow is a no-op.")
        } else {
            appendLine("        for (entry in rawInit.entries) {")
            appendLine("            val value: Any? = when (entry.name) {")
            for (col in columns) {
                val rowAccess = buildRowAccess(col)
                appendLine("                \"${col.propertyName}\" -> $rowAccess")
            }
            appendLine("                else -> continue")
            appendLine("            }")
            appendLine("            entry.silentSetter(entity, value)")
            appendLine("        }")
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
        versionedProperty: KSPropertyDeclaration?,
        excludedBackingFields: Set<String> = emptySet(),
        aggregateBackingScalarNames: Set<String> = emptySet()
    ): List<ColumnMeta> {
        // Detect PK: look for a concrete (non-abstract) 'id' property declared directly on the class.
        // Using getDeclaredProperties() avoids the hasBackingField pitfall on abstract interface properties
        // when the implementing class declares a concrete override.
        val hasDeclaredId = classDecl.getDeclaredProperties().any { it.simpleName.asString() == "id" && !it.isAbstract() }
        val versionedName = versionedProperty?.simpleName?.asString()
        val ctorParamNames =
            classDecl.primaryConstructor?.parameters
                ?.mapNotNull { it.name?.asString() }
                ?.toSet()
                ?: emptySet()
        return classDecl.getAllProperties()
            .filterNot { it.isExcluded() || it.simpleName.asString() in excludedBackingFields }
            .mapNotNull { buildColumnMeta(it, hasDeclaredId, versionedName, ctorParamNames, aggregateBackingScalarNames) }
            .toList()
    }

    private fun buildColumnMeta(
        prop: KSPropertyDeclaration,
        hasDeclaredId: Boolean,
        versionedName: String?,
        ctorParamNames: Set<String>,
        aggregateBackingScalarNames: Set<String> = emptySet()
    ): ColumnMeta? {
        val propName = prop.simpleName.asString()
        val persistenceAnnotation =
            prop.annotations.firstOrNull {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == PERSISTENCE_PROPERTY_FQN
            }
        val resolvedType = prop.type.resolve()
        val notNullableType = resolvedType.makeNotNullable()
        val typeFqn = notNullableType.declaration.qualifiedName?.asString() ?: "kotlin.Any"
        val isEnum = (notNullableType.declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS

        val propertyFqn = "${prop.parentDeclaration?.qualifiedName?.asString() ?: ""}.$propName".trimStart('.')

        val isPrimaryKey = propName == "id" && hasDeclaredId && !prop.isAbstract()
        val isVersion = versionedName != null && propName == versionedName
        val isAggregateBackingScalar = propName in aggregateBackingScalarNames

        // D-06: reject converter arguments on PK / @Version / @Aggregate single-ref FK columns
        // BEFORE invoking resolveConverter (which carries D-07 / D-08). This preserves the
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
                val hints =
                    PersistencePropertyHints(
                        length = persistenceAnnotation?.arguments?.firstOrNull { it.name?.asString() == "length" }?.value as? Int ?: -1,
                        precision = persistenceAnnotation?.arguments?.firstOrNull { it.name?.asString() == "precision" }?.value as? Int ?: -1,
                        scale = persistenceAnnotation?.arguments?.firstOrNull { it.name?.asString() == "scale" }?.value as? Int ?: -1,
                        typeHint = persistenceAnnotation?.arguments?.firstOrNull { it.name?.asString() == "type" }?.value as? String ?: ""
                    )
                refineConverterSqlType(
                    converterInfo = converterInfo, hints = hints, propertyFqn = propertyFqn, propName = propName
                ) ?: return null
            } else if (hasExplicitConverter) {
                // Converter declared but unresolved/invalid this round; avoid non-converter fallback.
                // Invalid converters already emitted diagnostics in resolveConverter.
                return null
            } else {
                mapToColumnTypeExpression(prop, persistenceAnnotation) ?: return null
            }

        return ColumnMeta(
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
    private fun refineConverterSqlType(
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
     * interface FQN means "no converter declared"; any other class triggers D-06 / D-07 / D-08
     * validation downstream.
     */
    private fun hasNonSentinelConverterArgument(annotation: KSAnnotation?): Boolean {
        if (annotation == null) return false
        val converterArg = annotation.arguments.firstOrNull { it.name?.asString() == "converter" }?.value as? KSType ?: return false
        val converterFqn = converterArg.declaration.qualifiedName?.asString() ?: return false
        return converterFqn != COLUMN_CONVERTER_FQN
    }

    /**
     * Reads the `converter` argument from a `@PersistenceProperty` annotation and validates
     * the referenced [ColumnConverter][net.transgressoft.lirp.persistence.ColumnConverter]
     * singleton against the structural contract (D-07 object kind, D-08 supported S type).
     *
     * Returns null when no converter is declared (sentinel), when the converter cannot yet
     * be resolved in this round (validate() guard so cross-round resolution does not produce
     * spurious diagnostics), or when validation fails after a diagnostic has been logged.
     */
    private fun resolveConverter(annotation: KSAnnotation?, propertyFqn: String): ConverterInfo? {
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

    private fun columnNameFor(persistenceAnnotation: KSAnnotation?, propName: String): String {
        if (persistenceAnnotation == null) return propName.toSnakeCase()
        val customName = persistenceAnnotation.arguments.firstOrNull { it.name?.asString() == "name" }?.value as? String
        return if (!customName.isNullOrEmpty()) customName else propName.toSnakeCase()
    }

    // Mutable for SqlTableDef fromRow purposes means: var property with a public setter.
    private fun hasPublicSetter(prop: KSPropertyDeclaration): Boolean =
        prop.setter?.modifiers?.none {
            it == Modifier.PROTECTED || it == Modifier.PRIVATE || it == Modifier.INTERNAL
        } ?: true

    private fun KSPropertyDeclaration.isExcluded(): Boolean {
        // Private backing fields are encapsulated implementation state — no public surface for
        // persistence to bind through. Mirrors the exclusion applied by `RawInitializerProcessor`
        // so the two processors agree on the persisted column set for a given entity shape.
        if (Modifier.PRIVATE in modifiers) return true
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

        // Short and Byte map to IntType (no dedicated ColumnType variant). SQLite stores all
        // integer affinities as INTEGER regardless of declared width, so introducing SMALLINT /
        // TINYINT variants would force dialect-specific SQL generation for marginal benefit.
        // The Kotlin side narrows on read (`Int.toShort()` / `Int.toByte()`) and widens on write
        // (`Short.toInt()` / `Byte.toInt()`) — see buildRowAccess and buildEntityAccess below.
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
    private fun collectForeignKeys(
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
    private fun collectJunctionRefs(
        classDecl: KSClassDeclaration,
        aggregates: List<AggregatePropertyMeta>
    ): List<JunctionRefInfo> {
        val collectionAggs = aggregates.filter { it.isCollection }
        if (collectionAggs.isEmpty()) return emptyList()

        val propertiesByName = classDecl.getAllProperties().associateBy { it.simpleName.asString() }
        val parentSimpleName = classDecl.simpleName.asString()
        return collectionAggs.mapNotNull { buildJunctionRefInfo(it, parentSimpleName, propertiesByName) }
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
        val isMutableList = typeFqn == "kotlin.collections.MutableList" || typeFqn == "kotlin.collections.MutableSet"

        return JunctionRefInfo(
            propertyName = agg.propertyName,
            backingFieldName = backingName,
            junctionObjectName = descriptorName,
            isOrdered = agg.isOrdered,
            itemKeyTypeSimpleName = itemSimpleName,
            isMutableList = isMutableList
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

    /**
     * Emits a `{Parent}_{Property}_LirpJunctionTableDef` object that implements [JunctionTableDef]
     * for one collection-typed `@Aggregate` property.
     *
     * The descriptor is the SQL-side companion of the parent's `_LirpTableDef` and lives in the
     * same package. Its column shape is fixed: `(parent_id, item_id)` always form the composite
     * primary key; `position` is appended for `aggregateList` and omitted for `aggregateSet`.
     */
    private fun generateJunctionTableDef(
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

    private fun pkColumnTypeExpression(classDecl: KSClassDeclaration): String? {
        val idProp = classDecl.getAllProperties().firstOrNull { it.simpleName.asString() == "id" } ?: return null
        return mapToColumnTypeExpression(idProp, persistenceAnnotation = null)
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
    val isCtorParam: Boolean = false,
    val isVersion: Boolean = false,
    val converterFqn: String? = null,
    val converterSqlFqn: String? = null
)

private data class AggregatePropertyMeta(
    val property: KSPropertyDeclaration,
    val propertyName: String,
    val isCollection: Boolean,
    val isOrdered: Boolean,
    val onDeleteName: String,
    val referencedClass: KSClassDeclaration,
    val backingScalarName: String?,
    val backingCollectionName: String? = null
)

private data class JunctionRefInfo(
    val propertyName: String,
    val backingFieldName: String,
    val junctionObjectName: String,
    val isOrdered: Boolean,
    val itemKeyTypeSimpleName: String,
    val isMutableList: Boolean
)

private data class ForeignKeyMeta(
    val columnName: String,
    val referencedTable: String,
    val referencedColumn: String,
    val onDelete: String
)