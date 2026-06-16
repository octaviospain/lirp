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

import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias

internal const val REACTIVE_ENTITY_BASE_FQN = "net.transgressoft.lirp.entity.ReactiveEntityBase"
internal const val IDENTIFIABLE_ENTITY_FQN = "net.transgressoft.lirp.entity.IdentifiableEntity"
internal const val FX_SCALAR_DELEGATE_FQN = "net.transgressoft.lirp.persistence.FxScalarPropertyDelegate"

/**
 * Returns true if [decl] extends `ReactiveEntityBase` or implements `IdentifiableEntity` anywhere
 * up its supertype chain.
 *
 * Walks the supertype graph via FQN comparison rather than referencing the runtime types, since
 * the entity types live in `lirp-core` which is not a compile-time dependency of `lirp-ksp`.
 * A visited-set guards against cyclic supertype references.
 */
internal fun isLirpEntity(decl: KSClassDeclaration): Boolean =
    isTypeByFqn(decl, REACTIVE_ENTITY_BASE_FQN) || isTypeByFqn(decl, IDENTIFIABLE_ENTITY_FQN)

/**
 * Returns true when [decl] extends `ReactiveEntityBase`, meaning generated hydration code may call
 * `withEventsDisabled { }` on instances. Plain `@PersistenceMapping` classes that do not extend it
 * (e.g. minimal test fixtures) lack that method, so generated code must not reference it for them.
 */
internal fun extendsReactiveEntityBase(decl: KSClassDeclaration): Boolean =
    isTypeByFqn(decl, REACTIVE_ENTITY_BASE_FQN)

/**
 * Returns true for anonymous objects and local classes that cannot have meaningful KSP-generated
 * accessors. Anonymous objects expose an empty [simpleName]; local classes lack a [qualifiedName].
 */
internal fun isAnonymousOrLocal(decl: KSClassDeclaration): Boolean =
    (decl.classKind == ClassKind.OBJECT && decl.simpleName.asString().isEmpty()) ||
        decl.qualifiedName == null

/**
 * Yields [decl] followed by every nested class declaration reachable from it, recursively.
 *
 * KSP processors call this to discover entity types declared inside outer classes (e.g. companion
 * objects, nested data classes used as test fixtures) without requiring the consumer to annotate
 * each nested type.
 */
internal fun allClassDeclarations(decl: KSClassDeclaration): Sequence<KSClassDeclaration> =
    sequence {
        yield(decl)
        decl.declarations.filterIsInstance<KSClassDeclaration>().forEach {
            yieldAll(allClassDeclarations(it))
        }
    }

internal fun isTypeByFqn(decl: KSClassDeclaration, fqn: String, visited: MutableSet<String> = mutableSetOf()): Boolean {
    val declFqn = decl.qualifiedName?.asString() ?: return false
    if (!visited.add(declFqn)) return false
    if (declFqn == fqn) return true
    for (superType in decl.superTypes) {
        val declaration = superType.resolve().declaration
        if (declaration is KSClassDeclaration && isTypeByFqn(declaration, fqn, visited)) return true
    }
    return false
}

/**
 * Returns true when [type]'s declaration (or any supertype reachable via FQN walk) implements
 * `FxScalarPropertyDelegate`. Handles type aliases by unwrapping to their aliased type, and guards
 * cycles with a visited-set.
 *
 * Shared between [FxScalarAccessorProcessor] and [LirpAccessorValidationProcessor] so both agree
 * on what counts as an FxScalar-typed property.
 */
internal fun isFxScalarType(type: KSType, visited: MutableSet<String> = mutableSetOf()): Boolean {
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

/**
 * Returns true when [prop] is a `var ... by reactiveProperty(...)` delegate on an entity class.
 *
 * KSP does not expose the delegate-expression type directly — `prop.type.resolve()` returns the
 * declared value type, not the delegate. Detection therefore uses a composite predicate:
 *
 * - `isDelegated()` rules out plain stored properties and constructor params.
 * - `isMutable` rules out `val`-declared aggregations and FxScalar `val x: StringProperty by ...`.
 * - The value type must not be an `FxScalarPropertyDelegate` subtype (excludes FxScalar paths).
 * - The value type must not be a `kotlin.collections.*` collection (excludes `aggregateList`
 *   and `aggregateSet` delegates whose declared types are `List`/`Set`).
 *
 * The remaining set covers ordinary `reactiveProperty(initialValue)`, `@Version`-annotated
 * `var version: Long by reactiveProperty(0L)`, and `@ToOneAggregate` single-ref Id properties — all
 * reactive-backed delegate types — the last bucket also covers `@ToOneAggregate` single-ref Id properties.
 */
internal fun isReactivePropertyDelegate(prop: KSPropertyDeclaration): Boolean {
    if (!prop.isDelegated() || !prop.isMutable) return false
    val resolvedType = prop.type.resolve()
    if (isFxScalarType(resolvedType)) return false
    // Type-parameter properties (e.g. `var value: V by reactiveProperty(...)` on a generic base)
    // have a null qualifiedName because `V` has no FQN. They are still reactive — concrete
    // subclasses inherit them as reactive-backed fields and need accessor/raw-init entries.
    val typeFqn = resolvedType.declaration.qualifiedName?.asString()
    if (typeFqn != null && typeFqn in LIRP_COLLECTION_FQNS) return false
    return true
}