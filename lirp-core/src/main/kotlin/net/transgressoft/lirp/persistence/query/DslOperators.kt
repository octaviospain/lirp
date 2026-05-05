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
 * Creates an equality predicate: `property == value`.
 *
 * @param value the value to compare against
 * @return a [Predicate.Eq] leaf node
 */
infix fun <T : IdentifiableEntity<*>, V> KProperty1<T, V>.eq(value: V): Predicate<T> =
    Predicate.Eq(this, value)

/**
 * Creates a greater-than predicate: `property > value`.
 *
 * @param value the value to compare against
 * @return a [Predicate.Gt] leaf node
 */
infix fun <T : IdentifiableEntity<*>, V : Comparable<V>> KProperty1<T, V>.gt(value: V): Predicate<T> =
    Predicate.Gt(this, value)

/**
 * Creates a greater-than-or-equal predicate: `property >= value`.
 *
 * @param value the value to compare against
 * @return a [Predicate.Gte] leaf node
 */
infix fun <T : IdentifiableEntity<*>, V : Comparable<V>> KProperty1<T, V>.gte(value: V): Predicate<T> =
    Predicate.Gte(this, value)

/**
 * Creates a less-than predicate: `property < value`.
 *
 * @param value the value to compare against
 * @return a [Predicate.Lt] leaf node
 */
infix fun <T : IdentifiableEntity<*>, V : Comparable<V>> KProperty1<T, V>.lt(value: V): Predicate<T> =
    Predicate.Lt(this, value)

/**
 * Creates a less-than-or-equal predicate: `property <= value`.
 *
 * @param value the value to compare against
 * @return a [Predicate.Lte] leaf node
 */
infix fun <T : IdentifiableEntity<*>, V : Comparable<V>> KProperty1<T, V>.lte(value: V): Predicate<T> =
    Predicate.Lte(this, value)

/**
 * Combines two predicates with logical AND.
 *
 * @param other the right-hand predicate
 * @return a [Predicate.And] composite node
 */
infix fun <T : IdentifiableEntity<*>> Predicate<T>.and(other: Predicate<T>): Predicate<T> =
    Predicate.And(this, other)

/**
 * Combines two predicates with logical OR.
 *
 * @param other the right-hand predicate
 * @return a [Predicate.Or] composite node
 */
infix fun <T : IdentifiableEntity<*>> Predicate<T>.or(other: Predicate<T>): Predicate<T> =
    Predicate.Or(this, other)

/**
 * Negates a predicate with logical NOT.
 *
 * @return a [Predicate.Not] composite node
 */
operator fun <T : IdentifiableEntity<*>> Predicate<T>.not(): Predicate<T> =
    Predicate.Not(this)

/**
 * Top-level builder for constructing a [Query] via the Kotlin DSL.
 *
 * Example:
 * ```kotlin
 * val query = query<Product> {
 *     where { category eq "electronics" }
 *     orderBy(price, Direction.DESC)
 *     limit(10)
 * }
 * ```
 *
 * @param block the builder configuration block
 * @return the configured [Query]
 */
fun <T : IdentifiableEntity<*>> query(block: QueryBuilder<T>.() -> Unit): Query<T> =
    QueryBuilder<T>().apply(block).build()