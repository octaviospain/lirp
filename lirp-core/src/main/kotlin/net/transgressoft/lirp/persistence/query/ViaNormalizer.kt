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

package net.transgressoft.lirp.persistence.query

import net.transgressoft.lirp.entity.IdentifiableEntity

/**
 * Pure-function normaliser that folds same-ref `Via*` pairs joined by the boolean
 * operator that preserves their semantics. Three rules apply (D-11, D-12,
 * orchestrator-confirmed third):
 *
 * 1. `Or(ViaAnyMatch(r, p1), ViaAnyMatch(r, p2))` → `ViaAnyMatch(r, Or(p1, p2))`
 * 2. `And(ViaNoneMatch(r, p1), ViaNoneMatch(r, p2))` → `ViaNoneMatch(r, Or(p1, p2))`
 * 3. `And(ViaAllMatch(r, p1), ViaAllMatch(r, p2))` → `ViaAllMatch(r, And(p1, p2))`
 *
 * Mixed quantifiers (e.g. `Or(ViaAnyMatch, ViaAllMatch)`) and pairs with a different
 * `parentProp` or a different `childRegistry` are never folded (D-12, D-13): each
 * quantifier carries different semantics, and structural equality of the ref is the
 * only condition under which the fold preserves meaning.
 *
 * Recursion is post-order so deeply nested fold candidates surface (e.g. an inner
 * same-ref pair under an outer `And` collapses before the outer node is examined).
 *
 * Idempotent: `normalize(normalize(p)) == normalize(p)`.
 */
internal fun <T : IdentifiableEntity<*>> normalize(p: Predicate<T>): Predicate<T> =
    when (p) {
        is Predicate.And<*> -> normalizeAnd(p as Predicate.And<T>)
        is Predicate.Or<*> -> normalizeOr(p as Predicate.Or<T>)
        is Predicate.Not<*> -> normalizeNot(p as Predicate.Not<T>)
        is ViaAnyMatch<*, *, *> -> normalizeViaAnyMatch(p)
        is ViaAllMatch<*, *, *> -> normalizeViaAllMatch(p)
        is ViaNoneMatch<*, *, *> -> normalizeViaNoneMatch(p)
        is ViaWhere<*, *, *> -> normalizeViaWhere(p)
        else -> p
    }

@Suppress("UNCHECKED_CAST")
private fun <T : IdentifiableEntity<*>> normalizeAnd(a: Predicate.And<T>): Predicate<T> {
    val left = normalize(a.left)
    val right = normalize(a.right)
    // Re-normalize fold results: folding builds a fresh child predicate (Or/And on the
    // inner Via children) that itself may contain a foldable same-ref pair after the
    // outer fold reshapes the tree. Each fold strictly reduces the same-ref pair count,
    // so recursion terminates.
    return tryFoldAnd(left, right)?.let { normalize(it) }
        ?: if (left === a.left && right === a.right) a else Predicate.And(left, right)
}

@Suppress("UNCHECKED_CAST")
private fun <T : IdentifiableEntity<*>> normalizeOr(o: Predicate.Or<T>): Predicate<T> {
    val left = normalize(o.left)
    val right = normalize(o.right)
    return tryFoldOr(left, right)?.let { normalize(it) }
        ?: if (left === o.left && right === o.right) o else Predicate.Or(left, right)
}

private fun <T : IdentifiableEntity<*>> normalizeNot(n: Predicate.Not<T>): Predicate<T> {
    val inner = normalize(n.inner)
    return if (inner === n.inner) n else Predicate.Not(inner)
}

@Suppress("UNCHECKED_CAST")
private fun <T : IdentifiableEntity<*>> normalizeViaAnyMatch(p: ViaAnyMatch<*, *, *>): Predicate<T> {
    val v = p as ViaAnyMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>
    val childNorm = normalize(v.childPredicate)
    return if (childNorm === v.childPredicate) p else ViaAnyMatch(v.parentProp, v.childRegistry, childNorm)
}

@Suppress("UNCHECKED_CAST")
private fun <T : IdentifiableEntity<*>> normalizeViaAllMatch(p: ViaAllMatch<*, *, *>): Predicate<T> {
    val v = p as ViaAllMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>
    val childNorm = normalize(v.childPredicate)
    return if (childNorm === v.childPredicate) p else ViaAllMatch(v.parentProp, v.childRegistry, childNorm)
}

@Suppress("UNCHECKED_CAST")
private fun <T : IdentifiableEntity<*>> normalizeViaNoneMatch(p: ViaNoneMatch<*, *, *>): Predicate<T> {
    val v = p as ViaNoneMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>
    val childNorm = normalize(v.childPredicate)
    return if (childNorm === v.childPredicate) p else ViaNoneMatch(v.parentProp, v.childRegistry, childNorm)
}

@Suppress("UNCHECKED_CAST")
private fun <T : IdentifiableEntity<*>> normalizeViaWhere(p: ViaWhere<*, *, *>): Predicate<T> {
    val v = p as ViaWhere<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>
    val childNorm = normalize(v.childPredicate)
    return if (childNorm === v.childPredicate) p else ViaWhere(v.parentProp, v.childRegistry, childNorm)
}

/**
 * Returns the folded predicate when [left] and [right] are a foldable same-ref AND pair,
 * or `null` when no AND fold rule applies. AND rules: `ViaNoneMatch ∧ ViaNoneMatch` →
 * `ViaNoneMatch(p1 ∨ p2)`; `ViaAllMatch ∧ ViaAllMatch` → `ViaAllMatch(p1 ∧ p2)`.
 */
private fun <T : IdentifiableEntity<*>> tryFoldAnd(left: Predicate<T>, right: Predicate<T>): Predicate<T>? {
    if (left is ViaNoneMatch<*, *, *> && right is ViaNoneMatch<*, *, *> && sameNoneRef(left, right)) {
        @Suppress("UNCHECKED_CAST")
        val l = left as ViaNoneMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>

        @Suppress("UNCHECKED_CAST")
        val r = right as ViaNoneMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>
        val combined = Predicate.Or(l.childPredicate, r.childPredicate)
        return ViaNoneMatch(l.parentProp, l.childRegistry, combined)
    }
    if (left is ViaAllMatch<*, *, *> && right is ViaAllMatch<*, *, *> && sameAllRef(left, right)) {
        @Suppress("UNCHECKED_CAST")
        val l = left as ViaAllMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>

        @Suppress("UNCHECKED_CAST")
        val r = right as ViaAllMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>
        val combined = Predicate.And(l.childPredicate, r.childPredicate)
        return ViaAllMatch(l.parentProp, l.childRegistry, combined)
    }
    return null
}

/**
 * Returns the folded predicate when [left] and [right] are a foldable same-ref OR pair,
 * or `null` when no OR fold rule applies. OR rule: `ViaAnyMatch ∨ ViaAnyMatch` →
 * `ViaAnyMatch(p1 ∨ p2)`.
 */
private fun <T : IdentifiableEntity<*>> tryFoldOr(left: Predicate<T>, right: Predicate<T>): Predicate<T>? {
    if (left is ViaAnyMatch<*, *, *> && right is ViaAnyMatch<*, *, *> && sameAnyRef(left, right)) {
        @Suppress("UNCHECKED_CAST")
        val l = left as ViaAnyMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>

        @Suppress("UNCHECKED_CAST")
        val r = right as ViaAnyMatch<T, Comparable<Any>, IdentifiableEntity<Comparable<Any>>>
        val combined = Predicate.Or(l.childPredicate, r.childPredicate)
        return ViaAnyMatch(l.parentProp, l.childRegistry, combined)
    }
    return null
}

private fun sameAnyRef(a: ViaAnyMatch<*, *, *>, b: ViaAnyMatch<*, *, *>): Boolean =
    a.parentProp == b.parentProp && a.childRegistry === b.childRegistry

private fun sameAllRef(a: ViaAllMatch<*, *, *>, b: ViaAllMatch<*, *, *>): Boolean =
    a.parentProp == b.parentProp && a.childRegistry === b.childRegistry

private fun sameNoneRef(a: ViaNoneMatch<*, *, *>, b: ViaNoneMatch<*, *, *>): Boolean =
    a.parentProp == b.parentProp && a.childRegistry === b.childRegistry