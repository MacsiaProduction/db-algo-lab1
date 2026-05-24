# Database Algorithms Lab 1

Реализация и benchmark-исследование трёх структур данных на Kotlin/JVM:

1. `ExtendibleHashTable` — file-based hash table со slotted bucket pages
2. `PerfectHashMap` — статическая FKS-таблица
3. `RandomProjectionLshIndex` — LSH для 3D-точек

## Быстрый старт

```bash
# Smoke check (~1 min)
./run_light.sh

# PH transition-zone scaling + report refresh
./run_all.sh
```

```bash
# Unit tests
./gradlew test

# Базовый JMH прогон (единый results.json)
./gradlew jmh

# HashTable quick: короткий цикл для phase drift / batch tuning
./gradlew jmh -PjmhPreset=quick -PjmhResultFile=build/results/jmh/ht_quick.json \
  -PjmhInclude='dbalgo.hashtable.HashTableIntervalBenchmark.bench(GetHit|GetMiss|UpdateHit|UpdateMiss|InsertOverwrite|InsertGrowthNoSplit|InsertGrowthSplit|DeleteDense|DeleteSparse)$'

# HashTable interval core subset, N=1M
./gradlew jmh -PjmhPreset=gate -PjmhResultFile=build/results/jmh/ht_gate.json \
  -PjmhInclude='dbalgo.hashtable.HashTableIntervalBenchmark.bench(GetHit|GetMiss|UpdateHit|UpdateMiss|InsertOverwrite|InsertGrowthNoSplit|DeleteDense)$'

# HashTable interval structural subset
./gradlew jmh -PjmhPreset=diag -PjmhResultFile=build/results/jmh/ht_diag.json \
  -PjmhInclude='dbalgo.hashtable.HashTableIntervalBenchmark.bench(InsertGrowthSplit|DeleteSparse)$'

# LSH CI-only interval suite
./gradlew jmh -PjmhPreset=lshGate -PjmhResultFile=build/results/jmh/lsh_ci.json \
  -PjmhInclude='dbalgo.lsh.LshIntervalBenchmark.benchFindNear$'

# PerfectHash lookup CI-only interval suite
./gradlew jmh -PjmhPreset=phLookupGate -PjmhResultFile=build/results/jmh/ph_lookup_ci.json \
  -PjmhInclude='dbalgo.perfecthash.PerfectHashLookupIntervalBenchmark.benchLookup$'

# PerfectHash build CI-only interval suite
./gradlew jmh -PjmhPreset=phBuildGate -PjmhResultFile=build/results/jmh/ph_build_ci.json \
  -PjmhInclude='dbalgo.perfecthash.PerfectHashBuildIntervalBenchmark.benchBuild$'

# Detailed lookup scaling (LSH: 1K + 10K..1M; PerfectHash: log scale 1K..50M)
./gradlew lookupScaling

# Dense LSH lookup scaling only
./gradlew lookupScaling -PlookupScalingSeries=lsh \
  -PlookupScalingOutput=build/results/lookup_scaling_lsh_1k_1m_step_10k.json

# PerfectHash logarithmic lookup scaling only
./gradlew lookupScaling -PlookupScalingSeries=perfecthash \
  -PlookupScalingOutput=build/results/lookup_scaling_ph_log_1k_50m.json \
  -PlookupScalingMaxHeap=32g

# PerfectHash lookup CPU profile for the report graph, N=50M
./gradlew jmh -Pprofile -PjmhPreset=precise -PjmhDataSize=50000000 \
  -PjmhMaxHeap=32g \
  -PjmhInclude='dbalgo.perfecthash.PerfectHashBenchmark.benchLookup$'

# Short smoke run with explicit sizes
./gradlew lookupScaling -PlookupScalingSeries=lsh -PlookupScalingDataSizes=1000,10000,20000

# Detailed lookup scaling with a custom RAM budget line
./gradlew lookupScaling -PlookupScalingRamBudgetGiB=8

# Detailed lookup scaling with custom retry counts
./gradlew lookupScaling -PlookupScalingMeasurementRepetitions=15 -PlookupScalingWarmupRepetitions=3

# Detailed PerfectHash lookup scaling with median across repeated point rebuilds
./gradlew lookupScaling -PlookupScalingSeries=perfecthash \
  -PlookupScalingOutput=build/results/lookup_scaling_ph_log_1k_50m_median5.json \
  -PlookupScalingMaxHeap=32g \
  -PlookupScalingPointRepetitions=5

# Manifest-driven confidence summaries
python3 docs/jmh_confidence.py --manifest docs/report_manifest.json --prefix dbalgo.hashtable.HashTableIntervalBenchmark --unit us --markdown
python3 docs/jmh_confidence.py --manifest docs/report_manifest.json --prefix dbalgo.lsh.LshIntervalBenchmark --unit us --markdown
python3 docs/jmh_confidence.py --manifest docs/report_manifest.json --prefix dbalgo.perfecthash.PerfectHashLookupIntervalBenchmark --unit ns --markdown
python3 docs/jmh_confidence.py --manifest docs/report_manifest.json --prefix dbalgo.perfecthash.PerfectHashBuildIntervalBenchmark --unit ms --markdown

# Генерация charts + sync числовых таблиц в BENCHMARK_REPORT.md
python3 docs/gen_charts.py --manifest docs/report_manifest.json
```

## CI policy

- `HashTableIntervalBenchmark`
  - `getHit`, `getMiss`, `updateHit`, `updateMiss`, `insertOverwrite`, `deleteDense`, `deleteSparse` -> `95% CI half-width <= 2%`
  - `insertGrowthNoSplit` -> `<= 3%`
  - `insertGrowthSplit` -> `<= 3.84%`
- `LshIntervalBenchmark.benchFindNear` -> `<= 2%`
- `PerfectHashLookupIntervalBenchmark.benchLookup` -> `<= 2%`
- `PerfectHashBuildIntervalBenchmark.benchBuild` -> `<= 3%`

## Reporting workflow

`docs/report_manifest.json` — единственный источник правды для published snapshot. `docs/jmh_confidence.py` и `docs/gen_charts.py` читают только manifest-listed JSON-файлы и больше не сканируют весь `build/results/jmh/*.json`.

`docs/gen_charts.py`:

- строит `ht_confidence.png`, `lsh_confidence.png`, `ph_confidence.png`
- строит `lsh_lookup_detail.png` и `ph_lookup_detail.png` из `lookup_scaling` dataset
  с latency, total RAM и LSH candidate-count diagnostics
- обновляет таблицы в `BENCHMARK_REPORT.md` между generated block markers
- падает, если в pinned snapshot нет обязательного dataset или обязательного размера

`docs/jmh_confidence.py --results ...` остаётся ad-hoc режимом для разовых проверок вне pinned snapshot.

Сводный manifest-driven отчёт находится в `BENCHMARK_REPORT.md`.
