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
import com.google.devtools.ksp.validate

private const val LIRP_REPOSITORY_ANNOTATION_FQN = "net.transgressoft.lirp.persistence.LirpRepository"

private val REPOSITORY_BASE_FQN_SET =
    setOf(
        "net.transgressoft.lirp.persistence.VolatileRepository",
        "net.transgressoft.lirp.persistence.PersistentRepositoryBase",
        "net.transgressoft.lirp.persistence.json.JsonFileRepository",
        "net.transgressoft.lirp.persistence.json.FlexibleJsonFileRepository"
    )

/**
 * KSP processor that generates [LirpRegistryInfo][net.transgressoft.lirp.persistence.LirpRegistryInfo]
 * implementations for repository classes annotated with
 * [@LirpRepository][net.transgressoft.lirp.persistence.LirpRepository].
 *
 * For each annotated class, a `{ClassName}_LirpRegistryInfo` is generated in the same package,
 * exposing the entity class for zero-config self-registration at construction time.
 */
class LirpRepositoryProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation(LIRP_REPOSITORY_ANNOTATION_FQN)
        val unableToProcess = mutableListOf<KSAnnotated>()

        for (symbol in symbols) {
            if (symbol !is KSClassDeclaration) continue
            if (!symbol.validate()) {
                unableToProcess.add(symbol)
                continue
            }
            val entityClassFqn = findEntityClassFqn(symbol)
            if (entityClassFqn == null) {
                logger.warn("Cannot determine entity class for @LirpRepository on ${symbol.qualifiedName?.asString()} — skipping")
                continue
            }
            generateRegistryInfo(symbol, entityClassFqn)
        }

        return unableToProcess
    }

    /**
     * Walks the supertype chain of [classDecl] recursively to find a known repository base class
     * ([VolatileRepository], [PersistentRepositoryBase], [JsonFileRepository], or [FlexibleJsonFileRepository])
     * and extract its second type argument (index 1) as the entity class FQN.
     */
    private fun findEntityClassFqn(classDecl: KSClassDeclaration): String? {
        for (superTypeRef in classDecl.superTypes) {
            val superType = superTypeRef.resolve()
            val superDecl = superType.declaration as? KSClassDeclaration ?: continue
            val superFqn = superDecl.qualifiedName?.asString() ?: continue

            if (superFqn in REPOSITORY_BASE_FQN_SET) {
                // The second type argument (index 1) is the entity type
                return superType.arguments.getOrNull(1)?.type?.resolve()?.declaration?.qualifiedName?.asString()
            }

            // Recurse into supertype chain to handle multi-level hierarchies
            val found = findEntityClassFqn(superDecl)
            if (found != null) return found
        }
        return null
    }

    private fun generateRegistryInfo(classDecl: KSClassDeclaration, entityClassFqn: String) {
        val packageName = classDecl.packageName.asString()
        val className = classDecl.simpleName.asString()
        val infoName = "${className}_LirpRegistryInfo"
        val entitySimpleName = entityClassFqn.substringAfterLast('.')

        val file =
            codeGenerator.createNewFile(
                dependencies = Dependencies(false, classDecl.containingFile!!),
                packageName = packageName,
                fileName = infoName
            )

        file.write(
            buildString {
                if (packageName.isNotEmpty()) {
                    appendLine("package $packageName")
                    appendLine()
                }
                appendLine("import net.transgressoft.lirp.persistence.LirpRegistryInfo")
                if (entityClassFqn.contains('.')) {
                    appendLine("import $entityClassFqn")
                }
                appendLine()
                appendLine("/**")
                appendLine(" * KSP-generated registry info for [$className].")
                appendLine(" * Exposes the entity class for zero-config self-registration.")
                appendLine(" */")
                appendLine("public class $infoName : LirpRegistryInfo {")
                appendLine("    override val entityClass: Class<*> = $entitySimpleName::class.java")
                appendLine("}")
            }.toByteArray()
        )
        file.close()

        logger.info("Generated $packageName.$infoName for $className (entity: $entityClassFqn)")
    }
}