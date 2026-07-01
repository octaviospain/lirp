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

package net.transgressoft.lirp.kafka.spi

import net.transgressoft.lirp.event.EventType
import net.transgressoft.lirp.event.LirpEvent

/**
 * Opt-in SPI marker for custom (non-CRUD) [LirpEvent] implementations that need to be relayed
 * through [net.transgressoft.lirp.kafka.KafkaEventPublisher.emitAsync] to the transactional
 * outbox and ultimately to Kafka.
 *
 * Standard CRUD and mutation events are captured automatically by the flush hook in
 * [net.transgressoft.lirp.kafka.KafkaOutboxSqlRepository]; custom events emitted through
 * [net.transgressoft.lirp.kafka.KafkaEventPublisher.emitAsync] must supply their own aggregate
 * identity and JSON payload, because the generic [LirpEvent] interface only exposes [type].
 *
 * Implementing this interface makes a custom event relayable:
 * - [aggregateId] becomes the Kafka record key, routing all events for the same aggregate to
 *   the same partition and preserving per-aggregate delivery order.
 * - [payload] is stored as the JSON body of the outbox row and delivered as the CloudEvents
 *   `data` field to downstream consumers.
 *
 * Events that do **not** implement [OutboxRoutableEvent] and are emitted through
 * [net.transgressoft.lirp.kafka.KafkaEventPublisher.emitAsync] will cause the publisher to
 * throw at the point of emission, surfacing the misconfiguration as a bug rather than silently
 * losing event data.
 */
interface OutboxRoutableEvent<out T : EventType> : LirpEvent<T> {

    /**
     * Stable string key identifying the aggregate instance this event belongs to.
     *
     * Used as the Kafka record key to route all events for the same aggregate to the same
     * partition, preserving per-aggregate ordering. Must be non-blank and stable across retries
     * so the relay can redeliver the row with the same key after a transient failure.
     */
    val aggregateId: String

    /**
     * JSON-encoded body of the event, serialized into the outbox row and delivered as the
     * CloudEvents `data` field.
     *
     * The format is left to the implementing event type; the relay transmits it verbatim.
     * Consumers that need strong schema guarantees should encode a versioned schema identifier
     * inside the payload or use a custom [net.transgressoft.lirp.kafka.spi.LirpEventSerializer].
     */
    val payload: String
}