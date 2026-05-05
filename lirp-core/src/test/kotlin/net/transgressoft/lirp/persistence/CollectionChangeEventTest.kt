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
import net.transgressoft.lirp.event.CollectionChangeEvent
import net.transgressoft.lirp.event.ReactiveMutationEvent
import net.transgressoft.lirp.event.ReactiveScope
import net.transgressoft.lirp.testing.SerializeWithReactiveScope
import io.kotest.assertions.nondeterministic.continually
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Tests that mutable aggregate collection operations emit [AggregateMutationEvent] wrapping
 * [CollectionChangeEvent] diffs, and never emit [ReactiveMutationEvent] for collection mutations.
 */
@DisplayName("CollectionChangeEvent emission")
@OptIn(ExperimentalCoroutinesApi::class)
@SerializeWithReactiveScope
internal class CollectionChangeEventTest : StringSpec({

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

    "add(element) on mutableAggregateList emits ADD CollectionChangeEvent with the added element" {
        val t1 = trackRepo.create(1, "Track 1")
        val playlist = DefaultAudioPlaylist(1, "Test").also(playlistRepo::add)

        val receivedEvent = AtomicReference<AggregateMutationEvent<*, *>>(null)
        val latch = CountDownLatch(1)

        playlist.subscribe { event ->
            if (event is AggregateMutationEvent<*, *>) {
                receivedEvent.set(event)
                latch.countDown()
            }
        }

        playlist.audioItems.add(t1)

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        val aggregateEvent = receivedEvent.get()
        val childEvent = aggregateEvent.childEvent
        childEvent.shouldBeInstanceOf<CollectionChangeEvent<*>>()
        @Suppress("UNCHECKED_CAST")
        val collectionEvent = childEvent as CollectionChangeEvent<AudioItem>
        collectionEvent.type shouldBe CollectionChangeEvent.Type.ADD
        collectionEvent.added shouldContainExactly listOf(t1)
        collectionEvent.removed shouldBe emptyList()
    }

    "remove(element) on mutableAggregateList emits REMOVE CollectionChangeEvent with the removed element" {
        val t1 = trackRepo.create(1, "Track 1")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(1)).also(playlistRepo::add)

        val receivedEvent = AtomicReference<AggregateMutationEvent<*, *>>(null)
        val latch = CountDownLatch(1)

        playlist.subscribe { event ->
            if (event is AggregateMutationEvent<*, *>) {
                receivedEvent.set(event)
                latch.countDown()
            }
        }

        playlist.audioItems.remove(t1)

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        val aggregateEvent = receivedEvent.get()
        val childEvent = aggregateEvent.childEvent
        childEvent.shouldBeInstanceOf<CollectionChangeEvent<*>>()
        @Suppress("UNCHECKED_CAST")
        val collectionEvent = childEvent as CollectionChangeEvent<AudioItem>
        collectionEvent.type shouldBe CollectionChangeEvent.Type.REMOVE
        collectionEvent.added shouldBe emptyList()
        collectionEvent.removed shouldContainExactly listOf(t1)
    }

    "set(index, element) on mutableAggregateList emits REPLACE CollectionChangeEvent with old and new elements" {
        val t1 = trackRepo.create(1, "Track 1")
        val t2 = trackRepo.create(2, "Track 2")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(1)).also(playlistRepo::add)

        val receivedEvent = AtomicReference<AggregateMutationEvent<*, *>>(null)
        val latch = CountDownLatch(1)

        playlist.subscribe { event ->
            if (event is AggregateMutationEvent<*, *>) {
                receivedEvent.set(event)
                latch.countDown()
            }
        }

        playlist.audioItems[0] = t2

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        val aggregateEvent = receivedEvent.get()
        val childEvent = aggregateEvent.childEvent
        childEvent.shouldBeInstanceOf<CollectionChangeEvent<*>>()
        @Suppress("UNCHECKED_CAST")
        val collectionEvent = childEvent as CollectionChangeEvent<AudioItem>
        collectionEvent.type shouldBe CollectionChangeEvent.Type.REPLACE
        collectionEvent.added shouldContainExactly listOf(t2)
        collectionEvent.removed shouldContainExactly listOf(t1)
    }

    "addAll(elements) emits single ADD CollectionChangeEvent with all added elements" {
        val t1 = trackRepo.create(1, "Track 1")
        val t2 = trackRepo.create(2, "Track 2")
        val playlist = DefaultAudioPlaylist(1, "Test").also(playlistRepo::add)

        val receivedEvents = mutableListOf<AggregateMutationEvent<*, *>>()
        val latch = CountDownLatch(1)

        playlist.subscribe { event ->
            if (event is AggregateMutationEvent<*, *>) {
                receivedEvents.add(event)
                latch.countDown()
            }
        }

        playlist.audioItems.addAll(listOf(t1, t2))

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        receivedEvents.size shouldBe 1
        val childEvent = receivedEvents[0].childEvent
        childEvent.shouldBeInstanceOf<CollectionChangeEvent<*>>()
        @Suppress("UNCHECKED_CAST")
        val collectionEvent = childEvent as CollectionChangeEvent<AudioItem>
        collectionEvent.type shouldBe CollectionChangeEvent.Type.ADD
        collectionEvent.added shouldContainExactly listOf(t1, t2)
        collectionEvent.removed shouldBe emptyList()
    }

    "removeAll(elements) emits single REMOVE CollectionChangeEvent with all removed elements" {
        val t1 = trackRepo.create(1, "Track 1")
        val t2 = trackRepo.create(2, "Track 2")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(1, 2)).also(playlistRepo::add)

        val receivedEvents = mutableListOf<AggregateMutationEvent<*, *>>()
        val latch = CountDownLatch(1)

        playlist.subscribe { event ->
            if (event is AggregateMutationEvent<*, *>) {
                receivedEvents.add(event)
                latch.countDown()
            }
        }

        playlist.audioItems.removeAll(listOf(t1, t2))

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        receivedEvents.size shouldBe 1
        val childEvent = receivedEvents[0].childEvent
        childEvent.shouldBeInstanceOf<CollectionChangeEvent<*>>()
        @Suppress("UNCHECKED_CAST")
        val collectionEvent = childEvent as CollectionChangeEvent<AudioItem>
        collectionEvent.type shouldBe CollectionChangeEvent.Type.REMOVE
        collectionEvent.added shouldBe emptyList()
        collectionEvent.removed shouldContainExactly listOf(t1, t2)
    }

    "clear() emits CLEAR CollectionChangeEvent with all removed entities" {
        val t1 = trackRepo.create(1, "Track 1")
        val t2 = trackRepo.create(2, "Track 2")
        val playlist = DefaultAudioPlaylist(1, "Test", listOf(1, 2)).also(playlistRepo::add)

        val receivedEvent = AtomicReference<AggregateMutationEvent<*, *>>(null)
        val latch = CountDownLatch(1)

        playlist.subscribe { event ->
            if (event is AggregateMutationEvent<*, *>) {
                receivedEvent.set(event)
                latch.countDown()
            }
        }

        playlist.audioItems.clear()

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        val aggregateEvent = receivedEvent.get()
        val childEvent = aggregateEvent.childEvent
        childEvent.shouldBeInstanceOf<CollectionChangeEvent<*>>()
        @Suppress("UNCHECKED_CAST")
        val collectionEvent = childEvent as CollectionChangeEvent<AudioItem>
        collectionEvent.type shouldBe CollectionChangeEvent.Type.CLEAR
        collectionEvent.added shouldBe emptyList()
        collectionEvent.removed shouldContainExactly listOf(t1, t2)
    }

    "clear() on empty collection does not emit any event" {
        val playlist = DefaultAudioPlaylist(1, "Test").also(playlistRepo::add)

        var eventCount = 0
        playlist.subscribe { event ->
            if (event is AggregateMutationEvent<*, *>) {
                eventCount++
            }
        }

        playlist.audioItems.clear()

        continually(200.milliseconds) {
            eventCount shouldBe 0
        }
    }

    "collection mutation does not emit ReactiveMutationEvent" {
        val t1 = trackRepo.create(1, "Track 1")
        val playlist = DefaultAudioPlaylist(1, "Test").also(playlistRepo::add)

        var reactiveMutationCount = 0
        playlist.subscribe { event ->
            if (event is ReactiveMutationEvent<*, *>) {
                reactiveMutationCount++
            }
        }

        playlist.audioItems.add(t1)
        playlist.audioItems.remove(t1)

        continually(200.milliseconds) {
            reactiveMutationCount shouldBe 0
        }
    }

    "add(element) on mutableAggregateSet emits ADD CollectionChangeEvent" {
        val p1 = DefaultAudioPlaylist(1, "P1").also(playlistRepo::add)
        val group = DefaultAudioPlaylist(100, "Group").also(playlistRepo::add)

        val receivedEvent = AtomicReference<AggregateMutationEvent<*, *>>(null)
        val latch = CountDownLatch(1)

        group.subscribe { event ->
            if (event is AggregateMutationEvent<*, *>) {
                receivedEvent.set(event)
                latch.countDown()
            }
        }

        group.playlists.add(p1)

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        val aggregateEvent = receivedEvent.get()
        val childEvent = aggregateEvent.childEvent
        childEvent.shouldBeInstanceOf<CollectionChangeEvent<*>>()
        @Suppress("UNCHECKED_CAST")
        val collectionEvent = childEvent as CollectionChangeEvent<MutableAudioPlaylist>
        collectionEvent.type shouldBe CollectionChangeEvent.Type.ADD
        collectionEvent.added shouldContainExactly listOf(p1)
        collectionEvent.removed shouldBe emptyList()
    }

    "AggregateMutationEvent.refName matches the collection property name" {
        val t1 = trackRepo.create(1, "Track 1")
        val playlist = DefaultAudioPlaylist(1, "Test").also(playlistRepo::add)

        val receivedEvent = AtomicReference<AggregateMutationEvent<*, *>>(null)
        val latch = CountDownLatch(1)

        playlist.subscribe { event ->
            if (event is AggregateMutationEvent<*, *>) {
                receivedEvent.set(event)
                latch.countDown()
            }
        }

        playlist.audioItems.add(t1)

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        receivedEvent.get().refName shouldBe "audioItems"
    }
})