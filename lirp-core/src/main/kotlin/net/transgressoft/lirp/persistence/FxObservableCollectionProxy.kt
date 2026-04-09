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
 * Marker interface for JavaFX observable collection proxies that wrap a mutable aggregate
 * collection delegate. Implemented by `FxAggregateListProxy` and `FxAggregateSetProxy`
 * from the `lirp-fx` module.
 *
 * [RegistryBase] uses this interface to detect fx proxies in [RegistryBase.bindEntityRefs]
 * without creating a circular module dependency between `lirp-core` and `lirp-fx`.
 * The [innerMutableProxy] property exposes the wrapped proxy (either [MutableAggregateListProxy]
 * or [MutableAggregateSetProxy]) so that RegistryBase can reach the internal delegate for
 * registry binding and collection emission callback injection.
 */
interface FxObservableCollectionProxy {
    /**
     * The wrapped mutable aggregate proxy.
     *
     * Will be either a [MutableAggregateListProxy] or [MutableAggregateSetProxy] instance.
     * Typed as [Any] to avoid importing concrete fx proxy types in lirp-core.
     */
    val innerMutableProxy: Any

    /**
     * Synchronizes the local element cache with the resolved entities from the bound registry.
     *
     * Called by [RegistryBase.bindEntityRefs] after the inner delegate's registry is bound,
     * ensuring the fx proxy's local cache reflects the current backing IDs. Without this,
     * proxies constructed from IDs only (e.g. from SQL deserialization) would have an empty
     * local cache, causing operations like [remove] and [get] to malfunction.
     */
    fun syncLocalCache()
}