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

package net.transgressoft.lirp.persistence

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.optional.shouldNotBePresent
import io.kotest.matchers.shouldBe

/**
 * Abstract integration test base covering all four scenario categories for LIRP repositories
 * operating with [AudioItem] / [MutableAudioPlaylist] entities mirroring the music-commons hierarchy:
 *
 * 1. CRUD round-trip — create, read, update, delete
 * 2. Aggregate resolution — resolve referenced entities via [LirpContext]
 * 3. Mutable collection operations — runtime add/remove with ID write-back
 * 4. Cascade behavior — all four cascade modes (CASCADE, DETACH, RESTRICT, NONE)
 *
 * Concrete subclasses supply the repository implementations via [createAudioItemRepo] and
 * [createPlaylistRepo], allowing the same test suite to run against Volatile, JSON, and SQL backends.
 */
abstract class MusicCommonsIntegrationTestBase : FunSpec() {

    /** Creates a fresh [Repository] for [AudioItem] entities bound to [ctx]. */
    abstract fun createAudioItemRepo(ctx: LirpContext): Repository<Int, AudioItem>

    /** Creates a fresh [Repository] for [MutableAudioPlaylist] entities bound to [ctx]. */
    abstract fun createPlaylistRepo(ctx: LirpContext): Repository<Int, MutableAudioPlaylist>

    /** Cleans up after each test. Override if additional teardown is needed. */
    open fun cleanup(ctx: LirpContext) {
        ctx.close()
    }

    /**
     * Flushes any pending writes (e.g. debounced JSON writes).
     * No-op in the base; JSON subclass overrides to advance the test dispatcher.
     */
    open fun flushPendingWrites() { /* no-op */ }

    lateinit var ctx: LirpContext
    lateinit var audioItemRepo: Repository<Int, AudioItem>
    lateinit var playlistRepo: Repository<Int, MutableAudioPlaylist>

    init {
        beforeEach {
            ctx = LirpContext()
            audioItemRepo = createAudioItemRepo(ctx)
            playlistRepo = createPlaylistRepo(ctx)
        }

        afterEach {
            cleanup(ctx)
        }

        // -------------------------------------------------------------------------
        // Category 1: CRUD round-trip
        // -------------------------------------------------------------------------

        test("CRUD round-trip creates AudioItem, reads back, verifies fields") {
            val item = MutableAudioItem(1, "Song A").also(audioItemRepo::add)
            audioItemRepo.findById(1) shouldBePresent { it.title shouldBe "Song A" }
        }

        test("CRUD round-trip updates reactive property on AudioItem") {
            val item = MutableAudioItem(1, "Song A").also(audioItemRepo::add)

            item.title = "Song B"

            audioItemRepo.findById(1) shouldBePresent { it.title shouldBe "Song B" }
        }

        test("CRUD round-trip deletes AudioItem and verifies removal") {
            val item = MutableAudioItem(1, "Song A").also(audioItemRepo::add)

            audioItemRepo.remove(item)

            audioItemRepo.findById(1).shouldNotBePresent()
        }

        test("CRUD round-trip creates AudioPlaylist with name, reads back") {
            val playlist = DefaultAudioPlaylist(10, "My Playlist").also(playlistRepo::add)

            playlistRepo.findById(10) shouldBePresent { it.name shouldBe "My Playlist" }
        }

        // -------------------------------------------------------------------------
        // Category 2: Aggregate resolution
        // -------------------------------------------------------------------------

        test("aggregate resolution resolves AudioItems from AudioPlaylist via LirpContext") {
            val item1 = MutableAudioItem(1, "Track 1").also(audioItemRepo::add)
            val item2 = MutableAudioItem(2, "Track 2").also(audioItemRepo::add)
            val item3 = MutableAudioItem(3, "Track 3").also(audioItemRepo::add)

            val playlist =
                DefaultAudioPlaylist(10, "Full Mix", listOf(1, 2, 3))
                    .also(playlistRepo::add)

            val resolved = playlist.audioItems.resolveAll()
            resolved shouldHaveSize 3
            resolved.map { it.id } shouldContainExactlyInAnyOrder listOf(1, 2, 3)
        }

        test("aggregate resolution resolves nested playlists (self-referencing)") {
            val subA = DefaultAudioPlaylist(20, "Sub A").also(playlistRepo::add)
            val subB = DefaultAudioPlaylist(30, "Sub B").also(playlistRepo::add)
            val parent =
                DefaultAudioPlaylist(10, "Parent", emptyList(), setOf(20, 30))
                    .also(playlistRepo::add)

            val resolved = parent.playlists.resolveAll()
            resolved shouldHaveSize 2
            resolved.map { it.id } shouldContainExactlyInAnyOrder listOf(20, 30)
        }

        test("aggregate resolution returns empty for playlist with no audioItems") {
            val playlist = DefaultAudioPlaylist(10, "Empty").also(playlistRepo::add)

            playlist.audioItems.resolveAll() shouldHaveSize 0
        }

        // -------------------------------------------------------------------------
        // Category 3: Mutable collection operations
        // -------------------------------------------------------------------------

        test("mutable collection adds AudioItem to playlist at runtime and backing IDs update") {
            val item = MutableAudioItem(1, "Song A").also(audioItemRepo::add)
            val playlist = DefaultAudioPlaylist(10, "Runtime Add").also(playlistRepo::add)

            playlist.audioItems.add(item)

            playlist.audioItems.referenceIds shouldContainExactlyInAnyOrder listOf(1)
            playlist.audioItems.resolveAll().map { it.id } shouldContainExactlyInAnyOrder listOf(1)
        }

        test("mutable collection removes AudioItem from playlist and backing IDs update") {
            val item1 = MutableAudioItem(1, "Song A").also(audioItemRepo::add)
            val item2 = MutableAudioItem(2, "Song B").also(audioItemRepo::add)
            val playlist =
                DefaultAudioPlaylist(10, "Two Items", listOf(1, 2))
                    .also(playlistRepo::add)

            playlist.audioItems.remove(item1)

            playlist.audioItems.referenceIds shouldContainExactlyInAnyOrder listOf(2)
        }

        test("mutable collection adds nested playlist at runtime (self-referencing)") {
            val child = DefaultAudioPlaylist(20, "Child").also(playlistRepo::add)
            val parent = DefaultAudioPlaylist(10, "Parent").also(playlistRepo::add)

            parent.playlists.add(child)

            parent.playlists.referenceIds shouldContainExactlyInAnyOrder listOf(20)
            parent.playlists.resolveAll().map { it.id } shouldContainExactlyInAnyOrder listOf(20)
        }

        // -------------------------------------------------------------------------
        // Category 4: Cascade behavior
        // -------------------------------------------------------------------------

        test("cascade DETACH leaves AudioItems in repo when playlist is removed") {
            val item1 = MutableAudioItem(1, "Song A").also(audioItemRepo::add)
            val item2 = MutableAudioItem(2, "Song B").also(audioItemRepo::add)
            val playlist =
                DefaultAudioPlaylist(10, "Detach Playlist", listOf(1, 2))
                    .also(playlistRepo::add)

            playlistRepo.remove(playlist)

            audioItemRepo.findById(1).shouldBePresent()
            audioItemRepo.findById(2).shouldBePresent()
        }

        test("cascade DETACH on self-referencing playlist leaves sub-playlists in repo") {
            val sub1 = DefaultAudioPlaylist(20, "Sub 1").also(playlistRepo::add)
            val sub2 = DefaultAudioPlaylist(30, "Sub 2").also(playlistRepo::add)
            val parent =
                DefaultAudioPlaylist(10, "Parent", emptyList(), setOf(20, 30))
                    .also(playlistRepo::add)

            playlistRepo.remove(parent)

            playlistRepo.findById(20).shouldBePresent()
            playlistRepo.findById(30).shouldBePresent()
        }

        test("cascade CASCADE removes referenced AudioItems when playlist is removed") {
            val item1 = MutableAudioItem(1, "Song A").also(audioItemRepo::add)
            val item2 = MutableAudioItem(2, "Song B").also(audioItemRepo::add)

            val cascadeRepo = CascadePlaylistRepo(ctx)
            val playlist =
                CascadeAudioPlaylist(10, listOf(1, 2)).also {
                    it.name = "Cascade"
                    cascadeRepo.add(it)
                }

            cascadeRepo.remove(playlist)

            audioItemRepo.findById(1).shouldNotBePresent()
            audioItemRepo.findById(2).shouldNotBePresent()
        }

        test("cascade RESTRICT throws when removing playlist with referenced AudioItems") {
            val item1 = MutableAudioItem(1, "Song A").also(audioItemRepo::add)

            val restrictRepo = RestrictPlaylistRepo(ctx)
            val playlist1 = RestrictAudioPlaylist(10, "Restrict 1", listOf(1)).also(restrictRepo::add)
            // Second playlist referencing the same item makes removal restricted
            restrictRepo.add(RestrictAudioPlaylist(11, "Restrict 2", listOf(1)))

            shouldThrow<IllegalStateException> {
                restrictRepo.remove(playlist1)
            }
        }

        test("cascade NONE leaves AudioItems and does not modify referenceIds") {
            val item1 = MutableAudioItem(1, "Song A").also(audioItemRepo::add)
            val item2 = MutableAudioItem(2, "Song B").also(audioItemRepo::add)

            val noneRepo = NonePlaylistRepo(ctx)
            val playlist = NoneAudioPlaylist(10, "None", listOf(1, 2)).also(noneRepo::add)

            noneRepo.remove(playlist)

            audioItemRepo.findById(1).shouldBePresent()
            audioItemRepo.findById(2).shouldBePresent()
        }
    }
}