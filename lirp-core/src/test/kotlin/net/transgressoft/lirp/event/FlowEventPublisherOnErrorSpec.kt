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

import net.transgressoft.lirp.event.CrudEvent.Type.CREATE
import net.transgressoft.lirp.event.StandardCrudEvent.Create
import net.transgressoft.lirp.testing.reactiveScope
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests for the per-subscription [LirpErrorHandler] on [FlowEventPublisher.subscribeAsync].
 *
 * These tests intentionally exercise throwing subscriber actions. The test scope has
 * `failOnUncaughtExceptions = false` because the "without onError" test deliberately lets an
 * exception propagate to the scope's uncaught-exception handler (the log-only fallback path).
 */
class FlowEventPublisherOnErrorSpec : DescribeSpec({

    val reactive = reactiveScope(failOnUncaughtExceptions = false)

    describe("subscribeAsync per-subscription onError handler") {

        it("subscribeAsync with onError fires the per-subscription handler when the action throws") {
            val publisher =
                FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>(
                    "per-sub-onerror-test"
                ).apply { activateEvents(CREATE) }

            val handlerInvocations = CopyOnWriteArrayList<Pair<Throwable, LirpErrorContext>>()
            val latch = CountDownLatch(1)
            val perSubHandler =
                LirpErrorHandler { t, ctx ->
                    handlerInvocations.add(t to ctx)
                    latch.countDown()
                }

            publisher.subscribeAsync(
                action = { throw RuntimeException("subscriber action failure") },
                onError = perSubHandler
            )

            publisher.emitAsync(Create(TestEntity("entity-1")))
            reactive.advance()

            latch.await(5, TimeUnit.SECONDS) shouldBe true

            handlerInvocations.size shouldBe 1
            val (_, ctx) = handlerInvocations.single()
            ctx.operation shouldBe LirpOperation.EMIT
            ctx.repository shouldBe "per-sub-onerror-test"
        }

        it("subscribeAsync with onError does not consult any publisher-level handler") {
            val publisherLevelInvocations = AtomicInteger(0)
            val publisherHandler = LirpErrorHandler { _, _ -> publisherLevelInvocations.incrementAndGet() }

            val publisher =
                FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>(
                    "independence-test",
                    onError = publisherHandler
                ).apply { activateEvents(CREATE) }

            val perSubHandlerFired = AtomicInteger(0)
            val latch = CountDownLatch(1)
            val perSubHandler =
                LirpErrorHandler { _, _ ->
                    perSubHandlerFired.incrementAndGet()
                    latch.countDown()
                }

            publisher.subscribeAsync(
                action = { throw RuntimeException("per-sub failure") },
                onError = perSubHandler
            )

            publisher.emitAsync(Create(TestEntity("entity-independence")))
            reactive.advance()

            latch.await(5, TimeUnit.SECONDS) shouldBe true

            // Per-subscription handler fires
            perSubHandlerFired.get() shouldBe 1
            // Publisher-level handler is not consulted for per-subscription failures
            publisherLevelInvocations.get() shouldBe 0
        }

        it("subscribeAsync without onError keeps log-only behavior — publisher-level handler is not consulted") {
            val publisherLevelInvocations = AtomicInteger(0)
            val publisherHandler = LirpErrorHandler { _, _ -> publisherLevelInvocations.incrementAndGet() }

            val publisher =
                FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>(
                    "no-sub-handler-test",
                    onError = publisherHandler
                ).apply { activateEvents(CREATE) }

            // Subscribe without a per-subscription onError — exception goes to the scope backstop (log-only)
            publisher.subscribeAsync { throw RuntimeException("action failure") }

            publisher.emitAsync(Create(TestEntity("entity-no-handler")))
            reactive.advance()

            // The publisher-level handler must not be invoked for single-arg subscribeAsync failures
            publisherLevelInvocations.get() shouldBe 0
        }
    }
})