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

import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher

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
 * maintaining responsive operation. A [CoroutineExceptionHandler] backstop is installed
 * on both default scopes so uncaught exceptions from root coroutines are logged at ERROR
 * level rather than silently discarded. The [SupervisorJob] ensures each failing launch
 * is isolated — siblings continue to run.
 *
 * @see flowScope
 * @see ioScope
 */
object ReactiveScope {

    private val log = KotlinLogging.logger {}

    /**
     * Last-resort handler for uncaught exceptions that escape a root coroutine on either
     * default scope. Logs the failure at ERROR level so it is observable even when no
     * explicit try/catch surrounds the launch site.
     *
     * The [SupervisorJob] on each scope guarantees sibling coroutines are not cancelled
     * when one child fails — this handler fires per failing root launch only.
     */
    private fun backstop(): CoroutineExceptionHandler =
        CoroutineExceptionHandler { _, throwable ->
            log.error(throwable) { "Uncaught coroutine failure in reactive scope" }
        }

    // Default scope with limited parallelism to prevent resource exhaustion
    // but ensuring all entity events are processed
    private var defaultFlowScope: CoroutineScope =
        CoroutineScope(Dispatchers.Default.limitedParallelism(4) + SupervisorJob() + backstop())

    private var defaultIoScope: CoroutineScope =
        CoroutineScope(Dispatchers.IO.limitedParallelism(1) + SupervisorJob() + backstop())

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

    /**
     * Single-thread, thread-pinned dispatcher dedicated to the explicit `transaction { }`
     * critical section.
     *
     * [ioScope] uses `Dispatchers.IO.limitedParallelism(1)`, which serializes coroutines but may
     * resume a suspended coroutine on a different physical thread of the shared I/O pool. The
     * transaction commit path holds a thread-owned [java.util.concurrent.locks.ReentrantLock]
     * across a suspending user block (which may itself switch dispatchers), so its acquire and
     * release must occur on the same thread — a migrating dispatcher would release the lock from a
     * non-owner thread, throw `IllegalMonitorStateException`, and leak the lock. This dispatcher
     * pins that lifecycle to one daemon thread, guaranteeing same-thread lock ownership.
     */
    private val defaultTransactionDispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "lirp-transaction").apply { isDaemon = true }
        }.asCoroutineDispatcher()

    /**
     * Dispatcher used to run the explicit `transaction { }` lifecycle on a pinned thread.
     */
    var transactionDispatcher: CoroutineDispatcher = defaultTransactionDispatcher

    fun resetDefaultFlowScope() {
        flowScope = defaultFlowScope
    }

    fun resetDefaultIoScope() {
        ioScope = defaultIoScope
    }

    /**
     * Returns `true` when [ioScope] has not been replaced by a test dispatcher and is therefore
     * the production single-slot scope. Flush coroutines use this to decide whether to offload
     * blocking I/O to the unbounded [Dispatchers.IO] pool: the offload is only needed (and
     * meaningful) when [ioScope] has the `limitedParallelism(1)` constraint.
     */
    internal val isProductionIoScope: Boolean get() = ioScope === defaultIoScope

    fun resetDefaultTransactionDispatcher() {
        transactionDispatcher = defaultTransactionDispatcher
    }
}