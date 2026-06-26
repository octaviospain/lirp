/******************************************************************************
 *     Copyright (C) 2026  Octavio Calleya Garcia                             *
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

package net.transgressoft.lirp.entity

import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.persistence.AudioItem
import net.transgressoft.lirp.persistence.MutableAudioItem
import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * Tests for [ReactiveEntityBase.captureSnapshot], [ReactiveEntityBase.restoreSnapshot],
 * and [ReactiveEntityBase.withEventsDeferred] — the snapshot and event-deferral primitives
 * used by the transaction machinery.
 */
@DisplayName("ReactiveEntityBase snapshot and event-deferral primitives")
internal class TransactionEntitySnapshotTest : StringSpec({

    val reactive = reactiveScope()

    "captureSnapshot round-trips all reactive scalar properties" {
        val item = MutableAudioItem(1, "title-initial", "album-initial")
        val snapshot = item.captureSnapshot()

        snapshot["title"] shouldBe "title-initial"
        snapshot["albumName"] shouldBe "album-initial"
    }

    "captureSnapshot captures exactly the two scalar reactive properties (excludes aggregate delegates)" {
        val item = MutableAudioItem(2, "title", "album")
        val snapshot = item.captureSnapshot()
        snapshot.keys shouldBe setOf("title", "albumName")
    }

    "restoreSnapshot reverts reactive scalar properties to captured values" {
        val item = MutableAudioItem(3, "original-title", "original-album")
        val snapshot = item.captureSnapshot()

        item.title = "mutated-title"
        item.albumName = "mutated-album"

        item.restoreSnapshot(snapshot)

        item.title shouldBe "original-title"
        item.albumName shouldBe "original-album"
    }

    "restoreSnapshot emits no events to subscribers" {
        val item = MutableAudioItem(4, "before", "album")
        val snapshot = item.captureSnapshot()
        item.title = "after"

        val capturedEvents = mutableListOf<MutationEvent<Int, AudioItem>>()
        item.subscribe { capturedEvents.add(it) }
        reactive.advance()

        item.restoreSnapshot(snapshot)
        reactive.advance()

        capturedEvents.shouldBeEmpty()
    }

    "withEventsDeferred routes PropertyChanged into the provided buffer instead of publishing" {
        val item = MutableAudioItem(5, "initial", "album")
        val buffer = mutableListOf<MutationEvent<Int, AudioItem>>()

        val capturedLive = mutableListOf<MutationEvent<Int, AudioItem>>()
        item.subscribe { capturedLive.add(it) }
        reactive.advance()

        item.withEventsDeferred(buffer) {
            item.title = "deferred-value"
        }
        reactive.advance()

        // Live subscriber received nothing while deferral was active.
        capturedLive.shouldBeEmpty()
        // Buffer captured the deferred event.
        buffer.size shouldBe 1
    }

    "withEventsDeferred restores the previous buffer on exit — re-entrant safe" {
        val item = MutableAudioItem(6, "start", "album")
        val outerBuffer = mutableListOf<MutationEvent<Int, AudioItem>>()
        val innerBuffer = mutableListOf<MutationEvent<Int, AudioItem>>()

        item.withEventsDeferred(outerBuffer) {
            item.withEventsDeferred(innerBuffer) {
                item.title = "inner"
            }
            // outer buffer is active again after inner block exits.
            item.title = "outer"
        }

        innerBuffer.size shouldBe 1
        outerBuffer.size shouldBe 1
    }
})