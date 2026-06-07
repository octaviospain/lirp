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

import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.Variance

/**
 * Renders a [KSType] back to a Kotlin source representation that preserves its generic type
 * arguments and nullability, e.g. `kotlin.collections.Map<kotlin.String, kotlin.Long?>`.
 *
 * Used by generated code where the rendered string is interpolated into an `as` cast. Dropping
 * type arguments here would produce an unchecked raw-type cast and lose the static guarantees the
 * declared property type already gave us.
 *
 * When the type's declaration has no FQN (type-parameter references like `V` or `T`), falls back
 * to `kotlin.Any?` — the generated cast then matches the erased upper bound, which is the most
 * specific thing the compiler can verify for an unconstrained type parameter.
 */
internal fun renderKsType(type: KSType): String {
    val baseName = type.declaration.qualifiedName?.asString() ?: return "kotlin.Any?"
    val args = type.arguments
    val rendered =
        if (args.isEmpty()) {
            baseName
        } else {
            val renderedArgs = args.joinToString(", ") { arg -> renderKsTypeArgument(arg) }
            "$baseName<$renderedArgs>"
        }
    return if (type.isMarkedNullable) "$rendered?" else rendered
}

/**
 * Renders a single [KSTypeArgument] preserving its use-site variance — `out X` for `COVARIANT`,
 * `in X` for `CONTRAVARIANT`, `*` for `STAR` projections or unresolved argument types. Without
 * this, projected property types like `Box<out Foo>` would collapse to `Box<Foo>` in generated
 * casts and serializer type arguments, silently widening the type used at the generation site.
 */
internal fun renderKsTypeArgument(arg: KSTypeArgument): String {
    if (arg.variance == Variance.STAR) return "*"
    val argType = arg.type?.resolve() ?: return "*"
    val rendered = renderKsType(argType)
    return when (arg.variance) {
        Variance.COVARIANT -> "out $rendered"
        Variance.CONTRAVARIANT -> "in $rendered"
        else -> rendered
    }
}

/**
 * Resolves the reactive self-type `R` from [classDecl]'s `ReactiveEntity<K, R>` or
 * `ReactiveEntityBase<K, R>` supertype by walking the supertype graph transitively, carrying
 * type-argument substitutions at each level.
 *
 * Returns the fully-qualified name of `R` when it can be resolved to a concrete declaration, or
 * `null` when the `ReactiveEntity`/`ReactiveEntityBase` supertype is absent or when `R` resolves
 * to an unsubstituted type parameter. A visited-set guards against cyclic supertype references,
 * mirroring [isTypeByFqn].
 *
 * The substitution map carries each supertype declaration's type-parameter names mapped to the
 * concrete type-arguments written at the use site. When descending from `ConcreteEntity`
 * into `IntermediateBase<K, R>` into `ReactiveEntityBase<K, R>`, the `R` argument written on the
 * outermost extends-clause is preserved through the chain so the final match on
 * `ReactiveEntityBase` yields the concrete reactive interface rather than the abstract `R`.
 */
internal fun resolveReactiveSelfType(classDecl: KSClassDeclaration): String? =
    resolveReactiveSelfTypeInternal(classDecl, emptyMap(), mutableSetOf())

private fun resolveReactiveSelfTypeInternal(
    classDecl: KSClassDeclaration,
    substitutions: Map<String, KSType>,
    visited: MutableSet<String>
): String? {
    val declFqn = classDecl.qualifiedName?.asString() ?: return null
    if (!visited.add(declFqn)) return null

    for (superRef in classDecl.superTypes) {
        val resolved = superRef.resolve()
        val superDecl = resolved.declaration as? KSClassDeclaration ?: continue
        val superFqn = superDecl.qualifiedName?.asString() ?: continue

        val nextSubstitutions = buildSupertypeSubstitutions(resolved, superDecl, substitutions)

        if (superFqn == REACTIVE_ENTITY_FQN || superFqn == REACTIVE_ENTITY_BASE_FQN) {
            return resolveSelfTypeArgument(resolved, nextSubstitutions, substitutions)
        }

        val result = resolveReactiveSelfTypeInternal(superDecl, nextSubstitutions, visited)
        if (result != null) return result
    }
    return null
}

/**
 * Maps each of [superDecl]'s type-parameter names to the concrete argument written at the use
 * site in [resolved], resolving any argument that is itself a type parameter through the inherited
 * [substitutions] so a `R` threaded across `IntermediateBase<K, R>` keeps its concrete binding.
 */
private fun buildSupertypeSubstitutions(
    resolved: KSType,
    superDecl: KSClassDeclaration,
    substitutions: Map<String, KSType>
): Map<String, KSType> =
    superDecl.typeParameters.mapIndexedNotNull { i, param ->
        val argType = resolved.arguments.getOrNull(i)?.type?.resolve() ?: return@mapIndexedNotNull null
        param.name.asString() to substituteTypeParameter(argType, substitutions)
    }.toMap()

/**
 * Reads `R` (type argument at index 1 of `ReactiveEntity<K, R>` / `ReactiveEntityBase<K, R>`) from
 * [resolved] and renders it against the substitution context. Returns the fully-qualified,
 * fully-rendered type — including any of `R`'s own type arguments, so a parameterized self-type
 * like `AudioItem<String>` yields `…AudioItem<kotlin.String>` rather than a raw `…AudioItem` — or
 * `null` when `R` (or any nested argument) remains an unsubstituted type parameter.
 */
private fun resolveSelfTypeArgument(
    resolved: KSType,
    nextSubstitutions: Map<String, KSType>,
    substitutions: Map<String, KSType>
): String? {
    val rArg = resolved.arguments.getOrNull(1)?.type?.resolve() ?: return null
    // `nextSubstitutions` (this level's bindings) takes precedence over the inherited context.
    return renderResolvedType(rArg, substitutions + nextSubstitutions)
}

/**
 * Renders [type] to a fully-qualified Kotlin type string after substitution, recursing into its
 * type arguments. Returns `null` when [type] or any nested argument stays a free type parameter
 * (which would otherwise produce an uncompilable raw or partially-substituted descriptor type).
 */
private fun renderResolvedType(type: KSType, substitutions: Map<String, KSType>): String? {
    val resolved = substituteTypeParameter(type, substitutions)
    if (resolved.declaration is KSTypeParameter) return null
    val fqn = resolved.declaration.qualifiedName?.asString() ?: return null
    if (resolved.arguments.isEmpty()) return fqn
    val renderedArgs =
        resolved.arguments.map { arg ->
            val argType = arg.type?.resolve() ?: return null
            renderResolvedType(argType, substitutions) ?: return null
        }
    return "$fqn<${renderedArgs.joinToString(", ")}>"
}

/**
 * Resolves [type] through [substitutions] when it is a type parameter (e.g. `R`), returning the
 * bound concrete type; otherwise returns [type] unchanged. A type parameter with no binding is
 * returned as-is so callers can detect that it stayed unresolved.
 */
private fun substituteTypeParameter(type: KSType, substitutions: Map<String, KSType>): KSType =
    if (type.declaration is KSTypeParameter) {
        substitutions[type.declaration.simpleName.asString()] ?: type
    } else {
        type
    }