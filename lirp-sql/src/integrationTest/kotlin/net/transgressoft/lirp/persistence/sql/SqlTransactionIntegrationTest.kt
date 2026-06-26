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

package net.transgressoft.lirp.persistence.sql

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.LirpTransactionException
import net.transgressoft.lirp.persistence.MutableAudioItem
import net.transgressoft.lirp.persistence.PendingUpdate
import net.transgressoft.lirp.persistence.TransactionBuffer
import net.transgressoft.lirp.persistence.TransactionConflictException
import net.transgressoft.lirp.persistence.sql.DatabaseTestSupport.databases
import net.transgressoft.lirp.persistence.sql.DatabaseTestSupport.withDatabaseTest
import net.transgressoft.lirp.persistence.transaction
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.DisplayName
import kotlin.time.Duration.Companion.seconds

// AudioItem Exposed column accessors
@Suppress("UNCHECKED_CAST")
private fun Table.audioItemIdColumn(): Column<Int> = columns.first { it.name == "id" } as Column<Int>

@Suppress("UNCHECKED_CAST")
private fun Table.audioItemTitleColumn(): Column<String> = columns.first { it.name == "title" } as Column<String>

/**
 * Multi-dialect integration tests for the transaction commit/rollback contract against real database
 * engines via Testcontainers.
 *
 * The pre-flush step of [transaction] drains pending ops to the store synchronously before the block
 * runs, providing a consistent baseline. These tests verify that property-mutation rollback leaves
 * no trace in the database from a concurrent connection, and that the `@Version` conflict detection
 * throws the correct typed exception against a real database engine.
 *
 * `withTests(databases)` covers PostgreSQL, MySQL, MariaDB, SQLite, and H2.
 */
@DisplayName("SqlRepository transaction commit/rollback integration")
internal class SqlTransactionIntegrationTest : FunSpec({

    // Opens a short-lived Exposed transaction against [dataSource] using the table backing
    // [tableDef], so a "third-party writer" or raw read can operate independently of the repo.
    fun <T> rawTransaction(
        dataSource: HikariDataSource,
        tableDef: SqlTableDef<*>,
        block: Table.() -> T
    ): T {
        val db = Database.connect(dataSource)
        val exposed = ExposedTableInterpreter().interpret(tableDef)
        return transaction(db) { exposed.table.block() }
    }

    // Polls the DB via rawTransaction until the row with [id] appears with at least [minVersion].
    suspend fun awaitVersionedInDb(
        dataSource: HikariDataSource,
        id: Int,
        minVersion: Long = 0L
    ) {
        eventually(10.seconds) {
            val version =
                rawTransaction(dataSource, TestVersionedPersonTableDef) {
                    @Suppress("UNCHECKED_CAST")
                    selectAll()
                        .where { (columns.first { it.name == "id" } as Column<Int>) eq id }
                        .singleOrNull()
                        ?.let { row ->
                            @Suppress("UNCHECKED_CAST")
                            row[columns.first { it.name == "version" } as Column<Long>]
                        }
                }
            checkNotNull(version) { "row with id=$id not yet in DB" }
            require(version >= minVersion) { "version $version < expected $minVersion" }
        }
    }

    context("transaction pre-flush — pending insert is committed to DB before the block runs") {
        withTests(databases) { db ->
            withDatabaseTest(db, AudioItemSqlTableDef) { dataSource ->
                val repo = AudioItemSqlRepository(dataSource)
                try {
                    // Add before the transaction — the insert is pending in the debounce queue.
                    repo.add(MutableAudioItem(10, "Bohemian Rhapsody", "A Night at the Opera"))

                    // transaction() drains the pending queue synchronously (pre-flush) before
                    // the block runs. The DB row is committed as a side effect of the pre-flush.
                    transaction(repo) { _ -> }

                    // Row is visible from a concurrent connection immediately after transaction().
                    val title =
                        rawTransaction(dataSource, AudioItemSqlTableDef) {
                            selectAll()
                                .where { audioItemIdColumn() eq 10 }
                                .singleOrNull()
                                ?.let { it[audioItemTitleColumn()] }
                        }
                    title shouldBe "Bohemian Rhapsody"
                } finally {
                    repo.close()
                }
            }
        }
    }

    context("transaction rollback — failed block leaves the pre-transaction DB row unchanged as seen from a concurrent connection") {
        withTests(databases) { db ->
            withDatabaseTest(db, AudioItemSqlTableDef) { dataSource ->
                // Seed: add the entity and flush synchronously via close().
                val seedRepo = AudioItemSqlRepository(dataSource)
                seedRepo.add(MutableAudioItem(20, "We Will Rock You", "News of the World"))
                seedRepo.close()

                val repo = AudioItemSqlRepository(dataSource)
                try {
                    shouldThrow<LirpTransactionException> {
                        transaction(repo) { r ->
                            // Mutate an already-loaded entity inside the block.
                            // The mutation is deferred to the event buffer and never writes to DB.
                            (r.findById(20).get() as MutableAudioItem).title = "mutated in block"
                            throw RuntimeException("injected failure — rollback expected")
                        }
                    }

                    // In-memory state reverted to pre-block value after rollback.
                    repo.findById(20).shouldBePresent { it.title shouldBe "We Will Rock You" }

                    // DB row still holds the pre-transaction value — the in-block mutation was
                    // never written to the DB because property mutations inside a transaction block
                    // are buffered for event-collapse, not for SQL persistence.
                    val title =
                        rawTransaction(dataSource, AudioItemSqlTableDef) {
                            selectAll()
                                .where { audioItemIdColumn() eq 20 }
                                .singleOrNull()
                                ?.let { it[audioItemTitleColumn()] }
                        }
                    title shouldBe "We Will Rock You"
                } finally {
                    repo.close()
                }
            }
        }
    }

    context("transaction @Version conflict on real DB — TransactionConflictException with in-block entity state in ConflictInfo") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestVersionedPersonTableDef) { dataSource ->
                val repo = SqlRepository<Int, TestVersionedPerson>(dataSource, TestVersionedPersonTableDef)
                try {
                    val entity = TestVersionedPerson(1).apply { firstName = "Alice" }
                    repo.add(entity)
                    // Flush the INSERT synchronously: close the seed repo and reopen.
                    repo.close()

                    val liveRepo = SqlRepository<Int, TestVersionedPerson>(dataSource, TestVersionedPersonTableDef)
                    try {
                        awaitVersionedInDb(dataSource, 1, minVersion = 0L)
                        val liveEntity = liveRepo.findById(1).get()

                        // Third-party writer bumps the DB row's version. In-memory entity keeps version=0.
                        rawTransaction(dataSource, TestVersionedPersonTableDef) {
                            @Suppress("UNCHECKED_CAST")
                            update({ (columns.first { it.name == "id" } as Column<Int>) eq 1 }) { row ->
                                @Suppress("UNCHECKED_CAST")
                                row[columns.first { it.name == "age" } as Column<Int>] = 99
                                @Suppress("UNCHECKED_CAST")
                                row[columns.first { it.name == "version" } as Column<Long>] = 1L
                            }
                        }

                        // Simulate the in-block mutation without reactive events (mirrors _txEventBuffer
                        // interception inside a real transaction block).
                        @Suppress("UNCHECKED_CAST")
                        (liveEntity as ReactiveEntityBase<Int, TestVersionedPerson>).withEventsDisabled {
                            liveEntity.firstName = "InBlockValue"
                        }

                        // Assemble the TransactionBuffer as commitTransactionBuffer expects it.
                        val buffer = TransactionBuffer(liveRepo)
                        buffer.updates.add(PendingUpdate(liveEntity, expectedVersion = 0L))

                        val ex =
                            shouldThrow<TransactionConflictException> {
                                liveRepo.commitTransactionBuffer(buffer)
                            }

                        // ConflictInfo.entity carries the value attempted inside the block (pre-rollback).
                        ex.conflicts.size shouldBe 1
                        @Suppress("UNCHECKED_CAST")
                        (ex.conflicts.single().entity as TestVersionedPerson).firstName shouldBe "InBlockValue"
                    } finally {
                        try {
                            liveRepo.close()
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                    try {
                        repo.close()
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    context("transaction onError handler receives block exception and suppresses propagation") {
        withTests(databases) { db ->
            withDatabaseTest(db, AudioItemSqlTableDef) { dataSource ->
                // Seed: add entity and flush synchronously via close().
                val seedRepo = AudioItemSqlRepository(dataSource)
                seedRepo.add(MutableAudioItem(30, "Another One Bites the Dust", "The Game"))
                seedRepo.close()

                val repo = AudioItemSqlRepository(dataSource)
                try {
                    var capturedThrowable: Throwable? = null

                    // The onError handler captures the raw block exception and suppresses propagation.
                    transaction(
                        repo,
                        onError = {
                            capturedThrowable = throwable
                        }
                    ) { r ->
                        (r.findById(30).get() as MutableAudioItem).title = "mutated"
                        throw RuntimeException("simulated block failure")
                    }

                    // Raw block exception delivered to onError — no wrapping on the handler path.
                    capturedThrowable?.message shouldBe "simulated block failure"
                    // In-memory state is reverted to the pre-block value after rollback.
                    repo.findById(30).shouldBePresent { it.title shouldBe "Another One Bites the Dust" }
                } finally {
                    repo.close()
                }
            }
        }
    }
})