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

package net.transgressoft.lirp.persistence.json

/**
 * Policy controlling how [JsonFileRepository] handles dangling
 * aggregate references (`@ToOneAggregate` / `@ToManyAggregates`) discovered while loading
 * entities from the JSON file.
 *
 * A reference is considered dangling when the referenced entity ID does not resolve to a live
 * entity in the corresponding registry at load time (e.g. the target file was edited by hand,
 * data was migrated piecemeal, or a peer repository was cleared).
 *
 * SQL-backed repositories enforce the same invariant via foreign-key constraints; this policy
 * closes the symmetric gap on the JSON side. Reconciliation is treated as cleanup, not a domain
 * mutation: no [net.transgressoft.lirp.event.CrudEvent] is emitted and `@Version` is never bumped
 * when the policy adjusts an entity during load.
 */
enum class JsonFkPolicy {
    /**
     * Drop dangling collection IDs and null dangling nullable scalar refs while emitting a
     * single warning per affected entity. Reconcile-time mutations are silent — no events fire
     * and `@Version` is not incremented.
     *
     * Default policy: always succeeds in loading the file even if external state has drifted.
     */
    LOG_AND_RECONCILE,

    /**
     * Throw [net.transgressoft.lirp.persistence.LirpDeserializationException] on load if any
     * dangling aggregate reference (`@ToOneAggregate` / `@ToManyAggregates`) is found.
     * Mirrors SQL `ON DELETE RESTRICT` semantics — the file is treated as authoritative and a
     * mismatch with peer registries is surfaced as an error.
     */
    STRICT
}