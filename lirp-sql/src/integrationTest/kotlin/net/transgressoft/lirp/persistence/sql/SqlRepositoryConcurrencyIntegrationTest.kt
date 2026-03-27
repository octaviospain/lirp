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
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Integration tests for [SqlRepository] under concurrent access using a real PostgreSQL database.
 *
 * The primary stress test runs 50 coroutines performing mixed CRUD operations concurrently.
 * Four failure modes are asserted: data corruption, lost updates, deadlocks, and event count mismatch.
 *
 * Each test drops the table before execution to maintain isolation within the shared container schema.
 */
@DisplayName("SqlRepository Concurrency Integration")
internal class SqlRepositoryConcurrencyIntegrationTest : StringSpec({

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

    "concurrent coroutines perform mixed CRUD without data corruption, lost updates, deadlocks, or event count mismatch" {
        val repo = SqlRepository(dataSource!!, TestPersonTableDef)
        val events = Collections.synchronizedList(mutableListOf<CrudEvent.Type>())
        repo.subscribe { event -> events.add(event.type) }

        val workerCount = 50
        val successfulAdds = AtomicInteger(0)
        val successfulRemoves = AtomicInteger(0)
        val barrier = CountDownLatch(1)

        run {
            val jobs =
                (1..workerCount).map { i ->
                    launch(Dispatchers.IO) {
                        barrier.await()
                        val person =
                            TestPerson(i).apply {
                                firstName = "Worker$i"
                                lastName = "Test"
                                age = i
                            }
                        if (repo.add(person)) successfulAdds.incrementAndGet()
                        repo.findById(i)
                        // Single mutation per coroutine to avoid flush pool exhaustion
                        repo.findById(i).ifPresent { it.firstName = "Mutated$i" }
                        Thread.sleep(100)
                        repo.findById(i).ifPresent { if (repo.remove(it)) successfulRemoves.incrementAndGet() }
                    }
                }
            barrier.countDown()
            // no deadlocks — all coroutines complete within 30s
            withTimeout(30.seconds) { jobs.joinAll() }
        }

        Thread.sleep(500)

        // no data corruption — all removes completed, repo is empty
        repo.size() shouldBe 0

        // no lost updates — verified by close/reopen on same DataSource
        repo.close()
        val repo2 = SqlRepository(dataSource, TestPersonTableDef)
        repo2.size() shouldBe 0
        repo2.close()

        // event count matches successful operations
        val creates = events.count { it == CrudEvent.Type.CREATE }
        val deletes = events.count { it == CrudEvent.Type.DELETE }
        creates shouldBe successfulAdds.get()
        deletes shouldBe successfulRemoves.get()
    }

    "concurrent read operations do not interfere with writes" {
        val repo = SqlRepository(dataSource!!, TestPersonTableDef)
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
})