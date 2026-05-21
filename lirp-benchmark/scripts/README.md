# Benchmark Report Automation

Turns the raw `results.json` produced by `gradle :lirp-benchmark:jmh` into:

1. **One CSV file per benchmark class** under `build/reports/jmh/csv/`
   — suitable for archival, diffing across runs, or importing into Grafana / Excel.
2. **A rendered `Performance-Benchmarks.md`** by token-substituting
   `scripts/Performance-Benchmarks.template.md`.

The script has no dependencies beyond the Python 3 stdlib. The
`renderBenchmarkReport` Gradle task is wired to run automatically as a
`finalizedBy` of `:lirp-benchmark:jmh` — see the **Gradle integration**
section below.

## Files

- `render_benchmark_results.py` — the post-processor.
- `Performance-Benchmarks.template.md` — the canonical wiki template. Covers
  the full report (repository microbenchmarks, comparative benchmarks, memory
  profiling, JDBC baseline, and the how-to-run section).

## Token syntax

```text
{{ METRIC | CLASS | METHOD | PARAMS | FORMAT? }}
```

| Field   | Example                              | Notes                                                                                          |
|---------|--------------------------------------|------------------------------------------------------------------------------------------------|
| METRIC  | `score`, `mean`, `p50`, `p95`, `p99` | `score` = throughput mean; `mean` = sample mean                                                |
| CLASS   | `VolatileRepoBenchmark`              | The simple class name from the JMH benchmark                                                   |
| METHOD  | `addEntity`                          | The `@Benchmark`-annotated method name                                                         |
| PARAMS  | `100` / `opCount=10000,subscriberCount=5` / `` | Bare value is shorthand for `entityCount=<value>`; comma-separated `k=v` for multi-param benchmarks; empty for paramless |
| FORMAT  | `int`, `thousand`, `ns`, `us`, `ms`, `bare` | Optional; defaults to a smart per-unit format. `bare` strips commas and rounds to int when fractional part is zero |

Unresolved tokens emit a warning to stderr and render as `—`.

**Environment placeholders** (substituted before benchmark tokens):

| Token             | Source                                     |
|-------------------|--------------------------------------------|
| `{{ TODAY }}`     | `datetime.date.today().isoformat()`        |
| `{{ JVM_VERSION }}` | first line of `java -version`            |
| `{{ OS_VERSION }}`  | `platform.system()/release()/machine()`  |
| `{{ CPU_MODEL }}`   | `/proc/cpuinfo` `model name` field       |
| `{{ RAM_GB }}`      | `/proc/meminfo` `MemTotal`               |

## Usage (manual)

```bash
gradle :lirp-benchmark:jmh -Pjmh.warmupIterations=2 -Pjmh.iterations=3 -Pjmh.fork=1 --rerun-tasks
python3 lirp-benchmark/scripts/render_benchmark_results.py
# CSVs in    lirp-benchmark/build/reports/jmh/csv/
# Markdown in lirp-benchmark/build/reports/jmh/Performance-Benchmarks.md
```

CLI flags:

```text
--results   PATH   default: lirp-benchmark/build/reports/jmh/results.json
--template  PATH   default: lirp-benchmark/scripts/Performance-Benchmarks.template.md
--out       PATH   default: lirp-benchmark/build/reports/jmh/Performance-Benchmarks.md
--csv-dir   PATH   default: lirp-benchmark/build/reports/jmh/csv
```

## Gradle integration (wired)

`lirp-benchmark/build.gradle` declares a `renderBenchmarkReport` task that
runs the Python script and is registered as a `finalizedBy` of `:jmh`. After
every successful JMH run, the CSVs and the rendered markdown are regenerated
in `build/reports/jmh/`.

You can also invoke it standalone — useful when you've already run the
benchmark and only want to re-render after editing the template:

```bash
gradle :lirp-benchmark:renderBenchmarkReport
```

## Proposed wiki sync (separate repo)

The wiki lives at `../lirp.wiki/` (a separate git repository). A sync step is
intentionally **not** part of the Gradle task — automatic pushes to a wiki
repo are surprising. A small one-shot script (or a manual `cp` + commit + push
flow) keeps the trust boundary clean:

```bash
cp lirp-benchmark/build/reports/jmh/Performance-Benchmarks.md \
   ../lirp.wiki/Performance-Benchmarks.md
( cd ../lirp.wiki && git add Performance-Benchmarks.md \
    && git commit -m "Refresh performance benchmarks ($(date -I))" \
    && git push )
```

## Pre-run hygiene checklist

To get repeatable numbers:

- Close other GUI apps; disable browser tabs that auto-refresh.
- Pin the CPU governor to `performance`: `sudo cpupower frequency-set -g performance`.
- Disable Turbo Boost if you have a script for it (reduces variance further).
- Always use `--rerun-tasks` so Gradle doesn't return a cached JMH result.
- Consider longer config for "official" runs (`-Pjmh.warmupIterations=5 -Pjmh.iterations=10`).

## Editorial layer

The template intentionally renders only **data** — the prose interpretation
notes that appeared in earlier wiki revisions (e.g. "SqlRepo add() is ~45%
lower than the 2026-04-14 baseline") are not auto-generated, because they
require human judgment about whether a delta is signal or noise.

The current workflow is:

1. `gradle :lirp-benchmark:jmh --rerun-tasks` regenerates the data.
2. `cp lirp-benchmark/build/reports/jmh/Performance-Benchmarks.md ../lirp.wiki/` (run from repo root).
3. Read the diff; add or update interpretive notes by hand where deltas warrant them.

## Follow-ups

- **Regression detection**: emit a `diff.md` comparing each metric against the
  previous run's CSV (kept in git or fetched from the wiki). Fail the task — or
  just warn — if any metric regresses by more than a configured threshold.
- **Trend chart generation**: archive each CSV under
  `build/reports/jmh/history/<timestamp>/` and produce a small SVG sparkline
  per metric for the README.
- **Auto-publish**: a separate `publishBenchmarkReport` task (not wired) that
  `cp`s into `../lirp.wiki/` and pushes — gated behind a `-Ppublish` flag so
  it never runs unintentionally.
