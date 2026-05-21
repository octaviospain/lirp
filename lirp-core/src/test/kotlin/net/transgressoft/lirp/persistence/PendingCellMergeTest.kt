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

import net.transgressoft.lirp.entity.ReactiveEntityBase
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe

/** Minimal entity used to drive the [PendingCell] merge transition tables in isolation. */
internal data class CellEntity(
    override val id: Int,
    val label: String
) : ReactiveEntityBase<Int, CellEntity>() {
    override val uniqueId: String get() = "cell-$id"

    override fun clone(): CellEntity = copy()
}

/**
 * Transition-table contract for the per-key collapse algebra: every row of the writer-side
 * [mergeWriterSide] table and every row of the failure-restoration [mergeOlder] table is exercised
 * as its own test case so that any regression points to a single row, plus an end-to-end check
 * that the first-observed `expectedVersion` invariant survives multi-step merges.
 */
internal class PendingCellMergeTest : StringSpec({

    val entityA1 = CellEntity(1, "A-v1")
    val entityA2 = CellEntity(1, "A-v2")
    val entityA3 = CellEntity(1, "A-v3")

    // ─────────────────────────────── mergeWriterSide ───────────────────────────────

    "[PendingCell] mergeWriterSide returns Insert when current is null and incoming is Insert" {
        val incoming = PendingCell.Insert<Int, CellEntity>(entityA1)
        mergeWriterSide(null, incoming) shouldBe incoming
    }

    "[PendingCell] mergeWriterSide returns Update when current is null and incoming is Update" {
        val incoming = PendingCell.Update<Int, CellEntity>(entityA1, expectedVersion = 3L)
        mergeWriterSide(null, incoming) shouldBe incoming
    }

    "[PendingCell] mergeWriterSide returns Delete when current is null and incoming is Delete" {
        val incoming = PendingCell.Delete<Int, CellEntity>(expectedVersion = 2L)
        mergeWriterSide(null, incoming) shouldBe incoming
    }

    "[PendingCell] mergeWriterSide returns latest Insert when current and incoming are both Insert" {
        val current = PendingCell.Insert<Int, CellEntity>(entityA1)
        val incoming = PendingCell.Insert<Int, CellEntity>(entityA2)
        mergeWriterSide(current, incoming) shouldBe incoming
    }

    "[PendingCell] mergeWriterSide produces Insert dropping expectedVersion when current is Insert and incoming is Update" {
        val current = PendingCell.Insert<Int, CellEntity>(entityA1)
        val incoming = PendingCell.Update<Int, CellEntity>(entityA2, expectedVersion = 7L)
        mergeWriterSide(current, incoming) shouldBe PendingCell.Insert(entityA2)
    }

    "[PendingCell] mergeWriterSide returns null when current is Insert and incoming is Delete" {
        val current = PendingCell.Insert<Int, CellEntity>(entityA1)
        val incoming = PendingCell.Delete<Int, CellEntity>(expectedVersion = null)
        mergeWriterSide(current, incoming).shouldBeNull()
    }

    "[PendingCell] mergeWriterSide returns Insert when current is Update and incoming is Insert" {
        val current = PendingCell.Update<Int, CellEntity>(entityA1, expectedVersion = 5L)
        val incoming = PendingCell.Insert<Int, CellEntity>(entityA2)
        mergeWriterSide(current, incoming) shouldBe incoming
    }

    "[PendingCell] mergeWriterSide preserves first-observed expectedVersion when current and incoming are both Update" {
        val current = PendingCell.Update<Int, CellEntity>(entityA1, expectedVersion = 5L)
        val incoming = PendingCell.Update<Int, CellEntity>(entityA2, expectedVersion = 7L)
        mergeWriterSide(current, incoming) shouldBe PendingCell.Update(entityA2, expectedVersion = 5L)
    }

    "[PendingCell] mergeWriterSide returns Delete with its own expectedVersion when current is Update and incoming is Delete" {
        val current = PendingCell.Update<Int, CellEntity>(entityA1, expectedVersion = 5L)
        val incoming = PendingCell.Delete<Int, CellEntity>(expectedVersion = 9L)
        mergeWriterSide(current, incoming) shouldBe incoming
    }

    "[PendingCell] mergeWriterSide returns Insert when current is Delete and incoming is Insert" {
        val current = PendingCell.Delete<Int, CellEntity>(expectedVersion = 4L)
        val incoming = PendingCell.Insert<Int, CellEntity>(entityA2)
        mergeWriterSide(current, incoming) shouldBe incoming
    }

    "[PendingCell] mergeWriterSide keeps current Delete when current is Delete and incoming is Update" {
        val current = PendingCell.Delete<Int, CellEntity>(expectedVersion = 4L)
        val incoming = PendingCell.Update<Int, CellEntity>(entityA2, expectedVersion = 8L)
        mergeWriterSide(current, incoming) shouldBe current
    }

    "[PendingCell] mergeWriterSide remains idempotent when current and incoming are both Delete" {
        val current = PendingCell.Delete<Int, CellEntity>(expectedVersion = 4L)
        val incoming = PendingCell.Delete<Int, CellEntity>(expectedVersion = 4L)
        mergeWriterSide(current, incoming) shouldBe current
    }

    "[PendingCell] mergeWriterSide preserves the first expectedVersion across three sequential Updates" {
        val first = PendingCell.Update<Int, CellEntity>(entityA1, expectedVersion = 5L)
        val second = PendingCell.Update<Int, CellEntity>(entityA2, expectedVersion = 7L)
        val third = PendingCell.Update<Int, CellEntity>(entityA3, expectedVersion = 11L)

        val afterFirst = mergeWriterSide<Int, CellEntity>(null, first)
        val afterSecond = mergeWriterSide(afterFirst, second)
        val afterThird = mergeWriterSide(afterSecond, third)

        afterThird shouldBe PendingCell.Update(entityA3, expectedVersion = 5L)
    }

    // ─────────────────────────────── mergeOlder ───────────────────────────────

    "[PendingCell] mergeOlder restores older when newer is null" {
        val older = PendingCell.Update<Int, CellEntity>(entityA1, expectedVersion = 5L)
        mergeOlder<Int, CellEntity>(older, null) shouldBe older
    }

    "[PendingCell] mergeOlder restores newer Insert when older is Insert and newer is Insert" {
        val older = PendingCell.Insert<Int, CellEntity>(entityA1)
        val newer = PendingCell.Insert<Int, CellEntity>(entityA2)
        mergeOlder(older, newer) shouldBe newer
    }

    "[PendingCell] mergeOlder collapses to Insert when older is Insert and newer is Update" {
        val older = PendingCell.Insert<Int, CellEntity>(entityA1)
        val newer = PendingCell.Update<Int, CellEntity>(entityA2, expectedVersion = 9L)
        mergeOlder(older, newer) shouldBe PendingCell.Insert(entityA2)
    }

    "[PendingCell] mergeOlder restores Update preserving older expectedVersion when both are Update" {
        val older = PendingCell.Update<Int, CellEntity>(entityA1, expectedVersion = 5L)
        val newer = PendingCell.Update<Int, CellEntity>(entityA2, expectedVersion = 7L)
        mergeOlder(older, newer) shouldBe PendingCell.Update(entityA2, expectedVersion = 5L)
    }

    "[PendingCell] mergeOlder restores Delete when older is Update and newer is Delete" {
        val older = PendingCell.Update<Int, CellEntity>(entityA1, expectedVersion = 5L)
        val newer = PendingCell.Delete<Int, CellEntity>(expectedVersion = 9L)
        mergeOlder(older, newer) shouldBe newer
    }

    "[PendingCell] mergeOlder preserves delete-side expectedVersion when older is Delete and newer is Insert" {
        val older = PendingCell.Delete<Int, CellEntity>(expectedVersion = 4L)
        val newer = PendingCell.Insert<Int, CellEntity>(entityA2)
        // The delete-side version must survive into the restored Update so a retry surfaces an
        // optimistic-lock conflict if a concurrent writer committed in the meantime.
        mergeOlder(older, newer) shouldBe PendingCell.Update(entityA2, expectedVersion = 4L)
    }

    "[PendingCell] mergeOlder keeps newer Update when older is Delete and newer is Update" {
        val older = PendingCell.Delete<Int, CellEntity>(expectedVersion = 4L)
        val newer = PendingCell.Update<Int, CellEntity>(entityA2, expectedVersion = 7L)
        mergeOlder(older, newer) shouldBe newer
    }

    "[PendingCell] mergeOlder restores Delete when older is Insert and newer is Delete" {
        val older = PendingCell.Insert<Int, CellEntity>(entityA1)
        val newer = PendingCell.Delete<Int, CellEntity>(expectedVersion = 4L)
        mergeOlder(older, newer) shouldBe newer
    }

    "[PendingCell] mergeOlder restores Insert when older is Update and newer is Insert" {
        val older = PendingCell.Update<Int, CellEntity>(entityA1, expectedVersion = 5L)
        val newer = PendingCell.Insert<Int, CellEntity>(entityA2)
        mergeOlder(older, newer) shouldBe newer
    }

    "[PendingCell] mergeOlder remains idempotent when both are Delete" {
        val older = PendingCell.Delete<Int, CellEntity>(expectedVersion = 4L)
        val newer = PendingCell.Delete<Int, CellEntity>(expectedVersion = 6L)
        mergeOlder(older, newer) shouldBe newer
    }
})