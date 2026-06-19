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

import net.transgressoft.lirp.entity.IdentifiableEntity
import net.transgressoft.lirp.event.CrudEvent

/**
 * Closes the seed window of a registry-backed projection: the interval between the projection
 * subscribing to registry events and its seed iteration completing.
 *
 * A projection must subscribe to the registry **before** iterating it for the initial seed,
 * otherwise events emitted concurrently during the seed are dropped — the publisher short-circuits
 * emissions while there are no subscribers, and the registry iterator is only weakly consistent.
 * Subscribing first, however, lets the event-delivery thread mutate buckets concurrently with the
 * seed thread. This buffer serializes the two: while seeding, events are held; after the seed
 * completes, [completeSeed] replays them in arrival order on the seed thread, keeping the seed
 * thread the sole writer throughout and never losing a delta.
 *
 * @param K the entity ID type
 * @param E the entity type
 */
internal class SeedEventBuffer<K : Comparable<K>, E : IdentifiableEntity<K>> {
    private val pending = ArrayDeque<CrudEvent<K, E>>()
    private var seeding = true

    /**
     * Buffers [event] when the seed is still in progress and returns `true`; returns `false` once the
     * seed has completed, signalling the caller to apply the event directly.
     */
    fun deferIfSeeding(event: CrudEvent<K, E>): Boolean =
        synchronized(pending) {
            if (seeding) {
                pending.addLast(event)
                true
            } else {
                false
            }
        }

    /**
     * Ends the seed window and replays every buffered event, in arrival order, through [apply].
     * Runs under the same monitor as [deferIfSeeding] so an event arriving mid-drain is either
     * replayed here or applied directly by its caller afterwards — never lost and never applied twice.
     */
    fun completeSeed(apply: (CrudEvent<K, E>) -> Unit) {
        synchronized(pending) {
            seeding = false
            while (pending.isNotEmpty()) apply(pending.removeFirst())
        }
    }
}