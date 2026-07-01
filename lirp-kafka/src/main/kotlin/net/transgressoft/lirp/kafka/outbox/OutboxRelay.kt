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

package net.transgressoft.lirp.kafka.outbox

import net.transgressoft.lirp.event.LirpErrorContext
import net.transgressoft.lirp.event.LirpErrorHandler
import net.transgressoft.lirp.event.LirpOperation
import net.transgressoft.lirp.event.ReactiveScope
import net.transgressoft.lirp.kafka.KafkaEventPublisher
import net.transgressoft.lirp.kafka.KafkaOutboxConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.kafka.common.errors.RetriableException
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Background relay loop that drains the transactional outbox by publishing each unsent row to
 * Kafka and marking it as sent only after the broker acknowledges the record.
 *
 * Each poll cycle executes inside a single Exposed transaction: the transaction polls unsent rows
 * (using dialect-appropriate row locking on PostgreSQL/MySQL/MariaDB), publishes each row via
 * [publisher], and commits [SqlOutboxStore.markSent] after a successful publish. A crash between
 * publish and commit rolls the transaction back, leaving the row available for redelivery on the
 * next cycle — the at-least-once guarantee.
 *
 * **Failure classification:** a [RetriableException] from the Kafka client increments
 * [OutboxEvent.retryCount] and schedules the next attempt via exponential backoff. Any other
 * exception, and any row whose [OutboxEvent.retryCount] is already at [KafkaOutboxConfig.maxRetries],
 * is moved atomically to the dead-letter table and the [onDeadLetter] callback is invoked.
 *
 * **Topic routing:** each row is published to `"${row.aggregateType}.events"`. This default routing
 * is suitable for most single-aggregate deployments; pluggable topic resolution is deferred to a
 * later release.
 *
 * **Ordering:** records are keyed by [OutboxEvent.aggregateId] so the Kafka producer routes
 * all events for a given aggregate to the same partition, preserving per-aggregate ordering.
 *
 * **Connection resource:** the relay holds a HikariCP connection open while waiting for Kafka
 * broker acknowledgement inside each row's transaction. Configure the Kafka producer's
 * `delivery.timeout.ms` and `request.timeout.ms` to bound this window; a broker outage that
 * exceeds the connection pool's `connectionTimeout` will surface as a pool exhaustion error.
 *
 * @param db Exposed [Database] handle for the outbox and dead-letter tables.
 * @param publisher Kafka publisher used to send each outbox row.
 * @param config Relay behaviour knobs — poll interval, batch size, retry limits, backoff.
 * @param onDeadLetter Optional callback invoked when a row is moved to the dead-letter table.
 *   The callback receives the terminal exception and a [LirpErrorContext] describing the failure.
 *   Exceptions thrown by the callback are swallowed.
 */
internal class OutboxRelay(
    private val db: Database,
    private val publisher: KafkaEventPublisher,
    private val config: KafkaOutboxConfig,
    private val onDeadLetter: LirpErrorHandler? = null
) : AutoCloseable {

    private val log = KotlinLogging.logger(javaClass.name)
    private val store = SqlOutboxStore(db)
    private var job: Job? = null

    /**
     * Starts the background poll loop on [ReactiveScope.ioScope].
     *
     * The [DeadLetterTable] schema is created idempotently before the loop starts. Calling
     * [start] on a relay that is already running throws [IllegalStateException].
     */
    fun start() {
        check(job == null || job!!.isCompleted) { "Relay is already running" }
        transaction(db) { SchemaUtils.create(DeadLetterTable) }
        job =
            ReactiveScope.ioScope.launch {
                while (isActive) {
                    try {
                        pollAndRelay()
                    } catch (e: CancellationException) {
                        throw e // cooperative cancellation — never swallow
                    } catch (e: Exception) {
                        log.error(e) { "Relay poll cycle failed" }
                    }
                    delay(config.pollIntervalMs)
                }
            }
    }

    /** Cancels the background poll loop. A stopped relay can be [start]ed again. */
    fun stop() {
        job?.cancel()
    }

    override fun close() {
        stop()
    }

    internal fun pollAndRelay() {
        val now = Clock.System.now()
        transaction(db) {
            val rows = store.findUnsentForRelay(config.batchSize, now)
            rows.forEach { row -> processRow(row) }
        }
    }

    internal fun processRow(row: OutboxEvent) {
        if (row.retryCount >= config.maxRetries) {
            val cause = RuntimeException("Max retries (${config.maxRetries}) exceeded for outbox event ${row.id}")
            store.moveToDeadLetter(row, Clock.System.now(), cause.message!!)
            invokeDeadLetterCallback(cause)
            return
        }

        try {
            publisher.publish(topicFor(row), row.aggregateId, row.payload.toByteArray())
            store.markSent(row.id)
        } catch (e: RetriableException) {
            val nextRetryAt = computeNextRetryAt(row.retryCount, config)
            store.scheduleRetry(row.id, nextRetryAt, e.message ?: e.javaClass.simpleName)
        } catch (e: Exception) {
            store.moveToDeadLetter(row, Clock.System.now(), e.message ?: e.javaClass.simpleName)
            invokeDeadLetterCallback(e)
        }
    }

    private fun invokeDeadLetterCallback(cause: Throwable) {
        try {
            onDeadLetter?.invoke(cause, LirpErrorContext(LirpOperation.EMIT, emptyList(), javaClass.name))
        } catch (e: Exception) {
            log.error(e) { "onDeadLetter callback threw an exception" }
        }
    }

    internal companion object {

        internal fun topicFor(row: OutboxEvent): String = "${row.aggregateType}.events"

        /**
         * Computes the next retry timestamp using exponential backoff with ±20% jitter.
         *
         * The delay doubles with each attempt (base × 2^retryCount) and is capped at
         * [KafkaOutboxConfig.retryMaxDelayMs]. The ±20% uniform jitter is applied to reduce
         * thundering-herd effects when many rows fail simultaneously.
         */
        internal fun computeNextRetryAt(retryCount: Int, config: KafkaOutboxConfig): Instant {
            val baseMs = config.retryBaseDelayMs
            val capMs = config.retryMaxDelayMs
            val raw = minOf(baseMs * (1L shl retryCount), capMs)
            val jittered = raw * (0.8 + Math.random() * 0.4)
            return Clock.System.now() + jittered.toLong().milliseconds
        }
    }
}