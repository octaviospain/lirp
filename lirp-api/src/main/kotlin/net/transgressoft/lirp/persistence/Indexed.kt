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

package net.transgressoft.lirp.persistence

/**
 * Marks a property for secondary index maintenance in repositories that extend `RegistryBase`.
 *
 * At compile time, the LIRP KSP processor scans for `@Indexed` annotations and generates a
 * [LirpIndexAccessor] implementation per entity class. The generated accessor contains direct
 * property getter lambdas — no runtime reflection is involved.
 *
 * At runtime, when an entity is first added to a repository, the generated accessor is loaded via
 * a convention-based class lookup (`{EntityClassName}_LirpIndexAccessor`) and its [IndexEntry]
 * descriptors populate the secondary index structure. Subsequent calls to [Registry.findByIndex]
 * or [Registry.findFirstByIndex] resolve in O(1) without scanning the collection.
 *
 * Uses [AnnotationRetention.BINARY] retention — the annotation is stored in the class file but is
 * not visible to Java runtime reflection scanners. KSP reads annotations directly from source code
 * at compile time, so runtime retention is unnecessary.
 *
 * The [name] parameter controls the index key used in [Registry.findByIndex]. If left empty, the
 * Kotlin property name is used automatically.
 *
 * Null property values are silently skipped — entities with a null value for an indexed property
 * are simply not included in that index.
 *
 * When `sorted = true` the property is added to a `NavigableMap`-backed bucket structure and the
 * property type MUST implement `Comparable`; the constraint is enforced at KSP compile time. Range
 * queries (`gt`/`gte`/`lt`/`lte`) on a sorted-indexed property are O(log N + |result|); Eq on
 * sorted buckets is O(log N). Null property values are still silently skipped.
 *
 * **Index staleness:** For repositories backed by `PersistentRepositoryBase` subclasses (such as
 * `SqlRepository` and `JsonFileRepository`), index entries are kept in sync with reactive property
 * mutations — changing an `@Indexed` property via a `reactiveProperty` delegate triggers an
 * automatic re-index. For entities held directly in `VolatileRepository` (without a persistent
 * backing store), mutations do **not** trigger re-indexing; index values reflect the state at
 * entity-add time. In that case, `@Indexed` properties should be immutable (e.g. declared `val`),
 * or changes must be made by removing the entity and re-adding it with the updated value.
 *
 * **Requires the `lirp-ksp` processor** to be applied via the KSP Gradle plugin. Without it,
 * `@Indexed` annotations have no effect and [Registry.findByIndex] throws [IllegalArgumentException].
 *
 * Example:
 * ```kotlin
 * // build.gradle.kts: ksp(project(":lirp-ksp"))
 *
 * data class Product(
 *     override val id: Int,
 *     @Indexed val category: String,
 *     @Indexed(name = "sku") val stockKeepingUnit: String
 * ) : IdentifiableEntity<Int>
 *
 * val repo = VolatileRepository<Int, Product>()
 * repo.add(Product(1, "electronics", "SKU-001"))
 *
 * val electronics = repo.findByIndex("category", "electronics")
 * val bySku = repo.findByIndex("sku", "SKU-001")
 * ```
 *
 * @param name The name of the index. Defaults to the property name when empty.
 * @param sorted When `true`, opts the property into a sorted (`NavigableMap`-backed) index bucket.
 *   The property type must implement `Comparable`; non-conforming types cause a KSP compile error.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Indexed(val name: String = "", val sorted: Boolean = false)