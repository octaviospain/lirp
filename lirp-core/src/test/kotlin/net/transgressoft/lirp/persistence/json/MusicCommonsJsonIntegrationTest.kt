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

package net.transgressoft.lirp.persistence.json

import net.transgressoft.lirp.event.ReactiveScope
import net.transgressoft.lirp.persistence.AudioItem
import net.transgressoft.lirp.persistence.AudioItemJsonFileRepository
import net.transgressoft.lirp.persistence.AudioItemVolatileRepository
import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.persistence.MusicCommonsIntegrationTestBase
import net.transgressoft.lirp.persistence.MutableAudioItem
import net.transgressoft.lirp.persistence.MutableAudioPlaylist
import net.transgressoft.lirp.persistence.MutableAudioPlaylistEntity
import net.transgressoft.lirp.persistence.PlaylistHierarchyJsonFileRepository
import net.transgressoft.lirp.persistence.Repository
import io.kotest.core.annotation.DisplayName
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Integration tests for [AudioItem] and [MutableAudioPlaylist] backed by [JsonFileRepository].
 *
 * Inherits all shared test scenarios from [MusicCommonsIntegrationTestBase]; provides JSON-backed
 * repository factories using [LirpEntitySerializer] for runtime delegate-introspection serialization.
 * The [ReactiveScope] dispatchers are overridden with a test dispatcher to make coroutine-driven
 * persistence synchronous in tests.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("Music-commons integration (JSON)")
class MusicCommonsJsonIntegrationTest : MusicCommonsIntegrationTestBase() {

    val testDispatcher = UnconfinedTestDispatcher()
    val testScope = CoroutineScope(testDispatcher)

    lateinit var audioItemFile: File
    lateinit var playlistFile: File

    init {
        beforeSpec {
            ReactiveScope.flowScope = testScope
            ReactiveScope.ioScope = testScope
        }

        afterSpec {
            ReactiveScope.resetDefaultIoScope()
            ReactiveScope.resetDefaultFlowScope()
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun createAudioItemRepo(ctx: LirpContext): Repository<Int, AudioItem> {
        audioItemFile = File.createTempFile("audio-items", ".json").also { it.deleteOnExit() }
        val serializer = MapSerializer(Int.serializer(), lirpSerializer(MutableAudioItem(0, ""))) as KSerializer<Map<Int, AudioItem>>
        return AudioItemJsonFileRepository(ctx, audioItemFile, serializer)
    }

    @Suppress("UNCHECKED_CAST")
    override fun createPlaylistRepo(ctx: LirpContext): Repository<Int, MutableAudioPlaylist> {
        playlistFile = File.createTempFile("playlists", ".json").also { it.deleteOnExit() }
        val serializer = MapSerializer(Int.serializer(), lirpSerializer(MutableAudioPlaylistEntity(0, ""))) as KSerializer<Map<Int, MutableAudioPlaylist>>
        return PlaylistHierarchyJsonFileRepository(ctx, playlistFile, serializer)
    }

    override fun flushPendingWrites() {
        testDispatcher.scheduler.advanceUntilIdle()
    }

    init {
        // -------------------------------------------------------------------------
        // JSON persistence round-trip tests
        // -------------------------------------------------------------------------

        test("JSON file contains expected structure after creating playlists with referenced items") {
            val item1 =
                MutableAudioItem(1, "Track A").also {
                    audioItemRepo.add(it)
                }
            val item2 =
                MutableAudioItem(2, "Track B").also {
                    audioItemRepo.add(it)
                }

            val child = MutableAudioPlaylistEntity(20, "Child Playlist").also(playlistRepo::add)
            val parent = MutableAudioPlaylistEntity(10, "Parent Playlist", listOf(1, 2), setOf(20)).also(playlistRepo::add)

            flushPendingWrites()

            val itemsJson = audioItemFile.readText()
            itemsJson shouldContain "\"title\""
            itemsJson shouldContain "Track A"
            itemsJson shouldContain "Track B"

            val playlistsJson = playlistFile.readText()
            playlistsJson shouldContain "Parent Playlist"
            playlistsJson shouldContain "Child Playlist"
            // Parent playlist serializes audioItems as ID array and playlists as ID set
            playlistsJson shouldContain "\"audioItems\""
            playlistsJson shouldContain "\"playlists\""

            // Verify the referenced IDs appear in the file (pretty-printed multi-line arrays)
            val normalized = playlistsJson.replace("\\s+".toRegex(), "")
            normalized shouldContain "\"audioItems\":[1,2]"
            normalized shouldContain "\"playlists\":[20]"
        }

        test("JSON file reflects mutations after adding items to playlist at runtime") {
            val item1 =
                MutableAudioItem(1, "Song 1").also {
                    audioItemRepo.add(it)
                }
            val item2 =
                MutableAudioItem(2, "Song 2").also {
                    audioItemRepo.add(it)
                }
            val playlist = MutableAudioPlaylistEntity(10, "Dynamic").also(playlistRepo::add)
            flushPendingWrites()

            playlist.audioItems.add(item1)
            playlist.audioItems.add(item2)
            flushPendingWrites()

            val normalized = playlistFile.readText().replace("\\s+".toRegex(), "")
            normalized shouldContain "\"audioItems\":[1,2]"
        }

        @Suppress("UNCHECKED_CAST")
        test("JSON round-trip reloads playlists with referenced items from file") {
            val item1 =
                MutableAudioItem(1, "Persisted Track").also {
                    audioItemRepo.add(it)
                }
            val playlist = MutableAudioPlaylistEntity(10, "Persisted Playlist", listOf(1)).also(playlistRepo::add)
            flushPendingWrites()
            ctx.close()

            // Reload from file in a fresh context
            val ctx2 = LirpContext()
            AudioItemVolatileRepository(ctx2).also { repo ->
                MutableAudioItem(1, "Persisted Track").also {
                    repo.add(it)
                }
            }

            val playlistSerializer =
                MapSerializer(
                    Int.serializer(), lirpSerializer(MutableAudioPlaylistEntity(0, ""))
                ) as KSerializer<Map<Int, MutableAudioPlaylist>>
            val reloadedRepo = PlaylistHierarchyJsonFileRepository(ctx2, playlistFile, playlistSerializer)
            flushPendingWrites()

            reloadedRepo.findById(10) shouldBePresent {
                it.name shouldBe "Persisted Playlist"
                it.audioItems shouldHaveSize 1
                it.audioItems.first().title shouldBe "Persisted Track"
            }

            ctx2.close()
        }

        @Suppress("UNCHECKED_CAST")
        test("JSON round-trip preserves self-referencing playlist aggregates after reload") {
            val subA = MutableAudioPlaylistEntity(20, "Sub A").also(playlistRepo::add)
            val subB = MutableAudioPlaylistEntity(30, "Sub B").also(playlistRepo::add)
            val parent = MutableAudioPlaylistEntity(10, "Parent", emptyList(), setOf(20, 30)).also(playlistRepo::add)
            flushPendingWrites()
            ctx.close()

            val ctx2 = LirpContext()
            val playlistSerializer =
                MapSerializer(
                    Int.serializer(), lirpSerializer(MutableAudioPlaylistEntity(0, ""))
                ) as KSerializer<Map<Int, MutableAudioPlaylist>>
            // JsonFileRepository self-registers in ctx2; playlists self-reference via the same repo
            val reloadedRepo = PlaylistHierarchyJsonFileRepository(ctx2, playlistFile, playlistSerializer)
            flushPendingWrites()

            reloadedRepo.findById(10) shouldBePresent {
                it.name shouldBe "Parent"
                it.playlists shouldHaveSize 2
                it.playlists.map { p -> p.id } shouldContainExactlyInAnyOrder listOf(20, 30)
            }

            ctx2.close()
        }
    }
}