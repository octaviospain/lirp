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

import com.google.devtools.ksp.getDeclaredProperties
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Origin

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