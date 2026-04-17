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

import net.transgressoft.lirp.persistence.ColumnDef
import net.transgressoft.lirp.persistence.ColumnType
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table

/**
 * Manual [SqlTableDef] for [TestVersionedPerson], mirroring [TestPersonTableDef] with an
 * additional `version` column flagged `isVersion = true` to exercise the optimistic-locking
 * code path in `SqlRepository`.
 */
object TestVersionedPersonTableDef : SqlTableDef<TestVersionedPerson> {
    override val tableName = "test_versioned_persons"
    override val columns =
        listOf(
            ColumnDef("id", ColumnType.IntType, nullable = false, primaryKey = true),
            ColumnDef("first_name", ColumnType.VarcharType(100), nullable = false, primaryKey = false),
            ColumnDef("last_name", ColumnType.VarcharType(100), nullable = false, primaryKey = false),
            ColumnDef("age", ColumnType.IntType, nullable = false, primaryKey = false),
            ColumnDef("version", ColumnType.LongType, nullable = false, primaryKey = false, isVersion = true)
        )

    @Suppress("UNCHECKED_CAST")
    override fun fromRow(row: ResultRow, table: Table): TestVersionedPerson {
        val cols = table.columns.associateBy { it.name }
        val entity = TestVersionedPerson(row[cols["id"]!! as Column<Int>])
        entity.firstName = row[cols["first_name"]!! as Column<String>]
        entity.lastName = row[cols["last_name"]!! as Column<String>]
        entity.age = row[cols["age"]!! as Column<Int>]
        entity.version = row[cols["version"]!! as Column<Long>]
        return entity
    }

    override fun toParams(entity: TestVersionedPerson, table: Table): Map<Column<*>, Any?> {
        val cols = table.columns.associateBy { it.name }
        return mapOf(
            cols["id"]!! to entity.id,
            cols["first_name"]!! to entity.firstName,
            cols["last_name"]!! to entity.lastName,
            cols["age"]!! to entity.age,
            cols["version"]!! to entity.version
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun applyRow(entity: TestVersionedPerson, row: ResultRow, table: Table) {
        val cols = table.columns.associateBy { it.name }
        entity.firstName = row[cols["first_name"]!! as Column<String>]
        entity.lastName = row[cols["last_name"]!! as Column<String>]
        entity.age = row[cols["age"]!! as Column<Int>]
        entity.version = row[cols["version"]!! as Column<Long>]
        // id is PK — immutable on TestVersionedPerson
    }

    override fun bumpVersion(entity: TestVersionedPerson, newVersion: Long) {
        entity.version = newVersion
    }
}