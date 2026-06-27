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
 * Extends [SoftDeletable] with a mutable [deletedAt] property so that the framework
 * can set or clear the timestamp without reflection.
 *
 * Concrete entities back this with `override var deletedAt: Instant? by reactiveProperty(null)`.
 */
interface MutableSoftDeletable : SoftDeletable {
    /** Sets or clears the soft-deletion timestamp. */
    override var deletedAt: Instant?
}