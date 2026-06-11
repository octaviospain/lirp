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
import net.transgressoft.lirp.event.EventType
import net.transgressoft.lirp.event.LirpEvent
import net.transgressoft.lirp.event.LirpEventPublisher
import net.transgressoft.lirp.event.LirpEventSubscription
import java.util.concurrent.ConcurrentLinkedQueue

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

    /** Number of events captured since the last [reset]. */
    val count: Int get() = captured.size

    /** Snapshot of captured events in arrival order. Safe to iterate concurrently. */
    val events: List<E> get() = captured.toList()

    /** The most recently captured event, or `null` if nothing has been recorded yet. */
    val last: E? get() = captured.lastOrNull()

    /** Append [event] to the internal queue. Bound to a publisher via [record]. */
    fun record(event: E) {
        captured.add(event)
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
        if (types.isEmpty()) subscribe(action = recorder::record)
        else subscribe(*types, action = recorder::record)

    // Subscription is kept alive by the publisher's internal bookkeeping; the explicit type
    // annotation above silences a compiler inference warning on the vararg branch.
    @Suppress("UNUSED_VARIABLE")
    val keepAlive = subscription
    return recorder
}