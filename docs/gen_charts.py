#!/usr/bin/env python3
"""Generate benchmark report charts from JMH results + JFR profiling data."""

import json, pathlib, collections
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

def extract(bench_suffix, mode):
    out = {}
    for e in raw:
        if e["benchmark"].endswith(bench_suffix) and e["mode"] == mode:
            out[int(e["params"]["dataSize"])] = e
    return out


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
# Chart 1: HashTable latency (grouped bar)
# ═════════════════════════════════════════════════════════════════════
print("Chart 1: HashTable latencies")
ops = ["benchGet", "benchUpdate", "benchInsert", "benchDelete"]
op_labels = ["get", "update", "insert", "delete"]

fig, ax = plt.subplots(figsize=(7, 4))
x = np.arange(len(SIZES))
w = 0.18

for i, (op, label) in enumerate(zip(ops, op_labels)):
    data = extract(op, "sample")
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
fig, axes = plt.subplots(1, 2, figsize=(9, 3.5))

for ax_i, (op, label) in enumerate([("benchGet", "get"), ("benchInsert", "insert")]):
    ax = axes[ax_i]
    data = extract(op, "sample")
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

print("\nAll charts generated in", OUT)
