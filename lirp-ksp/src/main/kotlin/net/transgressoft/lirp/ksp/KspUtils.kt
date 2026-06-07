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
import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.getVisibility
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSTypeArgument
import com.google.devtools.ksp.symbol.KSTypeParameter
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.symbol.Origin
import com.google.devtools.ksp.symbol.Variance
import com.google.devtools.ksp.symbol.Visibility

internal const val REACTIVE_ENTITY_BASE_FQN = "net.transgressoft.lirp.entity.ReactiveEntityBase"
internal const val IDENTIFIABLE_ENTITY_FQN = "net.transgressoft.lirp.entity.IdentifiableEntity"
internal const val FX_SCALAR_DELEGATE_FQN = "net.transgressoft.lirp.persistence.FxScalarPropertyDelegate"

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

/**
 * Merges annotations from [prop]'s declaration site and, when [ctorParam] is supplied, its
 * value-parameter site. For properties compiled into a dependency jar (`Origin.KOTLIN_LIB`),
 * Kotlin metadata surfaces data-class constructor annotations on the `VALUE_PARAMETER` rather
 * than the synthesized property declaration. Providing the matched [ctorParam] ensures those
 * annotations are visible to every persistence-annotation read.
 *
 * Same-module callers may pass `ctorParam = null`; the result is then identical to
 * `prop.annotations`.
 */
internal fun resolvePersistenceAnnotations(
    prop: KSPropertyDeclaration,
    ctorParam: KSValueParameter? = null
): Sequence<KSAnnotation> =
    prop.annotations + (ctorParam?.annotations ?: emptySequence())

/** Returns `true` if any annotation in this sequence has the given fully-qualified name. */
internal fun Sequence<KSAnnotation>.has(fqn: String): Boolean =
    any { it.annotationType.resolve().declaration.qualifiedName?.asString() == fqn }

/** Returns the first annotation with the given fully-qualified name, or `null`. */
internal fun Sequence<KSAnnotation>.firstWithFqn(fqn: String): KSAnnotation? =
    firstOrNull { it.annotationType.resolve().declaration.qualifiedName?.asString() == fqn }

/**
 * Returns `true` when [prop] has a custom getter written in source.
 *
 * KSP assigns [Origin.KOTLIN] to a getter declared in the current compilation unit's source.
 * Synthesized data-class accessors (same-module) report [Origin.SYNTHETIC]; compiled
 * accessors from a dependency jar report [Origin.KOTLIN_LIB]. The old `!= SYNTHETIC` check
 * misfired on cross-module compiled types, falsely rejecting every nested `@Embedded` imported
 * from another module. Using `== KOTLIN` accepts both synthesized ([Origin.SYNTHETIC]) and
 * cross-module compiled ([Origin.KOTLIN_LIB]) getters as non-custom.
 *
 * Limitation: a hand-written custom getter on a value type compiled in another module also
 * reports [Origin.KOTLIN_LIB] and is therefore indistinguishable from a synthesized accessor.
 * This is a Kotlin metadata ceiling — do not tighten this predicate back to `!= SYNTHETIC`.
 */
internal fun isSourceDeclaredCustomGetter(prop: KSPropertyDeclaration): Boolean =
    prop.getter?.origin == Origin.KOTLIN

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

private fun isTypeByFqn(decl: KSClassDeclaration, fqn: String, visited: MutableSet<String> = mutableSetOf()): Boolean {
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
 * Returns the JVM binary simple name of this class declaration relative to its enclosing package.
 *
 * For top-level classes returns [simpleName]. For inner/nested classes, walks [parentDeclaration]
 * recursively joining with `$` to produce the binary name that [Class.forName] expects
 * (e.g., `Outer$Inner` for one level, `Outer$Middle$Inner` for two levels).
 */
internal fun KSClassDeclaration.jvmBinaryName(): String {
    val parent = parentDeclaration as? KSClassDeclaration
    return if (parent != null) {
        "${parent.jvmBinaryName()}\$${simpleName.asString()}"
    } else {
        simpleName.asString()
    }
}

/**
 * Returns the Kotlin nested class name relative to the enclosing package, using `.` as separator.
 *
 * For top-level classes returns [simpleName]. For inner/nested classes, walks [parentDeclaration]
 * recursively joining with `.` to produce the Kotlin-source-level name used for type references
 * within the same package (e.g., `Outer.Inner` for one level, `Outer.Middle.Inner` for two levels).
 */
internal fun KSClassDeclaration.kotlinNestedName(): String {
    val parent = parentDeclaration as? KSClassDeclaration
    return if (parent != null) {
        "${parent.kotlinNestedName()}.${simpleName.asString()}"
    } else {
        simpleName.asString()
    }
}

/**
 * Returns the fully-qualified call qualifier for a `@PersistenceCreator` reconstruction target.
 *
 * Unlike [kotlinNestedName] (which assumes the generated file shares the entity's package), a
 * generated `_LirpTableDef` for a cross-module entity lives in a different package and does not
 * import the embeddable/entity type. The creator call must therefore be fully qualified — mirroring
 * the constructor path, which already emits the embeddable's FQN. Falls back to [kotlinNestedName]
 * only for the rare local/anonymous declaration with no qualified name.
 */
internal fun KSClassDeclaration.creatorCallQualifier(): String =
    qualifiedName?.asString() ?: kotlinNestedName()

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
 * `var version: Long by reactiveProperty(0L)`, and `@Aggregate` single-ref Id properties — all
 * reactive-backed delegate types.
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
 * Collects every reactive-property-delegated property reachable on [classDecl], including those
 * inherited from supertypes whose original declaration is `var x by reactiveProperty(...)`.
 *
 * KSP's [KSClassDeclaration.getAllProperties] reports inherited properties as plain accessors —
 * `isDelegated()` on the inheriting class's view returns false even when the supertype declared
 * the property with a `by` clause. Walking the supertype chain manually recovers the delegate
 * information from each property's *original* declaration site.
 *
 * For inherited properties whose declared type uses a supertype's generic parameter, the version
 * returned from [KSClassDeclaration.getAllProperties] on [classDecl] is preferred — it carries the
 * substituted (concrete) type for [classDecl]. This keeps generated code referencing concrete
 * type names (e.g. `Int`, `String`) rather than unresolved type-parameter symbols (e.g. `V`).
 *
 * Properties overridden in [classDecl] with the same simple name are deduplicated by name, keeping
 * the subclass's declaration when present — generated code references the subclass's accessor
 * signature, not the base class's.
 */
internal fun collectReactivePropertiesIncludingInherited(classDecl: KSClassDeclaration): List<KSPropertyDeclaration> {
    // Pre-resolve the subclass's view of every property by name so we can substitute concrete
    // type arguments when we later discover the delegate origin on a supertype.
    val subclassViewByName: Map<String, KSPropertyDeclaration> =
        classDecl.getAllProperties().associateBy { it.simpleName.asString() }
    val byName = LinkedHashMap<String, KSPropertyDeclaration>()

    fun addFrom(decl: KSClassDeclaration, visited: MutableSet<String>) {
        val fqn = decl.qualifiedName?.asString() ?: return
        if (!visited.add(fqn)) return
        decl.getDeclaredProperties()
            .filter { isReactivePropertyDelegate(it) }
            .forEach { prop ->
                val name = prop.simpleName.asString()
                // Prefer the subclass's view (which carries substituted type arguments) when one
                // exists; otherwise fall back to the supertype's declaration. This keeps codegen
                // type-correct for generic bases like ReactivePrimitiveWrapper<R, V>.
                val preferred = subclassViewByName[name] ?: prop
                byName.putIfAbsent(name, preferred)
            }
        for (superType in decl.superTypes) {
            val superDecl = superType.resolve().declaration
            if (superDecl is KSClassDeclaration) addFrom(superDecl, visited)
        }
    }
    addFrom(classDecl, mutableSetOf())
    return byName.values.toList()
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