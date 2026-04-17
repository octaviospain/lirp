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
 * Thrown when a versioned UPDATE or DELETE operation in a `SqlRepository` detects that the
 * entity's row in the database has been modified by another writer since the in-memory
 * snapshot was taken.
 *
 * This exception is defined in the `lirp-api` module so consumers can catch it
 * without depending on the `lirp-core` implementation module.
 *
 * Under LIRP's optimistic-concurrency model, `OptimisticLockException` is not directly observable
 * by consumers — it is caught inside the flush pipeline, triggers the auto-reload and
 * [net.transgressoft.lirp.event.StandardCrudEvent.Conflict] recovery path, and causes the failed
 * pending op to be dropped rather than re-enqueued. Consumers observe the conflict through the
 * `Conflict` event (see [net.transgressoft.lirp.event.CrudEvent.Type.CONFLICT]).
 *
 * The [message] names the entity type, id, expected version, and actual version found in the
 * database. Example:
 * `"Optimistic lock failure: Order(id=1, expected version=3, actual version=5). Entity has been modified by another writer."`
 *
 * @param message Description of the failure including entity type, id, expected version, and
 *   actual version when available.
 * @param entityId The primary-key value of the conflicting entity. Typed [Any] because the
 *   generic key type is not available at this API level.
 * @param expectedVersion The version the local mutation was based on.
 * @param actualVersion The current version on disk, or `null` if the row was found to have been
 *   deleted by another writer.
 * @param cause Optional underlying database exception. Typically `null` — this is a domain-level
 *   signal, not a database failure.
 */
class OptimisticLockException(
    message: String,
    val entityId: Any,
    val expectedVersion: Long,
    val actualVersion: Long? = null,
    cause: Throwable? = null
) : RuntimeException(message, cause)