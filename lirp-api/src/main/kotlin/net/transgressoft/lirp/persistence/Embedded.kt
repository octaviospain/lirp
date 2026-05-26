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

package net.transgressoft.lirp.persistence

/**
 * Marks a constructor `val` parameter whose type is an [Embeddable] value object as the embedding
 * point on a parent entity (a `@PersistenceMapping` class) or on another [Embeddable] (for
 * recursive nesting). The KSP processor flattens the embeddable's scalar fields into columns of
 * the parent table, prefixing each column name with [prefix].
 *
 * **Target constraint:** `@Embedded` must be applied to a primary-constructor `val` parameter. The
 * KSP processor rejects (with a compile-time error) `@Embedded` on `var` constructor parameters,
 * on properties declared in the class body, and on properties with a custom getter — the
 * embeddable is reconstructed via a nested constructor expression in the generated `fromRow` and
 * cannot be reassigned by `applyRow`.
 *
 * **Prefix semantics:**
 * - When [prefix] is left at its default empty string, the KSP processor auto-derives the prefix
 *   as the property name converted to snake_case followed by `_` (for example a property
 *   `albumArtist` becomes the prefix `album_artist_`).
 * - When [prefix] is set to an explicit non-empty value, that value is appended verbatim to every
 *   flattened column name without further validation — the processor does not enforce a particular
 *   identifier shape (no snake_case check, no trailing-`_` requirement).
 * - In recursive nesting, prefixes concatenate top-down: `entity.album.performer.name` with parent
 *   prefix `album_` and child prefix `performer_` produces the column name `album_performer_name`.
 *
 * **Collisions are hard errors.** If two flattened column names resolve to the same string anywhere
 * in the fully flattened column list of a `@PersistenceMapping` entity (including all recursive
 * descents), the KSP processor reports a compile-time error naming both colliding property paths.
 *
 * Uses [AnnotationRetention.BINARY] retention — the annotation is stored in the class file but is
 * not visible to Java runtime reflection scanners. KSP reads annotations directly from source code
 * at compile time, so runtime retention is unnecessary.
 *
 * **Requires the `lirp-ksp` processor** to be applied via the KSP Gradle plugin. Without it,
 * `@Embedded` annotations have no effect.
 *
 * Example:
 *
 * ```kotlin
 * @Embeddable
 * data class Artist(val name: String, val countryCode: String)
 *
 * @PersistenceMapping
 * data class AudioItem(
 *     override val id: Int,
 *     @Embedded val artist: Artist,                          // → artist_name, artist_country_code
 *     @Embedded(prefix = "perf_") val performer: Artist      // → perf_name, perf_country_code
 * ) : ReactiveEntityBase<Int, AudioItem>()
 * ```
 *
 * @param prefix Column-name prefix appended to every flattened scalar field of the referenced
 *   [Embeddable]. The default empty string is a sentinel meaning "auto-derive the prefix from the
 *   property name in snake_case followed by `_`". A non-empty value is appended verbatim with no
 *   shape validation. In recursive `@Embedded` chains, prefixes from outer to inner concatenate
 *   in declaration order to produce each leaf column's final name.
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
annotation class Embedded(val prefix: String = "")