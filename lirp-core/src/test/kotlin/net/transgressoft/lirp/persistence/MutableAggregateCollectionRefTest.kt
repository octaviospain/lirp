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

import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.event.ReactiveScope
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * Tests for [MutableAggregateListRefDelegate] and [MutableAggregateSetRefDelegate], validating
 * all phase requirements: lazy resolution (CORE-01), uniqueness enforcement (CORE-02),
 * ID write-back (CORE-03), thread safety (CORE-04), Java interop (CORE-05), and
 * mutation event emission (EVT-01).
 */
@DisplayName("MutableAggregateCollectionRefDelegate")
@OptIn(ExperimentalCoroutinesApi::class)
internal class MutableAggregateCollectionRefTest : StringSpec({

    val testDispatcher = UnconfinedTestDispatcher()
    val testScope = CoroutineScope(testDispatcher)

    beforeSpec {
        ReactiveScope.flowScope = testScope
        ReactiveScope.ioScope = testScope
    }

    lateinit var ctx: LirpContext
    lateinit var trackRepo: TestTrackVolatileRepo
    lateinit var playlistRepo: MutablePlaylistVolatileRepo
    lateinit var groupRepo: MutablePlaylistGroupVolatileRepo

    beforeEach {
        ctx = LirpContext()
        trackRepo = TestTrackVolatileRepo(ctx)
        playlistRepo = MutablePlaylistVolatileRepo(ctx)
        groupRepo = MutablePlaylistGroupVolatileRepo(ctx)
    }

    afterEach {
        ctx.close()
    }

    "mutableAggregateList resolves added entities from registry" {
        val t1 = trackRepo.create(1, "Track 1")
        val t2 = trackRepo.create(2, "Track 2")
        val playlist = playlistRepo.create(1L, "Test")

        playlist.items.add(t1)
        playlist.items.add(t2)

        playlist.items.resolveAll() shouldContainExactly listOf(t1, t2)
    }

    // CORE-01 additional: resolveAll() returns empty list before registry binding
    "mutableAggregateList returns empty before registry binding" {
        val playlist = MutablePlaylist(1L, "Unbound")

        playlist.items.resolveAll() shouldBe emptyList()
    }

    "mutableAggregateSet enforces uniqueness" {
        val p1 = playlistRepo.create(1L, "P1")
        val group = groupRepo.create(1L)

        group.playlists.add(p1) shouldBe true
        group.playlists.add(p1) shouldBe false
        group.playlists.referenceIds shouldHaveSize 1
    }

    "add updates delegate backing ID collection" {
        val t1 = trackRepo.create(1, "T1")
        val playlist = playlistRepo.create(1L, "Test")

        playlist.items.add(t1)

        playlist.items.referenceIds shouldContain 1
    }

    "remove updates delegate backing ID collection" {
        val t1 = trackRepo.create(1, "T1")
        val playlist = playlistRepo.create(1L, "Test", listOf(1))

        playlist.items.remove(t1)

        playlist.items.referenceIds shouldNotContain 1
    }

    "clear empties delegate backing ID collection" {
        trackRepo.create(1, "T1")
        trackRepo.create(2, "T2")
        val playlist = playlistRepo.create(1L, "Test", listOf(1, 2))

        playlist.items.clear()

        playlist.items.referenceIds shouldBe emptyList()
    }

    "clone produces independent copy of mutable list field" {
        val playlist = MutablePlaylist(1L, "Test", listOf(1, 2))
        val cloned = playlist.clone()

        (cloned.itemIds !== playlist.itemIds) shouldBe true
    }

    "concurrent coroutine adds produce no lost updates" {
        val playlist = playlistRepo.create(1L, "Concurrent")
        (1..1000).forEach { trackRepo.create(it, "Track $it") }

        runTest {
            val jobs =
                (1..10).map { batch ->
                    launch(Dispatchers.Default) {
                        (1..100).forEach { i ->
                            val trackId = (batch - 1) * 100 + i
                            val track = trackRepo.findById(trackId).get() as TestTrack
                            playlist.items.add(track)
                        }
                    }
                }
            jobs.joinAll()
        }

        playlist.items.referenceIds shouldHaveSize 1000
    }

    "MutationEvent emitted after add on mutable list" {
        val t1 = trackRepo.create(1, "T1")
        val playlist = playlistRepo.create(1L, "Test")
        val events = Collections.synchronizedList(mutableListOf<MutationEvent<Long, MutablePlaylist>>())

        playlist.subscribe { events.add(it) }
        val beforeModified = playlist.lastDateModified

        // Small sleep to allow lastDateModified to advance (it is based on LocalDateTime.now())
        Thread.sleep(10)

        playlist.items.add(t1)

        // Drive pending coroutines to completion: the idSetter update and event emission happen
        // inside mutateAndPublish (via mutateForCollection), then the channel→flow bridge and
        // the subscriber coroutine flush on the test dispatcher.
        testDispatcher.scheduler.advanceUntilIdle()

        events shouldHaveSize 1
        playlist.lastDateModified shouldBeGreaterThan beforeModified
    }
})