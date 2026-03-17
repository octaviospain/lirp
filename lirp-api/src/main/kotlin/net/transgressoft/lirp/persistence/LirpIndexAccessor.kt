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
 * Contract for KSP-generated index accessors.
 *
 * Each entity class with [@Indexed][Indexed] properties gets a compile-time generated implementation
 * of this interface. The generated class is named `{EntityName}_LirpIndexAccessor` and lives in the
 * same package as the entity, discovered at runtime via a convention-based [Class.forName] lookup.
 *
 * The generated [entries] contain direct property getter lambdas compiled to regular method calls,
 * completely avoiding `kotlin-reflect` or `java.lang.reflect` overhead.
 *
 * @param T The entity type this accessor was generated for
 */
interface LirpIndexAccessor<T> {

    /**
     * Pre-built index entries with direct property getters for all [@Indexed][Indexed] properties.
     */
    val entries: List<IndexEntry<T>>
}