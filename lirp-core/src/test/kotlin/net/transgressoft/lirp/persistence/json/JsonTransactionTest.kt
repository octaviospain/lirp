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

package net.transgressoft.lirp.persistence.json

import net.transgressoft.lirp.persistence.LirpTransactionException
import net.transgressoft.lirp.persistence.transaction
import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.annotation.DisplayName
import io.kotest.core.spec.style.StringSpec
import io.kotest.engine.spec.tempfile
import io.kotest.matchers.optional.shouldBePresent
import io.kotest.matchers.shouldBe

/**
 * Tests for [JsonFileRepository.commitTransactionBuffer]: verifies that the atomic
 * temp-file+rename write keeps the original JSON file intact on failure and produces
 * a valid file on success.
 *
 * Tests that need to reload from disk close the first repository before opening the second
 * to avoid the single-entity-type registration constraint.
 */
@DisplayName("JsonFileRepository transaction atomic-commit contract")
internal class JsonTransactionTest : StringSpec() {

    val reactive = reactiveScope()

    init {
        "transaction commits to JSON and the file contains the updated entity on reload" {
            val jsonFile = tempfile("json-tx-commit", ".json").also { it.deleteOnExit() }
            val repo = StandardCustomerJsonFileRepository(jsonFile)
            repo.create(1, "Freddie Mercury", "freddie@queen.com")
            reactive.advance()

            transaction(repo) { r ->
                (r.findById(1).get() as StandardCustomer).updateName("Brian May")
            }
            reactive.advance()

            // Close the first repo (flushes any remaining debounce state) before reloading.
            repo.close()

            // Reload from file: the committed value must be persisted.
            val reloaded = StandardCustomerJsonFileRepository(jsonFile)
            try {
                reloaded.findById(1).shouldBePresent {
                    it.name shouldBe "Brian May"
                }
            } finally {
                reloaded.close()
            }
        }

        "transaction failure leaves the original JSON file unchanged" {
            val jsonFile = tempfile("json-tx-rollback", ".json").also { it.deleteOnExit() }
            val initialRepo = StandardCustomerJsonFileRepository(jsonFile)
            initialRepo.create(2, "Roger Taylor", "roger@queen.com")
            reactive.advance()
            // Force flush to disk by closing.
            initialRepo.close()

            val contentBefore = jsonFile.readText()

            val freshRepo = StandardCustomerJsonFileRepository(jsonFile)
            try {
                shouldThrow<LirpTransactionException> {
                    transaction(freshRepo) { r ->
                        (r.findById(2).get() as StandardCustomer).updateName("mutated-before-failure")
                        throw RuntimeException("injected failure")
                    }
                }
                reactive.advance()
            } finally {
                try {
                    freshRepo.close()
                } catch (_: Exception) {
                }
            }

            // Original file must be identical to the pre-transaction content.
            jsonFile.readText() shouldBe contentBefore
        }

        "transaction persists multiple mutations atomically without corruption" {
            val jsonFile = tempfile("json-tx-multi", ".json").also { it.deleteOnExit() }
            val repo = StandardCustomerJsonFileRepository(jsonFile)
            repo.create(10, "John Deacon", "john@queen.com")
            repo.create(11, "Brian May", "brian@queen.com")
            reactive.advance()

            transaction(repo) { r ->
                (r.findById(10).get() as StandardCustomer).updateName("John Deacon — updated")
                (r.findById(11).get() as StandardCustomer).updateName("Brian May — updated")
            }
            reactive.advance()

            repo.close()

            val reloaded = StandardCustomerJsonFileRepository(jsonFile)
            try {
                reloaded.findById(10).shouldBePresent { it.name shouldBe "John Deacon — updated" }
                reloaded.findById(11).shouldBePresent { it.name shouldBe "Brian May — updated" }
            } finally {
                reloaded.close()
            }
        }

        "transaction rollback on failure reverts in-memory entity to pre-block value" {
            val jsonFile = tempfile("json-tx-mem-rollback", ".json").also { it.deleteOnExit() }
            val repo = StandardCustomerJsonFileRepository(jsonFile)

            try {
                repo.create(20, "Mick Jagger", "mick@stones.com")
                reactive.advance()

                shouldThrow<LirpTransactionException> {
                    transaction(repo) { r ->
                        (r.findById(20).get() as StandardCustomer).updateName("mutated-in-block")
                        throw RuntimeException("block throws")
                    }
                }
                reactive.advance()

                // In-memory entity reverted.
                repo.findById(20).shouldBePresent {
                    it.name shouldBe "Mick Jagger"
                }
            } finally {
                try {
                    repo.close()
                } catch (_: Exception) {
                }
            }
        }
    }
}