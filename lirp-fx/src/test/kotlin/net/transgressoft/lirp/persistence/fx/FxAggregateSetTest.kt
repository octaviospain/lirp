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
import javafx.collections.ObservableSet
import javafx.collections.SetChangeListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Tests for [FxAggregateSet] verifying JavaFX [SetChangeListener.Change] notifications,
 * per-element change semantics, dispatch thread behavior, and type compatibility with
 * [ObservableSet] and [AggregateCollectionRef].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FxAggregateSetTest : StringSpec({

    val testScope = CoroutineScope(UnconfinedTestDispatcher())

    beforeSpec {
        FxToolkitInit.ensureInitialized()
        ReactiveScope.flowScope = testScope
    }

    afterSpec {
        ReactiveScope.resetDefaultFlowScope()
    }

    "FxAggregateSet returns correct size after add" {
        val proxy = fxAggregateSet<Int, AudioItem>(dispatchToFxThread = false)
        proxy.add(MutableAudioItem(1, "Song A"))
        proxy.size shouldBe 1
    }

    "FxAggregateSet fires SetChangeListener per element on add" {
        val proxy = fxAggregateSet<Int, AudioItem>(dispatchToFxThread = false)
        val changes = mutableListOf<SetChangeListener.Change<out AudioItem>>()
        proxy.addListener(SetChangeListener(changes::add))

        val item = MutableAudioItem(1, "Song A")
        proxy.add(item)

        changes.size shouldBe 1
        changes[0].wasAdded() shouldBe true
        changes[0].elementAdded shouldBe item
    }

    "FxAggregateSet fires SetChangeListener per element on remove" {
        val item = MutableAudioItem(1, "Song A")
        val proxy = fxAggregateSet<Int, AudioItem>(dispatchToFxThread = false)
        proxy.add(item)

        val changes = mutableListOf<SetChangeListener.Change<out AudioItem>>()
        proxy.addListener(SetChangeListener(changes::add))

        proxy.remove(item)

        changes.size shouldBe 1
        changes[0].wasRemoved() shouldBe true
        changes[0].elementRemoved shouldBe item
    }

    "FxAggregateSet fires one Change per element on addAll" {
        val proxy = fxAggregateSet<Int, AudioItem>(dispatchToFxThread = false)
        val testItems = listOf(MutableAudioItem(1, "A"), MutableAudioItem(2, "B"), MutableAudioItem(3, "C"))
        val changes = mutableListOf<SetChangeListener.Change<out AudioItem>>()
        proxy.addListener(SetChangeListener(changes::add))

        proxy.addAll(testItems)

        changes.size shouldBe 3
        changes.all { it.wasAdded() } shouldBe true
    }

    "FxAggregateSet fires one Change per element on clear" {
        val proxy = fxAggregateSet<Int, AudioItem>(dispatchToFxThread = false)
        proxy.add(MutableAudioItem(1, "A"))
        proxy.add(MutableAudioItem(2, "B"))

        val changes = mutableListOf<SetChangeListener.Change<out AudioItem>>()
        proxy.addListener(SetChangeListener(changes::add))

        proxy.clear()

        changes.size shouldBe 2
        changes.all { it.wasRemoved() } shouldBe true
    }

    "FxAggregateSet fires listeners on flowScope when dispatchToFxThread=false" {
        val proxy = fxAggregateSet<Int, AudioItem>(dispatchToFxThread = false)
        var listenerFired = false

        proxy.addListener(SetChangeListener { listenerFired = true })

        proxy.add(MutableAudioItem(1, "A"))

        listenerFired shouldBe true
    }

    "FxAggregateSet fires listeners on FX thread when dispatchToFxThread=true" {
        val proxy = fxAggregateSet<Int, AudioItem>(dispatchToFxThread = true)
        val latch = CountDownLatch(1)
        var wasFxThread = false

        proxy.addListener(
            SetChangeListener {
                wasFxThread = Platform.isFxApplicationThread()
                latch.countDown()
            }
        )

        proxy.add(MutableAudioItem(1, "A"))

        latch.await(2, TimeUnit.SECONDS) shouldBe true
        wasFxThread shouldBe true
    }

    "FxAggregateSet implements ObservableSet" {
        val proxy = fxAggregateSet<Int, AudioItem>(dispatchToFxThread = false)
        proxy.shouldBeInstanceOf<ObservableSet<AudioItem>>()
    }

    "FxAggregateSet implements AggregateCollectionRef" {
        val proxy = fxAggregateSet<Int, AudioItem>(dispatchToFxThread = false)
        proxy.shouldBeInstanceOf<AggregateCollectionRef<Int, AudioItem>>()
    }

    "FxAggregateSet supports property delegation via getValue" {
        val proxy by fxAggregateSet<Int, AudioItem>(dispatchToFxThread = false)
        proxy.shouldBeInstanceOf<FxAggregateSet<Int, AudioItem>>()
    }

    "FxAggregateSet iterator remove fires Change" {
        val proxy = fxAggregateSet<Int, AudioItem>(dispatchToFxThread = false)
        val item1 = MutableAudioItem(1, "A")
        val item2 = MutableAudioItem(2, "B")
        proxy.addAll(listOf(item1, item2))

        val changes = mutableListOf<SetChangeListener.Change<out AudioItem>>()
        proxy.addListener(SetChangeListener(changes::add))

        val iter = proxy.iterator()
        iter.next()
        iter.remove()

        changes.size shouldBe 1
        changes[0].wasRemoved() shouldBe true
        proxy.size shouldBe 1
    }

    "FxAggregateSet iterator throws on remove before next" {
        val proxy = fxAggregateSet<Int, AudioItem>(dispatchToFxThread = false)
        proxy.add(MutableAudioItem(1, "A"))

        val iter = proxy.iterator()
        val exception = runCatching { iter.remove() }.exceptionOrNull()
        exception.shouldBeInstanceOf<IllegalStateException>()
    }

    "FxAggregateSet removeAll fires one Change per removed element" {
        val proxy = fxAggregateSet<Int, AudioItem>(dispatchToFxThread = false)
        val item1 = MutableAudioItem(1, "A")
        val item2 = MutableAudioItem(2, "B")
        val item3 = MutableAudioItem(3, "C")
        proxy.addAll(listOf(item1, item2, item3))

        val changes = mutableListOf<SetChangeListener.Change<out AudioItem>>()
        proxy.addListener(SetChangeListener(changes::add))

        proxy.removeAll(listOf(item1, item3))

        changes.size shouldBe 2
        changes.all { it.wasRemoved() } shouldBe true
        proxy.size shouldBe 1
    }

    "FxAggregateSet retainAll removes non-matching elements" {
        val proxy = fxAggregateSet<Int, AudioItem>(dispatchToFxThread = false)
        val item1 = MutableAudioItem(1, "A")
        val item2 = MutableAudioItem(2, "B")
        val item3 = MutableAudioItem(3, "C")
        proxy.addAll(listOf(item1, item2, item3))

        val changes = mutableListOf<SetChangeListener.Change<out AudioItem>>()
        proxy.addListener(SetChangeListener(changes::add))

        proxy.retainAll(listOf(item2))

        proxy.size shouldBe 1
        proxy.contains(item2) shouldBe true
        changes.size shouldBe 2
        changes.all { it.wasRemoved() } shouldBe true
    }

    "FxAggregateSet contains returns correct result" {
        val proxy = fxAggregateSet<Int, AudioItem>(dispatchToFxThread = false)
        val item = MutableAudioItem(1, "A")

        proxy.contains(item) shouldBe false
        proxy.add(item)
        proxy.contains(item) shouldBe true
    }

    "FxAggregateSet invalidation listeners fire on changes" {
        val proxy = fxAggregateSet<Int, AudioItem>(dispatchToFxThread = false)
        var invalidated = false

        proxy.addListener(javafx.beans.InvalidationListener { invalidated = true })

        proxy.add(MutableAudioItem(1, "A"))

        invalidated shouldBe true
    }

    "FxAggregateSet removeListener removes set change listener" {
        val proxy = fxAggregateSet<Int, AudioItem>(dispatchToFxThread = false)
        val changes = mutableListOf<SetChangeListener.Change<out AudioItem>>()
        val listener = SetChangeListener(changes::add)
        proxy.addListener(listener)
        proxy.removeListener(listener)

        proxy.add(MutableAudioItem(1, "A"))

        changes.size shouldBe 0
    }

    "FxAggregateSet removeListener removes invalidation listener" {
        val proxy = fxAggregateSet<Int, AudioItem>(dispatchToFxThread = false)
        var count = 0
        val listener = javafx.beans.InvalidationListener { count++ }
        proxy.addListener(listener)
        proxy.removeListener(listener)

        proxy.add(MutableAudioItem(1, "A"))

        count shouldBe 0
    }

    "FxAggregateSet add duplicate returns false" {
        val proxy = fxAggregateSet<Int, AudioItem>(dispatchToFxThread = false)
        val item = MutableAudioItem(1, "A")

        proxy.add(item) shouldBe true
        proxy.add(item) shouldBe false
        proxy.size shouldBe 1
    }

    "FxAggregateSet remove non-existing returns false" {
        val proxy = fxAggregateSet<Int, AudioItem>(dispatchToFxThread = false)
        proxy.remove(MutableAudioItem(1, "A")) shouldBe false
    }

    "FxAggregateSet clear on empty set is no-op" {
        val proxy = fxAggregateSet<Int, AudioItem>(dispatchToFxThread = false)
        val changes = mutableListOf<SetChangeListener.Change<out AudioItem>>()
        proxy.addListener(SetChangeListener(changes::add))

        proxy.clear()

        changes.size shouldBe 0
    }
})