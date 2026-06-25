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
import net.transgressoft.lirp.event.PropertyChanged
import net.transgressoft.lirp.persistence.AudioItemVolatileRepository
import net.transgressoft.lirp.persistence.LirpContext
import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Integration tests verifying that [net.transgressoft.lirp.persistence.RegistryBase] correctly
 * wires [net.transgressoft.lirp.persistence.FxScalarPropertyDelegate] instances via
 * [net.transgressoft.lirp.persistence.RegistryBase.bindEntityRefs], enabling dual notification:
 * lirp [net.transgressoft.lirp.event.PropertyChanged] and JavaFX [javafx.beans.value.ChangeListener]
 * callbacks fire for the same scalar property mutation.
 *
 * Uses the merged [FxAudioPlaylistEntity] which exercises all fx delegate types in a single entity.
 */
@DisplayName("FxScalarRegistryIntegrationTest")
class FxScalarRegistryIntegrationTest : StringSpec({

    val reactive = reactiveScope()

    beforeSpec {
        FxToolkitInit.ensureInitialized()
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

    "RegistryBase binds fx string property and emits PropertyChanged event on set" {
        val entity = fxPlaylistRepo.create(1, "playlist", tag = "initial")

        val received = mutableListOf<MutationEvent<Int, FxAudioPlaylistEntity>>()

        entity.subscribe { event -> received.add(event) }

        entity.tagProperty.set("updated")

        received.size shouldBe 1
        val pc = received[0] as PropertyChanged<Int, FxAudioPlaylistEntity, String>
        pc.oldValue shouldBe "initial"
        pc.newValue shouldBe "updated"
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

    "fx integer property emits PropertyChanged event on set" {
        val entity = fxPlaylistRepo.create(2, "playlist", year = 0)

        val received = mutableListOf<MutationEvent<Int, FxAudioPlaylistEntity>>()

        entity.subscribe { event -> received.add(event) }

        entity.yearProperty.set(2025)

        received.size shouldBe 1
        val pc = received[0] as PropertyChanged<Int, FxAudioPlaylistEntity, Int>
        pc.oldValue shouldBe 0
        pc.newValue shouldBe 2025
    }

    "fx boolean property emits PropertyChanged event on set" {
        val entity = fxPlaylistRepo.create(3, "playlist", active = false)

        val received = mutableListOf<MutationEvent<Int, FxAudioPlaylistEntity>>()

        entity.subscribe { event -> received.add(event) }

        entity.activeProperty.set(true)

        received.size shouldBe 1
        val pc = received[0] as PropertyChanged<Int, FxAudioPlaylistEntity, Boolean>
        pc.oldValue shouldBe false
        pc.newValue shouldBe true
    }

    "fx object property emits PropertyChanged event on set" {
        val entity = fxPlaylistRepo.create(4, "playlist", description = null)

        val received = mutableListOf<MutationEvent<Int, FxAudioPlaylistEntity>>()

        entity.subscribe { event -> received.add(event) }

        entity.descriptionProperty.set("vip")

        received.size shouldBe 1
        val pc = received[0] as PropertyChanged<Int, FxAudioPlaylistEntity, String>
        pc.oldValue shouldBe null
        pc.newValue shouldBe "vip"
    }

    "withEventsDisabled suppresses MutationEvent for fx scalar property" {
        val entity = fxPlaylistRepo.create(5, "playlist", tag = "before")

        val received = mutableListOf<MutationEvent<Int, FxAudioPlaylistEntity>>()

        entity.subscribe { event -> received.add(event) }

        entity.silently { entity.tagProperty.set("silent") }

        received.size shouldBe 0
        entity.tagProperty.get() shouldBe "silent"
    }

    "fx scalar property set before repository add does not emit event" {
        val entity = FxAudioPlaylistEntity(6, "standalone")

        val received = mutableListOf<MutationEvent<Int, FxAudioPlaylistEntity>>()

        entity.subscribe { event -> received.add(event) }

        entity.tagProperty.set("changed")

        // Entity is standalone (not in a repository), so no publisher is active — no event emitted.
        entity.tagProperty.get() shouldBe "changed"
        received.size shouldBe 0
    }
})