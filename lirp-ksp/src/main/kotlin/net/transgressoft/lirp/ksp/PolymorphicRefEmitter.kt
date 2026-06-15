/******************************************************************************
 *     Copyright (C) 2026  Octavio Calleya Garcia                             *
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
import com.google.devtools.ksp.symbol.KSFile

/**
 * Generates a per-declaration-site sealed-union type for each `polymorphicAggregate` property.
 *
 * For each property, a file named `{EntitySimpleName}{PropertyName}Arm.kt` is created in the
 * entity's package. The file contains:
 * - A `sealed class {SealedName}` with one `data class` subtype per arm, each carrying an
 *   exactly-typed `entity` property (no star-projection or downcast at call sites).
 * - A `fun PolymorphicResolution<{SealedName}>.activeArm(): {SealedName}` extension that
 *   dispatches on the resolved active label and returns the appropriately-typed subtype.
 *
 * The phantom type parameter on `PolymorphicResolution<{SealedName}>` is load-bearing: it
 * allows two `polymorphicAggregate` properties on the same entity to each own a distinct
 * `activeArm()` extension resolved by receiver type. Without it, both extensions would
 * have the same erased receiver and conflict at the call site.
 */
internal object PolymorphicRefEmitter {

    /**
     * Emits a sealed-union file for a single `polymorphicAggregate` property.
     *
     * @param packageName the declaring entity's package (may be empty for the default package)
     * @param entitySimpleName the simple name of the declaring entity class
     * @param propertyName the name of the `polymorphicAggregate` property
     * @param arms the resolved arm metadata list — each entry carries the arm label and the
     *   fully-qualified name of the arm's target entity type
     * @param codeGenerator the KSP code generator
     * @param sourceFile the containing file of the declaring entity, used to track source
     *   dependencies so KSP re-generates the sealed type when the entity changes
     * @param logger KSP logger for info-level generation diagnostics
     */
    fun emitSealedUnion(
        packageName: String,
        entitySimpleName: String,
        propertyName: String,
        arms: List<ArmTextMeta>,
        codeGenerator: CodeGenerator,
        sourceFile: KSFile,
        logger: KSPLogger
    ) {
        val sealedName = "$entitySimpleName${propertyName.replaceFirstChar { it.uppercase() }}Arm"
        val file =
            codeGenerator.createNewFile(
                dependencies = Dependencies(false, sourceFile),
                packageName = packageName,
                fileName = sealedName
            )
        file.write(
            buildString {
                if (packageName.isNotEmpty()) {
                    appendLine("package $packageName")
                    appendLine()
                }
                appendLine("import net.transgressoft.lirp.persistence.PolymorphicResolution")
                // Emit sorted, distinct imports for each arm entity FQN that contains a package separator.
                arms.map { it.entityFqn }
                    .filter { it.contains('.') }
                    .distinct()
                    .sorted()
                    .forEach { appendLine("import $it") }
                appendLine()
                appendLine("sealed class $sealedName {")
                for (arm in arms) {
                    val subtypeName = arm.label.replaceFirstChar { it.uppercase() }
                    val entitySimple = arm.entityFqn.substringAfterLast('.')
                    appendLine("    data class $subtypeName(val entity: $entitySimple) : $sealedName()")
                }
                appendLine("}")
                appendLine()
                // The phantom type parameter on PolymorphicResolution<SealedName> is load-bearing:
                // it lets multiple polymorphic properties on the same entity each own a distinct
                // activeArm() extension resolved by receiver type at the call site.
                // Resolve label and entity together via a single resolveActive() scan so dispatch
                // and resolution are atomic against one arm snapshot — a separate label scan plus
                // resolveArm() could otherwise observe a different active arm under a concurrent
                // mutation and fail the cast.
                appendLine("fun PolymorphicResolution<$sealedName>.activeArm(): $sealedName {")
                appendLine("    val (label, entity) = this.resolveActive()")
                appendLine("    return when (label) {")
                for (arm in arms) {
                    val subtypeName = arm.label.replaceFirstChar { it.uppercase() }
                    val entitySimple = arm.entityFqn.substringAfterLast('.')
                    appendLine("""        "${arm.label}" -> $sealedName.$subtypeName(entity as $entitySimple)""")
                }
                appendLine("""        else -> error("Unknown polymorphic arm: ${'$'}label")""")
                appendLine("    }")
                appendLine("}")
            }.toByteArray()
        )
        file.close()
        logger.info("Generated $packageName.$sealedName for $entitySimpleName.$propertyName")
    }
}