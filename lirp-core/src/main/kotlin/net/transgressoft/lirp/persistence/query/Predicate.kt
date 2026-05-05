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
import kotlin.reflect.KProperty1

/**
 * Sealed class representing a type-safe predicate that can be evaluated against an entity.
 *
 * The predicate AST is immutable and reflection-free: it uses only [KProperty1.get]
 * and [KProperty1.name], both of which resolve via compiler-synthesized property
 * references without the `kotlin-reflect` artifact.
 *
 * Only [Eq] leaf nodes are eligible for index acceleration by [QueryPlanner].
 * Other leaf types ([Gt], [Gte], [Lt], [Lte]) and composite nodes ([And], [Or], [Not])
 * fall back to in-memory filtering or full scans as determined by the planner.
 *
 * @param T the type of entity being evaluated
 */
sealed class Predicate<T : IdentifiableEntity<*>> {

    /**
     * Returns `true` if this predicate matches the given entity.
     */
    abstract fun matches(t: T): Boolean

    /**
     * Equality comparison between a property and a value.
     *
     * This is the only leaf node eligible for index acceleration.
     *
     * @param prop the property reference
     * @param value the value to compare against
     */
    class Eq<T : IdentifiableEntity<*>, V>(val prop: KProperty1<T, V>, val value: V) : Predicate<T>() {
        /** Returns `true` if [prop] of [t] equals [value]. */
        override fun matches(t: T): Boolean = prop.get(t) == value
    }

    /**
     * Greater-than comparison between a comparable property and a value.
     *
     * @param prop the property reference
     * @param value the value to compare against
     */
    class Gt<T : IdentifiableEntity<*>, V : Comparable<V>>(val prop: KProperty1<T, V>, val value: V) : Predicate<T>() {
        /** Returns `true` if [prop] of [t] is strictly greater than [value]. */
        override fun matches(t: T): Boolean = prop.get(t) > value
    }

    /**
     * Greater-than-or-equal comparison between a comparable property and a value.
     *
     * @param prop the property reference
     * @param value the value to compare against
     */
    class Gte<T : IdentifiableEntity<*>, V : Comparable<V>>(val prop: KProperty1<T, V>, val value: V) : Predicate<T>() {
        /** Returns `true` if [prop] of [t] is greater than or equal to [value]. */
        override fun matches(t: T): Boolean = prop.get(t) >= value
    }

    /**
     * Less-than comparison between a comparable property and a value.
     *
     * @param prop the property reference
     * @param value the value to compare against
     */
    class Lt<T : IdentifiableEntity<*>, V : Comparable<V>>(val prop: KProperty1<T, V>, val value: V) : Predicate<T>() {
        /** Returns `true` if [prop] of [t] is strictly less than [value]. */
        override fun matches(t: T): Boolean = prop.get(t) < value
    }

    /**
     * Less-than-or-equal comparison between a comparable property and a value.
     *
     * @param prop the property reference
     * @param value the value to compare against
     */
    class Lte<T : IdentifiableEntity<*>, V : Comparable<V>>(val prop: KProperty1<T, V>, val value: V) : Predicate<T>() {
        /** Returns `true` if [prop] of [t] is less than or equal to [value]. */
        override fun matches(t: T): Boolean = prop.get(t) <= value
    }

    /**
     * Logical AND of two predicates. Both must match for the composite to match.
     *
     * @param left the left predicate
     * @param right the right predicate
     */
    class And<T : IdentifiableEntity<*>>(val left: Predicate<T>, val right: Predicate<T>) : Predicate<T>() {
        /** Returns `true` if both [left] and [right] match [t]. */
        override fun matches(t: T): Boolean = left.matches(t) && right.matches(t)
    }

    /**
     * Logical OR of two predicates. Either must match for the composite to match.
     *
     * @param left the left predicate
     * @param right the right predicate
     */
    class Or<T : IdentifiableEntity<*>>(val left: Predicate<T>, val right: Predicate<T>) : Predicate<T>() {
        /** Returns `true` if either [left] or [right] matches [t]. */
        override fun matches(t: T): Boolean = left.matches(t) || right.matches(t)
    }

    /**
     * Logical NOT of a predicate. Inverts the match result of the inner predicate.
     *
     * @param inner the predicate to negate
     */
    class Not<T : IdentifiableEntity<*>>(val inner: Predicate<T>) : Predicate<T>() {
        /** Returns `true` if [inner] does **not** match [t]. */
        override fun matches(t: T): Boolean = !inner.matches(t)
    }
}