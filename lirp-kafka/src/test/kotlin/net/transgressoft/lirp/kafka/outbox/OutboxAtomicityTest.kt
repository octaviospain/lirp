/******************************************************************************
 *     Copyright (C) 2026  Octavio Calleya Garcia                             *
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

package net.transgressoft.lirp.kafka.outbox

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.kafka.KafkaOutboxSqlRepository
import net.transgressoft.lirp.persistence.AudioItem
import net.transgressoft.lirp.persistence.LirpTransactionException
import net.transgressoft.lirp.persistence.MutableAudioItem
import net.transgressoft.lirp.persistence.PendingUpdate
import net.transgressoft.lirp.persistence.RegistryBase
import net.transgressoft.lirp.persistence.TransactionBuffer
import net.transgressoft.lirp.persistence.TransactionConflictException
import net.transgressoft.lirp.persistence.sql.AudioItemSqlTableDef
import net.transgressoft.lirp.persistence.sql.ExposedTableInterpreter
import net.transgressoft.lirp.persistence.sql.H2ContainerSupport
import net.transgressoft.lirp.persistence.sql.TestVersionedPerson
import net.transgressoft.lirp.persistence.sql.TestVersionedPersonTableDef
import net.transgressoft.lirp.persistence.transaction
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * H2 unit tests for [KafkaOutboxSqlRepository] transactional outbox capture.
 *
 * Covers: commit produces a matching outbox row, the nine-column schema is fully queryable,
 * the debounced write path produces an outbox row, block-throw and version-conflict rollbacks
 * leave zero rows, and a single run produces rows spanning all three event-type families.
 */
@DisplayName("OutboxAtomicityTest (H2)")
internal class OutboxAtomicityTest : StringSpec() {

    /**
     * Counts outbox rows directly via raw Exposed — independent of the repository's in-memory
     * state so a no-op capture or a false rollback would surface as a count mismatch.
     */
    fun countOutboxRows(dataSource: HikariDataSource): Long {
        val db = Database.connect(dataSource)
        return transaction(db) { OutboxEventTable.selectAll().count() }
    }

    /**
     * Polls [countOutboxRows] until it reaches [expected] or the timeout elapses. The debounced
     * write pipeline flushes within a short window, but its scheduling can lag on a loaded CI host,
     * so the poll budget is intentionally wider than the flush window.
     */
    suspend fun waitForOutboxCount(
        dataSource: HikariDataSource,
        expected: Long,
        timeoutMs: Int = 3000,
        pollMs: Long = 50
    ) {
        var waited = 0
        while (countOutboxRows(dataSource) < expected && waited < timeoutMs) {
            delay(pollMs)
            waited += pollMs.toInt()
        }
    }

    init {

        afterEach {
            RegistryBase.deregisterRepository(AudioItem::class.java)
            RegistryBase.deregisterRepository(TestVersionedPerson::class.java)
        }

        "OutboxAtomicityTest commits one outbox row with matching aggregate id and CREATE code after transaction add" {
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val repo = KafkaOutboxSqlRepository<Int, AudioItem>(dataSource, AudioItemSqlTableDef)
            val item = MutableAudioItem(1, "Bohemian Rhapsody", "A Night at the Opera") as AudioItem
            try {
                transaction(repo) { r ->
                    r.add(item)
                }

                countOutboxRows(dataSource) shouldBe 1L

                // Verify the row content: aggregate id and event type code match the entity mutation.
                val db = Database.connect(dataSource)
                val row =
                    transaction(db) {
                        OutboxEventTable.selectAll().singleOrNull()
                    }
                row!![OutboxEventTable.aggregateId] shouldBe "1"
                row[OutboxEventTable.eventTypeCode] shouldBe CrudEvent.Type.CREATE.code
                row[OutboxEventTable.aggregateType] shouldBe AudioItemSqlTableDef.tableName
            } finally {
                repo.close()
                dataSource.close()
            }
        }

        "OutboxAtomicityTest all nine outbox columns are present and queryable without schema error" {
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val repo = KafkaOutboxSqlRepository<Int, AudioItem>(dataSource, AudioItemSqlTableDef)
            val item = MutableAudioItem(2, "Killer Queen", "Sheer Heart Attack") as AudioItem
            try {
                transaction(repo) { r ->
                    r.add(item)
                }

                val db = Database.connect(dataSource)
                val row =
                    transaction(db) {
                        OutboxEventTable.selectAll().singleOrNull()
                    }!!

                // Each column access verifies the schema column exists and is readable.
                row[OutboxEventTable.id] // uuid — primary key
                row[OutboxEventTable.aggregateType] shouldBe AudioItemSqlTableDef.tableName
                row[OutboxEventTable.aggregateId] shouldBe "2"
                row[OutboxEventTable.eventTypeCode] shouldBe CrudEvent.Type.CREATE.code
                row[OutboxEventTable.payload].isNotEmpty() shouldBe true
                row[OutboxEventTable.createdAt] // timestamp
                row[OutboxEventTable.sentAt] shouldBe null // nullable, not set at capture
                row[OutboxEventTable.retryCount] shouldBe 0 // default 0
                row[OutboxEventTable.lastError] shouldBe null // nullable, not set at capture
            } finally {
                repo.close()
                dataSource.close()
            }
        }

        "OutboxAtomicityTest debounced writePending flush produces one outbox row with a CrudEvent code" {
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val repo = KafkaOutboxSqlRepository<Int, AudioItem>(dataSource, AudioItemSqlTableDef)
            val item = MutableAudioItem(3, "We Will Rock You", "News of the World") as AudioItem
            try {
                // Add without an explicit transaction — debounce flush path.
                repo.add(item)

                // The debounce flush window is short (≈100 ms, up to ~1 s); poll well beyond it so
                // a loaded CI host does not flake.
                runBlocking { waitForOutboxCount(dataSource, 1L) }

                countOutboxRows(dataSource) shouldBe 1L

                val db = Database.connect(dataSource)
                val row = transaction(db) { OutboxEventTable.selectAll().singleOrNull() }!!
                row[OutboxEventTable.eventTypeCode] shouldBe CrudEvent.Type.CREATE.code
                row[OutboxEventTable.aggregateId] shouldBe "3"
            } finally {
                repo.close()
                dataSource.close()
            }
        }

        "OutboxAtomicityTest debounced delete flush produces a DELETE outbox row with an empty payload" {
            // The debounced delete path is the only one whose payload differs: the entity is already
            // gone from in-memory state when writePending fires, so the row records an empty JSON
            // object rather than a field snapshot.
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val repo = KafkaOutboxSqlRepository<Int, AudioItem>(dataSource, AudioItemSqlTableDef)
            val item = MutableAudioItem(7, "Somebody to Love", "A Day at the Races") as AudioItem
            try {
                // Create, then delete — both on the debounce path (no explicit transaction).
                repo.add(item)
                runBlocking { waitForOutboxCount(dataSource, 1L) }

                repo.remove(item)
                runBlocking { waitForOutboxCount(dataSource, 2L) }

                val db = Database.connect(dataSource)
                val deleteRow =
                    transaction(db) {
                        OutboxEventTable.selectAll()
                            .where { OutboxEventTable.eventTypeCode eq CrudEvent.Type.DELETE.code }
                            .singleOrNull()
                    }!!
                deleteRow[OutboxEventTable.aggregateId] shouldBe "7"
                deleteRow[OutboxEventTable.payload] shouldBe "{}"
            } finally {
                repo.close()
                dataSource.close()
            }
        }

        "OutboxAtomicityTest block-throw rollback leaves zero outbox rows" {
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val repo = KafkaOutboxSqlRepository<Int, AudioItem>(dataSource, AudioItemSqlTableDef)
            val item = MutableAudioItem(4, "Radio Ga Ga", "The Works") as AudioItem
            try {
                shouldThrow<LirpTransactionException> {
                    transaction(repo) { r ->
                        r.add(item)
                        throw RuntimeException("injected failure — outbox and entity rows must both roll back")
                    }
                }

                // Both the entity INSERT and the outbox INSERT must roll back atomically.
                countOutboxRows(dataSource) shouldBe 0L
            } finally {
                repo.close()
                dataSource.close()
            }
        }

        "OutboxAtomicityTest version-conflict crash leaves no additional outbox rows — rollback is atomic" {
            // commitTransactionBuffer is public (open fun in PersistentRepositoryBase with no
            // visibility modifier), so it is callable directly from the test without any
            // production visibility change. This approach mirrors SqlTransactionTest lines ~182-248
            // exactly: hand-assemble a TransactionBuffer with a stale PendingUpdate, call
            // commitTransactionBuffer, and assert the outbox INSERT rolled back atomically with
            // the conflicting entity write.
            //
            // Test setup:
            //   1. Seed entity to DB via seedRepo.close() (writePending path) — produces 1 outbox row
            //   2. Open a new repo, bump the DB row's version externally
            //   3. Hand-assembled buffer: PendingUpdate with expectedVersion=0 (stale)
            //   4. commitTransactionBuffer → TransactionConflictException
            //   5. Assert outbox count is still 1 (the failed UPDATE's outbox INSERT rolled back)
            val dataSource = H2ContainerSupport.buildH2DataSource()
            // Seed repo: add entity and close (triggers writePending → inserts CREATE outbox row).
            val seedRepo = KafkaOutboxSqlRepository<Int, TestVersionedPerson>(dataSource, TestVersionedPersonTableDef)
            val entity = TestVersionedPerson(20).apply { firstName = "Freddie" }
            seedRepo.add(entity)
            // Close flushes via writePending, writing the entity row and one outbox row (CREATE=100).
            seedRepo.close()

            // Wait for the debounce flush triggered by seedRepo.close() to complete.
            runBlocking { waitForOutboxCount(dataSource, 1L) }
            val outboxRowsBeforeConflict = countOutboxRows(dataSource)

            val repo = KafkaOutboxSqlRepository<Int, TestVersionedPerson>(dataSource, TestVersionedPersonTableDef)
            try {
                // Bump the DB row's version externally so the in-memory entity (version=0) is stale.
                val exposedTable = ExposedTableInterpreter().interpret(TestVersionedPersonTableDef)
                val db = Database.connect(dataSource)
                transaction(db) {
                    @Suppress("UNCHECKED_CAST")
                    exposedTable.table.update({
                        (exposedTable.table.columns.first { it.name == "id" } as Column<Int>) eq 20
                    }) { row ->
                        @Suppress("UNCHECKED_CAST")
                        row[exposedTable.table.columns.first { it.name == "version" } as Column<Long>] = 1L
                    }
                }

                // Set the attempted in-block value without firing reactive events.
                val liveEntity = repo.findById(20).get()
                @Suppress("UNCHECKED_CAST")
                (liveEntity as ReactiveEntityBase<Int, TestVersionedPerson>).withEventsDisabled {
                    liveEntity.firstName = "FreddieMutation"
                }

                // Assemble the buffer: entity update with expectedVersion=0 (stale after the bump).
                val buffer = TransactionBuffer(repo)
                buffer.updates.add(PendingUpdate(liveEntity, expectedVersion = 0L))

                // The version conflict rolls back both the entity UPDATE and the outbox INSERT.
                shouldThrow<TransactionConflictException> {
                    repo.commitTransactionBuffer(buffer)
                }

                // Outbox count must not increase — the INSERT that the hook produced inside the
                // conflicting transaction block was rolled back atomically.
                countOutboxRows(dataSource) shouldBe outboxRowsBeforeConflict
            } finally {
                try {
                    repo.close()
                } catch (_: Exception) {
                }
                dataSource.close()
            }
        }

        "OutboxAtomicityTest debounced writePending conflict records no outbox row for the rejected update" {
            // The debounce flush accumulates optimistic-lock conflicts instead of throwing, so it
            // commits the non-conflicting writes and is not rolled back. The capture hook must exclude
            // the conflicted entity, whose UPDATE matched zero rows — otherwise the outbox would carry
            // a phantom UPDATE event for a change the database rejected.
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val repo = KafkaOutboxSqlRepository<Int, TestVersionedPerson>(dataSource, TestVersionedPersonTableDef)
            try {
                repo.add(TestVersionedPerson(30).apply { firstName = "Brian" })
                runBlocking { waitForOutboxCount(dataSource, 1L) } // CREATE row; DB version 0

                // Bump the DB row's version externally so the in-memory entity (version 0) is stale.
                val exposedTable = ExposedTableInterpreter().interpret(TestVersionedPersonTableDef)
                val db = Database.connect(dataSource)
                transaction(db) {
                    @Suppress("UNCHECKED_CAST")
                    exposedTable.table.update({
                        (exposedTable.table.columns.first { it.name == "id" } as Column<Int>) eq 30
                    }) { row ->
                        @Suppress("UNCHECKED_CAST")
                        row[exposedTable.table.columns.first { it.name == "version" } as Column<Long>] = 1L
                    }
                }

                // Mutating the stale entity schedules a debounced UPDATE that matches zero rows and is
                // recovered as a conflict. The Conflict event signals the flush + recovery completed.
                val conflict = CompletableDeferred<Unit>()
                repo.subscribe(CrudEvent.Type.CONFLICT) { conflict.complete(Unit) }
                repo.findById(30).get().firstName = "BrianMutation"
                runBlocking { withTimeout(3000) { conflict.await() } }

                // The conflicted UPDATE must not have produced an outbox row — only the CREATE remains.
                val updateRows =
                    transaction(db) {
                        OutboxEventTable.selectAll()
                            .where { OutboxEventTable.eventTypeCode eq CrudEvent.Type.UPDATE.code }
                            .count()
                    }
                updateRows shouldBe 0L
                countOutboxRows(dataSource) shouldBe 1L
            } finally {
                repo.close()
                dataSource.close()
            }
        }

        "OutboxAtomicityTest entity flush captures Crud and MutationEvent families in one run" {
            // A single flush produces outbox rows spanning the two event families reachable inside
            // the JDBC commit, each routed through the hook's generic event.type.code mapping:
            //   (a) CrudEvent CREATE code (100) — from transaction { r.add(entity) }
            //   (b) MutationEvent PROPERTY_CHANGED code (302) — an in-block property mutation is
            //       routed into buffer.deferredEvents and mapped to an outbox row via event.type.code
            // The mapping records EventType.code verbatim, so any code an in-flush event carries is
            // captured. Events a consumer publishes through the event publisher outside the flush are
            // not visible to this in-commit hook; those are captured by the publisher-backed path.
            val dataSource = H2ContainerSupport.buildH2DataSource()
            val repo = KafkaOutboxSqlRepository<Int, AudioItem>(dataSource, AudioItemSqlTableDef)
            try {
                // (a) CREATE path — outbox row with code 100.
                val item1 = MutableAudioItem(10, "Bohemian Rhapsody", "A Night at the Opera") as AudioItem
                transaction(repo) { r ->
                    r.add(item1)
                }

                // (b) PROPERTY_CHANGED path — mutate a property in a transaction so
                // PersistentRepositoryBase routes it into buffer.deferredEvents, producing an
                // outbox row with code 302 (MutationEvent.Type.PROPERTY_CHANGED).
                val item2 = MutableAudioItem(11, "Killer Queen", "Sheer Heart Attack") as AudioItem
                transaction(repo) { r -> r.add(item2) }
                transaction(repo) { r ->
                    (r.findById(11).get() as MutableAudioItem).title = "Killer Queen Updated"
                }

                val db = Database.connect(dataSource)
                val codes =
                    transaction(db) {
                        OutboxEventTable.selectAll().map { it[OutboxEventTable.eventTypeCode] }.toSet()
                    }

                codes shouldContainAll
                    setOf(
                        CrudEvent.Type.CREATE.code, // Crud family
                        MutationEvent.Type.PROPERTY_CHANGED.code // Mutation family
                    )
            } finally {
                repo.close()
                dataSource.close()
            }
        }
    }
}