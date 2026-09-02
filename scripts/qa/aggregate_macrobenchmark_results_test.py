import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT = Path(__file__).with_name("aggregate_macrobenchmark_results.py")
JOURNEYS = [
    "coldStart_toToday",
    "coldStart_toWeekTimetable",
    "switchWeek_fiveTimes",
    "flingCourseGrid_withMoreThanOneHundredCourses",
    "roomColdQuery_toTimetableUiState_withSeededLargeDataset",
    "widgetDataBuild_throughProductionRepositoryChain",
]


def frame_runs() -> list[list[float]]:
    return [[float(index)] for index in range(1, 101)]


def samples() -> list[float]:
    return [float(index) for index in range(1, 101)]


def benchmark(name: str, *, repeat: int = 100, trace: bool = True) -> dict:
    startup = name in {
        "coldStart_toToday",
        "coldStart_toWeekTimetable",
        "roomColdQuery_toTimetableUiState_withSeededLargeDataset",
    }
    frame = name != "widgetDataBuild_throughProductionRepositoryChain"
    metrics = {}
    sampled_metrics = {}
    if startup:
        metrics = {
            "timeToInitialDisplayMs": {"runs": samples()},
            "timeToFullDisplayMs": {"runs": samples()},
        }
    if frame:
        sampled_metrics = {
            "frameDurationCpuMs": {"runs": frame_runs()},
            "frameOverrunMs": {"runs": frame_runs()},
        }
    if name == "widgetDataBuild_throughProductionRepositoryChain" and trace:
        metrics["DawnCourseBenchmark#widgetDataBuild"] = {"runs": samples()}
    return {
        "name": name,
        "metrics": metrics,
        "sampledMetrics": sampled_metrics,
        "repeatIterations": repeat,
    }


def write_results(root: Path, results: list[dict], *, context: dict | None = None) -> None:
    selected_context = {"device": "test-device", "api": 36} if context is None else context
    (root / "com.dawncourse.benchmark-BenchmarkData.json").write_text(
        json.dumps({"context": selected_context, "benchmarks": results}),
        encoding="utf-8",
    )


def run(root: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [sys.executable, str(SCRIPT), "--input-dir", str(root), "--output", str(root / "out.json")],
        check=False,
        capture_output=True,
        text=True,
    )


class AggregateMacrobenchmarkResultsTest(unittest.TestCase):
    def test_complete_six_journey_set_is_aggregated_in_stable_order(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            write_results(root, [benchmark(name) for name in reversed(JOURNEYS)])

            completed = run(root)

            self.assertEqual(completed.returncode, 0, completed.stderr)
            summary = json.loads((root / "out.json").read_text(encoding="utf-8"))
            self.assertEqual([item["name"] for item in summary["benchmarks"]], JOURNEYS)
            self.assertEqual(summary["minimumIterations"], 100)
            startup = summary["benchmarks"][0]["startup"]
            self.assertEqual(startup["timeToInitialDisplayMs"]["p50"], 50.0)
            self.assertEqual(summary["benchmarks"][-1]["widgetTrace"]["p99"], 99.0)

    def test_missing_journey_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            write_results(root, [benchmark(name) for name in JOURNEYS[:-1]])
            completed = run(root)
            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("missing required benchmark journey", completed.stderr)

    def test_duplicate_journey_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            write_results(root, [benchmark(name) for name in JOURNEYS] + [benchmark(JOURNEYS[0])])
            completed = run(root)
            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("duplicate benchmark journey", completed.stderr)

    def test_unknown_journey_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            write_results(root, [benchmark(name) for name in JOURNEYS] + [benchmark("oldStartup")])
            completed = run(root)
            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("unknown benchmark journey", completed.stderr)

    def test_repeat_one_with_one_hundred_frames_does_not_pass(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            results = [benchmark(name) for name in JOURNEYS]
            results[2] = benchmark(JOURNEYS[2], repeat=1)
            write_results(root, results)
            completed = run(root)
            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("repeatIterations=1", completed.stderr)

    def test_missing_widget_trace_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            results = [benchmark(name) for name in JOURNEYS]
            results[-1] = benchmark(JOURNEYS[-1], trace=False)
            write_results(root, results)
            completed = run(root)
            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("missing required widget trace", completed.stderr)

    def test_mixed_device_contexts_fail_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            first = root / "device-a"
            second = root / "device-b"
            first.mkdir()
            second.mkdir()
            (first / "a-BenchmarkData.json").write_text(
                json.dumps({"context": {"device": "A", "api": 36}, "benchmarks": [benchmark(name) for name in JOURNEYS[:3]]}),
                encoding="utf-8",
            )
            (second / "b-BenchmarkData.json").write_text(
                json.dumps({"context": {"device": "B", "api": 36}, "benchmarks": [benchmark(name) for name in JOURNEYS[3:]]}),
                encoding="utf-8",
            )
            completed = run(root)
            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("multiple devices/contexts", completed.stderr)

    def test_empty_context_is_not_accepted_as_a_device_identity(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            write_results(root, [benchmark(name) for name in JOURNEYS], context={})
            completed = run(root)
            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("non-empty context", completed.stderr)

    def test_under_sampled_startup_fails_closed(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            root = Path(temp_dir)
            results = [benchmark(name) for name in JOURNEYS]
            results[0]["metrics"]["timeToInitialDisplayMs"]["runs"] = [1.0]
            write_results(root, results)
            completed = run(root)
            self.assertNotEqual(completed.returncode, 0)
            self.assertIn("at least 100", completed.stderr)


if __name__ == "__main__":
    unittest.main()
