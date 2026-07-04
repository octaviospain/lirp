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

import net.transgressoft.lirp.persistence.sql.DatabaseTestSupport.databases
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies the contract that junction-row mutations are invisible to optimistic-lock versioning
 * across all four supported dialects. Adds, removes, and reorders against the junction
 * table (`fk_parent_children`) — performed via raw SQL to simulate an external writer — must NOT
 * bump `fk_parents.version`. Scalar property mutations routed through `SqlRepository` MUST bump
 * `fk_parents.version` exactly once per flush.
 */
@DisplayName("SqlRepository Versioning x Junction Integration")
internal class SqlRepositoryVersioningJunctionIntegrationTest : FunSpec({

    fun queryParentVersion(dataSource: HikariDataSource, parentId: Int): Long =
        dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT version FROM fk_parents WHERE id = ?").use { ps ->
                ps.setInt(1, parentId)
                ps.executeQuery().use {
                    require(it.next()) { "no fk_parents row for id=$parentId" }
                    it.getLong(1)
                }
            }
        }

    /**
     * Convenience setup: pre-creates the schema with `setupNone` (no scalar FK), seeds [parentId]
     * with `version = 0`, and seeds the supplied [childIds] in `fk_children`. Returns the open
     * data source so the test can issue subsequent raw-SQL or LIRP operations against it.
     */
    fun seedParentAndChildren(
        dataSource: HikariDataSource,
        parentId: Int,
        parentName: String,
        childIds: List<Int>
    ) {
        DatabaseTestSupport.dropTables(dataSource, "fk_parent_children", "fk_parents", "fk_children")
        FkScalarFkInstaller.setupNone(dataSource)

        val childRepo = SqlRepository(dataSource, FkChildTableDef)
        childIds.forEach { id -> childRepo.add(FkChild(id).apply { name = "C$id" }) }
        childRepo.close()

        val parentRepo = SqlRepository(dataSource, FkParentTableDef)
        parentRepo.installJunctionForeignKeys()
        parentRepo.add(FkParent(parentId).apply { name = parentName })
        parentRepo.close()
    }

    context("adding a junction row does not bump parent version") {
        withTests(databases) { db ->
            val dataSource = db.buildDataSource()
            try {
                seedParentAndChildren(dataSource, parentId = 1, parentName = "P1", childIds = listOf(10))
                queryParentVersion(dataSource, 1) shouldBe 0L

                // External writer inserts a junction row.
                dataSource.connection.use { conn ->
                    conn.prepareStatement(
                        "INSERT INTO fk_parent_children (parent_id, item_id, position) VALUES (?, ?, ?)"
                    ).use { ps ->
                        ps.setInt(1, 1)
                        ps.setInt(2, 10)
                        ps.setInt(3, 0)
                        ps.executeUpdate()
                    }
                }

                queryParentVersion(dataSource, 1) shouldBe 0L

                // Reload via LIRP — the parent's in-memory version must still be 0 and the loaded
                // childIds must reflect the externally-inserted junction row.
                val verify = SqlRepository(dataSource, FkParentTableDef)
                verify.findById(1).shouldBePresent {
                    it.version shouldBe 0L
                    it.childIds shouldBe listOf(10)
                }
                verify.close()
            } finally {
                dataSource.close()
            }
        }
    }

    context("removing a junction row does not bump parent version") {
        withTests(databases) { db ->
            val dataSource = db.buildDataSource()
            try {
                seedParentAndChildren(dataSource, parentId = 2, parentName = "P2", childIds = listOf(10, 20))
                // Seed two junction rows.
                dataSource.connection.use { conn ->
                    conn.prepareStatement(
                        "INSERT INTO fk_parent_children (parent_id, item_id, position) VALUES (?, ?, ?)"
                    ).use { ps ->
                        ps.setInt(1, 2)
                        ps.setInt(2, 10)
                        ps.setInt(3, 0)
                        ps.executeUpdate()
                        ps.setInt(1, 2)
                        ps.setInt(2, 20)
                        ps.setInt(3, 1)
                        ps.executeUpdate()
                    }
                }
                queryParentVersion(dataSource, 2) shouldBe 0L

                // External writer removes one junction row.
                dataSource.connection.use { conn ->
                    conn.prepareStatement(
                        "DELETE FROM fk_parent_children WHERE parent_id = ? AND item_id = ?"
                    ).use { ps ->
                        ps.setInt(1, 2)
                        ps.setInt(2, 10)
                        ps.executeUpdate()
                    }
                }

                queryParentVersion(dataSource, 2) shouldBe 0L

                val verify = SqlRepository(dataSource, FkParentTableDef)
                verify.findById(2).shouldBePresent {
                    it.version shouldBe 0L
                    it.childIds shouldBe listOf(20)
                }
                verify.close()
            } finally {
                dataSource.close()
            }
        }
    }

    context("reordering junction rows does not bump parent version") {
        withTests(databases) { db ->
            val dataSource = db.buildDataSource()
            try {
                seedParentAndChildren(dataSource, parentId = 3, parentName = "P3", childIds = listOf(10, 20))
                dataSource.connection.use { conn ->
                    conn.prepareStatement(
                        "INSERT INTO fk_parent_children (parent_id, item_id, position) VALUES (?, ?, ?)"
                    ).use { ps ->
                        ps.setInt(1, 3)
                        ps.setInt(2, 10)
                        ps.setInt(3, 0)
                        ps.executeUpdate()
                        ps.setInt(1, 3)
                        ps.setInt(2, 20)
                        ps.setInt(3, 1)
                        ps.executeUpdate()
                    }
                }
                queryParentVersion(dataSource, 3) shouldBe 0L

                // External writer swaps positions.
                dataSource.connection.use { conn ->
                    conn.prepareStatement(
                        "UPDATE fk_parent_children SET position = ? WHERE parent_id = ? AND item_id = ?"
                    ).use { ps ->
                        ps.setInt(1, 1)
                        ps.setInt(2, 3)
                        ps.setInt(3, 10)
                        ps.executeUpdate()
                        ps.setInt(1, 0)
                        ps.setInt(2, 3)
                        ps.setInt(3, 20)
                        ps.executeUpdate()
                    }
                }

                queryParentVersion(dataSource, 3) shouldBe 0L

                val verify = SqlRepository(dataSource, FkParentTableDef)
                verify.findById(3).shouldBePresent {
                    it.version shouldBe 0L
                    // Reload sorts by position; expect [20, 10] after the swap.
                    it.childIds shouldBe listOf(20, 10)
                }
                verify.close()
            } finally {
                dataSource.close()
            }
        }
    }

    context("scalar property mutation does bump parent version exactly once per flush") {
        withTests(databases) { db ->
            val dataSource = db.buildDataSource()
            try {
                seedParentAndChildren(dataSource, parentId = 4, parentName = "P4", childIds = emptyList())
                queryParentVersion(dataSource, 4) shouldBe 0L

                val parentRepo = SqlRepository(dataSource, FkParentTableDef)
                try {
                    parentRepo.installJunctionForeignKeys()
                    // Mutate the scalar `name` reactive property — emits a MutationEvent that the
                    // repository's per-entity subscription processes asynchronously, enqueues a
                    // PendingUpdate, and the debounce flush commits within ~100 ms (max 1 s).
                    parentRepo.findById(4).shouldBePresent { it.name = "P4-mutated" }

                    // Poll the DB until the debounce flush has committed the version bump.
                    eventually(10.seconds) { queryParentVersion(dataSource, 4) shouldBe 1L }

                    parentRepo.findById(4).shouldBePresent {
                        it.name shouldBe "P4-mutated"
                        it.version shouldBe 1L
                    }
                } finally {
                    parentRepo.close()
                }
            } finally {
                dataSource.close()
            }
        }
    }
})