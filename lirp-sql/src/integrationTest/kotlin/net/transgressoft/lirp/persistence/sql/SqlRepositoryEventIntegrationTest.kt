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
import net.transgressoft.lirp.event.CrudEvent.Type.UPDATE
import net.transgressoft.lirp.persistence.sql.DatabaseTestSupport.databases
import net.transgressoft.lirp.persistence.sql.DatabaseTestSupport.withDatabaseTest
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * Integration tests for [SqlRepository] event emission against PostgreSQL, MySQL 8.0, and MariaDB 11.
 *
 * Verifies that [CrudEvent] events (CREATE, UPDATE, DELETE) are emitted correctly by the repository
 * on CRUD operations, and that entity-level [net.transgressoft.lirp.event.MutationEvent]s fire when
 * reactive entity properties are mutated.
 */
@DisplayName("SqlRepository Event Integration")
internal class SqlRepositoryEventIntegrationTest : FunSpec({

    context("emits CREATE CrudEvent on add") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                val received = AtomicReference<CrudEvent.Type?>()
                repo.subscribe { event -> received.set(event.type) }
                delay(50.milliseconds) // let SharedFlow collector coroutine start

                repo.add(TestPerson(1).apply { firstName = "Alice" })

                eventually(5.seconds) {
                    received.get() shouldBe CrudEvent.Type.CREATE
                }

                repo.close()
            }
        }
    }

    context("emits DELETE CrudEvent on remove") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                val received = AtomicReference<CrudEvent.Type?>()
                // Subscribe before add so the collector coroutine is running when DELETE fires
                repo.subscribe { event -> received.set(event.type) }
                delay(50.milliseconds) // let SharedFlow collector coroutine start

                val person = TestPerson(2).apply { firstName = "Bob" }
                repo.add(person)
                repo.remove(person)

                eventually(5.seconds) {
                    received.get() shouldBe CrudEvent.Type.DELETE
                }

                repo.close()
            }
        }
    }

    context("emits DELETE CrudEvent on clear") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                val eventTypes = Collections.synchronizedList(mutableListOf<CrudEvent.Type>())
                // Subscribe before add so the collector coroutine is running when DELETE fires
                repo.subscribe { event -> eventTypes.add(event.type) }
                delay(50.milliseconds) // let SharedFlow collector coroutine start

                repo.add(TestPerson(3).apply { firstName = "Carol" })
                repo.clear()

                eventually(5.seconds) {
                    (CrudEvent.Type.DELETE in eventTypes).shouldBeTrue()
                }

                repo.close()
            }
        }
    }

    context("entity MutationEvent fires on property change") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                repo.add(TestPerson(4).apply { firstName = "Dave" })

                val mutationReceived = AtomicBoolean(false)
                val person = repo.findById(4).get()
                person.subscribe { mutationReceived.set(true) }
                delay(50.milliseconds) // let SharedFlow collector coroutine start

                person.firstName = "Changed"

                eventually(5.seconds) {
                    mutationReceived.get().shouldBeTrue()
                }

                repo.close()
            }
        }
    }

    context("CrudEvent and MutationEvent both fire on mutation-triggered persist") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                val crudEventTypes = Collections.synchronizedList(mutableListOf<CrudEvent.Type>())
                repo.subscribe { event -> crudEventTypes.add(event.type) }

                repo.add(TestPerson(5).apply { firstName = "Eve" })

                val mutationReceived = AtomicBoolean(false)
                val person = repo.findById(5).get()
                person.subscribe { mutationReceived.set(true) }
                delay(50.milliseconds) // let SharedFlow collector coroutine start

                person.firstName = "Evelyn"

                eventually(5.seconds) {
                    mutationReceived.get().shouldBeTrue()
                    (UPDATE in crudEventTypes).shouldBeTrue()
                }

                repo.close()
            }
        }
    }
})