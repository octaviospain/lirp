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
import net.transgressoft.lirp.testing.ReactiveScopeSerialization
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression guard for the `subscribeAsync` collector-registration barrier.
 *
 * On the real production `Dispatchers.Default` flow scope (kept in place via
 * [ReactiveScopeSerialization]), the collector coroutine and the bridge drain coroutine start
 * asynchronously and race. An event emitted in the gap between `subscribeAsync` returning and the
 * collector actually subscribing reaches the drain loop's `flow.emit`, but a `MutableSharedFlow`
 * with `replay == 0` and no active collector discards it — the event never reaches the subscriber
 * action. `subscribeAsync` must therefore block until its collector is registered, so every event
 * emitted after the call returns is delivered. Unique-per-emission payloads make a single dropped
 * event fatal to the count assertion.
 */
class SubscribeAsyncDeliveryBarrierTest : StringSpec({
    extension(ReactiveScopeSerialization)

    "subscribeAsync delivers every event emitted immediately after it returns across many cycles" {
        // Many subscribe→emit cycles: any single collector-startup drop fails the strict count.
        val cycles = 500
        repeat(cycles) {
            val publisher =
                FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("barrier-$it").apply {
                    activateEvents(CREATE)
                }
            val received = AtomicInteger(0)
            val subscription = publisher.subscribeAsync(CREATE) { received.incrementAndGet() }
            try {
                // Emitted synchronously right after subscribeAsync returns — the exact race window.
                publisher.emitAsync(Create(TestEntity(UUID.randomUUID().toString())))

                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (received.get() < 1 && System.nanoTime() < deadline) Thread.sleep(1)
                received.get() shouldBe 1
            } finally {
                subscription.cancel()
                publisher.close()
            }
        }
    }
})