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

package net.transgressoft.lirp.kafka

/**
 * Configuration knobs for the outbox relay process.
 *
 * Pass an instance to the relay when starting it. The relay requires the full outbox schema
 * including the `next_retry_at` column; use [KafkaOutboxConfig.DEFAULT] unless you need to
 * tune the delivery characteristics for a specific deployment.
 *
 * **Defaults:** poll interval 500 ms, batch size 100, max retries 5. Invalid values (non-positive
 * poll interval or batch size, negative max retries, or max delay less than base delay) are
 * rejected at construction with an [IllegalArgumentException].
 *
 * Operators tune [pollIntervalMs] and [batchSize] to balance delivery latency against
 * database load: a lower poll interval reduces end-to-end latency but increases the number of
 * DB round trips; a larger batch size amortises transaction overhead at the cost of longer
 * individual poll cycles.
 *
 * @property pollIntervalMs Milliseconds the relay waits between poll cycles. A shorter interval
 *   reduces delivery latency; a longer interval reduces database load. Must be positive.
 * @property batchSize Maximum number of outbox rows fetched and processed in a single poll
 *   cycle. Rows exceeding this limit are picked up in subsequent cycles. Must be positive.
 * @property maxRetries Maximum number of relay delivery attempts before a row is moved to the
 *   dead-letter table. A value of 0 moves failing rows to dead-letter on the first failure.
 *   Must be non-negative.
 * @property retryBaseDelayMs Base delay in milliseconds for exponential backoff after a
 *   retriable failure. The first retry is scheduled approximately this far into the future.
 *   Must be positive and not greater than [retryMaxDelayMs].
 * @property retryMaxDelayMs Upper bound in milliseconds for the exponential backoff delay. The
 *   computed retry delay is capped at this value regardless of the attempt count. Must be
 *   greater than or equal to [retryBaseDelayMs].
 * @property sqliteBusyTimeoutMs Milliseconds SQLite will wait to acquire a write lock before
 *   returning `SQLITE_BUSY` when a concurrent entity-save INSERT contends with the relay's
 *   write transaction. Applied pool-wide via `PRAGMA busy_timeout` on every HikariCP connection
 *   at relay startup; has no effect on non-SQLite data sources. SQLite serialises writers, so
 *   a single-relay deployment is the only supported configuration — this knob provides a
 *   retry window for capture INSERTs, not concurrent relay instances. Must be non-negative.
 */
data class KafkaOutboxConfig(
    val pollIntervalMs: Long = 500L,
    val batchSize: Int = 100,
    val maxRetries: Int = 5,
    val retryBaseDelayMs: Long = 1_000L,
    val retryMaxDelayMs: Long = 60_000L,
    val sqliteBusyTimeoutMs: Long = 3_000L
) {
    init {
        require(pollIntervalMs > 0) { "pollIntervalMs must be positive, was $pollIntervalMs" }
        require(batchSize > 0) { "batchSize must be positive, was $batchSize" }
        require(maxRetries >= 0) { "maxRetries must be non-negative, was $maxRetries" }
        require(retryBaseDelayMs > 0) { "retryBaseDelayMs must be positive, was $retryBaseDelayMs" }
        require(retryMaxDelayMs >= retryBaseDelayMs) {
            "retryMaxDelayMs ($retryMaxDelayMs) must be >= retryBaseDelayMs ($retryBaseDelayMs)"
        }
        require(sqliteBusyTimeoutMs >= 0) { "sqliteBusyTimeoutMs must be non-negative, was $sqliteBusyTimeoutMs" }
    }

    companion object {
        /** Default configuration suitable for most deployments. */
        val DEFAULT = KafkaOutboxConfig()
    }
}