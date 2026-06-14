package net.transgressoft.lirp.benchmark

import net.transgressoft.lirp.event.LirpEventSubscription
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * Measures the per-caller-thread cost of a single reactive property assignment fanning out to
 * [subscriberCount] registered subscribers.
 *
 * **What is measured.** Each [mutateProperty] invocation performs one `reactiveProperty` setter
 * call on a [BenchmarkEntity] with [subscriberCount] active subscribers. [Mode.SampleTime]
 * captures p50/p95/p99 latency percentiles in nanoseconds across the full parameter matrix.
 *
 * **What is not measured.** JVM startup, class loading, lazy-publisher initialization, and the
 * one-time coroutine-channel setup. The [setup] method performs a warm-up mutation before
 * measurement begins to ensure those costs are excluded.
 *
 * **Sync vs async interpretation asymmetry.**
 * - `transport=sync`: [Mode.SampleTime] includes the full synchronous callback dispatch cost.
 *   All [subscriberCount] callbacks execute inline on the caller thread before the setter returns,
 *   so the measured latency grows with subscriber count.
 * - `transport=async`: [Mode.SampleTime] measures only the time to enqueue the event into the
 *   async channel. Callback execution runs in background coroutines and is outside the measurement
 *   window. The async numbers therefore reflect channel-send overhead, not end-to-end dispatch cost.
 *
 * **Zero-subscriber row.** When [subscriberCount] is 0, no callbacks are registered. The publisher
 * early-returns when it detects no subscribers, so the 0-subscriber measurement isolates the
 * setter-plus-emit-guard overhead for both transports.
 *
 * **Dead-code elimination guard.** Subscriber callbacks store the event's hashCode into
 * [callbackSink] (a `@Volatile` field). The benchmark method consumes [callbackSink] via
 * [Blackhole.consume], making the field observably live to the JIT without injecting a
 * [Blackhole] into the lambda. Do NOT use `Blackhole.consumeCPU(event.hashCode().toLong())`
 * inside callbacks: `consumeCPU` spins for N tokens, and a large hashCode value produces an
 * unbounded CPU spin on the calling thread, which completely dominates sync-path measurements.
 *
 * **GC profiler.** To collect per-operation allocation in bytes, run with:
 * ```
 * gradle :lirp-benchmark:jmh -Pjmh.includes=MutationLatency -Pjmh.profilers=gc
 * ```
 * The `gc.alloc.rate.norm` metric shows bytes allocated per operation. A small
 * [net.transgressoft.lirp.event.PropertyChanged] event is expected per mutation; the number
 * must not scale with entity-graph size.
 */
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 10, time = 1, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
open class MutationLatencyBenchmark {

    @Param("0", "1", "5", "10")
    var subscriberCount: Int = 0

    @Param("sync", "async")
    var transport: String = "sync"

    lateinit var entity: BenchmarkEntity

    // Subscription handles prevent GC of async coroutine Jobs before @TearDown
    val subscriptions = mutableListOf<LirpEventSubscription<*, *, *>>()

    /**
     * Side-channel accumulator used in subscriber callbacks to prevent JIT dead-code elimination
     * of the callback body without injecting Blackhole into the lambda.
     *
     * Storing a field derived from the event forces the JIT to treat the callback as observable.
     * The accumulator is read in [mutateProperty] via [Blackhole.consume] so its value is not
     * optimised away end-to-end.
     */
    @Volatile
    var callbackSink: Long = 0L

    @Setup(Level.Trial)
    fun setup() {
        entity = BenchmarkEntity(1, "initial", 1)
        repeat(subscriberCount) {
            when (transport) {
                "sync" -> subscriptions += entity.subscribe { event -> callbackSink = event.hashCode().toLong() }
                "async" -> subscriptions += entity.subscribeAsync { event -> callbackSink = event.hashCode().toLong() }
                else -> error("Unknown transport '$transport'; expected 'sync' or 'async'")
            }
        }
        // Warm the lazy publisher and the async Channel/SharedFlow bridge before measurement starts
        entity.name = "warmup"
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        subscriptions.forEach { it.cancel() }
        entity.close()
    }

    /**
     * Measures the per-caller-thread cost of a single reactive property assignment
     * fanning out to [subscriberCount] registered subscribers via the [transport] path.
     *
     * For the sync path, [Mode.SampleTime] includes the full callback dispatch time on the
     * caller thread. For the async path, it measures only the time to enqueue the event
     * into the channel — callback execution runs asynchronously and is not included.
     */
    @Benchmark
    fun mutateProperty(bh: Blackhole) {
        entity.name = if (entity.name == "a") "b" else "a"
        bh.consume(entity.name)
        bh.consume(callbackSink)
    }
}