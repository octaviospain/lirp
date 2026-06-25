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
import net.transgressoft.lirp.event.BatchChanged
import net.transgressoft.lirp.event.CollectionChangeEvent
import net.transgressoft.lirp.event.FieldChange
import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.event.PropertyChanged
import net.transgressoft.lirp.persistence.AudioItem
import net.transgressoft.lirp.persistence.AudioItemVolatileRepository
import net.transgressoft.lirp.persistence.AudioPlaylistVolatileRepository
import net.transgressoft.lirp.persistence.BubbleAudioPlaylist
import net.transgressoft.lirp.persistence.BubbleAudioPlaylistRepo
import net.transgressoft.lirp.persistence.BubbleAudioTrack
import net.transgressoft.lirp.persistence.BubbleAudioTrackRepo
import net.transgressoft.lirp.persistence.DefaultAudioPlaylist
import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.persistence.MutableAudioItem
import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Tests for subscription extension functions ([subscribeToCollectionChanges], [subscribeToMutations],
 * [subscribeToProperty], [subscribeToPropertyEvent], [BatchChanged.changesOf],
 * [AggregateMutationEvent.childPropertyChanged]), covering event filtering semantics for the
 * typed property subscription API.
 */
@DisplayName("Subscription extension functions")
class SubscriptionExtensionsTest : StringSpec({

    val reactive = reactiveScope()

    lateinit var ctx: LirpContext
    lateinit var trackRepo: AudioItemVolatileRepository
    lateinit var playlistRepo: AudioPlaylistVolatileRepository
    lateinit var bubbleTrackRepo: BubbleAudioTrackRepo
    lateinit var bubblePlaylistRepo: BubbleAudioPlaylistRepo

    beforeEach {
        ctx = LirpContext()
        trackRepo = AudioItemVolatileRepository(ctx)
        playlistRepo = AudioPlaylistVolatileRepository(ctx)
        bubbleTrackRepo = BubbleAudioTrackRepo(ctx)
        bubblePlaylistRepo = BubbleAudioPlaylistRepo(ctx)
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
        reactive.advance()
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

    "subscribeToMutations receives PropertyChanged event for property change" {
        val playlist = DefaultAudioPlaylist(1, "Original Name").also(playlistRepo::add)

        val receivedEvent = AtomicReference<MutationEvent<*, *>>(null)
        val latch = CountDownLatch(1)

        playlist.subscribeToMutations { event ->
            receivedEvent.set(event)
            latch.countDown()
        }

        playlist.name = "New Name"

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        receivedEvent.get().shouldBeInstanceOf<PropertyChanged<*, *, *>>()
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

        // Drain pending coroutines — any direct MutationEvent that would fire has already fired
        reactive.advance()
        mutationEventCount shouldBe 0
    }

    "subscribe receives both property mutations and collection change events" {
        val t1 = trackRepo.create(1, "Track 1")
        val playlist = DefaultAudioPlaylist(1, "Original Name").also(playlistRepo::add)

        val receivedEvents = mutableListOf<Any>()
        val latch = CountDownLatch(2)

        playlist.subscribeAsync { event ->
            receivedEvents.add(event)
            latch.countDown()
        }

        playlist.name = "New Name"
        playlist.audioItems.add(t1)

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        receivedEvents.size shouldBe 2
        receivedEvents.any { it is PropertyChanged<*, *, *> } shouldBe true
        receivedEvents.any { it is AggregateMutationEvent<*, *> } shouldBe true
    }

    "subscribeToProperty delivers typed old and new values on property change" {
        val item = trackRepo.create(1, "Original Title")

        val receivedOld = AtomicReference<String>(null)
        val receivedNew = AtomicReference<String>(null)
        val latch = CountDownLatch(1)

        item.subscribeToProperty(AudioItem::title) { old, new ->
            receivedOld.set(old)
            receivedNew.set(new)
            latch.countDown()
        }

        item.title = "New Title"

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        receivedOld.get() shouldBe "Original Title"
        receivedNew.get() shouldBe "New Title"
    }

    "subscribeToProperty does not deliver events for other properties" {
        val item = trackRepo.create(1, "Title", "Album")

        var eventCount = 0

        item.subscribeToProperty(AudioItem::title) { _, _ ->
            eventCount++
        }

        item.albumName = "New Album"

        reactive.advance()
        eventCount shouldBe 0
    }

    "subscribeToProperty returns subscription that stops delivery after cancel" {
        val item = trackRepo.create(1, "Original Title")

        var eventCount = 0

        val subscription =
            item.subscribeToProperty(AudioItem::title) { _, _ ->
                eventCount++
            }

        subscription.cancel()
        item.title = "New Title"

        reactive.advance()
        eventCount shouldBe 0
    }

    "subscribeToPropertyEvent delivers full PropertyChanged event with typed values" {
        val item = trackRepo.create(1, "Original Title")

        val receivedEvent = AtomicReference<Any?>(null)
        val latch = CountDownLatch(1)

        // 1-param lambda resolves to the event-form overload (subscribeToPropertyEvent JVM name)
        item.subscribeToProperty(AudioItem::title) { event ->
            receivedEvent.set(event)
            latch.countDown()
        }

        item.title = "New Title"

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        val event = receivedEvent.get()
        event.shouldBeInstanceOf<PropertyChanged<*, *, *>>()
        @Suppress("UNCHECKED_CAST")
        val typedEvent = event as PropertyChanged<Int, AudioItem, String>
        typedEvent.newValue shouldBe "New Title"
        typedEvent.oldValue shouldBe "Original Title"
        // metadata fields (versionAtMutation, oldIndexKey, newIndexKey) are accessible
        // without a cast — they may be null when the entity has no @Version or @Indexed property
        typedEvent.property.name shouldBe "title"
    }

    "BatchChanged changesOf returns typed FieldChange list for named property" {
        val item = trackRepo.create(1, "Original Title", "Original Album")
        require(item is MutableAudioItem)

        val receivedBatch = AtomicReference<Any?>(null)
        val latch = CountDownLatch(1)

        item.subscribeAsync { event ->
            if (event is BatchChanged<*, *>) {
                receivedBatch.set(event)
                latch.countDown()
            }
        }

        item.bulkUpdate("New Title")

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        @Suppress("UNCHECKED_CAST")
        val batch = receivedBatch.get() as BatchChanged<Int, AudioItem>
        val titleChanges: List<FieldChange<AudioItem, String>> = batch.changesOf(AudioItem::title)
        titleChanges.size shouldBe 1
        titleChanges[0].oldValue shouldBe "Original Title"
        titleChanges[0].newValue shouldBe "New Title"
    }

    "BatchChanged changesOf returns empty list when property not touched" {
        val item = trackRepo.create(1, "Original Title", "Original Album")
        require(item is MutableAudioItem)

        val receivedBatch = AtomicReference<Any?>(null)
        val latch = CountDownLatch(1)

        item.subscribeAsync { event ->
            if (event is BatchChanged<*, *>) {
                receivedBatch.set(event)
                latch.countDown()
            }
        }

        item.bulkAlbumUpdate("New Album")

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        @Suppress("UNCHECKED_CAST")
        val batch = receivedBatch.get() as BatchChanged<Int, AudioItem>
        val titleChanges: List<FieldChange<AudioItem, String>> = batch.changesOf(AudioItem::title)
        titleChanges shouldBe emptyList()
    }

    "childPropertyChanged unwraps typed PropertyChanged from AggregateMutationEvent" {
        val track = bubbleTrackRepo.create(1, "Original Name")
        val playlist = bubblePlaylistRepo.create(1, track.id)

        val receivedAggEvent = AtomicReference<Any?>(null)
        val latch = CountDownLatch(1)

        playlist.subscribeAsync { event ->
            if (event is AggregateMutationEvent<*, *>) {
                receivedAggEvent.set(event)
                latch.countDown()
            }
        }

        track.updateTrackName("New Name")

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        @Suppress("UNCHECKED_CAST")
        val aggEvent = receivedAggEvent.get() as AggregateMutationEvent<Int, BubbleAudioPlaylist>
        val childChanged = aggEvent.childPropertyChanged(BubbleAudioTrack::trackName)
        childChanged shouldNotBe null
        childChanged!!.oldValue shouldBe "Original Name"
        childChanged.newValue shouldBe "New Name"
    }

    "childPropertyChanged returns null for non-matching property name" {
        val track = bubbleTrackRepo.create(1, "Original Name")
        val playlist = bubblePlaylistRepo.create(1, track.id)

        val receivedAggEvent = AtomicReference<Any?>(null)
        val latch = CountDownLatch(1)

        playlist.subscribeAsync { event ->
            if (event is AggregateMutationEvent<*, *>) {
                receivedAggEvent.set(event)
                latch.countDown()
            }
        }

        track.updateTrackName("New Name")

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        @Suppress("UNCHECKED_CAST")
        val aggEvent = receivedAggEvent.get() as AggregateMutationEvent<Int, BubbleAudioPlaylist>
        // AudioItem::title has name "title", BubbleAudioTrack has "trackName" — no match
        val result = aggEvent.childPropertyChanged(AudioItem::title)
        result shouldBe null
    }

    "subscribeToProperty on child entity works directly" {
        val track = bubbleTrackRepo.create(1, "Original Name")

        val receivedNew = AtomicReference<String>(null)
        val latch = CountDownLatch(1)

        track.subscribeToProperty(BubbleAudioTrack::trackName) { _, new ->
            receivedNew.set(new)
            latch.countDown()
        }

        track.updateTrackName("New Name")

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        receivedNew.get() shouldBe "New Name"
    }
})