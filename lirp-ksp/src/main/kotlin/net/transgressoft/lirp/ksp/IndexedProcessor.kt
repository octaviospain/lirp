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
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.validate

private const val INDEXED_ANNOTATION_FQN = "net.transgressoft.lirp.persistence.Indexed"

/**
 * KSP processor that generates [LirpIndexAccessor][net.transgressoft.lirp.persistence.LirpIndexAccessor]
 * implementations for entity classes containing [@Indexed][net.transgressoft.lirp.persistence.Indexed]
 * properties.
 *
 * For each entity class, a `{ClassName}_LirpIndexAccessor` is generated in the same package, providing
 * direct property getter lambdas compiled to regular method calls — zero runtime reflection.
 *
 * When a property is annotated with `@Indexed(sorted = true)`, the processor verifies at compile time
 * that the property type implements `Comparable<*>`. Non-conforming types produce a KSP build error
 * naming both the property and the entity.
 */
class IndexedProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(INDEXED_ANNOTATION_FQN)
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
        val visibility = effectiveVisibilityModifier(classDecl)
        if (visibility == null) {
            val fqn = classDecl.qualifiedName?.asString() ?: classDecl.simpleName.asString()
            logger.error(
                "Entity '$fqn' must be public or internal to generate a persistence companion (_LirpIndexAccessor). " +
                    "Private and protected entities cannot have accessible generated code.",
                classDecl
            )
            return
        }
        val packageName = classDecl.packageName.asString()
        val className = classDecl.simpleName.asString()
        val accessorName = "$className${LirpGenNames.INDEX_ACCESSOR_SUFFIX}"

        val entries = mutableListOf<IndexedPropertyMeta>()
        for (prop in properties) {
            val annotation =
                prop.annotations.first {
                    it.annotationType.resolve().declaration.qualifiedName?.asString() == INDEXED_ANNOTATION_FQN
                }
            val customName = annotation.arguments.firstOrNull { it.name?.asString() == "name" }?.value as? String
            val indexName = if (!customName.isNullOrEmpty()) customName else prop.simpleName.asString()
            val sortedArg = annotation.arguments.firstOrNull { it.name?.asString() == "sorted" }?.value as? Boolean == true

            if (sortedArg && !isComparableType(prop)) {
                logger.error(
                    "@Indexed(sorted = true) requires a Comparable property type; " +
                        "property '${prop.simpleName.asString()}' on entity '${classDecl.simpleName.asString()}' " +
                        "has non-Comparable type. Either implement Comparable or set sorted = false (the default).",
                    prop
                )
                continue
            }

            entries.add(IndexedPropertyMeta(indexName, prop.simpleName.asString(), sortedArg))
        }

        val file =
            codeGenerator.createNewFile(
                dependencies = Dependencies(false, classDecl.containingFile!!),
                packageName = packageName,
                fileName = accessorName
            )

        fun String.escapeForKotlinStringLiteral(): String =
            replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
                .replace("\n", "\\n").replace("\r", "\\r")

        val entriesCode =
            entries.joinToString(",\n        ") { meta ->
                val idx = meta.indexName.escapeForKotlinStringLiteral()
                val prop = meta.propertyName // identifier, not a string literal
                when {
                    meta.sorted -> "IndexEntry(\"$idx\", \"$prop\", sorted = true) { it.$prop }"
                    meta.indexName == meta.propertyName -> "IndexEntry(\"$idx\") { it.$prop }"
                    else -> "IndexEntry(\"$idx\", \"$prop\") { it.$prop }"
                }
            }

        file.write(
            buildString {
                if (packageName.isNotEmpty()) {
                    appendLine("package $packageName")
                    appendLine()
                }
                appendLine("import net.transgressoft.lirp.persistence.IndexEntry")
                appendLine("import net.transgressoft.lirp.persistence.LirpIndexAccessor")
                appendLine()
                appendLine("/**")
                appendLine(" * KSP-generated index accessor for [$className].")
                appendLine(" * Provides direct property getters — no runtime reflection.")
                appendLine(" */")
                appendLine("$visibility class $accessorName : LirpIndexAccessor<$className> {")
                appendLine("    override val entries: List<IndexEntry<$className>> = listOf(")
                appendLine("        $entriesCode")
                appendLine("    )")
                appendLine("}")
            }.toByteArray()
        )
        file.close()

        logger.info("Generated $packageName.$accessorName for $className")
    }

    /**
     * Returns true when the property's resolved type (or its non-nullable form for nullable properties)
     * implements `kotlin.Comparable` anywhere in its supertype chain.
     */
    private fun isComparableType(prop: KSPropertyDeclaration): Boolean {
        val resolvedType = prop.type.resolve().makeNotNullable()
        return isKsTypeComparable(resolvedType, mutableSetOf())
    }

    private fun isKsTypeComparable(
        type: KSType,
        visited: MutableSet<String>
    ): Boolean =
        when (val decl = type.declaration) {
            is KSClassDeclaration -> {
                val fqn = decl.qualifiedName?.asString()
                when {
                    fqn == null -> false
                    !visited.add("class:$fqn") -> false
                    fqn == "kotlin.Comparable" -> true
                    else -> decl.superTypes.any { isKsTypeComparable(it.resolve(), visited) }
                }
            }
            is KSTypeParameter ->
                visited.add("typeparam:${decl.name.asString()}") &&
                    decl.bounds.any { isKsTypeComparable(it.resolve(), visited) }
            else -> false
        }
}

private data class IndexedPropertyMeta(val indexName: String, val propertyName: String, val sorted: Boolean = false)