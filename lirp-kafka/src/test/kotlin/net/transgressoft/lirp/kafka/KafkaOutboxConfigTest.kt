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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * Unit tests verifying that [KafkaOutboxConfig] exposes the documented defaults and rejects
 * invalid construction arguments.
 */
@DisplayName("KafkaOutboxConfigTest")
internal class KafkaOutboxConfigTest : StringSpec() {

    init {
        "KafkaOutboxConfigTest DEFAULT has pollIntervalMs of 500" {
            KafkaOutboxConfig.DEFAULT.pollIntervalMs shouldBe 500L
        }

        "KafkaOutboxConfigTest DEFAULT has batchSize of 100" {
            KafkaOutboxConfig.DEFAULT.batchSize shouldBe 100
        }

        "KafkaOutboxConfigTest DEFAULT has maxRetries of 5" {
            KafkaOutboxConfig.DEFAULT.maxRetries shouldBe 5
        }

        "KafkaOutboxConfigTest construction rejects pollIntervalMs of zero" {
            shouldThrow<IllegalArgumentException> {
                KafkaOutboxConfig(pollIntervalMs = 0)
            }
        }

        "KafkaOutboxConfigTest construction rejects batchSize of zero" {
            shouldThrow<IllegalArgumentException> {
                KafkaOutboxConfig(batchSize = 0)
            }
        }

        "KafkaOutboxConfigTest construction rejects negative maxRetries" {
            shouldThrow<IllegalArgumentException> {
                KafkaOutboxConfig(maxRetries = -1)
            }
        }

        "KafkaOutboxConfigTest construction rejects retryMaxDelayMs less than retryBaseDelayMs" {
            shouldThrow<IllegalArgumentException> {
                KafkaOutboxConfig(retryBaseDelayMs = 5000L, retryMaxDelayMs = 1000L)
            }
        }

        "KafkaOutboxConfigTest LirpKafkaConfig.create rejects blank bootstrapServers" {
            shouldThrow<IllegalArgumentException> {
                LirpKafkaConfig.create("")
            }
        }
    }
}