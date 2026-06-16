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

/**
 * Marks a collection navigation property as an aggregate reference to a collection of
 * [net.transgressoft.lirp.entity.ReactiveEntity] instances.
 *
 * At compile time, the LIRP KSP processor scans for `@ToManyAggregates` annotations and generates a
 * [LirpRefAccessor] implementation per entity class. The generated accessor contains direct
 * property getter lambdas that retrieve the referenced entities' IDs — no runtime reflection.
 *
 * At runtime, when an entity is first added to a repository, the generated accessor is loaded via
 * a convention-based class lookup (`{EntityClassName}_LirpRefAccessor`) and its
 * [CollectionRefEntry][net.transgressoft.lirp.persistence.CollectionRefEntry] descriptors
 * drive reference resolution and cascade behavior.
 *
 * Uses [AnnotationRetention.BINARY] retention — the annotation is stored in the class file but is
 * not visible to Java runtime reflection scanners. KSP reads annotations directly from source code
 * at compile time, so runtime retention is unnecessary.
 *
 * **Requires the `lirp-ksp` processor** to be applied via the KSP Gradle plugin. Without it,
 * `@ToManyAggregates` annotations have no effect.
 *
 * Example:
 * ```kotlin
 * // build.gradle.kts: ksp(project(":lirp-ksp"))
 *
 * class Playlist(override val id: Int, val trackIds: List<Int>) : ReactiveEntityBase<Int, Playlist>() {
 *     @ToManyAggregates(onDelete = CascadeAction.CASCADE)
 *     val tracks by aggregateList<Int, Track>(trackIds)
 * }
 * ```
 *
 * @param bubbleUp When `true`, mutation events from referenced entities are forwarded to this
 *   entity's subscribers as [net.transgressoft.lirp.event.AggregateMutationEvent]. Disabled by default.
 * @param onDelete The [CascadeAction] to execute when this entity is removed from its repository.
 *   Defaults to [CascadeAction.DETACH].
 */
@Target(AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class ToManyAggregates(val bubbleUp: Boolean = false, val onDelete: CascadeAction = CascadeAction.DETACH)