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
import net.transgressoft.lirp.kafka.KafkaEventPublisher
import net.transgressoft.lirp.kafka.KafkaOutboxConfig
import net.transgressoft.lirp.kafka.spi.CloudEventsBinarySerializer
import net.transgressoft.lirp.kafka.spi.DefaultTopicResolver
import net.transgressoft.lirp.kafka.spi.LirpEventEnvelope
import net.transgressoft.lirp.kafka.spi.LirpEventSerializer
import net.transgressoft.lirp.kafka.spi.TopicResolver
import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.kafka.common.errors.RetriableException
import org.apache.kafka.common.header.internals.RecordHeaders
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible

/**
 * Background relay loop that drains the transactional outbox by publishing each unsent row to
 * Kafka and marking it as sent only after the broker acknowledges the record.
 *
 * Each row is processed in a single short transaction: the transaction claims the next eligible
 * row (using dialect-appropriate `FOR UPDATE SKIP LOCKED` on PostgreSQL/MySQL/MariaDB so concurrent
 * relays skip it), publishes it to Kafka, and commits [SqlOutboxStore.markSent] only after the
 * broker acknowledges the record. The row lock is held across the blocking send, which prevents a
 * second concurrent relay from re-claiming and double-publishing the same row. The blocking send is
 * wrapped in [kotlinx.coroutines.runInterruptible] so that cancelling the loop (via [stop] or
 * [stopAndJoin]) interrupts the blocked thread and returns promptly rather than waiting for the
 * producer's `delivery.timeout.ms`. A crash or cancellation between publish and commit rolls back
 * the transaction, leaving the row available for redelivery on the next cycle — the at-least-once
 * guarantee is preserved. Scoping each row to its own transaction keeps a transient persistence
 * failure on one row from rolling back rows already published and marked in the same cycle.
 *
 * **Failure classification:** a [RetriableException] from the Kafka client increments
 * [OutboxEvent.retryCount] and schedules the next attempt via exponential backoff while retries
 * remain; once [OutboxEvent.retryCount] reaches [KafkaOutboxConfig.maxRetries] a further retriable
 * failure moves the row to the dead-letter table. Any non-retriable exception moves the row
 * immediately. Delivery is always attempted at least once — a [KafkaOutboxConfig.maxRetries] of 0
 * dead-letters a row only after its first delivery attempt fails. The [onDeadLetter] callback is
 * invoked on every dead-letter move.
 *
 * **Topic routing:** each row's topic is resolved via the pluggable [TopicResolver]. The default
 * resolver returns `"${aggregateType}.events"`, which is suitable for most single-aggregate
 * deployments. Pass a custom [TopicResolver] to [net.transgressoft.lirp.kafka.LirpKafkaConfig.startRelay]
 * to override routing for specific aggregate types or event-type codes.
 *
 * **Ordering:** records are keyed by [OutboxEvent.aggregateId] so the Kafka producer routes
 * all events for a given aggregate to the same partition, preserving per-aggregate ordering
 * for a **single relay instance in the absence of retries**. Two situations break that ordering:
 *
 * - **Retry rescheduling:** when a delivery attempt fails and the row is rescheduled via
 *   exponential backoff, a newer event for the same aggregate that becomes eligible in the
 *   interim may be published first — the relay drains globally by creation time with no
 *   per-aggregate head-of-line blocking.
 * - **Multiple concurrent relays:** [net.transgressoft.lirp.kafka.outbox.SqlOutboxStore] selects
 *   rows with `SKIP LOCKED` on dialects that support it, so two relay instances can each claim
 *   adjacent rows for the same aggregate and publish them out of order even when neither was
 *   retried. Run a single relay instance if strict per-aggregate ordering is required.
 *
 * Consumers that require strict per-aggregate ordering under all conditions must implement
 * idempotent, sequence-number–aware processing to detect and handle out-of-order delivery.
 *
 * **Dispatcher isolation:** the relay runs on a dedicated [kotlinx.coroutines.Dispatchers.IO] scope
 * (not on the shared single-slot `ReactiveScope.ioScope`) so a slow broker acknowledgement does not
 * starve flush scheduling for other repositories. The blocking `producer.send().get()` inside each
 * row's transaction is wrapped in [kotlinx.coroutines.runInterruptible] so that cancelling the loop
 * (via [stop] or [stopAndJoin]) interrupts the blocked thread and returns promptly rather than
 * waiting for the producer's `delivery.timeout.ms`.
 *
 * **Connection resource:** the relay holds a HikariCP connection open while waiting for Kafka
 * broker acknowledgement inside each row's transaction. Configure the Kafka producer's
 * `delivery.timeout.ms` and `request.timeout.ms` to bound this window; a broker outage that
 * exceeds the connection pool's `connectionTimeout` will surface as a pool exhaustion error.
 *
 * @param db Exposed [Database] handle for the outbox and dead-letter tables.
 * @param publisher Kafka publisher used to send each outbox row.
 * @param config Relay behaviour knobs — poll interval, batch size, retry limits, backoff.
 * @param serializer Strategy for serializing a [LirpEventEnvelope] to wire bytes and CloudEvents
 *   `ce_*` headers. Defaults to [CloudEventsBinarySerializer].
 * @param topicResolver Strategy for resolving the Kafka topic name from a [LirpEventEnvelope].
 *   Defaults to [DefaultTopicResolver] which returns `"${aggregateType}.events"`.
 * @param onDeadLetter Optional callback invoked when a row is moved to the dead-letter table.
 *   The callback receives the terminal exception and a [LirpErrorContext] describing the failure.
 *   Exceptions thrown by the callback are swallowed.
 * @param store Outbox persistence store. Defaults to [SqlOutboxStore] over [db]. Exposed for
 *   fault-injection testing only; production callers must not pass a custom value.
 */
internal class OutboxRelay(
    private val db: Database,
    private val publisher: KafkaEventPublisher<*, *>,
    private val config: KafkaOutboxConfig,
    private val serializer: LirpEventSerializer = CloudEventsBinarySerializer(),
    private val topicResolver: TopicResolver = DefaultTopicResolver,
    private val onDeadLetter: LirpErrorHandler? = null,
    store: OutboxStore? = null
) : AutoCloseable {

    private val log = KotlinLogging.logger(javaClass.name)
    private val store: OutboxStore = store ?: SqlOutboxStore(db)

    // Dedicated scope keeps the relay off the shared single-slot ioScope so a slow broker
    // acknowledgement cannot block flush scheduling for unrelated repositories.
    private val relayScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    /**
     * Starts the background poll loop on a dedicated [Dispatchers.IO] scope.
     *
     * The [DeadLetterTable] schema is created idempotently before the loop starts. Calling
     * [start] on a relay that is already running throws [IllegalStateException].
     */
    fun start() {
        check(job == null || job!!.isCompleted) { "Relay is already running" }
        transaction(db) { SchemaUtils.create(DeadLetterTable) }
        job =
            relayScope.launch {
                var consecutiveFailures = 0
                while (isActive) {
                    try {
                        pollAndRelay()
                        consecutiveFailures = 0
                    } catch (e: CancellationException) {
                        throw e // cooperative cancellation — never swallow
                    } catch (e: Exception) {
                        consecutiveFailures++
                        if (consecutiveFailures == 1) {
                            log.error(e) { "Relay poll cycle failed" }
                        } else {
                            // Escalate visibility: a repeating failure (e.g. a dialect-specific syntax
                            // error or unreachable DB) grows the outbox unbounded. Log at ERROR with
                            // the consecutive count so operators notice and act quickly.
                            log.error(e) { "Relay poll cycle failed $consecutiveFailures times in a row — outbox is not draining. Last error: ${e.message}" }
                        }
                    }
                    delay(config.pollIntervalMs)
                }
            }
    }

    /**
     * Cancels the background poll loop and suspends until it finishes, then clears the job.
     *
     * Prefer this over [stop] when calling from a coroutine context to avoid blocking a thread.
     * A stopped relay can be [start]ed again.
     */
    suspend fun stopAndJoin() {
        job?.cancelAndJoin()
        job = null
    }

    /**
     * Cancels the background poll loop and blocks the calling thread until it finishes, so callers
     * can safely close the underlying [DataSource][javax.sql.DataSource] afterwards. A stopped relay
     * can be [start]ed again.
     *
     * The relay's publish step is wrapped in [runInterruptible], so cancellation calls
     * [Thread.interrupt] on any thread blocked inside the broker send, allowing [cancelAndJoin]
     * to complete promptly rather than waiting for `delivery.timeout.ms`.
     */
    fun stop() {
        val current = job ?: return
        runBlocking { current.cancelAndJoin() }
        job = null
    }

    override fun close() {
        stop()
    }

    internal suspend fun pollAndRelay() {
        val now = Clock.System.now()
        var processed = 0
        while (processed < config.batchSize) {
            // Claim, publish, and mark the row within a SINGLE transaction so the FOR UPDATE
            // SKIP LOCKED row lock is held across the publish. This is what prevents a second
            // concurrent relay instance from re-claiming and double-publishing the same row.
            // runInterruptible wraps the whole transaction so that cancelling the relay Job
            // interrupts the thread blocked inside the producer's Future.get(): stop() returns
            // promptly and the transaction rolls back, leaving the row unsent for a later cycle
            // (at-least-once). Running on the dedicated relayScope keeps this off the shared
            // single-slot ioScope, so a slow broker cannot starve other repositories' flushes.
            val handled =
                runInterruptible {
                    transaction(db) {
                        val row = store.findUnsentForRelay(1, now).firstOrNull() ?: return@transaction false
                        processRow(row)
                        true
                    }
                }
            if (!handled) break
            processed++
        }
    }

    internal fun processRow(row: OutboxEvent) {
        try {
            val envelope = LirpEventEnvelope.from(row)
            val topic = topicResolver.resolve(envelope)
            require(topic.isNotBlank()) {
                "TopicResolver returned a blank topic for aggregateType='${envelope.aggregateType}'"
            }
            // Widen to Throwable so an OutOfMemoryError or other Error during serialization does
            // not escape this method and kill the relay coroutine. The row is dead-lettered so
            // the queue is not permanently wedged.
            @Suppress("TooGenericExceptionCaught")
            val serialized =
                try {
                    serializer.serialize(envelope)
                } catch (t: Throwable) {
                    store.moveToDeadLetter(row, Clock.System.now(), t.message ?: t.javaClass.simpleName)
                    log.error(t) { "Serialization error for outbox row ${row.id} (${row.aggregateType}/${row.aggregateId}); row moved to dead-letter" }
                    invokeDeadLetterCallback(t)
                    return
                }
            val recordHeaders = RecordHeaders()
            serialized.headers.forEach { (k, v) -> recordHeaders.add(k, v) }
            // The enclosing pollAndRelay wraps this transaction in runInterruptible, so a blocking
            // send here is interruptible on relay stop() without releasing the row lock before the
            // publish completes.
            publisher.publish(topic, row.aggregateId, serialized.value, recordHeaders)
            store.markSent(row.id)
        } catch (e: InterruptedException) {
            // Cooperative cancellation on relay stop(): the blocking publish was interrupted.
            // Re-set the interrupt flag and rethrow so runInterruptible surfaces the cancellation
            // and the enclosing transaction rolls back — the row stays unsent for a later cycle
            // rather than being dead-lettered.
            Thread.currentThread().interrupt()
            throw e
        } catch (e: RetriableException) {
            if (row.retryCount >= config.maxRetries) {
                store.moveToDeadLetter(row, Clock.System.now(), e.message ?: e.javaClass.simpleName)
                invokeDeadLetterCallback(e)
            } else {
                val nextRetryAt = computeNextRetryAt(row.retryCount + 1, config)
                try {
                    store.scheduleRetry(row.id, nextRetryAt, e.message ?: e.javaClass.simpleName)
                } catch (dbFailure: Exception) {
                    // scheduleRetry DB write failed: the retry_count was not incremented, so the
                    // row would be re-published immediately on the next cycle with no backoff.
                    // Fall back to dead-lettering so the row is resolved and the queue drains.
                    log.error(dbFailure) {
                        "Failed to persist retry schedule for outbox row ${row.id}; moving to dead-letter to prevent unbounded re-publish"
                    }
                    store.moveToDeadLetter(row, Clock.System.now(), e.message ?: e.javaClass.simpleName)
                    invokeDeadLetterCallback(e)
                }
            }
        } catch (e: Exception) {
            if (e is org.apache.kafka.common.errors.RecordTooLargeException) {
                // Log distinctly: this is a configuration issue (broker's max.message.bytes or
                // message.max.bytes), not a permanent data problem. Raising the limit would deliver it.
                log.error(e) {
                    "Outbox row ${row.id} exceeds the broker's record size limit and has been moved to dead-letter. " +
                        "Raising max.message.bytes / max.request.size would allow delivery."
                }
            }
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
            // Clamp the shift distance: `Long shl` masks to the low 6 bits, so a retryCount >= 64
            // would silently wrap to a small exponent instead of saturating at capMs.
            val safeShift = minOf(retryCount, 62)
            val shift = 1L shl safeShift
            // Guard against Long overflow: for baseMs >= 2 and a large shift, baseMs * shift can
            // exceed Long.MAX_VALUE and wrap negative, which would slip past minOf(..., capMs) and
            // yield a next-retry timestamp in the past — a no-backoff busy loop. When the product
            // would exceed capMs, saturate directly to capMs rather than multiplying.
            val raw = if (baseMs > 0 && shift > capMs / baseMs) capMs else minOf(baseMs * shift, capMs)
            val jittered = raw * (0.8 + Math.random() * 0.4)
            return Clock.System.now() + jittered.toLong().milliseconds
        }
    }
}