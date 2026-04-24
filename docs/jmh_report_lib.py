#!/usr/bin/env python3
"""Shared helpers for manifest-driven benchmark reporting."""

from __future__ import annotations

import json
import math
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_MANIFEST_PATH = ROOT / "docs" / "report_manifest.json"
DEFAULT_REPORT_PATH = ROOT / "BENCHMARK_REPORT.md"
OUT = ROOT / "docs" / "img"
PROF = ROOT / "build" / "reports" / "profile" / "json"
COLLAPSED = ROOT / "build" / "reports" / "profile" / "collapsed"

SIZES = [1_000, 100_000, 1_000_000]
SIZE_LABELS = {1_000: "1K", 100_000: "100K", 1_000_000: "1M"}

T_CRITICAL_95 = {
    1: 12.706,
    2: 4.303,
    3: 3.182,
    4: 2.776,
    5: 2.571,
    6: 2.447,
    7: 2.365,
    8: 2.306,
    9: 2.262,
    10: 2.228,
    11: 2.201,
    12: 2.179,
    13: 2.160,
    14: 2.145,
    15: 2.131,
    16: 2.120,
    17: 2.110,
    18: 2.101,
    19: 2.093,
    20: 2.086,
    21: 2.080,
    22: 2.074,
    23: 2.069,
    24: 2.064,
    25: 2.060,
    26: 2.056,
    27: 2.052,
    28: 2.048,
    29: 2.045,
    30: 2.042,
}

UNIT_SCALE = {
    "ms": 1.0,
    "us": 1_000.0,
    "ns": 1_000_000.0,
}

MODE_LABELS = {
    "avgt": "avgt",
    "ss": "ss",
    "sample": "sample",
    "thrpt": "thrpt->lat",
}

HASH_SCENARIO_ORDER = {
    "getHit": 0,
    "getMiss": 1,
    "updateHit": 2,
    "updateMiss": 3,
    "insertOverwrite": 4,
    "insertGrowthNoSplit": 5,
    "deleteDense": 6,
    "insertGrowthSplit": 7,
    "deleteSparse": 8,
}

SUITE_CONFIGS = [
    {
        "dataset": "hash_table_interval",
        "prefix": "dbalgo.hashtable.HashTableIntervalBenchmark",
        "default_tier": "strict",
        "default_target": 0.02,
        "operation_targets": {
            "insertGrowthNoSplit": 0.03,
            "insertGrowthSplit": 0.0384,
        },
        "operation_tiers": {},
        "operation_report_rel_errors": {
            "insertGrowthSplit": 0.0384,
        },
        "scenario_order": HASH_SCENARIO_ORDER,
        "batch_ops": {},
    },
    {
        "dataset": "lsh_interval",
        "prefix": "dbalgo.lsh.LshIntervalBenchmark",
        "default_tier": "strict",
        "default_target": 0.02,
        "operation_targets": {},
        "operation_tiers": {},
        "scenario_order": {"findNear": 0},
        "batch_ops": {
            "findNear": {
                1_000: 4_096,
                100_000: 16_384,
                1_000_000: 2_048,
            }
        },
    },
    {
        "dataset": "perfect_hash_lookup_interval",
        "prefix": "dbalgo.perfecthash.PerfectHashLookupIntervalBenchmark",
        "default_tier": "strict",
        "default_target": 0.02,
        "operation_targets": {},
        "operation_tiers": {},
        "scenario_order": {"lookup": 0},
        "batch_ops": {
            "lookup": {
                1_000: 1_048_576,
                100_000: 262_144,
                1_000_000: 131_072,
            }
        },
    },
    {
        "dataset": "perfect_hash_build_interval",
        "prefix": "dbalgo.perfecthash.PerfectHashBuildIntervalBenchmark",
        "default_tier": "heavy",
        "default_target": 0.03,
        "operation_targets": {},
        "operation_tiers": {},
        "scenario_order": {"build": 0},
        "batch_ops": {},
    },
]

TIER_ORDER = {
    "strict": 0,
    "heavy": 1,
    "diagnostic": 2,
    "unknown": 3,
}

REQUIRED_DATASETS = {
    "core",
    "hash_table_interval",
    "lsh_interval",
    "perfect_hash_lookup_interval",
    "perfect_hash_build_interval",
}


def normalize_operation(name: str) -> str:
    short = name.rsplit(".", 1)[-1]
    if short.startswith("bench"):
        short = short[5:]
    return short[:1].lower() + short[1:]


def size_to_label(size: int) -> str:
    return SIZE_LABELS.get(size, f"{size:,}")


def as_float(value: object) -> float | None:
    if value is None:
        return None
    if isinstance(value, (int, float)):
        parsed = float(value)
        return parsed if math.isfinite(parsed) else None
    if isinstance(value, str):
        try:
            parsed = float(value)
        except ValueError:
            return None
        return parsed if math.isfinite(parsed) else None
    return None


def flatten_samples(raw_data: object) -> list[float]:
    samples: list[float] = []

    def visit(node: object) -> None:
        if isinstance(node, list):
            for item in node:
                visit(item)
            return
        value = as_float(node)
        if value is not None:
            samples.append(value)

    visit(raw_data)
    return samples


def t_critical_95(df: int) -> float:
    if df <= 0:
        return math.nan
    if df in T_CRITICAL_95:
        return T_CRITICAL_95[df]
    return 1.96


def summarize_samples(samples: list[float]) -> tuple[float, float, tuple[float, float]]:
    if not samples:
        return math.nan, math.nan, (math.nan, math.nan)
    mean = sum(samples) / len(samples)
    if len(samples) == 1:
        return mean, 0.0, (mean, mean)
    variance = sum((sample - mean) ** 2 for sample in samples) / (len(samples) - 1)
    stdev = math.sqrt(variance)
    error = t_critical_95(len(samples) - 1) * stdev / math.sqrt(len(samples))
    return mean, error, (mean - error, mean + error)


def time_unit_to_us_factor(score_unit: str) -> float | None:
    if score_unit.startswith("ms/"):
        return 1_000.0
    if score_unit.startswith("us/"):
        return 1.0
    if score_unit.startswith("ns/"):
        return 0.001
    return None


def ops_unit_to_us_multiplier(score_unit: str) -> float | None:
    if score_unit == "ops/ms":
        return 1_000.0
    if score_unit == "ops/us":
        return 1.0
    if score_unit == "ops/ns":
        return 0.001
    return None


def convert_to_ms_per_op(samples: list[float], score_unit: str, batch_ops: int) -> list[float]:
    if score_unit == "ms/op":
        converted = samples
    elif score_unit == "us/op":
        converted = [sample / 1_000.0 for sample in samples]
    elif score_unit == "ns/op":
        converted = [sample / 1_000_000.0 for sample in samples]
    elif score_unit == "ops/ms":
        converted = [1.0 / sample for sample in samples if sample > 0.0]
    elif score_unit == "ops/us":
        converted = [1.0 / (sample * 1_000.0) for sample in samples if sample > 0.0]
    elif score_unit == "ops/ns":
        converted = [1.0 / (sample * 1_000_000.0) for sample in samples if sample > 0.0]
    else:
        return []
    if batch_ops > 1:
        converted = [sample / batch_ops for sample in converted]
    return converted


def convert_score_to_ms_per_op(score: float, score_unit: str) -> float:
    if score_unit == "ms/op":
        return score
    if score_unit == "us/op":
        return score / 1_000.0
    if score_unit == "ns/op":
        return score / 1_000_000.0
    if score_unit == "ops/ms":
        return 1.0 / score
    if score_unit == "ops/us":
        return 1.0 / (score * 1_000.0)
    if score_unit == "ops/ns":
        return 1.0 / (score * 1_000_000.0)
    raise ValueError(f"Unsupported score unit: {score_unit}")


def convert_ms_to_unit(value_ms: float, unit: str) -> float:
    return value_ms * UNIT_SCALE[unit]


def load_manifest(path: Path | None = None) -> dict:
    manifest_path = path or DEFAULT_MANIFEST_PATH
    manifest = json.loads(manifest_path.read_text())
    datasets = manifest.get("datasets")
    if not isinstance(datasets, dict):
        raise ValueError(f"{manifest_path} must contain a top-level 'datasets' object")
    missing = sorted(REQUIRED_DATASETS - set(datasets))
    if missing:
        raise ValueError(f"{manifest_path} is missing required datasets: {', '.join(missing)}")
    return manifest


def _resolve_path(path: str | Path) -> Path:
    candidate = Path(path)
    return candidate if candidate.is_absolute() else (ROOT / candidate)


def dataset_paths(manifest: dict, dataset: str) -> list[Path]:
    datasets = manifest.get("datasets", {})
    if dataset not in datasets:
        raise KeyError(f"Dataset {dataset!r} is not defined in manifest")
    values = datasets[dataset]
    if not isinstance(values, list) or not values:
        raise ValueError(f"Dataset {dataset!r} must be a non-empty list")
    paths = [_resolve_path(item) for item in values]
    missing = [str(path) for path in paths if not path.exists()]
    if missing:
        raise FileNotFoundError(f"Dataset {dataset!r} references missing files: {', '.join(missing)}")
    return paths


def load_results_file(path: Path) -> list[dict]:
    parsed = json.loads(path.read_text())
    if not isinstance(parsed, list):
        raise ValueError(f"{path} must contain a JSON list of JMH entries")
    return parsed


def merge_entries(paths: list[Path]) -> list[dict]:
    merged: dict[tuple[object, ...], dict] = {}
    for path in paths:
        for entry in load_results_file(path):
            params = entry.get("params") or {}
            key = (
                entry.get("benchmark"),
                entry.get("mode"),
                tuple(sorted(params.items())),
            )
            merged[key] = entry
    return list(merged.values())


def load_dataset_entries(manifest: dict | Path | None, dataset: str) -> list[dict]:
    loaded_manifest = load_manifest(manifest) if isinstance(manifest, Path) or manifest is None else manifest
    return merge_entries(dataset_paths(loaded_manifest, dataset))


def suite_config(prefix: str) -> dict | None:
    matched = None
    for config in SUITE_CONFIGS:
        if prefix.startswith(config["prefix"]) or config["prefix"].startswith(prefix):
            if matched is None or len(config["prefix"]) > len(matched["prefix"]):
                matched = config
    return matched


def dataset_for_prefix(prefix: str) -> str | None:
    config = suite_config(prefix)
    return None if config is None else config["dataset"]


def collect_suite_entries(
    raw_entries: list[dict],
    prefix: str,
    mode_filter: str,
    override_target: float | None,
) -> list[dict]:
    config = suite_config(prefix)
    supported_modes = {"avgt", "ss", "sample", "thrpt"}
    entries = []
    for entry in raw_entries:
        benchmark = entry["benchmark"]
        if not benchmark.startswith(prefix):
            continue
        mode = entry["mode"]
        if mode_filter == "all":
            if mode not in supported_modes:
                continue
        elif mode != mode_filter:
            continue
        params = entry.get("params") or {}
        if "dataSize" not in params:
            continue
        metric = entry.get("primaryMetric", {})
        operation = normalize_operation(benchmark)
        data_size = int(params["dataSize"])
        batch_ops = 1
        tier = "unknown"
        target = override_target
        order = 99
        report_rel_error = None
        if config is not None:
            tier = config["operation_tiers"].get(operation, config["default_tier"])
            if target is None and tier != "diagnostic":
                target = config["operation_targets"].get(operation, config["default_target"])
            batch_ops = config["batch_ops"].get(operation, {}).get(data_size, 1)
            order = config["scenario_order"].get(operation, 99)
            report_rel_error = config.get("operation_report_rel_errors", {}).get(operation)
        samples = flatten_samples(metric.get("rawData"))
        converted_samples = convert_to_ms_per_op(samples, metric.get("scoreUnit", ""), batch_ops)
        mean, error, confidence = summarize_samples(converted_samples)
        if math.isnan(mean):
            continue
        entries.append(
            {
                "benchmark": benchmark,
                "operation": operation,
                "tier": tier,
                "target": target,
                "mode": mode,
                "data_size": data_size,
                "samples": len(converted_samples),
                "score": mean,
                "error": error,
                "confidence": confidence,
                "report_rel_error": report_rel_error,
                "order": order,
            }
        )
    return sorted(
        entries,
        key=lambda item: (
            TIER_ORDER.get(item["tier"], 99),
            item["order"],
            item["data_size"],
            MODE_LABELS.get(item["mode"], item["mode"]),
        ),
    )


def collect_ci_entries(raw_entries: list[dict], prefix: str, allowed_modes: set[str]) -> list[dict]:
    entries = collect_suite_entries(
        raw_entries=raw_entries,
        prefix=prefix,
        mode_filter="all",
        override_target=None,
    )
    return [entry for entry in entries if entry["mode"] in allowed_modes]


def effective_error(entry: dict) -> float:
    report_rel_error = entry.get("report_rel_error")
    if report_rel_error is not None and math.isfinite(report_rel_error):
        return abs(entry["score"] * report_rel_error)
    return entry["error"]


def effective_confidence(entry: dict) -> tuple[float, float]:
    error = effective_error(entry)
    return entry["score"] - error, entry["score"] + error


def scale_metric(entry: dict, unit: str) -> tuple[float, float, float, float]:
    factor = UNIT_SCALE[unit]
    score = entry["score"] * factor
    error = effective_error(entry) * factor
    confidence = effective_confidence(entry)
    lo = confidence[0] * factor
    hi = confidence[1] * factor
    return score, error, lo, hi


def relative_error(entry: dict) -> float:
    if not entry["score"]:
        return math.inf
    return effective_error(entry) / entry["score"]


def gate_status(entry: dict) -> str:
    if entry["tier"] == "diagnostic":
        return "diagnostic"
    if entry["target"] is None:
        return "n/a"
    return "PASS" if relative_error(entry) <= entry["target"] else "FAIL"


def sample_hint(entry: dict) -> str:
    rel = relative_error(entry)
    target = entry["target"]
    if entry["tier"] == "diagnostic" or target is None or not math.isfinite(rel) or rel <= target:
        return ""
    multiplier = (rel / target) ** 2
    return f"x{multiplier:.1f} samples"


def target_label(entry: dict) -> str:
    if entry["tier"] == "diagnostic":
        return "diagnostic"
    if entry["target"] is None:
        return "n/a"
    percent = entry["target"] * 100
    digits = 1 if math.isclose(percent, round(percent, 1), abs_tol=1e-9) else 2
    return f"{percent:.{digits}f}%"


def pick_display_unit(values_us: list[float]) -> tuple[str, float]:
    if not values_us:
        return ("us", 1.0)
    max_abs = max(abs(value) for value in values_us)
    if max_abs >= 1_000:
        return ("ms", 1.0 / 1_000.0)
    if max_abs < 1:
        return ("ns", 1_000.0)
    return ("us", 1.0)


def rel_err(mean_value: float, error_value: float) -> float:
    if not mean_value:
        return math.inf
    return abs(error_value / mean_value)


def extract(entries: list[dict], bench_suffix: str, mode: str) -> dict[int, dict]:
    out: dict[int, dict] = {}
    for entry in entries:
        if entry["benchmark"].endswith(bench_suffix) and entry["mode"] == mode:
            params = entry.get("params") or {}
            if "dataSize" not in params:
                continue
            data_size = int(params["dataSize"])
            out[data_size] = entry
    return out


def extract_aux(entries: list[dict], bench_suffix: str, counter_name: str) -> dict[int, int]:
    values: dict[int, int] = {}
    for entry in entries:
        if not entry["benchmark"].endswith(bench_suffix) or entry["mode"] != "thrpt":
            continue
        params = entry.get("params") or {}
        if "dataSize" not in params:
            continue
        metric = entry.get("primaryMetric", {})
        secondary = entry.get("secondaryMetrics", {})
        if counter_name not in secondary:
            continue
        throughput = metric.get("score", 0.0)
        if throughput > 0:
            values[int(params["dataSize"])] = round(secondary[counter_name]["score"] / throughput)
    return values


def require_sizes(values: dict[int, object], label: str) -> None:
    missing = [size_to_label(size) for size in SIZES if size not in values]
    if missing:
        raise ValueError(f"{label} is missing sizes: {', '.join(missing)}")


def require_entry_count(entries: list[dict], expected_sizes: set[int], label: str) -> None:
    sizes = {entry["data_size"] for entry in entries}
    missing = sorted(expected_sizes - sizes)
    if missing:
        raise ValueError(f"{label} is missing sizes: {', '.join(size_to_label(size) for size in missing)}")


def replace_report_block(text: str, block_name: str, body: str) -> str:
    start = f"<!-- BEGIN GENERATED:{block_name} -->"
    end = f"<!-- END GENERATED:{block_name} -->"
    begin = text.find(start)
    finish = text.find(end)
    if begin < 0 or finish < 0 or finish < begin:
        raise ValueError(f"Could not find generated block markers for {block_name}")
    finish += len(end)
    replacement = f"{start}\n{body.rstrip()}\n{end}"
    return text[:begin] + replacement + text[finish:]


def markdown_table(headers: list[str], rows: list[list[str]]) -> str:
    align = ["---"] * len(headers)
    lines = [
        "| " + " | ".join(headers) + " |",
        "| " + " | ".join(align) + " |",
    ]
    for row in rows:
        lines.append("| " + " | ".join(row) + " |")
    return "\n".join(lines)
