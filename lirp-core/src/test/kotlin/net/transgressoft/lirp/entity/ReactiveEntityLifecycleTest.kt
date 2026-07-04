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

package net.transgressoft.lirp.entity

import net.transgressoft.lirp.event.MutationEvent
import net.transgressoft.lirp.event.MutationEvent.Type.BATCH_CHANGED
import net.transgressoft.lirp.event.MutationEvent.Type.PROPERTY_CHANGED
import net.transgressoft.lirp.event.PropertyChanged
import net.transgressoft.lirp.persistence.AudioItem
import net.transgressoft.lirp.persistence.MutableAudioItem
import net.transgressoft.lirp.testing.reactiveScope
import net.transgressoft.lirp.testing.recordAsync
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

/**
 * Tests for [ReactiveEntityBase] lifecycle states: Created, Active, Dormant, and Closed.
 */
class ReactiveEntityLifecycleTest : StringSpec({
    val reactive = reactiveScope()

    "ReactiveEntity isClosed returns false for new entity" {
        val entity = LazyTestEntity("lifecycle-1")

        entity.isClosed shouldBe false
    }

    "ReactiveEntity close() marks entity as permanently closed" {
        val entity = LazyTestEntity("lifecycle-2")

        entity.close()

        entity.isClosed shouldBe true
    }

    "ReactiveEntity close() is idempotent" {
        val entity = LazyTestEntity("lifecycle-3")

        entity.close()
        entity.close()

        entity.isClosed shouldBe true
    }

    "ReactiveEntity close() closes its publisher when publisher is initialized" {
        val entity = LazyTestEntity("lifecycle-4")

        val subscription = entity.subscribeAsync { }
        entity.close()

        entity.isClosed shouldBe true
        shouldThrow<IllegalStateException> {
            entity.subscribeAsync { }
        }

        subscription.cancel()
    }

    "Closed ReactiveEntity throws IllegalStateException on property mutation" {
        val entity = LazyTestEntity("lifecycle-5")
        entity.close()

        val exception =
            shouldThrow<IllegalStateException> {
                entity.value = "x"
            }
        exception.message shouldContain "LazyTestEntity"
    }

    "Closed ReactiveEntity throws IllegalStateException on subscribe with lambda" {
        val entity = LazyTestEntity("lifecycle-6")
        entity.close()

        val exception =
            shouldThrow<IllegalStateException> {
                entity.subscribeAsync { }
            }
        exception.message shouldContain "LazyTestEntity"
    }

    "Closed ReactiveEntity throws IllegalStateException on subscribe with Flow.Subscriber" {
        val entity = LazyTestEntity("lifecycle-7")
        entity.close()

        shouldThrow<IllegalStateException> {
            entity.subscribe(
                object : Flow.Subscriber<MutationEvent<String, LazyTestEntity>> {
                    override fun onSubscribe(subscription: Flow.Subscription?) {}

                    override fun onNext(item: MutationEvent<String, LazyTestEntity>?) {}

                    override fun onError(throwable: Throwable?) {}

                    override fun onComplete() {}
                }
            )
        }
    }

    "Closed ReactiveEntity throws IllegalStateException on emitAsync" {
        val entity = LazyTestEntity("lifecycle-8")
        // Subscribe first to initialize the publisher
        val subscription = entity.subscribeAsync { }
        entity.close()

        shouldThrow<IllegalStateException> {
            entity.emitAsync(PropertyChanged(entity = entity, property = LazyTestEntity::value, oldValue = "initial", newValue = entity.value))
        }

        subscription.cancel()
    }

    "ReactiveEntity publisher shuts down when all subscribers cancel (dormant state)" {
        val creationCounter = AtomicInteger(0)
        val entity = LazyTestEntity("lifecycle-9", creationCounter)

        // First subscription creates the publisher (counter = 1)
        val subscription = entity.subscribeAsync { }
        creationCounter.get() shouldBe 1

        subscription.cancel()
        reactive.advance()

        // Entity is dormant: next subscription must create a fresh publisher (counter = 2)
        val subscription2 = entity.subscribeAsync { }
        creationCounter.get() shouldBe 2

        subscription2.cancel()
    }

    "ReactiveEntity enters dormant after multiple subscribers all cancel" {
        val creationCounter = AtomicInteger(0)
        val entity = LazyTestEntity("lifecycle-10", creationCounter)

        val subscriptions = List(3) { entity.subscribeAsync { } }
        creationCounter.get() shouldBe 1

        subscriptions.forEach { it.cancel() }
        reactive.advance()

        // Entity is dormant: next subscription recreates the publisher
        val subscription = entity.subscribeAsync { }
        creationCounter.get() shouldBe 2

        subscription.cancel()
    }

    "ReactiveEntity reactivates from dormant on new subscription" {
        val creationCounter = AtomicInteger(0)
        val entity = LazyTestEntity("lifecycle-11", creationCounter)

        // Go dormant
        val sub1 = entity.subscribeAsync { }
        sub1.cancel()
        reactive.advance()

        // Reactivate: subscribe again — must not throw
        val recorder = entity.recordAsync()

        entity.value = "reactivated"
        reactive.advance()

        recorder.count shouldBe 1
        (recorder.last as PropertyChanged<String, LazyTestEntity, String>).newValue shouldBe "reactivated"

        entity.close()
    }

    "ReactiveEntity supports multiple dormant-active cycles" {
        val creationCounter = AtomicInteger(0)
        val entity = LazyTestEntity("lifecycle-12", creationCounter)

        // Cycle 1: subscribe -> cancel -> dormant
        val sub1 = entity.subscribeAsync { }
        creationCounter.get() shouldBe 1
        sub1.cancel()
        reactive.advance()

        // Cycle 2: subscribe -> cancel -> dormant
        val sub2 = entity.subscribeAsync { }
        creationCounter.get() shouldBe 2
        sub2.cancel()
        reactive.advance()

        // Cycle 3: subscribe (active), receive events
        val recorder = entity.recordAsync()
        creationCounter.get() shouldBe 3

        entity.value = "after-cycles"
        reactive.advance()

        recorder.count shouldBe 1

        entity.close()
    }

    "ReactiveEntity lifecycle: Created -> Active -> Dormant -> Active -> Closed" {
        val creationCounter = AtomicInteger(0)
        val entity = LazyTestEntity("lifecycle-13", creationCounter)

        // Created: isClosed=false, no publisher
        entity.isClosed shouldBe false
        creationCounter.get() shouldBe 0

        // Active: publisher created on first subscribe
        val sub1 = entity.subscribeAsync { }
        creationCounter.get() shouldBe 1

        // Dormant: all subscribers cancelled
        sub1.cancel()
        reactive.advance()

        // Active again: new subscription reactivates
        val sub2 = entity.subscribeAsync { }
        creationCounter.get() shouldBe 2

        // Closed: permanent terminal state
        sub2.cancel()
        entity.close()
        entity.isClosed shouldBe true

        shouldThrow<IllegalStateException> {
            entity.subscribeAsync { }
        }
    }

    "ReactiveEntityBase emitAsync throws IllegalStateException when entity is closed" {
        val audioItem = MutableAudioItem(1, "Track Alpha")
        val sub = audioItem.subscribeAsync { }
        audioItem.close()

        shouldThrow<IllegalStateException> {
            audioItem.emitAsync(PropertyChanged(entity = audioItem, property = AudioItem::title, oldValue = audioItem.title, newValue = audioItem.title))
        }.message shouldContain "MutableAudioItem"

        sub.cancel()
    }

    "ReactiveEntityBase subscribeAsync with vararg eventTypes excludes non-matching event types" {
        val audioItem = MutableAudioItem(1, "Track Alpha")
        val received = mutableListOf<MutationEvent<Int, AudioItem>>()

        // A single-property assignment emits PROPERTY_CHANGED; a BATCH_CHANGED-only filter must exclude it.
        val subscription = audioItem.subscribeAsync(BATCH_CHANGED, action = Consumer { event -> received.add(event) })
        audioItem.title = "Track Beta"
        reactive.advance()

        received.size shouldBe 0
        subscription.cancel()
        audioItem.close()
    }

    "ReactiveEntityBase subscribeAsync with matching event type delivers events" {
        val audioItem = MutableAudioItem(1, "Track Alpha")
        val received = mutableListOf<MutationEvent<Int, AudioItem>>()

        // A property assignment emits PROPERTY_CHANGED, so a PROPERTY_CHANGED filter receives it.
        val subscription = audioItem.subscribeAsync(PROPERTY_CHANGED) { event -> received.add(event) }

        audioItem.title = "Track Beta"
        reactive.advance()

        received.size shouldBe 1
        subscription.cancel()
        audioItem.close()
    }

    "disableEvents suppresses mutation events from reactiveProperty setters" {
        val audioItem = MutableAudioItem(1, "Track Alpha")
        val recorder = audioItem.recordAsync()

        audioItem.suppressEvents()
        audioItem.title = "Track Beta"
        reactive.advance()

        recorder.count shouldBe 0
        audioItem.title shouldBe "Track Beta"

        audioItem.restoreEvents()
        audioItem.title = "Track Charlie"
        reactive.advance()

        recorder.count shouldBe 1
        (recorder.last as PropertyChanged<Int, AudioItem, String>).newValue shouldBe "Track Charlie"

        audioItem.close()
    }

    "withEventsDisabled suppresses events and restores emission afterward" {
        val audioItem = MutableAudioItem(1, "Track Alpha")
        val recorder = audioItem.recordAsync()

        audioItem.silently {
            audioItem.title = "Silent Track"
        }
        reactive.advance()
        recorder.count shouldBe 0
        audioItem.title shouldBe "Silent Track"

        audioItem.title = "Loud Track"
        reactive.advance()
        recorder.count shouldBe 1

        audioItem.close()
    }

    "withEventsDisabled restores state even if action throws" {
        val audioItem = MutableAudioItem(1, "Track Alpha")
        val recorder = audioItem.recordAsync()

        shouldThrow<RuntimeException> {
            audioItem.silently {
                audioItem.title = "BeforeError"
                throw RuntimeException("test error")
            }
        }

        audioItem.title = "AfterError"
        reactive.advance()
        recorder.count shouldBe 1

        audioItem.close()
    }

    "disableEvents suppresses mutateAndPublish block emission" {
        val audioItem = MutableAudioItem(1, "Track Alpha")
        val recorder = audioItem.recordAsync()

        audioItem.suppressEvents()
        audioItem.bulkUpdate("Silent Track")
        reactive.advance()

        recorder.count shouldBe 0
        audioItem.title shouldBe "Silent Track"

        audioItem.close()
    }
})