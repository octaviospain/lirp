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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import java.sql.SQLException

/**
 * End-to-end verification that the four [net.transgressoft.lirp.entity.CascadeAction] mappings
 * (RESTRICT → RESTRICT, CASCADE → CASCADE, DETACH → SET NULL, NONE → no clause) and the
 * junction-table parent/item FK install path produce the expected behavior against PostgreSQL,
 * MySQL 8, MariaDB 11, and a file-backed SQLite database.
 *
 * Scalar FK semantics are exercised by pre-creating `fk_children` / `fk_parents` with the cascade
 * clause inline via [FkScalarFkInstaller] (SQLite cannot add FKs after creation), then constructing
 * the LIRP repositories — `SchemaUtils.create` is idempotent and reuses the existing tables.
 *
 * Junction FK semantics rely on `SqlRepository.installJunctionForeignKeys()` for the deferred FK
 * pass; the test invokes it explicitly after both repositories materialise their tables.
 */
@DisplayName("SqlRepository Foreign Keys Integration")
internal class SqlRepositoryForeignKeyIntegrationTest : FunSpec({

    /**
     * Drops all FK-related tables in dependency order so the next iteration starts clean. Errors
     * for tables that don't exist yet are swallowed — tests run against fresh databases on every
     * dialect except SQLite (which gets a fresh tempfile per [HikariDataSource]) so the drops are
     * mostly defensive.
     */
    fun resetSchema(dataSource: HikariDataSource) {
        // Drop child tables before parents to avoid FK violations during teardown. Junction first,
        // then parents (the FK lives on the junction → parent edge), then children.
        for (sql in listOf(
            "DROP TABLE IF EXISTS fk_parent_children",
            "DROP TABLE IF EXISTS fk_parents",
            "DROP TABLE IF EXISTS fk_children"
        )) {
            try {
                dataSource.connection.use { conn ->
                    conn.createStatement().use { stmt -> stmt.execute(sql) }
                }
            } catch (_: SQLException) {
                // ignore — table may not exist on a fresh database
            }
        }
    }

    /**
     * Asserts that [block] throws a SQL foreign-key violation. The dialect-specific error codes
     * differ widely (PostgreSQL: SQLState 23503; MySQL/MariaDB: SQLState 23000 with
     * "foreign key constraint fails"; SQLite: errorCode 19 with "FOREIGN KEY constraint failed"),
     * so the helper accepts any of them.
     */
    fun assertFkViolation(block: () -> Unit) {
        val ex = shouldThrow<Throwable> { block() }
        // Walk the cause chain — Exposed / HikariCP wrap the underlying SQLException at least once.
        var cur: Throwable? = ex
        var sqlEx: SQLException? = null
        while (cur != null) {
            if (cur is SQLException) {
                sqlEx = cur
                break
            }
            cur = cur.cause
        }
        require(sqlEx != null) { "Expected a SQLException somewhere in the cause chain, got: $ex" }
        val msg = (sqlEx.message ?: "").lowercase()
        val sqlState = sqlEx.sqlState ?: ""
        val isFkViolation =
            msg.contains("foreign key") ||
                msg.contains("constraint") ||
                sqlState == "23503" ||
                sqlState == "23000" ||
                sqlEx.errorCode == 19 // SQLite SQLITE_CONSTRAINT
        require(isFkViolation) {
            "Expected FK violation. SQLState=$sqlState errorCode=${sqlEx.errorCode} message=${sqlEx.message}"
        }
    }

    context("RESTRICT blocks parent delete when scalar FK references existing child") {
        withTests(databases) { db ->
            val dataSource = db.buildDataSource()
            try {
                resetSchema(dataSource)
                FkScalarFkInstaller.setupRestrict(dataSource)

                val childRepo = SqlRepository(dataSource, FkChildTableDef)
                val parentRepo = SqlRepository(dataSource, FkParentTableDef)
                parentRepo.installJunctionForeignKeys()

                childRepo.add(FkChild(10).apply { name = "C10" })
                childRepo.close()

                val parent =
                    FkParent(1).apply {
                        name = "P1"
                        singleChildId = 10
                    }
                parentRepo.add(parent)
                parentRepo.close()

                // Reopen to flush, then attempt deleting the child via raw SQL — the RESTRICT FK
                // must reject. Routing the delete through a fresh JDBC connection keeps the test
                // independent of any LIRP cache invalidation behaviour.
                assertFkViolation {
                    dataSource.connection.use { conn ->
                        conn.createStatement().use { stmt ->
                            stmt.execute("DELETE FROM fk_children WHERE id = 10")
                        }
                    }
                }

                val verify = SqlRepository(dataSource, FkChildTableDef)
                verify.findById(10).shouldBePresent { it.name shouldBe "C10" }
                verify.close()
            } finally {
                dataSource.close()
            }
        }
    }

    context("CASCADE deletes parent when scalar-referenced child is deleted") {
        withTests(databases) { db ->
            val dataSource = db.buildDataSource()
            try {
                resetSchema(dataSource)
                FkScalarFkInstaller.setupCascade(dataSource)

                val childRepo = SqlRepository(dataSource, FkChildTableDef)
                val parentRepo = SqlRepository(dataSource, FkParentTableDef)
                parentRepo.installJunctionForeignKeys()

                childRepo.add(FkChild(20).apply { name = "C20" })
                childRepo.close()

                parentRepo.add(
                    FkParent(2).apply {
                        name = "P2"
                        singleChildId = 20
                    }
                )
                parentRepo.close()

                // Cascade direction: ON DELETE CASCADE on fk_parents.single_child_id means deleting
                // the child cascades the delete to the parent row that references it.
                dataSource.connection.use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute("DELETE FROM fk_children WHERE id = 20")
                    }
                }

                val verify = SqlRepository(dataSource, FkParentTableDef)
                verify.findById(2).isPresent shouldBe false
                verify.close()
            } finally {
                dataSource.close()
            }
        }
    }

    context("DETACH (SET NULL) nulls scalar FK column when referenced child is deleted") {
        withTests(databases) { db ->
            val dataSource = db.buildDataSource()
            try {
                resetSchema(dataSource)
                FkScalarFkInstaller.setupDetach(dataSource)

                val childRepo = SqlRepository(dataSource, FkChildTableDef)
                val parentRepo = SqlRepository(dataSource, FkParentTableDef)
                parentRepo.installJunctionForeignKeys()

                childRepo.add(FkChild(30).apply { name = "C30" })
                childRepo.close()

                parentRepo.add(
                    FkParent(3).apply {
                        name = "P3"
                        singleChildId = 30
                    }
                )
                parentRepo.close()

                dataSource.connection.use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute("DELETE FROM fk_children WHERE id = 30")
                    }
                }

                val verify = SqlRepository(dataSource, FkParentTableDef)
                verify.findById(3).shouldBePresent {
                    it.name shouldBe "P3"
                    it.singleChildId shouldBe null
                }
                verify.close()
            } finally {
                dataSource.close()
            }
        }
    }

    context("NONE allows orphan scalar reference (no FK constraint)") {
        withTests(databases) { db ->
            val dataSource = db.buildDataSource()
            try {
                resetSchema(dataSource)
                FkScalarFkInstaller.setupNone(dataSource)

                val parentRepo = SqlRepository(dataSource, FkParentTableDef)
                parentRepo.installJunctionForeignKeys()

                // Insert a parent referencing a child that does NOT exist. With CascadeAction.NONE
                // ("no FK clause"), the database accepts the orphan reference.
                parentRepo.add(
                    FkParent(4).apply {
                        name = "P4"
                        singleChildId = 999
                    }
                )
                parentRepo.close()

                val verify = SqlRepository(dataSource, FkParentTableDef)
                verify.findById(4).shouldBePresent { it.singleChildId shouldBe 999 }
                verify.close()
            } finally {
                dataSource.close()
            }
        }
    }

    context("junction CASCADE reaps junction rows when parent is deleted") {
        withTests(databases) { db ->
            val dataSource = db.buildDataSource()
            try {
                resetSchema(dataSource)
                // Use the NONE schema to keep the scalar FK out of the way — we only care about
                // junction CASCADE here.
                FkScalarFkInstaller.setupNone(dataSource)

                val childRepo = SqlRepository(dataSource, FkChildTableDef)
                val parentRepo = SqlRepository(dataSource, FkParentTableDef)
                parentRepo.installJunctionForeignKeys()

                listOf(10, 20, 30).forEach { id -> childRepo.add(FkChild(id).apply { name = "C$id" }) }
                childRepo.close()

                val parent = FkParent(5).apply { name = "P5" }
                parent.childIds = listOf(10, 20, 30)
                parentRepo.add(parent)
                parentRepo.close()

                // Sanity: junction has 3 rows.
                fun junctionRowCount(): Long =
                    dataSource.connection.use { conn ->
                        conn.createStatement().use { stmt ->
                            stmt.executeQuery("SELECT COUNT(*) FROM fk_parent_children WHERE parent_id = 5").use {
                                it.next()
                                it.getLong(1)
                            }
                        }
                    }
                junctionRowCount() shouldBe 3L

                // Delete the parent row directly; the parent-side FK ON DELETE CASCADE installed by
                // installJunctionForeignKeys() must reap every junction row pointing at parent_id=5.
                dataSource.connection.use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.execute("DELETE FROM fk_parents WHERE id = 5")
                    }
                }
                junctionRowCount() shouldBe 0L

                // Children remain — the junction FK reaps junction rows, not item rows.
                val childVerify = SqlRepository(dataSource, FkChildTableDef)
                listOf(10, 20, 30).forEach { id -> childVerify.findById(id).isPresent shouldBe true }
                childVerify.close()
            } finally {
                dataSource.close()
            }
        }
    }

    context("junction RESTRICT blocks child delete when junction row references it") {
        withTests(databases) { db ->
            val dataSource = db.buildDataSource()
            try {
                resetSchema(dataSource)
                FkScalarFkInstaller.setupNone(dataSource)

                val childRepo = SqlRepository(dataSource, FkChildTableDef)
                val parentRepo = SqlRepository(dataSource, FkParentTableDef)
                parentRepo.installJunctionForeignKeys()

                childRepo.add(FkChild(40).apply { name = "C40" })
                childRepo.close()

                val parent = FkParent(6).apply { name = "P6" }
                parent.childIds = listOf(40)
                parentRepo.add(parent)
                parentRepo.close()

                // Item-side FK is RESTRICT (per FkParentChildrenJunctionDef.itemFkOnDelete) so
                // deleting the referenced child must fail.
                assertFkViolation {
                    dataSource.connection.use { conn ->
                        conn.createStatement().use { stmt ->
                            stmt.execute("DELETE FROM fk_children WHERE id = 40")
                        }
                    }
                }

                val verify = SqlRepository(dataSource, FkChildTableDef)
                verify.findById(40).shouldBePresent { it.name shouldBe "C40" }
                verify.close()
            } finally {
                dataSource.close()
            }
        }
    }
})