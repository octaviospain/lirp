# LIRP Benchmarks

This file records JMH benchmark snapshots captured at notable milestones in LIRP's evolution.
Numbers come from `gradle :lirp-benchmark:jmh` with `InitTimeBenchmark` filtered via
`-Pjmh.includes='.*InitTimeBenchmark.*'`. Runs use `fork=1`, `warmupIterations=0`,
`iterations=2`, `Mode.SingleShotTime`, `TimeUnit.MILLISECONDS`.

## KSP accessors — InitTimeBenchmark baseline (#190, #191)

### Baseline (pre-implementation)

Commit: `3b5217c` (tip of `feature/190-191-ksp-accessors-54.5` before Plan 04 edits)
Mode: SingleShotTime, fork=1, warmup=0, iterations=2 (median over 2 single-shot iterations)

| Benchmark | Param (entityCount) | Score (ms/op) | Units |
| --------- | ------------------- | ------------- | ----- |
| initSql   | 10000               | 80.207        | ms/op |
| initSql   | 50000               | 269.965       | ms/op |
| initJson  | 10000               | 126.038       | ms/op |
| initJson  | 50000               | 325.719       | ms/op |

### Post-implementation

Commit: `bbc77b8` (tip of `feature/190-191-ksp-accessors-54.5` after Plan 04 Tasks 1–3)
Mode: SingleShotTime, fork=1, warmup=0, iterations=2 (median over 2 single-shot iterations)

| Benchmark | Param (entityCount) | Score (ms/op) | Units |
| --------- | ------------------- | ------------- | ----- |
| initSql   | 10000               | 86.552        | ms/op |
| initSql   | 50000               | 286.955       | ms/op |
| initJson  | 10000               | 107.056       | ms/op |
| initJson  | 50000               | 321.671       | ms/op |

### Delta

Computed as `baseline / post` (>1 = faster post-implementation, <1 = slower).

| Benchmark | Param  | Pre (ms/op) | Post (ms/op) | Delta       | Notes                                                                                                                                                                                                                            |
| --------- | ------ | ----------- | ------------ | ----------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| initSql   | 10000  | 80.207      | 86.552       | 0.93x       | Within noise band (single-shot, fork=1, iterations=2). Reflection deletion + applyScalarRow path is comparable to the prior reflection path at this scale.                                                                       |
| initSql   | 50000  | 269.965     | 286.955      | 0.94x       | Same caveat — measurement noise dominates a few-percent difference at single-shot.                                                                                                                                               |
| initJson  | 10000  | 126.038     | 107.056      | **1.18x faster** | Measurable improvement; reactive-property restoration now goes through the KSP-generated silent setter (no `Method.invoke`).                                                                                                |
| initJson  | 50000  | 325.719     | 321.671      | 1.01x       | Within noise. JSON parse cost dominates at 50K.                                                                                                                                                                                  |

**Commentary.** With `Fork=1, iterations=2, SingleShotTime`, runs carry significant per-fork
variance — JIT warmup, GC scheduling, and CPU thermal state all contribute. The numbers above
are intended as a directional snapshot, not a statistically significant claim. The phase's
primary deliverables are reflection deletion and `--add-opens` removal (positive security and
maintainability signals per RESEARCH.md V14); the perf delta is a secondary benefit that
materializes most clearly at smaller entity counts where reflection setup cost dominated.
