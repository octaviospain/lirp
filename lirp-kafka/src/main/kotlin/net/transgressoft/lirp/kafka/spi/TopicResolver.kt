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

package net.transgressoft.lirp.kafka.spi

/**
 * Resolves the Kafka topic name for a given [LirpEventEnvelope].
 *
 * Pass a custom implementation to
 * [net.transgressoft.lirp.kafka.LirpKafkaConfig.startRelay] to override the default
 * per-aggregate-type routing. Because this is a `fun interface`, a Kotlin lambda is accepted
 * directly at the call site:
 *
 * ```kotlin
 * lirpConfig.startRelay(dataSource, topicResolver = TopicResolver { env -> "custom.${env.aggregateType}" })
 * ```
 *
 * The default behaviour is provided by [DefaultTopicResolver], which returns
 * `"${aggregateType}.events"`.
 */
fun interface TopicResolver {

    /**
     * Returns the Kafka topic name for the given [envelope].
     *
     * The returned string must be a valid Kafka topic name (non-blank).
     *
     * @param envelope The event envelope to resolve a topic for.
     */
    fun resolve(envelope: LirpEventEnvelope): String
}

/**
 * Default [TopicResolver] that routes each event to a topic named `"${aggregateType}.events"`.
 *
 * This matches the default routing used by the outbox relay prior to the introduction of the
 * pluggable [TopicResolver] SPI, and is the recommended starting point for new deployments.
 * Consumers that need per-event-type or per-aggregate-instance routing can supply a custom
 * [TopicResolver] lambda at relay startup.
 */
internal object DefaultTopicResolver : TopicResolver {
    override fun resolve(envelope: LirpEventEnvelope): String = "${envelope.aggregateType}.events"
}