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

import kotlin.reflect.KClass

/**
 * Marks a property whose type is `List<E>` or `Set<E>` as a *value-element collection*: the
 * elements have no independent identity (unlike `@ToOneAggregate` / `@ToManyAggregates` references) and are persisted
 * alongside the parent entity in a single TEXT column carrying a JSON array of converter-encoded
 * element representations. The empty-collection invariant (`[]`) is preserved via the runtime
 * write path, not via a DDL DEFAULT clause (MySQL rejects `DEFAULT` on TEXT columns).
 *
 * **Supported property types:** exactly `kotlin.collections.List<E>` or `kotlin.collections.Set<E>`
 * where `E` is non-nullable. The collection property itself must also be non-nullable. The KSP
 * processor rejects `MutableList<E>`, `MutableSet<E>`, `Map<K, V>`, nullable element types
 * (`Set<E?>`), nullable collection types (`Set<E>?`), and `@ElementCollection` inside an
 * `@Embeddable` class — each with a targeted compile-time error.
 *
 * **Converter contract:** [elementConverter] must reference an `object` singleton implementing
 * `ColumnConverter<E, S>` where `S` is one of the 8 supported Kotlin primitives:
 * `String`, `Int`, `Long`, `Short`, `Byte`, `Boolean`, `Double`, or `Float`. The sentinel
 * [ColumnConverter] base interface is rejected at compile time; an explicit element converter is
 * required. The KSP processor delegates converter validation (object kind + `S` type membership)
 * to the same checks that govern `@PersistenceProperty(converter = …)`.
 *
 * **Column shape:** the KSP processor emits a single TEXT NOT NULL column whose name is
 * auto-derived from the property name in `snake_case` (e.g. `genreList` → `genre_list`). There is
 * no column-name override parameter. The runtime always writes `[]` for empty collections, so the
 * column is never NULL.
 *
 * **Does NOT coexist with `@PersistenceProperty`** on the same property. The two annotations are
 * mutually exclusive: `@ElementCollection` fully owns the persistence shape of the collection
 * column, and `@PersistenceProperty`'s hints (`length`, `precision`, `name`, `converter`) are
 * undefined against a JSON-array column.
 *
 * **Supported declaration forms:**
 * - Primary-constructor `val` or `var` parameter — populated via the entity's primary constructor.
 * - Body-declared `var x: List<E> by reactiveProperty(initial)` — populated via the generated
 *   accessor path that the rest of LIRP's reactive-property machinery already uses for scalars
 *   (`LirpReactivePropertyAccessor` + `LirpRawInitializer`). The element-collection column is
 *   reassigned through the property's setter on row reload.
 *
 * A body-declared read-only `val` is rejected at compile time: the generated `fromRow` must
 * reassign the field after construction, which a `val` cannot support. Declare it as a constructor
 * parameter or a reactive `var` instead.
 *
 * **Column-name derivation:** the auto-derived column name is `propertyName.toSnakeCase()` with no
 * trailing underscore (e.g. `val genres` → column `genres`, `val genreList` → column `genre_list`).
 *
 * **Not allowed inside `@Embeddable`:** `@ElementCollection` on a property inside an `@Embeddable`
 * class is rejected at compile time.
 *
 * Uses [AnnotationRetention.BINARY] retention — the annotation is stored in the class file but not
 * visible to Java runtime reflection scanners. KSP reads annotations directly from source code at
 * compile time, so runtime retention is unnecessary.
 *
 * **Requires the `lirp-ksp` processor** to be applied via the KSP Gradle plugin. Without it,
 * `@ElementCollection` annotations have no effect.
 *
 * Example:
 *
 * ```kotlin
 * value class Genre(val name: String)
 *
 * object GenreConverter : ColumnConverter<Genre, String> {
 *     override val sqlType = ColumnType.TextType
 *     override fun toSql(value: Genre) = value.name
 *     override fun fromSql(raw: String) = Genre(raw)
 * }
 *
 * @PersistenceMapping
 * data class AudioItem(
 *     override val id: Int,
 *     @ElementCollection(elementConverter = GenreConverter::class)
 *     val genres: Set<Genre>
 * ) : ReactiveEntityBase<Int, AudioItem>()
 * ```
 *
 * @param elementConverter Singleton (`object`) converter mapping each collection element `E` to its
 *   persistence-facing scalar `S`. The parameter is mandatory — the sentinel [ColumnConverter] base
 *   interface is a compile-time error. The converter's `S` type determines the JSON element type
 *   (e.g. `String` elements produce `["a","b"]`; `Int` elements produce `[1,2,3]`).
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.BINARY)
annotation class ElementCollection(
    val elementConverter: KClass<out ColumnConverter<*, *>>
)