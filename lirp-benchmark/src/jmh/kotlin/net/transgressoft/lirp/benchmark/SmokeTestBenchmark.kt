package net.transgressoft.lirp.benchmark

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import java.util.concurrent.TimeUnit

/**
 * Smoke test to verify JMH annotation processing and benchmark execution work correctly.
 * Run with: gradle :lirp-benchmark:jmh -Pjmh.includes='SmokeTestBenchmark'
 */
@State(Scope.Benchmark)
@Fork(1)
@Warmup(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 1, time = 1, timeUnit = TimeUnit.SECONDS)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
open class SmokeTestBenchmark {

    @Benchmark
    fun entityCreation(bh: Blackhole) {
        bh.consume(BenchmarkEntity(1, "test"))
    }
}