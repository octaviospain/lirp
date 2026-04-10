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

import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.event.ReactiveScope
import net.transgressoft.lirp.persistence.AudioItemVolatileRepository
import net.transgressoft.lirp.persistence.LirpContext
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Integration tests verifying that [net.transgressoft.lirp.persistence.RegistryBase] correctly
 * wires [net.transgressoft.lirp.persistence.FxScalarPropertyDelegate] instances via
 * [net.transgressoft.lirp.persistence.RegistryBase.bindEntityRefs], enabling dual notification:
 * lirp [net.transgressoft.lirp.event.ReactiveMutationEvent] and JavaFX [javafx.beans.value.ChangeListener]
 * callbacks fire for the same scalar property mutation.
 *
 * Uses the merged [FxAudioPlaylistEntity] which exercises all fx delegate types in a single entity.
 */
@DisplayName("FxScalarRegistryIntegrationTest")
@OptIn(ExperimentalCoroutinesApi::class)
class FxScalarRegistryIntegrationTest : StringSpec({

    val testDispatcher = UnconfinedTestDispatcher()
    val testScope = CoroutineScope(testDispatcher)

    beforeSpec {
        FxToolkitInit.ensureInitialized()
        ReactiveScope.flowScope = testScope
        ReactiveScope.ioScope = testScope
    }

    afterSpec {
        ReactiveScope.resetDefaultFlowScope()
        ReactiveScope.resetDefaultIoScope()
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

    "RegistryBase binds fx string property and emits ReactiveMutationEvent on set" {
        val entity = fxPlaylistRepo.create(1, "playlist", tag = "initial")

        val receivedEvent = AtomicReference<MutationEvent<Int, FxAudioPlaylistEntity>>(null)
        val latch = CountDownLatch(1)

        entity.subscribe { event ->
            receivedEvent.set(event)
            latch.countDown()
        }

        entity.tagProperty.set("updated")

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        val event = receivedEvent.get()
        event shouldNotBe null
        event.oldEntity.tagProperty.get() shouldBe "initial"
        event.newEntity.tagProperty.get() shouldBe "updated"
    }

    "fx string property fires JavaFX ChangeListener after RegistryBase binding" {
        val entity = fxPlaylistRepo.create(1, "playlist", tag = "old")

        var observedOld: String? = null
        var observedNew: String? = null
        entity.tagProperty.addListener { _, old, new ->
            observedOld = old
            observedNew = new
        }

        entity.tagProperty.set("new")

        observedOld shouldBe "old"
        observedNew shouldBe "new"
    }

    "fx integer property emits ReactiveMutationEvent on set" {
        val entity = fxPlaylistRepo.create(2, "playlist", year = 0)

        val receivedEvent = AtomicReference<MutationEvent<Int, FxAudioPlaylistEntity>>(null)
        val latch = CountDownLatch(1)

        entity.subscribe { event ->
            receivedEvent.set(event)
            latch.countDown()
        }

        entity.yearProperty.set(2025)

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        val event = receivedEvent.get()
        event shouldNotBe null
        event.oldEntity.yearProperty.get() shouldBe 0
        event.newEntity.yearProperty.get() shouldBe 2025
    }

    "fx boolean property emits ReactiveMutationEvent on set" {
        val entity = fxPlaylistRepo.create(3, "playlist", active = false)

        val receivedEvent = AtomicReference<MutationEvent<Int, FxAudioPlaylistEntity>>(null)
        val latch = CountDownLatch(1)

        entity.subscribe { event ->
            receivedEvent.set(event)
            latch.countDown()
        }

        entity.activeProperty.set(true)

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        val event = receivedEvent.get()
        event shouldNotBe null
        event.oldEntity.activeProperty.get() shouldBe false
        event.newEntity.activeProperty.get() shouldBe true
    }

    "fx object property emits ReactiveMutationEvent on set" {
        val entity = fxPlaylistRepo.create(4, "playlist", description = null)

        val receivedEvent = AtomicReference<MutationEvent<Int, FxAudioPlaylistEntity>>(null)
        val latch = CountDownLatch(1)

        entity.subscribe { event ->
            receivedEvent.set(event)
            latch.countDown()
        }

        entity.descriptionProperty.set("vip")

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        val event = receivedEvent.get()
        event shouldNotBe null
        event.oldEntity.descriptionProperty.get() shouldBe null
        event.newEntity.descriptionProperty.get() shouldBe "vip"
    }

    "withEventsDisabled suppresses MutationEvent for fx scalar property" {
        val entity = fxPlaylistRepo.create(5, "playlist", tag = "before")

        val receivedEvent = AtomicReference<MutationEvent<Int, FxAudioPlaylistEntity>>(null)
        val latch = CountDownLatch(1)

        entity.subscribe { event ->
            receivedEvent.set(event)
            latch.countDown()
        }

        entity.silently { entity.tagProperty.set("silent") }

        latch.await(500, TimeUnit.MILLISECONDS) shouldBe false
        receivedEvent.get() shouldBe null
        entity.tagProperty.get() shouldBe "silent"
    }

    "fx scalar property set before repository add does not emit event" {
        val entity = FxAudioPlaylistEntity(6, "standalone")

        val receivedEvent = AtomicReference<MutationEvent<Int, FxAudioPlaylistEntity>>(null)

        entity.subscribe { event ->
            receivedEvent.set(event)
        }

        entity.tagProperty.set("changed")

        Thread.sleep(200)
        entity.tagProperty.get() shouldBe "changed"
        receivedEvent.get() shouldBe null
    }
})