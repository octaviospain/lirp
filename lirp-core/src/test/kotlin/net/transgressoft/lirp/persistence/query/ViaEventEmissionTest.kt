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

package net.transgressoft.lirp.persistence.query

import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.testing.ReactiveScopeExtension
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Verifies the silent-by-default event contract for cross-aggregate queries (D-18):
 * the child registry never emits READ events when its entities are visited via the
 * planner's hash-join / per-parent loop paths, regardless of its own `enableEvents`
 * setting. The parent registry continues to honour Phase 52's silent-by-default
 * contract.
 */
@DisplayName("Via event emission")
@OptIn(ExperimentalCoroutinesApi::class)
internal class ViaEventEmissionTest : FunSpec({

    val testDispatcher = UnconfinedTestDispatcher()
    extension(ReactiveScopeExtension(testDispatcher))

    test("child registry emits no READ events when parent registry runs a via query without enableEvents") {
        val tracks =
            TrackRepo().apply {
                add(Track(1, "a", 10.0))
                add(Track(2, "b", 200.0))
            }
        val playlists =
            PlaylistRepo().apply {
                add(Playlist(1, "p", listOf(1, 2), null))
            }
        val childReadCount = AtomicInteger(0)
        tracks.subscribe(CrudEvent.Type.READ) { childReadCount.incrementAndGet() }

        playlists.query { where { Playlist::trackIds via tracks anyMatch { Track::price gt 100.0 } } }.toList()

        testDispatcher.scheduler.advanceUntilIdle()
        childReadCount.get() shouldBe 0
    }

    test("child registry emits no READ events even when parent registry has enableEvents set") {
        val tracks =
            TrackRepo().apply {
                add(Track(1, "a", 10.0))
                add(Track(2, "b", 200.0))
            }
        val playlists =
            PlaylistRepo().apply {
                add(Playlist(1, "p", listOf(1, 2), null))
            }
        val childReadCount = AtomicInteger(0)
        tracks.subscribe(CrudEvent.Type.READ) { childReadCount.incrementAndGet() }

        playlists.activateEvents(CrudEvent.Type.READ)
        playlists.query { where { Playlist::trackIds via tracks anyMatch { Track::price gt 100.0 } } }.toList()

        testDispatcher.scheduler.advanceUntilIdle()
        childReadCount.get() shouldBe 0
    }

    test("parent registry honors silent-by-default contract from Phase 52") {
        val tracks =
            TrackRepo().apply {
                add(Track(1, "a", 10.0))
                add(Track(2, "b", 200.0))
            }
        val playlists =
            PlaylistRepo().apply {
                add(Playlist(1, "p", listOf(1, 2), null))
                add(Playlist(2, "q", listOf(1), null))
            }
        val parentReadCount = AtomicInteger(0)
        playlists.subscribe(CrudEvent.Type.READ) { parentReadCount.incrementAndGet() }

        // No activateEvents on parent -> silent
        playlists.query { where { Playlist::trackIds via tracks anyMatch { Track::price gt 100.0 } } }.toList()
        testDispatcher.scheduler.advanceUntilIdle()
        parentReadCount.get() shouldBe 0

        // Now opt in: READ events should fire on the parent
        playlists.activateEvents(CrudEvent.Type.READ)
        playlists.query { where { Playlist::trackIds via tracks anyMatch { Track::price gt 100.0 } } }.toList()
        testDispatcher.scheduler.advanceUntilIdle()
        parentReadCount.get() shouldBeGreaterThan 0
    }
})