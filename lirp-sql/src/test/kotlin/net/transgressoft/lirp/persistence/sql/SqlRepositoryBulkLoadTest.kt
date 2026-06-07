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

import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.ColumnDef
import net.transgressoft.lirp.persistence.ColumnType
import net.transgressoft.lirp.persistence.LirpRawInitializer
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import java.sql.Connection
import java.sql.Statement
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

/**
 * Bulk-load and junction-sync unit tests for [SqlRepository], exercising the H2-backed end-to-end
 * round-trip for an entity with one ordered junction descriptor. Hand-rolled fixture entities and
 * table defs avoid coupling these tests to the KSP fixture-generation path; the same wiring is
 * proven in `lirp-ksp:test` and the integration tests under `src/integrationTest`.
 */
class SqlRepositoryBulkLoadTest : StringSpec({

    fun freshJdbcUrl() = "jdbc:h2:mem:bulkload-${UUID.randomUUID()};DB_CLOSE_DELAY=-1"

    fun newDataSource(jdbcUrl: String): HikariDataSource {
        val config =
            HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                this.maximumPoolSize = 5
            }
        return HikariDataSource(config)
    }

    "loadFromStore returns playlists with track IDs in position order" {
        val ds = newDataSource(freshJdbcUrl())
        try {
            // Construct the repo with loadOnInit=false so Exposed creates the schema (entity table
            // + junction table) using its own quoting rules. Then seed rows via raw SQL using the
            // same quoting (h2 reserved-word `position` and entity column `name` are quoted by
            // Exposed at DDL time, so reads must also quote).
            val repo = SqlRepository(ds, TestPlaylistTableDef, loadOnInit = false)
            try {
                ds.connection.use { conn ->
                    conn.createStatement().use { st ->
                        st.execute("INSERT INTO test_playlists (id, \"name\") VALUES (1, 'first')")
                        st.execute("INSERT INTO test_playlists (id, \"name\") VALUES (2, 'second')")
                        // Insert tracks out of position order to prove the load path sorts.
                        st.execute("INSERT INTO test_playlist_tracks (parent_id, item_id, \"position\") VALUES (1, 30, 2)")
                        st.execute("INSERT INTO test_playlist_tracks (parent_id, item_id, \"position\") VALUES (1, 10, 0)")
                        st.execute("INSERT INTO test_playlist_tracks (parent_id, item_id, \"position\") VALUES (1, 20, 1)")
                        st.execute("INSERT INTO test_playlist_tracks (parent_id, item_id, \"position\") VALUES (2, 50, 0)")
                    }
                }
                repo.load()
                repo.size() shouldBe 2
                repo.findById(1).get().trackIds shouldContainExactly listOf(10, 20, 30)
                repo.findById(2).get().trackIds shouldContainExactly listOf(50)
            } finally {
                repo.close()
            }
        } finally {
            ds.close()
        }
    }

    "loadFromStore issues exactly two queries for one junction descriptor" {
        val ds = newDataSource(freshJdbcUrl())
        try {
            // Pre-construct a sibling repo to materialise the schema (Exposed-driven DDL), then
            // close it. The data is seeded via raw SQL against the now-existing tables.
            SqlRepository(ds, TestPlaylistTableDef, loadOnInit = false).close()
            ds.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.execute("INSERT INTO test_playlists (id, \"name\") VALUES (1, 'pl')")
                    st.execute("INSERT INTO test_playlist_tracks (parent_id, item_id, \"position\") VALUES (1, 10, 0)")
                    st.execute("INSERT INTO test_playlist_tracks (parent_id, item_id, \"position\") VALUES (1, 20, 1)")
                }
            }

            val counting = QueryCountingDataSource(ds)
            val repo = SqlRepository(counting, TestPlaylistTableDef, loadOnInit = false)
            try {
                counting.selectCount.set(0)
                repo.load()
                // One SELECT against test_playlists, one against test_playlist_tracks.
                counting.selectCount.get() shouldBe 2
                repo.size() shouldBe 1
                repo.findById(1).get().trackIds shouldContainExactly listOf(10, 20)
            } finally {
                repo.close()
            }
        } finally {
            ds.close()
        }
    }

    "loadFromStore issues exactly one query when junctionTableDefs is empty" {
        val ds = newDataSource(freshJdbcUrl())
        try {
            SqlRepository(ds, TestTrackTableDef, loadOnInit = false).close()
            ds.connection.use { conn ->
                conn.createStatement().use { st ->
                    st.execute("INSERT INTO test_tracks (id, title) VALUES (1, 'one')")
                    st.execute("INSERT INTO test_tracks (id, title) VALUES (2, 'two')")
                }
            }

            val counting = QueryCountingDataSource(ds)
            val repo = SqlRepository(counting, TestTrackTableDef, loadOnInit = false)
            try {
                counting.selectCount.set(0)
                repo.load()
                counting.selectCount.get() shouldBe 1
                repo.size() shouldBe 2
            } finally {
                repo.close()
            }
        } finally {
            ds.close()
        }
    }

    "writePending replaces all junction rows on entity update" {
        val ds = newDataSource(freshJdbcUrl())
        try {
            val repo = SqlRepository(ds, TestPlaylistTableDef)
            try {
                val playlist =
                    TestPlaylist(1).apply {
                        name = "songs"
                        trackIds = listOf(10, 20, 30)
                    }
                repo.add(playlist)
                repo.close() // synchronous flush

                // Reload and verify three rows at positions 0, 1, 2.
                ds.connection.use { conn ->
                    val rows = mutableListOf<Triple<Int, Int, Int>>()
                    conn.createStatement().use { st ->
                        st.executeQuery(
                            "SELECT parent_id, item_id, \"position\" FROM test_playlist_tracks ORDER BY \"position\""
                        ).use { rs ->
                            while (rs.next()) {
                                rows.add(Triple(rs.getInt(1), rs.getInt(2), rs.getInt(3)))
                            }
                        }
                    }
                    rows shouldContainExactly
                        listOf(Triple(1, 10, 0), Triple(1, 20, 1), Triple(1, 30, 2))
                }

                // Re-open, mutate to a shorter list, flush, and verify the wholesale replace.
                val repo2 = SqlRepository(ds, TestPlaylistTableDef)
                try {
                    val reloaded = repo2.findById(1).get()
                    reloaded.trackIds shouldContainExactly listOf(10, 20, 30)
                    reloaded.trackIds = listOf(20, 30)
                    // The PendingUpdate is enqueued via the mutateAndPublish pipeline; force flush.
                    eventually(2.seconds) {
                        repo2.findById(1).get().trackIds shouldContainExactly listOf(20, 30)
                    }
                } finally {
                    repo2.close()
                }

                ds.connection.use { conn ->
                    val rows = mutableListOf<Triple<Int, Int, Int>>()
                    conn.createStatement().use { st ->
                        st.executeQuery(
                            "SELECT parent_id, item_id, \"position\" FROM test_playlist_tracks ORDER BY \"position\""
                        ).use { rs ->
                            while (rs.next()) {
                                rows.add(Triple(rs.getInt(1), rs.getInt(2), rs.getInt(3)))
                            }
                        }
                    }
                    rows shouldContainExactly listOf(Triple(1, 20, 0), Triple(1, 30, 1))
                }
            } finally {
                runCatching { repo.close() }
            }
        } finally {
            ds.close()
        }
    }

    "installJunctionForeignKeys is idempotent" {
        val ds = newDataSource(freshJdbcUrl())
        try {
            // Stand up both the item table (TestTrack) and the parent + junction tables
            // (TestPlaylist) via Exposed-driven DDL so column quoting matches what the FK install
            // path emits. The trackRepo is closed after init; the playlist repo holds the schema
            // open for the duration of the FK install assertions.
            val trackRepo = SqlRepository(ds, TestTrackTableDef, loadOnInit = false)
            trackRepo.close()
            val repo = SqlRepository(ds, TestPlaylistTableDef, loadOnInit = false)
            try {
                fun fkCount(): Int =
                    ds.connection.use { conn ->
                        conn.createStatement().use { st ->
                            // H2 v2 stores foreign keys in INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS
                            // via the constraint catalog; using TABLE_CONSTRAINTS with UPPER on the
                            // joined fk-source table is the most portable check across H2 minor
                            // releases.
                            st.executeQuery(
                                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS"
                            ).use { rs ->
                                rs.next()
                                rs.getInt(1)
                            }
                        }
                    }

                fkCount() shouldBe 0
                repo.installJunctionForeignKeys()
                val firstCount = fkCount()
                // Parent FK + item FK (item descriptor uses CASCADE here).
                firstCount shouldBe 2

                // Second invocation must not throw and must not create additional constraints.
                repo.installJunctionForeignKeys()
                fkCount() shouldBe firstCount
            } finally {
                repo.close()
            }
        } finally {
            ds.close()
        }
    }
})

/**
 * Hand-rolled aggregate parent entity. Backing field is a writable `var List<Int>` so the test
 * fixture's [TestPlaylistTableDef.applyJunctionRows] can re-assign it on bulk load.
 */
internal class TestPlaylist(override val id: Int) : ReactiveEntityBase<Int, TestPlaylist>() {
    var name: String by reactiveProperty("")
    var trackIds: List<Int> by reactiveProperty(emptyList())

    override val uniqueId: String get() = "test-playlist-$id"

    override fun clone(): TestPlaylist =
        TestPlaylist(id).also { copy ->
            copy.withEventsDisabled {
                copy.name = name
                copy.trackIds = trackIds.toList()
            }
        }
}

/**
 * Hand-rolled item entity. Has no junction descriptors, so the empty-junction path in
 * `loadFromStore` and `writePending` exercises through this fixture.
 */
internal class TestTrack(override val id: Int) : ReactiveEntityBase<Int, TestTrack>() {
    var title: String by reactiveProperty("")
    override val uniqueId: String get() = "test-track-$id"

    override fun clone(): TestTrack =
        TestTrack(id).also { copy -> copy.withEventsDisabled { copy.title = title } }
}

internal object TestTrackTableDef : SqlTableDef<TestTrack> {
    override val tableName: String = "test_tracks"
    override val columns: List<ColumnDef> =
        listOf(
            ColumnDef("id", ColumnType.IntType, nullable = false, primaryKey = true),
            ColumnDef("title", ColumnType.VarcharType(200), nullable = false, primaryKey = false)
        )

    @Suppress("UNCHECKED_CAST")
    override fun fromRow(row: ResultRow, table: Table): TestTrack {
        val cols = table.columns.associateBy { it.name }
        val entity = TestTrack(row[cols["id"]!! as Column<Int>])
        entity.title = row[cols["title"]!! as Column<String>]
        return entity
    }

    override fun toParams(entity: TestTrack, table: Table): Map<Column<*>, Any?> {
        val cols = table.columns.associateBy { it.name }
        return mapOf(cols["id"]!! to entity.id, cols["title"]!! to entity.title)
    }

    @Suppress("UNCHECKED_CAST")
    override fun applyRow(entity: TestTrack, row: ResultRow, table: Table) {
        val cols = table.columns.associateBy { it.name }
        entity.title = row[cols["title"]!! as Column<String>]
    }

    override fun applyScalarRow(entity: TestTrack, row: ResultRow, table: Table, rawInit: LirpRawInitializer<TestTrack>) {
        // No-op: entity state is fully populated by fromRow.
    }
}

/** Junction descriptor for [TestPlaylist.trackIds]. */
internal object TestPlaylistTracksJunctionDef : JunctionTableDef {
    override val tableName: String = "test_playlist_tracks"
    override val parentTableName: String = "test_playlists"
    override val itemTableName: String = "test_tracks"
    override val columns: List<JunctionColumnDef> =
        listOf(
            JunctionColumnDef("parent_id", ColumnType.IntType, primaryKey = true),
            JunctionColumnDef("item_id", ColumnType.IntType, primaryKey = true),
            JunctionColumnDef("position", ColumnType.IntType)
        )
    override val isOrdered: Boolean = true
    override val parentFkOnDelete: CascadeAction = CascadeAction.CASCADE
    override val itemFkOnDelete: CascadeAction = CascadeAction.CASCADE
}

internal object TestPlaylistTableDef : SqlTableDef<TestPlaylist>, JunctionAware<TestPlaylist> {
    override val tableName: String = "test_playlists"
    override val columns: List<ColumnDef> =
        listOf(
            ColumnDef("id", ColumnType.IntType, nullable = false, primaryKey = true),
            ColumnDef("name", ColumnType.VarcharType(200), nullable = false, primaryKey = false)
        )
    override val junctionTableDefs: List<JunctionTableDef> = listOf(TestPlaylistTracksJunctionDef)
    override val junctionAccessors: List<JunctionAccessor<TestPlaylist>> =
        listOf(
            object : JunctionAccessor<TestPlaylist> {
                override val descriptor: JunctionTableDef = TestPlaylistTracksJunctionDef

                override fun idsOf(entity: TestPlaylist): Collection<Any> = entity.trackIds.map { it as Any }
            }
        )

    @Suppress("UNCHECKED_CAST")
    override fun fromRow(row: ResultRow, table: Table): TestPlaylist {
        val cols = table.columns.associateBy { it.name }
        val entity = TestPlaylist(row[cols["id"]!! as Column<Int>])
        entity.name = row[cols["name"]!! as Column<String>]
        return entity
    }

    override fun toParams(entity: TestPlaylist, table: Table): Map<Column<*>, Any?> {
        val cols = table.columns.associateBy { it.name }
        return mapOf(cols["id"]!! to entity.id, cols["name"]!! to entity.name)
    }

    @Suppress("UNCHECKED_CAST")
    override fun applyRow(entity: TestPlaylist, row: ResultRow, table: Table) {
        val cols = table.columns.associateBy { it.name }
        entity.name = row[cols["name"]!! as Column<String>]
    }

    override fun applyJunctionRows(entity: TestPlaylist, descriptor: JunctionTableDef, ids: List<Any>) {
        if (descriptor === TestPlaylistTracksJunctionDef) {
            entity.withEventsDisabled {
                entity.trackIds = ids.filterIsInstance<Int>()
            }
        }
    }

    override fun applyScalarRow(entity: TestPlaylist, row: ResultRow, table: Table, rawInit: LirpRawInitializer<TestPlaylist>) {
        // No-op: entity state is fully populated by fromRow.
    }
}

/**
 * Decorator [DataSource] that increments [selectCount] every time a `SELECT` statement is prepared
 * on a borrowed connection. Used to assert the N+1 query plan in [SqlRepository.loadFromStore].
 */
private class QueryCountingDataSource(private val delegate: DataSource) : DataSource by delegate {
    val selectCount = AtomicInteger(0)

    override fun getConnection(): Connection = wrap(delegate.connection)

    override fun getConnection(username: String?, password: String?): Connection =
        wrap(delegate.getConnection(username, password))

    private fun wrap(connection: Connection): Connection =
        java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Connection::class.java)
        ) { _, method, args ->
            try {
                when (method.name) {
                    "prepareStatement" -> {
                        val sql = args?.firstOrNull() as? String
                        if (sql != null && sql.trim().uppercase().startsWith("SELECT")) {
                            selectCount.incrementAndGet()
                        }
                        method.invoke(connection, *(args ?: emptyArray()))
                    }
                    "createStatement" -> {
                        val raw = method.invoke(connection, *(args ?: emptyArray())) as Statement
                        wrapStatement(raw)
                    }
                    else -> method.invoke(connection, *(args ?: emptyArray()))
                }
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        } as Connection

    private fun wrapStatement(raw: Statement): Statement =
        java.lang.reflect.Proxy.newProxyInstance(
            javaClass.classLoader,
            arrayOf(Statement::class.java)
        ) { _, method, args ->
            if (method.name == "executeQuery" && args?.firstOrNull() is String) {
                val sql = args[0] as String
                if (sql.trim().uppercase().startsWith("SELECT")) {
                    selectCount.incrementAndGet()
                }
            }
            try {
                method.invoke(raw, *(args ?: emptyArray()))
            } catch (e: java.lang.reflect.InvocationTargetException) {
                throw e.targetException
            }
        } as Statement
}