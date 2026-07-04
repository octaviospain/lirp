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

import net.transgressoft.lirp.persistence.query.query
import net.transgressoft.lirp.persistence.sql.DatabaseTestSupport.awaitSubscriptionReady
import net.transgressoft.lirp.persistence.sql.DatabaseTestSupport.databases
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.nondeterministic.eventuallyConfig
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.optional.shouldNotBePresent
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Polling config for soft-delete persistence assertions. Reactive mutation events are dispatched
 * asynchronously and flushed via a debounced write pipeline, so DB reads retry until the row
 * reflects the expected state.
 */
private val persistedRowPoll =
    eventuallyConfig {
        duration = 30.seconds
        interval = 200.milliseconds
    }

/**
 * Cross-dialect integration tests verifying the soft-delete persistence contract for
 * [SoftDeletableVersionedTrack] across PostgreSQL, MySQL, MariaDB, SQLite, and H2.
 *
 * Complements [SoftDeleteSqlTest] (H2 unit tests with synchronous close-flush) by running the
 * same contracts through the async debounced write pipeline — mutations go through the reactive
 * subscription chain and are polled via [eventually] rather than flushed immediately on close.
 */
internal class SoftDeleteSqlIntegrationTest : FunSpec({

    val tableDef = SoftDeletableVersionedTrack_LirpTableDef

    context("soft-delete persists deleted_at via UPDATE and does not hard-delete the row") {
        withTests(databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, tableDef) { ds ->
                // A fixed delay is used here because no observable readiness signal is reachable
                // from the test without production changes: the per-entity persistence collector
                // starts on a launched coroutine, and there is no subscriber-count API to poll.
                val repo = SqlRepository<Int, SoftDeletableVersionedTrack>(ds, tableDef)
                try {
                    val track =
                        SoftDeletableVersionedTrack(id = 1).apply {
                            title = "Ghost Track"
                            artist = "LIRP"
                        }
                    repo.add(track)
                    awaitSubscriptionReady()
                    repo.softDelete(track)

                    eventually(persistedRowPoll) {
                        val row = DatabaseTestSupport.readRow(ds, tableDef.tableName, 1, "deleted_at")
                        row.shouldNotBeNull()
                        row["deleted_at"].shouldNotBeNull()
                    }
                } finally {
                    repo.close()
                }
            }
        }
    }

    context("soft-delete bumps @Version on UPDATE") {
        withTests(databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, tableDef) { ds ->
                val repo = SqlRepository<Int, SoftDeletableVersionedTrack>(ds, tableDef)
                try {
                    val track =
                        SoftDeletableVersionedTrack(id = 2).apply {
                            title = "Versioned Track"
                            artist = "LIRP"
                        }
                    repo.add(track)
                } finally {
                    repo.close()
                }

                val repo2 = SqlRepository<Int, SoftDeletableVersionedTrack>(ds, tableDef)
                try {
                    awaitSubscriptionReady()
                    val loaded = repo2.findById(2).get()
                    val versionBeforeSoftDelete = loaded.version
                    repo2.softDelete(loaded)

                    eventually(persistedRowPoll) {
                        val row = DatabaseTestSupport.readRow(ds, tableDef.tableName, 2, "version", "deleted_at")
                        row.shouldNotBeNull()
                        val dbVersion = (row["version"] as? Long) ?: (row["version"] as? Number)?.toLong()
                        dbVersion.shouldNotBeNull()
                        dbVersion shouldBe (versionBeforeSoftDelete + 1L)
                    }
                } finally {
                    repo2.close()
                }
            }
        }
    }

    context("load is unfiltered — deleted rows enter memory but excluded from default reads") {
        withTests(databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, tableDef) { ds ->
                val repo = SqlRepository<Int, SoftDeletableVersionedTrack>(ds, tableDef)
                try {
                    val active =
                        SoftDeletableVersionedTrack(id = 3).apply {
                            title = "Active Track"
                            artist = "LIRP"
                        }
                    val ghost =
                        SoftDeletableVersionedTrack(id = 4).apply {
                            title = "Ghost Track"
                            artist = "LIRP"
                        }
                    repo.add(active)
                    repo.add(ghost)
                    repo.softDelete(ghost)
                } finally {
                    repo.close()
                }

                // Reload — all rows (including deleted) must enter memory
                val repo2 = SqlRepository<Int, SoftDeletableVersionedTrack>(ds, tableDef)
                try {
                    // includeDeleted sees both entities (no DB-side filter on deleted_at)
                    val allInMemory = repo2.query { includeDeleted() }.map { it.id }.toSet()
                    allInMemory shouldBe setOf(3, 4)

                    // Default read path excludes the soft-deleted entity
                    repo2.findById(3).shouldBePresent()
                    repo2.findById(4).shouldNotBePresent()
                    repo2.size() shouldBe 1
                } finally {
                    repo2.close()
                }
            }
        }
    }

    context("restore persists deleted_at = NULL and bumps @Version") {
        withTests(databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, tableDef) { ds ->
                val repo = SqlRepository<Int, SoftDeletableVersionedTrack>(ds, tableDef)
                try {
                    val track =
                        SoftDeletableVersionedTrack(id = 5).apply {
                            title = "Restored Track"
                            artist = "LIRP"
                        }
                    repo.add(track)
                    repo.softDelete(track)
                } finally {
                    repo.close()
                }

                val repoAfterSoftDelete = SqlRepository<Int, SoftDeletableVersionedTrack>(ds, tableDef)
                try {
                    awaitSubscriptionReady()
                    val softDeleted = repoAfterSoftDelete.query { includeDeleted() }.find { it.id == 5 }
                    softDeleted.shouldNotBeNull()
                    val versionAfterSoftDelete = softDeleted.version
                    repoAfterSoftDelete.restore(softDeleted)

                    eventually(persistedRowPoll) {
                        val row = DatabaseTestSupport.readRow(ds, tableDef.tableName, 5, "deleted_at", "version")
                        row.shouldNotBeNull()
                        row["deleted_at"].shouldBeNull()
                        val dbVersion = (row["version"] as? Long) ?: (row["version"] as? Number)?.toLong()
                        dbVersion.shouldNotBeNull()
                        dbVersion shouldBe (versionAfterSoftDelete + 1L)
                    }
                } finally {
                    repoAfterSoftDelete.close()
                }

                // Entity visible via default reads again
                val finalRepo = SqlRepository<Int, SoftDeletableVersionedTrack>(ds, tableDef)
                try {
                    finalRepo.findById(5).shouldBePresent { it.deletedAt.shouldBeNull() }
                } finally {
                    finalRepo.close()
                }
            }
        }
    }
})