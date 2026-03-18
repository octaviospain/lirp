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
 * Contract for KSP-generated repository info classes.
 *
 * Each class annotated with [@LirpRepository][LirpRepository] gets a compile-time generated
 * implementation of this interface. The generated class is named `{ClassName}_LirpRegistryInfo`
 * and lives in the same package as the annotated repository, discovered at runtime via a
 * convention-based [Class.forName] lookup in `RegistryBase`'s init block.
 */
interface LirpRegistryInfo {

    /**
     * The entity class stored in the annotated repository, extracted at compile time by the KSP processor.
     */
    val entityClass: Class<*>
}