# Отчёт по JMH-бенчмаркам

Этот отчёт привязан к pinned snapshot из `docs/report_manifest.json`. Все числовые таблицы ниже синхронизируются из того же manifest-driven pipeline, что и confidence charts.

**Окружение:** CachyOS Linux 7.0.3 · AMD Ryzen 7 7800X3D · 32 GiB RAM · JDK 23.0.2

## 1. ExtendibleHashTable

Файловая extendible hash table с bucket pages, tombstones и позиционным I/O через `FileChannel`.

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

Для insert при переходе от 1K к 1M время растёт примерно в 2.1x. Это ожидаемо для файловой структуры: с увеличением N расширяется рабочий набор directory и bucket, чаще приходится переписывать bucket page, и чаще срабатывает более дорогой сценарий вставки.

### 1.1a Доверительные интервалы

![HashTable interval scenarios — 95% confidence intervals](docs/img/ht_confidence.png)

<!-- BEGIN GENERATED:HT_CI_TABLE -->
| Operation | Mode | N | Mean | 95% CI | rel.err |
| --- | --- | --- | --- | --- | --- |
| getHit | avgt | 1M | 1.01 us/op | [1.00, 1.02] us | 0.94% |
| getMiss | avgt | 1M | 0.92 us/op | [0.90, 0.93] us | 1.61% |
| updateHit | avgt | 1M | 2.65 us/op | [2.63, 2.68] us | 0.85% |
| updateMiss | avgt | 1M | 0.91 us/op | [0.90, 0.92] us | 0.91% |
| insertOverwrite | avgt | 1M | 2.47 us/op | [2.43, 2.51] us | 1.52% |
| insertGrowthNoSplit | ss | 1M | 6.08 us/op | [5.89, 6.27] us | 3.08% |
| deleteDense | ss | 1M | 3.75 us/op | [3.67, 3.82] us | 2.00% |
| insertGrowthSplit | ss | 1M | 19.07 us/op | [18.34, 19.80] us | 3.84% |
| deleteSparse | ss | 1M | 3.48 us/op | [3.37, 3.59] us | 3.16% |
<!-- END GENERATED:HT_CI_TABLE -->

### 1.2 Использование дискового пространства

<!-- BEGIN GENERATED:HT_DISK_TABLE -->
| N | bytes/entry |
| --- | --- |
| 1K | 86 |
| 100K | 129 |
| 1M | 115 |
<!-- END GENERATED:HT_DISK_TABLE -->


### 1.3 Профиль CPU

![ExtendibleHashTable — профиль CPU](docs/img/ht_cpu_profile.png)

![ExtendibleHashTable.update() — flamegraph](docs/img/ht_update_flamegraph.svg)

## 2. RandomProjection LSH

In-memory LSH-индекс для поиска близких 3D-точек.

### 2.1 Задержки

![LSH findNear — latency + total RAM by data size](docs/img/lsh_lookup_detail.png)

Детальный lookup-scaling прогон идёт по точкам `1K`, затем `10K..1M` с шагом `10K`. График показывает lookup latency, среднее число проверенных кандидатов на lookup и суммарный heap delta структуры в `MiB`.

Рост latency почти полностью следует за числом кандидатов: от `1.5` проверенных точек на `1K` до `114.0` на `1M`. При больших `N` время уходит уже не в сам hash, а в filter + distance + sort.

Заметные ступеньки RAM около `270K` и `530K..540K` — это удвоение capacity;
при переходах через `262144` и `524288` элементов backing arrays резервируются до `524288` и `1048576`.

### 2.1a Доверительные интервалы

`LshIntervalBenchmark` остаётся CI-only suite для canonical warm-cache query batches.

![LSH findNear — 95% confidence intervals](docs/img/lsh_confidence.png)

<!-- BEGIN GENERATED:LSH_CI_TABLE -->
| Operation | Mode | N | Mean | 95% CI | rel.err |
| --- | --- | --- | --- | --- | --- |
| findNear | avgt | 1K | 1.05 us/op | [1.04, 1.06] us | 0.84% |
| findNear | avgt | 100K | 0.94 us/op | [0.90, 0.98] us | 4.60% |
| findNear | avgt | 1M | 12.41 us/op | [12.02, 12.81] us | 3.19% |
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

![PerfectHash lookup — latency + total RAM by data size](docs/img/ph_lookup_detail.png)

<!-- BEGIN GENERATED:PH_LOOKUP_DETAIL_TABLE -->
| N | latency | total RAM | source |
| --- | --- | --- | --- |
| 1K | 31.6 ns/op | 0 MiB | median 5 runs |
| 2K | 29.7 ns/op | 0 MiB | median 5 runs |
| 5K | 31.8 ns/op | 1 MiB | median 5 runs |
| 10K | 33.4 ns/op | 1 MiB | median 5 runs |
| 20K | 37.2 ns/op | 3 MiB | median 5 runs |
| 50K | 42.3 ns/op | 6 MiB | median 5 runs |
| 100K | 45.0 ns/op | 13 MiB | median 5 runs |
| 200K | 45.8 ns/op | 26 MiB | median 5 runs |
| 500K | 51.0 ns/op | 64 MiB | median 5 runs |
| 1M | 53.2 ns/op | 165 MiB | median 10 runs (clean -Xmx32g) |
| 2M | 55.0 ns/op | 329 MiB | median 10 runs (clean -Xmx32g) |
| 5M | 55.8 ns/op | 830 MiB | median 10 runs (clean -Xmx32g) |
| 10M | 57.6 ns/op | 1.61 GiB | median 3 runs (clean -Xmx32g, partial) |
| 20M | 58.5 ns/op | 3.21 GiB | median 10 runs (clean -Xmx32g) |
| 50M | 61.6 ns/op | 8.00 GiB | median 10 runs (clean -Xmx32g) |
<!-- END GENERATED:PH_LOOKUP_DETAIL_TABLE -->

### 3.2a Доверительные интервалы

![PerfectHash — 95% confidence intervals](docs/img/ph_confidence.png)

**Lookup CI**

<!-- BEGIN GENERATED:PH_LOOKUP_CI_TABLE -->
| Operation | Mode | N | Mean | 95% CI | rel.err |
| --- | --- | --- | --- | --- | --- |
| lookup | avgt | 1K | 28.77 ns/op | [28.57, 28.97] ns | 0.71% |
| lookup | avgt | 100K | 91.01 ns/op | [84.85, 97.17] ns | 6.77% |
| lookup | avgt | 1M | 222.15 ns/op | [215.51, 228.79] ns | 2.99% |
<!-- END GENERATED:PH_LOOKUP_CI_TABLE -->

**Build CI**

<!-- BEGIN GENERATED:PH_BUILD_CI_TABLE -->
| Operation | Mode | N | Mean | 95% CI | rel.err |
| --- | --- | --- | --- | --- | --- |
| build | ss | 1K | 60.26 ms/op | [59.54, 60.97] ms | 1.19% |
| build | ss | 100K | 5695.30 ms/op | [5652.40, 5738.20] ms | 0.75% |
| build | ss | 1M | 61517.20 ms/op | [60387.22, 62647.19] ms | 1.84% |
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

![PerfectHashMap.lookup() — профиль CPU (N=50M)](docs/img/ph_lookup_cpu.png)

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
