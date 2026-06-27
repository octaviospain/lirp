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

private const val TO_MANY_AGGREGATES_ANNOTATION_FQN = "net.transgressoft.lirp.persistence.ToManyAggregates"
private const val TO_ONE_AGGREGATE_ANNOTATION_FQN = "net.transgressoft.lirp.persistence.ToOneAggregate"

/**
 * Matches one `arm<K, E>("label") { scalar }` call, tolerating optional whitespace and an
 * optional cascade argument written either as `onDelete = CascadeAction.X` or positionally as
 * `CascadeAction.X`. Capture groups:
 *   1 = K type name (e.g. "Int", "UUID")
 *   2 = E type name (e.g. "AudioItem", "MutableAudioPlaylist")
 *   3 = label string (e.g. "item")
 *   4 = onDelete value (e.g. "DETACH", "CASCADE") — captured from either the named or positional
 *       cascade form; empty string when the argument is absent (defaults to DETACH)
 *   5 = backing scalar reference path (e.g. "audioItemId" or "this.audioItemId"); the last
 *       dotted segment is the backing scalar identifier
 */
internal val ARM_REGEX = buildArmRegex()

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

    /**
     * Accumulates deferral records `(entityFqn, propertyName, detail)` across processing rounds.
     * Entities that successfully generate in a later round are removed from this set so that only
     * permanently unresolvable types produce a terminal diagnostic.
     */
    private val deferredRecords = mutableSetOf<Triple<String, String, String>>()

    /**
     * Tracks entity FQNs that were unable to be processed because the class-level symbol failed
     * [KSAnnotated.validate] — meaning one or more of its dependencies (including property types)
     * were unresolvable. Populated in [collectPersistenceMappingClasses] and
     * [collectPersistencePropertyClasses] alongside `unableToProcess`. Pruned when the entity
     * successfully generates in a later round.
     */
    private val validationDeferredFqns = mutableSetOf<String>()

    /**
     * Resolved polymorphic arm metadata per entity class, keyed by property name. Each entry maps
     * a `polymorphicAggregate` property name to the list of resolved [ArmTextMeta] with [ArmTextMeta.entityFqn]
     * populated. Populated during [collectAggregateProperties] and consumed in [process] to drive
     * sealed-union emission via [PolymorphicRefEmitter]. Cleared per entity on each round
     * alongside [deferredRecords].
     */
    private val polymorphicArmsByClass =
        mutableMapOf<KSClassDeclaration, MutableMap<String, List<ArmTextMeta>>>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val unableToProcess = mutableListOf<KSAnnotated>()
        val classes = mutableSetOf<KSClassDeclaration>()

        collectPersistenceMappingClasses(resolver, classes, unableToProcess)
        collectPersistencePropertyClasses(resolver, classes, unableToProcess)
        val versionedByClass = collectVersionedProperties(resolver, classes)

        val sqlTableDefAvailable = detectSqlTableDefAvailability(resolver)

        // Collect aggregate-reference properties per class (single-entity and collection refs) plus
        // synthesised per-arm metadata from polymorphicAggregate() declarations. Performed once
        // per round so the per-entity codegen below has everything it needs without re-scanning.
        val aggregatesByClass = collectAggregateProperties(resolver, classes, unableToProcess)

        for (classDecl in classes) {
            val aggregates = aggregatesByClass[classDecl].orEmpty()
            val foreignKeys = sqlTableDefAvailable.let { foreignKeyAnalyzer.collectForeignKeys(classDecl, aggregates) }
            val junctionRefs =
                if (sqlTableDefAvailable) foreignKeyAnalyzer.collectJunctionRefs(classDecl, aggregates) else emptyList()
            val excludedBackingFields = buildExcludedBackingFields(classDecl, aggregates)
            // inputs: backing scalar names for single-entity aggregates (FK columns). These are
            // excluded from converter routing because the FK column type is dictated by the
            // referenced entity's primary key type, not by a domain-to-scalar converter.
            val aggregateBackingScalarNames =
                aggregates.filter { !it.isCollection }.mapNotNull { it.backingScalarName }.toSet()

            val entityFqn = classDecl.qualifiedName?.asString() ?: classDecl.simpleName.asString()
            // Clear any prior per-entity deferral state before recording this round's outcome. An
            // entity that deferred on property A in one round and then on property B in a later round
            // must not retain the stale record for A — otherwise finish()/onError() emits stale or
            // duplicate terminal diagnostics for already-resolved blockers.
            deferredRecords.removeIf { it.first == entityFqn }
            validationDeferredFqns.remove(entityFqn)
            val collected =
                embeddableAnalyzer.collectColumnsAndSlots(classDecl, versionedByClass[classDecl], excludedBackingFields, aggregateBackingScalarNames)
            if (collected.deferredSymbol != null) {
                // The entity has at least one property whose type is unresolvable in this round.
                // Re-queue the entity so KSP retries it after pending symbols are contributed.
                unableToProcess.add(collected.deferredSymbol)
                collected.deferredDetail?.let { deferredRecords.add(it) }
                continue
            }

            generateTableDef(
                classDecl,
                sqlTableDefAvailable,
                foreignKeys,
                junctionRefs,
                collected
            )

            if (sqlTableDefAvailable) {
                for (collectionAgg in aggregates.filter { it.isCollection }) {
                    foreignKeyAnalyzer.generateJunctionTableDef(classDecl, collectionAgg)
                }
            }

            emitResolvedPolymorphicSealedUnions(classDecl)
        }

        return unableToProcess
    }

    /**
     * Computes the property names that must be excluded from SQL column collection for [classDecl]:
     * - backing collection fields of `@ToManyAggregates` collection refs (in-memory mirrors of
     *   junction-table rows, never columns; emitting them would trip the column-type mapper on
     *   `kotlin.collections.List`),
     * - `polymorphicAggregate` delegate properties (the delegate type itself is not a column), and
     * - delegate-val `@ToOneAggregate` properties (`val label by aggregate<K, E> { labelId }`),
     *   detected by the backing scalar name differing from the delegate val name.
     */
    private fun buildExcludedBackingFields(
        classDecl: KSClassDeclaration,
        aggregates: List<AggregatePropertyMeta>
    ): Set<String> {
        val polymorphicPropNames =
            classDecl.getAllProperties()
                .filter { prop ->
                    val text = readSourceLines(prop, linesAfter = 1) ?: return@filter false
                    text.contains(POLYMORPHIC_AGGREGATE_CALL)
                }
                .map { it.simpleName.asString() }
                .toSet()
        val toOneDelegateValNames =
            aggregates.filter { !it.isCollection && it.backingScalarName != null && it.backingScalarName != it.propertyName }
                .map { it.propertyName }
                .toSet()
        return aggregates.mapNotNullTo(mutableSetOf()) { if (it.isCollection) it.backingCollectionName else null } +
            polymorphicPropNames +
            toOneDelegateValNames
    }

    /**
     * Emits one sealed-union file per `polymorphicAggregate` property of [classDecl] whose arms were
     * all resolved in this round. Properties with unresolved arms were deferred during
     * [collectAggregateProperties] and do not appear in [polymorphicArmsByClass]. No-op when the
     * class has no resolved arms or no containing source file.
     */
    private fun emitResolvedPolymorphicSealedUnions(classDecl: KSClassDeclaration) {
        val resolvedPolymorphicArms: Map<String, List<ArmTextMeta>>? = polymorphicArmsByClass[classDecl]
        if (resolvedPolymorphicArms.isNullOrEmpty()) return
        val packageName = classDecl.packageName.asString()
        val entitySimpleName = classDecl.simpleName.asString()
        val sourceFile = classDecl.containingFile ?: return
        for ((propName, arms) in resolvedPolymorphicArms) {
            PolymorphicRefEmitter.emitSealedUnion(
                packageName = packageName,
                entitySimpleName = entitySimpleName,
                propertyName = propName,
                arms = arms,
                codeGenerator = codeGenerator,
                sourceFile = sourceFile,
                logger = logger
            )
        }
    }

    /**
     * Emits a targeted diagnostic for every deferral record that never converged because the
     * processor is being called as part of error clean-up. Note that [onError] fires whenever
     * any KSP error is logged — permanently unresolved types may appear here alongside other
     * compilation errors.
     */
    override fun onError() {
        emitTerminalDeferralDiagnostics()
    }

    /**
     * Belt-and-suspenders terminal diagnostic path invoked at the end of all KSP rounds when
     * processing completes without a prior error. Emits one [KSPLogger.error] per deferral
     * record that never converged, naming the entity, property, and unresolved type.
     */
    override fun finish() {
        emitTerminalDeferralDiagnostics()
    }

    private fun emitTerminalDeferralDiagnostics() {
        for ((entityFqn, propName, detail) in deferredRecords) {
            logger.error(
                "Property '$entityFqn.$propName' — $detail is still unresolved after final round. " +
                    "Ensure the type is on the compilation classpath or remove the @PersistenceProperty annotation."
            )
        }
        for (entityFqn in validationDeferredFqns) {
            logger.error(
                "Entity '$entityFqn' — one or more property types are still unresolved after final round. " +
                    "Ensure all property types are on the compilation classpath."
            )
        }
    }

    /**
     * Scans every entity class for `@ToOneAggregate` / `@ToManyAggregates` properties and `polymorphicAggregate()` declarations,
     * classifies each as collection-typed or single-entity, and records the metadata needed to emit
     * junction tables (collection refs) and foreign-key constraints (single refs).
     *
     * Detection mirrors [ReactiveEntityRefProcessor]: source-text scanning of factory call names
     * (`aggregateList`, `aggregateSet` and their mutable variants) is the primary mechanism because
     * the delegate factories return stdlib `List<E>` / `Set<E>`, which do not expose the
     * `AggregateCollectionRef` supertype to KSP's type-resolution. The lambda body of single-entity
     * `aggregate { … }` is also extracted from the same source-text view and used to identify the
     * scalar property that backs the foreign key.
     *
     * For `polymorphicAggregate(arm<K,E>("label") { scalar }, …)` properties (which carry no
     * aggregate annotation), all entity properties are scanned via source text and one synthetic
     * [AggregatePropertyMeta] is added per arm. Arms with `onDelete = NONE` are still synthesised
     * here so that any FK-level validation runs; [ForeignKeyAnalyzer.collectForeignKeys] drops
     * NONE-keyed entries before emitting constraints.
     */
    private fun collectAggregateProperties(
        resolver: Resolver,
        classes: Set<KSClassDeclaration>,
        unableToProcess: MutableList<KSAnnotated>
    ): Map<KSClassDeclaration, List<AggregatePropertyMeta>> {
        val byClass = mutableMapOf<KSClassDeclaration, MutableList<AggregatePropertyMeta>>()
        for (symbol in resolver.getSymbolsWithAnnotation(TO_MANY_AGGREGATES_ANNOTATION_FQN)) {
            if (symbol !is KSPropertyDeclaration) continue
            val parent = symbol.parentDeclaration as? KSClassDeclaration ?: continue
            if (parent !in classes) continue
            val meta = analyzeAggregateProperty(symbol) ?: continue
            byClass.getOrPut(parent) { mutableListOf() }.add(meta)
        }
        // Scan @ToOneAggregate-annotated FK scalar properties.
        for (symbol in resolver.getSymbolsWithAnnotation(TO_ONE_AGGREGATE_ANNOTATION_FQN)) {
            if (symbol !is KSPropertyDeclaration) continue
            val parent = symbol.parentDeclaration as? KSClassDeclaration ?: continue
            if (parent !in classes) continue
            val meta = analyzeToOneAggregateProperty(symbol) ?: continue
            byClass.getOrPut(parent) { mutableListOf() }.add(meta)
        }

        collectPolymorphicArms(resolver, classes, unableToProcess, byClass)
        return byClass
    }

    /**
     * Scans every entity class in [classes] for `polymorphicAggregate()` declarations — which carry
     * no aggregate annotation and are therefore invisible to the annotation-based passes — and
     * records the synthesised per-arm metadata into [byClass] and [polymorphicArmsByClass]. Each
     * property is delegated to [processPolymorphicProperty].
     */
    private fun collectPolymorphicArms(
        resolver: Resolver,
        classes: Set<KSClassDeclaration>,
        unableToProcess: MutableList<KSAnnotated>,
        byClass: MutableMap<KSClassDeclaration, MutableList<AggregatePropertyMeta>>
    ) {
        for (classDecl in classes) {
            for (prop in classDecl.getAllProperties()) {
                processPolymorphicProperty(classDecl, prop, resolver, unableToProcess, byClass)
            }
        }
    }

    /**
     * Processes a single property that may declare a `polymorphicAggregate(...)`. Returns without
     * effect when the property is not polymorphic, its source is unavailable, or a diagnostic was
     * emitted (unenforceable receiver, unparseable arm, invalid labels). When all arms resolve, the
     * FQN-enriched metas are stored for sealed-union emission; when any arm target is unresolvable,
     * the property is deferred and its partial arms are removed so the next round retries cleanly.
     */
    private fun processPolymorphicProperty(
        classDecl: KSClassDeclaration,
        prop: KSPropertyDeclaration,
        resolver: Resolver,
        unableToProcess: MutableList<KSAnnotated>,
        byClass: MutableMap<KSClassDeclaration, MutableList<AggregatePropertyMeta>>
    ) {
        // Quick pre-filter: read minimal context to check for the factory keyword before
        // spending more lines on scanning.
        val quickText = readSourceLines(prop, linesAfter = 1) ?: return
        if (!quickText.contains(POLYMORPHIC_AGGREGATE_CALL)) return
        // Read from the property line to end of file so the balanced-paren walk in
        // extractPolymorphicAggregateSpan always reaches the closing ')', regardless of arm
        // count or formatting. A fixed line window truncated 6+-arm or multiline declarations.
        val fullText = readSourceFromProperty(prop) ?: return
        // Bound the scan to the contiguous polymorphicAggregate(...) span to avoid bleeding
        // into adjacent property declarations.
        val boundedText = extractPolymorphicAggregateSpan(fullText)
        val entityFqn = classDecl.qualifiedName?.asString() ?: classDecl.simpleName.asString()
        val propName = prop.simpleName.asString()
        // The exactly-one pre-persist gate lives on ReactiveEntityBase; a polymorphicAggregate
        // property on a plain IdentifiableEntity would persist with no enforcement. The Kotlin
        // compiler already rejects this (polymorphicAggregate's provideDelegate requires a
        // ReactiveEntityBase receiver), but assert it here so the unenforceable configuration
        // can never slip through codegen unflagged.
        if (!extendsReactiveEntityBase(classDecl)) {
            logger.error(
                "Property '$entityFqn.$propName' declares a polymorphicAggregate but '$entityFqn' does not extend " +
                    "ReactiveEntityBase; the exactly-one-non-null invariant cannot be enforced before persistence.",
                prop
            )
            return
        }
        // Fail loud on arm declarations the regex cannot parse rather than silently dropping
        // them from FK / sealed-union generation while the runtime delegate keeps them.
        val unparseable = unparseableArmCalls(boundedText, ARM_REGEX)
        if (unparseable.isNotEmpty()) {
            logger.error(
                "Property '$entityFqn.$propName' has polymorphic arm declaration(s) the processor cannot parse: " +
                    unparseable.joinToString("; ") + ". Use arm<K, E>(\"label\"[, CascadeAction.X]) { scalar }.",
                prop
            )
            return
        }
        val armMetas = extractArmMetas(boundedText)
        if (armMetas.isEmpty()) return
        if (!validateArmLabels(armMetas.map { it.label }, entityFqn, propName, prop)) return
        var anyDeferred = false
        val resolvedArms = mutableListOf<ArmTextMeta>()
        for (armMeta in armMetas) {
            val referencedClass = resolveArmTargetClass(armMeta.eTypeName, prop, resolver)
            if (referencedClass == null) {
                // Unresolved target — defer via the existing multi-round mechanism.
                unableToProcess.add(prop)
                deferredRecords.add(Triple(entityFqn, propName, "arm target type '${armMeta.eTypeName}'"))
                anyDeferred = true
                break
            }
            val resolvedFqn = referencedClass.qualifiedName?.asString() ?: armMeta.eTypeName
            resolvedArms.add(armMeta.copy(entityFqn = resolvedFqn))
            byClass.getOrPut(classDecl) { mutableListOf() }.add(
                AggregatePropertyMeta(
                    property = prop,
                    propertyName = propName,
                    isCollection = false,
                    isOrdered = false,
                    onDeleteName = armMeta.onDeleteName,
                    referencedClass = referencedClass,
                    backingScalarName = armMeta.scalarName,
                    backingCollectionName = null
                )
            )
        }
        // If any arm deferred, remove any partial arms added for this property so the entity
        // is re-processed cleanly in the next round without duplicate entries.
        if (anyDeferred) {
            byClass[classDecl]?.removeIf { it.property == prop }
            polymorphicArmsByClass[classDecl]?.remove(propName)
        } else if (resolvedArms.isNotEmpty()) {
            // All arms resolved — store the FQN-enriched metas for sealed-union emission.
            polymorphicArmsByClass.getOrPut(classDecl) { mutableMapOf() }[propName] = resolvedArms
        }
    }

    /**
     * Extracts the source span of a `polymorphicAggregate(…)` call from the scanned text by
     * performing a balanced-parenthesis walk starting at the first `(` after `polymorphicAggregate`.
     * This bounds the arm regex to the call itself and prevents false matches from adjacent
     * property declarations that may also contain `arm(` or `{`.
     */
    private fun extractPolymorphicAggregateSpan(text: String): String =
        extractBalancedCallSpan(text, POLYMORPHIC_AGGREGATE_CALL)

    /**
     * Parses source text for `arm<K, E>("label") { scalar }` calls and returns a list of
     * [ArmTextMeta] with the raw textual type names, label, scalar identifier, and cascade action.
     * The `onDelete` defaults to `"DETACH"` when the argument is absent.
     */
    internal fun extractArmMetas(sourceText: String): List<ArmTextMeta> =
        ARM_REGEX.findAll(sourceText).map { m ->
            ArmTextMeta(
                kTypeName = m.groupValues[1].trim(),
                eTypeName = m.groupValues[2].trim(),
                label = m.groupValues[3],
                scalarName = armScalarFromPath(m.groupValues[5]),
                onDeleteName = m.groupValues[4].takeIf { it.isNotEmpty() } ?: "DETACH"
            )
        }.toList()

    /**
     * Resolves the target entity class declaration for a polymorphic arm given its source-level
     * simple type name. Resolution order:
     * 1. Parse the import list from the property's containing source file — find an import whose
     *    last segment matches the simple name.
     * 2. Try the entity's own package as a fallback.
     *
     * Returns `null` when the class cannot be resolved in this round (triggers deferral).
     */
    private fun resolveArmTargetClass(
        eTypeName: String,
        prop: KSPropertyDeclaration,
        resolver: Resolver
    ): KSClassDeclaration? {
        val location = prop.location as? FileLocation ?: return null
        val sourceFile = File(location.filePath)
        if (sourceFile.exists()) {
            val packageName = prop.packageName.asString()
            val importFqn = resolveImportedFqn(sourceFile.readLines(), eTypeName, packageName)
            if (importFqn != null) {
                val decl = resolver.getClassDeclarationByName(resolver.getKSNameFromString(importFqn))
                if (decl != null) return decl
            }
        }
        // Fallback: try the entity's own package.
        val packageName = prop.packageName.asString()
        if (packageName.isNotEmpty()) {
            val fqn = "$packageName.$eTypeName"
            val decl = resolver.getClassDeclarationByName(resolver.getKSNameFromString(fqn))
            if (decl != null) return decl
        }
        return null
    }

    private fun analyzeAggregateProperty(prop: KSPropertyDeclaration): AggregatePropertyMeta? {
        val annotation =
            prop.annotations.firstOrNull {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == TO_MANY_AGGREGATES_ANNOTATION_FQN
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
        val referencedClass =
            findReferencedClassDeclaration(resolvedType, isCollection) ?: run {
                logger.warn(
                    "Could not resolve the referenced entity class for aggregate-reference property " +
                        "'${prop.simpleName.asString()}' — its declared type exposes no usable entity type " +
                        "argument; skipping FK/junction generation for it.",
                    prop
                )
                return null
            }

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
     * Builds [AggregatePropertyMeta] for a property annotated with
     * [@ToOneAggregate][net.transgressoft.lirp.persistence.ToOneAggregate].
     *
     * Two forms are supported:
     * - **Scalar FK form** (`@ToOneAggregate var xId: K`): the annotated property itself is the FK
     *   column, so [AggregatePropertyMeta.backingScalarName] is the property's own name.
     * - **Delegate-val form** (`@ToOneAggregate val x by aggregate<K, E> { xId }`): the FK column
     *   is the scalar referenced inside the lambda body (`xId`), extracted via
     *   [extractAggregateLambdaIdentifier]. When the lambda body is a computed expression rather than
     *   a simple identifier, no FK metadata is emitted — the reference is valid in memory but has no
     *   corresponding SQL column.
     */
    private fun analyzeToOneAggregateProperty(prop: KSPropertyDeclaration): AggregatePropertyMeta? {
        val annotation =
            prop.annotations.firstOrNull {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == TO_ONE_AGGREGATE_ANNOTATION_FQN
            } ?: return null

        val onDeleteName =
            foreignKeyAnalyzer.extractCascadeActionName(
                annotation.arguments.firstOrNull { it.name?.asString() == "onDelete" }?.value
            )

        // Extract target: KClass<*> → KSType → KSClassDeclaration (confirmed pattern from ColumnMetaBuilder.kt:684).
        val targetArg =
            annotation.arguments.firstOrNull { it.name?.asString() == "target" }?.value as? KSType
                ?: return null
        val referencedClass = targetArg.declaration as? KSClassDeclaration ?: return null

        val sourceText = readSourceLines(prop, linesBefore = 0, linesAfter = 2) ?: ""
        val isDelegateVal =
            sourceText.contains("aggregate {") ||
                sourceText.contains("aggregate<") ||
                sourceText.contains("optionalAggregate") ||
                sourceText.contains("mutableAggregate{") ||
                sourceText.contains("mutableAggregate<")

        val backingScalarName =
            if (isDelegateVal) {
                // For the delegate-val form the FK column is the lambda's referenced scalar,
                // not the delegate property name. When the lambda body is a computed expression
                // (not a bare identifier), there is no SQL FK column — return null to skip FK
                // emission rather than emitting broken metadata.
                extractAggregateLambdaIdentifier(sourceText) ?: return null
            } else {
                prop.simpleName.asString()
            }

        return AggregatePropertyMeta(
            property = prop,
            propertyName = prop.simpleName.asString(),
            isCollection = false,
            isOrdered = false,
            onDeleteName = onDeleteName,
            referencedClass = referencedClass,
            backingScalarName = backingScalarName,
            backingCollectionName = null
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
        // Capture the reference path inside the lambda braces for the single-entity factory family,
        // then reduce it to the backing scalar identifier. The path may be `this.`-qualified or
        // dotted; the last segment is the backing scalar in conventional usage:
        //   aggregate         { customerId }            → customerId
        //   aggregate         { customerId!! }          → customerId
        //   aggregate         { customerId ?: 0 }       → customerId
        //   aggregate         { this.customerId }       → customerId
        //   optionalAggregate { parentTenantId }        → parentTenantId
        val regex =
            Regex(
                """\b(?:optional|mutable)?[Aa]ggregate\b[^{]*\{\s*((?:this\.)?[A-Za-z_][A-Za-z_0-9]*(?:\.[A-Za-z_][A-Za-z_0-9]*)*)"""
            )
        return regex.find(text)?.groupValues?.getOrNull(1)?.let { armScalarFromPath(it) }
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
     * Reads source text from a property's declaration line to end of file. Used for
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
     * Validates that arm labels within a single polymorphic property are distinct and are legal
     * Kotlin identifiers — the sealed-union emitter turns each label into a `data class` name and a
     * `when` branch, so a duplicate or non-identifier label produces an uncompilable generated file.
     * Emits a [KSPLogger.error] and returns `false` on violation.
     */
    private fun validateArmLabels(
        labels: List<String>,
        entityFqn: String,
        propName: String,
        prop: KSPropertyDeclaration
    ): Boolean {
        val duplicates = labels.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            logger.error("Property '$entityFqn.$propName' has duplicate polymorphic arm label(s): $duplicates.", prop)
            return false
        }
        val invalid = labels.filterNot { isValidArmLabel(it) }
        if (invalid.isNotEmpty()) {
            logger.error(
                "Property '$entityFqn.$propName' has polymorphic arm label(s) that are not valid Kotlin identifiers: " +
                    "$invalid. Labels become generated data class names and must match [A-Za-z_][A-Za-z_0-9]*.",
                prop
            )
            return false
        }
        return true
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
                validationDeferredFqns.add(symbol.qualifiedName?.asString() ?: symbol.simpleName.asString())
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
                validationDeferredFqns.add(parent.qualifiedName?.asString() ?: parent.simpleName.asString())
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
     * Returns `true` when [classDecl]'s supertype hierarchy includes [SoftDeletable][SOFT_DELETABLE_FQN].
     * Walks the full transitive supertype graph (class + interfaces) guarded by a visited set to
     * handle diamond inheritance without infinite recursion. Used to decide whether a synthesized
     * `deleted_at` column should be injected into the generated table descriptor.
     */
    private fun implementsSoftDeletable(classDecl: KSClassDeclaration): Boolean =
        implementsSoftDeletableRecursive(classDecl, mutableSetOf())

    private fun implementsSoftDeletableRecursive(
        classDecl: KSClassDeclaration,
        visited: MutableSet<String>
    ): Boolean {
        val fqn = classDecl.qualifiedName?.asString() ?: return false
        if (!visited.add(fqn)) return false
        for (superTypeRef in classDecl.superTypes) {
            val superDecl = superTypeRef.resolve().declaration
            val superFqn = superDecl.qualifiedName?.asString() ?: continue
            if (superFqn == SOFT_DELETABLE_FQN) return true
            if (superDecl is KSClassDeclaration && implementsSoftDeletableRecursive(superDecl, visited)) return true
        }
        return false
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

    /**
     * Resolves the reactive self-type `R` the generated descriptor is typed on. Returns the
     * concrete class name when `R` equals the class (self-referential) or cannot be resolved (with
     * a warning that the descriptor may not be assignable to a `Repository` bound on `R`), the
     * resolved interface name otherwise, or `null` to signal that generation must be skipped — the
     * one unrenderable case being a generic entity whose `R` is an unsubstituted type parameter.
     */
    private fun resolveDescriptorSelfType(classDecl: KSClassDeclaration, className: String): String? {
        val resolvedSelfType = resolveReactiveSelfType(classDecl)
        if (resolvedSelfType == null && classDecl.typeParameters.isNotEmpty()) {
            logger.warn(
                "Skipping _LirpTableDef generation for ${classDecl.qualifiedName?.asString()}: its reactive " +
                    "self-type R is an unsubstituted type parameter, so no valid SqlTableDef<R> can be generated"
            )
            return null
        }
        return when {
            resolvedSelfType == null -> {
                logger.warn(
                    "Could not resolve reactive self-type R for ${classDecl.qualifiedName?.asString()}; " +
                        "generated TableDef is typed on the concrete class and may not be assignable " +
                        "to a Repository bound on R : ReactiveEntity"
                )
                className
            }
            resolvedSelfType == classDecl.qualifiedName?.asString() -> className
            else -> resolvedSelfType
        }
    }

    /** Entity-level `@PersistenceCreator` resolution outcome consumed by `fromRow` emission. */
    private data class EntityCreator(val callExpression: String?, val paramNames: List<String>?)

    /**
     * Resolves the entity-level `@PersistenceCreator` reconstruction target. Returns `null` to abort
     * generation when the configuration is invalid (more than one creator, or a creator parameter
     * with no mapped column source) — both surfaced as compilation errors. A present creator always
     * takes precedence over the primary constructor; its parameters are matched by name against the
     * entity's constructor params, omitting any defaulted parameter that has no column source. When
     * no creator exists, falls back to the primary constructor and warns if that constructor is not
     * public. A non-public resolved creator on an internal entity warns rather than fails, since the
     * descriptor still compiles inside the declaring module.
     */
    private fun resolveEntityCreator(
        classDecl: KSClassDeclaration,
        className: String,
        availableParamNames: Set<String>
    ): EntityCreator? {
        return when (val resolution = resolveCreator(classDecl)) {
            is CreatorResolution.Ambiguous -> {
                logger.error(
                    "Multiple @PersistenceCreator targets on $className: " +
                        "${resolution.conflicting.formatCreatorOffenders()}; exactly one is required.",
                    classDecl
                )
                null
            }
            is CreatorResolution.Found -> {
                val resolvedParamNames = mutableListOf<String>()
                for (param in resolution.params) {
                    val paramName = param.name?.asString() ?: continue
                    when {
                        // Validate against the emitted slot names, not raw ctor param names: an
                        // excluded ctor param (e.g. @PersistenceIgnore) produces no slot/column, so
                        // fromRow would have no source to bind the creator parameter to.
                        paramName in availableParamNames -> resolvedParamNames += paramName
                        param.hasDefault -> { /* omit so the default value applies at instantiation */ }
                        else -> {
                            logger.error(
                                "@PersistenceCreator param '$paramName' on $className has no mapped column source.",
                                classDecl
                            )
                            return null
                        }
                    }
                }
                if (classDecl.hasInternalNonPublicCreator()) {
                    logger.warn(
                        "$className is internal and its @PersistenceCreator '${resolution.callExpression}' is not " +
                            "public; the generated descriptor may not compile outside its own module. Add a public " +
                            "@PersistenceCreator to make it cross-module usable."
                    )
                }
                EntityCreator(resolution.callExpression, resolvedParamNames)
            }
            CreatorResolution.None -> {
                if (classDecl.hasNonPublicPrimaryConstructor()) {
                    logger.warn(
                        "$className has a non-public primary constructor and no @PersistenceCreator; " +
                            "the generated descriptor may not compile outside its own module."
                    )
                }
                EntityCreator(null, null)
            }
        }
    }

    private fun generateTableDef(
        classDecl: KSClassDeclaration,
        sqlTableDefAvailable: Boolean,
        foreignKeys: List<ForeignKeyMeta>,
        junctionRefs: List<JunctionRefInfo>,
        collected: EmbeddableAnalyzer.CollectedShape
    ) {
        val visibility = effectiveVisibilityModifier(classDecl)
        if (visibility == null) {
            val fqn = classDecl.qualifiedName?.asString() ?: classDecl.simpleName.asString()
            logger.error(
                "Entity '$fqn' must be public or internal to generate a persistence companion (_LirpTableDef). " +
                    "Private and protected entities cannot have accessible generated code.",
                classDecl
            )
            return
        }
        val packageName = classDecl.packageName.asString()
        val className = classDecl.simpleName.asString()

        val selfType = resolveDescriptorSelfType(classDecl, className) ?: return
        val tableDefName = "$className${LirpGenNames.TABLE_DEF_SUFFIX}"

        // Validate creator params against the names that actually produce a slot/column source,
        // so a creator referencing an excluded (e.g. @PersistenceIgnore'd) ctor param is rejected.
        val mappableParamNames = collected.ctorSlots.map { it.ctorParamName }.toSet()
        val creator = resolveEntityCreator(classDecl, className, mappableParamNames) ?: return

        val tableName = resolveTableName(classDecl, className)
        val resolvedShape = collected
        val columns = resolvedShape.columns.toMutableList()
        val ctorSlots = resolvedShape.ctorSlots
        val setterSlots = resolvedShape.setterSlots

        // Synthesize a deleted_at column for entities whose supertype hierarchy includes SoftDeletable.
        // The column is appended after the full property scan following the same ordering rule as the
        // @Version column, so fromRow/toParams index alignment is preserved. Synthesis is skipped when
        // the entity already maps the `deletedAt` PROPERTY (via an explicit @PersistenceMapping entry)
        // so that a custom column name for deletedAt is respected. An unrelated property that happens
        // to use the column name "deleted_at" is not treated as an explicit mapping and falls through
        // to the normal column-name collision check below.
        val hasDeletedAtMapping = columns.any { it.propertyName == "deletedAt" }
        if (implementsSoftDeletable(classDecl) && !hasDeletedAtMapping) {
            val instantConverter = DEFAULT_CONVERTERS.getValue(INSTANT_FQN)
            columns.add(
                ColumnMeta(
                    name = "deleted_at",
                    propertyName = "deletedAt",
                    typeExpression = COLUMN_TYPE_TEXT_EXPR,
                    typeFqn = INSTANT_FQN,
                    nullable = true,
                    isPrimaryKey = false,
                    isEnum = false,
                    isMutable = true,
                    isCtorParam = false,
                    isVersion = false,
                    converterFqn = instantConverter.converterFqn,
                    converterSqlFqn = instantConverter.sqlTypeFqn
                )
            )
        }

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
            (listOfNotNull(classDecl.containingFile) + resolvedShape.embeddableFiles)
                .distinct()
                .toTypedArray()
        val file =
            codeGenerator.createNewFile(
                dependencies = Dependencies(false, *involvedFiles),
                packageName = packageName,
                fileName = tableDefName
            )

        val emitJunctions = canGenerateSqlMapping && junctionRefs.isNotEmpty()
        val emitVersioned = canGenerateSqlMapping && columns.any { it.isVersion }
        file.write(
            buildString {
                appendPackageAndImports(packageName, canGenerateSqlMapping, columns, foreignKeys.isNotEmpty(), emitJunctions, emitVersioned)
                appendObjectBody(
                    tableDefName,
                    className,
                    ObjectBodyParams(
                        tableName = tableName,
                        selfType = selfType,
                        canGenerateSqlMapping = canGenerateSqlMapping,
                        columns = columns,
                        constructorParamNames = constructorParamNames,
                        ctorSlots = ctorSlots,
                        setterSlots = setterSlots,
                        foreignKeys = foreignKeys,
                        junctionRefs = if (emitJunctions) junctionRefs else emptyList(),
                        isReactiveEntity = extendsReactiveEntityBase(classDecl),
                        creatorCallExpression = creator.callExpression,
                        creatorParamNames = creator.paramNames
                    ),
                    visibility = visibility
                )
            }.toByteArray()
        )
        file.close()

        logger.info("Generated $packageName.$tableDefName for $className (sqlTableDef=$canGenerateSqlMapping)")
    }
}