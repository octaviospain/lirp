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
 * Marker interface for all LIRP property delegates that participate in the framework's
 * runtime delegate introspection. Implemented by reactive property delegates and all
 * aggregate collection reference delegates.
 *
 * The [ReactiveEntityBase.delegateRegistry][net.transgressoft.lirp.entity.ReactiveEntityBase]
 * discovers instances of this interface by scanning `memberProperties` via kotlin-reflect,
 * enabling framework-level serialization without KSP code generation.
 */
internal interface LirpDelegate