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

/**
 * Entry-point configuration for the LIRP Kafka integration.
 *
 * Construct via [LirpKafkaConfig.create] and use to obtain a [KafkaEventPublisher].
 * `bootstrapServers` must be a non-blank comma-separated list of `host:port` pairs.
 *
 * This class is [AutoCloseable]: calling [close] releases the underlying broker connection
 * held by the cached [KafkaEventPublisher]. Wrap in a `use { }` block or call [close]
 * explicitly when the config is no longer needed.
 *
 * **Supported store:** only SQL-backed repositories participate in the transactional outbox.
 * [net.transgressoft.lirp.persistence.JsonFileRepository] and
 * [net.transgressoft.lirp.persistence.VolatileRepository] cannot guarantee atomic outbox writes
 * and are explicitly unsupported.
 */
class LirpKafkaConfig private constructor(val bootstrapServers: String) : AutoCloseable {

    private val publisherDelegate = lazy { KafkaEventPublisher(bootstrapServers) }
    private val _publisher: KafkaEventPublisher by publisherDelegate

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
     * The publisher is created lazily on first call and reused on subsequent calls.
     * Its lifecycle is tied to this config — call [close] to release broker connections.
     */
    fun publisher(): KafkaEventPublisher = _publisher

    /** Closes the [KafkaEventPublisher] and releases its broker connections. */
    override fun close() {
        if (publisherDelegate.isInitialized()) _publisher.close()
    }
}