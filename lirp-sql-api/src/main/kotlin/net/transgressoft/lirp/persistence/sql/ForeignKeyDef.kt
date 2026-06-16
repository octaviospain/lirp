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

/**
 * SQL foreign-key descriptor attached to a scalar column of a [SqlTableDef].
 *
 * Generated at compile time by the KSP `TableDefProcessor` for each single-entity
 * `@ToOneAggregate` reference whose [CascadeAction] is not [CascadeAction.NONE]. The
 * [net.transgressoft.lirp.persistence.sql.ExposedTableInterpreter] consumes these
 * descriptors and emits `REFERENCES … ON DELETE …` constraints on the corresponding
 * Exposed `Column`.
 *
 * `NONE` is intentionally absent — when the user picks `CascadeAction.NONE`, no
 * `ForeignKeyDef` is emitted at all (the column is left as a plain scalar with no
 * FK constraint, preserving backwards compatibility with consumers whose existing
 * schemas lack the constraint).
 *
 * @property columnName The name of the column in the parent entity's table that holds the FK.
 * @property referencedTable The name of the referenced (child) entity's table.
 * @property referencedColumn The name of the primary-key column in the referenced table.
 * @property onDelete The cascade policy from the user's `@ToOneAggregate(onDelete = …)` declaration.
 *   Translated to `ReferenceOption.RESTRICT` / `CASCADE` / `SET_NULL` by the SQL interpreter.
 */
data class ForeignKeyDef(
    val columnName: String,
    val referencedTable: String,
    val referencedColumn: String,
    val onDelete: CascadeAction
)