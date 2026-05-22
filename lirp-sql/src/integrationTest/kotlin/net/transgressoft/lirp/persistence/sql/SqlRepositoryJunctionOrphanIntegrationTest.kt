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

import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName

/**
 * Regression test for #202 — `SqlRepository`'s `clear()` and per-id `remove()` paths must wipe
 * junction rows before the parent rows so the operation is correct even when the deferred
 * foreign-key constraints have **not yet been installed** via
 * [SqlRepository.installJunctionForeignKeys]. Without the explicit junction cleanup, orphan
 * rows survive in the junction table and a subsequent FK install fails because the constraint
 * cannot be enforced against the existing data.
 *
 * The test deliberately skips the `installJunctionForeignKeys()` call before exercising
 * `clear()` and `remove()`, asserts the junction table is empty after each, and only then
 * installs the FK — proving the cleanup was sufficient to make FK installation succeed.
 *
 * Runs against PostgreSQL via Testcontainers — the FK-installation window is most relevant on
 * dialects that distinguish "no FK constraint" from "FK with ON DELETE CASCADE", which all three
 * server-class dialects do.
 */
@DisplayName("SqlRepository Junction Orphan Integration")
class SqlRepositoryJunctionOrphanIntegrationTest : StringSpec({

    fun resetSchema(dataSource: HikariDataSource) {
        // DROP TABLE IF EXISTS already handles the "table missing" case across PostgreSQL, MySQL,
        // MariaDB and SQLite, so any propagated SQLException is a real setup failure that must
        // surface — silently swallowing it would mask schema corruption and let regression tests
        // pass for the wrong reason.
        for (sql in listOf(
            "DROP TABLE IF EXISTS fk_parent_children",
            "DROP TABLE IF EXISTS fk_parents",
            "DROP TABLE IF EXISTS fk_children"
        )) {
            dataSource.connection.use { conn ->
                conn.createStatement().use { stmt -> stmt.execute(sql) }
            }
        }
    }

    fun junctionRowCount(dataSource: HikariDataSource, parentIdFilter: Int? = null): Long {
        val where = if (parentIdFilter != null) " WHERE parent_id = $parentIdFilter" else ""
        return dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM fk_parent_children$where").use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        }
    }

    "[SqlRepositoryJunctionOrphan] clear path removes junction rows before parent table when FKs not yet installed" {
        val dataSource = PostgresContainerSupport.buildDataSource()
        try {
            resetSchema(dataSource)
            // No FK install on the scalar column either — keep the scenario focused on junction.
            FkScalarFkInstaller.setupNone(dataSource)

            val childRepo = SqlRepository(dataSource, FkChildTableDef)
            val parentRepo = SqlRepository(dataSource, FkParentTableDef)
            // Deliberately do NOT call parentRepo.installJunctionForeignKeys() — this is the
            // load-bearing step that exercises the FK-not-yet-installed window.

            listOf(10, 20, 30).forEach { id -> childRepo.add(FkChild(id).apply { name = "C$id" }) }
            childRepo.close()

            val parent1 = FkParent(1).apply { name = "P1" }
            parent1.childIds = listOf(10, 20)
            val parent2 = FkParent(2).apply { name = "P2" }
            parent2.childIds = listOf(10, 30)
            parentRepo.add(parent1)
            parentRepo.add(parent2)
            parentRepo.close()

            // Sanity: four junction rows exist
            junctionRowCount(dataSource) shouldBe 4L

            // Clear via a fresh repo so we exercise the clear() path through writePending.
            val clearRepo = SqlRepository(dataSource, FkParentTableDef)
            clearRepo.clear()
            clearRepo.close()

            // Junction rows must be gone — #202 fix wipes the junction table before the parent.
            junctionRowCount(dataSource) shouldBe 0L

            // Verify the parent table is also empty (the existing behaviour).
            dataSource.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT COUNT(*) FROM fk_parents").use { rs ->
                        rs.next()
                        rs.getLong(1) shouldBe 0L
                    }
                }
            }
        } finally {
            dataSource.close()
        }
    }

    "[SqlRepositoryJunctionOrphan] remove path removes junction rows for the deleted id only" {
        val dataSource = PostgresContainerSupport.buildDataSource()
        try {
            resetSchema(dataSource)
            FkScalarFkInstaller.setupNone(dataSource)

            val childRepo = SqlRepository(dataSource, FkChildTableDef)
            val parentRepo = SqlRepository(dataSource, FkParentTableDef)
            // Deliberately skip installJunctionForeignKeys() — same FK-not-yet-installed window.

            listOf(10, 20, 30).forEach { id -> childRepo.add(FkChild(id).apply { name = "C$id" }) }
            childRepo.close()

            val parent1 = FkParent(1).apply { name = "P1" }
            parent1.childIds = listOf(10, 20)
            val parent2 = FkParent(2).apply { name = "P2" }
            parent2.childIds = listOf(10, 30)
            parentRepo.add(parent1)
            parentRepo.add(parent2)
            parentRepo.close()

            junctionRowCount(dataSource) shouldBe 4L

            val removeRepo = SqlRepository(dataSource, FkParentTableDef)
            val parent1Reloaded = removeRepo.findById(1).get()
            removeRepo.remove(parent1Reloaded)
            removeRepo.close()

            // Junction rows for parent1 are gone; parent2's are intact
            junctionRowCount(dataSource, parentIdFilter = 1) shouldBe 0L
            junctionRowCount(dataSource, parentIdFilter = 2) shouldBe 2L
        } finally {
            dataSource.close()
        }
    }

    "[SqlRepositoryJunctionOrphan] installJunctionForeignKeys succeeds after clear and remove" {
        val dataSource = PostgresContainerSupport.buildDataSource()
        try {
            resetSchema(dataSource)
            FkScalarFkInstaller.setupNone(dataSource)

            val childRepo = SqlRepository(dataSource, FkChildTableDef)
            val parentRepo = SqlRepository(dataSource, FkParentTableDef)
            // Deliberately skip installJunctionForeignKeys() — load-bearing.

            listOf(10, 20, 30).forEach { id -> childRepo.add(FkChild(id).apply { name = "C$id" }) }
            childRepo.close()

            val parent1 = FkParent(1).apply { name = "P1" }
            parent1.childIds = listOf(10, 20)
            val parent2 = FkParent(2).apply { name = "P2" }
            parent2.childIds = listOf(10, 30)
            parentRepo.add(parent1)
            parentRepo.add(parent2)
            parentRepo.close()

            // Mix of clear + remove flows to exercise both fix sites
            val mutateRepo = SqlRepository(dataSource, FkParentTableDef)
            mutateRepo.remove(mutateRepo.findById(1).get())
            mutateRepo.close()

            val clearRepo = SqlRepository(dataSource, FkParentTableDef)
            clearRepo.clear()
            clearRepo.close()

            junctionRowCount(dataSource) shouldBe 0L

            // Bottom line: with no orphan rows surviving, installJunctionForeignKeys() succeeds.
            val installRepo = SqlRepository(dataSource, FkParentTableDef)
            installRepo.installJunctionForeignKeys()
            installRepo.close()
        } finally {
            dataSource.close()
        }
    }
})