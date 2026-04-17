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

package net.transgressoft.lirp.event

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.ColumnDef
import net.transgressoft.lirp.persistence.ColumnType
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/** Minimal entity for exercising [StandardCrudEvent.Conflict] without pulling production fixtures. */
private data class VersionedEntity(
    override val id: Int,
    val label: String,
    val version: Long
) : ReactiveEntityBase<Int, VersionedEntity>() {
    override val uniqueId: String get() = "versioned-$id"

    override fun clone(): VersionedEntity = copy()
}

internal class StandardCrudEventConflictTest : StringSpec({

    "ColumnDef with 4 args preserves backwards compatibility and defaults isVersion to false" {
        val col = ColumnDef("id", ColumnType.IntType, nullable = false, primaryKey = true)
        col.isVersion shouldBe false
    }

    "ColumnDef with isVersion = true exposes the flag" {
        val col = ColumnDef("v", ColumnType.LongType, nullable = false, primaryKey = false, isVersion = true)
        col.isVersion shouldBe true
    }

    "CrudEvent.Type.CONFLICT has code 950 distinct from other types" {
        CrudEvent.Type.CONFLICT.code shouldBe 950
        CrudEvent.Type.entries.map { it.code }.distinct().size shouldBe CrudEvent.Type.entries.size
    }

    "StandardCrudEvent.Conflict reports type CONFLICT and isConflict true" {
        val old = VersionedEntity(1, "A-old", 3L)
        val new = VersionedEntity(1, "A-new", 5L)
        val event = StandardCrudEvent.Conflict(new, old, expectedVersion = 3L, actualVersion = 5L)
        event.type shouldBe CrudEvent.Type.CONFLICT
        event.isConflict() shouldBe true
        event.isUpdate() shouldBe false
        event.isCreate() shouldBe false
        event.isDelete() shouldBe false
        event.isRead() shouldBe false
        event.entities shouldBe mapOf(1 to new)
        event.oldEntities shouldBe mapOf(1 to old)
        event.expectedVersion shouldBe 3L
        event.actualVersion shouldBe 5L
    }

    "StandardCrudEvent.Conflict requires actualVersion > expectedVersion for non-sentinel values" {
        val old = VersionedEntity(3, "C-old", 5L)
        val new = VersionedEntity(3, "C-new", 5L)
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            StandardCrudEvent.Conflict(new, old, expectedVersion = 5L, actualVersion = 5L)
        }
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> {
            StandardCrudEvent.Conflict(new, old, expectedVersion = 7L, actualVersion = 5L)
        }
    }

    "StandardCrudEvent.Conflict accepts actualVersion == -1L sentinel for row-deleted-by-third-writer" {
        val entity = VersionedEntity(4, "D", 2L)
        val event = StandardCrudEvent.Conflict(entity, entity, expectedVersion = 2L, actualVersion = -1L)
        event.actualVersion shouldBe -1L
        event.isConflict() shouldBe true
    }

    "StandardCrudEvent Update existing variant is unaffected by new CONFLICT enum value" {
        val old = VersionedEntity(2, "B-old", 0L)
        val new = VersionedEntity(2, "B-new", 0L)
        val event = StandardCrudEvent.Update(new, old)
        event.isUpdate() shouldBe true
        event.isConflict() shouldBe false
    }
})