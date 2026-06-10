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
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * Tests for aggregate reference declaration compiles and delegate returns correct values.
 *
 * These tests verify that [aggregate] delegates work in isolation — no repository binding required.
 */
@DisplayName("AggregateRefDelegate")
internal class AggregateRefDeclarationTest : FunSpec({

    test("returns non-null ReactiveEntityReference when declared via aggregate delegate") {
        val playlist = BubbleUpAudioPlaylist(id = 1, audioItemId = 42)

        playlist.audioItem.shouldNotBeNull()
    }

    test("delegate referenceId returns the correct ID value from the entity's ID field") {
        val playlist = BubbleUpAudioPlaylist(id = 1, audioItemId = 42)

        playlist.audioItem.referenceId shouldBe 42
    }

    test("delegate referenceId reflects updated audioItemId after field changes directly") {
        val playlist = BubbleUpAudioPlaylist(id = 1, audioItemId = 10)

        playlist.audioItemId = 99
        playlist.audioItem.referenceId shouldBe 99
    }

    test("delegate is an instance of ReactiveEntityReference") {
        val playlist = BubbleUpAudioPlaylist(id = 2, audioItemId = 5)

        playlist.audioItem.shouldBeInstanceOf<ReactiveEntityReference<Int, AudioItem>>()
    }
})