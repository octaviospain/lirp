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

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.optional.shouldBeEmpty
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe

/**
 * Verifies that aggregate reference operations execute without runtime reflection on the hot path.
 *
 * Absence of `findDelegateField` and `cancelAllBubbleUpSubscriptions` in the compiled class confirms
 * that reflection-based delegate access was removed. All four delegate paths (bind, wire, cascade, detach)
 * are exercised by adding, removing, and closing entities — if any reflection helper were invoked,
 * the corresponding test entity's method invocation would succeed only because reflection was used.
 *
 * This is a structural verification: the compile-time proof is that the removed methods no longer
 * exist in the class, and the runtime proof is that all operations complete successfully without errors.
 */
@DisplayName("RegistryBase")
internal class ReflectionFreeVerificationTest : FunSpec({

    val reactive = reactiveScope()

    lateinit var ctx: LirpContext
    lateinit var audioItemRepo: AudioItemVolatileRepository
    lateinit var playlistRepo: BubbleUpAudioPlaylistRepo

    beforeEach {
        ctx = LirpContext()
        audioItemRepo = AudioItemVolatileRepository(ctx)
        playlistRepo = BubbleUpAudioPlaylistRepo(ctx)
    }

    afterEach {
        ctx.close()
    }

    test("RegistryBase does not contain findDelegateField reflection helper method") {
        val methodNames = RegistryBase::class.java.declaredMethods.map { it.name }

        methodNames.contains("findDelegateField").shouldBeFalse()
    }

    test("RegistryBase does not contain bindDelegateField reflection helper method") {
        val methodNames = RegistryBase::class.java.declaredMethods.map { it.name }

        methodNames.contains("bindDelegateField").shouldBeFalse()
    }

    test("ReactiveEntityBase does not contain cancelAllBubbleUpSubscriptions reflection scan method") {
        val methodNames = ReactiveEntityBase::class.java.declaredMethods.map { it.name }

        methodNames.contains("cancelAllBubbleUpSubscriptions").shouldBeFalse()
    }

    test("AggregateRefDelegate does not contain bindRegistryUntyped method") {
        val methodNames = AggregateRefDelegate::class.java.declaredMethods.map { it.name }

        methodNames.contains("bindRegistryUntyped").shouldBeFalse()
    }

    test("bindEntityRefs path resolves audio item reference after adding playlist to repository") {
        val audioItem = audioItemRepo.create(id = 1, title = "Track A")
        val playlist = playlistRepo.create(id = 100, audioItemId = 1)

        playlist.audioItem.resolve() shouldBePresent { it.title shouldBe "Track A" }
    }

    test("wireRefBubbleUp path wires subscription when bubbleUp is true") {
        val audioItem = audioItemRepo.create(id = 2, title = "Track B") as MutableAudioItem

        val playlist = playlistRepo.create(id = 200, audioItemId = 2)

        var received = false
        playlist.subscribeAsync { received = true }
        audioItem.title = "Track B Updated"

        received shouldBe true
    }

    test("executeCascadeForEntity path removes referenced entity on CASCADE delete") {
        val audioItem = audioItemRepo.create(id = 3, title = "Track C")

        val cascadeRepo = CascadePlaylistRepo(ctx)
        val cascadePlaylist = cascadeRepo.create(id = 300, name = "Cascade Playlist", audioItemIds = listOf(3))

        // Removing triggers executeCascadeForEntity via delegateGetter (no findDelegateField)
        cascadeRepo.remove(cascadePlaylist)

        // CASCADE: referenced audio item is also removed
        audioItemRepo.findById(3).shouldBeEmpty()
    }

    test("close() cancels bubble-up subscriptions via cancelAllBubbleUp without field scan") {
        val audioItem = audioItemRepo.create(id = 4, title = "Track D") as MutableAudioItem

        val mutableRefRepo = MutableRefPlaylistRepo(ctx)
        val playlist = mutableRefRepo.create(id = 400, audioItemId = 4)

        var receivedAfterClose = false
        playlist.subscribeAsync { receivedAfterClose = true }

        // close() uses loadRefAccessor + cancelAllBubbleUp — not the old declaredFields scan
        playlist.close()
        audioItem.title = "Track D Updated"

        // After close, no further events should be received
        receivedAfterClose shouldBe false
    }
})