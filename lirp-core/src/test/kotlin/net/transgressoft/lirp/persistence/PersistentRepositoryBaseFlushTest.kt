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
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Tests for [PersistentRepositoryBase.flush] exception-type branching.
 *
 * Verifies that:
 * - [OptimisticLockException] drops the failing op and invokes [handleOptimisticLockConflict]
 * - Other exceptions re-enqueue the failed ops and rethrow (existing behavior)
 * - Ops that arrived during the failing write are preserved for both exception paths
 */
@DisplayName("PersistentRepositoryBase flush exception routing")
internal class PersistentRepositoryBaseFlushTest : StringSpec({

    "OptimisticLockException drops the failed op and does not rethrow" {
        val repo =
            TestFlushRepo(
                writeImpl = { _ ->
                    throw OptimisticLockException(
                        message = "test conflict",
                        entityId = 1,
                        expectedVersion = 3L,
                        actualVersion = 5L
                    )
                }
            )
        try {
            repo.addForTest(FlushEntity(1, "v1"))

            shouldNotThrowAny { repo.flushForTest() }

            repo.conflictHookInvocations.size shouldBe 1
            repo.conflictHookInvocations.single().expectedVersion shouldBe 3L
            repo.conflictHookInvocations.single().actualVersion shouldBe 5L
            // After handling: queue drained (the failing op was NOT re-enqueued)
            repo.pendingOpsSize() shouldBe 0
        } finally {
            repo.quiesceAndClose()
        }
    }

    "generic Exception re-enqueues the failed ops and rethrows" {
        val repo = TestFlushRepo(writeImpl = { _ -> throw RuntimeException("transient") })
        try {
            repo.addForTest(FlushEntity(2, "v1"))

            shouldThrow<RuntimeException> { repo.flushForTest() }

            repo.conflictHookInvocations.size shouldBe 0
            // The failed op is back in the queue for the next cycle
            repo.pendingOpsSize() shouldBe 1
        } finally {
            repo.quiesceAndClose()
        }
    }

    "ops arriving during the failed write are preserved alongside re-enqueued ops on generic Exception" {
        val repoHolder = arrayOfNulls<TestFlushRepo>(1)
        val repo =
            TestFlushRepo(
                writeImpl = { _ ->
                    // Simulate "an op arriving during the write" by adding another entity from inside writePending.
                    repoHolder[0]!!.addForTest(FlushEntity(99, "concurrent"))
                    throw RuntimeException("transient")
                }
            )
        repoHolder[0] = repo
        try {
            repo.addForTest(FlushEntity(3, "v1"))

            shouldThrow<RuntimeException> { repo.flushForTest() }

            // Both the original (re-enqueued) and the "arrived during write" op are in the queue
            repo.pendingOpsSize() shouldBe 2
        } finally {
            repo.quiesceAndClose()
        }
    }

    "OptimisticLockException preserves ops arriving during the failed write and drops only the failing snapshot" {
        val repoHolder = arrayOfNulls<TestFlushRepo>(1)
        val repo =
            TestFlushRepo(
                writeImpl = { _ ->
                    repoHolder[0]!!.addForTest(FlushEntity(99, "concurrent"))
                    throw OptimisticLockException(
                        message = "test conflict",
                        entityId = 3,
                        expectedVersion = 3L,
                        actualVersion = 5L
                    )
                }
            )
        repoHolder[0] = repo
        try {
            repo.addForTest(FlushEntity(3, "v1"))

            shouldNotThrowAny { repo.flushForTest() }

            repo.conflictHookInvocations.size shouldBe 1
            // The "arrived during write" op is still in the queue (only the failing snapshot was dropped)
            repo.pendingOpsSize() shouldBe 1
        } finally {
            repo.quiesceAndClose()
        }
    }
})

/** Minimal reactive entity for flush-routing tests. */
private data class FlushEntity(
    override val id: Int,
    val label: String
) : ReactiveEntityBase<Int, FlushEntity>() {
    override val uniqueId: String get() = "flush-$id"

    override fun clone(): FlushEntity = copy()
}

/** Test subclass exposing flush() + pending queue size to the test harness. */
private class TestFlushRepo(
    var writeImpl: (List<PendingOp<Int, FlushEntity>>) -> Unit
) : PersistentRepositoryBase<Int, FlushEntity>(name = "TestFlushRepo", loadOnInit = false) {

    val conflictHookInvocations = mutableListOf<OptimisticLockException>()

    init {
        load()
    }

    override fun loadFromStore(): Map<Int, FlushEntity> = emptyMap()

    override fun writePending(ops: List<PendingOp<Int, FlushEntity>>) {
        writeImpl(ops)
    }

    override fun handleOptimisticLockConflict(e: OptimisticLockException) {
        conflictHookInvocations.add(e)
    }

    /** Public façade: enqueue a PendingInsert without going through add()'s event overhead. */
    fun addForTest(entity: FlushEntity): Boolean = add(entity)

    /** Public façade over the protected [flush] method. */
    fun flushForTest() = flush()

    /** Public façade over the internal pending queue size for test assertions. */
    fun pendingOpsSize(): Int = pendingOpsCount()

    // Deterministic test cleanup: swap writeImpl to no-op so any debounce/max-delay flush job
    // that fires after the test body cannot throw, then close the repo to cancel the scheduled
    // coroutines. Without this, delayed coroutines could fire in another test's time window
    // and cause cross-test flakiness.
    fun quiesceAndClose() {
        writeImpl = { }
        close()
    }
}