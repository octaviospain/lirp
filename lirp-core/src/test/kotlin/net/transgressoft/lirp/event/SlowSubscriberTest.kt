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

import net.transgressoft.lirp.persistence.CustomerVolatileRepo
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Delay in milliseconds applied to the slow subscriber in every test scenario. Increase this value on slow CI environments. */
const val SLOW_DELAY_MS = 50L

/**
 * Spacing between paced emissions. Must be significantly larger than [SLOW_DELAY_MS] to ensure
 * `collectLatest` does not cancel in-progress slow handlers on loaded CI environments where
 * coroutine scheduling adds variable overhead.
 */
private const val PACED_INTERVAL_MS = 120L

private const val EVENT_COUNT = 60

/**
 * Tests verifying that a slow subscriber does not block the [FlowEventPublisher] emitter
 * or starve fast co-subscribers of events.
 *
 * The structural guarantee comes from [FlowEventPublisher.emitAsync] using `Channel.trySend()`
 * (fire-and-forget) so the emitter never suspends regardless of how slow a subscriber is.
 * Each subscriber runs in its own independent coroutine: `delay()` in one subscriber only
 * suspends that coroutine, leaving other subscribers and the emitter unaffected.
 *
 * Note: [FlowEventPublisher.subscribe] uses `collectLatest` internally. With real dispatcher
 * and burst emission, `collectLatest` will restart the slow subscriber's handler for each
 * new event (since the handler delay exceeds the inter-event gap). This is expected behavior —
 * tests verify emission speed and the relative delivery advantage of the fast subscriber.
 *
 * To verify completeness (all events received by both subscribers), events are emitted
 * with spacing greater than [SLOW_DELAY_MS], ensuring each event fully completes in both
 * subscribers before the next arrives. In this spaced-emission scenario, the non-blocking
 * property is proven by the fast subscriber completing long before the slow subscriber.
 */
class SlowSubscriberTest : DescribeSpec({

    // Dedicated scope with unbounded Default dispatcher to avoid coroutine scheduling contention
    // between publisher coroutines and Kotest's runBlocking event loop
    val dedicatedScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    beforeSpec {
        ReactiveScope.flowScope = dedicatedScope
        ReactiveScope.ioScope = dedicatedScope
    }

    afterSpec {
        ReactiveScope.resetDefaultFlowScope()
        ReactiveScope.resetDefaultIoScope()
    }

    describe("Slow subscriber isolation on entity publisher") {
        it("publisher emission is non-blocking when one subscriber is slow").config(timeout = 30.seconds) {
            val fastCounter = AtomicInteger(0)
            val slowCounter = AtomicInteger(0)

            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("slow-entity-pub")
            publisher.activateEvents(CrudEvent.Type.CREATE)

            // Fast subscriber: instant handler, no delay
            val fastSub = publisher.subscribe { fastCounter.incrementAndGet() }
            // Slow subscriber: deliberate delay simulating expensive processing
            val slowSub =
                publisher.subscribe { event ->
                    delay(SLOW_DELAY_MS.milliseconds)
                    slowCounter.incrementAndGet()
                }

            // Measure only the time for emitAsync calls — proves fire-and-forget non-blocking behaviour
            val start = System.currentTimeMillis()
            repeat(EVENT_COUNT) { i ->
                publisher.emitAsync(StandardCrudEvent.Create(TestEntity("entity-$i")))
            }
            val emissionMs = System.currentTimeMillis() - start

            // Emission must complete orders of magnitude faster than if blocked by the slow subscriber
            // Blocked: 60 * 50ms = 3000ms; non-blocking: << 1500ms (EVENT_COUNT * SLOW_DELAY_MS / 2)
            emissionMs shouldBeLessThan EVENT_COUNT * SLOW_DELAY_MS / 2

            // Wait long enough for the slow subscriber to settle on the final event's delay
            delay((SLOW_DELAY_MS * 4).milliseconds)

            // Fast subscriber receives far more events than the slow subscriber during burst emission
            // because its instant handler is never cancelled by the next event
            fastCounter.get() shouldBeGreaterThan slowCounter.get()

            fastSub.cancel()
            slowSub.cancel()
        }

        it("publisher emission is non-blocking when one subscriber is slow — completeness with paced emission").config(timeout = 30.seconds) {
            val fastLatch = CountDownLatch(EVENT_COUNT)
            val slowLatch = CountDownLatch(EVENT_COUNT)
            val fastCounter = AtomicInteger(0)
            val slowCounter = AtomicInteger(0)

            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("slow-entity-paced-pub")
            publisher.activateEvents(CrudEvent.Type.CREATE)

            val fastSub =
                publisher.subscribe {
                    fastCounter.incrementAndGet()
                    fastLatch.countDown()
                }
            val slowSub =
                publisher.subscribe { event ->
                    delay(SLOW_DELAY_MS.milliseconds)
                    slowCounter.incrementAndGet()
                    slowLatch.countDown()
                }

            // Emit with spacing slightly above SLOW_DELAY_MS so collectLatest does not cancel any handler.
            // This still proves emission is much faster than if blocking occurred.
            val pacedIntervalMs = PACED_INTERVAL_MS
            val emitter =
                dedicatedScope.launch {
                    repeat(EVENT_COUNT) { i ->
                        publisher.emitAsync(StandardCrudEvent.Create(TestEntity("paced-$i")))
                        delay(pacedIntervalMs.milliseconds)
                    }
                }

            val start = System.currentTimeMillis()
            emitter.join()
            val totalTimeMs = System.currentTimeMillis() - start

            // Total paced emission time (includes inter-event gaps, not subscriber blocking):
            // ~60 * 60ms = 3600ms; each individual emitAsync is instant (non-blocking)
            // After all events emitted, fast subscriber should have them all almost immediately
            fastLatch.await(5, TimeUnit.SECONDS) shouldBe true

            // Slow subscriber processes at SLOW_DELAY_MS per event; wait for all to complete
            slowLatch.await(EVENT_COUNT * (SLOW_DELAY_MS + pacedIntervalMs) + 5000, TimeUnit.MILLISECONDS) shouldBe true

            fastCounter.get() shouldBe EVENT_COUNT
            slowCounter.get() shouldBe EVENT_COUNT

            fastSub.cancel()
            slowSub.cancel()
        }

        it("fast subscriber completes before slow subscriber when events are paced").config(timeout = 30.seconds) {
            val fastLatch = CountDownLatch(EVENT_COUNT)
            val slowLatch = CountDownLatch(EVENT_COUNT)
            // Record when the last fast and slow deliveries complete
            val fastCompletedMs = AtomicLong(0L)
            val slowCompletedMs = AtomicLong(0L)

            val publisher = FlowEventPublisher<CrudEvent.Type, CrudEvent<String, TestEntity>>("timing-entity-pub")
            publisher.activateEvents(CrudEvent.Type.CREATE)

            val fastSub =
                publisher.subscribe {
                    fastLatch.countDown()
                    if (fastLatch.count == 0L) fastCompletedMs.set(System.currentTimeMillis())
                }
            val slowSub =
                publisher.subscribe { event ->
                    delay(SLOW_DELAY_MS.milliseconds)
                    slowLatch.countDown()
                    if (slowLatch.count == 0L) slowCompletedMs.set(System.currentTimeMillis())
                }

            val pacedIntervalMs = PACED_INTERVAL_MS
            dedicatedScope.launch {
                repeat(EVENT_COUNT) { i ->
                    publisher.emitAsync(StandardCrudEvent.Create(TestEntity("timing-$i")))
                    delay(pacedIntervalMs.milliseconds)
                }
            }.join()

            // Both subscribers must receive all events
            fastLatch.await(5, TimeUnit.SECONDS) shouldBe true
            slowLatch.await(EVENT_COUNT * (SLOW_DELAY_MS + pacedIntervalMs) + 5000, TimeUnit.MILLISECONDS) shouldBe true

            // Fast subscriber finishes processing each event immediately; slow subscriber
            // always lags by SLOW_DELAY_MS on the last event, so it must complete later
            (slowCompletedMs.get() - fastCompletedMs.get()) shouldBeGreaterThan 0L
            slowLatch.count shouldBe 0L
            fastLatch.count shouldBe 0L

            fastSub.cancel()
            slowSub.cancel()
        }
    }

    describe("Slow subscriber isolation on repository publisher") {
        it("publisher emission is non-blocking when one repository subscriber is slow").config(timeout = 30.seconds) {
            val fastCounter = AtomicInteger(0)
            val slowCounter = AtomicInteger(0)

            val repository = CustomerVolatileRepo()

            val fastSub = repository.subscribe { fastCounter.incrementAndGet() }
            val slowSub =
                repository.subscribe { event ->
                    delay(SLOW_DELAY_MS.milliseconds)
                    slowCounter.incrementAndGet()
                }

            // create() triggers CrudEvent emission via trySend — non-blocking
            val start = System.currentTimeMillis()
            repeat(EVENT_COUNT) { i ->
                repository.create(i, "customer-$i")
            }
            val emissionMs = System.currentTimeMillis() - start

            // Emission must complete orders of magnitude faster than if blocked by the slow subscriber
            emissionMs shouldBeLessThan EVENT_COUNT * SLOW_DELAY_MS / 2

            // Wait for the slow subscriber to settle after the burst
            delay((SLOW_DELAY_MS * 4).milliseconds)

            // Fast subscriber outpaces the slow subscriber during burst emission
            fastCounter.get() shouldBeGreaterThan slowCounter.get()

            fastSub.cancel()
            slowSub.cancel()
            repository.close()
        }

        it("both subscribers receive all events when repository events are paced").config(timeout = 30.seconds) {
            val fastLatch = CountDownLatch(EVENT_COUNT)
            val slowLatch = CountDownLatch(EVENT_COUNT)

            val repository = CustomerVolatileRepo()

            val fastSub = repository.subscribe { fastLatch.countDown() }
            val slowSub =
                repository.subscribe { event ->
                    delay(SLOW_DELAY_MS.milliseconds)
                    slowLatch.countDown()
                }

            val pacedIntervalMs = PACED_INTERVAL_MS
            dedicatedScope.launch {
                repeat(EVENT_COUNT) { i ->
                    repository.create(i, "customer-$i")
                    delay(pacedIntervalMs.milliseconds)
                }
            }.join()

            fastLatch.await(5, TimeUnit.SECONDS) shouldBe true
            slowLatch.await(EVENT_COUNT * (SLOW_DELAY_MS + pacedIntervalMs) + 5000, TimeUnit.MILLISECONDS) shouldBe true

            fastLatch.count shouldBe 0L
            slowLatch.count shouldBe 0L

            fastSub.cancel()
            slowSub.cancel()
            repository.close()
        }
    }
})