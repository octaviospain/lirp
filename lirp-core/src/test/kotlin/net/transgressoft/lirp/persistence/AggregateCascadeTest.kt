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

import net.transgressoft.lirp.event.AggregateMutationEvent
import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.assertions.nondeterministic.continually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Tests for cascade behavior when the referencing entity is removed from its repository.
 *
 * Verifies that [CascadeAudioPlaylist] (CASCADE) removes the referenced [AudioItem],
 * [MutableRefPlaylist] (DETACH + bubbleUp) only cancels subscription, and [NoneAudioPlaylist]
 * (NONE) does nothing on delete.
 */
@DisplayName("AggregateCascadeTest")
internal class AggregateCascadeTest : FunSpec({

    val reactive = reactiveScope()

    lateinit var ctx: LirpContext

    beforeEach {
        ctx = LirpContext()
    }

    afterEach {
        ctx.close()
    }

    test("CASCADE remove() deletes the referenced audio item from its repository") {
        val audioItemRepo = AudioItemVolatileRepository(ctx)
        audioItemRepo.create(id = 1, title = "Track A")

        val cascadePlaylistRepo = CascadePlaylistRepo(ctx)
        cascadePlaylistRepo.create(id = 100, name = "Playlist A", audioItemIds = listOf(1))

        // Verify setup
        audioItemRepo.findById(1).shouldBePresent()

        // Remove the parent — cascade should remove the child
        cascadePlaylistRepo.remove(cascadePlaylistRepo.findById(100).get())

        audioItemRepo.contains(1) shouldBe false
    }

    test("CASCADE clear() deletes all referenced audio items from their repositories") {
        val audioItemRepo = AudioItemVolatileRepository(ctx)
        audioItemRepo.create(id = 1, title = "Track A")
        audioItemRepo.create(id = 2, title = "Track B")

        val cascadePlaylistRepo = CascadePlaylistRepo(ctx)
        cascadePlaylistRepo.create(id = 100, name = "Playlist A", audioItemIds = listOf(1))
        cascadePlaylistRepo.create(id = 101, name = "Playlist B", audioItemIds = listOf(2))

        // Verify setup
        audioItemRepo.size() shouldBe 2

        // Clear all parents — cascade should remove all children
        cascadePlaylistRepo.clear()

        audioItemRepo.size() shouldBe 0
    }

    test("DETACH remove() cancels the bubble-up subscription but referenced audio item remains in repository") {
        val audioItemRepo = AudioItemVolatileRepository(ctx)
        val audioItem = audioItemRepo.create(id = 1, title = "Track A") as MutableAudioItem

        // MutableRefPlaylist has @Aggregate(bubbleUp = true) — default onDelete is DETACH
        val mutableRefPlaylistRepo = MutableRefPlaylistRepo(ctx)
        val playlist = mutableRefPlaylistRepo.create(id = 100, audioItemId = 1)

        // Subscribe to playlist events (to check bubble-up is active before detach)
        val eventCountBefore = AtomicInteger(0)
        val beforeLatch = CountDownLatch(1)
        val subscription =
            playlist.subscribeAsync { event ->
                if (event is AggregateMutationEvent<*, *>) {
                    eventCountBefore.incrementAndGet()
                    beforeLatch.countDown()
                }
            }

        // Confirm bubble-up is active
        audioItem.title = "Track A Updated"
        beforeLatch.await(2, TimeUnit.SECONDS) shouldBe true
        eventCountBefore.get() shouldBe 1

        // Remove the parent — DETACH should cancel subscription, audio item stays
        mutableRefPlaylistRepo.remove(playlist)

        // Audio item still exists
        audioItemRepo.contains(1) shouldBe true

        // After removal, the playlist should not receive further events
        val eventCountAfter = AtomicInteger(0)
        audioItem.title = "Track A Updated Again"
        continually(300.milliseconds) { eventCountAfter.get() shouldBe 0 }
        subscription.cancel()
    }

    test("NONE remove() does nothing — referenced audio item stays in repository and subscription stays active") {
        val audioItemRepo = AudioItemVolatileRepository(ctx)
        audioItemRepo.create(id = 1, title = "Track A")

        val nonePlaylistRepo = NonePlaylistRepo(ctx)
        val playlist = nonePlaylistRepo.create(id = 100, name = "None Playlist", audioItemIds = listOf(1))

        // Remove the parent with NONE cascade action
        nonePlaylistRepo.remove(playlist)

        // Audio item still exists
        audioItemRepo.contains(1) shouldBe true
    }

    test("RESTRICT remove() throws IllegalStateException when another entity still references the target audio item") {
        val audioItemRepo = AudioItemVolatileRepository(ctx)
        audioItemRepo.create(id = 1, title = "Track A")

        val restrictPlaylistRepo = RestrictPlaylistRepo(ctx)
        val playlist1 = restrictPlaylistRepo.create(id = 100, name = "Restrict Playlist A", audioItemIds = listOf(1))
        restrictPlaylistRepo.create(id = 101, name = "Restrict Playlist B", audioItemIds = listOf(1))

        // playlist1 references audio item; playlist2 also references audio item
        // Removing playlist1 should throw because playlist2 still references audio item
        val exception =
            shouldThrow<IllegalStateException> {
                restrictPlaylistRepo.remove(playlist1)
            }
        exception.message shouldContain "Cannot cascade-delete"
    }

    test("RESTRICT remove() allows deletion when no other entity references the target audio item") {
        val audioItemRepo = AudioItemVolatileRepository(ctx)
        audioItemRepo.create(id = 1, title = "Track A")

        val restrictPlaylistRepo = RestrictPlaylistRepo(ctx)
        val playlist = restrictPlaylistRepo.create(id = 100, name = "Restrict Playlist", audioItemIds = listOf(1))

        // Only playlist references audio item — removal proceeds without error
        restrictPlaylistRepo.remove(playlist)

        // Audio item still exists (RESTRICT does not cascade-delete, just prevents if others reference)
        audioItemRepo.contains(1) shouldBe true
    }

    test("CASCADE on a cyclic reference graph throws IllegalStateException with cycle detected message") {
        val cyclicPlaylistRepo = CyclicPlaylistRepo(ctx)
        val cyclicPlaylistChildRepo = CyclicPlaylistChildRepo(ctx)

        val parent = cyclicPlaylistRepo.create(id = 1L, childId = 2L)
        cyclicPlaylistChildRepo.create(id = 2L, parentId = 1L)

        val exception =
            shouldThrow<IllegalStateException> {
                cyclicPlaylistRepo.remove(parent)
            }
        exception.message shouldContain "Cascade cycle detected"
    }

    test("CASCADE on an already-removed entity logs warning and returns without error") {
        val audioItemRepo = AudioItemVolatileRepository(ctx)
        audioItemRepo.create(id = 1, title = "Track A")

        val cascadePlaylistRepo = CascadePlaylistRepo(ctx)
        val playlist1 = cascadePlaylistRepo.create(id = 100, name = "Playlist A", audioItemIds = listOf(1))
        val playlist2 = cascadePlaylistRepo.create(id = 101, name = "Playlist B", audioItemIds = listOf(1))

        // Remove playlist1 — audio item gets cascade-deleted
        cascadePlaylistRepo.remove(playlist1)
        audioItemRepo.contains(1) shouldBe false

        // Remove playlist2 — audio item already gone, should complete without error (not throw)
        cascadePlaylistRepo.remove(playlist2)
        audioItemRepo.contains(1) shouldBe false
    }

    test("Concurrent wireBubbleUp and cancelBubbleUp do not leak subscriptions") {
        val audioItemRepo = AudioItemVolatileRepository(ctx)
        val audioItem = audioItemRepo.create(id = 1, title = "Track A") as MutableAudioItem

        val bubbleUpPlaylistRepo = BubbleUpAudioPlaylistRepo(ctx)
        val playlist = bubbleUpPlaylistRepo.create(id = 100, audioItemId = 1)

        // Cast to AggregateRefDelegate to access wireBubbleUp/cancelBubbleUp directly.
        // playlist.audioItem returns this (the delegate itself) via getValue().
        val delegate = playlist.audioItem as AggregateRefDelegate<Int, AudioItem>

        // Launch 50 coroutines: even-indexed wire, odd-indexed cancel
        runBlocking {
            (0 until 50).map { index ->
                launch(Dispatchers.Default) {
                    if (index % 2 == 0) {
                        delegate.wireBubbleUp(playlist, "audioItem")
                    } else {
                        delegate.cancelBubbleUp()
                    }
                }
            }.joinAll()
        }

        // Final clean state: cancel any residual subscription
        delegate.cancelBubbleUp()

        // After final cancel, no events should be forwarded
        val eventCount = AtomicInteger(0)
        playlist.subscribeAsync { event ->
            if (event is AggregateMutationEvent<*, *>) {
                eventCount.incrementAndGet()
            }
        }

        audioItem.title = "Track A Updated After Concurrent Storm"
        continually(300.milliseconds) { eventCount.get() shouldBe 0 }
    }

    test("ReactiveEntityBase close() always executes DETACH cleanup regardless of cascade config") {
        val audioItemRepo = AudioItemVolatileRepository(ctx)
        val audioItem = audioItemRepo.create(id = 1, title = "Track A") as MutableAudioItem

        // MutableRefPlaylist has @Aggregate(bubbleUp = true) — default onDelete is DETACH
        val mutableRefPlaylistRepo = MutableRefPlaylistRepo(ctx)
        val playlist = mutableRefPlaylistRepo.create(id = 100, audioItemId = 1)

        val eventCount = AtomicInteger(0)
        val initialLatch = CountDownLatch(1)

        playlist.subscribeAsync { event ->
            if (event is AggregateMutationEvent<*, *>) {
                eventCount.incrementAndGet()
                initialLatch.countDown()
            }
        }

        // Verify bubble-up is active
        audioItem.title = "Track A Updated"
        initialLatch.await(2, TimeUnit.SECONDS) shouldBe true
        eventCount.get() shouldBe 1

        // Close the playlist entity (not remove from repository)
        playlist.close()

        // After close, no more bubble-up events should reach the playlist
        audioItem.title = "Track A Updated Again"
        continually(300.milliseconds) { eventCount.get() shouldBe 1 } // still 1, no new events
    }

    test("MutableRefPlaylist bubble-up subscription is cancelled when the playlist is removed from its repository") {
        val audioItemRepo = AudioItemVolatileRepository(ctx)
        val mutableRefPlaylistRepo = MutableRefPlaylistRepo(ctx)

        val audioItem = audioItemRepo.create(id = 1, title = "Track A") as MutableAudioItem
        val playlist = mutableRefPlaylistRepo.create(id = 100, audioItemId = 1)

        val received = mutableListOf<MutationEvent<Int, MutableRefPlaylist>>()
        playlist.subscribeAsync { received.add(it) }

        audioItem.title = "Track B"
        reactive.advance()

        received.size shouldBe 1

        mutableRefPlaylistRepo.remove(playlist)

        audioItem.title = "Track C"
        reactive.advance()

        received.size shouldBe 1
    }

    test("Scalar RESTRICT remove() throws IllegalStateException when another entity references the same target") {
        val audioItemRepo = AudioItemVolatileRepository(ctx)
        audioItemRepo.create(id = 1, title = "Track A")

        val restrictRefRepo = RestrictRefPlaylistRepo(ctx)
        val playlist1 = restrictRefRepo.create(id = 100, audioItemId = 1)
        // Another playlist also references audio item 1
        restrictRefRepo.create(id = 101, audioItemId = 1)

        val exception =
            shouldThrow<IllegalStateException> {
                restrictRefRepo.remove(playlist1)
            }
        exception.message shouldContain "Cannot cascade-delete"
        // Audio item still present
        audioItemRepo.contains(1) shouldBe true
    }

    test("Scalar RESTRICT remove() allows deletion when no other entity references the target") {
        val audioItemRepo = AudioItemVolatileRepository(ctx)
        audioItemRepo.create(id = 1, title = "Track A")

        val restrictRefRepo = RestrictRefPlaylistRepo(ctx)
        val playlist = restrictRefRepo.create(id = 100, audioItemId = 1)

        // Only this playlist references audio item — removal succeeds without error
        restrictRefRepo.remove(playlist)

        // Audio item still exists (RESTRICT does not cascade-delete)
        audioItemRepo.contains(1) shouldBe true
    }

    test("Scalar NONE remove() does nothing — referenced audio item stays in repository") {
        val audioItemRepo = AudioItemVolatileRepository(ctx)
        audioItemRepo.create(id = 1, title = "Track A")

        val noneRefRepo = NoneRefPlaylistRepo(ctx)
        val playlist = noneRefRepo.create(id = 100, audioItemId = 1)

        noneRefRepo.remove(playlist)

        audioItemRepo.contains(1) shouldBe true
    }
})