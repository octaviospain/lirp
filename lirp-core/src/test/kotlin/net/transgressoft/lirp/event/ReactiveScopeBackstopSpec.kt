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

package net.transgressoft.lirp.event

import net.transgressoft.lirp.testing.LogCapture
import net.transgressoft.lirp.testing.ReactiveScopeSerialization
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch

/**
 * Verifies that uncaught exceptions from root coroutines launched on `ReactiveScope.ioScope`
 * and `ReactiveScope.flowScope` are delivered to the backstop and logged at ERROR level,
 * while sibling coroutines on the same supervised scope continue to execute.
 */
class ReactiveScopeBackstopSpec : StringSpec() {

    init {
        extension(ReactiveScopeSerialization)

        "ReactiveScope ioScope logs an uncaught coroutine failure at ERROR and keeps siblings alive" {
            val capture = LogCapture()
            capture.attach("net.transgressoft.lirp.event.ReactiveScope")

            val siblingRan = AtomicBoolean(false)
            val siblingLatch = CountDownLatch(1)

            try {
                // Root launch that throws — should reach the backstop
                ReactiveScope.ioScope.launch {
                    throw RuntimeException("ioScope backstop test exception")
                }

                // Sibling launch — must not be cancelled by the failure above
                ReactiveScope.ioScope.launch {
                    siblingRan.set(true)
                    siblingLatch.countDown()
                }

                siblingLatch.await(5, TimeUnit.SECONDS)

                eventually(5.seconds) {
                    val errorLogs = capture.logs.filter { it.level == "ERROR" }
                    errorLogs.shouldNotBeEmpty()
                    errorLogs.any { it.message.contains("Uncaught coroutine failure") }.shouldBeTrue()
                }

                siblingRan.get() shouldBe true
            } finally {
                capture.detach()
            }
        }

        "ReactiveScope flowScope logs an uncaught coroutine failure at ERROR and keeps siblings alive" {
            val capture = LogCapture()
            capture.attach("net.transgressoft.lirp.event.ReactiveScope")

            val siblingRan = AtomicBoolean(false)
            val siblingLatch = CountDownLatch(1)

            try {
                // Root launch that throws — should reach the backstop
                ReactiveScope.flowScope.launch {
                    throw RuntimeException("flowScope backstop test exception")
                }

                // Sibling launch — must not be cancelled by the failure above
                ReactiveScope.flowScope.launch {
                    siblingRan.set(true)
                    siblingLatch.countDown()
                }

                siblingLatch.await(5, TimeUnit.SECONDS)

                eventually(5.seconds) {
                    val errorLogs = capture.logs.filter { it.level == "ERROR" }
                    errorLogs.shouldNotBeEmpty()
                    errorLogs.any { it.message.contains("Uncaught coroutine failure") }.shouldBeTrue()
                }

                siblingRan.get() shouldBe true
            } finally {
                capture.detach()
            }
        }
    }
}