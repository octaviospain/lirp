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

import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.StandardCrudEvent
import net.transgressoft.lirp.persistence.sql.DatabaseTestSupport.databases
import net.transgressoft.lirp.persistence.sql.DatabaseTestSupport.withDatabaseTest
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.DisplayName
import java.util.Collections
import kotlin.time.Duration.Companion.seconds

/**
 * Per-dialect optimistic locking integration tests covering the 5 empirical signals defined in
 * the phase VALIDATION.md: two-writer UPDATE race, collapse-window first-version preservation,
 * DELETE-defeat resurrection, mixed versioned/unversioned coexistence, and per-id batch-delete
 * partial success. `withTests(databases)` executes every context against PostgreSQL, MySQL 8.0,
 * and MariaDB 11 via Testcontainers.
 *
 * Each race scenario is made deterministic by using a "third-party writer" — a raw Exposed
 * transaction that bumps the row's version before our local mutation flushes. This mirrors a
 * concurrent process writing to the same row and avoids the inherent timing sensitivity of
 * two-coroutine setups against a single HikariDataSource.
 */
@DisplayName("SqlRepository Optimistic Locking Integration")
internal class SqlRepositoryOptimisticLockingIntegrationTest : FunSpec({

    // Helper — opens a short-lived Exposed transaction against [dataSource] using the same
    // [tableDef] the repo uses, so a "third writer" can bump a row's version independently.
    fun <T> rawTransaction(dataSource: HikariDataSource, tableDef: SqlTableDef<*>, block: Table.() -> T): T {
        val db = Database.connect(dataSource)
        val exposed = ExposedTableInterpreter().interpret(tableDef)
        return transaction(db) { exposed.table.block() }
    }

    // Polls the DB directly until the row with [id] has [minVersion] or higher. Used to
    // synchronize against repo debounce flushes before racing / third-party writes.
    suspend fun awaitVersionedInDb(dataSource: HikariDataSource, id: Int, minVersion: Long = 0L) {
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

    suspend fun awaitUnversionedInDb(dataSource: HikariDataSource, id: Int) {
        eventually(10.seconds) {
            val count =
                rawTransaction(dataSource, TestPersonTableDef) {
                    @Suppress("UNCHECKED_CAST")
                    selectAll()
                        .where { (columns.first { it.name == "id" } as Column<Int>) eq id }
                        .count()
                }
            require(count > 0L) { "row with id=$id not yet in DB" }
        }
    }

    // Two-writer UPDATE race: the loser gets a Conflict. ----
    //
    // Deterministic variant: the repo owns one writer; a raw-Exposed "third-party" transaction is
    // the second writer, committing its UPDATE (and bumping version) BEFORE our local mutation
    // flushes. Our UPDATE therefore sees version mismatch → OptimisticLockException → Conflict.
    context("concurrent UPDATE producing one Conflict and auto-reload to canonical state") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestVersionedPersonTableDef) { dataSource ->
                val conflicts = Collections.synchronizedList(mutableListOf<StandardCrudEvent.Conflict<*, *>>())
                val updateEvents = Collections.synchronizedList(mutableListOf<CrudEvent.Type>())

                val repo = SqlRepository<Int, TestVersionedPerson>(dataSource, TestVersionedPersonTableDef)
                repo.subscribe { event ->
                    if (event is StandardCrudEvent.Conflict<*, *>) conflicts.add(event)
                    if (event.isUpdate()) updateEvents.add(event.type)
                }

                repo.add(
                    TestVersionedPerson(1).apply {
                        firstName = "Alice"
                        age = 30
                    }
                )
                awaitVersionedInDb(dataSource, 1)

                // Third-party writer commits its UPDATE and bumps version to 1.
                rawTransaction(dataSource, TestVersionedPersonTableDef) {
                    @Suppress("UNCHECKED_CAST")
                    update({ (columns.first { it.name == "id" } as Column<Int>) eq 1 }) { row ->
                        @Suppress("UNCHECKED_CAST")
                        row[columns.first { it.name == "age" } as Column<Int>] = 99
                        @Suppress("UNCHECKED_CAST")
                        row[columns.first { it.name == "version" } as Column<Long>] = 1L
                    }
                }

                // Our local mutation — fires the local UPDATE event (optimistic read at call site),
                // but the subsequent flush fails the WHERE version=0 check → Conflict.
                repo.findById(1).get().firstName = "Alice-local"

                eventually(15.seconds) {
                    (updateEvents.size) shouldBe 1
                    conflicts.size shouldBe 1
                }
                // The conflict routes the canonical state into in-memory via applyRow.
                eventually(5.seconds) {
                    repo.findById(1).get().age shouldBe 99
                    repo.findById(1).get().version shouldBe 1L
                }
                conflicts.single().expectedVersion shouldBe 0L
                conflicts.single().actualVersion shouldBe 1L

                repo.close()
            }
        }
    }

    context("rapid mutations within debounce + third-party writer produce one Conflict with FIRST observed expectedVersion") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestVersionedPersonTableDef) { dataSource ->
                val conflicts = Collections.synchronizedList(mutableListOf<StandardCrudEvent.Conflict<Int, TestVersionedPerson>>())
                val repo = SqlRepository<Int, TestVersionedPerson>(dataSource, TestVersionedPersonTableDef)
                repo.subscribe { event ->
                    if (event is StandardCrudEvent.Conflict<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        conflicts.add(event as StandardCrudEvent.Conflict<Int, TestVersionedPerson>)
                    }
                }

                repo.add(
                    TestVersionedPerson(2).apply {
                        firstName = "Bob"
                        age = 25
                    }
                )
                awaitVersionedInDb(dataSource, 2)

                val bobLocal = repo.findById(2).get()

                // Third-party writer commits a concurrent change and bumps version to 1 BEFORE our
                // local mutations' debounce fires.
                rawTransaction(dataSource, TestVersionedPersonTableDef) {
                    @Suppress("UNCHECKED_CAST")
                    update({ (columns.first { it.name == "id" } as Column<Int>) eq 2 }) { row ->
                        @Suppress("UNCHECKED_CAST")
                        row[columns.first { it.name == "age" } as Column<Int>] = 99
                        @Suppress("UNCHECKED_CAST")
                        row[columns.first { it.name == "version" } as Column<Long>] = 1L
                    }
                }

                // Three rapid local mutations inside the debounce window — they must collapse into
                // a single UPDATE that carries the FIRST observed expectedVersion (0L).
                bobLocal.firstName = "Bob-v1"
                bobLocal.firstName = "Bob-v2"
                bobLocal.firstName = "Bob-v3"

                eventually(15.seconds) { conflicts.size shouldBe 1 }
                val conflict = conflicts.single()
                conflict.expectedVersion shouldBe 0L // FIRST observed version per D-08
                conflict.actualVersion shouldBe 1L // third writer's canonical version

                // Auto-reload converged to the third writer's canonical state.
                eventually(5.seconds) {
                    repo.findById(2).get().age shouldBe 99
                    repo.findById(2).get().version shouldBe 1L
                }

                repo.close()
            }
        }
    }

    // DELETE conflict resurrects entity in-memory
    context("DELETE losing to concurrent UPDATE produces Conflict and re-inserts canonical entity") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestVersionedPersonTableDef) { dataSource ->
                val conflicts = Collections.synchronizedList(mutableListOf<StandardCrudEvent.Conflict<*, *>>())
                val repo = SqlRepository<Int, TestVersionedPerson>(dataSource, TestVersionedPersonTableDef)
                repo.subscribe { event ->
                    if (event is StandardCrudEvent.Conflict<*, *>) conflicts.add(event)
                }

                repo.add(TestVersionedPerson(3).apply { firstName = "Charlie" })
                awaitVersionedInDb(dataSource, 3)

                // Third-party writer bumps the version BEFORE our delete fires.
                rawTransaction(dataSource, TestVersionedPersonTableDef) {
                    @Suppress("UNCHECKED_CAST")
                    update({ (columns.first { it.name == "id" } as Column<Int>) eq 3 }) { row ->
                        @Suppress("UNCHECKED_CAST")
                        row[columns.first { it.name == "age" } as Column<Int>] = 42
                        @Suppress("UNCHECKED_CAST")
                        row[columns.first { it.name == "version" } as Column<Long>] = 1L
                    }
                }

                val charlieLocal = repo.findById(3).get()
                repo.remove(charlieLocal)

                eventually(15.seconds) { conflicts.size shouldBe 1 }

                // entity is back in in-memory state with canonical values.
                eventually(5.seconds) {
                    repo.findById(3).isPresent shouldBe true
                    repo.findById(3).get().age shouldBe 42
                    repo.findById(3).get().version shouldBe 1L
                }

                repo.close()
            }
        }
    }

    context("mixed versioned and unversioned repos do not cross-contaminate") {
        withTests(databases) { db ->
            // Versioned and unversioned tables live in two separate HikariDataSources so that
            // schema setup, table drops, and row operations stay isolated.
            withDatabaseTest(db, TestVersionedPersonTableDef) { versionedDs ->
                withDatabaseTest(db, TestPersonTableDef) { unversionedDs ->
                    val versionedConflicts = Collections.synchronizedList(mutableListOf<StandardCrudEvent.Conflict<*, *>>())
                    val unversionedConflicts = Collections.synchronizedList(mutableListOf<StandardCrudEvent.Conflict<*, *>>())

                    val versioned = SqlRepository(versionedDs, TestVersionedPersonTableDef)
                    versioned.subscribe { event ->
                        if (event is StandardCrudEvent.Conflict<*, *>) versionedConflicts.add(event)
                    }

                    // Seed versioned repo then force a conflict via third-party writer.
                    versioned.add(TestVersionedPerson(1).apply { firstName = "V" })
                    awaitVersionedInDb(versionedDs, 1)
                    rawTransaction(versionedDs, TestVersionedPersonTableDef) {
                        @Suppress("UNCHECKED_CAST")
                        update({ (columns.first { it.name == "id" } as Column<Int>) eq 1 }) { row ->
                            @Suppress("UNCHECKED_CAST")
                            row[columns.first { it.name == "version" } as Column<Long>] = 1L
                        }
                    }
                    versioned.findById(1).get().firstName = "V-local"
                    eventually(15.seconds) { versionedConflicts.size shouldBe 1 }

                    // Exercise the unversioned repo — last-write-wins, no Conflict event ever, even
                    // under a "third writer" pattern (because there's no version column to check).
                    val unversioned = SqlRepository(unversionedDs, TestPersonTableDef)
                    unversioned.subscribe { event ->
                        if (event is StandardCrudEvent.Conflict<*, *>) unversionedConflicts.add(event)
                    }
                    val plain = TestPerson(9).apply { firstName = "U" }
                    unversioned.add(plain)
                    awaitUnversionedInDb(unversionedDs, 9)

                    // Overwrite the unversioned row externally, then mutate locally — no conflict
                    // ever fires because the unversioned repo does not check AND version=?.
                    rawTransaction(unversionedDs, TestPersonTableDef) {
                        @Suppress("UNCHECKED_CAST")
                        update({ (columns.first { it.name == "id" } as Column<Int>) eq 9 }) { row ->
                            @Suppress("UNCHECKED_CAST")
                            row[columns.first { it.name == "first_name" } as Column<String>] = "U-external"
                        }
                    }
                    plain.age = 100
                    plain.firstName = "U-prime"

                    // Wrap both assertions in the eventually block so a hypothetical regression that
                    // emits a Conflict on the unversioned path (after the flush drains) fails loudly
                    // instead of silently passing because we asserted before the delayed fire.
                    eventually(5.seconds) {
                        unversioned.findById(9).get().firstName shouldBe "U-prime"
                        unversionedConflicts.size shouldBe 0
                    }

                    versioned.close()
                    unversioned.close()
                }
            }
        }
    }

    // Per-dialect correctness is exercised by withTests(databases) wrapping
    // signals 1-4 above. Any dialect-specific deviation (e.g. MySQL gap-lock timing) surfaces as
    // a failing test on that dialect's row.
    context("PendingBatchDelete with one conflicting id still deletes the others and emits one Conflict") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestVersionedPersonTableDef) { dataSource ->
                val conflicts = Collections.synchronizedList(mutableListOf<StandardCrudEvent.Conflict<*, *>>())
                val repo = SqlRepository<Int, TestVersionedPerson>(dataSource, TestVersionedPersonTableDef)
                repo.subscribe { event ->
                    if (event is StandardCrudEvent.Conflict<*, *>) conflicts.add(event)
                }

                val entities = (1..5).map { i -> TestVersionedPerson(i).apply { firstName = "E$i" } }
                entities.forEach { repo.add(it) }
                // Wait for all five to persist before bumping id=3.
                (1..5).forEach { id -> awaitVersionedInDb(dataSource, id) }

                // Bump id=3's version externally so its versioned DELETE will fail.
                rawTransaction(dataSource, TestVersionedPersonTableDef) {
                    @Suppress("UNCHECKED_CAST")
                    update({ (columns.first { it.name == "id" } as Column<Int>) eq 3 }) { row ->
                        @Suppress("UNCHECKED_CAST")
                        row[columns.first { it.name == "version" } as Column<Long>] = 1L
                    }
                }

                repo.removeAll(entities.toList())

                eventually(15.seconds) { conflicts.size shouldBe 1 }
                // Tighten the regression net: the single conflict must be for id=3 specifically
                // (not just "one conflict happened somewhere"). Oldentity/newentity both track
                // id=3 on the batch-delete recovery path.
                conflicts.single().entities.keys shouldBe setOf(3)
                conflicts.single().expectedVersion shouldBe 0L
                // id=3 is back in memory (re-inserted from canonical row); others are gone.
                eventually(5.seconds) {
                    repo.findById(3).isPresent shouldBe true
                    listOf(1, 2, 4, 5).forEach { id -> repo.findById(id).isPresent shouldBe false }
                }

                repo.close()
            }
        }
    }

    // ---- Regression — two sequential non-conflicting UPDATEs must both succeed. ----
    //
    // Guards against a bug where `toParams(entity)` emits the pre-bump `entity.version` and the
    // UPDATE leaves the DB column at the expected value. The in-memory bump advances entity to
    // v1; the next mutation's `WHERE version = 1` predicate then misses (DB still at v0) and
    // throws a spurious OptimisticLockException. The fix rewrites the version column in the
    // stmt block to `expected + 1` so DB and in-memory stay in sync.
    context("sequential non-conflicting UPDATEs both succeed and advance DB version") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestVersionedPersonTableDef) { dataSource ->
                val conflicts = Collections.synchronizedList(mutableListOf<StandardCrudEvent.Conflict<*, *>>())
                val repo = SqlRepository<Int, TestVersionedPerson>(dataSource, TestVersionedPersonTableDef)
                repo.subscribe { event ->
                    if (event is StandardCrudEvent.Conflict<*, *>) conflicts.add(event)
                }

                repo.add(
                    TestVersionedPerson(1).apply {
                        firstName = "Alice"
                        age = 30
                    }
                )
                awaitVersionedInDb(dataSource, 1, minVersion = 0L)

                // First UPDATE: expectedVersion = 0 → UPDATE WHERE version = 0 → affects 1 row,
                // DB version advances to 1, in-memory version bumps to 1.
                repo.findById(1).get().firstName = "Bob"
                awaitVersionedInDb(dataSource, 1, minVersion = 1L)

                // Second UPDATE on the same entity: expectedVersion = 1 (captured from the now-bumped
                // in-memory state) → UPDATE WHERE version = 1 → must affect 1 row (not spuriously
                // throw OptimisticLockException) and advance DB version to 2.
                repo.findById(1).get().age = 31
                awaitVersionedInDb(dataSource, 1, minVersion = 2L)

                // Third UPDATE for extra confidence — catches a version "one-off" in the fix.
                repo.findById(1).get().firstName = "Carol"
                awaitVersionedInDb(dataSource, 1, minVersion = 3L)

                eventually(5.seconds) {
                    repo.findById(1).get().version shouldBe 3L
                    repo.findById(1).get().firstName shouldBe "Carol"
                    repo.findById(1).get().age shouldBe 31
                }
                conflicts.size shouldBe 0

                repo.close()
            }
        }
    }

    // ---- Regression — a mixed flush with one conflict must still commit non-conflicting ops. ----
    //
    // Before the conflict-accumulation refactor, executeUpdate throwing OptimisticLockException
    // rolled back the whole flush transaction and PersistentRepositoryBase dropped the drained
    // snapshot — silently losing non-conflicting inserts/updates/deletes queued in the same
    // window. This scenario ensures a single conflict no longer destroys unrelated work.
    context("flush with one conflict still commits non-conflicting ops in the same window") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestVersionedPersonTableDef) { dataSource ->
                val conflicts = Collections.synchronizedList(mutableListOf<StandardCrudEvent.Conflict<*, *>>())
                val repo = SqlRepository<Int, TestVersionedPerson>(dataSource, TestVersionedPersonTableDef)
                repo.subscribe { event ->
                    if (event is StandardCrudEvent.Conflict<*, *>) conflicts.add(event)
                }

                // Seed id=10 (will be the conflict target) and id=11 (will be deleted in the same flush).
                repo.add(TestVersionedPerson(10).apply { firstName = "TargetV0" })
                repo.add(TestVersionedPerson(11).apply { firstName = "Victim" })
                awaitVersionedInDb(dataSource, 10)
                awaitVersionedInDb(dataSource, 11)

                // Third-party writer bumps id=10's version so our local update will conflict.
                rawTransaction(dataSource, TestVersionedPersonTableDef) {
                    @Suppress("UNCHECKED_CAST")
                    update({ (columns.first { it.name == "id" } as Column<Int>) eq 10 }) { row ->
                        @Suppress("UNCHECKED_CAST")
                        row[columns.first { it.name == "first_name" } as Column<String>] = "TargetV1-external"
                        @Suppress("UNCHECKED_CAST")
                        row[columns.first { it.name == "version" } as Column<Long>] = 1L
                    }
                }

                // Same flush window: add id=12 (insert), mutate id=10 (will conflict),
                // remove id=11 (delete). The conflict must not roll back the insert or delete.
                repo.add(TestVersionedPerson(12).apply { firstName = "NewFriend" })
                repo.findById(10).get().firstName = "TargetLocal"
                repo.remove(repo.findById(11).get())

                eventually(15.seconds) { conflicts.size shouldBe 1 }
                conflicts.single().entities.keys shouldBe setOf(10)

                // Non-conflicting ops committed despite the conflict on id=10.
                awaitVersionedInDb(dataSource, 12)
                eventually(5.seconds) {
                    repo.findById(12).isPresent shouldBe true
                    repo.findById(11).isPresent shouldBe false
                }
                // Raw DB check — id=11 row really was deleted; id=10 reflects third-party state.
                rawTransaction(dataSource, TestVersionedPersonTableDef) {
                    @Suppress("UNCHECKED_CAST")
                    val row11Count = selectAll().where { (columns.first { it.name == "id" } as Column<Int>) eq 11 }.count()
                    row11Count shouldBe 0L
                }

                repo.close()
            }
        }
    }

    // ---- Regression — recovery paths must not emit spurious CREATE/DELETE events. ----
    //
    // removeFromMemoryOnly (Case 1: third-party deletion) and addToMemoryOnly (Case 2b: defeated
    // DELETE) both call through VolatileRepository.remove/add, which emit Delete/Create events.
    // Recovery wraps each call in disableEvents/activateEvents so subscribers see only the
    // Conflict, not Delete+Conflict or Create+Conflict (which would be indistinguishable from
    // ordinary CRUD).
    context("recovery does not emit spurious Create/Delete events alongside Conflict") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestVersionedPersonTableDef) { dataSource ->
                val allEvents = Collections.synchronizedList(mutableListOf<CrudEvent.Type>())
                val conflicts = Collections.synchronizedList(mutableListOf<StandardCrudEvent.Conflict<*, *>>())
                val repo = SqlRepository<Int, TestVersionedPerson>(dataSource, TestVersionedPersonTableDef)
                repo.subscribe { event ->
                    if (event is StandardCrudEvent.Conflict<*, *>) conflicts.add(event)
                }

                repo.add(TestVersionedPerson(20).apply { firstName = "ToBeDeleted" })
                awaitVersionedInDb(dataSource, 20)

                // Third-party DELETE — our local UPDATE will hit canonicalRow == null (Case 1).
                rawTransaction(dataSource, TestVersionedPersonTableDef) {
                    deleteWhere {
                        @Suppress("UNCHECKED_CAST")
                        (columns.first { it.name == "id" } as Column<Int>) eq 20
                    }
                }

                // Start event tape AFTER seeding and third-party DELETE (so we only observe the
                // recovery window).
                repo.subscribe { event -> allEvents.add(event.type) }

                // Force a local mutation → our flush fails the WHERE id=20 AND version=0 check,
                // canonicalRow lookup returns null → Case 1 removeFromMemoryOnly path.
                repo.findById(20).get().firstName = "LocalChange"

                eventually(15.seconds) { conflicts.size shouldBe 1 }
                eventually(5.seconds) { repo.findById(20).isPresent shouldBe false }

                // Filter to events from the recovery window only (post-third-party-delete).
                // A pre-fix regression would include CrudEvent.Type.DELETE here.
                val recoveryEvents = allEvents.filter { it == CrudEvent.Type.DELETE || it == CrudEvent.Type.CONFLICT }
                recoveryEvents shouldBe listOf(CrudEvent.Type.CONFLICT)

                repo.close()
            }
        }
    }
})