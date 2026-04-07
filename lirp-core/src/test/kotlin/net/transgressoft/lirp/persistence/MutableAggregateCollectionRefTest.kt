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
import io.kotest.assertions.throwables.shouldThrow
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
 * Tests for [MutableAggregateListProxy] and [MutableAggregateSetProxy], validating
 * all proxy behaviors: lazy resolution per-access (D-01/D-02), indexed mutations (D-03),
 * replace at index (D-03), indexed removal (D-03), live sub-list and list-iterator views (D-07),
 * iterator().remove on set proxy (D-02), NoSuchElementException on unresolvable ID (D-05),
 * resolve-per-call freshness (D-04), referenceIds accessible via AggregateCollectionRef cast (D-08),
 * thread safety (CORE-04), and mutation event emission (EVT-01).
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

    "MutableAggregateListProxy get(index) resolves entity from registry" {
        val t1 = trackRepo.create(1, "Track 1")
        val t2 = trackRepo.create(2, "Track 2")
        val t3 = trackRepo.create(3, "Track 3")
        val playlist = playlistRepo.create(1L, "Test", listOf(1, 2, 3))

        playlist.items[0] shouldBe t1
        playlist.items[2] shouldBe t3
    }

    "MutableAggregateListProxy get(index) throws IndexOutOfBoundsException for invalid index" {
        val playlist = playlistRepo.create(1L, "Test", listOf(1, 2))

        shouldThrow<IndexOutOfBoundsException> {
            playlist.items[5]
        }
    }

    "MutableAggregateListProxy get(index) throws NoSuchElementException for unresolvable ID" {
        // ID 999 is not in trackRepo
        val playlist = playlistRepo.create(1L, "Test", listOf(999))

        shouldThrow<NoSuchElementException> {
            playlist.items[0]
        }
    }

    "MutableAggregateListProxy set(index, element) replaces entity and emits MutationEvent" {
        val t1 = trackRepo.create(1, "Track 1")
        val t2 = trackRepo.create(2, "Track 2")
        val playlist = playlistRepo.create(1L, "Test", listOf(1))
        val events = Collections.synchronizedList(mutableListOf<MutationEvent<Long, MutablePlaylist>>())
        playlist.subscribe { events.add(it) }

        playlist.items[0] = t2

        testDispatcher.scheduler.advanceUntilIdle()

        playlist.items[0] shouldBe t2
        playlist.items.referenceIds shouldContainExactly listOf(2)
        events shouldHaveSize 1
    }

    "MutableAggregateListProxy add(index, element) inserts at position and emits MutationEvent" {
        val t1 = trackRepo.create(1, "Track 1")
        val t2 = trackRepo.create(2, "Track 2")
        val t3 = trackRepo.create(3, "Track 3")
        val playlist = playlistRepo.create(1L, "Test", listOf(1, 3))
        val events = Collections.synchronizedList(mutableListOf<MutationEvent<Long, MutablePlaylist>>())
        playlist.subscribe { events.add(it) }

        playlist.items.add(1, t2)

        testDispatcher.scheduler.advanceUntilIdle()

        playlist.items shouldContainExactly listOf(t1, t2, t3)
        events shouldHaveSize 1
    }

    "MutableAggregateListProxy removeAt(index) removes by position and emits MutationEvent" {
        val t1 = trackRepo.create(1, "Track 1")
        val t2 = trackRepo.create(2, "Track 2")
        val t3 = trackRepo.create(3, "Track 3")
        val playlist = playlistRepo.create(1L, "Test", listOf(1, 2, 3))
        val events = Collections.synchronizedList(mutableListOf<MutationEvent<Long, MutablePlaylist>>())
        playlist.subscribe { events.add(it) }

        val removed = playlist.items.removeAt(1)

        testDispatcher.scheduler.advanceUntilIdle()

        removed shouldBe t2
        playlist.items shouldContainExactly listOf(t1, t3)
        events shouldHaveSize 1
    }

    "MutableAggregateListProxy subList() returns live view that emits events on mutation" {
        val t1 = trackRepo.create(1, "Track 1")
        val t2 = trackRepo.create(2, "Track 2")
        val t3 = trackRepo.create(3, "Track 3")
        val tNew = trackRepo.create(4, "Track 4")
        val playlist = playlistRepo.create(1L, "Test", listOf(1, 2, 3))
        val events = Collections.synchronizedList(mutableListOf<MutationEvent<Long, MutablePlaylist>>())
        playlist.subscribe { events.add(it) }

        val sub = playlist.items.subList(1, 3)
        sub.add(tNew)

        testDispatcher.scheduler.advanceUntilIdle()

        playlist.items shouldContain tNew
        events shouldHaveSize 1
    }

    "MutableAggregateListProxy listIterator() set emits MutationEvent" {
        val t1 = trackRepo.create(1, "Track 1")
        val t2 = trackRepo.create(2, "Track 2")
        val t3 = trackRepo.create(3, "Track 3")
        val playlist = playlistRepo.create(1L, "Test", listOf(1, 2))
        val events = Collections.synchronizedList(mutableListOf<MutationEvent<Long, MutablePlaylist>>())
        playlist.subscribe { events.add(it) }

        val iter = playlist.items.listIterator()
        iter.next()
        iter.set(t3)

        testDispatcher.scheduler.advanceUntilIdle()

        playlist.items[0] shouldBe t3
        events shouldHaveSize 1
    }

    "MutableAggregateSetProxy iterator().remove() removes entity and emits MutationEvent" {
        val p1 = playlistRepo.create(1L, "P1")
        val p2 = playlistRepo.create(2L, "P2")
        val group = groupRepo.create(1L, setOf(1L, 2L))
        val events = Collections.synchronizedList(mutableListOf<MutationEvent<Long, MutablePlaylistGroup>>())
        group.subscribe { events.add(it) }

        val iter = group.playlists.iterator()
        iter.next()
        iter.remove()

        testDispatcher.scheduler.advanceUntilIdle()

        group.playlists shouldHaveSize 1
        events shouldHaveSize 1
    }

    "MutableAggregateListProxy resolve-per-call returns updated entity after registry change" {
        val track = trackRepo.create(1, "Original Title")
        val playlist = playlistRepo.create(1L, "Test", listOf(1))

        playlist.items[0].title shouldBe "Original Title"

        // Remove and re-add with same ID but different title to simulate an update
        trackRepo.remove(track)
        val updatedTrack = trackRepo.create(1, "Updated Title")

        playlist.items[0].title shouldBe "Updated Title"
    }

    "referenceIds accessible via AggregateCollectionRef cast on mutable list proxy" {
        val t1 = trackRepo.create(1, "T1")
        val t2 = trackRepo.create(2, "T2")
        val playlist = playlistRepo.create(1L, "Test", listOf(1, 2))

        val ids = (playlist.items as AggregateCollectionRef<*, *>).referenceIds

        ids shouldContainExactly listOf(1, 2)
    }

    "AggregateListProxy provides List<E> with indexed access" {
        val t1 = trackRepo.create(1, "T1")
        val t2 = trackRepo.create(2, "T2")
        val playlistRepo2 = PlaylistVolatileRepo(ctx)
        val playlist = playlistRepo2.create(1L, "Test", listOf(1, 2))

        playlist.items[0] shouldBe t1
        playlist.items[1] shouldBe t2
        playlist.items shouldHaveSize 2
    }

    "AggregateSetProxy provides Set<E> with iteration" {
        val immutablePlaylistRepo = PlaylistVolatileRepo(ctx)
        val p1 = immutablePlaylistRepo.create(1L, "P1", emptyList())
        val p2 = immutablePlaylistRepo.create(2L, "P2", emptyList())
        val groupRepo2 = PlaylistGroupVolatileRepo(ctx)
        val group = groupRepo2.create(1L, setOf(1L, 2L))

        group.playlists shouldHaveSize 2
        group.playlists.toSet() shouldContainExactly setOf(p1, p2)
    }
})