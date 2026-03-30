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
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for [SqlRepository] CRUD operations against PostgreSQL, MySQL 8.0, and MariaDB 11.
 *
 * Covers the full entity lifecycle: add, read-back, mutate, verify persistence, remove, and clear.
 * Each test creates its own data source and drops the table before execution to guarantee isolation.
 */
@DisplayName("SqlRepository CRUD Integration")
internal class SqlRepositoryCrudIntegrationTest : FunSpec({

    context("adds entity and reads it back") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
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
        }
    }

    context("persists entity mutation via flush") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                val person =
                    TestPerson(5).apply {
                        firstName = "Frank"
                        lastName = "Lee"
                        age = 20
                    }.also(repo::add)

                person.firstName = "Franklin"

                eventually(5.seconds) {
                    val repo2 = SqlRepository(dataSource, TestPersonTableDef)
                    repo2.findById(5).shouldBePresent { it.firstName shouldBe "Franklin" }
                    repo2.close()
                }

                repo.close()
            }
        }
    }

    context("removes entity") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                val person = TestPerson(10).apply { firstName = "Grace" }
                repo.add(person)

                repo.remove(person)

                val repo2 = SqlRepository(dataSource, TestPersonTableDef)
                repo2.size() shouldBe 0

                repo.close()
                repo2.close()
            }
        }
    }

    context("clears all entities") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                repo.add(TestPerson(20).apply { firstName = "Alice" })
                repo.add(TestPerson(21).apply { firstName = "Bob" })

                repo.clear()

                val repo2 = SqlRepository(dataSource, TestPersonTableDef)
                repo2.size() shouldBe 0

                repo.close()
                repo2.close()
            }
        }
    }

    context("removeAll deletes specified entities") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
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
        }
    }

    context("full CRUD lifecycle: create, read, update, delete") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                val person =
                    TestPerson(100).apply {
                        firstName = "Lifecycle"
                        lastName = "Test"
                        age = 42
                    }

                repo.add(person)

                repo.findById(100).shouldBePresent { it.firstName shouldBe "Lifecycle" }

                repo.findById(100).shouldBePresent { it.firstName = "Updated" }

                eventually(5.seconds) {
                    val repo2 = SqlRepository(dataSource, TestPersonTableDef)
                    repo2.findById(100).shouldBePresent { it.firstName shouldBe "Updated" }
                    repo2.close()
                }

                val repo2 = SqlRepository(dataSource, TestPersonTableDef)
                repo2.findById(100).shouldBePresent { repo2.remove(it) }

                val repo3 = SqlRepository(dataSource, TestPersonTableDef)
                repo3.size() shouldBe 0

                repo.close()
                repo2.close()
                repo3.close()
            }
        }
    }
})