#!/usr/bin/env python3
"""
Post-processes the JMH results.json from `gradle :lirp-benchmark:jmh` into:
  1. One CSV file per benchmark class under build/reports/jmh/csv/
  2. A rendered Performance-Benchmarks.md by substituting tokens in
     scripts/Performance-Benchmarks.template.md

Usage:
    python3 render_benchmark_results.py [--results PATH] [--template PATH] [--out PATH]
                                        [--baseline PREVIOUS_RENDERED.md]

When --baseline points at a previously-rendered Performance-Benchmarks.md, every numeric table gains
a "Δ vs prev" column comparing this run's primary (first data) column against the baseline, labelled
(better)/(worse) according to whether the metric is higher-better (throughput) or lower-better
(latency, memory, init time).

Token grammar in the template:
    {{ METRIC | CLASS | METHOD | PARAMS | FORMAT? }}

Where:
    METRIC = score | mean | p50 | p95 | p99
    CLASS  = simple class name, e.g. SqlRepoBenchmark
    METHOD = benchmark method name, e.g. addEntity
    PARAMS = "" for paramless | "<n>" shorthand for entityCount=<n>
           | "k1=v1,k2=v2" for arbitrary param combinations
    FORMAT = optional: int | thousand | us | ms | ns | bare
             (default: smart, comma-separated thousands)

Environment placeholders (substituted before benchmark tokens):
    {{ TODAY }} {{ JVM_VERSION }} {{ OS_VERSION }} {{ CPU_MODEL }} {{ RAM_GB }}

Examples:
    {{ score | VolatileRepoBenchmark | addEntity | 100 }}
    {{ p50 | MemoryProfilingBenchmark | peakMemoryDuringInit | entityCount=10000,subscriberCount=5 }}
    {{ p50 | MutationLatencyBenchmark | mutateProperty | subscriberCount=1,transport=sync | bare }}
"""

from __future__ import annotations

import argparse
import csv
import datetime
import json
import platform
import re
import subprocess
import sys
from pathlib import Path
from typing import Any

# Matches {{ env_placeholder }} with no internal pipes.
ENV_TOKEN_RE = re.compile(r"\{\{\s*(?P<name>[A-Z_][A-Z0-9_]*)\s*\}\}")

# Matches {{ metric | class | method | params | format? }}
BENCH_TOKEN_RE = re.compile(
    r"\{\{\s*(?P<metric>score|mean|p50|p95|p99)\s*\|"
    r"\s*(?P<cls>\w+)\s*\|"
    r"\s*(?P<method>\w+)\s*\|"
    r"\s*(?P<params>[^|}]*?)\s*"
    r"(?:\|\s*(?P<fmt>\w+)\s*)?\}\}"
)


def parse_params(spec: str) -> dict[str, str]:
    """Parse the params field of a token.

    Empty -> {} (paramless benchmark)
    Bare value (no '=') -> {'entityCount': value}  (shorthand)
    Otherwise comma-separated key=value pairs
    """
    spec = spec.strip()
    if not spec:
        return {}
    if "=" not in spec:
        return {"entityCount": spec}
    out: dict[str, str] = {}
    for pair in spec.split(","):
        if "=" not in pair:
            raise ValueError(f"invalid param fragment: {pair!r}")
        k, v = pair.split("=", 1)
        out[k.strip()] = v.strip()
    return out


def load(results_path: Path) -> list[dict[str, Any]]:
    with results_path.open() as f:
        return json.load(f)


def index(rows: list[dict[str, Any]]) -> list[tuple[str, str, dict[str, str], dict[str, Any]]]:
    """Linear index: [(class, method, params_dict, row), ...].

    Multi-param benchmarks are kept as separate entries; lookup matches on
    all key-value pairs requested by the token. This avoids the dedup
    problem the first version had.
    """
    out: list[tuple[str, str, dict[str, str], dict[str, Any]]] = []
    for r in rows:
        full = r["benchmark"]
        cls = full.split(".")[-2]
        method = full.split(".")[-1]
        params = {k: str(v) for k, v in (r.get("params") or {}).items()}
        out.append((cls, method, params, r))
    return out


def lookup(
    idx: list[tuple[str, str, dict[str, str], dict[str, Any]]],
    cls: str,
    method: str,
    want: dict[str, str],
) -> dict[str, Any] | None:
    for c, m, p, row in idx:
        if c != cls or m != method:
            continue
        if all(p.get(k) == v for k, v in want.items()) and set(p.keys()) >= set(want.keys()):
            return row
    return None


def fmt_number(value: float, unit: str, fmt: str | None) -> str:
    if value is None:
        return "—"
    if fmt == "int" or fmt == "thousand":
        return f"{int(round(value)):,}"
    if fmt == "bare":
        # Integer-typed measurements (e.g. ns latency p50) come through as 26.0; show "26".
        # Fractional values keep two decimals.
        if abs(value - round(value)) < 1e-9:
            return f"{int(round(value))}"
        return f"{value:.2f}"
    if fmt in ("us", "ms", "ns"):
        return f"{value:,.2f}"
    # smart default by unit
    if unit == "ops/s":
        return f"{int(round(value)):,}"
    if value >= 1000:
        return f"{value:,.0f}"
    if value >= 1:
        return f"{value:.2f}"
    return f"{value:.3f}"


def resolve(metric: str, row: dict[str, Any]) -> tuple[float | None, str]:
    pm = row["primaryMetric"]
    unit = pm["scoreUnit"]
    if metric in ("score", "mean"):
        return pm["score"], unit
    pcts = pm.get("scorePercentiles") or {}
    if metric == "p50":
        return pcts.get("50.0"), unit
    if metric == "p95":
        return pcts.get("95.0"), unit
    if metric == "p99":
        return pcts.get("99.0"), unit
    raise ValueError(f"unknown metric: {metric}")


def gather_env() -> dict[str, str]:
    today = datetime.date.today().isoformat()
    # JVM
    try:
        jv = subprocess.run(
            ["java", "-version"], capture_output=True, text=True, check=False
        )
        jvm_line = (jv.stderr or jv.stdout).splitlines()[0] if (jv.stderr or jv.stdout) else "unknown"
    except FileNotFoundError:
        jvm_line = "unknown"
    # OS
    os_version = f"{platform.system()} {platform.release()} ({platform.machine()})"
    # CPU
    cpu_model = "unknown"
    try:
        with open("/proc/cpuinfo") as f:
            for line in f:
                if line.startswith("model name"):
                    cpu_model = line.split(":", 1)[1].strip()
                    break
    except FileNotFoundError:
        pass
    # RAM (Linux)
    ram_gb = "unknown"
    try:
        with open("/proc/meminfo") as f:
            for line in f:
                if line.startswith("MemTotal:"):
                    kb = int(line.split()[1])
                    ram_gb = f"{kb / (1024 * 1024):.0f}"
                    break
    except FileNotFoundError:
        pass

    return {
        "TODAY": today,
        "JVM_VERSION": jvm_line,
        "OS_VERSION": os_version,
        "CPU_MODEL": cpu_model,
        "RAM_GB": ram_gb,
    }


def render(
    template: str,
    idx: list[tuple[str, str, dict[str, str], dict[str, Any]]],
    env: dict[str, str],
) -> str:
    def env_sub(m: re.Match[str]) -> str:
        name = m.group("name")
        if name in env:
            return env[name]
        # Leave unknown ALL_CAPS tokens untouched (so e.g. SQL examples aren't mangled).
        return m.group(0)

    def bench_sub(m: re.Match[str]) -> str:
        metric = m.group("metric")
        cls = m.group("cls")
        method = m.group("method")
        params_raw = m.group("params") or ""
        fmt = m.group("fmt")
        try:
            want = parse_params(params_raw)
        except ValueError as e:
            sys.stderr.write(f"warning: bad params {params_raw!r}: {e}\n")
            return "—"
        row = lookup(idx, cls, method, want)
        if row is None:
            sys.stderr.write(
                f"warning: missing benchmark {cls}.{method} (params={want!r})\n"
            )
            return "—"
        value, unit = resolve(metric, row)
        return fmt_number(value, unit, fmt) if value is not None else "—"

    template = BENCH_TOKEN_RE.sub(bench_sub, template)
    template = ENV_TOKEN_RE.sub(env_sub, template)
    return template


def write_csvs(rows: list[dict[str, Any]], csv_dir: Path) -> None:
    csv_dir.mkdir(parents=True, exist_ok=True)
    by_class: dict[str, list[dict[str, Any]]] = {}
    for r in rows:
        cls = r["benchmark"].split(".")[-2]
        by_class.setdefault(cls, []).append(r)
    # Deterministic class, header, and row ordering keeps archived CSV diffs stable across runs.
    for cls in sorted(by_class.keys()):
        group = sorted(
            by_class[cls],
            key=lambda r: (
                r["benchmark"],
                r.get("mode", ""),
                json.dumps(r.get("params") or {}, sort_keys=True),
            ),
        )
        param_keys = sorted({k for r in group for k in (r.get("params") or {})})
        out = csv_dir / f"{cls}.csv"
        with out.open("w", newline="") as f:
            w = csv.writer(f)
            header = ["benchmark", "method", "mode"] + param_keys + [
                "score",
                "scoreError",
                "scoreUnit",
                "p50",
                "p95",
                "p99",
            ]
            w.writerow(header)
            for r in group:
                method = r["benchmark"].split(".")[-1]
                params = r.get("params") or {}
                pm = r["primaryMetric"]
                pcts = pm.get("scorePercentiles") or {}
                row = [r["benchmark"], method, r["mode"]]
                row += [params.get(k, "") for k in param_keys]
                row += [
                    pm["score"],
                    pm.get("scoreError", ""),
                    pm["scoreUnit"],
                    pcts.get("50.0", ""),
                    pcts.get("95.0", ""),
                    pcts.get("99.0", ""),
                ]
                w.writerow(row)


TABLE_LINE_RE = re.compile(r"^\s*\|.*\|\s*$")
DELTA_HEADER = "Δ vs prev"


def _split_row(line: str) -> list[str]:
    s = line.strip()
    if s.startswith("|"):
        s = s[1:]
    if s.endswith("|"):
        s = s[:-1]
    return [c.strip() for c in s.split("|")]


def _is_separator(cells: list[str]) -> bool:
    nonblank = [c for c in cells if c.strip()]
    return bool(nonblank) and all(re.fullmatch(r":?-{3,}:?", c.strip()) for c in nonblank)


# A "measurement" cell is a single number with an optional unit and nothing else — this excludes
# prose cells that merely contain an incidental digit (e.g. "openjdk version 21.0.11", "Up to 50K").
MEASUREMENT_RE = re.compile(
    r"^-?[\d,]+(?:\.\d+)?\s*(?:µs|ns|ms|s|ops/s|bytes?|B|KB|MB|GB|%)?$",
    re.IGNORECASE,
)


def _cell_number(cell: str) -> float | None:
    """Return the numeric value of a clean measurement cell, or None for prose / placeholder cells."""
    c = cell.strip()
    if not MEASUREMENT_RE.match(c):
        return None
    m = re.search(r"-?\d+(?:\.\d+)?", c.replace(",", ""))
    return float(m.group()) if m else None


def _header_sig(header: list[str]) -> tuple[str, ...]:
    """Header signature with any trailing delta column removed, so re-runs still match a prior baseline."""
    cells = list(header)
    while cells and DELTA_HEADER in cells[-1]:
        cells.pop()
    return tuple(cells)


def _parse_tables(md: str) -> list[dict[str, Any]]:
    """Parse markdown tables, tagging each with its nearest heading and preceding caption line."""
    tables: list[dict[str, Any]] = []
    lines = md.splitlines()
    heading = ""
    last_text = ""
    i = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()
        if stripped.startswith("#"):
            heading = stripped.lstrip("#").strip()
            last_text = heading
            i += 1
            continue
        is_table = (
            TABLE_LINE_RE.match(line)
            and i + 1 < len(lines)
            and TABLE_LINE_RE.match(lines[i + 1])
            and _is_separator(_split_row(lines[i + 1]))
        )
        if is_table:
            header = _split_row(line)
            rows: list[tuple[int, list[str]]] = []
            j = i + 2
            while j < len(lines) and TABLE_LINE_RE.match(lines[j]):
                rows.append((j, _split_row(lines[j])))
                j += 1
            tables.append(
                {
                    "heading": heading,
                    "caption": last_text,
                    "header": header,
                    "rows": rows,
                    "header_line": i,
                    "sep_line": i + 1,
                }
            )
            i = j
            continue
        if stripped:
            last_text = stripped
        i += 1
    return tables


def _direction_higher_better(table: dict[str, Any]) -> bool:
    """A table reports a 'higher is better' metric (throughput) when its heading, caption, or header
    mentions ops/s or throughput; otherwise lower is better (latency, memory, init time)."""
    haystack = " ".join([table["heading"], table["caption"], " ".join(table["header"])]).lower()
    return "ops/s" in haystack or "throughput" in haystack


def _delta_cell(new_v: float | None, old_v: float | None, higher_better: bool) -> str:
    if new_v is None or old_v is None:
        return "n/a"
    if old_v == 0:
        return "0.0%" if new_v == 0 else "n/a"
    pct = (new_v - old_v) / abs(old_v) * 100.0
    if abs(pct) < 0.05:
        return "≈ 0%"
    improved = (new_v > old_v) if higher_better else (new_v < old_v)
    return f"{pct:+.1f}% ({'better' if improved else 'worse'})"


def augment_with_deltas(rendered_md: str, baseline_md: str) -> str:
    """Append a delta column to each numeric data table in [rendered_md], comparing its primary
    (first data) column against the matching table in [baseline_md]. Tables with no numeric primary
    column (e.g. the environment or recommendations tables) are left untouched, as are tables with no
    matching baseline. Matching is by (heading, caption, header signature) with FIFO disambiguation so
    sibling tables that share a header (e.g. throughput vs latency under one section) align correctly.
    """
    base_tables = _parse_tables(baseline_md)
    base_groups: dict[tuple[str, str, tuple[str, ...]], list[dict[str, Any]]] = {}
    for t in base_tables:
        base_groups.setdefault((t["heading"], t["caption"], _header_sig(t["header"])), []).append(t)

    new_tables = _parse_tables(rendered_md)
    lines = rendered_md.splitlines()
    # Record edits per line index, applied at the end to keep line numbers stable.
    edits: dict[int, str] = {}

    for t in new_tables:
        # Augment only genuine numeric data tables: a majority of data rows must have a clean
        # measurement in the primary column. This skips text tables (environment, recommendations)
        # whose cells merely contain an incidental digit.
        data_rows = [cells for _, cells in t["rows"] if cells and cells[0].strip()]
        measured = sum(1 for cells in data_rows if len(cells) > 1 and _cell_number(cells[1]) is not None)
        if not data_rows or measured * 2 < len(data_rows):
            continue
        key = (t["heading"], t["caption"], _header_sig(t["header"]))
        group = base_groups.get(key)
        base_t = group.pop(0) if group else None
        higher_better = _direction_higher_better(t)

        # Baseline rows keyed by first-column label -> primary column number.
        base_by_label: dict[str, float | None] = {}
        if base_t is not None:
            for _, bcells in base_t["rows"]:
                if bcells:
                    base_by_label[bcells[0]] = _cell_number(bcells[1]) if len(bcells) > 1 else None

        # Header + separator get the new column.
        edits[t["header_line"]] = lines[t["header_line"]].rstrip() + f" {DELTA_HEADER} |"
        edits[t["sep_line"]] = lines[t["sep_line"]].rstrip() + "------------|"
        for line_idx, cells in t["rows"]:
            new_v = _cell_number(cells[1]) if len(cells) > 1 else None
            old_v = base_by_label.get(cells[0]) if base_t is not None else None
            cell = _delta_cell(new_v, old_v, higher_better)
            edits[line_idx] = lines[line_idx].rstrip() + f" {cell} |"

    if not edits:
        return rendered_md
    for idx, replacement in edits.items():
        lines[idx] = replacement
    legend = (
        f"> **{DELTA_HEADER}** compares each table's first data column against the previously "
        "published numbers. For throughput (ops/s) higher is better; for latency, memory, and init "
        "time lower is better — `(better)`/`(worse)` already account for the metric's direction."
    )
    out = "\n".join(lines)
    # Insert the legend just after the first horizontal rule (below the title/configuration block).
    marker = "\n---\n"
    pos = out.find(marker)
    if pos != -1:
        insert_at = pos + len(marker)
        out = out[:insert_at] + "\n" + legend + "\n" + out[insert_at:]
    else:
        out = legend + "\n\n" + out
    return out


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--results",
        type=Path,
        default=Path("lirp-benchmark/build/reports/jmh/results.json"),
    )
    parser.add_argument(
        "--template",
        type=Path,
        default=Path("lirp-benchmark/scripts/Performance-Benchmarks.template.md"),
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=Path("lirp-benchmark/build/reports/jmh/Performance-Benchmarks.md"),
    )
    parser.add_argument(
        "--csv-dir",
        type=Path,
        default=Path("lirp-benchmark/build/reports/jmh/csv"),
    )
    parser.add_argument(
        "--baseline",
        type=Path,
        default=None,
        help="A previously-rendered Performance-Benchmarks.md. When provided, each numeric table "
        "gains a 'Δ vs prev' column comparing this run's primary column against the baseline.",
    )
    args = parser.parse_args()

    if not args.results.exists():
        sys.stderr.write(f"error: {args.results} does not exist\n")
        return 1

    rows = load(args.results)
    write_csvs(rows, args.csv_dir)
    idx = index(rows)
    env = gather_env()

    if args.template.exists():
        rendered = render(args.template.read_text(), idx, env)
        if args.baseline is not None:
            if args.baseline.exists():
                rendered = augment_with_deltas(rendered, args.baseline.read_text())
                print(f"added delta column vs baseline {args.baseline}")
            else:
                sys.stderr.write(
                    f"warning: baseline {args.baseline} not found; rendering without delta column\n"
                )
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(rendered)
        print(f"wrote {args.out}")
    else:
        sys.stderr.write(
            f"warning: template {args.template} not found; skipping markdown render\n"
        )

    print(f"wrote {len(rows)} benchmark rows to CSVs in {args.csv_dir}/")
    return 0


if __name__ == "__main__":
    sys.exit(main())
