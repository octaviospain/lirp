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
import com.google.devtools.ksp.validate

private const val REACTIVE_ENTITY_REF_ANNOTATION_FQN = "net.transgressoft.lirp.persistence.ReactiveEntityRef"

/**
 * KSP processor that generates [LirpRefAccessor][net.transgressoft.lirp.persistence.LirpRefAccessor]
 * implementations for entity classes containing
 * [@ReactiveEntityRef][net.transgressoft.lirp.persistence.ReactiveEntityRef] properties.
 *
 * For each entity class, a `{ClassName}_LirpRefAccessor` is generated in the same package, providing
 * direct ID getter lambdas and aggregate reference metadata — zero runtime reflection.
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

    private fun generateAccessor(classDecl: KSClassDeclaration, properties: List<KSPropertyDeclaration>) {
        val packageName = classDecl.packageName.asString()
        val className = classDecl.simpleName.asString()
        val accessorName = "${className}_LirpRefAccessor"

        val entries =
            properties.mapNotNull { prop ->
                val annotation =
                    prop.annotations.firstOrNull {
                        it.annotationType.resolve().declaration.qualifiedName?.asString() == REACTIVE_ENTITY_REF_ANNOTATION_FQN
                    } ?: return@mapNotNull null

                val bubbleUp = annotation.arguments.firstOrNull { it.name?.asString() == "bubbleUp" }?.value as? Boolean ?: false

                // onDelete argument is an enum value — extract its simple name (e.g., "CASCADE", "DETACH", "NONE")
                // KSP represents enum annotation arguments as KSType instances. The declaration's simpleName
                // gives the enum constant name directly.
                val onDeleteArg = annotation.arguments.firstOrNull { it.name?.asString() == "onDelete" }
                val onDeleteValue = onDeleteArg?.value
                val cascadeActionName =
                    when {
                        onDeleteValue is KSType -> onDeleteValue.declaration.simpleName.asString()
                        // Fallback: KSP sometimes returns the enum value as a string representation
                        onDeleteValue != null -> {
                            val str = onDeleteValue.toString()
                            when {
                                str.endsWith("CASCADE") -> "CASCADE"
                                str.endsWith("NONE") -> "NONE"
                                else -> "DETACH"
                            }
                        }
                        else -> "DETACH"
                    }

                // The referenced entity class is the first type argument of ReactiveEntityReference<E, K>
                val referencedClassFqn =
                    extractReferencedClassFqn(prop)
                        ?: run {
                            logger.warn("Cannot determine referenced class for property '${prop.simpleName.asString()}' in $className — skipping")
                            return@mapNotNull null
                        }

                RefPropertyMeta(
                    refName = prop.simpleName.asString(),
                    propertyName = prop.simpleName.asString(),
                    referencedClassFqn = referencedClassFqn,
                    bubbleUp = bubbleUp,
                    cascadeAction = cascadeActionName
                )
            }

        val file =
            codeGenerator.createNewFile(
                dependencies = Dependencies(false, classDecl.containingFile!!),
                packageName = packageName,
                fileName = accessorName
            )

        // Collect import statements for all referenced entity classes
        val referencedImports =
            entries
                .map { it.referencedClassFqn }
                .distinct()
                .filter { it.contains('.') }
                .sorted()

        val entriesCode =
            entries.joinToString(",\n        ") { meta ->
                val referencedSimpleName = meta.referencedClassFqn.substringAfterLast('.')
                "RefEntry(\"${meta.refName}\", { it.${meta.propertyName} }, $referencedSimpleName::class.java, ${meta.bubbleUp}, CascadeAction.${meta.cascadeAction})"
            }

        file.write(
            buildString {
                if (packageName.isNotEmpty()) {
                    appendLine("package $packageName")
                    appendLine()
                }
                appendLine("import net.transgressoft.lirp.entity.CascadeAction")
                appendLine("import net.transgressoft.lirp.persistence.LirpRefAccessor")
                appendLine("import net.transgressoft.lirp.persistence.RefEntry")
                for (importFqn in referencedImports) {
                    appendLine("import $importFqn")
                }
                appendLine()
                appendLine("/**")
                appendLine(" * KSP-generated aggregate reference accessor for [$className].")
                appendLine(" * Provides direct ID getter lambdas and reference metadata — no runtime reflection.")
                appendLine(" */")
                appendLine("public class $accessorName : LirpRefAccessor<$className> {")
                appendLine("    override val entries: List<RefEntry<$className>> = listOf(")
                appendLine("        $entriesCode")
                appendLine("    )")
                appendLine("}")
            }.toByteArray()
        )
        file.close()

        logger.info("Generated $packageName.$accessorName for $className")
    }

    /**
     * Extracts the fully qualified name of the referenced entity class from the property's
     * declared type. The property type is expected to be `ReactiveEntityReference<E, K>` where
     * `E` is the referenced entity type.
     */
    private fun extractReferencedClassFqn(prop: KSPropertyDeclaration): String? {
        val propType = prop.type.resolve()
        // If the property uses a delegate (by aggregateRef<E,K> { ... }), the declared type
        // in source is ReactiveEntityReference<E, K>. We get the first type argument.
        val typeArgs = propType.arguments
        if (typeArgs.isNotEmpty()) {
            val firstArg = typeArgs.first().type?.resolve()
            return firstArg?.declaration?.qualifiedName?.asString()
        }
        return null
    }
}

private data class RefPropertyMeta(
    val refName: String,
    val propertyName: String,
    val referencedClassFqn: String,
    val bubbleUp: Boolean,
    val cascadeAction: String
)