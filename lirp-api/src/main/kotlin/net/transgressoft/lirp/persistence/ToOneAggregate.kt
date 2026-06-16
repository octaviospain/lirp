/******************************************************************************
 *     Copyright (C) 2026  Octavio Calleya Garcia                             *
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
import kotlin.reflect.KClass

/**
 * Marks a single-entity aggregate reference to another [net.transgressoft.lirp.entity.ReactiveEntity].
 *
 * Two placement forms are supported:
 *
 * - **Persisted FK scalar** (`var ownerCompanyId: UUID?`): the annotated property holds the foreign-key
 *   value directly. The LIRP KSP processor generates a `ForeignKeyDef` entry in
 *   `{Entity}_LirpTableDef.foreignKeys()` and a navigation extension accessor
 *   `val {Entity}.{propNameStrippedOfId}: ReactiveEntityReference<K, E>`.
 *   Optional vs. required FK is inferred from Kotlin nullability: a nullable scalar (`UUID?`) produces
 *   an optional reference; a non-nullable scalar (`UUID`) produces a required one.
 *
 * - **Computed-key delegate** (`val owner by aggregate<K, E> { ownerCompanyId }` or
 *   `val owner by optionalAggregate<K, E> { ownerCompanyId }`): the annotated property is a delegate
 *   that evaluates the FK at runtime via the supplied lambda. No extension accessor is generated — the
 *   delegate itself is the accessor. A `ForeignKeyDef` is still emitted when the lambda body is a simple
 *   property reference; computed expressions (e.g. `if (…) a else b`) have no corresponding SQL column
 *   and are silently skipped in FK metadata generation.
 *
 * In both forms a `RefEntry` is emitted in `{Entity}_LirpRefAccessor` so cascade and bubble-up run
 * automatically.
 *
 * **Requires the `lirp-ksp` processor** to be applied via the KSP Gradle plugin. Without it,
 * `@ToOneAggregate` annotations have no effect.
 *
 * @param target the referenced entity class. For the scalar form it must carry `@PersistenceMapping`
 *   and extend `ReactiveEntity`. For the delegate form it must match the entity type argument of
 *   `aggregate<K, E>` / `optionalAggregate<K, E>`.
 * @param onDelete the [CascadeAction] executed when the owning entity is removed from its repository.
 *   Defaults to [CascadeAction.DETACH].
 * @param bubbleUp when `true`, mutation events from the referenced entity are forwarded to this
 *   entity's subscribers as [net.transgressoft.lirp.event.AggregateMutationEvent]. Disabled by default.
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class ToOneAggregate(
    val target: KClass<*>,
    val onDelete: CascadeAction = CascadeAction.DETACH,
    val bubbleUp: Boolean = false
)