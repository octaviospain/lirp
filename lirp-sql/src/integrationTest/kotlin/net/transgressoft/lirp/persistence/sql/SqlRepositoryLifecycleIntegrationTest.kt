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
import net.transgressoft.lirp.persistence.sql.DatabaseTestSupport.withDatabaseTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName

/**
 * Integration tests for [SqlRepository] lifecycle management against PostgreSQL, MySQL 8.0, and MariaDB 11.
 *
 * Covers container connectivity, data persistence across close/reopen,
 * closed-repository semantics, DataSource ownership, and multi-instance data sharing.
 */
@DisplayName("SqlRepository Lifecycle Integration")
internal class SqlRepositoryLifecycleIntegrationTest : FunSpec({

    context("Testcontainer starts and connects via HikariCP") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                dataSource.isClosed.shouldBeFalse()

                val repo = SqlRepository(dataSource, TestPersonTableDef)
                repo.add(TestPerson(1).apply { firstName = "Alice" })
                repo.size() shouldBe 1

                repo.close()
            }
        }
    }

    context("data persists across repository close and reopen") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo1 = SqlRepository(dataSource, TestPersonTableDef)
                repo1.add(
                    TestPerson(42).apply {
                        firstName = "Persistent"
                        lastName = "Data"
                        age = 7
                    }
                )
                repo1.close()

                val repo2 = SqlRepository(dataSource, TestPersonTableDef)
                repo2.findById(42).shouldBePresent {
                    it.firstName shouldBe "Persistent"
                    it.lastName shouldBe "Data"
                    it.age shouldBe 7
                }
                repo2.size() shouldBe 1
                repo2.close()
            }
        }
    }

    context("closed repository throws IllegalStateException on add") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                repo.close()

                shouldThrow<IllegalStateException> {
                    repo.add(TestPerson(99))
                }
            }
        }
    }

    context("does not close user-provided datasource on repository close") {
        withTests(databases) { db ->
            val dataSource = db.buildDataSource()
            try {
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                repo.close()

                dataSource.isClosed.shouldBeFalse()
            } finally {
                dataSource.close()
            }
        }
    }

    context("multiple repository instances share same data") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo1 = SqlRepository(dataSource, TestPersonTableDef)
                repo1.add(TestPerson(77).apply { firstName = "Shared" })

                val repo2 = SqlRepository(dataSource, TestPersonTableDef)
                repo2.findById(77).shouldBePresent { it.firstName shouldBe "Shared" }

                repo1.close()
                repo2.close()
            }
        }
    }
})