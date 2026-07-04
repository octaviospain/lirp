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
import net.transgressoft.lirp.persistence.sql.DatabaseTestSupport.PERSISTED_ROW_POLL
import net.transgressoft.lirp.persistence.sql.DatabaseTestSupport.databases
import net.transgressoft.lirp.persistence.sql.DatabaseTestSupport.withDatabaseTest
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.datatest.withTests
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.DisplayName
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Integration tests asserting that [SqlRepository.loadFromStore] populates entities via the
 * KSP-generated [net.transgressoft.lirp.persistence.LirpRawInitializer] silent-setter path,
 * producing semantically equivalent entities while emitting zero [CrudEvent] or
 * [net.transgressoft.lirp.event.MutationEvent] notifications during bulk load.
 *
 * Runs against PostgreSQL, MySQL, MariaDB, and SQLite via Testcontainers.
 */
@DisplayName("SqlRepository RawInit Bulk Load Integration")
internal class SqlRepositoryRawInitLoadTest : FunSpec({

    context("[SqlRepository] bulk load via raw initializer produces semantically equivalent entities") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                // Seed the database via a first repository instance.
                val seedRepo = SqlRepository(dataSource, TestPersonTableDef)
                repeat(8) { i ->
                    seedRepo.add(
                        TestPerson(i).apply {
                            firstName = "First-$i"
                            lastName = "Last-$i"
                            age = 20 + i
                        }
                    )
                }
                seedRepo.close()

                // Construct a fresh repository — loadFromStore reads rows and applies values via
                // fromRow + (where present) the generated LirpRawInitializer silent setters.
                val repo = SqlRepository(dataSource, TestPersonTableDef)
                repo.size() shouldBe 8
                for (i in 0 until 8) {
                    val person = repo.findById(i).orElseThrow()
                    person.firstName shouldBe "First-$i"
                    person.lastName shouldBe "Last-$i"
                    person.age shouldBe 20 + i
                }
                repo.close()
            }
        }
    }

    context("[SqlRepository] bulk load emits no MutationEvent for entities populated from store") {
        withTests(databases) { db ->
            withDatabaseTest(db, TestPersonTableDef) { dataSource ->
                val seedRepo = SqlRepository(dataSource, TestPersonTableDef)
                repeat(3) { i ->
                    seedRepo.add(
                        TestPerson(i).apply {
                            firstName = "Seed-$i"
                            lastName = "Person"
                            age = 30
                        }
                    )
                }
                seedRepo.close()

                // Track CrudEvents emitted by the repo during load. Plan-04 contract: bulk load
                // must surface zero CREATE events to subscribers attached during the load itself,
                // mirroring today's "PersistentRepositoryBase.loaded flag gates emission".
                val crudEventCount = AtomicInteger(0)
                val repo = SqlRepository(dataSource, TestPersonTableDef, loadOnInit = false)
                repo.subscribe { _ -> crudEventCount.incrementAndGet() }
                repo.load()

                crudEventCount.get() shouldBe 0

                // Subscribers attached AFTER load see no retroactive MutationEvent either.
                val mutationEvents = CopyOnWriteArrayList<String>()
                for (entity in repo) {
                    entity.subscribe { ev -> mutationEvents += ev.toString() }
                }
                // Sanity check: one mutation should fire exactly one event to prove the
                // subscription is wired correctly.
                val first = repo.findById(0).orElseThrow()
                first.age = 99

                // Poll until the async reactive dispatch delivers the single mutation event, rather
                // than racing it with a fixed sleep.
                eventually(PERSISTED_ROW_POLL) {
                    mutationEvents.size shouldBe 1
                    mutationEvents.first() shouldNotBe ""
                }

                repo.close()
            }
        }
    }
})