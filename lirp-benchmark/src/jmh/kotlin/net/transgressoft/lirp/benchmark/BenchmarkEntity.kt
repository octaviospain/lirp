package net.transgressoft.lirp.benchmark

import net.transgressoft.lirp.entity.ReactiveEntityBase

/**
 * Minimal reactive entity for benchmarks.
 *
 * Declared `open` because JMH generates subclasses of `@State` classes,
 * and benchmark state holders may embed this entity directly. Does not
 * depend on KSP, JavaFX, or kotlinx.serialization to keep the benchmark
 * module lightweight.
 */
open class BenchmarkEntity(
    override val id: Int,
    val label: String
) : ReactiveEntityBase<Int, BenchmarkEntity>() {
    override val uniqueId: String get() = "bench-$id"
    var name: String by reactiveProperty(label)

    override fun clone(): BenchmarkEntity = BenchmarkEntity(id, label).also { it.name = name }
}