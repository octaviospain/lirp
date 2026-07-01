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

import net.transgressoft.lirp.persistence.sql.PostgresContainerSupport
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.jetbrains.exposed.v1.jdbc.Database
import org.junit.jupiter.api.DisplayName
import java.time.Duration
import java.util.Properties
import kotlin.time.Duration.Companion.seconds

@DisplayName("KafkaPublishSkeleton Integration")
internal class KafkaPublishSkeletonIT : StringSpec({

    "KafkaEventPublisher publishes a record that a consumer receives" {
        val topic = "lirp-skeleton-test"
        val bootstrapServers = KafkaContainerSupport.bootstrapServers

        val consumerProps =
            Properties().apply {
                put("bootstrap.servers", bootstrapServers)
                put("group.id", "lirp-skeleton-it-${System.currentTimeMillis()}")
                put("key.deserializer", StringDeserializer::class.java.name)
                put("value.deserializer", ByteArrayDeserializer::class.java.name)
                put("auto.offset.reset", "earliest")
            }

        // This test exercises the direct synchronous publish path only, so it constructs the
        // publisher directly rather than starting the relay — starting the relay would launch a
        // poll loop against an outbox table this test never creates.
        val dataSource = PostgresContainerSupport.buildDataSource()
        val db = Database.connect(dataSource)
        val publisher = KafkaEventPublisher<Nothing, Nothing>("lirp-skeleton", bootstrapServers, db)
        try {
            publisher.publish(topic, "aggregate-1", "skeleton-payload".toByteArray())
        } finally {
            publisher.close()
            dataSource.close()
        }

        KafkaConsumer<String, ByteArray>(consumerProps).use { consumer ->
            consumer.subscribe(listOf(topic))
            eventually(10.seconds) {
                val records = consumer.poll(Duration.ofMillis(200))
                records.count() shouldBe 1
                records.first().value().decodeToString() shouldBe "skeleton-payload"
            }
        }
    }
})