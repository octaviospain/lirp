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
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** Minimal entity for testing [PendingOp] collapse without any dependency on production test fixtures. */
private data class SimpleEntity(
    override val id: Int,
    val label: String
) : ReactiveEntityBase<Int, SimpleEntity>() {
    override val uniqueId: String get() = "simple-$id"

    override fun clone(): SimpleEntity = copy()
}

internal class PendingOpCollapseTest : StringSpec({

    val entityA1 = SimpleEntity(1, "A-v1")
    val entityA2 = SimpleEntity(1, "A-v2")
    val entityB = SimpleEntity(2, "B")
    val entityC = SimpleEntity(3, "C")
    val entityD = SimpleEntity(4, "D")
    val entityE = SimpleEntity(5, "E")

    "collapse of empty list returns empty list" {
        collapse<Int, SimpleEntity>(emptyList()).shouldBeEmpty()
    }

    "collapse of single PendingInsert returns itself" {
        val result = collapse(listOf(PendingInsert(entityA1)))
        result shouldContainExactly listOf(PendingInsert(entityA1))
    }

    "PendingInsert followed by PendingUpdate on same entity collapses to PendingInsert with latest state" {
        val ops =
            listOf(
                PendingInsert(entityA1),
                PendingUpdate(entityA2)
            )
        val result = collapse(ops)
        result shouldContainExactly listOf(PendingInsert(entityA2))
    }

    "PendingInsert followed by PendingDelete on same entity collapses to empty list" {
        val ops =
            listOf(
                PendingInsert(entityA1),
                PendingDelete(entityA1.id)
            )
        val result = collapse(ops)
        result.shouldBeEmpty()
    }

    "Insert then Delete then Insert on same ID collapses to PendingInsert with final entity" {
        val entityAFinal = SimpleEntity(1, "A-reborn")
        val ops =
            listOf(
                PendingInsert(entityA1),
                PendingDelete(entityA1.id),
                PendingInsert(entityAFinal)
            )
        val result = collapse(ops)
        result shouldContainExactly listOf(PendingInsert(entityAFinal))
    }

    "multiple PendingUpdate on same entity collapses to single PendingUpdate with latest state" {
        val entityAv3 = SimpleEntity(1, "A-v3")
        val ops =
            listOf(
                PendingUpdate(entityA1),
                PendingUpdate(entityA2),
                PendingUpdate(entityAv3)
            )
        val result = collapse(ops)
        result shouldContainExactly listOf(PendingUpdate(entityAv3))
    }

    "PendingUpdate followed by PendingDelete on same entity collapses to PendingDelete" {
        val ops =
            listOf(
                PendingUpdate(entityA1),
                PendingDelete(entityA1.id)
            )
        val result = collapse(ops)
        result shouldContainExactly listOf(PendingDelete(entityA1.id))
    }

    "multiple PendingInsert on different entities merges into PendingBatchInsert" {
        val ops =
            listOf(
                PendingInsert(entityA1),
                PendingInsert(entityB),
                PendingInsert(entityC)
            )
        val result = collapse(ops)
        result shouldHaveSize 1
        val batch = result.first()
        batch.shouldBeInstanceOf<PendingBatchInsert<Int, SimpleEntity>>()
        batch.entities shouldContainExactly listOf(entityA1, entityB, entityC)
    }

    "multiple PendingDelete on different IDs merges into PendingBatchDelete" {
        val ops =
            listOf(
                PendingDelete<Int, SimpleEntity>(1),
                PendingDelete(2),
                PendingDelete(3)
            )
        val result = collapse(ops)
        result shouldHaveSize 1
        val batch = result.first()
        batch.shouldBeInstanceOf<PendingBatchDelete<Int, SimpleEntity>>()
        batch.ids shouldContainExactly listOf(1, 2, 3)
    }

    "PendingClear cancels all preceding operations" {
        val ops =
            listOf(
                PendingInsert(entityA1),
                PendingUpdate(entityB),
                PendingDelete(entityC.id),
                PendingClear()
            )
        val result = collapse(ops)
        result shouldContainExactly listOf(PendingClear())
    }

    "ops after PendingClear are kept with PendingClear preceding them" {
        val ops =
            listOf(
                PendingInsert(entityA1),
                PendingClear(),
                PendingInsert(entityB)
            )
        val result = collapse(ops)
        result shouldContainExactly listOf(PendingClear(), PendingInsert(entityB))
    }

    "collapsed ops are ordered: inserts first, then updates, then deletes" {
        val ops =
            listOf(
                PendingDelete(entityC.id),
                PendingUpdate(entityB),
                PendingInsert(entityA1)
            )
        val result = collapse(ops)
        result shouldHaveSize 3
        result[0].shouldBeInstanceOf<PendingInsert<Int, SimpleEntity>>()
        result[1].shouldBeInstanceOf<PendingUpdate<Int, SimpleEntity>>()
        result[2].shouldBeInstanceOf<PendingDelete<Int, SimpleEntity>>()
    }

    "ops after PendingClear are ordered: clear first, then inserts then updates then deletes" {
        val ops =
            listOf(
                PendingClear(),
                PendingDelete(entityC.id),
                PendingUpdate(entityB),
                PendingInsert(entityA1)
            )
        val result = collapse(ops)
        result shouldHaveSize 4
        result[0].shouldBeInstanceOf<PendingClear<Int, SimpleEntity>>()
        result[1].shouldBeInstanceOf<PendingInsert<Int, SimpleEntity>>()
        result[2].shouldBeInstanceOf<PendingUpdate<Int, SimpleEntity>>()
        result[3].shouldBeInstanceOf<PendingDelete<Int, SimpleEntity>>()
    }

    "PendingDelete followed by PendingUpdate on same entity preserves the delete" {
        val ops =
            listOf(
                PendingDelete<Int, SimpleEntity>(1),
                PendingUpdate(entityA1)
            )
        val result = collapse(ops)
        result shouldContainExactly listOf(PendingDelete(entityA1.id))
    }

    "mixed operations on multiple entities collapse correctly" {
        val ops =
            listOf(
                PendingInsert(entityA1), // A: insert v1
                PendingUpdate(entityA2), // A: update v2 -> insert keeps v2
                PendingInsert(entityB), // B: insert
                PendingDelete(entityB.id), // B: insert+delete = no-op
                PendingUpdate(entityC), // C: update
                PendingDelete(entityC.id), // C: update+delete = delete
                PendingInsert(entityD), // D: insert
                PendingInsert(entityE) // E: insert
            )
        val result = collapse(ops)
        // Expected: PendingBatchInsert(A-v2, D, E), PendingDelete(C)
        result shouldHaveSize 2
        result[0].shouldBeInstanceOf<PendingBatchInsert<Int, SimpleEntity>>()
        val batchInsert = result[0] as PendingBatchInsert<Int, SimpleEntity>
        batchInsert.entities shouldContainExactly listOf(entityA2, entityD, entityE)
        result[1].shouldBeInstanceOf<PendingDelete<Int, SimpleEntity>>()
        (result[1] as PendingDelete<Int, SimpleEntity>).id shouldBe entityC.id
    }

    "PendingBatchInsert input entities are expanded and re-collapsed as individual inserts" {
        val ops =
            listOf(
                PendingBatchInsert(listOf(entityA1, entityB, entityC))
            )
        val result = collapse(ops)
        result shouldHaveSize 1
        val batch = result.first()
        batch.shouldBeInstanceOf<PendingBatchInsert<Int, SimpleEntity>>()
        batch.entities shouldContainExactly listOf(entityA1, entityB, entityC)
    }

    "PendingBatchDelete input IDs are expanded and re-collapsed as individual deletes" {
        val ops =
            listOf(
                PendingBatchDelete<Int, SimpleEntity>(listOf(1, 2, 3))
            )
        val result = collapse(ops)
        result shouldHaveSize 1
        val batch = result.first()
        batch.shouldBeInstanceOf<PendingBatchDelete<Int, SimpleEntity>>()
        batch.ids shouldContainExactly listOf(1, 2, 3)
    }
})