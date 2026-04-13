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
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.validate

private const val FX_SCALAR_DELEGATE_FQN = "net.transgressoft.lirp.persistence.FxScalarPropertyDelegate"
private const val REACTIVE_ENTITY_BASE_FQN = "net.transgressoft.lirp.entity.ReactiveEntityBase"
private const val IDENTIFIABLE_ENTITY_FQN = "net.transgressoft.lirp.entity.IdentifiableEntity"
private const val NULLABLE_STRING_SERIALIZER = "@Suppress(\"UNCHECKED_CAST\") serializer<String?>() as KSerializer<Any?>"
private const val NULLABLE_STRING_CAST_TYPE = "String?"

/**
 * KSP processor that generates [LirpFxScalarAccessor][net.transgressoft.lirp.persistence.LirpFxScalarAccessor]
 * implementations for entity classes containing [FxScalarPropertyDelegate][net.transgressoft.lirp.persistence.FxScalarPropertyDelegate]
 * properties.
 *
 * Detection uses supertype-chain FQN walking rather than annotation scanning, since `FxScalarPropertyDelegate`
 * lives in `lirp-core` which is not a compile-time dependency of `lirp-ksp`. Only classes extending
 * `ReactiveEntityBase` or implementing `IdentifiableEntity` are processed; anonymous and local classes
 * are skipped.
 *
 * For each qualifying entity, a `{ClassName}_LirpFxScalarAccessor` is generated in the same package,
 * providing direct get/set lambdas and compile-time resolved [KSerializer][kotlinx.serialization.KSerializer]
 * instances — zero runtime reflection.
 */
class FxScalarAccessorProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

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
                val fxScalarProps = classDecl.getAllProperties().filter { isFxScalarProperty(it) }.toList()
                if (fxScalarProps.isNotEmpty()) {
                    generateAccessor(classDecl, fxScalarProps)
                    generatedAccessors.add(fqn)
                }
            }
        return emptyList()
    }

    private fun allClassDeclarations(classDecl: KSClassDeclaration): Sequence<KSClassDeclaration> =
        sequence {
            yield(classDecl)
            classDecl.declarations.filterIsInstance<KSClassDeclaration>().forEach {
                yieldAll(allClassDeclarations(it))
            }
        }

    private fun isAnonymousOrLocal(classDecl: KSClassDeclaration): Boolean =
        (classDecl.classKind == ClassKind.OBJECT && classDecl.simpleName.asString().isEmpty()) ||
            classDecl.qualifiedName == null

    private fun isLirpEntity(classDecl: KSClassDeclaration): Boolean =
        isTypeByFqn(classDecl, REACTIVE_ENTITY_BASE_FQN) || isTypeByFqn(classDecl, IDENTIFIABLE_ENTITY_FQN)

    private fun isTypeByFqn(
        classDecl: KSClassDeclaration,
        fqn: String,
        visited: MutableSet<String> = mutableSetOf()
    ): Boolean {
        val declFqn = classDecl.qualifiedName?.asString() ?: return false
        if (!visited.add(declFqn)) return false
        if (declFqn == fqn) return true
        for (superType in classDecl.superTypes) {
            val declaration = superType.resolve().declaration
            if (declaration is KSClassDeclaration && isTypeByFqn(declaration, fqn, visited)) return true
        }
        return false
    }

    private fun isFxScalarProperty(prop: KSPropertyDeclaration): Boolean =
        isFxScalarType(prop.type.resolve())

    private fun isFxScalarType(type: KSType, visited: MutableSet<String> = mutableSetOf()): Boolean {
        val declaration = type.declaration
        if (declaration is KSTypeAlias) return isFxScalarType(declaration.type.resolve(), visited)
        val declFqn = declaration.qualifiedName?.asString() ?: return false
        if (!visited.add(declFqn)) return false
        if (declFqn == FX_SCALAR_DELEGATE_FQN) return true
        if (declaration is KSClassDeclaration) {
            for (superType in declaration.superTypes) {
                if (isFxScalarType(superType.resolve(), visited)) return true
            }
        }
        return false
    }

    private fun generateAccessor(classDecl: KSClassDeclaration, properties: List<KSPropertyDeclaration>) {
        val packageName = classDecl.packageName.asString()
        val jvmName = classDecl.jvmBinaryName()
        val kotlinName = classDecl.kotlinNestedName()
        val accessorName = "${jvmName}_LirpFxScalarAccessor"
        // Backtick-escape the class name in Kotlin source when it contains '$' (nested class separator)
        val accessorSourceName = if ('$' in accessorName) "`$accessorName`" else accessorName

        val entries =
            properties.map { prop ->
                val propName = prop.simpleName.asString()
                val propTypeFqn = prop.type.resolve().declaration.qualifiedName?.asString() ?: ""
                val (serializerExpr, castType) = resolveSerializerAndCastType(prop, propTypeFqn)
                FxScalarPropMeta(propName, serializerExpr, castType)
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
                    appendLine("FxScalarEntry(")
                    appendLine("                name = \"${meta.propName}\",")
                    appendLine("                getter = { it.${meta.propName}.get() },")
                    appendLine("                setter = { entity, value -> entity.${meta.propName}.set(value as ${meta.castType}) },")
                    append("                serializer = ${meta.serializerExpr}")
                    append("\n            )")
                }
            }

        file.write(
            buildString {
                if (packageName.isNotEmpty()) {
                    appendLine("package $packageName")
                    appendLine()
                }
                appendLine("import kotlinx.serialization.KSerializer")
                appendLine("import kotlinx.serialization.serializer")
                appendLine("import net.transgressoft.lirp.persistence.FxScalarEntry")
                appendLine("import net.transgressoft.lirp.persistence.LirpFxScalarAccessor")
                appendLine()
                appendLine("/**")
                appendLine(" * KSP-generated FxScalar accessor for [$kotlinName].")
                appendLine(" * Provides direct get/set lambdas — no runtime reflection.")
                appendLine(" */")
                appendLine("@Suppress(\"UNCHECKED_CAST\")")
                appendLine("public class $accessorSourceName : LirpFxScalarAccessor<$kotlinName> {")
                appendLine("    override val entries: List<FxScalarEntry<$kotlinName>> = listOf(")
                appendLine("        $entriesCode")
                appendLine("    )")
                appendLine("}")
            }.toByteArray()
        )
        file.close()

        logger.info("Generated $packageName.$accessorName for $kotlinName")
    }

    private fun resolveSerializerAndCastType(
        prop: KSPropertyDeclaration,
        propTypeFqn: String
    ): Pair<String, String> =
        when {
            propTypeFqn.endsWith("StringProperty") ->
                NULLABLE_STRING_SERIALIZER to NULLABLE_STRING_CAST_TYPE
            propTypeFqn.endsWith("IntegerProperty") ->
                "@Suppress(\"UNCHECKED_CAST\") serializer<Int>() as KSerializer<Any?>" to "Int"
            propTypeFqn.endsWith("DoubleProperty") ->
                "@Suppress(\"UNCHECKED_CAST\") serializer<Double>() as KSerializer<Any?>" to "Double"
            propTypeFqn.endsWith("FloatProperty") ->
                "@Suppress(\"UNCHECKED_CAST\") serializer<Float>() as KSerializer<Any?>" to "Float"
            propTypeFqn.endsWith("LongProperty") ->
                "@Suppress(\"UNCHECKED_CAST\") serializer<Long>() as KSerializer<Any?>" to "Long"
            propTypeFqn.endsWith("BooleanProperty") ->
                "@Suppress(\"UNCHECKED_CAST\") serializer<Boolean>() as KSerializer<Any?>" to "Boolean"
            propTypeFqn.endsWith("ObjectProperty") -> resolveObjectPropertySerializer(prop)
            else -> NULLABLE_STRING_SERIALIZER to NULLABLE_STRING_CAST_TYPE
        }

    private fun resolveObjectPropertySerializer(prop: KSPropertyDeclaration): Pair<String, String> {
        val typeArg = prop.type.resolve().arguments.getOrNull(0)?.type?.resolve()
        return if (typeArg != null) {
            val rendered = renderKsType(typeArg)
            "@Suppress(\"UNCHECKED_CAST\") serializer<$rendered?>() as KSerializer<Any?>" to "$rendered?"
        } else {
            NULLABLE_STRING_SERIALIZER to NULLABLE_STRING_CAST_TYPE
        }
    }

    private fun renderKsType(type: KSType): String {
        val baseName = type.declaration.qualifiedName?.asString() ?: return "String"
        val args = type.arguments
        if (args.isEmpty()) return baseName
        val renderedArgs =
            args.joinToString(", ") { arg ->
                val argType = arg.type?.resolve()
                if (argType != null) renderKsType(argType) else "*"
            }
        return "$baseName<$renderedArgs>"
    }
}

private data class FxScalarPropMeta(
    val propName: String,
    val serializerExpr: String,
    val castType: String
)