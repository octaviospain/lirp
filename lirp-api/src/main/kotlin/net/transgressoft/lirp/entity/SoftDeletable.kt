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

package net.transgressoft.lirp.entity

import java.time.Instant

/**
 * Marks an entity as supporting soft deletion via a nullable [deletedAt] timestamp.
 *
 * An entity is considered soft-deleted when [deletedAt] is non-null. Repositories and
 * projections that are aware of soft deletion filter out soft-deleted entities from their
 * visible result sets.
 */
interface SoftDeletable {
    /** The instant at which the entity was soft-deleted, or `null` if it is active. */
    val deletedAt: Instant?
}