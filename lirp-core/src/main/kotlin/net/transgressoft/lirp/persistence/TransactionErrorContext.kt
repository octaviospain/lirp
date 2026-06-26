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

import net.transgressoft.lirp.entity.ReactiveEntity

/**
 * Receiver type for the call-scoped `onError` lambda passed to `transaction`.
 *
 * When a transaction block fails — whether from a constraint violation, connection error, or
 * an `@Version` conflict — in-memory state is restored to its pre-block values **before**
 * this context is delivered to the handler. The handler observes the repository in its
 * rolled-back state.
 *
 * Two fields describe the failure:
 * - [throwable] is always present and carries the underlying cause.
 * - [conflicts] is non-empty only when the failure was an `@Version` conflict on one or more
 *   entities. An empty list means the failure was a non-conflict cause (constraint violation,
 *   connection error, etc.).
 *
 * Because rollback completes before the handler runs, [ConflictInfo.entity] carries the
 * values that were attempted **inside** the block (captured from the pre-rollback in-memory
 * state), not the restored pre-block values. Use [ConflictInfo.canonical] to read the
 * authoritative store state for reconciliation.
 *
 * @param K the comparable entity key type
 * @param R the reactive entity type
 * @param throwable the exception that caused the transaction to fail
 * @param conflicts the `@Version` conflicts detected during the commit; empty for non-conflict failures
 */
class TransactionErrorContext<K : Comparable<K>, R : ReactiveEntity<K, R>>(
    val throwable: Throwable,
    val conflicts: List<ConflictInfo<K, R>>
)