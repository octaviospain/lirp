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

package net.transgressoft.fleet.e2e

import org.testcontainers.kafka.ConfluentKafkaContainer

/**
 * Shared Testcontainers Kafka broker and bootstrap-servers accessor for the fleet e2e integration tests.
 *
 * The container is started lazily and thread-safely on first access and reused across all
 * integration test classes within the same JVM process. Uses KRaft mode (no ZooKeeper) via
 * [ConfluentKafkaContainer] default behaviour in Testcontainers 2.x.
 *
 * A JVM shutdown hook stops the container explicitly so it is cleaned up even when Ryuk is
 * disabled (e.g. `TESTCONTAINERS_RYUK_DISABLED=true`) or the JVM is terminated abnormally.
 */
internal object KafkaContainerSupport {
    private val container: ConfluentKafkaContainer by lazy {
        ConfluentKafkaContainer("confluentinc/cp-kafka:7.8.0").apply {
            start()
            Runtime.getRuntime().addShutdownHook(Thread { stop() })
        }
    }

    /** Comma-separated `host:port` bootstrap servers for the running container. */
    val bootstrapServers: String get() = container.bootstrapServers
}