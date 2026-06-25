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

package net.transgressoft.lirp.persistence.query

/**
 * Classifies the kind of index lookup performed for a single predicate leaf, as recorded
 * in an [IndexHit].
 *
 * Each constant corresponds to one predicate shape that the query planner can push to a
 * hash or sorted index, avoiding a full in-memory scan for that leaf.
 *
 * @see IndexHit
 */
enum class IndexHitType {

    /** The predicate leaf was an exact-equality match resolved through an index lookup. */
    EXACT,

    /** The predicate leaf was a membership check resolved as a union of index lookups. */
    MULTI,

    /** The predicate leaf was a range comparison pushed to a sorted index. */
    RANGE
}