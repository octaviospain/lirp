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
import com.google.devtools.ksp.symbol.Modifier

private const val PERSISTENCE_IGNORE_FQN = "net.transgressoft.lirp.persistence.PersistenceIgnore"
private const val TRANSIENT_FQN = "kotlin.jvm.Transient"

private val COLLECTION_FQNS =
    setOf(
        "kotlin.collections.List",
        "kotlin.collections.MutableList",
        "kotlin.collections.Set",
        "kotlin.collections.MutableSet",
        "kotlin.collections.Collection",
        "kotlin.collections.MutableCollection",
        "kotlin.collections.Map",
        "kotlin.collections.MutableMap"
    )

/**
 * KSP processor that generates [LirpRawInitializer][net.transgressoft.lirp.persistence.LirpRawInitializer]
 * implementations for entity classes, used by `SqlRepository.loadFromStore` and
 * `JsonFileRepository.loadFromStore` to bulk-load entities without firing reactive events.
 *
 * For each qualifying entity the processor collects:
 *
 * - Reactive-backed properties detected via [isReactivePropertyDelegate] (ordinary
 *   `reactiveProperty(...)`, `@Version`, and `@Aggregate` single-ref Id properties).
 * - Non-reactive `var` scalar properties that are not constructor parameters (e.g.
 *   `lastDateModified`).
 *
 * Collection-typed `@Aggregate` properties (`aggregateList`, `aggregateSet`, and their `mutable*`
 * variants) are excluded — junction-row materialization is handled separately by
 * `SqlTableDef.applyJunctionRows`.
 *
 * Each generated entry's `silentSetter` writes the backing field through
 * [writeReactivePropertyBackingField][net.transgressoft.lirp.persistence.writeReactivePropertyBackingField]
 * for reactive-backed properties, or via a plain `entity.prop = value` cast for non-reactive
 * `var` properties.
 */
class RawInitializerProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    // Tracks FQNs of classes whose raw initializer has already been generated to prevent
    // duplicate file creation across KSP processing rounds.
    private val generatedAccessors = mutableSetOf<String>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        // validate() is intentionally omitted here. This processor only inspects property
        // declarations and reactive delegate patterns — it never reads annotation argument types
        // (e.g. @ElementCollection converter KClass arguments). Requiring validate() would cause
        // entities whose annotations reference types defined in the same compilation unit to be
        // silently dropped in KSP2 AA mode, where validate() returns false until all types in the
        // unit are resolved. Since getNewFiles() is only populated in round 1 and deferred source
        // entities cannot be recovered via getClassDeclarationByName in round 2, omitting
        // validate() is the correct policy for this processor.
        resolver.getNewFiles()
            .flatMap { it.declarations }
            .filterIsInstance<KSClassDeclaration>()
            .flatMap { allClassDeclarations(it) }
            .filter { isLirpEntity(it) && !isAnonymousOrLocal(it) }
            .forEach { classDecl ->
                val fqn = classDecl.qualifiedName?.asString() ?: return@forEach
                if (shouldSkip(classDecl, fqn)) return@forEach

                // Always emit a raw initializer for every persisted entity, even when the entries
                // list is empty. The validator requires the file to be present; consumers iterate
                // entries and a zero-entry list is a valid no-op for bulk-load.
                generateInitializer(classDecl, collectRawInitEntries(classDecl))
                generatedAccessors.add(fqn)
            }
        return emptyList()
    }

    private fun shouldSkip(classDecl: KSClassDeclaration, fqn: String): Boolean {
        if (fqn in generatedAccessors) return true
        // Generic abstract bases cannot have a non-parameterized initializer; concrete
        // subclasses get their own initializer. Abstract entities cannot be instantiated
        // by `fromRow` either, so any generated initializer would be unreachable.
        if (classDecl.typeParameters.isNotEmpty() || classDecl.isAbstract()) return true
        // Private/internal entities — and any nested entity whose enclosing class is
        // non-public — cannot be referenced from a public generated class. Skip to avoid
        // emitting uncompilable "public exposes internal type argument" code.
        if (!isPubliclyVisible(classDecl)) return true
        return false
    }

    private fun collectRawInitEntries(classDecl: KSClassDeclaration): List<RawInitPropMeta> {
        val ctorParams =
            classDecl.primaryConstructor?.parameters?.mapNotNull { it.name?.asString() }?.toSet().orEmpty()

        // Collect reactive-backed properties (including those inherited from supertypes
        // whose declaration is `var x by reactiveProperty(...)`). KSP loses delegate
        // information on inherited properties seen via getAllProperties(), so we walk
        // the supertype chain explicitly.
        val reactivePropsByName =
            collectReactivePropertiesIncludingInherited(classDecl).associateBy { it.simpleName.asString() }

        val entries = mutableListOf<RawInitPropMeta>()
        for ((propName, prop) in reactivePropsByName) {
            if (prop.isExcluded()) continue
            entries.add(RawInitPropMeta(propName, isReactive = true, castType = null))
        }
        for (prop in classDecl.getAllProperties()) {
            if (prop.isExcluded()) continue
            val propName = prop.simpleName.asString()
            if (propName in reactivePropsByName) continue
            // Non-reactive `var` scalar property that is NOT a constructor parameter.
            // Constructor params are populated by `fromRow`'s ctor call; non-ctor `var`s
            // (such as `lastDateModified`) need a raw-init entry to be hydrated.
            // Collection-typed backing fields are excluded — junction-row materialization
            // is handled separately by `SqlTableDef.applyJunctionRows`.
            if (prop.isMutable &&
                prop.hasPublicSetter() &&
                propName !in ctorParams &&
                !prop.isDelegated() &&
                !prop.isCollectionTyped()
            ) {
                entries.add(RawInitPropMeta(propName, isReactive = false, castType = renderTypeRef(prop)))
            }
        }
        return entries
    }

    private fun renderTypeRef(prop: KSPropertyDeclaration): String = renderKsType(prop.type.resolve())

    private fun KSPropertyDeclaration.isCollectionTyped(): Boolean {
        val fqn = type.resolve().makeNotNullable().declaration.qualifiedName?.asString() ?: return false
        return fqn in COLLECTION_FQNS
    }

    // A `var x; private set` declaration is mutable from the type's own perspective but cannot be
    // reassigned from the sibling-package generated initializer. Emitting an entry for such a
    // property would produce `entity.x = value as T` that fails Kotlin compile. `protected` and
    // `internal` setters fail the same way from external module boundaries; conservative
    // public-only gating keeps the contract simple.
    private fun KSPropertyDeclaration.hasPublicSetter(): Boolean =
        setter?.modifiers?.none {
            it == Modifier.PRIVATE || it == Modifier.PROTECTED || it == Modifier.INTERNAL
        } ?: true

    /**
     * Returns `true` if [this] property must not receive a `RawInitEntry` in the generated
     * `_LirpRawInitializer` file.
     *
     * Private properties are excluded because the generated initializer lives in a sibling
     * top-level class and has no public setter to bind through; emitting an entry for a
     * `private var` would produce code that fails Kotlin compile with
     * `Cannot access 'var x': it is private`. Bulk-load callers that need to restore private
     * state must promote the property to `internal`/`public`, or accept that the constructor
     * default applies on load — consistent with the existing `@PersistenceIgnore` semantics.
     *
     * `@PersistenceIgnore` and `kotlin.jvm.Transient` are honored as explicit opt-outs.
     */
    private fun KSPropertyDeclaration.isExcluded(): Boolean {
        if (Modifier.PRIVATE in modifiers) return true
        val annotationFqns =
            annotations
                .map { it.annotationType.resolve().declaration.qualifiedName?.asString() }
                .toSet()
        if (PERSISTENCE_IGNORE_FQN in annotationFqns) return true
        if (TRANSIENT_FQN in annotationFqns) return true
        return false
    }

    private fun generateInitializer(classDecl: KSClassDeclaration, properties: List<RawInitPropMeta>) {
        val packageName = classDecl.packageName.asString()
        val jvmName = classDecl.jvmBinaryName()
        val kotlinName = classDecl.kotlinNestedName()
        val initializerName = "${jvmName}_LirpRawInitializer"
        val initializerSourceName = if ('$' in initializerName) "`$initializerName`" else initializerName

        val containingFile =
            classDecl.containingFile ?: run {
                logger.warn("Skipping $kotlinName: no containing file (synthetic class?)")
                return
            }
        val file =
            codeGenerator.createNewFile(
                dependencies = Dependencies(false, containingFile),
                packageName = packageName,
                fileName = initializerName
            )

        val entriesCode =
            properties.joinToString(",\n        ") { meta ->
                buildString {
                    appendLine("RawInitEntry(")
                    appendLine("                name = \"${meta.propName}\",")
                    if (meta.isReactive) {
                        appendLine("                silentSetter = { entity, value ->")
                        appendLine("                    writeReactivePropertyBackingField<Any?>(entity, \"${meta.propName}\", value)")
                        append("                }")
                    } else {
                        appendLine("                silentSetter = { entity, value ->")
                        appendLine("                    entity.${meta.propName} = value as ${meta.castType}")
                        append("                }")
                    }
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
                appendLine("import net.transgressoft.lirp.persistence.LirpRawInitializer")
                appendLine("import net.transgressoft.lirp.persistence.RawInitEntry")
                appendLine("import net.transgressoft.lirp.persistence.writeReactivePropertyBackingField")
                appendLine()
                appendLine("/**")
                appendLine(" * KSP-generated raw initializer for [$kotlinName].")
                appendLine(" * Writes per-row values into entity backing fields without firing reactive events.")
                appendLine(" */")
                appendLine("@Suppress(\"UNCHECKED_CAST\", \"ClassName\")")
                appendLine("public class $initializerSourceName : LirpRawInitializer<$kotlinName> {")
                appendLine("    override val entries: List<RawInitEntry<$kotlinName>> = listOf(")
                appendLine("        $entriesCode")
                appendLine("    )")
                appendLine("}")
            }.toByteArray()
        )
        file.close()

        logger.info("Generated $packageName.$initializerName for $kotlinName")
    }
}

private data class RawInitPropMeta(
    val propName: String,
    val isReactive: Boolean,
    val castType: String?
)