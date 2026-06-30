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

package net.transgressoft.lirp.kafka.outbox

import java.util.UUID

/**
 * Abstraction over the outbox persistence layer.
 *
 * Implementations must write outbox rows **inside the same open database transaction** that
 * is flushing the entity mutations — specifically inside the `transaction { }` block in
 * `SqlRepository.commitTransactionBuffer`. Writing in a separate transaction breaks the
 * atomicity guarantee: either both the entity mutation and the outbox row are committed, or
 * neither is.
 *
 * The SQL-backed implementation is the only supported store. JSON-file and volatile
 * repositories cannot participate in the transactional outbox.
 */
internal interface OutboxStore {
    /**
     * Returns up to [limit] outbox events that have not yet been sent to Kafka,
     * ordered by creation time ascending.
     */
    fun findUnsent(limit: Int): List<OutboxEvent>

    /**
     * Marks the event identified by [id] as sent.
     */
    fun markSent(id: UUID)
}