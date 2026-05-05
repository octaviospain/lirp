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
import net.transgressoft.lirp.persistence.query.Direction
import net.transgressoft.lirp.persistence.query.and
import net.transgressoft.lirp.persistence.query.eq
import net.transgressoft.lirp.persistence.query.gt
import net.transgressoft.lirp.persistence.query.gte
import net.transgressoft.lirp.persistence.query.lt
import net.transgressoft.lirp.persistence.query.lte
import net.transgressoft.lirp.persistence.query.not
import net.transgressoft.lirp.persistence.query.or
import net.transgressoft.lirp.persistence.query.query
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName as JunitDisplayName
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * Integration tests for the Query DSL against [SqlRepository] on PostgreSQL, MySQL, MariaDB, and SQLite.
 *
 * Reuses [TestPerson] and [TestPersonTableDef] from testFixtures, augmented with
 * [TestPerson_LirpIndexAccessor] so that `firstName` and `age` are secondary-indexed.
 *
 * Covers simple predicates, composite predicates, ordering, pagination, and the
 * SQL-specific persistence-across-close scenario.
 */
@JunitDisplayName("SqlRepository Query DSL Integration")
@DisplayName("SqlRepository Query DSL Integration")
internal class SqlRepositoryQueryDslIntegrationTest : FunSpec({

    context("Eq filter on indexed property returns matching entities") {
        withTests(DatabaseTestSupport.databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                try {
                    repo.add(
                        TestPerson(1).apply {
                            firstName = "Alice"
                            lastName = "Smith"
                            age = 30
                        }
                    )
                    repo.add(
                        TestPerson(2).apply {
                            firstName = "Bob"
                            lastName = "Jones"
                            age = 25
                        }
                    )
                    repo.add(
                        TestPerson(3).apply {
                            firstName = "Alice"
                            lastName = "Brown"
                            age = 35
                        }
                    )

                    val results = repo.query { where { TestPerson::firstName eq "Alice" } }.toList()

                    results shouldHaveSize 2
                    results.map { it.id } shouldContainExactlyInAnyOrder listOf(1, 3)
                } finally {
                    repo.close()
                }
            }
        }
    }

    context("Eq filter with no matches returns empty sequence") {
        withTests(DatabaseTestSupport.databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                try {
                    repo.add(
                        TestPerson(1).apply {
                            firstName = "Alice"
                            age = 30
                        }
                    )

                    val results = repo.query { where { TestPerson::firstName eq "Zara" } }.toList()

                    results.shouldBeEmpty()
                } finally {
                    repo.close()
                }
            }
        }
    }

    context("Gt filter returns entities above threshold") {
        withTests(DatabaseTestSupport.databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                try {
                    repo.add(
                        TestPerson(1).apply {
                            firstName = "A"
                            age = 20
                        }
                    )
                    repo.add(
                        TestPerson(2).apply {
                            firstName = "B"
                            age = 30
                        }
                    )
                    repo.add(
                        TestPerson(3).apply {
                            firstName = "C"
                            age = 40
                        }
                    )

                    val results = repo.query { where { TestPerson::age gt 25 } }.toList()

                    results shouldHaveSize 2
                    results.all { it.age > 25 }.shouldBeTrue()
                } finally {
                    repo.close()
                }
            }
        }
    }

    context("Gte filter returns entities at or above threshold") {
        withTests(DatabaseTestSupport.databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                try {
                    repo.add(
                        TestPerson(1).apply {
                            firstName = "A"
                            age = 20
                        }
                    )
                    repo.add(
                        TestPerson(2).apply {
                            firstName = "B"
                            age = 30
                        }
                    )
                    repo.add(
                        TestPerson(3).apply {
                            firstName = "C"
                            age = 40
                        }
                    )

                    val results = repo.query { where { TestPerson::age gte 30 } }.toList()

                    results shouldHaveSize 2
                    results.map { it.id } shouldContainExactlyInAnyOrder listOf(2, 3)
                } finally {
                    repo.close()
                }
            }
        }
    }

    context("Lt filter returns entities below threshold") {
        withTests(DatabaseTestSupport.databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                try {
                    repo.add(
                        TestPerson(1).apply {
                            firstName = "A"
                            age = 20
                        }
                    )
                    repo.add(
                        TestPerson(2).apply {
                            firstName = "B"
                            age = 30
                        }
                    )
                    repo.add(
                        TestPerson(3).apply {
                            firstName = "C"
                            age = 40
                        }
                    )

                    val results = repo.query { where { TestPerson::age lt 35 } }.toList()

                    results shouldHaveSize 2
                    results.all { it.age < 35 }.shouldBeTrue()
                } finally {
                    repo.close()
                }
            }
        }
    }

    context("Lte filter returns entities at or below threshold") {
        withTests(DatabaseTestSupport.databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                try {
                    repo.add(
                        TestPerson(1).apply {
                            firstName = "A"
                            age = 20
                        }
                    )
                    repo.add(
                        TestPerson(2).apply {
                            firstName = "B"
                            age = 30
                        }
                    )
                    repo.add(
                        TestPerson(3).apply {
                            firstName = "C"
                            age = 40
                        }
                    )

                    val results = repo.query { where { TestPerson::age lte 30 } }.toList()

                    results shouldHaveSize 2
                    results.map { it.id } shouldContainExactlyInAnyOrder listOf(1, 2)
                } finally {
                    repo.close()
                }
            }
        }
    }

    context("And returns intersection of indexed and range predicates") {
        withTests(DatabaseTestSupport.databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                try {
                    repo.add(
                        TestPerson(1).apply {
                            firstName = "Alice"
                            age = 20
                        }
                    )
                    repo.add(
                        TestPerson(2).apply {
                            firstName = "Alice"
                            age = 30
                        }
                    )
                    repo.add(
                        TestPerson(3).apply {
                            firstName = "Bob"
                            age = 40
                        }
                    )

                    val results =
                        repo.query {
                            where {
                                (TestPerson::firstName eq "Alice") and (TestPerson::age gt 25)
                            }
                        }.toList()

                    results shouldHaveSize 1
                    results.first().id shouldBe 2
                } finally {
                    repo.close()
                }
            }
        }
    }

    context("And with two indexed Eq predicates uses index intersection") {
        withTests(DatabaseTestSupport.databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                try {
                    repo.add(
                        TestPerson(1).apply {
                            firstName = "Alice"
                            age = 30
                        }
                    )
                    repo.add(
                        TestPerson(2).apply {
                            firstName = "Alice"
                            age = 30
                        }
                    )
                    repo.add(
                        TestPerson(3).apply {
                            firstName = "Bob"
                            age = 30
                        }
                    )

                    val results =
                        repo.query {
                            where {
                                (TestPerson::firstName eq "Alice") and (TestPerson::age eq 30)
                            }
                        }.toList()

                    results shouldHaveSize 2
                    results.map { it.id } shouldContainExactlyInAnyOrder listOf(1, 2)
                } finally {
                    repo.close()
                }
            }
        }
    }

    context("Or returns union of matching entities") {
        withTests(DatabaseTestSupport.databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                try {
                    repo.add(
                        TestPerson(1).apply {
                            firstName = "Alice"
                            age = 20
                        }
                    )
                    repo.add(
                        TestPerson(2).apply {
                            firstName = "Bob"
                            age = 30
                        }
                    )
                    repo.add(
                        TestPerson(3).apply {
                            firstName = "Charlie"
                            age = 40
                        }
                    )

                    val results =
                        repo.query {
                            where {
                                (TestPerson::firstName eq "Alice") or (TestPerson::firstName eq "Bob")
                            }
                        }.toList()

                    results shouldHaveSize 2
                    results.map { it.id } shouldContainExactlyInAnyOrder listOf(1, 2)
                } finally {
                    repo.close()
                }
            }
        }
    }

    context("Not returns complement of matching entities") {
        withTests(DatabaseTestSupport.databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                try {
                    repo.add(
                        TestPerson(1).apply {
                            firstName = "Alice"
                            age = 20
                        }
                    )
                    repo.add(
                        TestPerson(2).apply {
                            firstName = "Bob"
                            age = 30
                        }
                    )
                    repo.add(
                        TestPerson(3).apply {
                            firstName = "Charlie"
                            age = 40
                        }
                    )

                    val results =
                        repo.query {
                            where {
                                !(TestPerson::firstName eq "Alice")
                            }
                        }.toList()

                    results shouldHaveSize 2
                    results.map { it.id } shouldContainExactlyInAnyOrder listOf(2, 3)
                } finally {
                    repo.close()
                }
            }
        }
    }

    context("Composite predicate with And, Or, and Not") {
        withTests(DatabaseTestSupport.databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                try {
                    repo.add(
                        TestPerson(1).apply {
                            firstName = "Alice"
                            age = 20
                        }
                    )
                    repo.add(
                        TestPerson(2).apply {
                            firstName = "Alice"
                            age = 30
                        }
                    )
                    repo.add(
                        TestPerson(3).apply {
                            firstName = "Bob"
                            age = 40
                        }
                    )
                    repo.add(
                        TestPerson(4).apply {
                            firstName = "Charlie"
                            age = 50
                        }
                    )
                    repo.add(
                        TestPerson(5).apply {
                            firstName = "Dave"
                            age = 35
                        }
                    )

                    val results =
                        repo.query {
                            where {
                                (TestPerson::firstName eq "Alice") or
                                    (!(TestPerson::age lt 40))
                            }
                        }.toList()

                    results shouldHaveSize 4
                    results.map { it.id } shouldContainExactlyInAnyOrder listOf(1, 2, 3, 4)
                } finally {
                    repo.close()
                }
            }
        }
    }

    context("orderBy returns sorted results ascending") {
        withTests(DatabaseTestSupport.databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                try {
                    repo.add(
                        TestPerson(1).apply {
                            firstName = "C"
                            age = 30
                        }
                    )
                    repo.add(
                        TestPerson(2).apply {
                            firstName = "A"
                            age = 20
                        }
                    )
                    repo.add(
                        TestPerson(3).apply {
                            firstName = "B"
                            age = 10
                        }
                    )

                    val results = repo.query { orderBy(TestPerson::age) }.toList()

                    results.map { it.id } shouldBe listOf(3, 2, 1)
                } finally {
                    repo.close()
                }
            }
        }
    }

    context("orderBy returns sorted results descending") {
        withTests(DatabaseTestSupport.databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                try {
                    repo.add(
                        TestPerson(1).apply {
                            firstName = "C"
                            age = 30
                        }
                    )
                    repo.add(
                        TestPerson(2).apply {
                            firstName = "A"
                            age = 20
                        }
                    )
                    repo.add(
                        TestPerson(3).apply {
                            firstName = "B"
                            age = 10
                        }
                    )

                    val results = repo.query { orderBy(TestPerson::age, Direction.DESC) }.toList()

                    results.map { it.id } shouldBe listOf(1, 2, 3)
                } finally {
                    repo.close()
                }
            }
        }
    }

    context("limit returns top N results") {
        withTests(DatabaseTestSupport.databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                try {
                    repo.add(
                        TestPerson(1).apply {
                            firstName = "A"
                            age = 10
                        }
                    )
                    repo.add(
                        TestPerson(2).apply {
                            firstName = "B"
                            age = 20
                        }
                    )
                    repo.add(
                        TestPerson(3).apply {
                            firstName = "C"
                            age = 30
                        }
                    )
                    repo.add(
                        TestPerson(4).apply {
                            firstName = "D"
                            age = 40
                        }
                    )

                    val results =
                        repo.query {
                            orderBy(TestPerson::age)
                            limit(2)
                        }.toList()

                    results shouldHaveSize 2
                    results.map { it.id } shouldBe listOf(1, 2)
                } finally {
                    repo.close()
                }
            }
        }
    }

    context("offset skips first N results") {
        withTests(DatabaseTestSupport.databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                try {
                    repo.add(
                        TestPerson(1).apply {
                            firstName = "A"
                            age = 10
                        }
                    )
                    repo.add(
                        TestPerson(2).apply {
                            firstName = "B"
                            age = 20
                        }
                    )
                    repo.add(
                        TestPerson(3).apply {
                            firstName = "C"
                            age = 30
                        }
                    )
                    repo.add(
                        TestPerson(4).apply {
                            firstName = "D"
                            age = 40
                        }
                    )

                    val results =
                        repo.query {
                            orderBy(TestPerson::age)
                            offset(2)
                        }.toList()

                    results shouldHaveSize 2
                    results.map { it.id } shouldBe listOf(3, 4)
                } finally {
                    repo.close()
                }
            }
        }
    }

    context("limit and offset return correct page") {
        withTests(DatabaseTestSupport.databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                try {
                    repo.add(
                        TestPerson(1).apply {
                            firstName = "A"
                            age = 10
                        }
                    )
                    repo.add(
                        TestPerson(2).apply {
                            firstName = "B"
                            age = 20
                        }
                    )
                    repo.add(
                        TestPerson(3).apply {
                            firstName = "C"
                            age = 30
                        }
                    )
                    repo.add(
                        TestPerson(4).apply {
                            firstName = "D"
                            age = 40
                        }
                    )

                    val results =
                        repo.query {
                            orderBy(TestPerson::age)
                            offset(1)
                            limit(2)
                        }.toList()

                    results shouldHaveSize 2
                    results.map { it.id } shouldBe listOf(2, 3)
                } finally {
                    repo.close()
                }
            }
        }
    }

    context("composite orderBy orders by multiple properties") {
        withTests(DatabaseTestSupport.databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                try {
                    repo.add(
                        TestPerson(1).apply {
                            firstName = "Alice"
                            lastName = "Z"
                            age = 30
                        }
                    )
                    repo.add(
                        TestPerson(2).apply {
                            firstName = "Bob"
                            lastName = "A"
                            age = 30
                        }
                    )
                    repo.add(
                        TestPerson(3).apply {
                            firstName = "Alice"
                            lastName = "A"
                            age = 20
                        }
                    )

                    val results =
                        repo.query {
                            orderBy(TestPerson::age, Direction.DESC)
                            orderBy(TestPerson::lastName)
                        }.toList()

                    results.map { it.id } shouldBe listOf(2, 1, 3)
                } finally {
                    repo.close()
                }
            }
        }
    }

    context("no predicate returns all entities") {
        withTests(DatabaseTestSupport.databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                try {
                    repo.add(
                        TestPerson(1).apply {
                            firstName = "A"
                            age = 20
                        }
                    )
                    repo.add(
                        TestPerson(2).apply {
                            firstName = "B"
                            age = 30
                        }
                    )

                    val results = repo.query { }.toList()

                    results shouldHaveSize 2
                } finally {
                    repo.close()
                }
            }
        }
    }

    context("query returns lazy Sequence") {
        withTests(DatabaseTestSupport.databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                try {
                    repo.add(
                        TestPerson(1).apply {
                            firstName = "A"
                            age = 20
                        }
                    )

                    repo.activateEvents(CrudEvent.Type.READ)
                    val readCount = AtomicInteger(0)
                    repo.subscribe(CrudEvent.Type.READ) { readCount.incrementAndGet() }

                    val sequence = repo.query { where { TestPerson::firstName eq "A" } }

                    // Before terminal operation: no READ event should have fired
                    readCount.get() shouldBeExactly 0

                    // Terminal operation triggers execution and READ event
                    val rows = sequence.toList()
                    rows shouldHaveSize 1
                    rows.first().firstName shouldBe "A"

                    // emitAsync is asynchronous; poll until the event is processed
                    eventually(2.seconds) {
                        readCount.get() shouldBeExactly 1
                    }
                } finally {
                    repo.close()
                }
            }
        }
    }

    context("query results survive repo close and reopen") {
        withTests(DatabaseTestSupport.databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                try {
                    repo.add(
                        TestPerson(1).apply {
                            firstName = "Alice"
                            lastName = "Smith"
                            age = 30
                        }
                    )
                    repo.add(
                        TestPerson(2).apply {
                            firstName = "Bob"
                            lastName = "Jones"
                            age = 25
                        }
                    )
                    repo.add(
                        TestPerson(3).apply {
                            firstName = "Alice"
                            lastName = "Brown"
                            age = 35
                        }
                    )
                } finally {
                    repo.close()
                }

                val repo2 = SqlRepository(dataSource, TestPersonTableDef)
                try {
                    val results = repo2.query { where { TestPerson::firstName eq "Alice" } }.toList()

                    results shouldHaveSize 2
                    results.map { it.id } shouldContainExactlyInAnyOrder listOf(1, 3)
                } finally {
                    repo2.close()
                }
            }
        }
    }

    context("query with filter after reload returns correct subset") {
        withTests(DatabaseTestSupport.databases) { db ->
            DatabaseTestSupport.withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                try {
                    repo.add(
                        TestPerson(1).apply {
                            firstName = "A"
                            age = 10
                        }
                    )
                    repo.add(
                        TestPerson(2).apply {
                            firstName = "B"
                            age = 20
                        }
                    )
                    repo.add(
                        TestPerson(3).apply {
                            firstName = "C"
                            age = 30
                        }
                    )
                } finally {
                    repo.close()
                }

                val repo2 = SqlRepository(dataSource, TestPersonTableDef)
                try {
                    val results =
                        repo2.query {
                            where { TestPerson::age gte 20 }
                            orderBy(TestPerson::age)
                        }.toList()

                    results shouldHaveSize 2
                    results.map { it.id } shouldBe listOf(2, 3)
                } finally {
                    repo2.close()
                }
            }
        }
    }
})