package net.transgressoft.lirp.benchmark

import net.transgressoft.lirp.entity.ReactiveEntityBase
import net.transgressoft.lirp.persistence.Indexed

/**
 * Minimal reactive entity for benchmarks.
 *
 * Declared `open` because JMH generates subclasses of `@State` classes,
 * and benchmark state holders may embed this entity directly. Does not
 * depend on JavaFX or kotlinx.serialization to keep the benchmark module
 * lightweight; KSP runs on the JMH source set so the `@Indexed` annotations
 * on [label] (hash bucket) and [age] (sorted bucket) yield a generated
 * `BenchmarkEntity_LirpIndexAccessor` at compile time.
 */
open class BenchmarkEntity(
    override val id: Int,
    @Indexed val label: String,
    @Indexed(sorted = true) val age: Int
) : ReactiveEntityBase<Int, BenchmarkEntity>() {
    override val uniqueId: String get() = "bench-$id"
    var name: String by reactiveProperty(label)

    override fun clone(): BenchmarkEntity = BenchmarkEntity(id, label, age).also { it.name = name }
}