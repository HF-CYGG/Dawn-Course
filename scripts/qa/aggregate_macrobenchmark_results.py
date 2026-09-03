#!/usr/bin/env python3
"""Fail-closed aggregation for the Dawn Course Macrobenchmark contract."""

from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
from typing import Any


MINIMUM_ITERATIONS = 100
PERCENTILES = (("p50", 0.50), ("p95", 0.95), ("p99", 0.99))
STARTUP_METRICS = ("timeToInitialDisplayMs", "timeToFullDisplayMs")
FRAME_METRICS = ("frameDurationCpuMs", "frameOverrunMs")
WIDGET_TRACE_KEYS = (
    "DawnCourseBenchmark#widgetDataBuild",
    "widgetDataBuild",
    "traceSectionMetric",
)
WIDGET_TRACE_PREFIX = "DawnCourseBenchmark#widgetDataBuild"

# This is deliberately an allow-list. A new journey must update this contract
# and its tests instead of silently appearing in CI artifacts.
EXPECTED_JOURNEYS = (
    "coldStart_toToday",
    "coldStart_toToday_withBaselineProfile",
    "coldStart_toWeekTimetable",
    "switchWeek_fiveTimes",
    "flingCourseGrid_withMoreThanOneHundredCourses",
    "roomColdQuery_toTimetableUiState_withSeededLargeDataset",
    "widgetDataBuild_throughProductionRepositoryChain",
)

JOURNEY_REQUIREMENTS: dict[str, tuple[bool, bool, bool]] = {
    "coldStart_toToday": (True, True, False),
    "coldStart_toToday_withBaselineProfile": (True, True, False),
    "coldStart_toWeekTimetable": (True, True, False),
    "switchWeek_fiveTimes": (False, True, False),
    "flingCourseGrid_withMoreThanOneHundredCourses": (False, True, False),
    "roomColdQuery_toTimetableUiState_withSeededLargeDataset": (True, True, False),
    "widgetDataBuild_throughProductionRepositoryChain": (False, False, True),
}


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        raise ValueError("cannot calculate a percentile from zero samples")
    ordered = sorted(values)
    return ordered[math.ceil(fraction * len(ordered)) - 1]


def summary(values: list[float], minimum_samples: int, metric_name: str) -> dict[str, Any]:
    if len(values) < minimum_samples:
        raise ValueError(
            f"{metric_name} only has {len(values)} samples; "
            f"at least {minimum_samples} are required for P95/P99"
        )
    return {
        "sampleCount": len(values),
        **{name: percentile(values, fraction) for name, fraction in PERCENTILES},
    }


def numbers(values: Any, metric_name: str) -> list[float]:
    if not isinstance(values, list):
        raise ValueError(f"{metric_name}.runs must be an array")
    parsed: list[float] = []
    for value in values:
        if not isinstance(value, (int, float)) or isinstance(value, bool):
            raise ValueError(f"{metric_name} contains a non-numeric sample")
        parsed.append(float(value))
    return parsed


def flatten_runs(runs: Any, metric_name: str) -> list[float]:
    if not isinstance(runs, list):
        raise ValueError(f"{metric_name}.runs must be an array")
    flattened: list[float] = []
    for iteration in runs:
        if not isinstance(iteration, list):
            raise ValueError(f"{metric_name}.runs must contain one array per iteration")
        flattened.extend(numbers(iteration, metric_name))
    return flattened


def _metric_object(metrics: Any, metric_name: str, journey: str) -> dict[str, Any]:
    if not isinstance(metrics, dict):
        raise ValueError(f"{journey} is missing AndroidX metrics or sampledMetrics")
    metric = metrics.get(metric_name)
    if not isinstance(metric, dict):
        raise ValueError(f"{journey} is missing required metric: {metric_name}")
    return metric


def _trace_metric(result: dict[str, Any], journey: str) -> tuple[str, Any]:
    for container_name in ("metrics", "sampledMetrics", "traceMetrics"):
        container = result.get(container_name)
        if not isinstance(container, dict):
            continue
        for key in WIDGET_TRACE_KEYS:
            if key in container:
                return key, container[key]
        # TraceSectionMetric appends the selected mode to the label, e.g.
        # ``DawnCourseBenchmark#widgetDataBuildSumMs``. Keep the prefix
        # constrained to this production trace rather than accepting any
        # arbitrary metric as proof that the widget path ran.
        for key, value in container.items():
            if isinstance(key, str) and key.startswith(WIDGET_TRACE_PREFIX):
                return key, value
    raise ValueError(
        f"{journey} is missing required widget trace metric "
        f"({WIDGET_TRACE_KEYS[0]})"
    )


def _aggregate_trace(metric: Any, journey: str, minimum_samples: int) -> dict[str, Any]:
    if not isinstance(metric, dict):
        raise ValueError(f"{journey} widget trace is not a metric object")
    # TraceSectionMetric emits one numeric value per iteration. Accept the
    # nested shape too, but never accept percentile-only data that hides runs.
    runs = metric.get("runs")
    if isinstance(runs, list) and all(isinstance(value, list) for value in runs):
        values = flatten_runs(runs, f"{journey}.widgetTrace")
    else:
        values = numbers(runs, f"{journey}.widgetTrace")
    return summary(values, minimum_samples, f"{journey}.widgetTrace")


def aggregate_benchmark(
    result: dict[str, Any], minimum_iterations: int = MINIMUM_ITERATIONS
) -> dict[str, Any]:
    name = result.get("name")
    if not isinstance(name, str) or not name:
        raise ValueError("benchmark result is missing a non-empty name")
    if name not in JOURNEY_REQUIREMENTS:
        raise ValueError(f"unknown active journey: {name}")
    if minimum_iterations < MINIMUM_ITERATIONS:
        raise ValueError(f"minimum iteration gate must be at least {MINIMUM_ITERATIONS}")

    repeat_iterations = result.get("repeatIterations")
    if not isinstance(repeat_iterations, int) or isinstance(repeat_iterations, bool):
        raise ValueError(f"{name}.repeatIterations must be an integer")
    if repeat_iterations < minimum_iterations:
        raise ValueError(
            f"{name}.repeatIterations={repeat_iterations}; "
            f"at least {minimum_iterations} are required"
        )

    metrics = result.get("metrics")
    sampled_metrics = result.get("sampledMetrics")
    needs_startup, needs_frame, needs_trace = JOURNEY_REQUIREMENTS[name]
    item: dict[str, Any] = {"name": name, "repeatIterations": repeat_iterations}

    if needs_startup:
        startup: dict[str, Any] = {}
        for metric_name in STARTUP_METRICS:
            metric = _metric_object(metrics, metric_name, name)
            startup[metric_name] = summary(
                numbers(metric.get("runs"), f"{name}.{metric_name}"),
                minimum_iterations,
                f"{name}.{metric_name}",
            )
        item["startup"] = startup

    if needs_frame:
        frame_timing: dict[str, Any] = {}
        for metric_name in FRAME_METRICS:
            metric = _metric_object(sampled_metrics, metric_name, name)
            frame_timing[metric_name] = summary(
                flatten_runs(metric.get("runs"), f"{name}.{metric_name}"),
                minimum_iterations,
                f"{name}.{metric_name}",
            )
        item["frameTiming"] = frame_timing

    if needs_trace:
        trace_name, trace = _trace_metric(result, name)
        item["widgetTrace"] = {
            "metricName": trace_name,
            **_aggregate_trace(trace, name, minimum_iterations),
        }

    return item


def _context_fingerprint(context: Any) -> str:
    if not isinstance(context, dict) or not context:
        raise ValueError("BenchmarkData JSON must contain a non-empty context")
    return json.dumps(context, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def load_benchmark_results(input_dir: Path) -> list[dict[str, Any]]:
    input_dir = input_dir.resolve()
    if not input_dir.is_dir():
        raise ValueError(f"input directory does not exist: {input_dir}")
    files = sorted(input_dir.rglob("*BenchmarkData.json"))
    if not files:
        raise ValueError(f"No AndroidX *BenchmarkData.json files found under {input_dir}")

    results: list[dict[str, Any]] = []
    contexts: set[str] = set()
    for file in files:
        try:
            payload = json.loads(file.read_text(encoding="utf-8"))
        except json.JSONDecodeError as error:
            raise ValueError(f"Invalid JSON in {file}: {error}") from error
        if not isinstance(payload, dict) or not isinstance(payload.get("benchmarks"), list):
            raise ValueError(f"{file} does not match AndroidX BenchmarkData JSON schema")
        contexts.add(_context_fingerprint(payload.get("context")))
        for benchmark in payload["benchmarks"]:
            if not isinstance(benchmark, dict):
                raise ValueError(f"{file} contains a non-object benchmark result")
            result = dict(benchmark)
            result["_sourceFile"] = str(file)
            results.append(result)

    if len(contexts) != 1:
        raise ValueError(
            "BenchmarkData input mixes multiple devices/contexts; "
            "provide one explicit result directory from one benchmark context"
        )
    if not results:
        raise ValueError("AndroidX BenchmarkData JSON contains no benchmark results")
    return results


def validate_journey_set(results: list[dict[str, Any]]) -> None:
    names = [result.get("name") for result in results]
    unknown = sorted({name for name in names if name not in EXPECTED_JOURNEYS})
    if unknown:
        raise ValueError(f"unknown benchmark journey(s): {', '.join(map(str, unknown))}")
    duplicate = sorted({name for name in names if names.count(name) > 1})
    if duplicate:
        raise ValueError(f"duplicate benchmark journey(s): {', '.join(duplicate)}")
    missing = [name for name in EXPECTED_JOURNEYS if name not in names]
    if missing:
        raise ValueError(f"missing required benchmark journey(s): {', '.join(missing)}")
    if len(names) != len(EXPECTED_JOURNEYS):
        raise ValueError("benchmark result set must contain exactly six active journeys")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-dir", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--min-iterations", type=int, default=MINIMUM_ITERATIONS)
    args = parser.parse_args()
    if args.min_iterations < MINIMUM_ITERATIONS:
        parser.error(f"--min-iterations must be at least {MINIMUM_ITERATIONS}")

    try:
        benchmarks = load_benchmark_results(args.input_dir)
        validate_journey_set(benchmarks)
        aggregated = {
            item["name"]: item
            for item in (aggregate_benchmark(benchmark, args.min_iterations) for benchmark in benchmarks)
        }
        result = {
            "schemaVersion": 2,
            "sourceFormat": "AndroidX BenchmarkData",
            "minimumIterations": args.min_iterations,
            "requiredJourneys": list(EXPECTED_JOURNEYS),
            "benchmarks": [aggregated[name] for name in EXPECTED_JOURNEYS],
        }
    except ValueError as error:
        parser.error(str(error))

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
