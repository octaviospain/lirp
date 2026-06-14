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
 * Marker interface for JavaFX scalar property delegates that participate in lirp's reactive mutation
 * event system. Implemented by `Lirp*Property` classes in the `lirp-fx` module.
 *
 * [RegistryBase] uses this interface to detect FxScalar property delegates in
 * [RegistryBase.bindEntityRefs] without creating a circular module dependency between
 * `lirp-core` and `lirp-fx`. When discovered, RegistryBase injects a mutation callback
 * that emits a [net.transgressoft.lirp.event.PropertyChanged] event on the owning entity's
 * publisher, carrying the old and new values captured synchronously before and after the
 * `super.set()` call.
 *
 * The callback receives the old value captured by the FxScalar delegate before invoking
 * `super.set()`, the new value, and a mutation block wrapping the `super.set()` call.
 */
@Suppress("kotlin:S6517") // Nominal capability marker, its single method is incidental
interface FxScalarPropertyDelegate {
    /**
     * Binds a mutation callback injected by [RegistryBase] that emits a typed mutation event for
     * each scalar property assignment.
     *
     * @param callback a function that receives the captured old value (before `super.set()`),
     *   the new value, and a mutation block (the `super.set()` call); executes the block and
     *   emits a [net.transgressoft.lirp.event.PropertyChanged] event on the entity's publisher
     */
    fun bindMutationCallback(callback: (oldValue: Any?, newValue: Any?, mutationBlock: () -> Unit) -> Unit)
}