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

private const val AGGREGATE_ANNOTATION_FQN = "net.transgressoft.lirp.persistence.Aggregate"
private const val AGGREGATE_COLLECTION_REF_FQN = "net.transgressoft.lirp.persistence.AggregateCollectionRef"
private const val AGGREGATE_LIST_REF_DELEGATE_FQN = "net.transgressoft.lirp.persistence.AggregateListRefDelegate"
private const val AGGREGATE_SET_REF_DELEGATE_FQN = "net.transgressoft.lirp.persistence.AggregateSetRefDelegate"
private val STDLIB_COLLECTION_FQNS =
    setOf(
        "kotlin.collections.MutableList",
        "kotlin.collections.MutableSet",
        "kotlin.collections.List",
        "kotlin.collections.Set"
    )

/**
 * KSP processor that generates [LirpViaAccessor][net.transgressoft.lirp.persistence.LirpViaAccessor]
 * implementations for entity classes containing
 * [@Aggregate][net.transgressoft.lirp.persistence.Aggregate] properties.
 *
 * For each entity class, a `{ClassName}_LirpViaAccessor` file is emitted in the same package as the
 * entity, providing typed [kotlin.reflect.KProperty1] descriptors that the cross-aggregate Query DSL
 * planner consumes to resolve `via … anyMatch / allMatch / noneMatch / where` operator chains at
 * query time.
 *
 * The generated class mirrors the sibling [net.transgressoft.lirp.persistence.LirpRefAccessor] file
 * placement convention (one `{Entity}_LirpXxxAccessor` per concern, discovered via [Class.forName]).
 * Unlike [net.transgressoft.lirp.ksp.ReactiveEntityRefProcessor], this processor does NOT distinguish
 * `aggregateList` vs `aggregateSet` semantics — `via` operates on `Collection<K>` uniformly.
 *
 * Collection reference detection reuses the same source-text-then-supertype-walk strategy as
 * [ReactiveEntityRefProcessor] (the stdlib `List`/`Set` return types of the aggregate factories
 * do not have `AggregateCollectionRef` in their supertype chain). Single-entity refs fall through
 * to the standard supertype walk.
 */
class LirpViaAccessorProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(AGGREGATE_ANNOTATION_FQN)
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

    private fun generateAccessor(classDecl: KSClassDeclaration, properties: List<KSPropertyDeclaration>) {
        val packageName = classDecl.packageName.asString()
        val className = classDecl.jvmBinaryName()
        val kotlinClassName = classDecl.kotlinNestedName()
        val accessorName = "${className}_LirpViaAccessor"

        val singleMetas = mutableListOf<ViaPropertyMeta>()
        val collectionMetas = mutableListOf<ViaPropertyMeta>()

        for (prop in properties) {
            val resolvedType = prop.type.resolve()
            if (isCollectionReference(prop, resolvedType)) {
                val referencedClassFqn =
                    findReferencedClassFqnFromCollectionType(resolvedType)
                        ?: run {
                            logger.warn(
                                "Cannot determine referenced class for collection property " +
                                    "'${prop.simpleName.asString()}' in $className — skipping"
                            )
                            continue
                        }
                collectionMetas.add(ViaPropertyMeta(prop.simpleName.asString(), referencedClassFqn))
            } else {
                val referencedClassFqn =
                    findReferencedClassFqnFromType(resolvedType)
                        ?: run {
                            logger.warn(
                                "Cannot determine referenced class for property " +
                                    "'${prop.simpleName.asString()}' in $className — skipping"
                            )
                            continue
                        }
                singleMetas.add(ViaPropertyMeta(prop.simpleName.asString(), referencedClassFqn))
            }
        }

        val containingFile = classDecl.containingFile
        if (containingFile == null) {
            // Classpath or synthetic symbols carry no source file — skip silently rather than crash.
            logger.warn(
                "Skipping $className: containingFile is null (classpath or synthetic origin)"
            )
            return
        }

        val file =
            codeGenerator.createNewFile(
                dependencies = Dependencies(false, containingFile),
                packageName = packageName,
                fileName = accessorName
            )

        val allReferencedFqns =
            (singleMetas.map { it.referencedClassFqn } + collectionMetas.map { it.referencedClassFqn })
                .distinct()
                .filter { it.contains('.') }
                .sorted()

        val collectionEntriesCode =
            collectionMetas.joinToString(",\n        ") { meta ->
                val referencedSimpleName = meta.referencedClassFqn.substringAfterLast('.')
                """
                @Suppress("UNCHECKED_CAST")
                ViaCollectionAccessorEntry(
                    refName = "${meta.refName}",
                    parentProp = $kotlinClassName::${meta.refName} as KProperty1<$kotlinClassName, Collection<Nothing>>,
                    referencedClass = $referencedSimpleName::class.java
                )
                """.trimIndent()
            }

        val singleEntriesCode =
            singleMetas.joinToString(",\n        ") { meta ->
                val referencedSimpleName = meta.referencedClassFqn.substringAfterLast('.')
                """
                @Suppress("UNCHECKED_CAST")
                ViaSingleAccessorEntry(
                    refName = "${meta.refName}",
                    parentProp = $kotlinClassName::${meta.refName} as KProperty1<$kotlinClassName, Nothing?>,
                    referencedClass = $referencedSimpleName::class.java
                )
                """.trimIndent()
            }

        file.write(
            buildString {
                if (packageName.isNotEmpty()) {
                    appendLine("package $packageName")
                    appendLine()
                }
                appendLine("import kotlin.reflect.KProperty1")
                appendLine("import net.transgressoft.lirp.persistence.LirpViaAccessor")
                appendLine("import net.transgressoft.lirp.persistence.ViaCollectionAccessorEntry")
                appendLine("import net.transgressoft.lirp.persistence.ViaSingleAccessorEntry")
                for (importFqn in allReferencedFqns) {
                    appendLine("import $importFqn")
                }
                appendLine()
                appendLine("/**")
                appendLine(" * KSP-generated cross-aggregate `via` accessor for [$className].")
                appendLine(" * Provides typed KProperty1 descriptors consumed by the Query DSL planner — do not edit.")
                appendLine(" */")
                appendLine("public class `$accessorName` : LirpViaAccessor<$kotlinClassName> {")
                if (collectionMetas.isEmpty()) {
                    appendLine(
                        "    override val collectionEntries: List<ViaCollectionAccessorEntry<*, $kotlinClassName>> = emptyList()"
                    )
                } else {
                    appendLine(
                        "    override val collectionEntries: List<ViaCollectionAccessorEntry<*, $kotlinClassName>> = listOf("
                    )
                    appendLine("        $collectionEntriesCode")
                    appendLine("    )")
                }
                appendLine()
                if (singleMetas.isEmpty()) {
                    appendLine(
                        "    override val singleEntries: List<ViaSingleAccessorEntry<*, $kotlinClassName>> = emptyList()"
                    )
                } else {
                    appendLine(
                        "    override val singleEntries: List<ViaSingleAccessorEntry<*, $kotlinClassName>> = listOf("
                    )
                    appendLine("        $singleEntriesCode")
                    appendLine("    )")
                }
                appendLine("}")
            }.toByteArray()
        )
        file.close()

        logger.info("Generated $packageName.$accessorName for $className")
    }

    private fun isCollectionReference(prop: KSPropertyDeclaration, type: KSType): Boolean {
        val text = readSourceLines(prop, linesBefore = 0, linesAfter = 1)
        if (text != null && containsAggregateFactoryCall(text)) return true
        return isCollectionReferenceByType(type)
    }

    private fun containsAggregateFactoryCall(text: String): Boolean =
        text.contains("mutableAggregateList") ||
            text.contains("mutableAggregateSet") ||
            text.contains("aggregateList") ||
            text.contains("aggregateSet")

    private fun isCollectionReferenceByType(type: KSType): Boolean {
        val declaration = type.declaration
        if (declaration is KSTypeAlias) {
            return isCollectionReferenceByType(declaration.type.resolve())
        }
        if (isCollectionReferenceFqn(declaration.qualifiedName?.asString())) return true

        if (declaration is KSClassDeclaration) {
            for (superType in declaration.superTypes) {
                if (isCollectionReferenceByType(superType.resolve())) return true
            }
        }
        return false
    }

    private fun isCollectionReferenceFqn(fqn: String?): Boolean =
        fqn == AGGREGATE_COLLECTION_REF_FQN ||
            fqn == AGGREGATE_LIST_REF_DELEGATE_FQN ||
            fqn == AGGREGATE_SET_REF_DELEGATE_FQN

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

    private fun findReferencedClassFqnFromCollectionType(type: KSType): String? {
        val declaration = type.declaration

        if (declaration is KSTypeAlias) {
            return findReferencedClassFqnFromCollectionType(declaration.type.resolve())
        }

        val typeArgs = type.arguments
        val fqn = declaration.qualifiedName?.asString()

        if (fqn in STDLIB_COLLECTION_FQNS && typeArgs.size == 1) {
            val entityArg = typeArgs[0].type?.resolve()
            return entityArg?.declaration?.qualifiedName?.asString()
        }

        if (typeArgs.size >= 2) {
            val entityArg = typeArgs[1].type?.resolve()
            return entityArg?.declaration?.qualifiedName?.asString()
        }

        if (declaration is KSClassDeclaration) {
            for (superType in declaration.superTypes) {
                val resolvedSuperType = superType.resolve()
                val superFqn = resolvedSuperType.declaration.qualifiedName?.asString()
                if (superFqn == AGGREGATE_COLLECTION_REF_FQN) {
                    val entityArg = resolvedSuperType.arguments.getOrNull(1)?.type?.resolve()
                    return entityArg?.declaration?.qualifiedName?.asString()
                }
                val deepResult = findReferencedClassFqnFromCollectionType(resolvedSuperType)
                if (deepResult != null) return deepResult
            }
        }

        return null
    }

    private fun findReferencedClassFqnFromType(type: KSType): String? {
        val declaration = type.declaration

        if (declaration is KSTypeAlias) {
            return findReferencedClassFqnFromType(declaration.type.resolve())
        }

        val typeArgs = type.arguments
        if (typeArgs.size >= 2) {
            val entityArg = typeArgs[1].type?.resolve()
            return entityArg?.declaration?.qualifiedName?.asString()
        }

        if (declaration is KSClassDeclaration) {
            for (superType in declaration.superTypes) {
                val resolvedSuperType = superType.resolve()
                val superFqn = resolvedSuperType.declaration.qualifiedName?.asString()
                if (superFqn == "net.transgressoft.lirp.persistence.ReactiveEntityReference") {
                    val entityArg = resolvedSuperType.arguments.getOrNull(1)?.type?.resolve()
                    return entityArg?.declaration?.qualifiedName?.asString()
                }
                val deepResult = findReferencedClassFqnFromType(resolvedSuperType)
                if (deepResult != null) return deepResult
            }
        }

        return null
    }
}

private data class ViaPropertyMeta(
    val refName: String,
    val referencedClassFqn: String
)