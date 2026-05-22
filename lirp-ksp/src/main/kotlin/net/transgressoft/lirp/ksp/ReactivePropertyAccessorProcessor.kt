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

import com.google.devtools.ksp.isAbstract
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.validate

/**
 * KSP processor that generates [LirpReactivePropertyAccessor][net.transgressoft.lirp.persistence.LirpReactivePropertyAccessor]
 * implementations for entity classes containing `var x by reactiveProperty(...)`-delegated properties.
 *
 * Eliminates the `KProperty1.get` + `Method.invoke(setValueMethod)` reflection branch in
 * `LirpEntitySerializer`: every entry's `silentSetter` resolves the delegate via the entity's lazy
 * `delegateRegistry` and writes the backing field through
 * [writeReactivePropertyBackingField][net.transgressoft.lirp.persistence.writeReactivePropertyBackingField],
 * bypassing event emission, lastDateModified bumping, and clone comparison.
 *
 * Detection mirrors [FxScalarAccessorProcessor] but consumes the composite predicate
 * [isReactivePropertyDelegate] from [KspUtils] — KSP exposes no direct delegate-type accessor on
 * [KSPropertyDeclaration], so detection relies on `isDelegated`, `isMutable`, and exclusion of
 * FxScalar / kotlin-collections value types.
 */
class ReactivePropertyAccessorProcessor(private val codeGenerator: CodeGenerator, private val logger: KSPLogger) : SymbolProcessor {

    // Tracks FQNs of classes whose accessor has already been generated to prevent duplicate
    // file creation across KSP processing rounds.
    private val generatedAccessors = mutableSetOf<String>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        resolver.getNewFiles()
            .flatMap { it.declarations }
            .filterIsInstance<KSClassDeclaration>()
            .flatMap { allClassDeclarations(it) }
            .filter { it.validate() && isLirpEntity(it) && !isAnonymousOrLocal(it) }
            .forEach { classDecl ->
                val fqn = classDecl.qualifiedName?.asString() ?: return@forEach
                if (fqn in generatedAccessors) return@forEach
                // Generic abstract bases (e.g. ReactivePrimitiveWrapper<R, V>) cannot have a
                // non-parameterized accessor. Concrete subclasses get their own accessor.
                // Abstract entities cannot be instantiated from persisted rows, so any accessor
                // generated for them would be unreachable.
                if (classDecl.typeParameters.isNotEmpty() || classDecl.isAbstract()) return@forEach
                // Walk the parent declaration chain: a `public` nested entity inside an
                // `internal`/`private` outer would still produce `public exposes internal type`
                // compile errors on the generated `public class ... : LirpReactivePropertyAccessor<E>`.
                if (!isPubliclyVisible(classDecl)) return@forEach
                val reactiveProps = collectReactivePropertiesIncludingInherited(classDecl)
                if (reactiveProps.isNotEmpty()) {
                    generateAccessor(classDecl, reactiveProps)
                    generatedAccessors.add(fqn)
                }
            }
        return emptyList()
    }

    private fun generateAccessor(classDecl: KSClassDeclaration, properties: List<KSPropertyDeclaration>) {
        val packageName = classDecl.packageName.asString()
        val jvmName = classDecl.jvmBinaryName()
        val kotlinName = classDecl.kotlinNestedName()
        val accessorName = "${jvmName}_LirpReactivePropertyAccessor"
        // Backtick-escape the class name in Kotlin source when it contains '$' (nested class separator)
        val accessorSourceName = if ('$' in accessorName) "`$accessorName`" else accessorName

        val entries =
            properties.map { prop ->
                val propName = prop.simpleName.asString()
                val renderedType = renderKsType(prop.type.resolve())
                ReactivePropMeta(propName, renderedType)
            }

        val containingFile =
            classDecl.containingFile ?: run {
                logger.warn("Skipping $kotlinName: no containing file (synthetic class?)")
                return
            }
        val file =
            codeGenerator.createNewFile(
                dependencies = Dependencies(false, containingFile),
                packageName = packageName,
                fileName = accessorName
            )

        val entriesCode =
            entries.joinToString(",\n        ") { meta ->
                buildString {
                    appendLine("ReactivePropertyEntry(")
                    appendLine("                name = \"${meta.propName}\",")
                    appendLine("                getter = { it.${meta.propName} },")
                    appendLine("                silentSetter = { entity, value ->")
                    appendLine("                    writeReactivePropertyBackingField<Any?>(entity, \"${meta.propName}\", value)")
                    appendLine("                },")
                    append("                serializer = @Suppress(\"UNCHECKED_CAST\") serializer<${meta.renderedType}>() as KSerializer<Any?>")
                    append("\n            )")
                }
            }

        file.write(
            buildString {
                appendLine("// Generated — DO NOT EDIT")
                if (packageName.isNotEmpty()) {
                    appendLine("package $packageName")
                    appendLine()
                }
                appendLine("import kotlinx.serialization.KSerializer")
                appendLine("import kotlinx.serialization.serializer")
                appendLine("import net.transgressoft.lirp.persistence.LirpReactivePropertyAccessor")
                appendLine("import net.transgressoft.lirp.persistence.ReactivePropertyEntry")
                appendLine("import net.transgressoft.lirp.persistence.writeReactivePropertyBackingField")
                appendLine()
                appendLine("/**")
                appendLine(" * KSP-generated reactive-property accessor for [$kotlinName].")
                appendLine(" * Provides direct get/silent-set lambdas — no runtime reflection on the entity.")
                appendLine(" */")
                appendLine("@Suppress(\"UNCHECKED_CAST\")")
                appendLine("@OptIn(kotlin.uuid.ExperimentalUuidApi::class)")
                appendLine("public class $accessorSourceName : LirpReactivePropertyAccessor<$kotlinName> {")
                appendLine("    override val entries: List<ReactivePropertyEntry<$kotlinName>> = listOf(")
                appendLine("        $entriesCode")
                appendLine("    )")
                appendLine("}")
            }.toByteArray()
        )
        file.close()

        logger.info("Generated $packageName.$accessorName for $kotlinName")
    }
}

private data class ReactivePropMeta(
    val propName: String,
    val renderedType: String
)