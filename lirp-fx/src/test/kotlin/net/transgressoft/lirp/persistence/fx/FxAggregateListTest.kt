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
import net.transgressoft.lirp.persistence.AggregateCollectionRef
import net.transgressoft.lirp.persistence.AudioItem
import net.transgressoft.lirp.persistence.MutableAudioItem
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import javafx.application.Platform
import javafx.collections.ListChangeListener
import javafx.collections.ObservableList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Tests for [FxAggregateList] verifying JavaFX [ListChangeListener.Change] notifications,
 * dispatch thread behavior, and type compatibility with [ObservableList] and [AggregateCollectionRef].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FxAggregateListTest : StringSpec({

    val testScope = CoroutineScope(UnconfinedTestDispatcher())

    beforeSpec {
        FxToolkitInit.ensureInitialized()
        ReactiveScope.flowScope = testScope
    }

    afterSpec {
        ReactiveScope.resetDefaultFlowScope()
    }

    "FxAggregateList returns correct size after add" {
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        proxy.add(0, MutableAudioItem(1, "Song A"))
        proxy.size shouldBe 1
    }

    "FxAggregateList fires ListChangeListener on single add" {
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val changes = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        proxy.addListener(ListChangeListener(changes::add))

        proxy.add(0, MutableAudioItem(1, "Song A"))

        changes.size shouldBe 1
        val change = changes[0]
        change.next() shouldBe true
        change.wasAdded() shouldBe true
        change.from shouldBe 0
        change.to shouldBe 1
    }

    "FxAggregateList fires ListChangeListener on single remove" {
        val item = MutableAudioItem(1, "Song A")
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        proxy.add(0, item)

        val changes = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        proxy.addListener(ListChangeListener(changes::add))

        proxy.removeAt(0)

        changes.size shouldBe 1
        val change = changes[0]
        change.next() shouldBe true
        change.wasRemoved() shouldBe true
        change.removed.size shouldBe 1
    }

    "FxAggregateList fires ListChangeListener on set" {
        val item1 = MutableAudioItem(1, "Song A")
        val item2 = MutableAudioItem(2, "Song B")
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        proxy.add(0, item1)

        val changes = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        proxy.addListener(ListChangeListener(changes::add))

        proxy[0] = item2

        changes.size shouldBe 1
        val change = changes[0]
        change.next() shouldBe true
        change.wasReplaced() shouldBe true
    }

    "FxAggregateList fires single Change on addAll" {
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val testItems = listOf(MutableAudioItem(1, "A"), MutableAudioItem(2, "B"), MutableAudioItem(3, "C"))
        val changes = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        proxy.addListener(ListChangeListener(changes::add))

        proxy.addAll(testItems)

        changes.size shouldBe 1
        val change = changes[0]
        change.next() shouldBe true
        change.wasAdded() shouldBe true
        change.from shouldBe 0
        change.to shouldBe 3
    }

    "FxAggregateList fires Change on clear" {
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        proxy.add(0, MutableAudioItem(1, "A"))
        proxy.add(1, MutableAudioItem(2, "B"))

        val changes = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        proxy.addListener(ListChangeListener(changes::add))

        proxy.clear()

        changes.size shouldBe 1
        val change = changes[0]
        change.next() shouldBe true
        change.wasRemoved() shouldBe true
        change.removed.size shouldBe 2
    }

    "FxAggregateList fires listeners on flowScope when dispatchToFxThread=false" {
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        var listenerFired = false

        proxy.addListener(ListChangeListener { listenerFired = true })

        proxy.add(0, MutableAudioItem(1, "A"))

        listenerFired shouldBe true
    }

    "FxAggregateList fires listeners on FX thread when dispatchToFxThread=true" {
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = true)
        val latch = CountDownLatch(1)
        var wasFxThread = false

        proxy.addListener(
            ListChangeListener {
                wasFxThread = Platform.isFxApplicationThread()
                latch.countDown()
            }
        )

        proxy.add(0, MutableAudioItem(1, "A"))

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        wasFxThread shouldBe true
    }

    "FxAggregateList implements ObservableList" {
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        proxy.shouldBeInstanceOf<ObservableList<AudioItem>>()
    }

    "FxAggregateList implements AggregateCollectionRef" {
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        proxy.shouldBeInstanceOf<AggregateCollectionRef<Int, AudioItem>>()
    }

    "FxAggregateList supports property delegation via getValue" {
        val proxy by fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        proxy.shouldBeInstanceOf<FxAggregateList<Int, AudioItem>>()
    }

    "FxAggregateList fires MultiRemoveChange with ascending indices on removeAll" {
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val item1 = MutableAudioItem(1, "A")
        val item2 = MutableAudioItem(2, "B")
        val item3 = MutableAudioItem(3, "C")
        proxy.addAll(listOf(item1, item2, item3))

        val changes = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        proxy.addListener(ListChangeListener(changes::add))

        proxy.removeAll(listOf(item1, item3))

        changes.size shouldBe 1
        val change = changes[0]
        change.next() shouldBe true
        change.wasRemoved() shouldBe true
        change.from shouldBe 0
        change.removed shouldBe listOf(item1)
        change.next() shouldBe true
        change.wasRemoved() shouldBe true
        change.from shouldBe 1
        change.removed shouldBe listOf(item3)
        change.next() shouldBe false
    }

    "FxAggregateList MultiRemoveChange supports reset" {
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val items = listOf(MutableAudioItem(1, "A"), MutableAudioItem(2, "B"), MutableAudioItem(3, "C"))
        proxy.addAll(items)

        val changes = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        proxy.addListener(ListChangeListener(changes::add))

        proxy.removeAll(listOf(items[0], items[2]))

        val change = changes[0]
        change.next() shouldBe true
        change.next() shouldBe true
        change.next() shouldBe false
        change.reset()
        change.next() shouldBe true
        change.wasRemoved() shouldBe true
    }

    "FxAggregateList fires ReplaceAllChange on setAll" {
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val item1 = MutableAudioItem(1, "A")
        val item2 = MutableAudioItem(2, "B")
        proxy.add(0, item1)

        val changes = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        proxy.addListener(ListChangeListener(changes::add))

        proxy.setAll(item2)

        changes.size shouldBe 1
        val change = changes[0]
        change.next() shouldBe true
        change.wasReplaced() shouldBe true
        change.removed shouldBe listOf(item1)
        change.from shouldBe 0
        change.to shouldBe 1
        change.next() shouldBe false
    }

    "FxAggregateList setAll with collection replaces all elements" {
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        proxy.addAll(listOf(MutableAudioItem(1, "A"), MutableAudioItem(2, "B")))

        val newItems = listOf(MutableAudioItem(3, "C"), MutableAudioItem(4, "D"), MutableAudioItem(5, "E"))
        val changes = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        proxy.addListener(ListChangeListener(changes::add))

        proxy.setAll(newItems)

        proxy.size shouldBe 3
        proxy[0].id shouldBe 3
        changes.size shouldBe 1
    }

    "FxAggregateList setAll on empty with empty returns false" {
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val changes = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        proxy.addListener(ListChangeListener(changes::add))

        proxy.setAll(emptyList()) shouldBe false

        changes.size shouldBe 0
    }

    "FxAggregateList retainAll removes non-matching elements" {
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val item1 = MutableAudioItem(1, "A")
        val item2 = MutableAudioItem(2, "B")
        val item3 = MutableAudioItem(3, "C")
        proxy.addAll(listOf(item1, item2, item3))

        val changes = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        proxy.addListener(ListChangeListener(changes::add))

        proxy.retainAll(listOf(item2))

        proxy.size shouldBe 1
        proxy[0] shouldBe item2
        changes.size shouldBe 1
    }

    "FxAggregateList remove(from, to) fires Change for range" {
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val items = listOf(MutableAudioItem(1, "A"), MutableAudioItem(2, "B"), MutableAudioItem(3, "C"))
        proxy.addAll(items)

        val changes = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        proxy.addListener(ListChangeListener(changes::add))

        proxy.remove(0, 2)

        proxy.size shouldBe 1
        changes.size shouldBe 1
        val change = changes[0]
        change.next() shouldBe true
        change.wasRemoved() shouldBe true
        change.removedSize shouldBe 2
    }

    "FxAggregateList removeAt fires Change with correct index" {
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val item1 = MutableAudioItem(1, "A")
        val item2 = MutableAudioItem(2, "B")
        proxy.addAll(listOf(item1, item2))

        val changes = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        proxy.addListener(ListChangeListener(changes::add))

        val removed = proxy.removeAt(0)

        removed shouldBe item1
        proxy.size shouldBe 1
        changes.size shouldBe 1
        val change = changes[0]
        change.next() shouldBe true
        change.wasRemoved() shouldBe true
        change.removed shouldBe listOf(item1)
    }

    "FxAggregateList addAll at index inserts at correct position" {
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        proxy.add(0, MutableAudioItem(1, "A"))
        proxy.add(1, MutableAudioItem(4, "D"))

        val changes = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        proxy.addListener(ListChangeListener(changes::add))

        val newItems = listOf(MutableAudioItem(2, "B"), MutableAudioItem(3, "C"))
        proxy.addAll(1, newItems)

        proxy.size shouldBe 4
        proxy[1].id shouldBe 2
        proxy[2].id shouldBe 3
        changes.size shouldBe 1
        val change = changes[0]
        change.next() shouldBe true
        change.wasAdded() shouldBe true
        change.from shouldBe 1
        change.to shouldBe 3
    }

    "FxAggregateList AddChange supports reset and getPermutation" {
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val changes = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        proxy.addListener(ListChangeListener(changes::add))

        proxy.add(0, MutableAudioItem(1, "A"))

        val change = changes[0]
        change.next() shouldBe true
        change.wasAdded() shouldBe true
        change.removed shouldBe emptyList()
        change.next() shouldBe false
        change.reset()
        change.next() shouldBe true
    }

    "FxAggregateList SetChange supports reset and getPermutation" {
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        proxy.add(0, MutableAudioItem(1, "A"))

        val changes = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        proxy.addListener(ListChangeListener(changes::add))

        proxy[0] = MutableAudioItem(2, "B")

        val change = changes[0]
        change.next() shouldBe true
        change.wasReplaced() shouldBe true
        change.from shouldBe 0
        change.to shouldBe 1
        change.removed shouldBe listOf(MutableAudioItem(1, "A"))
        change.next() shouldBe false
        change.reset()
        change.next() shouldBe true
    }

    "FxAggregateList invalidation listeners fire on changes" {
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        var invalidated = false

        proxy.addListener(javafx.beans.InvalidationListener { invalidated = true })

        proxy.add(0, MutableAudioItem(1, "A"))

        invalidated shouldBe true
    }

    "FxAggregateList removeListener removes listener" {
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val changes = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        val listener = ListChangeListener(changes::add)
        proxy.addListener(listener)
        proxy.removeListener(listener)

        proxy.add(0, MutableAudioItem(1, "A"))

        changes.size shouldBe 0
    }

    "FxAggregateList removeListener removes invalidation listener" {
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        var count = 0
        val listener = javafx.beans.InvalidationListener { count++ }
        proxy.addListener(listener)
        proxy.removeListener(listener)

        proxy.add(0, MutableAudioItem(1, "A"))

        count shouldBe 0
    }

    "FxAggregateList clear on empty list is no-op" {
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val changes = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        proxy.addListener(ListChangeListener(changes::add))

        proxy.clear()

        changes.size shouldBe 0
    }

    "FxAggregateList addAll with empty collection returns false" {
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        proxy.addAll(emptyList()) shouldBe false
        proxy.addAll(0, emptyList()) shouldBe false
    }

    "FxAggregateList removeAll with empty collection returns false" {
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        proxy.removeAll(emptyList()) shouldBe false
    }

    "FxAggregateList retainAll with all elements is no-op" {
        val proxy = fxAggregateList<Int, AudioItem>(dispatchToFxThread = false)
        val item = MutableAudioItem(1, "A")
        proxy.add(0, item)

        val changes = mutableListOf<ListChangeListener.Change<out AudioItem>>()
        proxy.addListener(ListChangeListener(changes::add))

        proxy.retainAll(listOf(item)) shouldBe false

        changes.size shouldBe 0
    }
})