#!/usr/bin/env python3
"""Generate benchmark report charts from JMH results + profiler outputs."""

import collections
import colorsys
import hashlib
import html
import json
import pathlib
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.ticker as mticker
import numpy as np

ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT  = ROOT / "docs" / "img"
OUT.mkdir(parents=True, exist_ok=True)
JMH  = ROOT / "build" / "results" / "jmh" / "results.json"
PROF = ROOT / "build" / "reports" / "profile" / "json"
COLLAPSED = ROOT / "build" / "reports" / "profile" / "collapsed"

with open(JMH) as f:
    raw = json.load(f)

# ── helpers ──────────────────────────────────────────────────────────
plt.rcParams.update({
    "figure.dpi": 150, "figure.facecolor": "white",
    "axes.facecolor": "#f8f8f8", "axes.grid": True, "grid.alpha": 0.3,
    "font.size": 10, "axes.titlesize": 12, "axes.titleweight": "bold",
})

COLORS = ["#4C72B0", "#DD8452", "#55A868", "#C44E52", "#8172B3", "#937860"]
SIZES = [1_000, 100_000, 1_000_000]
SIZE_LABELS = ["1K", "100K", "1M"]

def save(fig, name):
    fig.savefig(OUT / name, bbox_inches="tight", pad_inches=0.15)
    plt.close(fig)
    print(f"  ✓ {name}")

def save_text(name, text):
    (OUT / name).write_text(text, encoding="utf-8")
    print(f"  ✓ {name}")

def extract(bench_suffix, mode):
    out = {}
    for e in raw:
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
    ax.set_xticklabels(SIZE_LABELS)
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
# Chart 5: Memory per entry (from results.json AuxCounters)
# ═════════════════════════════════════════════════════════════════════
print("Chart 5: Memory per entry")

def extract_aux(bench_suffix, counter_name):
    vals = {}
    for e in raw:
        if e["benchmark"].endswith(bench_suffix) and e["mode"] == "thrpt":
            ds = int(e["params"]["dataSize"])
            sec = e.get("secondaryMetrics", {})
            if counter_name in sec:
                thrpt = e["primaryMetric"]["score"]
                if thrpt > 0:
                    vals[ds] = round(sec[counter_name]["score"] / thrpt)
    return vals

ht_disk = extract_aux("benchDiskBytesPerEntry", "diskBytesPerEntry")

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
ax.set_xticklabels(SIZE_LABELS)
ax.set_ylabel("bytes / entry")
ax.set_title("Потребление памяти на запись")
ax.legend(fontsize=8, framealpha=0.9)
save(fig, "memory_per_entry.png")

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
        ax.set_xticklabels(SIZE_LABELS)
        ax.set_ylabel("Latency (µs)")
        ax.set_title(f"HashTable {label} — персентили")
        ax.legend(fontsize=7, loc="upper left")
        ax.set_yscale("log")
        ax.yaxis.set_major_formatter(mticker.ScalarFormatter())

    fig.tight_layout()
    save(fig, "ht_percentiles.png")
else:
    print("  WARNING: incomplete HashTable percentile data, skipping ht_percentiles.png")

print("\nAll charts generated in", OUT)
