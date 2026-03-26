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

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.persistence.ColumnDef
import net.transgressoft.lirp.persistence.ColumnType
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.junit.jupiter.api.DisplayName
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * A simple test entity backed by [ReactiveEntityBase] with three publicly mutable properties
 * suitable for SQL column mapping.
 */
internal class TestPerson(override val id: Int) : ReactiveEntityBase<Int, TestPerson>() {
    var firstName: String by reactiveProperty("")
    var lastName: String by reactiveProperty("")
    var age: Int by reactiveProperty(0)
    override val uniqueId: String get() = id.toString()

    override fun clone(): TestPerson =
        TestPerson(id).also {
            it.firstName = firstName
            it.lastName = lastName
            it.age = age
        }
}

/**
 * Manual [SqlTableDef] for [TestPerson], providing column descriptors and Exposed row mapping
 * without KSP generation.
 */
internal object TestPersonTableDef : SqlTableDef<TestPerson> {
    override val tableName = "test_persons"
    override val columns =
        listOf(
            ColumnDef("id", ColumnType.IntType, nullable = false, primaryKey = true),
            ColumnDef("first_name", ColumnType.VarcharType(100), nullable = false, primaryKey = false),
            ColumnDef("last_name", ColumnType.VarcharType(100), nullable = false, primaryKey = false),
            ColumnDef("age", ColumnType.IntType, nullable = false, primaryKey = false)
        )

    override fun fromRow(row: ResultRow, table: Table): TestPerson {
        val cols = table.columns.associateBy { it.name }

        @Suppress("UNCHECKED_CAST")
        val entity = TestPerson(row[cols["id"]!! as Column<Int>])
        @Suppress("UNCHECKED_CAST")
        entity.firstName = row[cols["first_name"]!! as Column<String>]
        @Suppress("UNCHECKED_CAST")
        entity.lastName = row[cols["last_name"]!! as Column<String>]
        @Suppress("UNCHECKED_CAST")
        entity.age = row[cols["age"]!! as Column<Int>]
        return entity
    }

    override fun toParams(entity: TestPerson, table: Table): Map<Column<*>, Any?> {
        val cols = table.columns.associateBy { it.name }
        return mapOf(
            cols["id"]!! to entity.id,
            cols["first_name"]!! to entity.firstName,
            cols["last_name"]!! to entity.lastName,
            cols["age"]!! to entity.age
        )
    }
}

/**
 * Unit tests for [SqlRepository] using H2 in-memory databases to verify SQL-first CRUD semantics,
 * event emission, lifecycle management, and entity loading on initialization.
 */
@DisplayName("SqlRepository")
internal class SqlRepositoryTest : FunSpec({

    /** Returns a unique JDBC URL for an isolated H2 in-memory database per test. */
    fun freshJdbcUrl() = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1"

    fun buildExternalDataSource(jdbcUrl: String): HikariDataSource {
        val config =
            HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                this.maximumPoolSize = 5
            }
        return HikariDataSource(config)
    }

    test("adds entity and persists to database") {
        val jdbcUrl = freshJdbcUrl()
        val repo = SqlRepository(jdbcUrl, TestPersonTableDef)
        val person =
            TestPerson(1).apply {
                firstName = "Alice"
                lastName = "Smith"
                age = 30
            }

        repo.add(person)

        repo.size() shouldBe 1

        // A second repository on the same DB URL verifies the row was persisted
        val repo2 = SqlRepository(jdbcUrl, TestPersonTableDef)
        repo2.size() shouldBe 1
        repo2.findById(1).shouldBePresent { it.firstName shouldBe "Alice" }

        repo.close()
        repo2.close()
    }

    test("emits CREATE event on add") {
        val repo = SqlRepository(freshJdbcUrl(), TestPersonTableDef)
        val received = AtomicReference<CrudEvent.Type?>()
        repo.subscribe { event -> received.set(event.type) }

        repo.add(TestPerson(1).apply { firstName = "Bob" })

        // FlowEventPublisher emits asynchronously; allow brief settling
        Thread.sleep(100)
        received.get() shouldBe CrudEvent.Type.CREATE

        repo.close()
    }

    test("removes entity and deletes from database") {
        val jdbcUrl = freshJdbcUrl()
        val repo = SqlRepository(jdbcUrl, TestPersonTableDef)
        val person =
            TestPerson(2).apply {
                firstName = "Carol"
                lastName = "Jones"
                age = 25
            }
        repo.add(person)

        repo.remove(person)

        repo.size() shouldBe 0

        // Verify row was deleted in DB
        val repo2 = SqlRepository(jdbcUrl, TestPersonTableDef)
        repo2.size() shouldBe 0

        repo.close()
        repo2.close()
    }

    test("emits DELETE event on remove") {
        val repo = SqlRepository(freshJdbcUrl(), TestPersonTableDef)
        val person = TestPerson(3).apply { firstName = "Dave" }
        repo.add(person)
        val received = AtomicReference<CrudEvent.Type?>()
        repo.subscribe { event -> received.set(event.type) }

        repo.remove(person)

        Thread.sleep(100)
        received.get() shouldBe CrudEvent.Type.DELETE

        repo.close()
    }

    test("loads existing rows from database on initialization") {
        val jdbcUrl = freshJdbcUrl()
        // First repository inserts a row
        val repo1 = SqlRepository(jdbcUrl, TestPersonTableDef)
        repo1.add(
            TestPerson(10).apply {
                firstName = "Eve"
                lastName = "Brown"
                age = 40
            }
        )
        repo1.close()

        // Second repository on the same DB should load the pre-existing row
        val repo2 = SqlRepository(jdbcUrl, TestPersonTableDef)
        repo2.size() shouldBe 1
        repo2.findById(10).shouldBePresent { it.firstName shouldBe "Eve" }

        repo2.close()
    }

    test("auto-creates table on initialization") {
        // Creating the repository on a fresh DB should not throw; table is created automatically
        val repo = SqlRepository(freshJdbcUrl(), TestPersonTableDef)
        repo.size() shouldBe 0
        repo.close()
    }

    test("throws IllegalStateException on add after close") {
        val repo = SqlRepository(freshJdbcUrl(), TestPersonTableDef)
        repo.close()

        shouldThrow<IllegalStateException> {
            repo.add(TestPerson(99))
        }
    }

    test("closes HikariCP pool when owning the datasource") {
        val repo = SqlRepository(freshJdbcUrl(), TestPersonTableDef)
        // Access the HikariDataSource to verify it is shut down after repo.close()
        val dataSourceField = SqlRepository::class.java.getDeclaredField("dataSource")
        dataSourceField.isAccessible = true
        val hikariDs = dataSourceField.get(repo) as HikariDataSource

        repo.close()

        hikariDs.isClosed.shouldBeTrue()
    }

    test("does not close user-provided datasource on close") {
        val jdbcUrl = freshJdbcUrl()
        val externalDs = buildExternalDataSource(jdbcUrl)
        val repo = SqlRepository(externalDs, TestPersonTableDef)

        repo.close()

        externalDs.isClosed shouldBe false
        externalDs.close()
    }

    test("persists entity mutation to database via onDirty") {
        val jdbcUrl = freshJdbcUrl()
        val repo = SqlRepository(jdbcUrl, TestPersonTableDef)
        val person =
            TestPerson(5).apply {
                firstName = "Frank"
                lastName = "Lee"
                age = 20
            }
        repo.add(person)

        // Mutate the entity — triggers the subscription callback and synchronous onDirty
        person.firstName = "Franklin"

        // Allow the synchronous onDirty to complete and any async event propagation to settle
        Thread.sleep(200)

        // Second repository reads from DB; should see the updated value
        val repo2 = SqlRepository(jdbcUrl, TestPersonTableDef)
        repo2.findById(5).shouldBePresent { it.firstName shouldBe "Franklin" }

        repo.close()
        repo2.close()
    }

    test("clears all entities from database and in-memory") {
        val jdbcUrl = freshJdbcUrl()
        val repo = SqlRepository(jdbcUrl, TestPersonTableDef)
        repo.add(TestPerson(20).apply { firstName = "Grace" })
        repo.add(TestPerson(21).apply { firstName = "Hank" })

        repo.clear()

        repo.size() shouldBe 0

        val repo2 = SqlRepository(jdbcUrl, TestPersonTableDef)
        repo2.size() shouldBe 0

        repo.close()
        repo2.close()
    }

    test("removeAll deletes specified entities from database") {
        val jdbcUrl = freshJdbcUrl()
        val repo = SqlRepository(jdbcUrl, TestPersonTableDef)
        val p1 = TestPerson(30).apply { firstName = "Iris" }
        val p2 = TestPerson(31).apply { firstName = "Jack" }
        val p3 = TestPerson(32).apply { firstName = "Kate" }
        repo.add(p1)
        repo.add(p2)
        repo.add(p3)

        repo.removeAll(listOf(p1, p2))

        repo.size() shouldBe 1

        val repo2 = SqlRepository(jdbcUrl, TestPersonTableDef)
        repo2.size() shouldBe 1
        repo2.findById(32).shouldBePresent { it.firstName shouldBe "Kate" }

        repo.close()
        repo2.close()
    }
})