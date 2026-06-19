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

package net.transgressoft.lirp.persistence.projection

import net.transgressoft.lirp.event.CrudEvent
import net.transgressoft.lirp.event.StandardCrudEvent
import net.transgressoft.lirp.persistence.MutableAudioItem
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe

/**
 * Tests for [SeedEventBuffer], verifying that events are buffered while the seed is in progress and
 * replayed in arrival order on completion, and that events arriving after completion are passed
 * through for direct application.
 */
@DisplayName("SeedEventBuffer")
internal class SeedEventBufferTest : StringSpec({

    "defers events while seeding and replays them in arrival order on completeSeed" {
        val buffer = SeedEventBuffer<Int, MutableAudioItem>()
        val create = StandardCrudEvent.Create(MutableAudioItem(1, "Track A", "Jazz"))
        val delete = StandardCrudEvent.Delete(MutableAudioItem(2, "Track B", "Rock"))

        buffer.deferIfSeeding(create) shouldBe true
        buffer.deferIfSeeding(delete) shouldBe true

        val replayed = mutableListOf<CrudEvent<Int, MutableAudioItem>>()
        buffer.completeSeed { replayed.add(it) }

        replayed shouldContainExactly listOf(create, delete)
    }

    "passes events through without deferring once the seed has completed" {
        val buffer = SeedEventBuffer<Int, MutableAudioItem>()
        val replayed = mutableListOf<CrudEvent<Int, MutableAudioItem>>()

        // No events were buffered during the (empty) seed.
        buffer.completeSeed { replayed.add(it) }
        replayed shouldBe emptyList()

        buffer.deferIfSeeding(StandardCrudEvent.Create(MutableAudioItem(1, "Track A", "Jazz"))) shouldBe false
    }
})