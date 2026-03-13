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

package net.transgressoft.lirp.event

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Centralized manager for coroutine scopes used throughout the reactive system.
 *
 * This singleton object provides standardized coroutine scopes for different types of operations
 * in the reactive framework:
 *
 * - Flow processing: For handling event flows, subscriptions, and reactive updates
 * - I/O operations: For file access, serialization, and other potentially blocking operations
 *
 * By centralizing scope management, ReactiveScope ensures:
 * 1. Consistent behavior across the reactive system
 * 2. Proper resource utilization with controlled parallelism
 * 3. Easy configuration for testing with test dispatchers
 * 4. Clean cancellation of ongoing operations when needed
 *
 * The default scopes use limited parallelism to prevent resource exhaustion while
 * maintaining responsive operation.
 *
 * @see flowScope
 * @see ioScope
 */
object ReactiveScope {
    // Default scope with limited parallelism to prevent resource exhaustion
    // but ensuring all entity events are processed
    private var defaultFlowScope: CoroutineScope = CoroutineScope(Dispatchers.Default.limitedParallelism(4) + SupervisorJob())

    private var defaultIoScope: CoroutineScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob())

    /**
     * Sets the default scope for all reactive entities that don't specify their own.
     * Primarily used for testing to inject test dispatchers but can be used to customize
     * the default scope for all reactive entities.
     */
    var flowScope: CoroutineScope = defaultFlowScope

    /**
     * Sets the default scope for I/O operations.
     */
    var ioScope: CoroutineScope = defaultIoScope

    fun resetDefaultFlowScope() {
        flowScope = defaultFlowScope
    }

    fun resetDefaultIoScope() {
        ioScope = defaultIoScope
    }
}