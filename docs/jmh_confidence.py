#!/usr/bin/env python3
"""Summarize JMH confidence intervals from rawData."""

from __future__ import annotations

import argparse
from pathlib import Path

from jmh_report_lib import (
    DEFAULT_MANIFEST_PATH,
    MODE_LABELS,
    collect_suite_entries,
    dataset_for_prefix,
    gate_status,
    load_dataset_entries,
    load_results_file,
    relative_error,
    sample_hint,
    scale_metric,
    target_label,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--manifest",
        default=str(DEFAULT_MANIFEST_PATH),
        help="Manifest with pinned report datasets",
    )
    parser.add_argument(
        "--dataset",
        default=None,
        help="Manifest dataset to load. Defaults to the suite dataset inferred from --prefix",
    )
    parser.add_argument(
        "--results",
        default=None,
        help="Ad-hoc JMH results.json path. Overrides --manifest/--dataset when provided",
    )
    parser.add_argument(
        "--prefix",
        required=True,
        help="Benchmark class or method prefix, for example dbalgo.hashtable.HashTableIntervalBenchmark",
    )
    parser.add_argument(
        "--mode",
        default="all",
        help="JMH mode to select: avgt, ss, sample, thrpt, or all",
    )
    parser.add_argument(
        "--unit",
        choices=("ms", "us", "ns"),
        default="us",
        help="Output unit for score and confidence interval",
    )
    parser.add_argument(
        "--target-relative-error",
        type=float,
        default=None,
        help="Override suite-specific gate with a single relative half-width target",
    )
    parser.add_argument(
        "--markdown",
        action="store_true",
        help="Print a markdown table instead of plain text",
    )
    return parser.parse_args()


def print_markdown(entries: list[dict], unit: str) -> None:
    print("| Tier | Operation | Mode | N | Mean | 95% CI | ±error | rel.err | Samples | Target | Gate | Hint |")
    print("|------|-----------|:----:|--:|-----:|:-------|-------:|--------:|--------:|:------:|:-----|:-----|")
    for entry in entries:
        score, error, lo, hi = scale_metric(entry, unit)
        hint = sample_hint(entry) or " "
        print(
            f"| {entry['tier']} | {entry['operation']} | {MODE_LABELS.get(entry['mode'], entry['mode'])} | "
            f"{entry['data_size']:,} | {score:.2f} {unit}/op | "
            f"[{lo:.2f}, {hi:.2f}] | {error:.2f} {unit} | "
            f"{relative_error(entry) * 100:.2f}% | {entry['samples']} | {target_label(entry)} | "
            f"{gate_status(entry)} | {hint} |"
        )


def print_plain(entries: list[dict], unit: str) -> None:
    for entry in entries:
        score, error, lo, hi = scale_metric(entry, unit)
        hint = sample_hint(entry)
        print(
            f"{entry['tier']:<10} {MODE_LABELS.get(entry['mode'], entry['mode']):>9}  "
            f"{entry['operation']:>20}  N={entry['data_size']:<7}  "
            f"{score:9.3f} {unit}/op  "
            f"95% CI [{lo:.3f}, {hi:.3f}]  "
            f"±{error:.3f} {unit}  "
            f"{relative_error(entry) * 100:6.2f}%  "
            f"n={entry['samples']:<4}  "
            f"target={target_label(entry):>10}  "
            f"{gate_status(entry)}"
            + (f"  {hint}" if hint else "")
        )


def load_raw_entries(args: argparse.Namespace) -> list[dict]:
    if args.results:
        return load_results_file(Path(args.results))
    dataset = args.dataset or dataset_for_prefix(args.prefix)
    if dataset is None:
        raise SystemExit(
            f"Could not infer manifest dataset for prefix={args.prefix!r}; pass --dataset or --results explicitly"
        )
    return load_dataset_entries(Path(args.manifest), dataset)


def main() -> int:
    args = parse_args()
    raw_entries = load_raw_entries(args)
    entries = collect_suite_entries(
        raw_entries=raw_entries,
        prefix=args.prefix,
        mode_filter=args.mode,
        override_target=args.target_relative_error,
    )
    if not entries:
        raise SystemExit(f"No JMH entries found for prefix={args.prefix!r}, mode={args.mode!r}")
    if args.markdown:
        print_markdown(entries, args.unit)
    else:
        print_plain(entries, args.unit)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
