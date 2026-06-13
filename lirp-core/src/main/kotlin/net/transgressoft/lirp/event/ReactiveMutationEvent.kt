/******************************************************************************
 * Copyright (C) 2025  Octavio Calleya Garcia                                 *
 *                                                                            *
 * This program is free software: you can redistribute it and/or modify       *
 * it under the terms of the GNU General Public License as published by       *
 * the Free Software Foundation, either version 3 of the License, or          *
 * (at your option) any later version.                                        *
 *                                                                            *
 * This program is distributed in the hope that it will be useful,            *
 * but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 * GNU General Public License for more details.                               *
 *                                                                            *
 * You should have received a copy of the GNU General Public License          *
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.     *
 ******************************************************************************/

package net.transgressoft.lirp.event

import net.transgressoft.lirp.entity.ReactiveEntity

/**
 * General-purpose mutation event carrying only the mutated entity reference.
 * Emitted by legacy code paths that do not yet carry per-property change details.
 *
 * @param K the entity key type
 * @param R the entity type
 */
data class ReactiveMutationEvent<K, R>(override val entity: R)
: MutationEvent<K, R> where K : Comparable<K>, R : ReactiveEntity<K, R> {

    override val type = MutationEvent.Type.MUTATE
}