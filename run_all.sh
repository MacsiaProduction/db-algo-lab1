#!/bin/bash
# PH transition-zone lookup scaling (1M..50M, median 10) + report refresh.
#
#   ./run_light.sh   # ~1–2 min smoke first
#   ./run_all.sh     # full transition-zone rerun
#
set -euo pipefail
cd "$(dirname "$0")"

PH_SIZES="${PH_SIZES:-1000000,2000000,5000000,10000000,20000000,50000000}"
PH_POINT_REPS="${PH_POINT_REPS:-10}"
PH_MAX_HEAP="${PH_MAX_HEAP:-32g}"
PH_OUTPUT="${PH_OUTPUT:-build/results/lookup_scaling_ph_transition_median10.json}"

echo "==> lab1 run_all: $(date -Iseconds)"
echo "==> PH sizes=$PH_SIZES  pointReps=$PH_POINT_REPS  heap=$PH_MAX_HEAP"
echo "==> Free RAM: $(free -h | awk '/Mem:/ {print $7}')"

./gradlew classes --no-daemon -q

./gradlew lookupScaling --no-daemon -q \
  -PlookupScalingSeries=perfecthash \
  -PlookupScalingDataSizes="$PH_SIZES" \
  -PlookupScalingPointRepetitions="$PH_POINT_REPS" \
  -PlookupScalingMaxHeap="$PH_MAX_HEAP" \
  -PlookupScalingOutput="$PH_OUTPUT"

python3 - <<PY
import json
from pathlib import Path

path = Path("$PH_OUTPUT")
expected = [int(x) for x in "$PH_SIZES".split(",") if x.strip()]
data = json.loads(path.read_text())
by_size = {int(p["dataSize"]): p for p in data.get("perfectHash", [])}
missing = [n for n in expected if n not in by_size]
if missing:
    raise SystemExit(f"missing sizes in output: {missing}")
print(f"==> validated {path}: {len(expected)} point(s)")
PY

python3 docs/gen_charts.py --manifest docs/report_manifest.json

echo "==> lab1 run_all complete: $PH_OUTPUT"
