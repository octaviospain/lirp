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

import net.transgressoft.lirp.persistence.AudioItem
import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.persistence.MusicCommonsIntegrationTestBase
import net.transgressoft.lirp.persistence.MutableAudioItem
import net.transgressoft.lirp.persistence.MutableAudioPlaylist
import net.transgressoft.lirp.persistence.MutableAudioPlaylistEntity
import net.transgressoft.lirp.persistence.Registry
import net.transgressoft.lirp.persistence.Repository
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName

/**
 * PostgreSQL integration tests for [MusicCommonsIntegrationTestBase] test scenarios.
 *
 * Validates LIRP's SQL persistence layer against a Testcontainers-managed PostgreSQL instance,
 * covering CRUD round-trips, aggregate resolution, mutable collection operations, and cascade
 * behavior using [AudioItem] and [MutableAudioPlaylist] entities.
 *
 * Uses [AudioItemSqlRepository] and [AudioPlaylistSqlRepository] with hand-written
 * [SqlTableDef] objects that serialize aggregate reference IDs as comma-separated TEXT columns.
 *
 * The [dataSource] is created once per spec (in [beforeSpec]) so it is available when the
 * abstract base's [beforeEach] calls [createAudioItemRepo] and [createPlaylistRepo].
 * Table cleanup for isolation is performed inside [cleanup] after [ctx] is closed,
 * ensuring pending writes are flushed before tables are dropped. The [SqlRepository]
 * constructor then recreates the tables for the next test.
 *
 * Cascade tests require both [audioItemRepo] and [playlistRepo] (plus cascade variant repos)
 * to be registered in the same [LirpContext]. Since [SqlRepository] only registers in
 * [LirpContext.default] (the public constructor path does not accept a custom context), the
 * SQL repos are also registered in the per-test [ctx] via reflection on the internal
 * [LirpContext.register] method so that cascade actions can resolve cross-repo references.
 */
@DisplayName("Music-commons SQL integration (PostgreSQL)")
class MusicCommonsSqlIntegrationTest : MusicCommonsIntegrationTestBase() {

    // Initialized in beforeSpec so it is ready before the abstract base's beforeEach runs
    lateinit var dataSource: HikariDataSource

    init {
        beforeSpec {
            dataSource = PostgresContainerSupport.buildDataSource()
            // Drop any leftover tables from a previous run before the first test
            DatabaseTestSupport.dropTable(dataSource, AudioItemSqlTableDef)
            DatabaseTestSupport.dropTable(dataSource, AudioPlaylistSqlTableDef)
        }
    }

    override fun createAudioItemRepo(ctx: LirpContext): Repository<Int, AudioItem> {
        val repo = AudioItemSqlRepository(dataSource)
        // Also register in per-test ctx so cascade actions can find AudioItem entries
        // via ctx.registryFor() when cascade variant repos (volatile) are created in ctx.
        // LirpContext.register is internal and receives a JVM name suffix; accessed via reflection.
        registerInContext(ctx, AudioItem::class.java, repo)
        return repo
    }

    override fun createPlaylistRepo(ctx: LirpContext): Repository<Int, MutableAudioPlaylist> {
        val repo = AudioPlaylistSqlRepository(dataSource)
        registerInContext(ctx, MutableAudioPlaylist::class.java, repo)
        return repo
    }

    override fun cleanup(ctx: LirpContext) {
        // Close the test's local LirpContext (closes cascade/restrict/none volatile repos)
        ctx.close()
        // Close and unregister SQL repos from LirpContext.default so next test can re-register.
        // The HikariDataSource is not closed because AudioItemSqlRepository was constructed with
        // a user-provided DataSource (ownsDataSource=false).
        LirpContext.default.close()
        // Drop tables after flush so the next test's SqlRepository starts with a clean schema
        DatabaseTestSupport.dropTable(dataSource, AudioItemSqlTableDef)
        DatabaseTestSupport.dropTable(dataSource, AudioPlaylistSqlTableDef)
    }

    init {
        // -------------------------------------------------------------------------
        // SQL persistence round-trip tests
        // -------------------------------------------------------------------------

        test("SQL persistence survives close and reopen for AudioItems") {
            MutableAudioItem(1).also {
                it.title = "Persisted Song"
                audioItemRepo.add(it)
            }
            MutableAudioItem(2).also {
                it.title = "Another Song"
                audioItemRepo.add(it)
            }
            flushPendingWrites()

            // Close everything and reopen from the same database
            ctx.close()
            LirpContext.default.close()

            val ctx2 = LirpContext()
            val reloadedRepo = AudioItemSqlRepository(dataSource)
            registerInContext(ctx2, AudioItem::class.java, reloadedRepo)

            reloadedRepo.findById(1) shouldBePresent { it.title shouldBe "Persisted Song" }
            reloadedRepo.findById(2) shouldBePresent { it.title shouldBe "Another Song" }

            ctx2.close()
            LirpContext.default.close()
        }

        test("SQL persistence survives close and reopen for playlists with referenced items") {
            MutableAudioItem(1).also {
                it.title = "Track 1"
                audioItemRepo.add(it)
            }
            MutableAudioItem(2).also {
                it.title = "Track 2"
                audioItemRepo.add(it)
            }
            MutableAudioPlaylistEntity(10, listOf(1, 2)).also {
                it.name = "My Playlist"
                playlistRepo.add(it)
            }
            flushPendingWrites()

            ctx.close()
            LirpContext.default.close()

            val ctx2 = LirpContext()
            val itemRepo2 = AudioItemSqlRepository(dataSource)
            registerInContext(ctx2, AudioItem::class.java, itemRepo2)
            val playlistRepo2 = AudioPlaylistSqlRepository(dataSource)
            registerInContext(ctx2, MutableAudioPlaylist::class.java, playlistRepo2)

            playlistRepo2.findById(10) shouldBePresent {
                it.name shouldBe "My Playlist"
                it.audioItems shouldHaveSize 2
                it.audioItems.map { item: AudioItem -> item.title } shouldContainExactlyInAnyOrder listOf("Track 1", "Track 2")
            }

            ctx2.close()
            LirpContext.default.close()
        }

        test("SQL persistence preserves self-referencing playlist aggregates after reload") {
            val subA =
                MutableAudioPlaylistEntity(20).also {
                    it.name = "Sub A"
                    playlistRepo.add(it)
                }
            val subB =
                MutableAudioPlaylistEntity(30).also {
                    it.name = "Sub B"
                    playlistRepo.add(it)
                }
            MutableAudioPlaylistEntity(10, emptyList(), setOf(20, 30)).also {
                it.name = "Parent"
                playlistRepo.add(it)
            }
            flushPendingWrites()

            ctx.close()
            LirpContext.default.close()

            val ctx2 = LirpContext()
            val playlistRepo2 = AudioPlaylistSqlRepository(dataSource)
            registerInContext(ctx2, MutableAudioPlaylist::class.java, playlistRepo2)

            playlistRepo2.findById(10) shouldBePresent {
                it.name shouldBe "Parent"
                it.playlists shouldHaveSize 2
                it.playlists.map(MutableAudioPlaylist::id) shouldContainExactlyInAnyOrder listOf(20, 30)
            }

            ctx2.close()
            LirpContext.default.close()
        }

        test("SQL persistence reflects runtime mutations after close and reload") {
            val item =
                MutableAudioItem(1).also {
                    it.title = "Original"
                    audioItemRepo.add(it)
                }
            val playlist =
                MutableAudioPlaylistEntity(10).also {
                    it.name = "Empty"
                    playlistRepo.add(it)
                }

            // Mutate at runtime: add item (auto-persists via mutation event subscription)
            playlist.audioItems.add(item)

            // close() triggers synchronous final flush of pending mutations
            ctx.close()
            LirpContext.default.close()

            val ctx2 = LirpContext()
            val itemRepo2 = AudioItemSqlRepository(dataSource)
            registerInContext(ctx2, AudioItem::class.java, itemRepo2)
            val playlistRepo2 = AudioPlaylistSqlRepository(dataSource)
            registerInContext(ctx2, MutableAudioPlaylist::class.java, playlistRepo2)

            playlistRepo2.findById(10) shouldBePresent {
                it.audioItems shouldHaveSize 1
                it.audioItems.first().title shouldBe "Original"
            }

            ctx2.close()
            LirpContext.default.close()
        }
    }

    /**
     * Registers [registry] under [entityClass] in [ctx] via reflection.
     *
     * [LirpContext.register] is `internal` and receives a `$lirp_core` JVM name suffix due to
     * Kotlin's internal visibility mangling, so it is not directly callable from the lirp-sql module.
     */
    private fun registerInContext(ctx: LirpContext, entityClass: Class<*>, registry: Registry<*, *>) {
        val method =
            LirpContext::class.java.getDeclaredMethod(
                "register\$lirp_core",
                Class::class.java,
                Registry::class.java
            )
        method.isAccessible = true
        method.invoke(ctx, entityClass, registry)
    }
}