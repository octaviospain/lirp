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
import net.transgressoft.lirp.persistence.json.BubbleUpAudioPlaylistJsonFileRepository
import net.transgressoft.lirp.persistence.json.JsonFileRepository
import net.transgressoft.lirp.persistence.json.MutableAudioPlaylistJsonFileRepository
import net.transgressoft.lirp.persistence.json.lirpSerializer
import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempfile
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Unit tests for [RegistryBase.registerRepository], the public API for delegation-based
 * repository registration into [LirpContext.default].
 *
 * Verifies registration succeeds, idempotent same-instance calls are allowed, different-instance
 * duplicates throw [IllegalStateException], non-RegistryBase instances throw [IllegalArgumentException],
 * close() deregisters, and re-registration after close succeeds.
 */
@DisplayName("RegistryBase.registerRepository()")
internal class RegisterRepositoryTest : StringSpec({

    val reactive = reactiveScope()

    afterEach {
        LirpContext.resetDefault()
    }

    "registers delegate RegistryBase in LirpContext.default keyed by entity class" {
        val delegate = VolatileRepository<Int, AudioItem>("AudioItems")

        RegistryBase.registerRepository(AudioItem::class.java, delegate)

        LirpContext.default.registryFor(AudioItem::class.java) shouldBe delegate
    }

    "registerRepository() called twice with the same instance is idempotent" {
        val delegate = VolatileRepository<Int, AudioItem>("AudioItems")

        RegistryBase.registerRepository(AudioItem::class.java, delegate)
        RegistryBase.registerRepository(AudioItem::class.java, delegate)

        LirpContext.default.registryFor(AudioItem::class.java) shouldBe delegate
    }

    "registerRepository() with a different instance for same entity class throws ISE" {
        val delegate1 = VolatileRepository<Int, AudioItem>("AudioItems1")
        val delegate2 = VolatileRepository<Int, AudioItem>("AudioItems2")

        RegistryBase.registerRepository(AudioItem::class.java, delegate1)

        shouldThrow<IllegalStateException> {
            RegistryBase.registerRepository(AudioItem::class.java, delegate2)
        }.message shouldBe "A repository for AudioItem is already registered. Only one @LirpRepository per entity type is allowed."
    }

    "registerRepository() with a non-RegistryBase Registry instance throws IAE" {
        val nonRegistryBase = mockk<Repository<Int, AudioItem>>()

        shouldThrow<IllegalArgumentException> {
            RegistryBase.registerRepository(AudioItem::class.java, nonRegistryBase)
        }.message shouldContain "Only RegistryBase instances can be registered"
    }

    "close() on delegate deregisters from LirpContext.default" {
        val delegate = VolatileRepository<Int, AudioItem>("AudioItems")

        RegistryBase.registerRepository(AudioItem::class.java, delegate)
        LirpContext.default.registryFor(AudioItem::class.java) shouldBe delegate

        delegate.close()

        LirpContext.default.registryFor(AudioItem::class.java) shouldBe null
    }

    "registerRepository() with a delegate from a non-default context throws IAE" {
        val customContext = LirpContext()
        val delegate = VolatileRepository<Int, AudioItem>(customContext, "AudioItems")

        try {
            shouldThrow<IllegalArgumentException> {
                RegistryBase.registerRepository(AudioItem::class.java, delegate)
            }.message shouldContain "registerRepository() only supports RegistryBase instances created in LirpContext.default"
        } finally {
            delegate.close()
        }
    }

    "registerRepository() succeeds after close() and re-registration" {
        val delegate1 = VolatileRepository<Int, AudioItem>("AudioItems")

        RegistryBase.registerRepository(AudioItem::class.java, delegate1)
        delegate1.close()

        LirpContext.default.registryFor(AudioItem::class.java) shouldBe null

        val delegate2 = VolatileRepository<Int, AudioItem>("AudioItems2")
        RegistryBase.registerRepository(AudioItem::class.java, delegate2)

        LirpContext.default.registryFor(AudioItem::class.java) shouldBe delegate2
    }

    "registerRepository() rebinds collection aggregate refs of entities loaded before registration" {
        // Reproduces the case where a JsonFileRepository (no @LirpRepository auto-registration)
        // loads entities whose aggregate refs target a class whose registry has not yet been
        // registered. Without rebinding on registerRepository(), the delegates remain unbound
        // forever and resolveAll() returns an empty set even after the registry is registered.
        //
        // The minimal failing scenario: a self-referential type loaded via a plain
        // JsonFileRepository, where registerRepository() is called manually after construction
        // (mirroring the music-commons FXPlaylistHierarchy pattern).
        val playlistFile = tempfile("playlist-late-register", ".json").also { it.deleteOnExit() }

        // Write self-referencing playlists via the auto-registered repo so the
        // serialized JSON encodes the parent->child relationship.
        val authoringRepo = MutableAudioPlaylistJsonFileRepository(LirpContext.default, playlistFile, serializationDelayMs = 5L)
        val child = authoringRepo.create(20, "Child")
        val parent = authoringRepo.create(10, "Parent")
        parent.playlists.add(child)
        reactive.advance()
        authoringRepo.close()
        LirpContext.resetDefault()

        // Load via PLAIN JsonFileRepository (no @LirpRepository annotation, so no
        // auto-registration of the playlist registry), then manually call registerRepository()
        // afterwards. This is exactly the pattern music-commons FXPlaylistHierarchy uses.
        @Suppress("UNCHECKED_CAST")
        val mapSerializer: KSerializer<Map<Int, MutableAudioPlaylist>> =
            MapSerializer(Int.serializer(), lirpSerializer(DefaultAudioPlaylist(0, ""))) as KSerializer<Map<Int, MutableAudioPlaylist>>
        val plainRepo = JsonFileRepository<Int, MutableAudioPlaylist>(playlistFile, mapSerializer)

        // At this point, plainRepo loaded both entities. bindEntityRefs ran during load but
        // skipped each entity's `playlists` aggregate ref because no registry was registered
        // for MutableAudioPlaylist when it was called.

        RegistryBase.registerRepository(MutableAudioPlaylist::class.java, plainRepo)

        // After registerRepository(), the previously-skipped aggregate refs must now resolve.
        val reloadedParent = plainRepo.findById(10).get() as DefaultAudioPlaylist
        reloadedParent.playlists.referenceIds shouldBe setOf(20)
        reloadedParent.playlists.resolveAll().map { it.id } shouldBe listOf(20)

        plainRepo.close()
    }

    "registerRepository() rebinds scalar aggregate refs and rewires bubble-up" {
        // Mirrors the collection-ref scenario for scalar refs declared with `bubbleUp = true`,
        // verifying both that resolve() succeeds AND that the bubble-up subscription fires.
        // Without re-running wireRefBubbleUp() inside registerRepository(), parent subscribers
        // would not receive AggregateMutationEvent from the late-registered child.
        val playlistFile = tempfile("playlist-scalar-late-register", ".json").also { it.deleteOnExit() }

        // Write an audio item + bubble-up playlist via auto-registered repos so the JSON
        // encodes the audioItemId scalar ref.
        val authoringItems = AudioItemVolatileRepository(LirpContext.default)
        val authoringPlaylists = BubbleUpAudioPlaylistJsonFileRepository(LirpContext.default, playlistFile, 5L)
        authoringItems.create(1, "Track Alpha")
        authoringPlaylists.create(10, 1)
        reactive.advance()
        authoringPlaylists.close()
        authoringItems.close()
        LirpContext.resetDefault()

        // Reload playlists WITHOUT registering the AudioItem registry first.
        // BubbleUpAudioPlaylistJsonFileRepository auto-registers BubbleUpAudioPlaylist, but AudioItem
        // remains absent — each loaded playlist's audioItem scalar ref delegate is left unbound, and
        // wireRefBubbleUp() ran with bubbleUpParent set but no resolvable child, so no
        // bubble-up subscription was created.
        val playlistRepo = BubbleUpAudioPlaylistJsonFileRepository(LirpContext.default, playlistFile, 5L)
        val loadedPlaylist = playlistRepo.findById(10).get()
        loadedPlaylist.audioItem.resolve().isPresent shouldBe false

        // Late-register AudioItem via registerRepository(). The rebinding pass must
        // walk all registries (including playlistRepo), rebind scalar refs whose target class is
        // now resolvable, AND re-wire bubble-up subscriptions for `bubbleUp = true` refs.
        val lateItems = VolatileRepository<Int, AudioItem>(LirpContext.default, "AudioItemsLate")
        val audioItem = MutableAudioItem(1, "Track Alpha").also { lateItems.add(it) }
        RegistryBase.registerRepository(AudioItem::class.java, lateItems)

        // resolve() now returns the audio item — confirms the scalar bind branch works.
        loadedPlaylist.audioItem.resolve().isPresent shouldBe true
        loadedPlaylist.audioItem.resolve().get().title shouldBe "Track Alpha"

        // Bubble-up must fire: a mutation on the audio item should reach the playlist's subscribers
        // as an AggregateMutationEvent.
        val bubbleUpReceived = AtomicBoolean(false)
        loadedPlaylist.subscribe { event ->
            if (event is AggregateMutationEvent<*, *>) bubbleUpReceived.set(true)
        }
        audioItem.title = "Track Alpha Updated"
        reactive.advance()

        bubbleUpReceived.get() shouldBe true

        playlistRepo.close()
        lateItems.close()
    }
})