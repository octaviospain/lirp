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
 * Marks a class as an *embeddable value object* — a structured aggregate that owns no identity of
 * its own and is persisted by flattening its scalar fields into columns of its containing entity's
 * table.
 *
 * `@Embeddable` classes must be **concrete `data class`es** (no interfaces, no polymorphism, no
 * `abstract`/`sealed`/`object` declarations) so the KSP processor can read the primary constructor
 * parameter list and round-trip instances through `fromRow` / `toParams`. Use [Embedded] on a
 * property of a `@PersistenceMapping` entity — or on a property of another `@Embeddable` for
 * recursive nesting — to actually persist an instance.
 *
 * Uses [AnnotationRetention.BINARY] retention — the annotation is stored in the class file but is
 * not visible to Java runtime reflection scanners. KSP reads annotations directly from source code
 * at compile time, so runtime retention is unnecessary.
 *
 * **Requires the `lirp-ksp` processor** to be applied via the KSP Gradle plugin. Without it,
 * `@Embeddable` and [Embedded] annotations have no effect.
 *
 * Example:
 *
 * ```kotlin
 * @Embeddable
 * data class Artist(val name: String, val countryCode: String)
 *
 * @Embeddable
 * data class Album(
 *     val title: String,
 *     @Embedded(prefix = "performer_") val performer: Artist,
 *     val year: Short?
 * )
 *
 * @PersistenceMapping
 * data class AudioItem(
 *     override val id: Int,
 *     @Embedded val artist: Artist,    // → artist_name, artist_country_code
 *     @Embedded val album: Album       // → album_title, album_performer_name, album_performer_country_code, album_year
 * ) : ReactiveEntityBase<Int, AudioItem>()
 * ```
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
annotation class Embeddable