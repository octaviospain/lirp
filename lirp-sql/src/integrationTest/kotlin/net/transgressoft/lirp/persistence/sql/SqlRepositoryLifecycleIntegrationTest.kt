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
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName

/**
 * Integration tests for [SqlRepository] lifecycle management against a real PostgreSQL database.
 *
 * Covers container connectivity (TEST-01), data persistence across close/reopen (TEST-06),
 * closed-repository semantics, DataSource ownership, and multi-instance data sharing.
 * Each test drops the table before execution to guarantee isolation within the shared container schema.
 */
@DisplayName("SqlRepository Lifecycle Integration")
internal class SqlRepositoryLifecycleIntegrationTest : FunSpec({

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

    test("PostgreSQL Testcontainer starts and connects via HikariCP") {
        PostgresContainerSupport.container.isRunning.shouldBeTrue()

        val repo = SqlRepository(dataSource!!, TestPersonTableDef)
        repo.add(TestPerson(1).apply { firstName = "Alice" })
        repo.size() shouldBe 1

        repo.close()
    }

    test("data persists across repository close and reopen") {
        val repo1 = SqlRepository(dataSource!!, TestPersonTableDef)
        repo1.add(TestPerson(42).apply { firstName = "Persistent"; lastName = "Data"; age = 7 })
        repo1.close()

        val repo2 = SqlRepository(dataSource!!, TestPersonTableDef)
        repo2.findById(42).shouldBePresent {
            it.firstName shouldBe "Persistent"
            it.lastName shouldBe "Data"
            it.age shouldBe 7
        }
        repo2.size() shouldBe 1
        repo2.close()
    }

    test("closed repository throws IllegalStateException on add") {
        val repo = SqlRepository(dataSource!!, TestPersonTableDef)
        repo.close()

        shouldThrow<IllegalStateException> {
            repo.add(TestPerson(99))
        }
    }

    test("does not close user-provided datasource on repository close") {
        val repo = SqlRepository(dataSource!!, TestPersonTableDef)
        repo.close()

        dataSource!!.isClosed.shouldBeFalse()
    }

    test("multiple repository instances share same PostgreSQL data") {
        val repo1 = SqlRepository(dataSource!!, TestPersonTableDef)
        repo1.add(TestPerson(77).apply { firstName = "Shared" })

        // A second repository on the same DataSource must see the data on initialization
        val repo2 = SqlRepository(dataSource!!, TestPersonTableDef)
        repo2.findById(77).shouldBePresent { it.firstName shouldBe "Shared" }

        repo1.close()
        repo2.close()
    }
})
