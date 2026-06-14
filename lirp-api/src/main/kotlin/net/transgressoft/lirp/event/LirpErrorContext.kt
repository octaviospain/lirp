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

package net.transgressoft.lirp.event

/**
 * Immutable payload delivered to an [LirpErrorHandler] alongside the throwable.
 *
 * The three fields deliberately mirror the MDC keys (`lirp.operation`, `lirp.entityId`,
 * `lirp.repository`) so that the handler callback and the accompanying log line describe
 * the same failure with the same vocabulary. Only entity identity information is included —
 * never full entity field values — to avoid exposing sensitive payload data in error paths.
 *
 * For scope-level backstop invocations where the entity context is not known, [entityIds]
 * is empty.
 *
 * @param operation The type of operation in progress when the failure occurred.
 * @param entityIds The identifiers of the entities involved; empty when the failure is not
 *   attributable to a specific set of entities (e.g. a scope-level uncaught exception).
 * @param repository The name of the repository or publisher in which the failure occurred;
 *   mirrors the `lirp.repository` MDC key.
 */
data class LirpErrorContext(
    val operation: LirpOperation,
    val entityIds: Collection<Any?>,
    val repository: String
)