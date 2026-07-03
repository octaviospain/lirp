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

import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.ColumnDef
import net.transgressoft.lirp.persistence.ColumnType
import net.transgressoft.lirp.persistence.LirpRawInitializer
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies that [SqlRepository.installEntityForeignKeys] actually applies the constraints
 * declared in [SqlTableDef.foreignKeys] at the database level — closing the gap left by the
 * Phase 53 deferral where the metadata was emitted but never consumed.
 *
 * Each test stands up two H2 tables in the same in-memory database (parent + child), declares
 * a single-entity FK descriptor with the cascade variant under test, calls
 * [SqlRepository.installEntityForeignKeys], and asserts the resulting database-level behaviour
 * via direct JDBC. `Int` keys avoid `java.util.UUID` vs `kotlin.uuid.Uuid` plumbing noise that
 * is orthogonal to the FK install contract under test.
 *
 * Persistence waits use kotest's [eventually] to poll the DB rather than fixed `delay(...)` sleeps,
 * keeping the suite deterministic under CI jitter.
 */
class SqlRepositoryEntityForeignKeyInstallTest : StringSpec({

    val nextId = AtomicInteger(0)

    "installEntityForeignKeys with RESTRICT prevents deleting a referenced child row" {
        val ds = freshDataSource()
        val childRepo = SqlRepository(ds, ScalarFkChildTableDef)
        val parentRepo = SqlRepository(ds, ScalarFkParentTableDef(CascadeAction.RESTRICT))
        try {
            parentRepo.installEntityForeignKeys()

            val childId = nextId.incrementAndGet()
            childRepo.add(ScalarFkChild(childId).apply { name = "linked" })
            awaitRowPresent(ds, "fk_scalar_children", childId)

            val parentId = nextId.incrementAndGet()
            parentRepo.add(ScalarFkParent(parentId).apply { this.childId = childId })
            awaitRowPresent(ds, "fk_scalar_parents", parentId)

            shouldThrow<java.sql.SQLException> {
                ds.connection.use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.executeUpdate("DELETE FROM fk_scalar_children WHERE id = $childId")
                    }
                }
            }

            childRepo.findById(childId).shouldBePresent { it.name shouldBe "linked" }
        } finally {
            closeQuietly(parentRepo, childRepo, ds)
        }
    }

    "installEntityForeignKeys with CASCADE deletes the parent row when its child is deleted" {
        val ds = freshDataSource()
        val childRepo = SqlRepository(ds, ScalarFkChildTableDef)
        val parentRepo = SqlRepository(ds, ScalarFkParentTableDef(CascadeAction.CASCADE))
        try {
            parentRepo.installEntityForeignKeys()

            val childId = nextId.incrementAndGet()
            childRepo.add(ScalarFkChild(childId).apply { name = "doomed" })
            awaitRowPresent(ds, "fk_scalar_children", childId)

            val parentId = nextId.incrementAndGet()
            parentRepo.add(ScalarFkParent(parentId).apply { this.childId = childId })
            awaitRowPresent(ds, "fk_scalar_parents", parentId)

            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeUpdate("DELETE FROM fk_scalar_children WHERE id = $childId")
                }
            }

            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    val rs = stmt.executeQuery("SELECT COUNT(*) FROM fk_scalar_parents WHERE id = $parentId")
                    rs.next()
                    rs.getInt(1) shouldBe 0
                }
            }
        } finally {
            closeQuietly(parentRepo, childRepo, ds)
        }
    }

    "installEntityForeignKeys with DETACH nulls the FK column when the child is deleted" {
        val ds = freshDataSource()
        val childRepo = SqlRepository(ds, ScalarFkChildTableDef)
        val parentRepo = SqlRepository(ds, ScalarFkParentTableDef(CascadeAction.DETACH))
        try {
            parentRepo.installEntityForeignKeys()

            val childId = nextId.incrementAndGet()
            childRepo.add(ScalarFkChild(childId).apply { name = "to-be-orphaned" })
            awaitRowPresent(ds, "fk_scalar_children", childId)

            val parentId = nextId.incrementAndGet()
            parentRepo.add(ScalarFkParent(parentId).apply { this.childId = childId })
            awaitRowPresent(ds, "fk_scalar_parents", parentId)

            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    stmt.executeUpdate("DELETE FROM fk_scalar_children WHERE id = $childId")
                }
            }

            ds.connection.use { conn ->
                conn.createStatement().use { stmt ->
                    val rs =
                        stmt.executeQuery(
                            "SELECT single_child_id FROM fk_scalar_parents WHERE id = $parentId"
                        )
                    rs.next() shouldBe true
                    rs.getObject(1).shouldBeNull()
                }
            }
        } finally {
            closeQuietly(parentRepo, childRepo, ds)
        }
    }

    "installEntityForeignKeys is idempotent across repeated calls" {
        val ds = freshDataSource()
        val childRepo = SqlRepository(ds, ScalarFkChildTableDef)
        val parentRepo = SqlRepository(ds, ScalarFkParentTableDef(CascadeAction.RESTRICT))
        try {
            parentRepo.installEntityForeignKeys()
            parentRepo.installEntityForeignKeys()
            parentRepo.installEntityForeignKeys()

            val childId = nextId.incrementAndGet()
            childRepo.add(ScalarFkChild(childId).apply { name = "still-linked" })
            awaitRowPresent(ds, "fk_scalar_children", childId)

            val parentId = nextId.incrementAndGet()
            parentRepo.add(ScalarFkParent(parentId).apply { this.childId = childId })
            awaitRowPresent(ds, "fk_scalar_parents", parentId)

            shouldThrow<java.sql.SQLException> {
                ds.connection.use { conn ->
                    conn.createStatement().use { stmt ->
                        stmt.executeUpdate("DELETE FROM fk_scalar_children WHERE id = $childId")
                    }
                }
            }
        } finally {
            closeQuietly(parentRepo, childRepo, ds)
        }
    }

    "installEntityForeignKeys is a no-op when the entity declares no foreign keys" {
        val ds = freshDataSource()
        val childRepo = SqlRepository(ds, ScalarFkChildTableDef)
        try {
            childRepo.installEntityForeignKeys()

            val childId = nextId.incrementAndGet()
            childRepo.add(ScalarFkChild(childId).apply { name = "standalone" })
            awaitRowPresent(ds, "fk_scalar_children", childId)

            childRepo.findById(childId).shouldBePresent { it.name shouldBe "standalone" }
        } finally {
            closeQuietly(childRepo, ds)
        }
    }
})

private fun freshDataSource(): HikariDataSource {
    val cfg =
        HikariConfig().apply {
            jdbcUrl = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1"
            maximumPoolSize = 4
        }
    return HikariDataSource(cfg)
}

// Polls the DB directly until a row with the given primary key appears, with a generous timeout.
// Replaces fixed `delay(...)` sleeps so the suite stays deterministic under CI jitter.
private suspend fun awaitRowPresent(ds: HikariDataSource, tableName: String, id: Int) {
    eventually(DatabaseTestSupport.PERSISTED_ROW_POLL) {
        ds.connection.use { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT COUNT(*) FROM $tableName WHERE id = $id")
                rs.next()
                require(rs.getInt(1) == 1) { "row id=$id not yet visible in $tableName" }
            }
        }
    }
}

// Closes resources in reverse-of-acquisition order, swallowing per-resource failures so a leak
// during cleanup never masks a test-body failure.
private fun closeQuietly(vararg resources: AutoCloseable) {
    for (resource in resources) {
        try {
            resource.close()
        } catch (_: Throwable) {
            // Ignore — best-effort cleanup, the original test failure (if any) is more interesting.
        }
    }
}

internal class ScalarFkChild(override val id: Int) : ReactiveEntityBase<Int, ScalarFkChild>() {
    var name: String by reactiveProperty("")
    override val uniqueId: String get() = "scalar-fk-child-$id"

    override fun clone(): ScalarFkChild =
        ScalarFkChild(id).also { copy -> copy.withEventsDisabled { copy.name = name } }
}

internal class ScalarFkParent(override val id: Int) : ReactiveEntityBase<Int, ScalarFkParent>() {
    var childId: Int? by reactiveProperty(null)
    override val uniqueId: String get() = "scalar-fk-parent-$id"

    override fun clone(): ScalarFkParent =
        ScalarFkParent(id).also { copy -> copy.withEventsDisabled { copy.childId = childId } }
}

internal object ScalarFkChildTableDef : SqlTableDef<ScalarFkChild> {
    override val tableName = "fk_scalar_children"
    override val columns =
        listOf(
            ColumnDef("id", ColumnType.IntType, nullable = false, primaryKey = true),
            ColumnDef("name", ColumnType.VarcharType(200), nullable = false, primaryKey = false)
        )

    @Suppress("UNCHECKED_CAST")
    override fun fromRow(row: ResultRow, table: Table): ScalarFkChild {
        val cols = table.columns.associateBy { it.name }
        return ScalarFkChild(row[cols["id"]!! as Column<Int>]).also { entity ->
            entity.withEventsDisabled { entity.name = row[cols["name"]!! as Column<String>] }
        }
    }

    override fun toParams(entity: ScalarFkChild, table: Table): Map<Column<*>, Any?> {
        val cols = table.columns.associateBy { it.name }
        return mapOf(cols["id"]!! to entity.id, cols["name"]!! to entity.name)
    }

    @Suppress("UNCHECKED_CAST")
    override fun applyRow(entity: ScalarFkChild, row: ResultRow, table: Table) {
        val cols = table.columns.associateBy { it.name }
        entity.withEventsDisabled { entity.name = row[cols["name"]!! as Column<String>] }
    }

    override fun applyScalarRow(entity: ScalarFkChild, row: ResultRow, table: Table, rawInit: LirpRawInitializer<ScalarFkChild>) {
        // No-op: entity state is fully populated by fromRow.
    }
}

internal class ScalarFkParentTableDef(private val onDelete: CascadeAction) : SqlTableDef<ScalarFkParent>, ForeignKeyAware {
    override val tableName = "fk_scalar_parents"
    override val columns =
        listOf(
            ColumnDef("id", ColumnType.IntType, nullable = false, primaryKey = true),
            ColumnDef("single_child_id", ColumnType.IntType, nullable = true, primaryKey = false)
        )

    override fun foreignKeys(): List<ForeignKeyDef> =
        listOf(
            ForeignKeyDef(
                columnName = "single_child_id",
                referencedTable = "fk_scalar_children",
                referencedColumn = "id",
                onDelete = onDelete
            )
        )

    @Suppress("UNCHECKED_CAST")
    override fun fromRow(row: ResultRow, table: Table): ScalarFkParent {
        val cols = table.columns.associateBy { it.name }
        return ScalarFkParent(row[cols["id"]!! as Column<Int>]).also { entity ->
            entity.withEventsDisabled {
                entity.childId = row[cols["single_child_id"]!! as Column<Int?>]
            }
        }
    }

    override fun toParams(entity: ScalarFkParent, table: Table): Map<Column<*>, Any?> {
        val cols = table.columns.associateBy { it.name }
        return mapOf(
            cols["id"]!! to entity.id,
            cols["single_child_id"]!! to entity.childId
        )
    }

    @Suppress("UNCHECKED_CAST")
    override fun applyRow(entity: ScalarFkParent, row: ResultRow, table: Table) {
        val cols = table.columns.associateBy { it.name }
        entity.withEventsDisabled {
            entity.childId = row[cols["single_child_id"]!! as Column<Int?>]
        }
    }

    override fun applyScalarRow(entity: ScalarFkParent, row: ResultRow, table: Table, rawInit: LirpRawInitializer<ScalarFkParent>) {
        // No-op: entity state is fully populated by fromRow.
    }
}