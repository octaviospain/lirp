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

import net.transgressoft.lirp.event.ReactiveScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * Tests for [PersistentRepositoryBase] debounce queue behavior using virtual time.
 *
 * All timing is controlled via [TestCoroutineScheduler] to avoid flaky wall-clock dependencies.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("PersistentRepositoryBase debounce queue")
internal class PersistentRepositoryDebounceTest : StringSpec({

    val testScheduler = TestCoroutineScheduler()
    val testDispatcher = StandardTestDispatcher(testScheduler)
    val testScope = CoroutineScope(testDispatcher + SupervisorJob())

    lateinit var ctx: LirpContext
    lateinit var repo: TestPersistentRepository

    beforeSpec {
        ReactiveScope.flowScope = testScope
        ReactiveScope.ioScope = testScope
    }

    beforeTest {
        ctx = LirpContext()
        repo = TestPersistentRepository(ctx)
    }

    afterTest {
        try {
            if (!repo.repoIsClosed) repo.close()
        } catch (_: Exception) {
            // close() may propagate flush errors from tests that deliberately inject failures
        }
        testScheduler.runCurrent()
    }

    afterSpec {
        ReactiveScope.resetDefaultFlowScope()
        ReactiveScope.resetDefaultIoScope()
    }

    "rapid add() calls within debounce window produce exactly 1 writePending() call" {
        val e1 = Customer(1, "Alice")
        val e2 = Customer(2, "Bob")
        val e3 = Customer(3, "Charlie")

        repo.add(e1)
        repo.add(e2)
        repo.add(e3)

        // Before debounce fires, no write yet
        repo.writtenOps.shouldBeEmpty()

        // Advance past debounce window (100ms default)
        testScheduler.advanceTimeBy(101)
        testScheduler.runCurrent()

        // Exactly 1 writePending call, collapsed to PendingBatchInsert
        repo.writtenOps shouldHaveSize 1
        repo.writtenOps[0].shouldHaveSize(1)
        repo.writtenOps[0][0].shouldBeInstanceOf<PendingBatchInsert<Int, Customer>>()
    }

    "after debounceMillis of inactivity, writePending() is called with collapsed ops" {
        val customer = Customer(10, "Dan")
        repo.add(customer)

        testScheduler.advanceTimeBy(50)
        testScheduler.runCurrent()
        repo.writtenOps.shouldBeEmpty()

        testScheduler.advanceTimeBy(55)
        testScheduler.runCurrent()

        repo.writtenOps shouldHaveSize 1
        repo.writtenOps[0][0].shouldBeInstanceOf<PendingInsert<Int, Customer>>()
        val op = repo.writtenOps[0][0] as PendingInsert<Int, Customer>
        op.entity shouldBe customer
    }

    "max-delay cap forces flush even under continuous mutations" {
        val shortDebounce = TestPersistentRepository(ctx, debounceMillis = 200L, maxDelayMillis = 500L)

        val customer = Customer(20, "Eve")
        shortDebounce.add(customer)

        // Mutate every 150ms to keep resetting the 200ms debounce (no debounce flush yet),
        // while the 500ms max-delay job runs to completion
        repeat(3) { i ->
            testScheduler.advanceTimeBy(150)
            testScheduler.runCurrent()
            customer.updateName("Name-$i")
        }

        // 450ms elapsed; debounce still restarting. Advance to 500ms total.
        testScheduler.advanceTimeBy(60)
        testScheduler.runCurrent()

        // max-delay cap (500ms) should have fired
        shortDebounce.writtenOps.isEmpty() shouldBe false
        shortDebounce.close()
    }

    "close() flushes all pending ops synchronously before returning" {
        val customer = Customer(30, "Frank")
        repo.add(customer)

        // Don't advance time — debounce not yet fired
        repo.writtenOps.shouldBeEmpty()

        repo.close()

        // After close(), pending ops must be flushed synchronously
        repo.writtenOps shouldHaveSize 1
        repo.writtenOps[0][0].shouldBeInstanceOf<PendingInsert<Int, Customer>>()
    }

    "operations after close() throw IllegalStateException" {
        repo.close()

        shouldThrow<IllegalStateException> { repo.add(Customer(40, "Grace")) }
        shouldThrow<IllegalStateException> { repo.remove(Customer(41, "Hank")) }
        shouldThrow<IllegalStateException> { repo.removeAll(listOf(Customer(42, "Ivy"))) }
        shouldThrow<IllegalStateException> { repo.clear() }
    }

    "flush failure re-enqueues raw ops and next flush processes them" {
        val customer = Customer(50, "Jack")
        repo.add(customer)
        repo.failNextWrite = true

        // First flush: writePending throws, ops re-enqueued, retry scheduled
        testScheduler.advanceTimeBy(101)
        testScheduler.runCurrent()

        // No successful writes yet — the exception occurred inside the coroutine
        repo.writtenOps.shouldBeEmpty()

        // Retry flush fires after another debounce window
        testScheduler.advanceTimeBy(101)
        testScheduler.runCurrent()

        repo.writtenOps shouldHaveSize 1
        repo.writtenOps[0][0].shouldBeInstanceOf<PendingInsert<Int, Customer>>()
    }

    "entity mutation enqueues PendingUpdate and is included in next flush" {
        val customer = Customer(60, "Kate")
        repo.add(customer)
        testScheduler.advanceTimeBy(101)
        testScheduler.runCurrent()
        repo.writtenOps.clear()

        customer.updateName("Kate Updated")
        testScheduler.advanceTimeBy(101)
        testScheduler.runCurrent()

        repo.writtenOps shouldHaveSize 1
        repo.writtenOps[0][0].shouldBeInstanceOf<PendingUpdate<Int, Customer>>()
        val update = repo.writtenOps[0][0] as PendingUpdate<Int, Customer>
        update.entity.name shouldBe "Kate Updated"
    }

    "clear followed by add produces PendingClear then PendingInsert" {
        val c1 = Customer(80, "Mia")
        val c2 = Customer(81, "Noah")
        repo.add(c1)
        testScheduler.advanceTimeBy(101)
        testScheduler.runCurrent()
        repo.writtenOps.clear()

        repo.clear()
        repo.add(c2)

        testScheduler.advanceTimeBy(101)
        testScheduler.runCurrent()

        repo.writtenOps shouldHaveSize 1
        val ops = repo.writtenOps[0]
        ops shouldHaveSize 2
        ops[0].shouldBeInstanceOf<PendingClear<Int, Customer>>()
        ops[1].shouldBeInstanceOf<PendingInsert<Int, Customer>>()
    }

    "flush failure with interleaved enqueue preserves chronological order" {
        val c1 = Customer(90, "Olivia")
        repo.add(c1)
        repo.failNextWrite = true

        // First flush: fails, re-enqueues
        testScheduler.advanceTimeBy(101)
        testScheduler.runCurrent()
        repo.writtenOps.shouldBeEmpty()

        // Interleaved enqueue while retry is pending
        val c2 = Customer(91, "Pete")
        repo.add(c2)

        // Retry flush: should contain both ops in chronological order
        testScheduler.advanceTimeBy(101)
        testScheduler.runCurrent()

        repo.writtenOps shouldHaveSize 1
        val ops = repo.writtenOps[0]
        ops shouldHaveSize 1
        ops[0].shouldBeInstanceOf<PendingBatchInsert<Int, Customer>>()
        val batch = ops[0] as PendingBatchInsert<Int, Customer>
        batch.entities.map { it.id } shouldContainExactly listOf(c1.id, c2.id)
    }

    "init via addToMemoryOnly() does NOT enqueue any PendingOps" {
        val preloaded = Customer(70, "Leo")
        val initRepo = TestPersistentRepository(ctx)
        initRepo.loadFromStorage(preloaded)

        // Advance time to trigger any potential debounce
        testScheduler.advanceTimeBy(200)
        testScheduler.runCurrent()

        initRepo.writtenOps.shouldBeEmpty()
        initRepo.close()
    }
})

private class TestPersistentRepository(
    context: LirpContext,
    debounceMillis: Long = 100L,
    maxDelayMillis: Long = 1000L
) : PersistentRepositoryBase<Int, Customer>(
        context, "TestPersistentRepo", ConcurrentHashMap(),
        debounceMillis, maxDelayMillis
    ) {
    val writtenOps = mutableListOf<List<PendingOp<Int, Customer>>>()
    var failNextWrite = false

    init {
        if (loadOnInit) load()
    }

    override fun loadFromStore(): Map<Int, Customer> = emptyMap()

    override fun writePending(ops: List<PendingOp<Int, Customer>>) {
        if (failNextWrite) {
            failNextWrite = false
            throw RuntimeException("Simulated write failure")
        }
        writtenOps.add(ops)
    }

    val repoIsClosed: Boolean get() = closed

    /** Exposes [addToMemoryOnly] for testing init-path behaviour. */
    fun loadFromStorage(entity: Customer) {
        addToMemoryOnly(entity)
    }
}