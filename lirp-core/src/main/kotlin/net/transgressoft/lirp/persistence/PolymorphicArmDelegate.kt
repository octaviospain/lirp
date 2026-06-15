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

import net.transgressoft.lirp.entity.CascadeAction
import net.transgressoft.lirp.entity.IdentifiableEntity

/**
 * Wrapper around an [AggregateRefDelegate] representing a single named arm in a polymorphic
 * aggregate reference. Each arm targets one entity type and carries its own cascade semantics.
 *
 * Instances are created via the [arm] factory and passed to [polymorphicAggregate].
 * The [innerDelegate] is owned exclusively by this wrapper — no hand-rolled registry lookup
 * is required because [optionalAggregate] handles all resolution and binding internally.
 *
 * @param K the type of the referenced entity's ID, must be [Comparable]
 * @param E the referenced entity type
 * @param label the unique name identifying this arm within its [PolymorphicAggregateDelegate]
 * @param onDelete the cascade action to execute on the referenced entity when the owning entity
 *   is removed; defaults to [CascadeAction.DETACH]
 * @param innerDelegate the underlying optional aggregate delegate that resolves the entity
 * @param idProvider the lambda supplying the current FK value; evaluated to check for null without
 *   requiring a bound registry
 * @param referencedClass the runtime class of the target entity type; used to locate the correct
 *   registry in the [net.transgressoft.lirp.persistence.LirpContext] during arm binding
 */
class PolymorphicArmDelegate<K : Comparable<K>, E : IdentifiableEntity<K>>(
    val label: String,
    val onDelete: CascadeAction = CascadeAction.DETACH,
    internal val innerDelegate: AggregateRefDelegate<K, E>,
    private val idProvider: () -> K?,
    internal val referencedClass: Class<E>
) {
    /**
     * Returns `true` when the arm's FK scalar is non-null. This check does not require the
     * registry to be bound, making it safe to use before the entity is added to a repository.
     */
    fun hasValue(): Boolean = idProvider() != null
}

/**
 * Creates a named arm for use with [polymorphicAggregate], targeting a single entity type.
 *
 * The [idProvider] lambda is evaluated fresh on each resolution call, matching the behaviour
 * of [optionalAggregate]. The arm's [onDelete] cascade action applies independently of the
 * other arms in the group — arms reference different entity types and may need different
 * cascade semantics.
 *
 * The reified type parameter `E` is captured at the call site so that the arm can locate the
 * correct registry at binding time without relying on KSP-generated metadata.
 *
 * @param K the type of the referenced entity's ID, must be [Comparable]
 * @param E the referenced entity type
 * @param label the unique name that identifies this arm; used by [PolymorphicResolution.resolveArm]
 *   and by the KSP-generated typed accessor
 * @param onDelete cascade action when the owning entity is removed; defaults to [CascadeAction.DETACH]
 * @param idProvider lambda returning the current FK value, or `null` when this arm is not set
 */
inline fun <K : Comparable<K>, reified E : IdentifiableEntity<K>> arm(
    label: String,
    onDelete: CascadeAction = CascadeAction.DETACH,
    noinline idProvider: () -> K?
): PolymorphicArmDelegate<K, E> = PolymorphicArmDelegate(label, onDelete, optionalAggregate(idProvider), idProvider, E::class.java)