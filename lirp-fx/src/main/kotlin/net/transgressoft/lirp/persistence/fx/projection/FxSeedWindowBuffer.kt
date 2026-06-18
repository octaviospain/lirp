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

/**
 * Closes the seed window of a registry-backed FX projection: the interval between the core
 * subscribing to registry events and the projection finishing its direct seed.
 *
 * A registry-backed projection wires [onBucketsChanged] as the core's buckets-changed listener
 * **before** triggering core initialization. The core fires that listener synchronously, on the
 * constructing (init) thread, for its own seed — those callbacks are ignored because the projection
 * applies the seed directly. Genuine mutations arriving during the window come from the registry
 * event thread; they are buffered until [drainAndReconcile] hands them to [onWindowMutation] in one
 * batch, after the direct seed, so no delta is lost between the seed snapshot and the listener wiring.
 *
 * Must be constructed on the init thread, before core initialization.
 *
 * @param PK the projection key type
 * @param onWindowMutation invoked with the keys of genuine window mutations (and, on drain, with any
 *   buffered keys) so the projection can schedule a flush for them
 */
internal class FxSeedWindowBuffer<PK : Comparable<PK>>(
    private val onWindowMutation: (Set<PK>) -> Unit
) {
    private val initThread = Thread.currentThread()
    private val buffer = sortedSetOf<PK>()
    private var seeding = true

    /** Core buckets-changed listener: ignores the core's synchronous seed, buffers window mutations. */
    fun onBucketsChanged(changedKeys: Set<PK>) {
        var dispatch = false
        synchronized(buffer) {
            when {
                !seeding -> dispatch = true
                Thread.currentThread() === initThread -> Unit // core's synchronous seed, applied directly
                else -> buffer.addAll(changedKeys)
            }
        }
        if (dispatch) onWindowMutation(changedKeys)
    }

    /** Ends the window and reconciles any keys mutated during it in a single batch. */
    fun drainAndReconcile() {
        val buffered =
            synchronized(buffer) {
                seeding = false
                buffer.toSet().also { buffer.clear() }
            }
        if (buffered.isNotEmpty()) onWindowMutation(buffered)
    }
}