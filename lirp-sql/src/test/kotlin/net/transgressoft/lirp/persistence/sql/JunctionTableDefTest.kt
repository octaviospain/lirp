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
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Contract tests for [JunctionTableDef] and [JunctionColumnDef] — the persistence-agnostic
 * descriptors for KSP-generated junction tables that back `aggregateList`/`aggregateSet`
 * collection references.
 */
class JunctionTableDefTest : StringSpec({

    "JunctionColumnDef carries name, type, primaryKey, and nullable flags" {
        val col = JunctionColumnDef(name = "parent_id", type = ColumnType.IntType, primaryKey = true, nullable = false)
        col.name shouldBe "parent_id"
        col.type shouldBe ColumnType.IntType
        col.primaryKey shouldBe true
        col.nullable shouldBe false
    }

    "JunctionColumnDef defaults primaryKey and nullable to false" {
        val col = JunctionColumnDef(name = "position", type = ColumnType.IntType)
        col.primaryKey shouldBe false
        col.nullable shouldBe false
    }

    "JunctionTableDef exposes parentTableName, itemTableName, columns list, and isOrdered flag" {
        val def =
            object : JunctionTableDef {
                override val tableName: String = "playlist_tracks"
                override val parentTableName: String = "playlist"
                override val itemTableName: String = "track"
                override val columns: List<JunctionColumnDef> =
                    listOf(
                        JunctionColumnDef("parent_id", ColumnType.IntType, primaryKey = true),
                        JunctionColumnDef("item_id", ColumnType.IntType, primaryKey = true),
                        JunctionColumnDef("position", ColumnType.IntType)
                    )
                override val isOrdered: Boolean = true
                override val parentFkOnDelete: CascadeAction = CascadeAction.CASCADE
                override val itemFkOnDelete: CascadeAction = CascadeAction.RESTRICT
            }

        def.tableName shouldBe "playlist_tracks"
        def.parentTableName shouldBe "playlist"
        def.itemTableName shouldBe "track"
        def.columns.size shouldBe 3
        def.isOrdered shouldBe true
        def.parentFkOnDelete shouldBe CascadeAction.CASCADE
        def.itemFkOnDelete shouldBe CascadeAction.RESTRICT
    }
})