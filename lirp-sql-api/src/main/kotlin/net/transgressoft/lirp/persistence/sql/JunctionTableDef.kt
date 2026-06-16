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

package net.transgressoft.lirp.persistence.sql

import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.persistence.ColumnType

/**
 * A single column descriptor within a [JunctionTableDef].
 *
 * Junction tables have a fixed shape: two foreign-key columns (`parent_id`, `item_id`) form a
 * composite primary key, and an optional `position` column captures list ordering. Each
 * [JunctionColumnDef] is therefore a SQL-specific column descriptor — distinct from the entity-row
 * [net.transgressoft.lirp.persistence.ColumnDef] because junction columns never participate in
 * `fromRow` / `toParams` mappings.
 *
 * @property name The column name as it appears in the SQL DDL.
 * @property type The persistence-agnostic column type, reused from
 *   [net.transgressoft.lirp.persistence.ColumnType].
 * @property primaryKey Whether this column is part of the junction's composite primary key.
 * @property nullable Whether this column accepts SQL NULL.
 */
data class JunctionColumnDef(
    val name: String,
    val type: ColumnType,
    val primaryKey: Boolean = false,
    val nullable: Boolean = false
)

/**
 * Persistence-agnostic descriptor for a junction table that backs an `aggregateList` or
 * `aggregateSet` collection reference.
 *
 * Generated at compile time by the KSP `TableDefProcessor` as a sibling object of the parent
 * entity's `_LirpTableDef`. One descriptor is emitted per `@ToManyAggregates` collection property; the
 * generated object name follows the convention `{ParentSimpleName}_{PropertySimpleName}_LirpJunctionTableDef`.
 *
 * Lives in `lirp-sql` rather than `lirp-api` because junction tables are a SQL-specific concept —
 * JSON persistence inlines collection IDs into the parent document and has no junction equivalent.
 *
 * The descriptor carries the foreign-key cascade policy for both sides:
 * - [parentFkOnDelete] is always [CascadeAction.CASCADE] (housekeeping — when the parent entity is
 *   deleted, the orphaned junction rows are reclaimed by the database itself).
 * - [itemFkOnDelete] mirrors the user-declared `@ToManyAggregates(onDelete = …)` value and translates to
 *   the SQL `ON DELETE` clause on the item-side foreign key.
 */
interface JunctionTableDef {

    /** The junction table name (e.g., `playlist_tracks`). */
    val tableName: String

    /** The SQL table name of the parent entity (the entity owning the `@ToManyAggregates` property). */
    val parentTableName: String

    /** The SQL table name of the referenced item entity. */
    val itemTableName: String

    /**
     * The fixed column shape of the junction table:
     * - `parent_id` and `item_id` (both `primaryKey = true`) form the composite key.
     * - `position` (with `primaryKey = false`) is present only when [isOrdered] is `true`.
     */
    val columns: List<JunctionColumnDef>

    /** `true` for `aggregateList` (ordered, with `position` column); `false` for `aggregateSet`. */
    val isOrdered: Boolean

    /** Always [CascadeAction.CASCADE] — orphaned junction rows are housekeeping. */
    val parentFkOnDelete: CascadeAction

    /** The user-declared `@ToManyAggregates(onDelete = …)` policy applied to the item-side foreign key. */
    val itemFkOnDelete: CascadeAction
}