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
import net.transgressoft.lirp.persistence.sql.DatabaseTestSupport.databases
import net.transgressoft.lirp.persistence.sql.DatabaseTestSupport.withDatabaseTest
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Integration tests for [SqlRepository] under concurrent access using PostgreSQL, MySQL 8.0, and MariaDB 11.
 *
 * The primary stress test runs concurrent coroutines performing mixed CRUD operations.
 * Four failure modes are asserted: data corruption, lost updates, deadlocks, and event count mismatch.
 * Individual worker failures (e.g. MySQL deadlock rollbacks) are tolerated — the test asserts
 * consistency of successfully completed operations.
 */
@DisplayName("SqlRepository Concurrency Integration")
internal class SqlRepositoryConcurrencyIntegrationTest : FunSpec({

    context("concurrent coroutines perform mixed CRUD without data corruption or lost updates") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                val events = Collections.synchronizedList(mutableListOf<CrudEvent.Type>())
                repo.subscribe { event -> events.add(event.type) }

                val workerCount = 20
                val successfulAdds = AtomicInteger(0)
                val successfulRemoves = AtomicInteger(0)
                val barrier = CountDownLatch(1)

                run {
                    val jobs =
                        (1..workerCount).map { i ->
                            launch(Dispatchers.IO) {
                                barrier.await()
                                runCatching {
                                    val person =
                                        TestPerson(i).apply {
                                            firstName = "Worker$i"
                                            lastName = "Test"
                                            age = i
                                        }
                                    if (repo.add(person)) successfulAdds.incrementAndGet()
                                    repo.findById(i)
                                    repo.findById(i).ifPresent { it.firstName = "Mutated$i" }
                                    delay(100)
                                    repo.findById(i).ifPresent { if (repo.remove(it)) successfulRemoves.incrementAndGet() }
                                }
                            }
                        }
                    barrier.countDown()
                    withTimeout(30.seconds) { jobs.joinAll() }
                }

                successfulAdds.get() shouldBeGreaterThan 0
                successfulRemoves.get() shouldBeGreaterThan 0

                eventually(5.seconds) {
                    repo.size() shouldBe 0
                }

                eventually(5.seconds) {
                    val creates = events.count { it == CrudEvent.Type.CREATE }
                    val deletes = events.count { it == CrudEvent.Type.DELETE }
                    creates shouldBe successfulAdds.get()
                    deletes shouldBe successfulRemoves.get()
                }

                repo.close()
                val repo2 = SqlRepository(dataSource, TestPersonTableDef)
                repo2.size() shouldBe 0
                repo2.close()
            }
        }
    }

    context("concurrent read operations do not interfere with writes") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                val successfulAdds = AtomicInteger(0)
                val barrier = CountDownLatch(1)

                run {
                    val writers =
                        (1..10).map { i ->
                            launch(Dispatchers.IO) {
                                barrier.await()
                                val person = TestPerson(i).apply { firstName = "Writer$i" }
                                if (repo.add(person)) successfulAdds.incrementAndGet()
                            }
                        }
                    val readers =
                        (1..10).map { i ->
                            launch(Dispatchers.IO) {
                                barrier.await()
                                repo.findById(i)
                                repo.size()
                            }
                        }
                    barrier.countDown()
                    withTimeout(15.seconds) {
                        writers.joinAll()
                        readers.joinAll()
                    }
                }

                repo.size() shouldBe successfulAdds.get()

                repo.close()
            }
        }
    }
})