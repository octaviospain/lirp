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

import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Integration tests for the delegation-based repository registration pattern.
 *
 * Verifies that a repository wrapper using Kotlin's `by` delegation registers its underlying
 * [VolatileRepository] delegate into [LirpContext.default] via [RegistryBase.registerRepository]
 * called from an `init` block. Tests cover registration on construction, delegate identity,
 * routing of repository operations through the delegate, deregistration on close, independent
 * multi-type registration, and duplicate rejection.
 */
@DisplayName("DelegationRegistrationIntegration")
internal class DelegationRegistrationIntegrationTest : StringSpec({

    afterEach {
        LirpContext.resetDefault()
    }

    "constructing DelegatingAudioItemRepo registers the delegate VolatileRepository in LirpContext.default" {
        val delegate = VolatileRepository<Int, AudioItem>("DelegatingAudioItems")
        DelegatingAudioItemRepo(delegate)

        val registered = LirpContext.default.registryFor(AudioItem::class.java)

        registered.shouldNotBeNull()
        registered shouldBe delegate
        registered.shouldBeInstanceOf<VolatileRepository<*, *>>()
    }

    "LirpContext.default.registries() contains exactly one entry keyed by AudioItem after DelegatingAudioItemRepo construction" {
        val delegate = VolatileRepository<Int, AudioItem>("DelegatingAudioItems")
        DelegatingAudioItemRepo(delegate)

        val registries = LirpContext.default.registries()

        registries shouldHaveSize 1
        registries shouldContainKey AudioItem::class.java
        registries[AudioItem::class.java] shouldBe delegate
    }

    "DelegatingAudioItemRepo routes add, contains, and size to the delegate" {
        val delegate = VolatileRepository<Int, AudioItem>("DelegatingAudioItems")
        val wrapper = DelegatingAudioItemRepo(delegate)

        val audioItem = wrapper.create(1, "Track Alpha")

        wrapper.contains(1) shouldBe true
        wrapper.size() shouldBe 1
        delegate.contains(1) shouldBe true
        delegate.size() shouldBe 1
        delegate.findById(1).isPresent shouldBe true
        delegate.findById(1).get() shouldBe audioItem
    }

    "closing the wrapper deregisters from LirpContext.default and closes the delegate" {
        val delegate = VolatileRepository<Int, AudioItem>("DelegatingAudioItems")
        val wrapper = DelegatingAudioItemRepo(delegate)
        wrapper.create(1, "Track Alpha")

        LirpContext.default.registryFor(AudioItem::class.java).shouldNotBeNull()

        wrapper.close()

        LirpContext.default.registryFor(AudioItem::class.java).shouldBeNull()
        delegate.isClosed shouldBe true
    }

    "two delegation wrappers for different entity types register independently" {
        val audioItemDelegate = VolatileRepository<Int, AudioItem>("DelegatingAudioItems")
        val playlistDelegate = VolatileRepository<Int, MutableAudioPlaylist>("DelegatingPlaylists")
        DelegatingAudioItemRepo(audioItemDelegate)
        DelegatingPlaylistRepo(playlistDelegate)

        val registries = LirpContext.default.registries()

        registries shouldHaveSize 2
        registries[AudioItem::class.java] shouldBe audioItemDelegate
        registries[MutableAudioPlaylist::class.java] shouldBe playlistDelegate
    }

    "constructing a second DelegatingAudioItemRepo for the same entity class throws ISE" {
        val delegate1 = VolatileRepository<Int, AudioItem>("DelegatingAudioItems1")
        val delegate2 = VolatileRepository<Int, AudioItem>("DelegatingAudioItems2")
        DelegatingAudioItemRepo(delegate1)

        shouldThrow<IllegalStateException> {
            DelegatingAudioItemRepo(delegate2)
        }.message shouldBe "A repository for AudioItem is already registered. Only one @LirpRepository per entity type is allowed."
    }

    "DelegatingAudioItemRepo.close() is safe to call when already deregistered" {
        val delegate = VolatileRepository<Int, AudioItem>("DelegatingAudioItems")
        val wrapper = DelegatingAudioItemRepo(delegate)

        RegistryBase.deregisterRepository(AudioItem::class.java)

        shouldNotThrowAny {
            wrapper.close()
        }
        delegate.isClosed shouldBe true
    }

    "DelegatingPlaylistRepo.close() deregisters MutableAudioPlaylist independently of AudioItem" {
        val audioItemDelegate = VolatileRepository<Int, AudioItem>("DelegatingAudioItems")
        val playlistDelegate = VolatileRepository<Int, MutableAudioPlaylist>("DelegatingPlaylists")
        DelegatingAudioItemRepo(audioItemDelegate)
        val playlistWrapper = DelegatingPlaylistRepo(playlistDelegate)

        playlistWrapper.close()

        LirpContext.default.registryFor(MutableAudioPlaylist::class.java).shouldBeNull()
        LirpContext.default.registryFor(AudioItem::class.java).shouldNotBeNull()
    }
})