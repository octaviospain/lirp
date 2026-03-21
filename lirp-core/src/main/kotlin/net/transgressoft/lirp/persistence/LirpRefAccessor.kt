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
 * Contract for KSP-generated aggregate reference accessors.
 *
 * Each entity class with [@ReactiveEntityRef][ReactiveEntityRef] properties gets a compile-time
 * generated implementation of this interface. The generated class is named
 * `{EntityName}_LirpRefAccessor` and lives in the same package as the entity, discovered at
 * runtime via a convention-based [Class.forName] lookup.
 *
 * The generated [entries] contain direct property getter and delegate getter lambdas for retrieving
 * referenced entity IDs and [AggregateRefDelegate] instances, completely avoiding `kotlin-reflect`
 * or `java.lang.reflect` overhead.
 *
 * The generated [cancelAllBubbleUp] iterates all [entries] and calls
 * [AggregateRefDelegate.cancelBubbleUp] on each delegate. If the KSP processor does not generate
 * this method, the class will fail to compile — this is the compile-time safety net ensuring all
 * implementations remain consistent with the contract.
 *
 * @param T The entity type this accessor was generated for
 */
interface LirpRefAccessor<T> {

    /**
     * Pre-built reference entries with direct ID getter and delegate getter lambdas for all
     * [@ReactiveEntityRef][ReactiveEntityRef] properties.
     *
     * Star-projected K since references on the same entity may point to differently-typed IDs.
     */
    val entries: List<RefEntry<T, *>>

    /**
     * Cancels all active bubble-up subscriptions on the given [entity] by calling
     * [AggregateRefDelegate.cancelBubbleUp] on each entry's delegate.
     *
     * This method is abstract — the KSP processor always generates an implementation.
     * If KSP code generation is missing for an annotated entity, the generated class will
     * not compile, surfacing the error at build time rather than at runtime.
     *
     * @param entity the entity whose aggregate reference delegates should be detached
     */
    fun cancelAllBubbleUp(entity: T)
}