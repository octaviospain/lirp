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
 * [RegistryBase] uses this interface to detect fx scalar property delegates in
 * [RegistryBase.bindEntityRefs] without creating a circular module dependency between
 * `lirp-core` and `lirp-fx`. When discovered, RegistryBase injects a mutation callback
 * that wraps each `super.set()` call in a clone-before-mutation sequence, emitting a
 * [net.transgressoft.lirp.event.ReactiveMutationEvent] on the owning entity's publisher.
 *
 * The injected [callback] parameter accepts a mutation block supplied by the delegate —
 * typically a lambda that calls `super.set(newValue)` on the underlying Simple*Property.
 * RegistryBase wraps this block in [net.transgressoft.lirp.entity.ReactiveEntityBase.emitFxScalarMutation]
 * to ensure clone-before-mutation ordering and correct event emission.
 */
fun interface FxScalarPropertyDelegate {
    /**
     * Binds a mutation callback injected by [RegistryBase] that wraps scalar mutations in
     * clone-before-mutation event emission logic.
     *
     * @param callback a function that accepts a mutation block (the `super.set()` call) and
     *   executes it within lirp's reactive event pipeline
     */
    fun bindMutationCallback(callback: (() -> Unit) -> Unit)
}