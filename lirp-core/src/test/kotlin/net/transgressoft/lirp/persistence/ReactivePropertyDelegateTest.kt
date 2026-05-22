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
import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList

class ReactivePropertyDelegateTest : StringSpec({
    val reactive = reactiveScope()

    "[ReactivePropertyDelegate] writeBackingDirectly updates storedValue without emitting MutationEvent" {
        val entity = DelegateProbeEntity("probe-1", "initial")
        val received = CopyOnWriteArrayList<MutationEvent<String, DelegateProbeEntity>>()
        val subscription = entity.subscribe { received.add(it) }
        reactive.advance()

        writeReactivePropertyBackingField<String>(entity, "name", "silently-rewritten")
        reactive.advance()

        entity.name shouldBe "silently-rewritten"
        received.shouldBeEmpty()

        subscription.cancel()
    }

    "[ReactivePropertyDelegate] writeBackingDirectly does not mutate lastDateModified" {
        val entity = DelegateProbeEntity("probe-2", "initial")
        val timestampBefore = entity.lastDateModified

        writeReactivePropertyBackingField<String>(entity, "name", "silently-rewritten")

        entity.lastDateModified shouldBe timestampBefore
        entity.name shouldBe "silently-rewritten"
    }

    "[ReactivePropertyDelegate] regular setValue assignment still emits events and bumps lastDateModified" {
        val entity = DelegateProbeEntity("probe-3", "initial")
        val received = CopyOnWriteArrayList<MutationEvent<String, DelegateProbeEntity>>()
        val subscription = entity.subscribe { received.add(it) }
        reactive.advance()

        val timestampBefore = entity.lastDateModified
        // Ensure clock tick so timestamps can differ
        Thread.sleep(2)
        entity.name = "loudly-changed"
        reactive.advance()

        entity.name shouldBe "loudly-changed"
        received.size shouldBe 1
        received[0].newEntity.name shouldBe "loudly-changed"
        (entity.lastDateModified > timestampBefore) shouldBe true

        subscription.cancel()
    }
})

/**
 * Minimal reactive entity used only to exercise the top-level [ReactivePropertyDelegate]
 * silent-write and live-setter paths without depending on richer test fixtures.
 */
class DelegateProbeEntity(
    override val id: String,
    initialName: String
) : ReactiveEntityBase<String, DelegateProbeEntity>() {

    var name: String by reactiveProperty(initialName)

    override val uniqueId: String get() = "probe-$id"

    override fun clone(): DelegateProbeEntity = DelegateProbeEntity(id, name)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DelegateProbeEntity) return false
        return id == other.id && name == other.name
    }

    override fun hashCode(): Int = 31 * id.hashCode() + name.hashCode()

    override fun toString(): String = "DelegateProbeEntity(id=$id, name=$name)"
}