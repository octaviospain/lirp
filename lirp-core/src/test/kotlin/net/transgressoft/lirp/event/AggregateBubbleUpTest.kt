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

package net.transgressoft.lirp.event

import net.transgressoft.lirp.persistence.AudioItem
import net.transgressoft.lirp.persistence.AudioItemVolatileRepository
import net.transgressoft.lirp.persistence.AudioPlaylistVolatileRepository
import net.transgressoft.lirp.persistence.BubbleAudioLibraryRepo
import net.transgressoft.lirp.persistence.BubbleAudioPlaylistRepo
import net.transgressoft.lirp.persistence.BubbleAudioTrackRepo
import net.transgressoft.lirp.persistence.BubbleUpAudioPlaylistRepo
import net.transgressoft.lirp.persistence.DefaultAudioPlaylist
import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.persistence.MutableAudioItem
import net.transgressoft.lirp.persistence.MutableRefPlaylistRepo
import net.transgressoft.lirp.testing.reactiveScope
import net.transgressoft.lirp.testing.recordAsync
import io.kotest.assertions.nondeterministic.continually
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for bubble-up event propagation from referenced child entities to parent entity subscribers.
 *
 * Verifies that [AggregateMutationEvent] is delivered to parent subscribers when bubble-up is enabled,
 * silenced when disabled, and that propagation is single-level only (no transitive forwarding).
 */
@DisplayName("AggregateBubbleUpTest")
@Suppress("UNCHECKED_CAST")
internal class AggregateBubbleUpTest : FunSpec({

    reactiveScope()

    lateinit var ctx: LirpContext

    beforeEach {
        ctx = LirpContext()
    }

    afterEach {
        ctx.close()
    }

    test("BubbleUpAudioPlaylist receives AggregateMutationEvent when referenced audio item mutates") {
        val audioItemRepo = AudioItemVolatileRepository(ctx)
        val audioItem = audioItemRepo.create(id = 1, title = "Track A") as MutableAudioItem

        val playlistRepo = BubbleUpAudioPlaylistRepo(ctx)
        val playlist = playlistRepo.create(id = 100, audioItemId = 1)

        val recorder = playlist.recordAsync()

        audioItem.title = "Track A Updated"

        recorder.awaitCount(1) shouldBe true
        recorder.last.shouldBeInstanceOf<AggregateMutationEvent<*, *>>()
    }

    test("AggregateMutationEvent refName matches the declared reference property name") {
        val audioItemRepo = AudioItemVolatileRepository(ctx)
        val audioItem = audioItemRepo.create(id = 1, title = "Track A") as MutableAudioItem

        val playlistRepo = BubbleUpAudioPlaylistRepo(ctx)
        val playlist = playlistRepo.create(id = 100, audioItemId = 1)

        val recorder = playlist.recordAsync()

        audioItem.title = "Track A Updated"

        recorder.awaitCount(1) shouldBe true
        val event = recorder.last
        event.shouldBeInstanceOf<AggregateMutationEvent<*, *>>()
        event.refName shouldBe "audioItem"
    }

    test("AggregateMutationEvent childEvent contains the original MutationEvent from the referenced audio item") {
        val audioItemRepo = AudioItemVolatileRepository(ctx)
        val audioItem = audioItemRepo.create(id = 1, title = "Track A") as MutableAudioItem

        val playlistRepo = BubbleUpAudioPlaylistRepo(ctx)
        val playlist = playlistRepo.create(id = 100, audioItemId = 1)

        val recorder = playlist.recordAsync()

        audioItem.title = "Track A Updated"

        recorder.awaitCount(1) shouldBe true
        val aggregateEvent = recorder.last
        aggregateEvent.shouldBeInstanceOf<AggregateMutationEvent<*, *>>()
        aggregateEvent.childEvent.shouldBeInstanceOf<PropertyChanged<Int, AudioItem, String>>()
        val childMutation = aggregateEvent.childEvent as PropertyChanged<Int, AudioItem, String>
        childMutation.newValue shouldBe "Track A Updated"
        childMutation.oldValue shouldBe "Track A"
    }

    test("Audio playlist with bubbleUp=false does NOT receive events when referenced audio item mutates") {
        val audioItemRepo = AudioItemVolatileRepository(ctx)
        val audioItem = audioItemRepo.create(id = 1, title = "Track A") as MutableAudioItem

        val playlistRepo = AudioPlaylistVolatileRepository(ctx)
        val playlist = DefaultAudioPlaylist(100, "My Playlist", listOf(1))
        playlistRepo.add(playlist)

        val receivedEventCount = java.util.concurrent.atomic.AtomicInteger(0)

        playlist.subscribeAsync { receivedEventCount.incrementAndGet() }

        audioItem.title = "Track A Updated"

        // Wait briefly to confirm no event arrives
        continually(300.milliseconds) { receivedEventCount.get() shouldBe 0 }
    }

    test("Bubble-up re-wires to new audio item after reference ID change via mutateAndPublish") {
        val audioItemRepo = AudioItemVolatileRepository(ctx)
        val audioItem1 = audioItemRepo.create(id = 1, title = "Track A") as MutableAudioItem
        val audioItem2 = audioItemRepo.create(id = 2, title = "Track B") as MutableAudioItem

        val playlistRepo = MutableRefPlaylistRepo(ctx)
        val playlist = playlistRepo.create(id = 100, audioItemId = 1)

        val recorder = playlist.recordAsync()

        // Verify initial wiring: audioItem1 mutation arrives
        audioItem1.title = "Track A Updated"
        recorder.awaitCount(1) shouldBe true
        recorder.count shouldBe 1

        // Change reference to audioItem2, then trigger re-wire via resolve()
        playlist.changeItem(2)
        playlist.audioItem.resolve()

        // Mutate audioItem2 — should arrive (re-wired)
        audioItem2.title = "Track B Updated"
        recorder.awaitCount(2) shouldBe true

        // Mutate audioItem1 — should NOT produce further aggregate events
        val countBeforeOldMutation = recorder.count
        audioItem1.title = "Track A Again"
        // No additional events from old audioItem1 subscription
        continually(300.milliseconds) { recorder.count shouldBe countBeforeOldMutation }
    }

    test("Bubble-up stays on old audio item when new reference ID does not resolve") {
        val audioItemRepo = AudioItemVolatileRepository(ctx)
        val audioItem1 = audioItemRepo.create(id = 1, title = "Track A") as MutableAudioItem

        val playlistRepo = MutableRefPlaylistRepo(ctx)
        val playlist = playlistRepo.create(id = 100, audioItemId = 1)

        val recorder = playlist.recordAsync()

        // Verify initial wiring
        audioItem1.title = "Track A Updated"
        recorder.awaitCount(1) shouldBe true

        // Change to non-existent ID — re-wire should fail, old subscription preserved
        playlist.changeItem(999)
        playlist.audioItem.resolve() // triggers re-wire attempt — should fail

        // Old subscription to audioItem1 still active
        val countBefore = recorder.count
        audioItem1.title = "Track A Again"
        eventually(5.seconds) { recorder.count shouldBe countBefore + 1 }
    }

    test("Bubble-up re-wires after initially unresolvable new ID becomes available") {
        val audioItemRepo = AudioItemVolatileRepository(ctx)
        audioItemRepo.create(id = 1, title = "Track A")

        val playlistRepo = MutableRefPlaylistRepo(ctx)
        val playlist = playlistRepo.create(id = 100, audioItemId = 1)

        // Change to ID 2 — not yet in repo
        playlist.changeItem(2)
        playlist.audioItem.resolve() // re-wire fails, old sub (or none if audioItem1 was initial) stays

        // Add audioItem2
        val audioItem2 = audioItemRepo.create(id = 2, title = "Track B") as MutableAudioItem

        // Trigger re-wire again — now succeeds
        playlist.audioItem.resolve()

        val recorder = playlist.recordAsync()

        // Mutate audioItem2 — should arrive after successful re-wire
        audioItem2.title = "Track B Updated"
        recorder.awaitCount(1) shouldBe true
        recorder.count shouldBe 1
    }

    test("Bubble-up propagation is single-level only: BubbleAudioTrack mutation notifies BubbleAudioPlaylist but NOT BubbleAudioLibrary") {
        val repoA = BubbleAudioTrackRepo(ctx)
        val repoB = BubbleAudioPlaylistRepo(ctx)
        val repoC = BubbleAudioLibraryRepo(ctx)

        val track = repoA.create(id = 1, trackName = "original")
        val audioPlaylist = repoB.create(id = 10, trackId = 1)
        val audioLibrary = repoC.create(id = 100, playlistId = 10)

        val bRecorder = audioPlaylist.recordAsync()
        val cReceivedCount = java.util.concurrent.atomic.AtomicInteger(0)

        // BubbleAudioLibrary should NOT receive any events — bubble-up is single-level
        audioLibrary.subscribeAsync { cReceivedCount.incrementAndGet() }

        track.updateTrackName("mutated")

        // BubbleAudioPlaylist should receive bubble-up from BubbleAudioTrack
        bRecorder.awaitCount(1) shouldBe true
        bRecorder.last.shouldBeInstanceOf<AggregateMutationEvent<*, *>>()
        continually(300.milliseconds) { cReceivedCount.get() shouldBe 0 }
    }

    test("BubbleUpAudioPlaylist added before its audio item exists completes wireBubbleUp without throwing") {
        val audioItemRepo = AudioItemVolatileRepository(ctx)
        val bubbleUpPlaylistRepo = BubbleUpAudioPlaylistRepo(ctx)

        val playlist = bubbleUpPlaylistRepo.create(id = 1, audioItemId = 999)

        playlist.audioItem.resolve().isPresent shouldBe false

        audioItemRepo.create(id = 999, title = "Late Track")
        playlist.audioItem.resolve().isPresent shouldBe true
    }
})