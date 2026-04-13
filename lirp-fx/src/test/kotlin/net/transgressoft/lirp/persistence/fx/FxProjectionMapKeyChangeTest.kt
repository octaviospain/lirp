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

import net.transgressoft.lirp.event.ReactiveScope
import net.transgressoft.lirp.persistence.AudioItem
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Tests for [FxProjectionMap] covering key-change scenarios. Closes coverage Gap 2 from the
 * CONCERNS.md audit: the [FxProjectionMap.removeFromAnyBucket] fallback path was untested.
 *
 * The fallback is triggered when an entity's projection key is mutated while the entity is
 * still in the source collection, causing [handleRemoved] to look up the new key (which has
 * no bucket) and fall through to a linear scan to find and remove the entity by reference.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FxProjectionMapKeyChangeTest : StringSpec({

    val testScope = CoroutineScope(UnconfinedTestDispatcher())

    beforeSpec {
        FxToolkitInit.ensureInitialized()
        ReactiveScope.flowScope = testScope
    }

    afterSpec {
        ReactiveScope.resetDefaultFlowScope()
    }

    "FxProjectionMap reflects new bucket after entity key changes and is removed then re-added" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection = FxProjectionMap({ source }, { it.albumName }, false)

        source.add(0, FxAudioItem(1, "Track A", "Jazz"))
        projection["Jazz"]!!.size shouldBe 1

        val item = source[0] as FxAudioItem
        source.removeAt(0)

        // Change the key after removal so re-add goes to the new bucket
        item.albumName = "Rock"
        source.add(0, item)

        projection.containsKey("Jazz") shouldBe false
        projection["Rock"]!!.size shouldBe 1
        projection["Rock"]!![0].id shouldBe 1
    }

    "FxProjectionMap removeFromAnyBucket handles key change during remove" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection = FxProjectionMap({ source }, { it.albumName }, false)

        source.add(0, FxAudioItem(1, "Track A", "Jazz"))
        projection["Jazz"]!!.size shouldBe 1

        // Mutate the key while the entity is still in the source — the projection
        // still stores the entity under "Jazz", but keyExtractor now returns "Rock"
        (source[0] as FxAudioItem).albumName = "Rock"

        // handleRemoved will call keyExtractor("Rock"), find no bucket, and fall back to removeFromAnyBucket
        source.removeAt(0)

        projection.containsKey("Jazz") shouldBe false
        projection.containsKey("Rock") shouldBe false
        projection.isEmpty() shouldBe true
    }

    "FxProjectionMap handles key change with multiple items in the same bucket" {
        val source = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val projection = FxProjectionMap({ source }, { it.albumName }, false)

        source.addAll(
            listOf(
                FxAudioItem(1, "Track A", "Jazz"),
                FxAudioItem(2, "Track B", "Jazz"),
                FxAudioItem(3, "Track C", "Jazz")
            )
        )
        projection["Jazz"]!!.size shouldBe 3

        // Mutate item 2's key while still in source
        (source[1] as FxAudioItem).albumName = "Rock"

        // Remove item 2 — removeFromAnyBucket must find and remove it from the "Jazz" bucket
        source.removeAt(1)

        projection["Jazz"]!!.size shouldBe 2
        projection.containsKey("Rock") shouldBe false
    }
})