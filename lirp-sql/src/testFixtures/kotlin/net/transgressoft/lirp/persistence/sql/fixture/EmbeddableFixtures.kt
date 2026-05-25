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

package net.transgressoft.lirp.persistence.sql.fixture

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.Embeddable
import net.transgressoft.lirp.persistence.Embedded
import net.transgressoft.lirp.persistence.PersistenceMapping
import net.transgressoft.lirp.persistence.PersistenceProperty
import net.transgressoft.lirp.persistence.sql.PathConverter
import java.nio.file.Path

/**
 * Single-level value object representing a contributing person. Two scalar fields with the same
 * names as [PublisherValue] — the parent entity's prefix derivation must keep flattened columns
 * distinct when both embeddables share field names.
 */
@Embeddable
data class PersonValue(val name: String, val countryCode: String)

/**
 * Single-level value object representing the publishing organisation. Shares field names with
 * [PersonValue] to exercise prefix differentiation on the parent entity.
 */
@Embeddable
data class PublisherValue(val name: String, val countryCode: String)

/**
 * Two-level value object: a `WorkValue` carries scalar fields plus two nested `@Embedded` value
 * objects ([PersonValue] performer + [PublisherValue] publisher). Exercises mixed scalar / embedded
 * / nullable-`Short` composition under recursive flattening with prefix concatenation.
 */
@Embeddable
data class WorkValue(
    val title: String,
    @Embedded val performer: PersonValue,
    val isCompilation: Boolean,
    val year: Short?,
    @Embedded val publisher: PublisherValue
)

/**
 * Parent entity embedding [WorkValue] (2-level recursive flatten) and a converter-bearing scalar
 * leaf at the entity root via [PathConverter]. Used by the SQL round-trip suite to verify that a
 * full multi-level `@Embedded` graph reconstructs identically after read-back.
 *
 * Flattened columns:
 *  - `id`, `title`, `path`
 *  - `work_title`, `work_performer_name`, `work_performer_country_code`, `work_is_compilation`,
 *    `work_year`, `work_publisher_name`, `work_publisher_country_code`
 */
@PersistenceMapping(name = "catalog_item")
data class CatalogItem(
    override val id: Long,
    val title: String,
    @Embedded val work: WorkValue,
    @PersistenceProperty(converter = PathConverter::class) val path: Path
) : ReactiveEntityBase<Long, CatalogItem>() {
    override val uniqueId: String get() = "$id"

    override fun clone(): CatalogItem = copy()
}

/**
 * Innermost leaf of the 3-level recursive `@Embedded` chain. Holds a single scalar field whose
 * flattened column name is the concatenation of every enclosing prefix.
 */
@Embeddable
data class L3Leaf(val value: String)

/**
 * Mid-level link in the 3-level recursive `@Embedded` chain — contains one further `@Embedded`
 * value object ([L3Leaf]).
 */
@Embeddable
data class L2Mid(
    @Embedded val leaf: L3Leaf
)

/**
 * Topmost link in the 3-level recursive `@Embedded` chain — contains one further `@Embedded`
 * value object ([L2Mid]).
 */
@Embeddable
data class L1Top(
    @Embedded val mid: L2Mid
)

/**
 * Parent entity exercising 3 levels of recursive `@Embedded` nesting. The expected single
 * flattened scalar column is `top_mid_leaf_value`, asserting that prefix concatenation walks the
 * full chain top-down.
 */
@PersistenceMapping(name = "three_level_entity")
data class ThreeLevelEntity(
    override val id: Long,
    @Embedded val top: L1Top
) : ReactiveEntityBase<Long, ThreeLevelEntity>() {
    override val uniqueId: String get() = "$id"

    override fun clone(): ThreeLevelEntity = copy()
}

/**
 * Value object composing `@PersistenceProperty(converter = …)` at a scalar leaf inside an
 * `@Embeddable`. Used to lock the cross-feature success criterion that converter-routed scalars
 * survive a full flatten + read-back round-trip when located inside an embeddable.
 */
@Embeddable
data class MediaValue(
    val name: String,
    @PersistenceProperty(converter = PathConverter::class) val path: Path
)

/**
 * Parent entity embedding [MediaValue] to exercise converter-inside-`@Embeddable` composition at
 * the SQL round-trip layer. The leaf column `media_path` is written via `PathConverter.toSql`
 * and reconstructed via `PathConverter.fromSql`.
 */
@PersistenceMapping(name = "media_entity")
data class MediaEntity(
    override val id: Long,
    @Embedded val media: MediaValue
) : ReactiveEntityBase<Long, MediaEntity>() {
    override val uniqueId: String get() = "$id"

    override fun clone(): MediaEntity = copy()
}