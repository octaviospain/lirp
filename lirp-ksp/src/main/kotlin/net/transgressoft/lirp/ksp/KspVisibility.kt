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

import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Visibility

/**
 * Computes the effective Kotlin visibility modifier string for a generated companion declaration
 * tied to [decl], walking every enclosing class declaration to apply the most-restrictive rule.
 *
 * A sibling-package generated companion cannot widen the entity's visibility — a `public class
 * Foo_LirpXAccessor : LirpXAccessor<InternalFoo>` would fail compilation with "public exposes
 * internal type". This helper resolves that by returning `"internal"` whenever any node in the
 * enclosing-declaration chain is `INTERNAL`, and `"public"` only when every node is
 * `PUBLIC`/`JAVA_PACKAGE`.
 *
 * `PRIVATE` or `PROTECTED` anywhere in the chain means no accessible companion can be generated.
 * Returns `null` as a do-not-emit sentinel without emitting a diagnostic — callers that want a
 * hard KSP error for explicitly-annotated entities may call [logger.error] on the returned null.
 *
 * @param decl the entity class declaration whose generated companion needs a visibility modifier
 * @return `"public"` or `"internal"`, or `null` when the entity must not be generated
 */
internal fun effectiveVisibilityModifier(decl: KSClassDeclaration): String? {
    var mostRestrictive = "public"
    var current: KSClassDeclaration? = decl
    while (current != null) {
        when (current.getVisibility()) {
            Visibility.PUBLIC, Visibility.JAVA_PACKAGE -> { /* no downgrade */ }
            Visibility.INTERNAL -> mostRestrictive = "internal"
            Visibility.PRIVATE, Visibility.PROTECTED -> return null
            else -> return null // LOCAL or unknown — non-generatable; caller skips or fails
        }
        current = current.parentDeclaration as? KSClassDeclaration
    }
    return mostRestrictive
}

/**
 * Returns `true` when [this] property must be excluded from generated accessor entries because it
 * is `private`.
 *
 * Generated accessor companions live in a sibling top-level file within the same package. A
 * `private` property is inaccessible from outside the declaring class — emitting an accessor entry
 * for it would produce code that fails Kotlin compile with `Cannot access 'var x': it is private`.
 * Callers that need to persist private state must promote the property to `internal` or `public`.
 *
 * This predicate is shared by [RawInitializerProcessor] and [ReactivePropertyAccessorProcessor]
 * so both generators apply the identical exclusion rule from one authoritative site.
 */
internal fun KSPropertyDeclaration.isPrivateForGeneratedAccess(): Boolean =
    Modifier.PRIVATE in modifiers