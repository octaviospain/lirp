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
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.validate

private const val REACTIVE_ENTITY_REF_ANNOTATION_FQN = "net.transgressoft.lirp.persistence.ReactiveEntityRef"
private const val REACTIVE_ENTITY_REFERENCE_FQN = "net.transgressoft.lirp.persistence.ReactiveEntityReference"

/**
 * KSP processor that generates [LirpRefAccessor][net.transgressoft.lirp.persistence.LirpRefAccessor]
 * implementations for entity classes containing
 * [@ReactiveEntityRef][net.transgressoft.lirp.persistence.ReactiveEntityRef] properties.
 *
 * For each entity class, a `{ClassName}_LirpRefAccessor` is generated in the same package, providing:
 * - Direct ID getter lambdas (`idGetter`) via the delegate's `referenceId` property
 * - Direct delegate getter lambdas (`delegateGetter`) via the Kotlin `${'$'}delegate` backing field convention
 * - A `cancelAllBubbleUp` override that cancels all bubble-up subscriptions without any reflection
 *
 * The generated code uses named constructor arguments for [RefEntry][net.transgressoft.lirp.persistence.RefEntry]
 * so that future field additions remain backward-compatible.
 *
 * Type resolution handles type aliases and intermediate interfaces: if a property's declared type
 * is a type alias or an intermediate interface (not `ReactiveEntityReference` directly), the processor
 * recursively walks the supertype chain to find the `ReactiveEntityReference<E, K>` supertype and
 * extracts `E` from its first type argument.
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
                                str.endsWith("RESTRICT") -> "RESTRICT"
                                else -> "DETACH"
                            }
                        }
                        else -> "DETACH"
                    }

                // The referenced entity class is extracted recursively, handling type aliases
                // and intermediate interfaces that extend ReactiveEntityReference<E, K>
                val referencedClassFqn =
                    findReferencedClassFqnFromType(prop.type.resolve())
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

        // Build RefEntry named constructor calls with delegateGetter and typed idGetter.
        // Since AggregateRefDelegate.getValue() returns `this`, accessing `it.propName` at runtime
        // returns the delegate itself typed as ReactiveEntityReference<E, K>. Casting to
        // AggregateRefDelegate<*, *> is safe — the only aggregateRef<E,K> implementation is
        // AggregateRefDelegate. The UNCHECKED_CAST suppression is placed on each RefEntry call.
        val entriesCode =
            entries.joinToString(",\n        ") { meta ->
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
                appendLine("import net.transgressoft.lirp.persistence.LirpRefAccessor")
                appendLine("import net.transgressoft.lirp.persistence.RefEntry")
                for (importFqn in referencedImports) {
                    appendLine("import $importFqn")
                }
                appendLine()
                appendLine("/**")
                appendLine(" * KSP-generated aggregate reference accessor for [$className].")
                appendLine(" * Provides direct ID getter and delegate getter lambdas — no runtime reflection.")
                appendLine(" */")
                appendLine("public class $accessorName : LirpRefAccessor<$className> {")
                appendLine("    override val entries: List<RefEntry<$className, *>> = listOf(")
                appendLine("        $entriesCode")
                appendLine("    )")
                appendLine()
                appendLine(cancelAllBubbleUpCode)
                appendLine("}")
            }.toByteArray()
        )
        file.close()

        logger.info("Generated $packageName.$accessorName for $className")
    }

    /**
     * Recursively extracts the fully qualified name of the referenced entity class from a [KSType].
     *
     * Resolution strategy (in priority order):
     * 1. If the type is a [KSTypeAlias], unwrap and recurse on the aliased type.
     * 2. If the type has type arguments (direct generic such as `ReactiveEntityReference<Customer, Int>`),
     *    extract the FQN of the first type argument (`Customer`).
     * 3. If the type is a class declaration (possibly an intermediate interface), walk its supertype
     *    chain looking for a supertype whose FQN matches [REACTIVE_ENTITY_REFERENCE_FQN] and extract
     *    the first type argument from that supertype.
     *
     * Returns `null` if no referenced entity class can be determined.
     */
    private fun findReferencedClassFqnFromType(type: KSType): String? {
        val declaration = type.declaration

        // Case 1: type alias — unwrap and recurse
        if (declaration is KSTypeAlias) {
            return findReferencedClassFqnFromType(declaration.type.resolve())
        }

        // Case 2: type has direct type arguments (ReactiveEntityReference<E, K> or similar)
        val typeArgs = type.arguments
        if (typeArgs.isNotEmpty()) {
            val firstArg = typeArgs.first().type?.resolve()
            return firstArg?.declaration?.qualifiedName?.asString()
        }

        // Case 3: intermediate interface — walk supertype chain for ReactiveEntityReference<E, K>
        if (declaration is KSClassDeclaration) {
            for (superType in declaration.superTypes) {
                val resolvedSuperType = superType.resolve()
                val superFqn = resolvedSuperType.declaration.qualifiedName?.asString()
                if (superFqn == REACTIVE_ENTITY_REFERENCE_FQN) {
                    val firstArg = resolvedSuperType.arguments.firstOrNull()?.type?.resolve()
                    return firstArg?.declaration?.qualifiedName?.asString()
                }
                // Recurse deeper for multi-level inheritance chains
                val deepResult = findReferencedClassFqnFromType(resolvedSuperType)
                if (deepResult != null) return deepResult
            }
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