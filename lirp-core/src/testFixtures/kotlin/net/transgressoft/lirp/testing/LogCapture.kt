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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * A single captured log record, holding the level, formatted message, and an immutable
 * snapshot of the MDC context map at the moment the event was appended.
 *
 * @param level The log level as a string (e.g. `"TRACE"`, `"DEBUG"`, `"INFO"`, `"WARN"`, `"ERROR"`).
 * @param message The fully formatted log message.
 * @param mdc Snapshot of the SLF4J MDC key-value pairs present when the event was logged.
 */
data class CapturedLog(
    val level: String,
    val message: String,
    val mdc: Map<String, String>
)

/**
 * Thread-safe test helper that attaches a Logback [ListAppender] to a named logger and
 * exposes the captured events as a list of [CapturedLog] records.
 *
 * Each captured record holds the log level, formatted message, and an immutable snapshot
 * of the MDC map at the time of logging — allowing assertions over both log content and
 * MDC correlation keys in a single step.
 *
 * Attach before the code under test runs, then call [detach] in a tear-down block to
 * restore the logger to its prior state:
 * ```
 * val capture = LogCapture()
 * capture.attach("net.transgressoft.lirp.event.FlowEventPublisher")
 * // ... exercise the code ...
 * capture.logs shouldHaveSize 1
 * capture.logs.first().mdc["lirp.repository"] shouldBe "myRepo"
 * capture.detach()
 * ```
 */
class LogCapture {

    val appender = ListAppender<ILoggingEvent>()
    var capturedLogger: Logger? = null
    var previousLevel: Level? = null

    /**
     * Attaches this capture helper to the Logback logger with [loggerName], setting its
     * level to [captureLevel] so that all events at that level and above are recorded.
     *
     * Any prior attachment is detached first, so repeated [attach] calls never leave the
     * appender bound to an earlier logger. The previous level is remembered and restored
     * by [detach].
     *
     * @param loggerName Fully qualified logger name to capture.
     * @param captureLevel Minimum log level to record; defaults to [Level.TRACE] to capture everything.
     */
    fun attach(loggerName: String, captureLevel: Level = Level.TRACE) {
        detach()
        val logger = LoggerFactory.getLogger(loggerName) as Logger
        previousLevel = logger.level
        logger.level = captureLevel
        appender.start()
        logger.addAppender(appender)
        capturedLogger = logger
    }

    /**
     * Detaches this capture helper from the logger it was attached to, restores that logger's
     * previous level, and stops the internal appender. Safe to call even when [attach] was
     * never called.
     */
    fun detach() {
        capturedLogger?.let { logger ->
            logger.detachAppender(appender)
            logger.level = previousLevel
        }
        appender.stop()
        capturedLogger = null
        previousLevel = null
    }

    /**
     * Returns a snapshot of all captured log events as [CapturedLog] records, preserving
     * arrival order. The MDC map in each record is an immutable copy of the MDC state at
     * log time.
     */
    val logs: List<CapturedLog>
        get() =
            appender.list.map { event ->
                CapturedLog(
                    level = event.level.toString(),
                    message = event.formattedMessage,
                    mdc = event.mdcPropertyMap?.toMap() ?: emptyMap()
                )
            }
}