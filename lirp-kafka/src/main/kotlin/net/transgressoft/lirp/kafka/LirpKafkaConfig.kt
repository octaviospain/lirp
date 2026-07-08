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

package net.transgressoft.lirp.kafka

import net.transgressoft.lirp.event.LirpErrorHandler
import net.transgressoft.lirp.kafka.outbox.OutboxRelay
import net.transgressoft.lirp.kafka.outbox.SqlOutboxStore
import net.transgressoft.lirp.kafka.spi.CloudEventsBinarySerializer
import net.transgressoft.lirp.kafka.spi.DefaultTopicResolver
import net.transgressoft.lirp.kafka.spi.LirpEventSerializer
import net.transgressoft.lirp.kafka.spi.TopicResolver
import org.jetbrains.exposed.v1.jdbc.Database
import javax.sql.DataSource

/**
 * Entry-point configuration for the LIRP Kafka integration.
 *
 * Construct via [LirpKafkaConfig.create] and use to obtain a [KafkaEventPublisher] or to start
 * the outbox relay. `bootstrapServers` must be a non-blank comma-separated list of `host:port`
 * pairs.
 *
 * This class is [AutoCloseable]: calling [close] stops the relay (if running) and releases the
 * underlying broker connection held by the cached [KafkaEventPublisher]. Wrap in a `use { }`
 * block or call [close] explicitly when the config is no longer needed.
 *
 * **Relay lifecycle:** call [startRelay] with a [DataSource] to begin background outbox
 * delivery. The relay polls the outbox table, publishes each row via the [KafkaEventPublisher],
 * and marks it as sent only after the broker acknowledges. Call [stopRelay] to stop the relay
 * without closing the publisher, or [close] to stop both.
 *
 * **Supported store:** only SQL-backed repositories participate in the transactional outbox.
 * [net.transgressoft.lirp.persistence.JsonFileRepository] and
 * [net.transgressoft.lirp.persistence.VolatileRepository] cannot guarantee atomic outbox writes
 * and are explicitly unsupported.
 */
class LirpKafkaConfig private constructor(val bootstrapServers: String) : AutoCloseable {

    private var _publisher: KafkaEventPublisher<*, *>? = null
    private var relay: OutboxRelay? = null

    companion object {
        /**
         * Creates a [LirpKafkaConfig] with the given [bootstrapServers].
         *
         * @throws IllegalArgumentException if [bootstrapServers] is blank.
         */
        fun create(bootstrapServers: String): LirpKafkaConfig {
            require(bootstrapServers.isNotBlank()) { "bootstrapServers must not be blank" }
            return LirpKafkaConfig(bootstrapServers)
        }
    }

    /**
     * Returns the [KafkaEventPublisher] owned by this config.
     *
     * The publisher is initialized when [startRelay] is called and reused on subsequent calls.
     * Its lifecycle is tied to this config — call [close] to release broker connections.
     *
     * @throws IllegalStateException if called before [startRelay]
     */
    fun publisher(): KafkaEventPublisher<*, *> = checkNotNull(_publisher) { "Publisher not initialized — call startRelay first" }

    /**
     * Starts the outbox relay against the given [dataSource].
     *
     * The relay connects to the database, creates the dead-letter table if it does not exist,
     * and begins polling the outbox on a background coroutine. At most one relay per
     * [LirpKafkaConfig] instance is supported; calling [startRelay] when a relay is already
     * running throws [IllegalStateException].
     *
     * @param dataSource JDBC data source for the outbox and dead-letter tables.
     * @param config Relay behaviour knobs — poll interval, batch size, retry limits, backoff.
     * @param producerConfig Optional producer properties overlaid on the safe defaults
     *   (`delivery.timeout.ms=30000`, `request.timeout.ms=10000`, `max.block.ms=10000`). These
     *   defaults bound the window the relay holds a HikariCP connection open while waiting for
     *   broker acknowledgement; pass overrides here to tighten or relax the bounds.
     * @param serializer Strategy for serializing each [net.transgressoft.lirp.kafka.spi.LirpEventEnvelope]
     *   to wire bytes and CloudEvents `ce_*` headers before publishing. Defaults to
     *   [CloudEventsBinarySerializer].
     * @param topicResolver Strategy for resolving the Kafka topic name from each
     *   [net.transgressoft.lirp.kafka.spi.LirpEventEnvelope]. Defaults to [DefaultTopicResolver],
     *   which returns `"${aggregateType}.events"`.
     * @param onDeadLetter Optional callback invoked when a row is moved to the dead-letter table.
     */
    @Synchronized
    fun startRelay(
        dataSource: DataSource,
        config: KafkaOutboxConfig = KafkaOutboxConfig.DEFAULT,
        producerConfig: Map<String, String> = emptyMap(),
        serializer: LirpEventSerializer = CloudEventsBinarySerializer(),
        topicResolver: TopicResolver = DefaultTopicResolver,
        onDeadLetter: LirpErrorHandler? = null
    ) {
        check(relay == null) { "Relay is already running; call stopRelay() first" }
        val db = Database.connect(dataSource)
        // Reuse the cached publisher across relay restarts so a prior stopRelay() does not leave the
        // previous producer/broker connection open when a new relay is started.
        // Pass dataSource explicitly so the publisher registers the db→dataSource mapping used
        // by emitAsync to detect an ambient transaction on a different Database wrapping the same pool.
        val publisher =
            _publisher
                ?: KafkaEventPublisher<Nothing, Nothing>(
                    "lirp-kafka", bootstrapServers, db,
                    SqlOutboxStore(db), producerConfig, dataSource = dataSource
                ).also { _publisher = it }
        relay = OutboxRelay(db, publisher, config, serializer, topicResolver, onDeadLetter).also { it.start() }
    }

    /** Stops the relay without closing the [KafkaEventPublisher]. */
    @Synchronized
    fun stopRelay() {
        relay?.stop()
        relay = null
    }

    /** Stops the relay (if running) and closes the [KafkaEventPublisher]. */
    @Synchronized
    override fun close() {
        relay?.stop()
        relay = null
        _publisher?.close()
        _publisher = null
    }
}