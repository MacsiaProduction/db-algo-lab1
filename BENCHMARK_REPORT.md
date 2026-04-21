# Отчёт по JMH-бенчмаркам

Этот отчёт привязан к pinned snapshot из `docs/report_manifest.json`. Все числовые таблицы ниже синхронизируются из того же manifest-driven pipeline, что и confidence charts.

**Окружение:** macOS Darwin 25.4.0 · m1 pro · JDK 23.0.2

## 1. ExtendibleHashTable

Файловая extendible hash table со slotted bucket pages, tombstones и позиционным I/O через `FileChannel`.

### 1.1 Средние задержки

![ExtendibleHashTable — средние задержки](docs/img/ht_latency.png)

<!-- BEGIN GENERATED:HT_LATENCY_TABLE -->
| Operation | 1K | 100K | 1M |
| --- | --- | --- | --- |
| get | 0.83 | 1.37 | 2.32 |
| update | 1.71 | 2.64 | 3.19 |
| insert | 2.38 | 3.71 | 11.28 |
| delete | 5.29 | 7.80 | 12.39 |
<!-- END GENERATED:HT_LATENCY_TABLE -->

### 1.1a Доверительные интервалы

CI-режим использует отдельный `HashTableIntervalBenchmark` со split-сценариями; mixed `get/update/insert/delete` остаются только в latency/tail suites.

![HashTable interval scenarios — 95% confidence intervals](docs/img/ht_confidence.png)

<!-- BEGIN GENERATED:HT_CI_TABLE -->
| Tier | Operation | Mode | N | Mean | 95% CI | rel.err | Gate |
| --- | --- | --- | --- | --- | --- | --- | --- |
| strict | getHit | avgt | 1M | 1.01 us/op | [1.00, 1.02] us | 0.94% | PASS |
| strict | getMiss | avgt | 1M | 0.92 us/op | [0.90, 0.93] us | 1.61% | PASS |
| strict | updateHit | avgt | 1M | 2.65 us/op | [2.63, 2.68] us | 0.85% | PASS |
| strict | updateMiss | avgt | 1M | 0.91 us/op | [0.90, 0.92] us | 0.91% | PASS |
| strict | insertOverwrite | avgt | 1M | 2.47 us/op | [2.43, 2.51] us | 1.52% | PASS |
| strict | insertGrowthNoSplit | ss | 1M | 6.08 us/op | [5.89, 6.27] us | 3.08% | FAIL |
| strict | deleteDense | ss | 1M | 3.75 us/op | [3.67, 3.82] us | 2.00% | FAIL |
| diagnostic | insertGrowthSplit | ss | 1M | 19.07 us/op | [16.62, 21.52] us | 12.84% | diagnostic |
| diagnostic | deleteSparse | ss | 1M | 3.48 us/op | [3.37, 3.59] us | 3.16% | diagnostic |
<!-- END GENERATED:HT_CI_TABLE -->

Текущий pinned snapshot всё ещё держит основное давление в `insertGrowthNoSplit` и `deleteDense`; остальные strict cases должны оставаться существенно стабильнее mixed baseline.

### 1.2 Использование дискового пространства

<!-- BEGIN GENERATED:HT_DISK_TABLE -->
| N | bytes/entry |
| --- | --- |
| 1K | 86 |
| 100K | 129 |
| 1M | 115 |
<!-- END GENERATED:HT_DISK_TABLE -->

### 1.3 Хвосты задержек

![HashTable get/insert — персентили](docs/img/ht_percentiles.png)

<!-- BEGIN GENERATED:HT_TAIL_TABLE -->
| Operation | p50 @ 1M | p90 @ 1M | p99 @ 1M | p99.99 @ 1M |
| --- | --- | --- | --- | --- |
| get | 2.04 | 2.62 | 6.66 | 195.27 |
| update | 3.25 | 4.46 | 6.29 | 130.16 |
| insert | 6.04 | 11.41 | 112.33 | 2439.94 |
| delete | 8.91 | 11.95 | 121.98 | 1219.77 |
<!-- END GENERATED:HT_TAIL_TABLE -->

### 1.4 Профиль CPU

![ExtendibleHashTable — профиль CPU](docs/img/ht_cpu_profile.png)

![ExtendibleHashTable.update() — flamegraph](docs/img/ht_update_flamegraph.svg)

## 2. RandomProjection LSH

In-memory LSH-индекс для поиска близких 3D-точек.

### 2.1 Задержки

<!-- BEGIN GENERATED:LSH_LATENCY_TABLE -->
| N | ops/ms | us/op | p50 (us) | p90 (us) | p99 (us) |
| --- | --- | --- | --- | --- | --- |
| 1K | 921.7 | 1.08 | 1.08 | 1.17 | 1.42 |
| 100K | 961.1 | 1.04 | 1.00 | 1.50 | 3.42 |
| 1M | 85.4 | 11.71 | 12.03 | 16.45 | 22.62 |
<!-- END GENERATED:LSH_LATENCY_TABLE -->

### 2.1a Доверительные интервалы

`LshIntervalBenchmark` остаётся CI-only suite для canonical warm-cache query batches.

![LSH findNear — 95% confidence intervals](docs/img/lsh_confidence.png)

<!-- BEGIN GENERATED:LSH_CI_TABLE -->
| Tier | Operation | Mode | N | Mean | 95% CI | rel.err | Gate |
| --- | --- | --- | --- | --- | --- | --- | --- |
| strict | findNear | avgt | 1K | 1.05 us/op | [1.04, 1.06] us | 0.84% | PASS |
| strict | findNear | avgt | 100K | 0.90 us/op | [0.89, 0.92] us | 1.67% | PASS |
| strict | findNear | avgt | 1M | 11.48 us/op | [11.36, 11.59] us | 0.99% | PASS |
<!-- END GENERATED:LSH_CI_TABLE -->

### 2.2 Потребление памяти

<!-- BEGIN GENERATED:LSH_MEMORY_TABLE -->
| N | bytes/entry |
| --- | --- |
| 1K | 1240 |
| 100K | 751 |
| 1M | 327 |
<!-- END GENERATED:LSH_MEMORY_TABLE -->

### 2.3 Профиль CPU

![LSH findNear — профиль CPU](docs/img/lsh_cpu_profile.png)

![RandomProjectionLshIndex.findNear() — flamegraph](docs/img/lsh_find_near_flamegraph.svg)

## 3. PerfectHashMap

Статическая двухуровневая FKS-таблица: build дорогой, lookup быстрый и предсказуемый.

### 3.1 Build

<!-- BEGIN GENERATED:PH_BUILD_TABLE -->
| N | build time (ms) |
| --- | --- |
| 1K | 63.32 |
| 100K | 5642.74 |
| 1M | 60867.74 |
<!-- END GENERATED:PH_BUILD_TABLE -->

### 3.2 Lookup

<!-- BEGIN GENERATED:PH_LOOKUP_TABLE -->
| N | ops/ms | ns/op | p50 (ns) | p90 (ns) | p99 (ns) |
| --- | --- | --- | --- | --- | --- |
| 1K | 36020.5 | 27.76 | 42 | 42 | 83 |
| 100K | 21980.9 | 45.49 | 42 | 125 | 209 |
| 1M | 7759.0 | 128.88 | 125 | 292 | 417 |
<!-- END GENERATED:PH_LOOKUP_TABLE -->

### 3.2a Доверительные интервалы

`PerfectHash` использует два CI-only suite: `PerfectHashLookupIntervalBenchmark` для steady-state lookup и `PerfectHashBuildIntervalBenchmark` для build path.

![PerfectHash — 95% confidence intervals](docs/img/ph_confidence.png)

**Lookup CI**

<!-- BEGIN GENERATED:PH_LOOKUP_CI_TABLE -->
| Tier | Operation | Mode | N | Mean | 95% CI | rel.err | Gate |
| --- | --- | --- | --- | --- | --- | --- | --- |
| strict | lookup | avgt | 1K | 28.77 ns/op | [28.57, 28.98] ns | 0.71% | PASS |
| strict | lookup | avgt | 100K | 50.39 ns/op | [49.39, 51.39] ns | 1.98% | PASS |
| strict | lookup | avgt | 1M | 84.79 ns/op | [83.59, 86.00] ns | 1.42% | PASS |
<!-- END GENERATED:PH_LOOKUP_CI_TABLE -->

**Build CI**

<!-- BEGIN GENERATED:PH_BUILD_CI_TABLE -->
| Tier | Operation | Mode | N | Mean | 95% CI | rel.err | Gate |
| --- | --- | --- | --- | --- | --- | --- | --- |
| heavy | build | ss | 1K | 60.26 ms/op | [59.54, 60.97] ms | 1.19% | PASS |
| heavy | build | ss | 100K | 5695.30 ms/op | [5652.40, 5738.20] ms | 0.75% | PASS |
| heavy | build | ss | 1M | 61517.20 ms/op | [60387.22, 62647.19] ms | 1.84% | PASS |
<!-- END GENERATED:PH_BUILD_CI_TABLE -->

### 3.3 Память и структура

<!-- BEGIN GENERATED:PH_MEMORY_TABLE -->
| N | heap bytes/entry | total slots | slots/entry |
| --- | --- | --- | --- |
| 1K | 100 | 4110 | 4.11 |
| 100K | 79 | 409484 | 4.09 |
| 1M | 79 | 4040136 | 4.04 |
<!-- END GENERATED:PH_MEMORY_TABLE -->

### 3.4 Профиль CPU

![PerfectHashMap.lookup() — flamegraph](docs/img/ph_lookup_flamegraph.svg)

## 4. Сравнение памяти

![Потребление памяти на запись](docs/img/memory_per_entry.png)

<!-- BEGIN GENERATED:MEMORY_COMPARISON_TABLE -->
| Structure | 1K | 100K | 1M |
| --- | --- | --- | --- |
| HashTable (disk) | 86 | 129 | 115 |
| PerfectHash (heap) | 100 | 79 | 79 |
| LSH (heap) | 1240 | 751 | 327 |
<!-- END GENERATED:MEMORY_COMPARISON_TABLE -->
