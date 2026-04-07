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

import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Tests for collection reference declaration and basic properties.
 *
 * Verifies that [aggregateList] and [aggregateSet] delegates work in isolation — no repository
 * binding required. These tests exercise [AggregateListRefDelegate] and [AggregateSetRefDelegate]
 * properties: referenceIds, resolveAll on unbound delegates, and getValue return type.
 */
@DisplayName("AggregateCollectionRefDelegate")
internal class AggregateCollectionRefDeclarationTest : StringSpec({

    "AggregateListRefDelegate returns referenceIds as List preserving order and duplicates" {
        val playlist = ImmutableAudioPlaylist(id = 1, name = "My Playlist", initialAudioItemIds = listOf(3, 1, 2, 1))

        playlist.audioItems.referenceIds shouldContainExactly listOf(3, 1, 2, 1)
    }

    "AggregateSetRefDelegate returns referenceIds as Set with unique elements" {
        val group = ImmutablePlaylistGroup(id = 1, initialPlaylistIds = setOf(10, 20, 30))

        group.playlists.referenceIds shouldBe setOf(10, 20, 30)
    }

    "aggregateList getValue returns the delegate typed as AggregateCollectionRef" {
        val playlist = ImmutableAudioPlaylist(id = 1, name = "My Playlist", initialAudioItemIds = listOf(1, 2, 3))

        playlist.audioItems.shouldBeInstanceOf<AggregateCollectionRef<Int, AudioItem>>()
    }

    "aggregateSet getValue returns the delegate typed as AggregateCollectionRef" {
        val group = ImmutablePlaylistGroup(id = 1, initialPlaylistIds = setOf(1))

        group.playlists.shouldBeInstanceOf<AggregateCollectionRef<Int, ImmutableAudioPlaylist>>()
    }

    "Unbound AggregateListRefDelegate.resolveAll returns empty list" {
        val playlist = ImmutableAudioPlaylist(id = 1, name = "My Playlist", initialAudioItemIds = listOf(1, 2, 3))

        playlist.audioItems.resolveAll() shouldBe emptyList()
    }

    "Unbound AggregateSetRefDelegate.resolveAll returns empty set" {
        val group = ImmutablePlaylistGroup(id = 1, initialPlaylistIds = setOf(1, 2))

        group.playlists.resolveAll() shouldBe emptySet()
    }

    "AggregateListRefDelegate has no wireBubbleUp or cancelBubbleUp methods" {
        val delegate = AggregateListRefDelegate(listOf(1, 2, 3))

        val methods = delegate.javaClass.methods.map { it.name }
        methods.none { it in listOf("wireBubbleUp", "cancelBubbleUp") } shouldBe true
    }

    "AggregateSetRefDelegate has no wireBubbleUp or cancelBubbleUp methods" {
        val delegate = AggregateSetRefDelegate(setOf(1, 2))

        val methods = delegate.javaClass.methods.map { it.name }
        methods.none { it in listOf("wireBubbleUp", "cancelBubbleUp") } shouldBe true
    }

    "aggregateList factory returns AggregateCollectionRef" {
        val delegate = aggregateList(listOf(1, 2, 3))

        delegate.shouldBeInstanceOf<AggregateCollectionRef<Int, AudioItem>>()
        delegate.referenceIds shouldHaveSize 3
    }

    "aggregateSet factory returns AggregateCollectionRef" {
        val delegate = aggregateSet(setOf(1, 2))

        delegate.shouldBeInstanceOf<AggregateCollectionRef<Int, ImmutableAudioPlaylist>>()
        delegate.referenceIds shouldHaveSize 2
    }
})