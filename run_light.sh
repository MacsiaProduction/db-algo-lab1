#!/bin/bash
# Quick pipeline check for lab1 (~1–2 min). Run before ./run_all.sh.
set -euo pipefail
cd "$(dirname "$0")"

echo "==> lab1 run_light: $(date -Iseconds)"
echo "==> Free RAM: $(free -h | awk '/Mem:/ {print $7}')"

./gradlew classes --no-daemon -q

./gradlew lookupScaling --no-daemon -q \
  -PlookupScalingSeries=perfecthash \
  -PlookupScalingDataSizes=1000 \
  -PlookupScalingPointRepetitions=1 \
  -PlookupScalingMaxHeap=4g \
  -PlookupScalingOutput=build/results/lookup_scaling_smoke_v2.json

python3 - <<'PY'
import json
from pathlib import Path

path = Path("build/results/lookup_scaling_smoke_v2.json")
data = json.loads(path.read_text())
points = data.get("perfectHash", [])
assert points, "perfectHash is empty"
p = points[0]
assert p["dataSize"] == 1000 and p["latencyNsPerOp"] > 0
print(f"==> smoke OK: N=1000 latency={p['latencyNsPerOp']:.1f} ns/op")
PY

echo "==> lab1 run_light complete"
