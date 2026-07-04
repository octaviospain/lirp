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
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.optional.shouldNotBePresent
import io.kotest.matchers.shouldBe

/**
 * H2-backed unit tests verifying the soft-delete persistence contract for [SoftDeletableVersionedTrack]:
 * - soft-delete persists `deleted_at` via UPDATE and bumps `@Version`; the row is not hard-deleted.
 * - SQL load is unfiltered (`SELECT *` with no `deleted_at IS NULL` predicate) — deleted rows
 *   enter memory — while the default read layer excludes them.
 * - restore persists `deleted_at = NULL` and bumps the version again.
 */
internal class SoftDeleteSqlTest : StringSpec({

    extension(IsolatedReactiveScope)

    val tableDef = SoftDeletableVersionedTrack_LirpTableDef

    "SoftDeleteSqlTest soft-delete persists deleted_at via UPDATE and does not hard-delete the row" {
        DatabaseTestSupport.withDatabaseTest(DbConfig("H2") { H2ContainerSupport.buildH2DataSource() }, tableDef) { dataSource ->
            val repo = SqlRepository<Int, SoftDeletableVersionedTrack>(dataSource, tableDef)
            val track =
                SoftDeletableVersionedTrack(id = 1).apply {
                    title = "Ghost Track"
                    artist = "LIRP"
                }
            repo.add(track)
            repo.softDelete(track)

            // Poll for the persisted row while the repo is still open: softDelete propagates to the
            // SQL write pipeline asynchronously, so a raw read right after close() would race delivery.
            DatabaseTestSupport.awaitRow(dataSource, tableDef.tableName, 1, "deleted_at", "version") { row ->
                // Raw DB check: row still exists with non-null deleted_at (not hard-deleted)
                row["deleted_at"].shouldNotBeNull()
            }
            repo.close()
        }
    }

    "SoftDeleteSqlTest soft-delete bumps @Version on UPDATE" {
        DatabaseTestSupport.withDatabaseTest(DbConfig("H2") { H2ContainerSupport.buildH2DataSource() }, tableDef) { dataSource ->
            val repo = SqlRepository<Int, SoftDeletableVersionedTrack>(dataSource, tableDef)
            val track =
                SoftDeletableVersionedTrack(id = 2).apply {
                    title = "Versioned Track"
                    artist = "LIRP"
                }
            repo.add(track)
            repo.close()

            // Open a second repo to get the initial version
            val repo2 = SqlRepository<Int, SoftDeletableVersionedTrack>(dataSource, tableDef)
            val loaded = repo2.findById(2).get()
            val versionBeforeSoftDelete = loaded.version
            repo2.softDelete(loaded)
            // The deletedAt mutation propagates to the SQL write pipeline asynchronously and is
            // persisted by the debounced background writer. Poll for the persisted version bump via
            // the shared helper (bounded by PERSISTED_ROW_POLL) instead of a fixed sleep or a
            // hand-rolled window, so this assertion shares the same generous CI-safe budget as every
            // other persistence poll.
            DatabaseTestSupport.awaitRow(dataSource, tableDef.tableName, 2, "version", "deleted_at") { row ->
                val dbVersion = (row["version"] as? Long) ?: (row["version"] as? Number)?.toLong()
                dbVersion.shouldNotBeNull()
                dbVersion shouldBe (versionBeforeSoftDelete + 1L)
            }
            repo2.close()
        }
    }

    "SoftDeleteSqlTest load is unfiltered — deleted rows enter memory but excluded from default reads" {
        DatabaseTestSupport.withDatabaseTest(DbConfig("H2") { H2ContainerSupport.buildH2DataSource() }, tableDef) { dataSource ->
            // Seed one active and one soft-deleted track, then reload
            val repo = SqlRepository<Int, SoftDeletableVersionedTrack>(dataSource, tableDef)
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
            repo.close()

            // Reload — all rows must enter memory (load-all-then-filter)
            val repo2 = SqlRepository<Int, SoftDeletableVersionedTrack>(dataSource, tableDef)

            // includeDeleted sees both entities (no DB-side filter on deleted_at)
            val allInMemory = repo2.query { includeDeleted() }.map { it.id }.toSet()
            allInMemory shouldBe setOf(3, 4)

            // Default read path excludes the soft-deleted entity
            repo2.findById(3).shouldBePresent()
            repo2.findById(4).shouldNotBePresent()
            // size() counts only active entities
            repo2.size() shouldBe 1

            repo2.close()
        }
    }

    "SoftDeleteSqlTest restore persists deleted_at = NULL and bumps @Version" {
        DatabaseTestSupport.withDatabaseTest(DbConfig("H2") { H2ContainerSupport.buildH2DataSource() }, tableDef) { dataSource ->
            val repo = SqlRepository<Int, SoftDeletableVersionedTrack>(dataSource, tableDef)
            val track =
                SoftDeletableVersionedTrack(id = 5).apply {
                    title = "Restored Track"
                    artist = "LIRP"
                }
            repo.add(track)
            repo.softDelete(track)
            repo.close()

            val repoAfterSoftDelete = SqlRepository<Int, SoftDeletableVersionedTrack>(dataSource, tableDef)
            val softDeleted = repoAfterSoftDelete.query { includeDeleted() }.find { it.id == 5 }
            softDeleted.shouldNotBeNull()
            val versionAfterSoftDelete = softDeleted.version
            repoAfterSoftDelete.restore(softDeleted)

            // Poll for the restore to land while the repo is still open: restore() propagates to the
            // SQL write pipeline asynchronously, so a raw read right after close() would race delivery.
            DatabaseTestSupport.awaitRow(dataSource, tableDef.tableName, 5, "deleted_at", "version") { row ->
                // Row in DB: deleted_at is NULL and version was bumped
                row["deleted_at"].shouldBeNull()
                val dbVersion = (row["version"] as? Long) ?: (row["version"] as? Number)?.toLong()
                dbVersion.shouldNotBeNull()
                dbVersion shouldBe (versionAfterSoftDelete + 1L)
            }
            repoAfterSoftDelete.close()

            // And the entity is visible via default reads again
            val finalRepo = SqlRepository<Int, SoftDeletableVersionedTrack>(dataSource, tableDef)
            finalRepo.findById(5).shouldBePresent { it.deletedAt.shouldBeNull() }
            finalRepo.close()
        }
    }
})