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

package net.transgressoft.lirp.testing

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.core.extensions.Extension
import io.kotest.core.extensions.SpecExtension
import io.kotest.core.spec.Spec
import io.kotest.engine.concurrency.SpecExecutionMode
import io.kotest.engine.concurrency.TestExecutionMode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Kotest project configuration for core tests.
 *
 * Specs run with bounded concurrency while individual tests remain sequential so specs
 * with shared fixtures, virtual schedulers, or repository state keep deterministic ordering.
 */
class CoreKotestProjectConfig : AbstractProjectConfig() {
    override val specExecutionMode = SpecExecutionMode.LimitedConcurrency(4)
    override val testExecutionMode = TestExecutionMode.Sequential
    override val extensions: List<Extension> = listOf(ReactiveScopeSpecSerializationExtension)
}

/**
 * Serializes specs that mutate the process-wide [net.transgressoft.lirp.event.ReactiveScope].
 *
 * LIRP core tests already share that global hook extensively. Without this guard, project-level
 * spec parallelism causes unrelated specs to rebind the same scope while file-backed or
 * debounce-sensitive specs are still running.
 */
private object ReactiveScopeSpecSerializationExtension : SpecExtension {

    val mutex = Mutex()

    override suspend fun intercept(spec: Spec, execute: suspend (Spec) -> Unit) {
        if (spec::class.java.isAnnotationPresent(SerializeWithReactiveScope::class.java)) {
            mutex.withLock {
                execute(spec)
            }
        } else {
            execute(spec)
        }
    }
}