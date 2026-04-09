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

package net.transgressoft.lirp.persistence.fx

import net.transgressoft.lirp.entity.subscribeToCollectionChanges
import net.transgressoft.lirp.event.CollectionChangeEvent
import net.transgressoft.lirp.event.ReactiveScope
import net.transgressoft.lirp.persistence.AudioItem
import net.transgressoft.lirp.persistence.AudioItemVolatileRepository
import net.transgressoft.lirp.persistence.LirpContext
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import javafx.collections.ListChangeListener
import javafx.collections.SetChangeListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Integration tests verifying that RegistryBase correctly unwraps fx proxies via
 * [FxObservableCollectionProxy][net.transgressoft.lirp.persistence.FxObservableCollectionProxy],
 * enabling dual notification: lirp [CollectionChangeEvent] and JavaFX listener callbacks
 * fire for the same mutation.
 */
@DisplayName("FxRegistryIntegrationTest")
@OptIn(ExperimentalCoroutinesApi::class)
class FxRegistryIntegrationTest : StringSpec({

    val testDispatcher = UnconfinedTestDispatcher()
    val testScope = CoroutineScope(testDispatcher)
    lateinit var previousFlowScope: CoroutineScope
    lateinit var previousIoScope: CoroutineScope

    beforeSpec {
        FxToolkitInit.ensureInitialized()
        previousFlowScope = ReactiveScope.flowScope
        previousIoScope = ReactiveScope.ioScope
        ReactiveScope.flowScope = testScope
        ReactiveScope.ioScope = testScope
    }

    afterSpec {
        ReactiveScope.flowScope = previousFlowScope
        ReactiveScope.ioScope = previousIoScope
    }

    lateinit var trackRepo: AudioItemVolatileRepository
    lateinit var fxPlaylistRepo: FxAudioPlaylistVolatileRepository

    beforeEach {
        trackRepo = AudioItemVolatileRepository()
        fxPlaylistRepo = FxAudioPlaylistVolatileRepository()
    }

    afterEach {
        LirpContext.default.close()
    }

    "RegistryBase binds fx list proxy and fires CollectionChangeEvent" {
        val t1 = trackRepo.create(1, "Track 1")
        val playlist = fxPlaylistRepo.create(1, "Test")

        val lirpEvent = AtomicReference<CollectionChangeEvent<*>>(null)
        val latch = CountDownLatch(1)

        playlist.subscribeToCollectionChanges {
            lirpEvent.set(it)
            latch.countDown()
        }

        playlist.audioItems.add(t1)

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        val collectionEvent = lirpEvent.get()
        collectionEvent.type shouldBe CollectionChangeEvent.Type.ADD
        collectionEvent.added shouldContainExactly listOf(t1)
    }

    "RegistryBase binds fx set proxy and fires CollectionChangeEvent" {
        val playlist1 = fxPlaylistRepo.create(1, "Parent")
        val playlist2 = fxPlaylistRepo.create(2, "Child")

        val lirpEvent = AtomicReference<CollectionChangeEvent<*>>(null)
        val latch = CountDownLatch(1)

        playlist1.subscribeToCollectionChanges {
            lirpEvent.set(it)
            latch.countDown()
        }

        playlist1.playlists.add(playlist2)

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        val collectionEvent = lirpEvent.get()
        collectionEvent.type shouldBe CollectionChangeEvent.Type.ADD
        collectionEvent.added shouldContainExactly listOf(playlist2)
    }

    "fx list proxy fires both JavaFX ListChangeListener and lirp CollectionChangeEvent" {
        val t1 = trackRepo.create(1, "Track 1")
        val playlist = fxPlaylistRepo.create(1, "Test")

        val fxChanges = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        playlist.audioItems.addListener(ListChangeListener(fxChanges::add))

        val lirpEvent = AtomicReference<CollectionChangeEvent<*>>(null)
        val latch = CountDownLatch(1)

        playlist.subscribeToCollectionChanges {
            lirpEvent.set(it)
            latch.countDown()
        }

        playlist.audioItems.add(t1)

        latch.await(2, TimeUnit.SECONDS) shouldBe true

        fxChanges.size shouldBe 1
        val change = fxChanges[0]
        change.next() shouldBe true
        change.wasAdded() shouldBe true

        val collectionEvent = lirpEvent.get()
        collectionEvent.type shouldBe CollectionChangeEvent.Type.ADD
        collectionEvent.added shouldContainExactly listOf(t1)
    }

    "fx set proxy fires both JavaFX SetChangeListener and lirp CollectionChangeEvent" {
        val playlist1 = fxPlaylistRepo.create(1, "Parent")
        val playlist2 = fxPlaylistRepo.create(2, "Child")

        val fxChanges = mutableListOf<SetChangeListener.Change<out FxAudioPlaylistEntity>>()
        playlist1.playlists.addListener(SetChangeListener(fxChanges::add))

        val lirpEvent = AtomicReference<CollectionChangeEvent<*>>(null)
        val latch = CountDownLatch(1)

        playlist1.subscribeToCollectionChanges {
            lirpEvent.set(it)
            latch.countDown()
        }

        playlist1.playlists.add(playlist2)

        latch.await(2, TimeUnit.SECONDS) shouldBe true

        fxChanges.size shouldBe 1
        fxChanges[0].wasAdded() shouldBe true
        fxChanges[0].elementAdded shouldBe playlist2

        val collectionEvent = lirpEvent.get()
        collectionEvent.type shouldBe CollectionChangeEvent.Type.ADD
        collectionEvent.added shouldContainExactly listOf(playlist2)
    }
})