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

import io.github.oshai.kotlinlogging.KotlinLogging
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import java.util.Properties
import java.util.concurrent.ExecutionException

/**
 * Publishes domain event payloads to a Kafka topic via a [KafkaProducer].
 *
 * [publish] is a direct synchronous send (acks=all) that blocks until the broker
 * acknowledges the record. Future releases will drive publish through the transactional
 * outbox relay instead.
 *
 * [bootstrapServers] must be a non-blank comma-separated list of `host:port` pairs;
 * validated at construction.
 */
class KafkaEventPublisher(bootstrapServers: String) : AutoCloseable {

    init {
        require(bootstrapServers.isNotBlank()) { "bootstrapServers must not be blank" }
    }

    private val log = KotlinLogging.logger(javaClass.name)

    private val producer: KafkaProducer<String, ByteArray> =
        KafkaProducer(
            Properties().apply {
                put("bootstrap.servers", bootstrapServers)
                put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
                put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer")
                put("acks", "all")
            }
        )

    /**
     * Sends [value] to [topic] with [key] as the partition key.
     *
     * Blocks until the broker acknowledges the record (acks=all). The underlying Kafka exception
     * is unwrapped from [ExecutionException] so callers can distinguish retriable network faults
     * from permanent failures such as authorization or serialization errors.
     */
    fun publish(topic: String, key: String, value: ByteArray) {
        log.debug { "publish: topic=$topic key=$key bytes=${value.size}" }
        try {
            producer.send(ProducerRecord(topic, key, value)).get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    override fun close() {
        producer.close()
    }
}