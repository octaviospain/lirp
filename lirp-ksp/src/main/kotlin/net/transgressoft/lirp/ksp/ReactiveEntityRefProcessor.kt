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
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.validate
import java.io.File

private const val REACTIVE_ENTITY_REF_ANNOTATION_FQN = "net.transgressoft.lirp.persistence.Aggregate"
private const val REACTIVE_ENTITY_REFERENCE_FQN = "net.transgressoft.lirp.persistence.ReactiveEntityReference"
private const val REACTIVE_ENTITY_COLLECTION_REFERENCE_FQN = "net.transgressoft.lirp.persistence.ReactiveEntityCollectionReference"
private const val AGGREGATE_LIST_REF_DELEGATE_FQN = "net.transgressoft.lirp.persistence.AggregateListRefDelegate"
private const val AGGREGATE_SET_REF_DELEGATE_FQN = "net.transgressoft.lirp.persistence.AggregateSetRefDelegate"

/**
 * KSP processor that generates [LirpRefAccessor][net.transgressoft.lirp.persistence.LirpRefAccessor]
 * implementations for entity classes containing
 * [@Aggregate][net.transgressoft.lirp.persistence.Aggregate] properties.
 *
 * For each entity class, a `{ClassName}_LirpRefAccessor` is generated in the same package, providing:
 * - Direct ID getter lambdas (`idGetter`) via the delegate's `referenceId` property, for single-entity references
 * - Direct IDs getter lambdas (`idsGetter`) via the delegate's `referenceIds` property, for collection references
 * - Direct delegate getter lambdas (`delegateGetter`) for both single and collection references
 * - A `cancelAllBubbleUp` override that cancels all bubble-up subscriptions without any reflection
 * - `collectionEntries` populated with [CollectionRefEntry][net.transgressoft.lirp.persistence.CollectionRefEntry]
 *   instances for all collection-typed `@Aggregate` properties
 *
 * The generated code uses named constructor arguments for [RefEntry][net.transgressoft.lirp.persistence.RefEntry]
 * and [CollectionRefEntry][net.transgressoft.lirp.persistence.CollectionRefEntry]
 * so that future field additions remain backward-compatible.
 *
 * Type resolution handles type aliases and intermediate interfaces: if a property's declared type
 * is a type alias or an intermediate interface (not `ReactiveEntityReference` directly), the processor
 * recursively walks the supertype chain to find the `ReactiveEntityReference<K, E>` supertype and
 * extracts `E` from its first type argument.
 *
 * Collection reference detection inspects the resolved property type against
 * `ReactiveEntityCollectionReference` in its supertype chain. Whether the reference is ordered
 * (List) or unordered (Set) is determined by scanning the property's source declaration text for
 * the `aggregateList` vs `aggregateSet` factory call, since KSP resolves delegated property types
 * to the interface rather than the concrete delegate class.
 *
 * For collection-typed properties, `bubbleUp = true` is rejected with a compile error since
 * collection references do not support event propagation.
 */
class ReactiveEntityRefProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(REACTIVE_ENTITY_REF_ANNOTATION_FQN)
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

        for ((classDecl, properties) in classToProperties) {
            generateAccessor(classDecl, properties)
        }

        return unableToProcess
    }

    /**
     * Classifies each `@Aggregate`-annotated property as either a single-entity reference or a
     * collection reference, extracting the metadata required for code generation.
     */
    private fun classifyProperties(
        properties: List<KSPropertyDeclaration>,
        className: String,
        singleEntries: MutableList<RefPropertyMeta>,
        collectionMetas: MutableList<CollectionRefPropertyMeta>
    ) {
        for (prop in properties) {
            val annotation =
                prop.annotations.firstOrNull {
                    it.annotationType.resolve().declaration.qualifiedName?.asString() == REACTIVE_ENTITY_REF_ANNOTATION_FQN
                } ?: continue

            val bubbleUp = annotation.arguments.firstOrNull { it.name?.asString() == "bubbleUp" }?.value as? Boolean ?: false
            val onDeleteArg = annotation.arguments.firstOrNull { it.name?.asString() == "onDelete" }
            val onDeleteExplicitInSource = isOnDeleteExplicitInSource(prop)
            val explicitOnDeleteName = if (onDeleteArg != null) extractCascadeActionName(onDeleteArg.value) else null

            val resolvedType = prop.type.resolve()

            if (isCollectionReference(resolvedType)) {
                classifyCollectionProperty(prop, className, bubbleUp, onDeleteExplicitInSource, explicitOnDeleteName, resolvedType, collectionMetas)
            } else {
                classifySingleProperty(prop, className, bubbleUp, explicitOnDeleteName, resolvedType, singleEntries)
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
                "bubbleUp = true is not supported on collection-typed @Aggregate properties. " +
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

    private fun classifySingleProperty(
        prop: KSPropertyDeclaration,
        className: String,
        bubbleUp: Boolean,
        explicitOnDeleteName: String?,
        resolvedType: KSType,
        singleEntries: MutableList<RefPropertyMeta>
    ) {
        val cascadeActionName = explicitOnDeleteName ?: "DETACH"

        val referencedClassFqn =
            findReferencedClassFqnFromType(resolvedType)
                ?: run {
                    logger.warn("Cannot determine referenced class for property '${prop.simpleName.asString()}' in $className — skipping")
                    return
                }

        singleEntries.add(
            RefPropertyMeta(
                refName = prop.simpleName.asString(),
                propertyName = prop.simpleName.asString(),
                referencedClassFqn = referencedClassFqn,
                bubbleUp = bubbleUp,
                cascadeAction = cascadeActionName
            )
        )
    }

    private fun generateAccessor(classDecl: KSClassDeclaration, properties: List<KSPropertyDeclaration>) {
        val packageName = classDecl.packageName.asString()
        val className = classDecl.simpleName.asString()
        val accessorName = "${className}_LirpRefAccessor"

        val singleEntries = mutableListOf<RefPropertyMeta>()
        val collectionMetas = mutableListOf<CollectionRefPropertyMeta>()
        classifyProperties(properties, className, singleEntries, collectionMetas)

        val file =
            codeGenerator.createNewFile(
                dependencies = Dependencies(false, classDecl.containingFile!!),
                packageName = packageName,
                fileName = accessorName
            )

        // Collect import statements for all referenced entity classes (single + collection)
        val allReferencedFqns =
            (singleEntries.map { it.referencedClassFqn } + collectionMetas.map { it.referencedClassFqn })
                .distinct()
                .filter { it.contains('.') }
                .sorted()

        // Build RefEntry named constructor calls with delegateGetter and typed idGetter.
        // Since AggregateRefDelegate.getValue() returns `this`, accessing `it.propName` at runtime
        // returns the delegate itself typed as ReactiveEntityReference<K, E>. Casting to
        // AggregateRefDelegate<*, *> is safe — the only aggregate<K,E> implementation is
        // AggregateRefDelegate. The UNCHECKED_CAST suppression is placed on each RefEntry call.
        val entriesCode =
            singleEntries.joinToString(",\n        ") { meta ->
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

        val collectionEntriesCode =
            collectionMetas.joinToString(",\n        ") { meta ->
                val referencedSimpleName = meta.referencedClassFqn.substringAfterLast('.')
                """
                @Suppress("UNCHECKED_CAST")
                CollectionRefEntry(
                    refName = "${meta.refName}",
                    idsGetter = { it.${meta.propertyName}.referenceIds },
                    delegateGetter = { it.${meta.propertyName} as ReactiveEntityCollectionReference<*, *> },
                    referencedClass = $referencedSimpleName::class.java,
                    cascadeAction = CascadeAction.${meta.cascadeAction},
                    isOrdered = ${meta.isOrdered}
                )
                """.trimIndent()
            }

        val cancelAllBubbleUpCode =
            """
            override fun cancelAllBubbleUp(entity: $className) {
                entries.forEach { entry -> entry.delegateGetter(entity).cancelBubbleUp() }
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
                appendLine("import net.transgressoft.lirp.persistence.ReactiveEntityCollectionReference")
                appendLine("import net.transgressoft.lirp.persistence.RefEntry")
                for (importFqn in allReferencedFqns) {
                    appendLine("import $importFqn")
                }
                appendLine()
                appendLine("/**")
                appendLine(" * KSP-generated aggregate reference accessor for [$className].")
                appendLine(" * Provides direct ID getter and delegate getter lambdas — no runtime reflection.")
                appendLine(" */")
                appendLine("public class $accessorName : LirpRefAccessor<$className> {")
                if (singleEntries.isEmpty()) {
                    appendLine("    override val entries: List<RefEntry<*, $className>> = emptyList()")
                } else {
                    appendLine("    override val entries: List<RefEntry<*, $className>> = listOf(")
                    appendLine("        $entriesCode")
                    appendLine("    )")
                }
                appendLine()
                if (collectionMetas.isEmpty()) {
                    appendLine("    override val collectionEntries: List<CollectionRefEntry<*, $className>> = emptyList()")
                } else {
                    appendLine("    override val collectionEntries: List<CollectionRefEntry<*, $className>> = listOf(")
                    appendLine("        $collectionEntriesCode")
                    appendLine("    )")
                }
                appendLine()
                appendLine(cancelAllBubbleUpCode)
                appendLine("}")
            }.toByteArray()
        )
        file.close()

        logger.info("Generated $packageName.$accessorName for $className")
    }

    /**
     * Determines whether a resolved property type represents a collection reference by walking
     * the supertype chain to find [REACTIVE_ENTITY_COLLECTION_REFERENCE_FQN].
     */
    private fun isCollectionReference(type: KSType): Boolean {
        val declaration = type.declaration
        if (declaration is KSTypeAlias) {
            return isCollectionReference(declaration.type.resolve())
        }
        if (isCollectionReferenceFqn(declaration.qualifiedName?.asString())) return true

        if (declaration is KSClassDeclaration) {
            for (superType in declaration.superTypes) {
                if (isCollectionReference(superType.resolve())) return true
            }
        }
        return false
    }

    private fun isCollectionReferenceFqn(fqn: String?): Boolean =
        fqn == REACTIVE_ENTITY_COLLECTION_REFERENCE_FQN ||
            fqn == AGGREGATE_LIST_REF_DELEGATE_FQN ||
            fqn == AGGREGATE_SET_REF_DELEGATE_FQN

    /**
     * Determines whether the collection reference is ordered (List semantics) by inspecting the
     * property's source declaration text for the `aggregateList` vs `aggregateSet` factory call.
     *
     * Because KSP resolves delegated property types to the interface (`ReactiveEntityCollectionReference`)
     * rather than the concrete delegate class, the type system alone cannot distinguish list from set.
     * Reading the source file around the property declaration is the most reliable way to detect
     * which factory function is used.
     *
     * Falls back to `false` (Set semantics) if the source is unavailable or the line contains neither.
     */
    private fun isOrderedCollectionDelegate(prop: KSPropertyDeclaration): Boolean {
        val text = readSourceLines(prop, linesBefore = 0, linesAfter = 5) ?: return false
        return when {
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
     * Extracts the referenced entity FQN from a collection reference type by walking the
     * [REACTIVE_ENTITY_COLLECTION_REFERENCE_FQN] supertype and reading its first type argument.
     */
    private fun findReferencedClassFqnFromCollectionType(type: KSType): String? {
        val declaration = type.declaration

        if (declaration is KSTypeAlias) {
            return findReferencedClassFqnFromCollectionType(declaration.type.resolve())
        }

        // Direct type arguments on the resolved type (e.g., AggregateListRefDelegate<Int, Track>)
        // E is the second type argument after the K-first ordering
        val typeArgs = type.arguments
        if (typeArgs.size >= 2) {
            val entityArg = typeArgs[1].type?.resolve()
            return entityArg?.declaration?.qualifiedName?.asString()
        }

        // Walk supertypes for ReactiveEntityCollectionReference<K, E>
        if (declaration is KSClassDeclaration) {
            for (superType in declaration.superTypes) {
                val resolvedSuperType = superType.resolve()
                val superFqn = resolvedSuperType.declaration.qualifiedName?.asString()
                if (superFqn == REACTIVE_ENTITY_COLLECTION_REFERENCE_FQN) {
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