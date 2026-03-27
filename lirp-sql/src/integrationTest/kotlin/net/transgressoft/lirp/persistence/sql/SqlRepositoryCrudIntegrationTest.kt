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
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName

/**
 * Integration tests for [SqlRepository] CRUD operations against a real PostgreSQL database.
 *
 * Covers the full entity lifecycle: add, read-back, mutate, verify persistence, remove, and clear.
 * Each test drops the table before execution to guarantee isolation within the shared container schema.
 */
@DisplayName("SqlRepository CRUD Integration")
internal class SqlRepositoryCrudIntegrationTest : StringSpec({

    var dataSource: HikariDataSource? = null

    beforeSpec {
        dataSource = PostgresContainerSupport.buildDataSource()
    }

    afterSpec {
        dataSource?.close()
    }

    beforeTest {
        val db = Database.connect(dataSource!!)
        val t = ExposedTableInterpreter().interpret(TestPersonTableDef)
        runCatching { transaction(db) { SchemaUtils.drop(t.table) } }
    }

    "adds entity and reads it back from PostgreSQL" {
        val repo = SqlRepository(dataSource!!, TestPersonTableDef)
        repo.add(
            TestPerson(1).apply {
                firstName = "Alice"
                lastName = "Smith"
                age = 30
            }
        )

        repo.findById(1).shouldBePresent {
            it.firstName shouldBe "Alice"
            it.lastName shouldBe "Smith"
            it.age shouldBe 30
        }
        repo.close()
    }

    "persists entity mutation to PostgreSQL via flush" {
        val repo = SqlRepository(dataSource!!, TestPersonTableDef)
        val person =
            TestPerson(5).apply {
                firstName = "Frank"
                lastName = "Lee"
                age = 20
            }.also(repo::add)

        person.firstName = "Franklin"
        Thread.sleep(200)

        val repo2 = SqlRepository(dataSource, TestPersonTableDef)
        repo2.findById(5).shouldBePresent { it.firstName shouldBe "Franklin" }

        repo.close()
        repo2.close()
    }

    "removes entity from PostgreSQL" {
        val repo = SqlRepository(dataSource!!, TestPersonTableDef)
        val person = TestPerson(10).apply { firstName = "Grace" }
        repo.add(person)

        repo.remove(person)

        val repo2 = SqlRepository(dataSource, TestPersonTableDef)
        repo2.size() shouldBe 0

        repo.close()
        repo2.close()
    }

    "clears all entities from PostgreSQL" {
        val repo = SqlRepository(dataSource!!, TestPersonTableDef)
        repo.add(TestPerson(20).apply { firstName = "Alice" })
        repo.add(TestPerson(21).apply { firstName = "Bob" })

        repo.clear()

        val repo2 = SqlRepository(dataSource, TestPersonTableDef)
        repo2.size() shouldBe 0

        repo.close()
        repo2.close()
    }

    "removeAll deletes specified entities from PostgreSQL" {
        val repo = SqlRepository(dataSource!!, TestPersonTableDef)
        val p1 = TestPerson(30).apply { firstName = "Iris" }
        val p2 = TestPerson(31).apply { firstName = "Jack" }
        val p3 = TestPerson(32).apply { firstName = "Kate" }
        repo.add(p1)
        repo.add(p2)
        repo.add(p3)

        repo.removeAll(listOf(p1, p2))

        val repo2 = SqlRepository(dataSource, TestPersonTableDef)
        repo2.size() shouldBe 1
        repo2.findById(32).shouldBePresent { it.firstName shouldBe "Kate" }

        repo.close()
        repo2.close()
    }

    "full CRUD lifecycle: create, read, update, delete" {
        val repo = SqlRepository(dataSource!!, TestPersonTableDef)
        val person =
            TestPerson(100).apply {
                firstName = "Lifecycle"
                lastName = "Test"
                age = 42
            }

        repo.add(person)

        repo.findById(100).shouldBePresent { it.firstName shouldBe "Lifecycle" }

        // Update via mutation
        repo.findById(100).shouldBePresent { it.firstName = "Updated" }
        Thread.sleep(200)

        // Verify mutation persisted via new repo instance
        val repo2 = SqlRepository(dataSource, TestPersonTableDef)
        repo2.findById(100).shouldBePresent { it.firstName shouldBe "Updated" }

        // Delete
        repo2.findById(100).shouldBePresent { repo2.remove(it) }

        val repo3 = SqlRepository(dataSource, TestPersonTableDef)
        repo3.size() shouldBe 0

        repo.close()
        repo2.close()
        repo3.close()
    }
})