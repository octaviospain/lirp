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

package net.transgressoft.lirp.entity

import net.transgressoft.lirp.event.AggregateMutationEvent
import net.transgressoft.lirp.event.CollectionChangeEvent
import net.transgressoft.lirp.event.ReactiveMutationEvent
import net.transgressoft.lirp.event.ReactiveScope
import net.transgressoft.lirp.persistence.AudioItem
import net.transgressoft.lirp.persistence.AudioItemVolatileRepository
import net.transgressoft.lirp.persistence.AudioPlaylistVolatileRepository
import net.transgressoft.lirp.persistence.DefaultAudioPlaylist
import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.testing.SerializeWithReactiveScope
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Tests for [subscribeToCollectionChanges] and [subscribeToMutations] extension functions,
 * covering event filtering semantics for the 3-tier subscription API.
 */
@DisplayName("Subscription extension functions")
@OptIn(ExperimentalCoroutinesApi::class)
@SerializeWithReactiveScope
class SubscriptionExtensionsTest : StringSpec({

    val testDispatcher = UnconfinedTestDispatcher()
    val testScope = CoroutineScope(testDispatcher)

    beforeSpec {
        ReactiveScope.flowScope = testScope
        ReactiveScope.ioScope = testScope
    }

    afterSpec {
        ReactiveScope.resetDefaultFlowScope()
        ReactiveScope.resetDefaultIoScope()
    }

    lateinit var ctx: LirpContext
    lateinit var trackRepo: AudioItemVolatileRepository
    lateinit var playlistRepo: AudioPlaylistVolatileRepository

    beforeEach {
        ctx = LirpContext()
        trackRepo = AudioItemVolatileRepository(ctx)
        playlistRepo = AudioPlaylistVolatileRepository(ctx)
    }

    afterEach {
        ctx.close()
    }

    "subscribeToCollectionChanges receives ADD event from mutableAggregateList" {
        val t1 = trackRepo.create(1, "Track 1")
        val playlist = DefaultAudioPlaylist(1, "Test").also(playlistRepo::add)

        val receivedEvent = AtomicReference<CollectionChangeEvent<*>>(null)
        val latch = CountDownLatch(1)

        playlist.subscribeToCollectionChanges(AudioItem::class) { event ->
            receivedEvent.set(event)
            latch.countDown()
        }

        playlist.audioItems.add(t1)

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        val collectionEvent = receivedEvent.get()
        collectionEvent.shouldBeInstanceOf<CollectionChangeEvent<*>>()
        collectionEvent.type shouldBe CollectionChangeEvent.Type.ADD
        collectionEvent.added shouldBe listOf(t1)
    }

    "subscribeToCollectionChanges with refName filters to named collection only" {
        val t1 = trackRepo.create(1, "Track 1")
        val playlist = DefaultAudioPlaylist(1, "Test").also(playlistRepo::add)

        val receivedEvent = AtomicReference<CollectionChangeEvent<*>>(null)
        val latch = CountDownLatch(1)

        playlist.subscribeToCollectionChanges(AudioItem::class, "audioItems") { event ->
            receivedEvent.set(event)
            latch.countDown()
        }

        playlist.audioItems.add(t1)

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        receivedEvent.get().shouldBeInstanceOf<CollectionChangeEvent<*>>()
        receivedEvent.get().type shouldBe CollectionChangeEvent.Type.ADD
    }

    "subscribeToCollectionChanges with refName does not receive events from other collections" {
        val subPlaylist = DefaultAudioPlaylist(2, "Sub").also(playlistRepo::add)
        val parent = DefaultAudioPlaylist(1, "Parent").also(playlistRepo::add)

        var eventCount = 0

        // Subscribe only to audioItems, but mutate playlists
        parent.subscribeToCollectionChanges(AudioItem::class, "audioItems") { _ ->
            eventCount++
        }

        parent.playlists.add(subPlaylist)

        // Drain pending coroutines — UnconfinedTestDispatcher executes eagerly,
        // so any event that would fire has already fired after this call
        testDispatcher.scheduler.advanceUntilIdle()
        eventCount shouldBe 0
    }

    "typed subscribeToCollectionChanges delivers CollectionChangeEvent with concrete element type" {
        val t1 = trackRepo.create(1, "Track 1")
        val playlist = DefaultAudioPlaylist(1, "Test").also(playlistRepo::add)

        val receivedItems = mutableListOf<AudioItem>()
        val latch = CountDownLatch(1)

        playlist.subscribeToCollectionChanges(AudioItem::class, "audioItems") { event ->
            receivedItems.addAll(event.added)
            latch.countDown()
        }

        playlist.audioItems.add(t1)

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        receivedItems.size shouldBe 1
        receivedItems[0].id shouldBe 1
    }

    "subscribeToMutations receives ReactiveMutationEvent for property change" {
        val playlist = DefaultAudioPlaylist(1, "Original Name").also(playlistRepo::add)

        val receivedEvent = AtomicReference<ReactiveMutationEvent<*, *>>(null)
        val latch = CountDownLatch(1)

        playlist.subscribeToMutations { event ->
            receivedEvent.set(event)
            latch.countDown()
        }

        playlist.name = "New Name"

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        receivedEvent.get().shouldBeInstanceOf<ReactiveMutationEvent<*, *>>()
    }

    "subscribeToMutations does not receive AggregateMutationEvent from collection mutation" {
        val t1 = trackRepo.create(1, "Track 1")
        val playlist = DefaultAudioPlaylist(1, "Test").also(playlistRepo::add)

        var mutationEventCount = 0

        playlist.subscribeToMutations { _ ->
            mutationEventCount++
        }

        playlist.audioItems.add(t1)
        playlist.audioItems.remove(t1)

        // Drain pending coroutines — any ReactiveMutationEvent that would fire has already fired
        testDispatcher.scheduler.advanceUntilIdle()
        mutationEventCount shouldBe 0
    }

    "subscribe receives both property mutations and collection change events" {
        val t1 = trackRepo.create(1, "Track 1")
        val playlist = DefaultAudioPlaylist(1, "Original Name").also(playlistRepo::add)

        val receivedEvents = mutableListOf<Any>()
        val latch = CountDownLatch(2)

        playlist.subscribe { event ->
            receivedEvents.add(event)
            latch.countDown()
        }

        playlist.name = "New Name"
        playlist.audioItems.add(t1)

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        receivedEvents.size shouldBe 2
        receivedEvents.any { it is ReactiveMutationEvent<*, *> } shouldBe true
        receivedEvents.any { it is AggregateMutationEvent<*, *> } shouldBe true
    }
})