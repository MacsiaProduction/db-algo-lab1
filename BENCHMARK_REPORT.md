# Отчёт по JMH-бенчмаркам

Этот отчёт привязан к pinned snapshot из `docs/report_manifest.json`. Все числовые таблицы ниже синхронизируются из того же manifest-driven pipeline, что и confidence charts.

**Окружение:** CachyOS Linux 7.0.3 · AMD Ryzen 7 7800X3D · 30 GiB RAM · JDK 23.0.2

## 1. ExtendibleHashTable

Файловая extendible hash table с bucket pages, tombstones и позиционным I/O через `FileChannel`.

### 1.1 Средние задержки

![ExtendibleHashTable — средние задержки](docs/img/ht_latency.png)

<!-- BEGIN GENERATED:HT_LATENCY_TABLE -->
| Operation | 1K | 100K | 1M |
| --- | --- | --- | --- |
| get | 0.81 | 0.89 | 1.12 |
| update | 1.96 | 2.04 | 2.68 |
| insert | 3.17 | 3.32 | 6.68 |
| delete | 7.40 | 7.81 | 10.63 |
<!-- END GENERATED:HT_LATENCY_TABLE -->

Для insert при переходе от 1K к 1M время растёт примерно в 2.1x. Это ожидаемо для файловой структуры: с увеличением N расширяется рабочий набор directory и bucket, чаще приходится переписывать bucket page, и чаще срабатывает более дорогой сценарий вставки.

### 1.1a Доверительные интервалы

![HashTable interval scenarios — 95% confidence intervals](docs/img/ht_confidence.png)

<!-- BEGIN GENERATED:HT_CI_TABLE -->
| Operation | Mode | N | Mean | 95% CI | rel.err |
| --- | --- | --- | --- | --- | --- |
| getHit | avgt | 1M | 0.79 us/op | [0.79, 0.79] us | 0.46% |
| getMiss | avgt | 1M | 0.76 us/op | [0.76, 0.77] us | 0.52% |
| updateHit | avgt | 1M | 3.05 us/op | [3.03, 3.06] us | 0.44% |
| updateMiss | avgt | 1M | 0.78 us/op | [0.78, 0.78] us | 0.15% |
| insertOverwrite | avgt | 1M | 3.01 us/op | [3.01, 3.01] us | 0.13% |
| insertGrowthNoSplit | ss | 1M | 9.67 us/op | [9.63, 9.71] us | 0.37% |
| deleteDense | ss | 1M | 5.13 us/op | [5.11, 5.14] us | 0.32% |
| insertGrowthSplit | ss | 1M | 12.30 us/op | [11.83, 12.77] us | 3.84% |
| deleteSparse | ss | 1M | 5.42 us/op | [5.36, 5.49] us | 1.22% |
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

<!-- BEGIN GENERATED:LSH_LOOKUP_DETAIL_TABLE -->
| N | latency | total RAM | avg checked candidates | avg matches |
| --- | --- | --- | --- | --- |
| 1K | 0.16 us/op | 1 MiB | 1.5 | 1.5 |
| 10K | 0.18 us/op | 10 MiB | 2.3 | 2.3 |
| 20K | 0.26 us/op | 19 MiB | 3.6 | 3.5 |
| 30K | 0.21 us/op | 26 MiB | 4.8 | 4.7 |
| 40K | 0.22 us/op | 34 MiB | 5.6 | 5.5 |
| 50K | 0.23 us/op | 40 MiB | 7.1 | 6.9 |
| 60K | 0.25 us/op | 44 MiB | 7.8 | 7.6 |
| 70K | 0.29 us/op | 50 MiB | 8.9 | 8.6 |
| 80K | 0.30 us/op | 55 MiB | 9.9 | 9.6 |
| 90K | 0.33 us/op | 59 MiB | 11.2 | 11.0 |
| 100K | 0.40 us/op | 63 MiB | 12.6 | 12.3 |
| 110K | 0.46 us/op | 67 MiB | 13.7 | 13.3 |
| 120K | 0.51 us/op | 70 MiB | 14.8 | 14.5 |
| 130K | 0.50 us/op | 73 MiB | 15.9 | 15.6 |
| 140K | 0.59 us/op | 85 MiB | 17.0 | 16.6 |
| 150K | 0.59 us/op | 93 MiB | 18.3 | 17.9 |
| 160K | 0.62 us/op | 95 MiB | 19.5 | 19.0 |
| 170K | 0.64 us/op | 98 MiB | 20.6 | 20.1 |
| 180K | 0.68 us/op | 100 MiB | 21.7 | 21.2 |
| 190K | 0.69 us/op | 102 MiB | 22.8 | 22.2 |
| 200K | 0.79 us/op | 104 MiB | 24.0 | 23.4 |
| 210K | 0.76 us/op | 107 MiB | 25.0 | 24.4 |
| 220K | 0.79 us/op | 109 MiB | 26.3 | 25.7 |
| 230K | 0.93 us/op | 111 MiB | 27.4 | 26.8 |
| 240K | 0.88 us/op | 113 MiB | 28.5 | 27.9 |
| 250K | 0.89 us/op | 115 MiB | 29.6 | 28.9 |
| 260K | 1.08 us/op | 116 MiB | 31.0 | 30.2 |
| 270K | 1.45 us/op | 133 MiB | 32.2 | 31.4 |
| 280K | 1.50 us/op | 148 MiB | 33.0 | 32.2 |
| 290K | 1.76 us/op | 150 MiB | 34.2 | 33.3 |
| 300K | 1.68 us/op | 151 MiB | 35.3 | 34.4 |
| 310K | 1.92 us/op | 153 MiB | 36.5 | 35.6 |
| 320K | 1.86 us/op | 155 MiB | 37.7 | 36.8 |
| 330K | 2.08 us/op | 157 MiB | 38.9 | 37.9 |
| 340K | 2.01 us/op | 159 MiB | 40.2 | 39.2 |
| 350K | 2.21 us/op | 161 MiB | 41.4 | 40.3 |
| 360K | 2.09 us/op | 162 MiB | 42.3 | 41.2 |
| 370K | 2.15 us/op | 164 MiB | 43.5 | 42.4 |
| 380K | 2.20 us/op | 166 MiB | 44.6 | 43.4 |
| 390K | 2.29 us/op | 167 MiB | 45.6 | 44.5 |
| 400K | 2.48 us/op | 168 MiB | 46.8 | 45.6 |
| 410K | 2.37 us/op | 169 MiB | 47.8 | 46.6 |
| 420K | 2.57 us/op | 171 MiB | 49.0 | 47.8 |
| 430K | 2.48 us/op | 173 MiB | 50.0 | 48.8 |
| 440K | 2.55 us/op | 174 MiB | 51.1 | 49.9 |
| 450K | 2.60 us/op | 176 MiB | 52.3 | 51.0 |
| 460K | 2.67 us/op | 178 MiB | 53.8 | 52.4 |
| 470K | 2.86 us/op | 179 MiB | 54.9 | 53.5 |
| 480K | 2.80 us/op | 182 MiB | 56.0 | 54.6 |
| 490K | 3.01 us/op | 184 MiB | 57.1 | 55.7 |
| 500K | 2.92 us/op | 185 MiB | 58.3 | 56.8 |
| 510K | 2.95 us/op | 187 MiB | 59.4 | 57.9 |
| 520K | 3.00 us/op | 188 MiB | 60.5 | 59.0 |
| 530K | 3.21 us/op | 205 MiB | 61.7 | 60.2 |
| 540K | 3.21 us/op | 223 MiB | 62.9 | 61.3 |
| 550K | 3.13 us/op | 224 MiB | 64.0 | 62.4 |
| 560K | 3.32 us/op | 225 MiB | 65.0 | 63.4 |
| 570K | 3.40 us/op | 226 MiB | 66.1 | 64.5 |
| 580K | 3.28 us/op | 228 MiB | 67.4 | 65.8 |
| 590K | 3.49 us/op | 229 MiB | 68.5 | 66.9 |
| 600K | 3.57 us/op | 231 MiB | 69.8 | 68.1 |
| 610K | 3.62 us/op | 232 MiB | 71.1 | 69.3 |
| 620K | 3.52 us/op | 234 MiB | 72.3 | 70.5 |
| 630K | 3.73 us/op | 235 MiB | 73.5 | 71.7 |
| 640K | 3.59 us/op | 236 MiB | 74.5 | 72.6 |
| 650K | 3.64 us/op | 237 MiB | 75.1 | 73.2 |
| 660K | 3.68 us/op | 238 MiB | 76.3 | 74.5 |
| 670K | 4.06 us/op | 240 MiB | 77.4 | 75.5 |
| 680K | 4.06 us/op | 241 MiB | 78.7 | 76.8 |
| 690K | 4.14 us/op | 243 MiB | 79.8 | 77.9 |
| 700K | 3.98 us/op | 244 MiB | 80.8 | 78.9 |
| 710K | 3.96 us/op | 245 MiB | 82.0 | 80.0 |
| 720K | 4.21 us/op | 246 MiB | 83.1 | 81.1 |
| 730K | 4.07 us/op | 248 MiB | 84.3 | 82.2 |
| 740K | 4.49 us/op | 249 MiB | 85.3 | 83.3 |
| 750K | 4.42 us/op | 250 MiB | 86.4 | 84.3 |
| 760K | 4.50 us/op | 251 MiB | 87.5 | 85.5 |
| 770K | 4.62 us/op | 253 MiB | 88.8 | 86.7 |
| 780K | 4.34 us/op | 254 MiB | 89.9 | 87.8 |
| 790K | 4.65 us/op | 255 MiB | 90.9 | 88.8 |
| 800K | 4.67 us/op | 256 MiB | 92.0 | 89.9 |
| 810K | 4.73 us/op | 258 MiB | 93.1 | 90.9 |
| 820K | 4.56 us/op | 259 MiB | 94.2 | 92.0 |
| 830K | 4.61 us/op | 260 MiB | 95.5 | 93.2 |
| 840K | 4.66 us/op | 261 MiB | 96.7 | 94.4 |
| 850K | 4.98 us/op | 263 MiB | 97.9 | 95.6 |
| 860K | 5.02 us/op | 264 MiB | 98.9 | 96.6 |
| 870K | 4.83 us/op | 265 MiB | 100.0 | 97.6 |
| 880K | 4.87 us/op | 267 MiB | 101.1 | 98.7 |
| 890K | 5.17 us/op | 268 MiB | 102.2 | 99.8 |
| 900K | 5.22 us/op | 269 MiB | 103.0 | 100.7 |
| 910K | 5.28 us/op | 270 MiB | 104.4 | 102.0 |
| 920K | 5.35 us/op | 272 MiB | 105.4 | 103.0 |
| 930K | 5.40 us/op | 273 MiB | 106.3 | 103.8 |
| 940K | 5.45 us/op | 274 MiB | 107.5 | 105.0 |
| 950K | 5.23 us/op | 275 MiB | 108.8 | 106.2 |
| 960K | 5.56 us/op | 277 MiB | 109.7 | 107.1 |
| 970K | 5.31 us/op | 278 MiB | 110.6 | 108.0 |
| 980K | 5.68 us/op | 279 MiB | 111.7 | 109.2 |
| 990K | 5.49 us/op | 280 MiB | 112.9 | 110.3 |
| 1M | 5.81 us/op | 281 MiB | 114.0 | 111.4 |
<!-- END GENERATED:LSH_LOOKUP_DETAIL_TABLE -->

Рост latency почти полностью следует за числом кандидатов: от `1.5` проверенных точек на `1K` до `114.0` на `1M`. При больших `N` время уходит уже не в сам hash, а в filter + distance + sort.

Заметные ступеньки RAM около `270K` и `530K..540K` — это capacity cliffs, а не изменение плотности данных. `RandomProjectionLshIndex` хранит `ids/xs/ys/zs` в массивах с удвоением capacity; при переходах через `262144` и `524288` элементов backing arrays резервируются до `524288` и `1048576`. Рядом с этими точками также накладываются resize внутренних `HashMap`/`IntBag` buckets, поэтому на графике скачок выглядит как короткая двухточечная ступенька. Candidate count при этом растёт гладко, значит это эффект выделенной ёмкости, а не деградация LSH-хеша.

### 2.1a Доверительные интервалы

`LshIntervalBenchmark` остаётся CI-only suite для canonical warm-cache query batches.

![LSH findNear — 95% confidence intervals](docs/img/lsh_confidence.png)

<!-- BEGIN GENERATED:LSH_CI_TABLE -->
| Operation | Mode | N | Mean | 95% CI | rel.err |
| --- | --- | --- | --- | --- | --- |
| findNear | avgt | 1K | 0.10 us/op | [0.10, 0.10] us | 1.57% |
| findNear | avgt | 100K | 0.70 us/op | [0.70, 0.70] us | 0.26% |
| findNear | avgt | 1M | 5.53 us/op | [5.51, 5.54] us | 0.26% |
<!-- END GENERATED:LSH_CI_TABLE -->

### 2.2 Потребление памяти

<!-- BEGIN GENERATED:LSH_MEMORY_TABLE -->
| N | bytes/entry |
| --- | --- |
| 1K | 1215 |
| 100K | 685 |
| 1M | 298 |
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
| 1K | 56.36 |
| 100K | 5282.03 |
| 1M | 53552.87 |
<!-- END GENERATED:PH_BUILD_TABLE -->

### 3.2 Lookup

![PerfectHash lookup — latency + total RAM by data size](docs/img/ph_lookup_detail.png)

Отдельный scaling-runner строит actual logarithmic lookup-серию от `1K` до `150M` ключей. Для каждой строки показаны lookup latency и суммарный heap delta таблицы. Точки `1K..50M` сглажены медианой по пяти независимым rebuild + lookup прогонам; `100M` — медиана по двум rebuild + lookup прогонам; `150M` — медиана по пяти rebuild + lookup прогонам.

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
| 500K | 57.3 ns/op | 64 MiB | median 5 runs |
| 1M | 54.1 ns/op | 129 MiB | median 5 runs |
| 2M | 51.8 ns/op | 259 MiB | median 5 runs |
| 5M | 54.3 ns/op | 657 MiB | median 5 runs |
| 10M | 58.8 ns/op | 1.27 GiB | median 5 runs |
| 20M | 76.5 ns/op | 2.52 GiB | median 5 runs |
| 50M | 85.6 ns/op | 6.29 GiB | median 5 runs |
| 100M | 104.6 ns/op | 12.58 GiB | median 2 runs |
| 150M | 105.6 ns/op | 18.23 GiB | median 5 runs |
<!-- END GENERATED:PH_LOOKUP_DETAIL_TABLE -->

Скачок latency между `10M` и `20M` не выглядит как resize структуры: heap остаётся почти линейным (`136.5 -> 135.5` bytes/entry), а медиана по пяти rebuild стабильно повторяет рост (`58.8 -> 76.5 ns/op`). Более вероятная причина — переход в режим худшей locality: lookup делает top-level hash, обращение в большой `buckets[]`, затем pointer chase в bucket object / secondary arrays / key. На `10M` один top-level reference array занимает порядка `40 MiB`, на `20M` уже около `80 MiB`; вместе с вторичными объектами hot working set перестаёт комфортно помещаться в L3/TLB 7800X3D. Поэтому это следует читать как cache/TLB cliff для random-like lookup, а не как алгоритмический скачок числа слотов.

**Подтверждение O(1).** Каждый lookup выполняет ровно два hash-вычисления и два array-обращения независимо от N — это теоретическая O(1)-операция FKS-таблицы. Данные это подтверждают на всём диапазоне `1K..150M`. Переход `100M → 150M` (+50% данных) даёт прирост latency всего `104.6 → 105.6 ns/op` (+1%); переход `50M → 150M` (+200% данных) — `85.6 → 105.6 ns/op` (+23%), причём большая часть этого роста уже заложена в `50M → 100M`. Heap при этом растёт строго линейно (`~130–136 bytes/entry` на всём диапазоне `10M..150M`), то есть memory usage O(N), а latency практически не реагирует на дальнейшее масштабирование N. Суммарно от `1K` до `150M` (рост данных в 150 000×) latency выросла лишь в ~3.3×, и весь этот рост объясняется иерархией памяти (L1 → L2 → L3 → RAM), а не алгоритмической сложностью. Начиная примерно с `50M`, когда рабочий набор полностью уходит в RAM, latency стабилизируется в диапазоне `85–106 ns/op` — это поведение O(1) в режиме random-access DRAM.

### 3.2a Доверительные интервалы

`PerfectHash` использует два CI-only suite: `PerfectHashLookupIntervalBenchmark` для steady-state lookup и `PerfectHashBuildIntervalBenchmark` для build path.

![PerfectHash — 95% confidence intervals](docs/img/ph_confidence.png)

**Lookup CI**

<!-- BEGIN GENERATED:PH_LOOKUP_CI_TABLE -->
| Operation | Mode | N | Mean | 95% CI | rel.err |
| --- | --- | --- | --- | --- | --- |
| lookup | avgt | 1K | 22.26 ns/op | [22.25, 22.27] ns | 0.04% |
| lookup | avgt | 100K | 37.21 ns/op | [37.10, 37.32] ns | 0.28% |
| lookup | avgt | 1M | 39.59 ns/op | [39.52, 39.65] ns | 0.17% |
<!-- END GENERATED:PH_LOOKUP_CI_TABLE -->

**Build CI**

<!-- BEGIN GENERATED:PH_BUILD_CI_TABLE -->
| Operation | Mode | N | Mean | 95% CI | rel.err |
| --- | --- | --- | --- | --- | --- |
| build | ss | 1K | 56.85 ms/op | [56.11, 57.58] ms | 1.29% |
| build | ss | 100K | 5296.23 ms/op | [5291.07, 5301.40] ms | 0.10% |
| build | ss | 1M | 53596.26 ms/op | [53563.65, 53628.88] ms | 0.06% |
<!-- END GENERATED:PH_BUILD_CI_TABLE -->

### 3.3 Память и структура

<!-- BEGIN GENERATED:PH_MEMORY_TABLE -->
| N | heap bytes/entry | total slots | slots/entry |
| --- | --- | --- | --- |
| 1K | 94 | 4110 | 4.11 |
| 100K | 75 | 409484 | 4.09 |
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
| PerfectHash (heap) | 94 | 75 | 79 |
| LSH (heap) | 1215 | 685 | 298 |
<!-- END GENERATED:MEMORY_COMPARISON_TABLE -->
