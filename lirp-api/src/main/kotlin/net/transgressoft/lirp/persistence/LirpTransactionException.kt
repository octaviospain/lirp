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

/**
 * Thrown when a `transaction` block fails to commit atomically.
 *
 * In-memory state is restored to its pre-block values before this exception propagates.
 * The [cause] carries the underlying failure (constraint violation, connection error, etc.).
 *
 * This exception is defined in the `lirp-api` module so consumers can catch it without
 * depending on the `lirp-core` implementation module.
 *
 * When an `onError` handler is configured on the `transaction` call, this exception is
 * suppressed and the handler is invoked instead.
 */
open class LirpTransactionException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Specialization of [LirpTransactionException] raised when one or more `@Version`-protected
 * entities in the transaction block were modified by a concurrent writer before the commit.
 *
 * Each element of [conflicts] describes the in-memory entity (restored to its pre-block state)
 * alongside the authoritative database state and the database version at conflict time. The
 * caller can read [ConflictInfo.canonical] to obtain the authoritative state and decide whether
 * to retry.
 *
 * The [conflicts] list uses star projections because the exception must be throwable for
 * heterogeneous entity types when multiple participants are involved in the same transaction.
 */
class TransactionConflictException(
    message: String,
    val conflicts: List<ConflictInfo<*, *>>,
    cause: Throwable? = null
) : LirpTransactionException(message, cause)