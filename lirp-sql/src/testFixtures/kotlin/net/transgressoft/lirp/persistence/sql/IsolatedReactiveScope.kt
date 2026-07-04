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

package net.transgressoft.lirp.persistence.sql

import io.kotest.core.extensions.SpecExtension
import io.kotest.core.spec.Spec

/**
 * Kotest [SpecExtension] that runs a spec with [DatabaseTestSupport.withIsolatedReactiveScope] in
 * effect, so its debounced writes and reactive-event delivery use fresh per-spec scopes instead of
 * the JVM-global single-thread `ioScope` shared by every other spec.
 *
 * Persistence-assertion specs poll a durable write with `eventually`/`awaitRow`. When they run on
 * the shared global scope, a slow or backed-up flush from another spec — most visible under a loaded
 * CI runner — can starve the write past the poll window and fail the assertion intermittently. This
 * extension gives each such spec its own scope, cancelled when the spec ends, so no leftover flush
 * can queue behind it. Apply it to any `src/test` spec that asserts on debounced persistence.
 *
 *     class SomePersistenceTest : StringSpec({
 *         extension(IsolatedReactiveScope)
 *         "..." { /* debounced write + eventually { ... } */ }
 *     })
 */
object IsolatedReactiveScope : SpecExtension {
    override suspend fun intercept(spec: Spec, execute: suspend (Spec) -> Unit) {
        DatabaseTestSupport.withIsolatedReactiveScope { execute(spec) }
    }
}