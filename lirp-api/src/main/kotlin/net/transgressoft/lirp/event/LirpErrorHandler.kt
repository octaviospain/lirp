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

/**
 * Kotlin-first callback interface for observing async framework failures.
 *
 * When configured on a repository or publisher, this handler is invoked for failures
 * that would otherwise be silently swallowed or logged only — such as persistence flush
 * failures, event-channel drain exceptions, and uncaught coroutine errors. It is a
 * notify-only mechanism: the framework has already logged the failure before invoking the
 * handler, and the handler cannot alter control flow or retry behaviour.
 *
 * When no handler is configured, the framework falls back to log-only behaviour (the
 * default prior to configuration).
 *
 * A Kotlin lambda is directly assignable:
 * ```
 * val handler = LirpErrorHandler { throwable, ctx ->
 *     alerting.send("${ctx.repository}/${ctx.operation} failed: ${throwable.message}")
 * }
 * ```
 */
fun interface LirpErrorHandler {

    /**
     * Invoked when an async framework operation fails.
     *
     * The framework guarantees that the failure has already been logged before this call.
     * Implementations must not throw — any exception thrown by the handler will be swallowed.
     *
     * @param throwable The exception that caused the failure.
     * @param context Structured context describing the operation and entities involved.
     */
    operator fun invoke(throwable: Throwable, context: LirpErrorContext)
}