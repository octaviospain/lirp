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

import net.transgressoft.lirp.ksp.TableDefSourceEmitter.appendObjectBody
import net.transgressoft.lirp.ksp.TableDefSourceEmitter.appendPackageAndImports
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.FileLocation
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.validate
import java.io.File

private const val AGGREGATE_ANNOTATION_FQN = "net.transgressoft.lirp.persistence.Aggregate"

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
 *
 * Actual code generation is delegated to [TableDefSourceEmitter] (string building), [EmbeddableAnalyzer]
 * (`@Embedded` / `@Embeddable` recursive descent), [ForeignKeyAnalyzer] (FK constraints and junction
 * tables), and [ColumnMetaBuilder] (per-property column resolution).
 */
class TableDefProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    private val columnMetaBuilder = ColumnMetaBuilder(logger)
    private val embeddableAnalyzer = EmbeddableAnalyzer(logger, columnMetaBuilder)
    private val foreignKeyAnalyzer = ForeignKeyAnalyzer(logger, codeGenerator, columnMetaBuilder)

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
            val foreignKeys = sqlTableDefAvailable.let { foreignKeyAnalyzer.collectForeignKeys(classDecl, aggregates) }
            val junctionRefs =
                if (sqlTableDefAvailable) foreignKeyAnalyzer.collectJunctionRefs(classDecl, aggregates) else emptyList()
            // Backing collection fields for @Aggregate collection refs are never SQL columns —
            // they are the in-memory mirror of junction-table rows. Exclude them from column
            // collection so the column-type mapper doesn't emit "Unsupported column type
            // 'kotlin.collections.List'" errors.
            val excludedBackingFields =
                aggregates.mapNotNullTo(mutableSetOf()) { if (it.isCollection) it.backingCollectionName else null }
            // inputs: backing scalar names for single-entity aggregates (FK columns). These are
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
                    foreignKeyAnalyzer.generateJunctionTableDef(classDecl, collectionAgg)
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
        val onDeleteName = foreignKeyAnalyzer.extractCascadeActionName(annotation.arguments.firstOrNull { it.name?.asString() == "onDelete" }?.value)

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
     * collection — the validation in [ForeignKeyAnalyzer.collectJunctionRefs] surfaces a clear
     * error in that case.
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

    // Scan for @Version-annotated properties and validate them. Invalid @Version
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
     * Validates a @Version property — type must be non-nullable `kotlin.Long`, must be
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

        // at most one @Version per class.
        if (parent in alreadyFound) {
            logger.error(
                "Class '$className' has multiple @Version properties " +
                    "('${alreadyFound.getValue(parent).simpleName.asString()}' and '$propName'); only one is allowed.",
                prop
            )
            return false
        }

        // type must be exactly kotlin.Long (non-nullable).
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

        // must be var.
        if (!prop.isMutable) {
            logger.error(
                "@Version property '$className.$propName' must be declared with 'var' (not 'val').",
                prop
            )
            return false
        }

        // must use the reactiveProperty delegate (enforcement via isDelegated as a
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
        val collected =
            embeddableAnalyzer.collectColumnsAndSlots(classDecl, versionedProperty, excludedBackingFields, aggregateBackingScalarNames)
        val columns = collected.columns
        val ctorSlots = collected.ctorSlots
        val setterSlots = collected.setterSlots

        // column-name collision detection runs ONCE at the entity level on the fully
        // flattened column list, after all recursive @Embedded descents. Detection at this level
        // (rather than per-@Embeddable) catches grandchild collisions that an intermediate prefix
        // might otherwise mask. Codegen is suppressed for the entity when collisions are reported.
        if (embeddableAnalyzer.detectColumnCollisions(classDecl, columns)) {
            logger.info("Skipping _LirpTableDef generation for $className due to column-name collisions")
            return
        }
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

        // Include all files that shape this descriptor in Dependencies so KSP re-generates it
        // whenever any involved source changes: the entity itself and any nested @Embeddable types
        // visited during recursive descent.
        val involvedFiles =
            (listOfNotNull(classDecl.containingFile) + collected.embeddableFiles)
                .distinct()
                .toTypedArray()
        val file =
            codeGenerator.createNewFile(
                dependencies = Dependencies(false, *involvedFiles),
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
                        ctorSlots = ctorSlots,
                        setterSlots = setterSlots,
                        foreignKeys = foreignKeys,
                        junctionRefs = if (emitJunctions) junctionRefs else emptyList()
                    )
                )
            }.toByteArray()
        )
        file.close()

        logger.info("Generated $packageName.$tableDefName for $className (sqlTableDef=$canGenerateSqlMapping)")
    }
}