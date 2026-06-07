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

import com.google.devtools.ksp.getConstructors
import com.google.devtools.ksp.getDeclaredFunctions
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Visibility

/**
 * Outcome of [resolveCreator] for a single class declaration.
 *
 * Callers pattern-match on the sealed subtypes to drive codegen:
 * - [Found] — exactly one `@PersistenceCreator`-annotated target was located; the caller should
 *   use [Found.callExpression] as the reconstruction call target in the generated `fromRow`.
 * - [None] — no `@PersistenceCreator` annotation is present; the caller falls back to the
 *   primary constructor and may emit a [com.google.devtools.ksp.processing.KSPLogger.warn] when
 *   the primary constructor is non-public.
 * - [Ambiguous] — more than one `@PersistenceCreator`-annotated target was found on the same
 *   type. This is a configuration bug: reconstruction is undefined. Callers must emit a hard
 *   `logger.error` naming every declaration in [Ambiguous.conflicting] (so the developer can
 *   locate all offenders) and abort code generation for the affected type.
 */
internal sealed interface CreatorResolution {

    /**
     * A single `@PersistenceCreator`-annotated companion-object function or secondary constructor
     * was found.
     *
     * @param callExpression Fully-qualified Kotlin source expression used as the call target in the
     *   generated `fromRow` (e.g. `com.acme.Artist.of` for a companion function, or `com.acme.Artist`
     *   for a secondary constructor). Derived from [KSClassDeclaration.creatorCallQualifier] so the
     *   generated `_LirpTableDef` — which lives in a different package and does not import the type —
     *   resolves the reference, mirroring the FQN-qualified constructor path.
     * @param params The value parameters of the annotated function or constructor, in declaration
     *   order. Callers match these by name against the existing [CtorSlot] tree and use each
     *   parameter's default-value presence to decide whether an unmatched parameter may be omitted.
     */
    data class Found(val callExpression: String, val params: List<KSValueParameter>) : CreatorResolution

    /**
     * No `@PersistenceCreator` annotation was found on any companion-object function or secondary
     * constructor. The primary constructor is the only available reconstruction seam.
     */
    data object None : CreatorResolution

    /**
     * More than one `@PersistenceCreator`-annotated target was found on the same type. This is a
     * configuration bug — the reconstruction seam is ambiguous and codegen must be aborted.
     *
     * [conflicting] carries every annotated declaration so the caller can name all offenders in
     * the `logger.error` message. Reporting only one would leave the developer hunting for the
     * second. The caller is responsible for rendering each offender (e.g. via its `location`) in
     * the emitted diagnostic.
     *
     * @param conflicting all `@PersistenceCreator`-annotated declarations found on the type
     */
    data class Ambiguous(val conflicting: List<KSAnnotated>) : CreatorResolution
}

/**
 * Resolves the `@PersistenceCreator`-annotated reconstruction target for [classDecl].
 *
 * Scans two locations in declaration order:
 * 1. The companion object's declared functions filtered by `@PersistenceCreator`.
 * 2. The class's secondary constructors filtered by `@PersistenceCreator` (the primary constructor
 *    is excluded by identity to avoid treating a primary-ctor annotation as a creator target).
 *
 * Returns [CreatorResolution.Ambiguous] (carrying all offenders) when more than one annotated
 * target is found — callers must turn this into a `logger.error` naming every offender.
 * Returns [CreatorResolution.Found] when exactly one target is found, with a
 * [KSClassDeclaration.creatorCallQualifier]-based call expression that resolves from the generated
 * file regardless of its package. Returns [CreatorResolution.None] when no annotated target exists.
 *
 * @param classDecl the entity or `@Embeddable` class to inspect
 * @return the resolution outcome; never `null`
 */
internal fun resolveCreator(classDecl: KSClassDeclaration): CreatorResolution {
    val companion =
        classDecl.declarations
            .filterIsInstance<KSClassDeclaration>()
            .firstOrNull { it.isCompanionObject }

    val annotatedFns: List<KSAnnotated> =
        companion
            ?.getDeclaredFunctions()
            ?.filter { fn -> fn.annotations.has(PERSISTENCE_CREATOR_FQN) }
            ?.toList()
            ?: emptyList()

    val annotatedCtors: List<KSAnnotated> =
        classDecl.getConstructors()
            .filter { it != classDecl.primaryConstructor }
            .filter { ctor -> ctor.annotations.has(PERSISTENCE_CREATOR_FQN) }
            .toList()

    val annotated: List<KSAnnotated> = annotatedFns + annotatedCtors
    return when {
        annotated.size > 1 -> CreatorResolution.Ambiguous(annotated)
        annotated.size == 1 && annotatedFns.isNotEmpty() -> {
            val fn = annotatedFns.first() as KSFunctionDeclaration
            CreatorResolution.Found(
                callExpression = "${classDecl.creatorCallQualifier()}.${fn.simpleName.asString()}",
                params = fn.parameters
            )
        }
        annotated.size == 1 -> {
            val ctor = annotatedCtors.first() as KSFunctionDeclaration
            CreatorResolution.Found(
                callExpression = classDecl.creatorCallQualifier(),
                params = ctor.parameters
            )
        }
        else -> CreatorResolution.None
    }
}

/**
 * Renders the conflicting `@PersistenceCreator` declarations for the ambiguity diagnostic, naming
 * each function by simple name and source location so every offender is locatable.
 */
internal fun List<KSAnnotated>.formatCreatorOffenders(): String =
    joinToString(", ") { offender ->
        (offender as? KSFunctionDeclaration)?.let { "${it.simpleName.asString()} at ${it.location}" }
            ?: offender.location.toString()
    }

/**
 * True when [this] is `internal` and its resolved `@PersistenceCreator` — a companion-object
 * function or a secondary constructor — is itself not publicly accessible, so the generated
 * descriptor would fail to compile outside the declaring module. Resolves the annotated declaration
 * directly (the same way [resolveCreator] does), so it covers both creator kinds. Must be called
 * only after ambiguity has been ruled out, since at most one annotated target is expected.
 */
internal fun KSClassDeclaration.hasInternalNonPublicCreator(): Boolean {
    if (getVisibility() != Visibility.INTERNAL) return false
    val companion = declarations.filterIsInstance<KSClassDeclaration>().firstOrNull { it.isCompanionObject }
    val annotatedFn = companion?.getDeclaredFunctions()?.firstOrNull { it.annotations.has(PERSISTENCE_CREATOR_FQN) }
    val annotatedCtor = getConstructors().firstOrNull { it != primaryConstructor && it.annotations.has(PERSISTENCE_CREATOR_FQN) }
    val creatorVis = (annotatedFn ?: annotatedCtor)?.getVisibility() ?: return false
    return creatorVis != Visibility.PUBLIC && creatorVis != Visibility.JAVA_PACKAGE
}

/**
 * True when the primary constructor is not publicly accessible. With no `@PersistenceCreator` seam,
 * a generated descriptor that calls this constructor compiles only inside the declaring module.
 */
internal fun KSClassDeclaration.hasNonPublicPrimaryConstructor(): Boolean {
    val vis = primaryConstructor?.getVisibility() ?: return false
    return vis != Visibility.PUBLIC && vis != Visibility.JAVA_PACKAGE
}