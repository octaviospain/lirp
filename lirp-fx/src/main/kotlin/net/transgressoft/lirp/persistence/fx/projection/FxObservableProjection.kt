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

package net.transgressoft.lirp.persistence.fx.projection

import net.transgressoft.lirp.persistence.projection.ObservableProjection
import javafx.collections.ObservableMap

/**
 * The JavaFX-flavoured observable projection surface, exposing both the JavaFX [ObservableMap]
 * API and the core [ObservableProjection] entries-changed listener on a single return type.
 *
 * The FX value-transform projection factories return this interface so a caller keeps **both**
 * subscription styles without an intermediate cast:
 * - `addListener(MapChangeListener)` / `addListener(InvalidationListener)` from [ObservableMap],
 *   for binding the projection directly to JavaFX controls and observing per-entry map changes the
 *   FX-native way.
 * - [addOnEntriesChangedListener][ObservableProjection.addOnEntriesChangedListener] from
 *   [ObservableProjection], for a batched, value-carrying create/replace/delete stream that mirrors
 *   the core layer's listener contract.
 *
 * Both surfaces observe the same underlying state and stay consistent within a single FX pulse.
 * The map is read-only; mutation methods inherited from [ObservableMap] throw
 * [UnsupportedOperationException]. Closing it (via [ObservableProjection]'s
 * [close][net.transgressoft.lirp.persistence.projection.CloseableProjection.close]) releases any
 * registry subscription the backing projection holds.
 *
 * @param PK the projection key type
 * @param V the transformed value type
 */
interface FxObservableProjection<PK, V : Any> : ObservableMap<PK, V>, ObservableProjection<PK, V>