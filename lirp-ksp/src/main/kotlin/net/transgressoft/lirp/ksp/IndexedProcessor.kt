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
import com.google.devtools.ksp.validate

private const val INDEXED_ANNOTATION_FQN = "net.transgressoft.lirp.persistence.Indexed"

/**
 * KSP processor that generates [LirpIndexAccessor][net.transgressoft.lirp.persistence.LirpIndexAccessor]
 * implementations for entity classes containing [@Indexed][net.transgressoft.lirp.persistence.Indexed]
 * properties.
 *
 * For each entity class, a `{ClassName}_LirpIndexAccessor` is generated in the same package, providing
 * direct property getter lambdas compiled to regular method calls — zero runtime reflection.
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
        val packageName = classDecl.packageName.asString()
        val className = classDecl.simpleName.asString()
        val accessorName = "${className}_LirpIndexAccessor"

        val entries =
            properties.map { prop ->
                val annotation =
                    prop.annotations.first {
                        it.annotationType.resolve().declaration.qualifiedName?.asString() == INDEXED_ANNOTATION_FQN
                    }
                val customName = annotation.arguments.firstOrNull { it.name?.asString() == "name" }?.value as? String
                val indexName = if (!customName.isNullOrEmpty()) customName else prop.simpleName.asString()
                IndexedPropertyMeta(indexName, prop.simpleName.asString())
            }

        val file =
            codeGenerator.createNewFile(
                dependencies = Dependencies(false, classDecl.containingFile!!),
                packageName = packageName,
                fileName = accessorName
            )

        val entriesCode =
            entries.joinToString(",\n        ") { meta ->
                "IndexEntry(\"${meta.indexName}\") { it.${meta.propertyName} }"
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
                appendLine("public class $accessorName : LirpIndexAccessor<$className> {")
                appendLine("    override val entries: List<IndexEntry<$className>> = listOf(")
                appendLine("        $entriesCode")
                appendLine("    )")
                appendLine("}")
            }.toByteArray()
        )
        file.close()

        logger.info("Generated $packageName.$accessorName for $className")
    }
}

private data class IndexedPropertyMeta(val indexName: String, val propertyName: String)