#!/usr/bin/env python3
"""Generate benchmark report charts from manifest-driven JMH results + profiler outputs."""

from __future__ import annotations

import argparse
import collections
import colorsys
import hashlib
import html
import json
import math
import pathlib

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
import numpy as np

from jmh_report_lib import (
    COLLAPSED,
    DEFAULT_MANIFEST_PATH,
    DEFAULT_REPORT_PATH,
    OUT,
    PROF,
    SIZES,
    SIZE_LABELS,
    collect_ci_entries,
    convert_ms_to_unit,
    convert_score_to_ms_per_op,
    extract,
    extract_aux,
    gate_status,
    load_dataset_entries,
    load_manifest,
    markdown_table,
    pick_display_unit,
    rel_err,
    relative_error,
    effective_error,
    replace_report_block,
    require_entry_count,
    require_sizes,
    scale_metric,
    size_to_label,
)


def parse_args():
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--manifest",
        default=str(DEFAULT_MANIFEST_PATH),
        help="Pinned report manifest with exact benchmark inputs",
    )
    parser.add_argument(
        "--report",
        default=str(DEFAULT_REPORT_PATH),
        help="Markdown report to update between generated block markers",
    )
    return parser.parse_args()


args = parse_args()
manifest = load_manifest(path=pathlib.Path(args.manifest))
core_entries = load_dataset_entries(manifest, "core")
hash_interval_entries = load_dataset_entries(manifest, "hash_table_interval")
lsh_interval_entries = load_dataset_entries(manifest, "lsh_interval")
ph_lookup_interval_entries = load_dataset_entries(manifest, "perfect_hash_lookup_interval")
ph_build_interval_entries = load_dataset_entries(manifest, "perfect_hash_build_interval")
raw = core_entries

OUT.mkdir(parents=True, exist_ok=True)
print(
    "Loaded manifest datasets:"
    f" core={len(core_entries)},"
    f" ht_interval={len(hash_interval_entries)},"
    f" lsh_interval={len(lsh_interval_entries)},"
    f" ph_lookup_interval={len(ph_lookup_interval_entries)},"
    f" ph_build_interval={len(ph_build_interval_entries)}"
)

# ── helpers ──────────────────────────────────────────────────────────
plt.rcParams.update({
    "figure.dpi": 150, "figure.facecolor": "white",
    "axes.facecolor": "#f8f8f8", "axes.grid": True, "grid.alpha": 0.3,
    "font.size": 10, "axes.titlesize": 12, "axes.titleweight": "bold",
})

COLORS = ["#4C72B0", "#DD8452", "#55A868", "#C44E52", "#8172B3", "#937860"]
SIZE_LABEL_LIST = [SIZE_LABELS[size] for size in SIZES]

def save(fig, name):
    fig.savefig(OUT / name, bbox_inches="tight", pad_inches=0.15)
    plt.close(fig)
    print(f"  ✓ {name}")

def save_text(name, text):
    (OUT / name).write_text(text, encoding="utf-8")
    print(f"  ✓ {name}")

def extract(entries_or_suffix, bench_suffix=None, mode=None):
    if mode is None:
        entries = raw
        mode = bench_suffix
        bench_suffix = entries_or_suffix
    else:
        entries = entries_or_suffix
    out = {}
    for e in entries:
        if e["benchmark"].endswith(bench_suffix) and e["mode"] == mode:
            out[int(e["params"]["dataSize"])] = e
    return out


def has_sizes(data):
    return all(size in data for size in SIZES)


# ── Flamegraph renderer (from async-profiler collapsed stacks) ─────

class FlameNode:
    __slots__ = ("name", "value", "children")

    def __init__(self, name):
        self.name = name
        self.value = 0.0
        self.children = {}


def add_stack(root, frames, value):
    node = root
    node.value += value
    for frame in frames:
        node = node.children.setdefault(frame, FlameNode(frame))
        node.value += value


def load_collapsed(bench_name, mode="Throughput", data_size=1_000_000):
    path = COLLAPSED / f"{bench_name}-{mode}-dataSize-{data_size}.txt"
    if not path.exists():
        print(f"  WARNING: {path.name} not found")
        return None
    root = FlameNode("(root)")
    with open(path) as f:
        for raw_line in f:
            line = raw_line.strip()
            if not line or line.startswith("#") or " " not in line:
                continue
            stack, value = line.rsplit(" ", 1)
            try:
                weight = float(value)
            except ValueError:
                continue
            add_stack(root, [frame for frame in stack.split(";") if frame], weight)
    return root if root.value else None


def max_depth(node):
    if not node.children:
        return 0
    return 1 + max(max_depth(child) for child in node.children.values())


def flame_color(name):
    lower = name.lower()
    if any(token in lower for token in [
        "libsystem",
        "syscall",
        "pread",
        "pwrite",
        "truncate",
        "unlink",
        "__open",
        "open0",
        "read0",
        "write0",
        "fstat",
        "filedispatcherimpl",
    ]):
        return "#c44e52"
    if "g1" in lower or ".gc" in lower or "mark_" in lower:
        return "#55a868"
    if any(token in lower for token in ["dbalgo", "extendiblehash", "perfecthash", "randomprojection"]):
        return "#4c72b0"
    if any(token in lower for token in ["java.", "jdk.", "kotlin.", "hashmap", "arrays", "timsort"]):
        return "#dd8452"

    digest = hashlib.md5(name.encode("utf-8")).hexdigest()
    hue = int(digest[:2], 16) / 255.0
    sat = 0.35 + (int(digest[2:4], 16) / 255.0) * 0.25
    val = 0.85 + (int(digest[4:6], 16) / 255.0) * 0.1
    r, g, b = colorsys.hsv_to_rgb(hue, sat, val)
    return "#{:02x}{:02x}{:02x}".format(int(r * 255), int(g * 255), int(b * 255))


def trim_label(label, width_px, font_px=11):
    max_chars = int((width_px - 6) / (font_px * 0.58))
    if max_chars <= 3:
        return ""
    if len(label) <= max_chars:
        return label
    return label[: max_chars - 1] + "…"


def render_flamegraph(root, name, title):
    total = root.value
    if not total:
        print(f"  WARNING: no samples for {name}")
        return

    frame_h = 18
    width = 1200
    pad_x = 12
    title_h = 34
    depth = max_depth(root)
    height = title_h + depth * frame_h + 16
    parts = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width + pad_x * 2}" height="{height}" '
        f'viewBox="0 0 {width + pad_x * 2} {height}">',
        '<style>'
        'text{font-family:Menlo,Consolas,monospace;fill:#1f1f1f}'
        '.title{font-size:16px;font-weight:700}'
        '.meta{font-size:11px;fill:#555}'
        '.frame{stroke:#ffffff;stroke-width:0.5}'
        '.label{font-size:11px;dominant-baseline:middle}'
        '</style>',
        '<rect width="100%" height="100%" fill="#fffdf8"/>',
        f'<text class="title" x="{pad_x}" y="20">{html.escape(title)}</text>',
        f'<text class="meta" x="{pad_x}" y="32">async-profiler collapsed stacks, total samples: {int(total)}</text>',
    ]

    def walk(node, x0, depth_idx):
        y = height - 8 - (depth_idx + 1) * frame_h
        x = x0
        for child in sorted(node.children.values(), key=lambda item: (-item.value, item.name)):
            child_w = width * child.value / total
            if child_w < 0.5:
                x += child_w
                continue
            tooltip = f"{child.name} | {child.value:.0f} samples | {child.value * 100 / total:.1f}%"
            parts.append(
                f'<g><title>{html.escape(tooltip)}</title>'
                f'<rect class="frame" x="{pad_x + x:.2f}" y="{y:.2f}" width="{child_w:.2f}" height="{frame_h - 1}" '
                f'fill="{flame_color(child.name)}"/>'
            )
            label = trim_label(child.name, child_w)
            if label:
                parts.append(
                    f'<text class="label" x="{pad_x + x + 3:.2f}" y="{y + (frame_h - 1) / 2:.2f}">'
                    f'{html.escape(label)}</text>'
                )
            parts.append("</g>")
            walk(child, x, depth_idx + 1)
            x += child_w

    walk(root, 0.0, 0)
    parts.append("</svg>")
    save_text(name, "\n".join(parts))


# ── JFR profile parser ──────────────────────────────────────────────

def classify_frame(frame):
    """Classify a leaf stack frame into a CPU category."""
    method = frame.get("method", {})
    typ = method.get("type", {})
    lib_name = typ.get("name", "")
    pkg = typ.get("package") or ""
    if isinstance(pkg, dict):
        pkg = pkg.get("name", "")
    mname = method.get("name", "")

    if lib_name.startswith("libsystem_"):
        return "Syscalls"
    if "G1" in mname or "gc" in mname.lower() or "GC" in lib_name:
        return "GC"
    if lib_name == "libjvm.dylib" and any(kw in mname for kw in
            ["G1", "gc", "GC", "mark", "scrub", "evacuate", "collect", "young", "concurrent"]):
        return "GC"
    if "arraycopy" in mname or "copyOf" in mname:
        return "MemCopy"
    pkg_norm = pkg.replace("/", ".")
    if pkg_norm.startswith("dbalgo") or "dbalgo" in lib_name:
        return "User code"
    if pkg_norm.startswith(("java.", "jdk.", "sun.", "kotlin.", "org.openjdk.jmh")):
        return "JDK"
    if lib_name in ("libjvm.dylib", "libjli.dylib"):
        return "JVM"
    return "Other"


def parse_jfr_profile(json_path):
    """Parse JFR JSON → (categories_pct, top_methods, sample_count)."""
    with open(json_path) as f:
        data = json.load(f)
    events = data["recording"]["events"]
    samples = [e for e in events if e.get("type") == "jdk.ExecutionSample"]
    if not samples:
        return {}, {}, 0

    cat_counts = collections.Counter()
    method_counts = collections.Counter()

    for s in samples:
        frames = s["values"].get("stackTrace", {}).get("frames", [])
        if not frames:
            continue
        leaf = frames[0]
        cat_counts[classify_frame(leaf)] += 1
        m = leaf.get("method", {})
        method_counts[f"{m.get('type',{}).get('name','?')}.{m.get('name','?')}"] += 1

    total = sum(cat_counts.values())
    cat_pct = {k: round(100 * v / total, 1) for k, v in cat_counts.items()}
    top_methods = {k: round(100 * v / total, 1) for k, v in method_counts.most_common(10)}
    return cat_pct, top_methods, total


def load_profile(bench_name, mode="Throughput", data_size=1_000_000):
    path = PROF / f"{bench_name}-{mode}-dataSize-{data_size}.json"
    if not path.exists():
        print(f"  WARNING: {path.name} not found")
        return {}, {}, 0
    return parse_jfr_profile(path)


def get_cat_val(prof, cat):
    if cat == "Other":
        known = sum(prof.get(c, 0) for c in ["Syscalls", "User code", "JDK", "MemCopy"])
        return max(0, 100 - known)
    return prof.get(cat, 0)


# ═════════════════════════════════════════════════════════════════════
# Flamegraphs: representative CPU stacks from collapsed profiles
# ═════════════════════════════════════════════════════════════════════
print("Flamegraphs: rendering SVGs from collapsed stacks")
for bench_name, out_name, title in [
    ("dbalgo.hashtable.HashTableBenchmark.benchUpdate", "ht_update_flamegraph.svg",
     "ExtendibleHashTable.update() — flamegraph (N=1M)"),
    ("dbalgo.lsh.LshBenchmark.benchRpFindNear", "lsh_find_near_flamegraph.svg",
     "RandomProjectionLshIndex.findNear() — flamegraph (N=1M)"),
    ("dbalgo.perfecthash.PerfectHashBenchmark.benchLookup", "ph_lookup_flamegraph.svg",
     "PerfectHashMap.lookup() — flamegraph (N=1M)"),
]:
    collapsed = load_collapsed(bench_name)
    if collapsed is not None:
        render_flamegraph(collapsed, out_name, title)


# ═════════════════════════════════════════════════════════════════════
# Chart 1: HashTable latency (grouped bar)
# ═════════════════════════════════════════════════════════════════════
print("Chart 1: HashTable latencies")
ops = ["benchGet", "benchUpdate", "benchInsert", "benchDelete"]
op_labels = ["get", "update", "insert", "delete"]
series = {op: extract(op, "sample") for op in ops}
if all(has_sizes(data) for data in series.values()):
    fig, ax = plt.subplots(figsize=(7, 4))
    x = np.arange(len(SIZES))
    w = 0.18

    for i, (op, label) in enumerate(zip(ops, op_labels)):
        data = series[op]
        vals = [data[s]["primaryMetric"]["score"] * 1000 for s in SIZES]
        bars = ax.bar(x + i * w, vals, w, label=label, color=COLORS[i],
                      edgecolor="white", linewidth=0.5)
        for bar, v in zip(bars, vals):
            ax.text(bar.get_x() + bar.get_width() / 2, bar.get_height() + 0.5,
                    f"{v:.0f}" if v >= 10 else f"{v:.1f}",
                    ha="center", va="bottom", fontsize=7)

    ax.set_xticks(x + 1.5 * w)
    ax.set_xticklabels(SIZE_LABEL_LIST)
    ax.set_ylabel("Latency (µs/op)")
    ax.set_title("ExtendibleHashTable — средние задержки")
    ax.legend(loc="upper left", framealpha=0.9)
    ax.set_yscale("log")
    ax.yaxis.set_major_formatter(mticker.ScalarFormatter())
    ax.yaxis.get_major_formatter().set_scientific(False)
    save(fig, "ht_latency.png")
else:
    print("  WARNING: incomplete HashTable sample data, skipping ht_latency.png")

# ═════════════════════════════════════════════════════════════════════
# Chart 2: HashTable CPU profile (stacked bar from JFR)
# ═════════════════════════════════════════════════════════════════════
print("Chart 2: HashTable CPU profile (from JFR)")
ht_prefix = "dbalgo.hashtable.HashTableBenchmark"
ht_ops_prof = ["benchGet", "benchInsert", "benchUpdate", "benchDelete"]
ht_labels_prof = ["get", "insert", "update", "delete"]

profile_data = {}
for op, label in zip(ht_ops_prof, ht_labels_prof):
    cats, methods, n = load_profile(f"{ht_prefix}.{op}")
    profile_data[label] = cats
    print(f"  {label}: {n} samples — {cats}")

chart_cats = ["Syscalls", "User code", "JDK", "MemCopy", "Other"]
cat_colors = ["#C44E52", "#4C72B0", "#DD8452", "#55A868", "#CCCCCC"]

fig, ax = plt.subplots(figsize=(6, 3.5))
x = np.arange(len(ht_labels_prof))
bottom = np.zeros(len(ht_labels_prof))

for cat, col in zip(chart_cats, cat_colors):
    vals = [get_cat_val(profile_data[op], cat) for op in ht_labels_prof]
    ax.bar(x, vals, 0.55, bottom=bottom, label=cat, color=col,
           edgecolor="white", linewidth=0.5)
    for xi, (v, b) in enumerate(zip(vals, bottom)):
        if v >= 8:
            ax.text(xi, b + v / 2, f"{v:.0f}%", ha="center", va="center",
                    fontsize=8, color="white", fontweight="bold")
    bottom += vals

ax.set_xticks(x)
ax.set_xticklabels(ht_labels_prof)
ax.set_ylabel("% CPU time")
ax.set_title("ExtendibleHashTable — профиль CPU (N=1M)")
ax.legend(loc="upper right", fontsize=8, framealpha=0.9)
ax.set_ylim(0, 105)
save(fig, "ht_cpu_profile.png")

# ═════════════════════════════════════════════════════════════════════
# Chart 3: PerfectHash lookup CPU (horizontal bar from JFR)
# ═════════════════════════════════════════════════════════════════════
print("Chart 3: PerfectHash lookup CPU (from JFR)")
ph_prefix = "dbalgo.perfecthash.PerfectHashBenchmark"
lookup_cats, lookup_methods, ln = load_profile(f"{ph_prefix}.benchLookup")
print(f"  {ln} samples, top: {dict(list(lookup_methods.items())[:5])}")

def group_ph_lookup(methods):
    groups = collections.OrderedDict()
    groups["hash() FNV-1a"] = 0
    groups["String→bytes"] = 0
    groups["Array ops"] = 0
    groups["Bucket.lookup"] = 0
    groups["Other"] = 0
    for meth, pct in methods.items():
        ml = meth.lower().replace("/", ".")
        if "hash" in ml and ("perfecthash" in ml or "PerfectHash" in meth):
            groups["hash() FNV-1a"] += pct
        elif any(kw in ml for kw in ["encode", "utf8", "hasnegatives", "coder", "tobytearray"]):
            groups["String→bytes"] += pct
        elif "arraycopy" in ml or "copyof" in ml:
            groups["Array ops"] += pct
        elif "lookup" in ml:
            groups["Bucket.lookup"] += pct
        else:
            groups["Other"] += pct
    return groups

ph_groups = group_ph_lookup(lookup_methods)

fig, ax = plt.subplots(figsize=(6, 2.5))
ph_labels = list(ph_groups.keys())
ph_vals = list(ph_groups.values())
ph_cols = ["#4C72B0", "#DD8452", "#55A868", "#C44E52", "#CCCCCC"]

y = np.arange(len(ph_labels))
bars = ax.barh(y, ph_vals, color=ph_cols[:len(ph_labels)], edgecolor="white",
               linewidth=0.5, height=0.6)
for bar, v in zip(bars, ph_vals):
    ax.text(bar.get_width() + 1, bar.get_y() + bar.get_height() / 2,
            f"{v:.0f}%", va="center", fontsize=9)
ax.set_yticks(y)
ax.set_yticklabels(ph_labels)
ax.set_xlabel("% CPU time")
ax.set_title("PerfectHashMap.lookup() — профиль CPU (N=1M)")
ax.set_xlim(0, max(ph_vals) * 1.15)
ax.invert_yaxis()
save(fig, "ph_lookup_cpu.png")

# ═════════════════════════════════════════════════════════════════════
# Chart 4: LSH CPU profile (pie from JFR)
# ═════════════════════════════════════════════════════════════════════
print("Chart 4: LSH CPU profile (from JFR)")
lsh_cats, lsh_methods, ln = load_profile("dbalgo.lsh.LshBenchmark.benchRpFindNear")
print(f"  {ln} samples, cats: {lsh_cats}")

def group_lsh(cats, methods):
    groups = collections.OrderedDict()
    groups["GC (G1)"] = cats.get("GC", 0)
    groups["findNear"] = cats.get("User code", 0)
    groups["HashMap ops"] = sum(v for k, v in methods.items()
                                if "HashMap" in k or "HashSet" in k)
    groups["TimSort"] = sum(v for k, v in methods.items()
                            if "TimSort" in k or "sort" in k.lower() or "compare" in k.lower())
    groups["Other"] = max(0, 100 - sum(groups.values()))
    return groups

lsh_groups = group_lsh(lsh_cats, lsh_methods)
lsh_labels = [k for k, v in lsh_groups.items() if v > 0]
lsh_vals = [v for v in lsh_groups.values() if v > 0]
lsh_cols = ["#C44E52", "#4C72B0", "#DD8452", "#55A868", "#8172B3", "#CCCCCC"]

fig, ax = plt.subplots(figsize=(5, 4))
wedges, texts, autotexts = ax.pie(
    lsh_vals, labels=lsh_labels, autopct="%1.0f%%",
    colors=lsh_cols[:len(lsh_vals)], startangle=90,
    pctdistance=0.75, textprops={"fontsize": 9})
for at in autotexts:
    at.set_fontsize(8)
    at.set_fontweight("bold")
ax.set_title("LSH findNear — профиль CPU (N=1M)")
save(fig, "lsh_cpu_profile.png")

# ═════════════════════════════════════════════════════════════════════
# Chart 5: Memory per entry (from manifest-selected AuxCounters)
# ═════════════════════════════════════════════════════════════════════
print("Chart 5: Memory per entry")

ht_disk = extract_aux(raw, "benchDiskBytesPerEntry", "diskBytesPerEntry")

def extract_heap(class_prefix, counter_name="heapBytesPerEntry"):
    vals = {}
    for e in raw:
        b = e["benchmark"]
        if class_prefix in b and "benchHeapBytesPerEntry" in b and e["mode"] == "thrpt":
            ds = int(e["params"]["dataSize"])
            sec = e.get("secondaryMetrics", {})
            if counter_name in sec:
                thrpt = e["primaryMetric"]["score"]
                if thrpt > 0:
                    vals[ds] = round(sec[counter_name]["score"] / thrpt)
    return vals

lsh_heap = extract_heap("LshBenchmark")
ph_heap = extract_heap("PerfectHashBenchmark")

ht_v = [ht_disk.get(s, 0) for s in SIZES]
lsh_v = [lsh_heap.get(s, 0) for s in SIZES]
ph_v = [ph_heap.get(s, 0) for s in SIZES]
print(f"  HT disk: {ht_v}, LSH heap: {lsh_v}, PH heap: {ph_v}")

if all(size in ht_disk for size in SIZES) and all(size in lsh_heap for size in SIZES) and all(size in ph_heap for size in SIZES):
    fig, ax = plt.subplots(figsize=(6, 3.5))
    x = np.arange(len(SIZES))
    w = 0.25
    ax.bar(x - w, ht_v, w, label="HashTable (disk)", color=COLORS[0], edgecolor="white")
    ax.bar(x, ph_v, w, label="PerfectHash (heap)", color=COLORS[2], edgecolor="white")
    ax.bar(x + w, lsh_v, w, label="LSH (heap)", color=COLORS[1], edgecolor="white")

    for xi, (h, p, l) in enumerate(zip(ht_v, ph_v, lsh_v)):
        ax.text(xi - w, h + 15, str(h), ha="center", fontsize=7)
        ax.text(xi, p + 15, str(p), ha="center", fontsize=7)
        ax.text(xi + w, l + 15, str(l), ha="center", fontsize=7)

    ax.set_xticks(x)
    ax.set_xticklabels(SIZE_LABEL_LIST)
    ax.set_ylabel("bytes / entry")
    ax.set_title("Потребление памяти на запись")
    ax.legend(fontsize=8, framealpha=0.9)
    save(fig, "memory_per_entry.png")
else:
    print("  WARNING: incomplete memory counters, skipping memory_per_entry.png")

# ═════════════════════════════════════════════════════════════════════
# Chart 6: HashTable percentiles (get / insert)
# ═════════════════════════════════════════════════════════════════════
print("Chart 6: HashTable tail latency")
tail_series = {op: extract(op, "sample") for op in ["benchGet", "benchInsert"]}
if all(has_sizes(data) for data in tail_series.values()):
    fig, axes = plt.subplots(1, 2, figsize=(9, 3.5))

    for ax_i, (op, label) in enumerate([("benchGet", "get"), ("benchInsert", "insert")]):
        ax = axes[ax_i]
        data = tail_series[op]
        pcts = ["50.0", "90.0", "99.0", "99.99"]
        pct_labels = ["p50", "p90", "p99", "p99.99"]

        x = np.arange(len(SIZES))
        w = 0.18
        for pi, (pkey, plabel) in enumerate(zip(pcts, pct_labels)):
            vals = [data[s]["primaryMetric"]["scorePercentiles"].get(pkey, 0) * 1000
                    for s in SIZES]
            ax.bar(x + pi * w, vals, w, label=plabel, color=COLORS[pi],
                   edgecolor="white", linewidth=0.5)

        ax.set_xticks(x + 1.5 * w)
        ax.set_xticklabels(SIZE_LABEL_LIST)
        ax.set_ylabel("Latency (µs)")
        ax.set_title(f"HashTable {label} — персентили")
        ax.legend(fontsize=7, loc="upper left")
        ax.set_yscale("log")
        ax.yaxis.set_major_formatter(mticker.ScalarFormatter())

    fig.tight_layout()
    save(fig, "ht_percentiles.png")
else:
    print("  WARNING: incomplete HashTable percentile data, skipping ht_percentiles.png")

# ═════════════════════════════════════════════════════════════════════
# Chart 7: HashTable interval confidence (95% CI from rawData)
# ═════════════════════════════════════════════════════════════════════
print("Chart 7: HashTable interval confidence")
ht_order = [
    "getHit",
    "getMiss",
    "updateHit",
    "updateMiss",
    "insertOverwrite",
    "insertGrowthNoSplit",
    "deleteDense",
    "insertGrowthSplit",
    "deleteSparse",
]
ht_ci_entries = collect_ci_entries(
    hash_interval_entries,
    "dbalgo.hashtable.HashTableIntervalBenchmark.bench",
    {"avgt", "ss"},
)
if ht_ci_entries:
    sizes = sorted({entry["data_size"] for entry in ht_ci_entries})
    unit, _ = pick_display_unit([convert_ms_to_unit(entry["score"], "us") for entry in ht_ci_entries])
    fig, axes = plt.subplots(1, len(sizes), figsize=(max(7, 5.2 * len(sizes)), 4.6), sharey=True)
    if len(sizes) == 1:
        axes = [axes]

    for idx, size in enumerate(sizes):
        ax = axes[idx]
        subset = [entry for entry in ht_ci_entries if entry["data_size"] == size]
        subset.sort(key=lambda item: ht_order.index(item["operation"]) if item["operation"] in ht_order else 99)
        if not subset:
            continue

        y = np.arange(len(subset))
        means = [convert_ms_to_unit(entry["score"], unit) for entry in subset]
        errors = [convert_ms_to_unit(effective_error(entry), unit) for entry in subset]
        colors = ["#4C72B0" for _ in subset]

        ax.barh(y, means, color=colors, alpha=0.28, edgecolor="none")
        ax.errorbar(
            means,
            y,
            xerr=errors,
            fmt="o",
            color="#1f1f1f",
            ecolor="#1f1f1f",
            elinewidth=1,
            capsize=3,
            markersize=4,
        )
        for yi, mean, err in zip(y, means, errors):
            rel = rel_err(mean, err)
            if math.isfinite(rel):
                ax.text(mean + err, yi, f"  ±{rel * 100:.2f}%", va="center", fontsize=7)

        if idx == 0:
            ax.set_yticks(y)
            ax.set_yticklabels([entry["operation"] for entry in subset], fontsize=8)
        else:
            ax.tick_params(axis="y", labelleft=False)
        ax.set_xlabel(f"Latency ({unit}/op)")
        ax.set_title(f"N={size_to_label(size)}")

    fig.suptitle("HashTable interval scenarios — 95% confidence intervals", y=1.02)
    fig.tight_layout()
    save(fig, "ht_confidence.png")
else:
    print("  WARNING: no HashTable interval entries, skipping ht_confidence.png")

# ═════════════════════════════════════════════════════════════════════
# Chart 8: LSH confidence (95% CI from rawData)
# ═════════════════════════════════════════════════════════════════════
print("Chart 8: LSH confidence")
lsh_ci = collect_ci_entries(
    lsh_interval_entries,
    "dbalgo.lsh.LshIntervalBenchmark.benchFindNear",
    {"avgt"},
)
if lsh_ci:
    require_entry_count(lsh_ci, set(SIZES), "LSH interval dataset")
    lsh_ci.sort(key=lambda item: item["data_size"])
    unit, _ = pick_display_unit([convert_ms_to_unit(entry["score"], "us") for entry in lsh_ci])
    x = np.arange(len(lsh_ci))
    means = [convert_ms_to_unit(entry["score"], unit) for entry in lsh_ci]
    errors = [convert_ms_to_unit(entry["error"], unit) for entry in lsh_ci]
    labels = [size_to_label(entry["data_size"]) for entry in lsh_ci]
    modes = [entry["mode"] for entry in lsh_ci]

    fig, ax = plt.subplots(figsize=(6.2, 3.8))
    ax.errorbar(x, means, yerr=errors, fmt="o-", color="#4C72B0", capsize=4, linewidth=1.5, markersize=5)
    ax.set_xticks(x)
    ax.set_xticklabels(labels)
    ax.set_xlabel("Data size")
    ax.set_ylabel(f"Latency ({unit}/op)")
    ax.set_title("LSH findNear — 95% confidence intervals")

    for xi, mean, err, mode in zip(x, means, errors, modes):
        rel = rel_err(mean, err)
        ax.text(xi, mean + err, f"±{rel * 100:.2f}% ({mode})", ha="center", va="bottom", fontsize=8)

    save(fig, "lsh_confidence.png")
else:
    print("  WARNING: no LSH confidence entries, skipping lsh_confidence.png")

# ═════════════════════════════════════════════════════════════════════
# Chart 9: PerfectHash confidence (95% CI from rawData)
# ═════════════════════════════════════════════════════════════════════
print("Chart 9: PerfectHash confidence")
ph_lookup = collect_ci_entries(
    ph_lookup_interval_entries,
    "dbalgo.perfecthash.PerfectHashLookupIntervalBenchmark.benchLookup",
    {"avgt"},
)

ph_build = collect_ci_entries(
    ph_build_interval_entries,
    "dbalgo.perfecthash.PerfectHashBuildIntervalBenchmark.benchBuild",
    {"ss"},
)

if ph_lookup or ph_build:
    fig, axes = plt.subplots(1, 2, figsize=(10.4, 3.8))

    if ph_lookup:
        require_entry_count(ph_lookup, set(SIZES), "PerfectHash lookup interval dataset")
        ph_lookup.sort(key=lambda item: item["data_size"])
        unit, _ = pick_display_unit([convert_ms_to_unit(entry["score"], "us") for entry in ph_lookup])
        x = np.arange(len(ph_lookup))
        means = [convert_ms_to_unit(entry["score"], unit) for entry in ph_lookup]
        errors = [convert_ms_to_unit(entry["error"], unit) for entry in ph_lookup]
        labels = [size_to_label(entry["data_size"]) for entry in ph_lookup]
        axes[0].errorbar(x, means, yerr=errors, fmt="o-", color="#55A868", capsize=4, linewidth=1.5, markersize=5)
        axes[0].set_xticks(x)
        axes[0].set_xticklabels(labels)
        axes[0].set_xlabel("Data size")
        axes[0].set_ylabel(f"Latency ({unit}/op)")
        axes[0].set_title("Lookup")
        for xi, mean, err in zip(x, means, errors):
            axes[0].text(xi, mean + err, f"±{rel_err(mean, err) * 100:.2f}%", ha="center", va="bottom", fontsize=8)
    else:
        axes[0].set_axis_off()
        axes[0].text(0.5, 0.5, "No lookup CI data", ha="center", va="center")

    if ph_build:
        require_entry_count(ph_build, set(SIZES), "PerfectHash build interval dataset")
        ph_build.sort(key=lambda item: item["data_size"])
        unit, _ = pick_display_unit([convert_ms_to_unit(entry["score"], "us") for entry in ph_build])
        x = np.arange(len(ph_build))
        means = [convert_ms_to_unit(entry["score"], unit) for entry in ph_build]
        errors = [convert_ms_to_unit(entry["error"], unit) for entry in ph_build]
        labels = [size_to_label(entry["data_size"]) for entry in ph_build]
        axes[1].errorbar(x, means, yerr=errors, fmt="o-", color="#C44E52", capsize=4, linewidth=1.5, markersize=5)
        axes[1].set_xticks(x)
        axes[1].set_xticklabels(labels)
        axes[1].set_xlabel("Data size")
        axes[1].set_ylabel(f"Latency ({unit}/op)")
        axes[1].set_title("Build")
        for xi, mean, err in zip(x, means, errors):
            axes[1].text(xi, mean + err, f"±{rel_err(mean, err) * 100:.2f}%", ha="center", va="bottom", fontsize=8)
    else:
        axes[1].set_axis_off()
        axes[1].text(0.5, 0.5, "No build CI data", ha="center", va="center")

    fig.suptitle("PerfectHash — 95% confidence intervals", y=1.02)
    fig.tight_layout()
    save(fig, "ph_confidence.png")
else:
    print("  WARNING: no PerfectHash confidence entries, skipping ph_confidence.png")

def metric_score(entry, unit):
    metric = entry["primaryMetric"]
    return convert_ms_to_unit(
        convert_score_to_ms_per_op(metric["score"], metric["scoreUnit"]),
        unit,
    )


def metric_percentile(entry, percentile, unit):
    metric = entry["primaryMetric"]
    raw_value = metric["scorePercentiles"][percentile]
    return convert_ms_to_unit(
        convert_score_to_ms_per_op(raw_value, metric["scoreUnit"]),
        unit,
    )


def format_number(value, unit="", digits=2):
    suffix = f" {unit}" if unit else ""
    return f"{value:.{digits}f}{suffix}"


def confidence_rows(entries, unit, include_tier=True):
    rows = []
    for entry in entries:
        score, error, lo, hi = scale_metric(entry, unit)
        row = []
        if include_tier:
            row.append(entry["tier"])
        row.extend([
            entry["operation"],
            entry["mode"],
            size_to_label(entry["data_size"]),
            format_number(score, f"{unit}/op"),
            f"[{lo:.2f}, {hi:.2f}] {unit}",
            f"{relative_error(entry) * 100:.2f}%",
        ])
        rows.append(row)
    return rows


def build_report_tables():
    require_sizes(ht_disk, "HashTable disk usage dataset")
    require_sizes(lsh_heap, "LSH heap usage dataset")
    require_sizes(ph_heap, "PerfectHash heap usage dataset")

    ht_sample = {suffix: extract(raw, suffix, "sample") for suffix in ["benchGet", "benchUpdate", "benchInsert", "benchDelete"]}
    for label, values in ht_sample.items():
        require_sizes(values, f"HashTable {label} sample dataset")

    ht_thrpt = {suffix: extract(raw, suffix, "thrpt") for suffix in ["benchGet", "benchUpdate", "benchInsert", "benchDelete"]}
    for label, values in ht_thrpt.items():
        require_sizes(values, f"HashTable {label} throughput dataset")

    lsh_thrpt = extract(raw, "benchRpFindNear", "thrpt")
    lsh_sample = extract(raw, "benchRpFindNear", "sample")
    require_sizes(lsh_thrpt, "LSH throughput dataset")
    require_sizes(lsh_sample, "LSH sample dataset")

    ph_lookup_thrpt = extract(raw, "benchLookup", "thrpt")
    ph_lookup_sample = extract(raw, "benchLookup", "sample")
    ph_build_sample = extract(raw, "benchBuild", "sample")
    require_sizes(ph_lookup_thrpt, "PerfectHash lookup throughput dataset")
    require_sizes(ph_lookup_sample, "PerfectHash lookup sample dataset")
    require_sizes(ph_build_sample, "PerfectHash build sample dataset")

    ph_total_slots = extract_aux(raw, "benchStructSize", "totalSlots")
    ph_top_level = extract_aux(raw, "benchStructSize", "topLevelSize")
    require_sizes(ph_total_slots, "PerfectHash struct size dataset")
    require_sizes(ph_top_level, "PerfectHash top-level size dataset")

    blocks = {}
    blocks["HT_LATENCY_TABLE"] = markdown_table(
        ["Operation", "1K", "100K", "1M"],
        [
            [label] + [format_number(metric_score(values[size], "us"), digits=2) for size in SIZES]
            for label, values in [
                ("get", ht_sample["benchGet"]),
                ("update", ht_sample["benchUpdate"]),
                ("insert", ht_sample["benchInsert"]),
                ("delete", ht_sample["benchDelete"]),
            ]
        ],
    )
    blocks["HT_CI_TABLE"] = markdown_table(
        ["Operation", "Mode", "N", "Mean", "95% CI", "rel.err"],
        confidence_rows(ht_ci_entries, "us", include_tier=False),
    )
    blocks["HT_DISK_TABLE"] = markdown_table(
        ["N", "bytes/entry"],
        [[size_to_label(size), str(ht_disk[size])] for size in SIZES],
    )
    blocks["HT_TAIL_TABLE"] = markdown_table(
        ["Operation", "p50 @ 1M", "p90 @ 1M", "p99 @ 1M", "p99.99 @ 1M"],
        [
            [
                label,
                format_number(metric_percentile(values[1_000_000], "50.0", "us"), digits=2),
                format_number(metric_percentile(values[1_000_000], "90.0", "us"), digits=2),
                format_number(metric_percentile(values[1_000_000], "99.0", "us"), digits=2),
                format_number(metric_percentile(values[1_000_000], "99.99", "us"), digits=2),
            ]
            for label, values in [
                ("get", ht_sample["benchGet"]),
                ("update", ht_sample["benchUpdate"]),
                ("insert", ht_sample["benchInsert"]),
                ("delete", ht_sample["benchDelete"]),
            ]
        ],
    )

    blocks["LSH_LATENCY_TABLE"] = markdown_table(
        ["N", "ops/ms", "us/op", "p50 (us)", "p90 (us)", "p99 (us)"],
        [
            [
                size_to_label(size),
                format_number(lsh_thrpt[size]["primaryMetric"]["score"], digits=1),
                format_number(metric_score(lsh_thrpt[size], "us"), digits=2),
                format_number(metric_percentile(lsh_sample[size], "50.0", "us"), digits=2),
                format_number(metric_percentile(lsh_sample[size], "90.0", "us"), digits=2),
                format_number(metric_percentile(lsh_sample[size], "99.0", "us"), digits=2),
            ]
            for size in SIZES
        ],
    )
    blocks["LSH_CI_TABLE"] = markdown_table(
        ["Tier", "Operation", "Mode", "N", "Mean", "95% CI", "rel.err"],
        confidence_rows(lsh_ci, "us"),
    )
    blocks["LSH_MEMORY_TABLE"] = markdown_table(
        ["N", "bytes/entry"],
        [[size_to_label(size), str(lsh_heap[size])] for size in SIZES],
    )

    blocks["PH_BUILD_TABLE"] = markdown_table(
        ["N", "build time (ms)"],
        [[size_to_label(size), format_number(metric_score(ph_build_sample[size], "ms"), digits=2)] for size in SIZES],
    )
    blocks["PH_LOOKUP_TABLE"] = markdown_table(
        ["N", "ops/ms", "ns/op", "p50 (ns)", "p90 (ns)", "p99 (ns)"],
        [
            [
                size_to_label(size),
                format_number(ph_lookup_thrpt[size]["primaryMetric"]["score"], digits=1),
                format_number(metric_score(ph_lookup_thrpt[size], "ns"), digits=2),
                format_number(metric_percentile(ph_lookup_sample[size], "50.0", "ns"), digits=0),
                format_number(metric_percentile(ph_lookup_sample[size], "90.0", "ns"), digits=0),
                format_number(metric_percentile(ph_lookup_sample[size], "99.0", "ns"), digits=0),
            ]
            for size in SIZES
        ],
    )
    blocks["PH_LOOKUP_CI_TABLE"] = markdown_table(
        ["Tier", "Operation", "Mode", "N", "Mean", "95% CI", "rel.err"],
        confidence_rows(ph_lookup, "ns"),
    )
    blocks["PH_BUILD_CI_TABLE"] = markdown_table(
        ["Tier", "Operation", "Mode", "N", "Mean", "95% CI", "rel.err"],
        confidence_rows(ph_build, "ms"),
    )
    blocks["PH_MEMORY_TABLE"] = markdown_table(
        ["N", "heap bytes/entry", "total slots", "slots/entry"],
        [
            [
                size_to_label(size),
                str(ph_heap[size]),
                str(ph_total_slots[size]),
                f"{ph_total_slots[size] / size:.2f}",
            ]
            for size in SIZES
        ],
    )
    blocks["MEMORY_COMPARISON_TABLE"] = markdown_table(
        ["Structure", "1K", "100K", "1M"],
        [
            ["HashTable (disk)"] + [str(ht_disk[size]) for size in SIZES],
            ["PerfectHash (heap)"] + [str(ph_heap[size]) for size in SIZES],
            ["LSH (heap)"] + [str(lsh_heap[size]) for size in SIZES],
        ],
    )
    return blocks


def update_report():
    print("Report: syncing generated tables")
    report_path = pathlib.Path(args.report)
    report = report_path.read_text()
    for block_name, body in build_report_tables().items():
        marker = f"<!-- BEGIN GENERATED:{block_name} -->"
        if marker not in report:
            print(f"  - skip {block_name} (block not present)")
            continue
        report = replace_report_block(report, block_name, body)
    report_path.write_text(report)
    print(f"  ✓ {report_path}")


update_report()

print("\nAll charts generated in", OUT)
