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

package net.transgressoft.lirp.testing

import net.transgressoft.lirp.entity.LirpEntity
import net.transgressoft.lirp.entity.ReactiveEntity
import net.transgressoft.lirp.event.EventType
import net.transgressoft.lirp.event.LirpEvent
import net.transgressoft.lirp.event.LirpEventPublisher
import net.transgressoft.lirp.event.LirpEventSubscription
import net.transgressoft.lirp.event.MutationEvent
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Thread-safe test helper that captures events emitted to a subscriber into a typed queue.
 *
 * Drop-in replacement for the ad-hoc `AtomicInteger` counter + `subscribe { ... }` pattern when
 * a test needs more than a count — payload inspection, type filtering, or a snapshot/delta
 * check across subscription windows. Tests that genuinely need only a count remain clearer
 * with `AtomicInteger`.
 *
 * Internal by design: the API is tuned to lirp's own tests and not committed to as a public
 * surface. Promote on demand if downstream consumers reimplement the same pattern.
 *
 *     val recorder = repository.record()           // subscribes and starts capturing
 *     // ... emit events ...
 *     recorder.count shouldBe 3
 *     recorder.events.map { it.entities.keys.first() } shouldContainExactly listOf(1, 2, 3)
 */
class EventRecorder<E : Any> {

    private val captured = ConcurrentLinkedQueue<E>()
    private val lock = ReentrantLock()
    private val countChanged = lock.newCondition()

    /** Number of events captured since the last [reset]. */
    val count: Int get() = captured.size

    /** Snapshot of captured events in arrival order. Safe to iterate concurrently. */
    val events: List<E> get() = captured.toList()

    /** The most recently captured event, or `null` if nothing has been recorded yet. */
    val last: E? get() = captured.lastOrNull()

    /** Append [event] to the internal queue. Bound to a publisher via [record]. */
    fun record(event: E) {
        lock.withLock {
            captured.add(event)
            countChanged.signalAll()
        }
    }

    /**
     * Blocks until at least [n] events have been captured or [timeout] elapses, returning `true`
     * when the target was reached. Replaces the ad-hoc `CountDownLatch` + `await` dance in tests
     * that emit asynchronously and then need to wait for delivery before asserting.
     *
     * Intended for tests wired to real dispatchers (async delivery). Test-dispatcher specs should
     * keep driving the scheduler with `advance()` and asserting [count] synchronously instead.
     */
    fun awaitCount(n: Int, timeout: Duration = 2.seconds): Boolean {
        val deadlineNanos = System.nanoTime() + timeout.inWholeNanoseconds
        lock.withLock {
            while (captured.size < n) {
                val remaining = deadlineNanos - System.nanoTime()
                if (remaining <= 0) return captured.size >= n
                countChanged.awaitNanos(remaining)
            }
            return true
        }
    }

    /** Drops all captured events. Useful between subscription phases in long-running specs. */
    fun reset() {
        captured.clear()
    }
}

/**
 * Subscribes a fresh [EventRecorder] to this publisher and returns it.
 *
 * When [types] is empty the recorder is bound to every event; otherwise only the listed types
 * are captured. The recorder keeps recording until the spec ends — explicit unsubscription is
 * unnecessary because the wired [net.transgressoft.lirp.event.ReactiveScope] is replaced
 * between specs by [ReactiveScopeExtension].
 */
fun <ET : EventType, E : LirpEvent<ET>> LirpEventPublisher<ET, E>.record(
    vararg types: ET
): EventRecorder<E> {
    val recorder = EventRecorder<E>()
    val subscription: LirpEventSubscription<in LirpEntity, ET, E> =
        if (types.isEmpty()) subscribe(callback = recorder::record)
        else subscribe(*types, callback = recorder::record)

    // Subscription is kept alive by the publisher's internal bookkeeping; the explicit type
    // annotation above silences a compiler inference warning on the vararg branch.
    @Suppress("UNUSED_VARIABLE")
    val keepAlive = subscription
    return recorder
}

/**
 * Subscribes a fresh [EventRecorder] synchronously to this entity's mutation events and returns it.
 *
 * When [types] is empty every mutation is captured; otherwise only the listed types are. The
 * synchronous [ReactiveEntity.subscribe] path records inline on the emitting thread — use this when
 * the test drives delivery deterministically (e.g. a test dispatcher advanced via `advance()`).
 */
fun <K, R> R.record(vararg types: MutationEvent.Type): EventRecorder<MutationEvent<K, R>>
    where K : Comparable<K>, R : ReactiveEntity<K, R> {
    val recorder = EventRecorder<MutationEvent<K, R>>()
    if (types.isEmpty()) subscribe(callback = recorder::record)
    else subscribe(*types, callback = recorder::record)
    return recorder
}

/**
 * Subscribes a fresh [EventRecorder] asynchronously to this entity's mutation events and returns it.
 *
 * Pairs with [EventRecorder.awaitCount] to replace the `AtomicReference` + `CountDownLatch` + await
 * pattern: record asynchronously, emit, then `awaitCount(n)` before asserting on the captured events.
 */
fun <K, R> R.recordAsync(): EventRecorder<MutationEvent<K, R>>
    where K : Comparable<K>, R : ReactiveEntity<K, R> {
    val recorder = EventRecorder<MutationEvent<K, R>>()
    subscribeAsync { event -> recorder.record(event) }
    return recorder
}