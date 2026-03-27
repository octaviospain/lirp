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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.DisplayName
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Integration tests for [SqlRepository] event emission against a real PostgreSQL database.
 *
 * Verifies that [CrudEvent] events (CREATE, UPDATE, DELETE) are emitted correctly by the repository
 * on CRUD operations, and that entity-level [net.transgressoft.lirp.event.MutationEvent]s fire when
 * reactive entity properties are mutated.
 *
 * Each test drops the table before execution to maintain isolation within the shared container schema.
 */
@DisplayName("SqlRepository Event Integration")
internal class SqlRepositoryEventIntegrationTest : FunSpec({

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

    test("emits CREATE CrudEvent on add") {
        val repo = SqlRepository(dataSource!!, TestPersonTableDef)
        val received = AtomicReference<CrudEvent.Type?>()
        repo.subscribe { event -> received.set(event.type) }

        repo.add(TestPerson(1).apply { firstName = "Alice" })
        Thread.sleep(200)

        received.get() shouldBe CrudEvent.Type.CREATE

        repo.close()
    }

    test("emits DELETE CrudEvent on remove") {
        val repo = SqlRepository(dataSource!!, TestPersonTableDef)
        val person = TestPerson(2).apply { firstName = "Bob" }
        repo.add(person)

        val received = AtomicReference<CrudEvent.Type?>()
        repo.subscribe { event -> received.set(event.type) }

        repo.remove(person)
        Thread.sleep(200)

        received.get() shouldBe CrudEvent.Type.DELETE

        repo.close()
    }

    test("emits DELETE CrudEvent on clear") {
        val repo = SqlRepository(dataSource!!, TestPersonTableDef)
        repo.add(TestPerson(3).apply { firstName = "Carol" })

        val eventTypes = Collections.synchronizedList(mutableListOf<CrudEvent.Type>())
        repo.subscribe { event -> eventTypes.add(event.type) }

        repo.clear()
        Thread.sleep(200)

        (CrudEvent.Type.DELETE in eventTypes).shouldBeTrue()

        repo.close()
    }

    test("entity MutationEvent fires on property change") {
        val repo = SqlRepository(dataSource!!, TestPersonTableDef)
        repo.add(TestPerson(4).apply { firstName = "Dave" })

        val mutationReceived = AtomicBoolean(false)
        val person = repo.findById(4).get()
        person.subscribe { mutationReceived.set(true) }
        // Allow the subscription coroutine to start collecting before we fire the event
        Thread.sleep(100)

        person.firstName = "Changed"
        Thread.sleep(200)

        mutationReceived.get().shouldBeTrue()

        repo.close()
    }

    test("CrudEvent and MutationEvent both fire on mutation-triggered persist") {
        val repo = SqlRepository(dataSource!!, TestPersonTableDef)
        val crudEventTypes = Collections.synchronizedList(mutableListOf<CrudEvent.Type>())
        repo.subscribe { event -> crudEventTypes.add(event.type) }

        repo.add(TestPerson(5).apply { firstName = "Eve" })
        Thread.sleep(100)

        val mutationReceived = AtomicBoolean(false)
        val person = repo.findById(5).get()
        person.subscribe { mutationReceived.set(true) }
        // Allow the subscription coroutine to start collecting before we fire the event
        Thread.sleep(100)

        person.firstName = "Evelyn"
        Thread.sleep(300)

        mutationReceived.get().shouldBeTrue()
        (CrudEvent.Type.UPDATE in crudEventTypes).shouldBeTrue()

        repo.close()
    }
})
