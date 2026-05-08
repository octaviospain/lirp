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
import net.transgressoft.lirp.persistence.ColumnDef
import net.transgressoft.lirp.persistence.ColumnType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table

/**
 * Contract tests for the junction-aware extension surface added to [SqlTableDef]:
 * [SqlTableDef.junctionTableDefs], [SqlTableDef.junctionAccessors], and
 * [SqlTableDef.applyJunctionRows].
 *
 * The defaults must be safe — every existing hand-written `SqlTableDef` implementation must
 * continue to compile and pass without overriding any of the three members. The KSP-emitted
 * overrides for entities with `@Aggregate` collection refs are tested separately in `lirp-ksp`.
 */
class SqlTableDefJunctionContractTest : StringSpec({

    data class Dummy(val id: Int)

    val minimalDef =
        object : SqlTableDef<Dummy> {
            override val tableName: String = "dummy"
            override val columns: List<ColumnDef> =
                listOf(ColumnDef("id", ColumnType.IntType, nullable = false, primaryKey = true))

            override fun fromRow(row: ResultRow, table: Table): Dummy = error("not used")

            override fun toParams(entity: Dummy, table: Table): Map<Column<*>, Any?> = emptyMap()

            override fun applyRow(entity: Dummy, row: ResultRow, table: Table) = Unit
        }

    "SqlTableDef junctionTableDefs defaults to empty list" {
        minimalDef.junctionTableDefs.shouldBeEmpty()
    }

    "SqlTableDef junctionAccessors defaults to empty list" {
        minimalDef.junctionAccessors.shouldBeEmpty()
    }

    "SqlTableDef applyJunctionRows is a no-op by default" {
        val descriptor =
            object : JunctionTableDef {
                override val tableName: String = "dummy_items"
                override val parentTableName: String = "dummy"
                override val itemTableName: String = "item"
                override val columns: List<JunctionColumnDef> = emptyList()
                override val isOrdered: Boolean = false
                override val parentFkOnDelete: CascadeAction = CascadeAction.CASCADE
                override val itemFkOnDelete: CascadeAction = CascadeAction.DETACH
            }

        // Default body is a no-op — no exception, no observable side effect on the entity.
        minimalDef.applyJunctionRows(Dummy(1), descriptor, listOf(10, 20, 30))
    }

    "JunctionAccessor exposes descriptor reference and idsOf contract" {
        val descriptor =
            object : JunctionTableDef {
                override val tableName: String = "dummy_items"
                override val parentTableName: String = "dummy"
                override val itemTableName: String = "item"
                override val columns: List<JunctionColumnDef> = emptyList()
                override val isOrdered: Boolean = true
                override val parentFkOnDelete: CascadeAction = CascadeAction.CASCADE
                override val itemFkOnDelete: CascadeAction = CascadeAction.DETACH
            }

        val accessor =
            object : JunctionAccessor<Dummy> {
                override val descriptor: JunctionTableDef = descriptor

                override fun idsOf(entity: Dummy): Collection<Any> = listOf(entity.id, entity.id + 1)
            }

        accessor.descriptor.shouldBeSameInstanceAs(descriptor)
        val ids = accessor.idsOf(Dummy(7)).toList()
        ids.shouldHaveSize(2)
        ids[0] shouldBe 7
        ids[1] shouldBe 8
    }
})