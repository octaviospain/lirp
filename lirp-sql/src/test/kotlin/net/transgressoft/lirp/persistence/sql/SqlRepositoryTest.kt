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
import net.transgressoft.lirp.persistence.RegistryBase
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * Unit tests for [SqlRepository] using H2 in-memory databases to verify SQL-first CRUD semantics,
 * event emission, lifecycle management, and entity loading on initialization.
 */
@DisplayName("SqlRepository")
internal class SqlRepositoryTest : FunSpec({

    /** Returns a unique JDBC URL for an isolated H2 in-memory database per test. */
    fun freshJdbcUrl() = "jdbc:h2:mem:${UUID.randomUUID()};DB_CLOSE_DELAY=-1"

    afterEach {
        // Ensure mutable aggregate test repos are deregistered from the shared LirpContext.default
        // even if a test fails mid-assertion and doesn't reach its close() calls.
        RegistryBase.deregisterRepository(SqlTestTrack::class.java)
        RegistryBase.deregisterRepository(MutablePlaylistSql::class.java)
    }

    fun buildExternalDataSource(jdbcUrl: String): HikariDataSource {
        val config =
            HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                this.maximumPoolSize = 5
            }
        return HikariDataSource(config)
    }

    test("adds entity and persists to database") {
        val jdbcUrl = freshJdbcUrl()
        val repo = SqlRepository(jdbcUrl, TestPersonTableDef)
        val person =
            TestPerson(1).apply {
                firstName = "Alice"
                lastName = "Smith"
                age = 30
            }

        repo.add(person)

        repo.size() shouldBe 1

        // close() triggers a synchronous flush so the pending insert is written to the DB
        // before the second repository reads it
        repo.close()
        val repo2 = SqlRepository(jdbcUrl, TestPersonTableDef)
        repo2.size() shouldBe 1
        repo2.findById(1).shouldBePresent { it.firstName shouldBe "Alice" }

        repo2.close()
    }

    test("emits CREATE event on add") {
        val repo = SqlRepository(freshJdbcUrl(), TestPersonTableDef)
        val received = AtomicReference<CrudEvent.Type?>()
        repo.subscribe { event -> received.set(event.type) }
        delay(50.milliseconds) // let SharedFlow collector coroutine start

        repo.add(TestPerson(1).apply { firstName = "Bob" })

        eventually(5.seconds) {
            received.get() shouldBe CrudEvent.Type.CREATE
        }

        repo.close()
    }

    test("removes entity and deletes from database") {
        val jdbcUrl = freshJdbcUrl()
        val repo = SqlRepository(jdbcUrl, TestPersonTableDef)
        val person =
            TestPerson(2).apply {
                firstName = "Carol"
                lastName = "Jones"
                age = 25
            }
        repo.add(person)

        repo.remove(person)

        repo.size() shouldBe 0

        // Verify row was deleted in DB
        val repo2 = SqlRepository(jdbcUrl, TestPersonTableDef)
        repo2.size() shouldBe 0

        repo.close()
        repo2.close()
    }

    test("emits DELETE event on remove") {
        val repo = SqlRepository(freshJdbcUrl(), TestPersonTableDef)
        val person = TestPerson(3).apply { firstName = "Dave" }
        val received = AtomicReference<CrudEvent.Type?>()
        // Subscribe before add so the subscriber coroutine is active before events are emitted
        repo.subscribe { event -> received.set(event.type) }
        delay(50.milliseconds) // let SharedFlow collector coroutine start

        repo.add(person)
        repo.remove(person)

        eventually(5.seconds) {
            received.get() shouldBe CrudEvent.Type.DELETE
        }

        repo.close()
    }

    test("loads existing rows from database on initialization") {
        val jdbcUrl = freshJdbcUrl()
        // First repository inserts a row
        val repo1 = SqlRepository(jdbcUrl, TestPersonTableDef)
        repo1.add(
            TestPerson(10).apply {
                firstName = "Eve"
                lastName = "Brown"
                age = 40
            }
        )
        repo1.close()

        // Second repository on the same DB should load the pre-existing row
        val repo2 = SqlRepository(jdbcUrl, TestPersonTableDef)
        repo2.size() shouldBe 1
        repo2.findById(10).shouldBePresent { it.firstName shouldBe "Eve" }

        repo2.close()
    }

    test("auto-creates table on initialization") {
        // Creating the repository on a fresh DB should not throw; table is created automatically
        val repo = SqlRepository(freshJdbcUrl(), TestPersonTableDef)
        repo.size() shouldBe 0
        repo.close()
    }

    test("throws IllegalStateException on add after close") {
        val repo = SqlRepository(freshJdbcUrl(), TestPersonTableDef)
        repo.close()

        shouldThrow<IllegalStateException> {
            repo.add(TestPerson(99))
        }
    }

    test("closes HikariCP pool when owning the datasource") {
        val repo = SqlRepository(freshJdbcUrl(), TestPersonTableDef)
        // Access the HikariDataSource to verify it is shut down after repo.close()
        val dataSourceField = SqlRepository::class.java.getDeclaredField("dataSource")
        dataSourceField.isAccessible = true
        val hikariDs = dataSourceField.get(repo) as HikariDataSource

        repo.close()

        hikariDs.isClosed.shouldBeTrue()
    }

    test("does not close user-provided datasource on close") {
        val jdbcUrl = freshJdbcUrl()
        val externalDs = buildExternalDataSource(jdbcUrl)
        val repo = SqlRepository(externalDs, TestPersonTableDef)

        repo.close()

        externalDs.isClosed shouldBe false
        externalDs.close()
    }

    test("persists entity mutation to database via flush") {
        val jdbcUrl = freshJdbcUrl()
        val repo = SqlRepository(jdbcUrl, TestPersonTableDef)
        val person =
            TestPerson(5).apply {
                firstName = "Frank"
                lastName = "Lee"
                age = 20
            }
        repo.add(person)

        // Mutate the entity — triggers the subscription callback and synchronous flush
        person.firstName = "Franklin"

        eventually(5.seconds) {
            val repo2 = SqlRepository(jdbcUrl, TestPersonTableDef)
            repo2.findById(5).shouldBePresent { it.firstName shouldBe "Franklin" }
            repo2.close()
        }

        repo.close()
    }

    test("clears all entities from database and in-memory") {
        val jdbcUrl = freshJdbcUrl()
        val repo = SqlRepository(jdbcUrl, TestPersonTableDef)
        repo.add(TestPerson(20).apply { firstName = "Grace" })
        repo.add(TestPerson(21).apply { firstName = "Hank" })

        repo.clear()

        repo.size() shouldBe 0

        val repo2 = SqlRepository(jdbcUrl, TestPersonTableDef)
        repo2.size() shouldBe 0

        repo.close()
        repo2.close()
    }

    test("removeAll deletes specified entities from database") {
        val jdbcUrl = freshJdbcUrl()
        val repo = SqlRepository(jdbcUrl, TestPersonTableDef)
        val p1 = TestPerson(30).apply { firstName = "Iris" }
        val p2 = TestPerson(31).apply { firstName = "Jack" }
        val p3 = TestPerson(32).apply { firstName = "Kate" }
        repo.add(p1)
        repo.add(p2)
        repo.add(p3)

        repo.removeAll(listOf(p1, p2))

        repo.size() shouldBe 1

        // close() triggers a synchronous flush so pending ops are written to the DB
        // before the second repository reads it
        repo.close()
        val repo2 = SqlRepository(jdbcUrl, TestPersonTableDef)
        repo2.size() shouldBe 1
        repo2.findById(32).shouldBePresent { it.firstName shouldBe "Kate" }

        repo2.close()
    }

    context("Deferred loading") {

        test("constructs with table created but empty when loadOnInit is false") {
            val jdbcUrl = freshJdbcUrl()
            // Pre-populate the database via an eager repo, then close it
            val seedRepo = SqlRepository(jdbcUrl, TestPersonTableDef)
            seedRepo.add(TestPerson(1).apply { firstName = "Alice" })
            seedRepo.close()

            val repo = SqlRepository(jdbcUrl, TestPersonTableDef, loadOnInit = false)

            repo.size() shouldBe 0
            repo.isLoaded shouldBe false

            repo.close()
        }

        test("load() populates repository from database") {
            val jdbcUrl = freshJdbcUrl()
            val seedRepo = SqlRepository(jdbcUrl, TestPersonTableDef)
            seedRepo.add(TestPerson(1).apply { firstName = "Alice" })
            seedRepo.add(TestPerson(2).apply { firstName = "Bob" })
            seedRepo.close()

            val repo = SqlRepository(jdbcUrl, TestPersonTableDef, loadOnInit = false)
            repo.size() shouldBe 0

            repo.load()

            repo.size() shouldBe 2
            repo.isLoaded shouldBe true
            repo.findById(1).shouldBePresent { it.firstName shouldBe "Alice" }
            repo.findById(2).shouldBePresent { it.firstName shouldBe "Bob" }

            repo.close()
        }

        test("load() twice throws IllegalStateException") {
            val repo = SqlRepository(freshJdbcUrl(), TestPersonTableDef, loadOnInit = false)
            repo.load()

            shouldThrow<IllegalStateException> { repo.load() }

            repo.close()
        }

        test("add() before load() throws IllegalStateException") {
            val repo = SqlRepository(freshJdbcUrl(), TestPersonTableDef, loadOnInit = false)

            shouldThrow<IllegalStateException> {
                repo.add(TestPerson(99).apply { firstName = "Zara" })
            }

            repo.close()
        }

        test("isLoaded reflects state before and after load()") {
            val repo = SqlRepository(freshJdbcUrl(), TestPersonTableDef, loadOnInit = false)
            repo.isLoaded shouldBe false

            repo.load()

            repo.isLoaded shouldBe true

            repo.close()
        }

        test("CRUD operations work normally after explicit load()") {
            val jdbcUrl = freshJdbcUrl()
            val repo = SqlRepository(jdbcUrl, TestPersonTableDef, loadOnInit = false)
            repo.load()

            repo.add(TestPerson(10).apply { firstName = "Carol" })

            repo.size() shouldBe 1
            repo.close()

            val repo2 = SqlRepository(jdbcUrl, TestPersonTableDef)
            repo2.size() shouldBe 1
            repo2.findById(10).shouldBePresent { it.firstName shouldBe "Carol" }
            repo2.close()
        }

        test("default loadOnInit=true loads rows eagerly") {
            val jdbcUrl = freshJdbcUrl()
            val seedRepo = SqlRepository(jdbcUrl, TestPersonTableDef)
            seedRepo.add(TestPerson(5).apply { firstName = "Eve" })
            seedRepo.close()

            val repo = SqlRepository(jdbcUrl, TestPersonTableDef)

            repo.size() shouldBe 1
            repo.isLoaded shouldBe true
            repo.findById(5).shouldBePresent { it.firstName shouldBe "Eve" }

            repo.close()
        }
    }

    context("Mutable aggregate collection delegates") {

        test("persists mutable aggregate trackIds and reloads them") {
            val jdbcUrl = freshJdbcUrl()

            val trackRepo = SqlTestTrackRepository(jdbcUrl)
            val playlistRepo = MutablePlaylistSqlRepository(jdbcUrl)

            val track1 = SqlTestTrack(1).also { it.title = "Track A" }
            val track2 = SqlTestTrack(2).also { it.title = "Track B" }
            trackRepo.add(track1)
            trackRepo.add(track2)

            val playlist = MutablePlaylistSql(1L).also { it.name = "SQL Playlist" }
            playlistRepo.add(playlist)

            playlist.tracks.add(track1)
            playlist.tracks.add(track2)
            delay(50.milliseconds)

            playlistRepo.close()
            trackRepo.close()

            val trackRepo2 = SqlTestTrackRepository(jdbcUrl)
            val playlistRepo2 = MutablePlaylistSqlRepository(jdbcUrl)

            playlistRepo2.findById(1L).shouldBePresent {
                it.trackIds shouldContainExactly listOf(1, 2)
                it.tracks.resolveAll() shouldHaveSize 2
            }

            playlistRepo2.close()
            trackRepo2.close()
        }

        test("persists further mutations after reload") {
            val jdbcUrl = freshJdbcUrl()

            val trackRepo1 = SqlTestTrackRepository(jdbcUrl)
            val playlistRepo1 = MutablePlaylistSqlRepository(jdbcUrl)

            val t1 = SqlTestTrack(1).also { it.title = "T1" }
            val t2 = SqlTestTrack(2).also { it.title = "T2" }
            val t3 = SqlTestTrack(3).also { it.title = "T3" }
            trackRepo1.add(t1)
            trackRepo1.add(t2)
            trackRepo1.add(t3)

            val playlist = MutablePlaylistSql(1L).also { it.name = "Evolving" }
            playlistRepo1.add(playlist)
            playlist.tracks.add(t1)
            playlist.tracks.add(t2)
            delay(50.milliseconds)

            playlistRepo1.close()
            trackRepo1.close()

            val trackRepo2 = SqlTestTrackRepository(jdbcUrl)
            val playlistRepo2 = MutablePlaylistSqlRepository(jdbcUrl)

            val reloaded = playlistRepo2.findById(1L).get()
            reloaded.trackIds shouldContainExactly listOf(1, 2)

            val reloadedT1 = trackRepo2.findById(1).get()
            val reloadedT3 = trackRepo2.findById(3).get()
            reloaded.tracks.add(reloadedT3)
            reloaded.tracks.remove(reloadedT1)
            delay(50.milliseconds)

            playlistRepo2.close()
            trackRepo2.close()

            val trackRepo3 = SqlTestTrackRepository(jdbcUrl)
            val playlistRepo3 = MutablePlaylistSqlRepository(jdbcUrl)

            playlistRepo3.findById(1L).shouldBePresent {
                it.trackIds shouldContainExactly listOf(2, 3)
            }

            playlistRepo3.close()
            trackRepo3.close()
        }

        test("addAll on mutable aggregate persists all added trackIds") {
            val jdbcUrl = freshJdbcUrl()
            val trackRepo = SqlTestTrackRepository(jdbcUrl)
            val playlistRepo = MutablePlaylistSqlRepository(jdbcUrl)

            val t1 = SqlTestTrack(1).also { it.title = "T1" }
            val t2 = SqlTestTrack(2).also { it.title = "T2" }
            val t3 = SqlTestTrack(3).also { it.title = "T3" }
            trackRepo.add(t1)
            trackRepo.add(t2)
            trackRepo.add(t3)

            val playlist = MutablePlaylistSql(1L).also { it.name = "Bulk" }
            playlistRepo.add(playlist)

            playlist.tracks.addAll(listOf(t1, t2, t3))
            delay(50.milliseconds)

            playlistRepo.close()
            trackRepo.close()

            val trackRepo2 = SqlTestTrackRepository(jdbcUrl)
            val playlistRepo2 = MutablePlaylistSqlRepository(jdbcUrl)

            playlistRepo2.findById(1L).shouldBePresent {
                it.trackIds shouldContainExactly listOf(1, 2, 3)
                it.tracks.resolveAll() shouldHaveSize 3
            }

            playlistRepo2.close()
            trackRepo2.close()
        }

        test("removeAll on mutable aggregate persists remaining trackIds") {
            val jdbcUrl = freshJdbcUrl()
            val trackRepo = SqlTestTrackRepository(jdbcUrl)
            val playlistRepo = MutablePlaylistSqlRepository(jdbcUrl)

            val t1 = SqlTestTrack(1).also { it.title = "T1" }
            val t2 = SqlTestTrack(2).also { it.title = "T2" }
            val t3 = SqlTestTrack(3).also { it.title = "T3" }
            trackRepo.add(t1)
            trackRepo.add(t2)
            trackRepo.add(t3)

            val playlist = MutablePlaylistSql(1L, listOf(1, 2, 3)).also { it.name = "BulkRemove" }
            playlistRepo.add(playlist)

            playlist.tracks.removeAll(listOf(t1, t3))
            delay(50.milliseconds)

            playlistRepo.close()
            trackRepo.close()

            val trackRepo2 = SqlTestTrackRepository(jdbcUrl)
            val playlistRepo2 = MutablePlaylistSqlRepository(jdbcUrl)

            playlistRepo2.findById(1L).shouldBePresent {
                it.trackIds shouldContainExactly listOf(2)
            }

            playlistRepo2.close()
            trackRepo2.close()
        }

        test("emits UPDATE event when mutable aggregate collection is mutated") {
            val jdbcUrl = freshJdbcUrl()
            val trackRepo = SqlTestTrackRepository(jdbcUrl)
            val playlistRepo = MutablePlaylistSqlRepository(jdbcUrl)
            val received = AtomicReference<CrudEvent.Type?>()
            playlistRepo.subscribe { event -> received.set(event.type) }
            delay(100.milliseconds)

            val t1 = SqlTestTrack(1).also { it.title = "Track" }
            trackRepo.add(t1)

            val playlist = MutablePlaylistSql(1L).also { it.name = "EventTest" }
            playlistRepo.add(playlist)
            delay(50.milliseconds)

            playlist.tracks.add(t1)

            eventually(10.seconds) {
                received.get() shouldBe CrudEvent.Type.UPDATE
            }

            playlistRepo.close()
            trackRepo.close()
        }

        test("loads entity with initial trackIds and delegate resolves correctly") {
            val jdbcUrl = freshJdbcUrl()
            val trackRepo = SqlTestTrackRepository(jdbcUrl)
            val playlistRepo = MutablePlaylistSqlRepository(jdbcUrl)

            val t1 = SqlTestTrack(10).also { it.title = "Pre-existing Track" }
            trackRepo.add(t1)

            val playlist = MutablePlaylistSql(1L, listOf(10)).also { it.name = "Pre-loaded" }
            playlistRepo.add(playlist)

            playlistRepo.close()
            trackRepo.close()

            val trackRepo2 = SqlTestTrackRepository(jdbcUrl)
            val playlistRepo2 = MutablePlaylistSqlRepository(jdbcUrl)

            playlistRepo2.findById(1L).shouldBePresent {
                it.trackIds shouldContainExactly listOf(10)
                it.tracks.resolveAll() shouldHaveSize 1
                it.tracks.resolveAll().first().id shouldBe 10
            }

            playlistRepo2.close()
            trackRepo2.close()
        }

        test("clear on mutable aggregate persists empty state") {
            val jdbcUrl = freshJdbcUrl()
            val trackRepo1 = SqlTestTrackRepository(jdbcUrl)
            val playlistRepo1 = MutablePlaylistSqlRepository(jdbcUrl)

            val t1 = SqlTestTrack(1).also { it.title = "T1" }
            trackRepo1.add(t1)

            val playlist = MutablePlaylistSql(1L).also { it.name = "ClearMe" }
            playlistRepo1.add(playlist)
            playlist.tracks.add(t1)
            playlist.tracks.clear()
            delay(50.milliseconds)

            playlistRepo1.close()
            trackRepo1.close()

            val trackRepo2 = SqlTestTrackRepository(jdbcUrl)
            val playlistRepo2 = MutablePlaylistSqlRepository(jdbcUrl)

            playlistRepo2.findById(1L).shouldBePresent {
                it.trackIds shouldBe emptyList()
            }

            playlistRepo2.close()
            trackRepo2.close()
        }
    }
})