# Отчёт по JMH-бенчмаркам

**Окружение:** macOS Darwin 25.4.0 · m1 pro · JDK 23.0.2 · `@Fork(1)`
**JMH:** обычно `@Warmup(2×2s)` + `@Measurement(3×2s)`; для `PerfectHashBenchmark.benchBuild` — `2×1s` + `3×1s`
---

## 1. ExtendibleHashTable

Файловая хеш-таблица с отдельным файлом на бакет и позиционным I/O через `FileChannel`.

### 1.1 Средние задержки (SampleTime, µs/op)

![ExtendibleHashTable — средние задержки](docs/img/ht_latency.png)

| Operation |    1K |  100K | 1M |
|-----------|------:|------:|---:|
| get |  3.62 |  3.84 | 4.72 |
| update |  6.04 |  5.57 | 7.22 |
| insert |  6.18 |  8.41 | 29.21 |
| delete | 24.84 | 44.22 | 56.07 |

- Реальный size-effect виден между `100K` и `1M`: при заполнении половины таблицы рабочее множество bucket-файлов растёт примерно с `~1.6 MB` до `~16 MB`, и это уже заметно по `get`/`insert`/`delete`.
- `delete` остаётся дорогим по смыслу операции: он действительно уменьшает bucket-файл и не может избежать `truncate()`.

### 1.2 Использование дискового пространства

| N | bytes/entry |
|---|------------:|
| 1K | 32 |
| 100K | 32 |
| 1M | 32 |

После перехода на фиксированную длину benchmark-ключей и значений расход стал стабильным: на запись приходится примерно `32 B`, а рост размера данных больше не искажает таблицу через разную длину строк.

### 1.3 Хвосты задержек

![HashTable get/insert — персентили](docs/img/ht_percentiles.png)

| Operation | p50 @ 1M | p99 @ 1M | p99.9 @ 1M | p99.99 @ 1M |
|-----------|---------:|---------:|-----------:|------------:|
| get | 4.08 | 9.74 | 47.25 | 873.03 |
| update | 7.21 | 18.56 | 74.92 | 569.13 |
| insert | 11.04 | 218.11 | 2 887.29 | 21 754.86 |
| delete | 47.04 | 111.08 | 1 605.55 | 9 243.37 |

- `insert` резко тяжелее по хвостам, потому что на больших размерах чаще попадает в split и directory update.
- `delete` остаётся худшим, потому что почти всегда реально меняет размер bucket-файла.

### 1.4 Профиль CPU (async-profiler, N=1M)

![ExtendibleHashTable — профиль CPU](docs/img/ht_cpu_profile.png)

| Operation | Syscalls | User code | JDK | MemCopy | Top hot method |
|-----------|--------:|----------:|----:|--------:|:---------------|
| get | 56% | 8% | 31% | 2% | `pread` 23% |
| insert | 75% | 8% | 14% | 1% | `__unlink` 21%, `pwrite` 20% |
| update | 64% | 16% | 15% | 3% | `fstat` 21%, `pread` 17% |
| delete | 87% | 4% | 4% | <1% | `ftruncate` 55% |

![ExtendibleHashTable.update() — flamegraph](docs/img/ht_update_flamegraph.svg)

---

## 2. RandomProjection LSH

In-memory LSH-индекс для поиска близких 3D-точек на основе random projection hash.

### 2.1 Задержки

| N | ops/ms | µs/op | p50 (µs) | p90 (µs) | p99 (µs) |
|---|-------:|------:|---------:|---------:|---------:|
| 1K | 48.1 | 20.80 | 18.37 | 20.45 | 34.11 |
| 100K | 207.0 | 4.83 | 3.37 | 5.58 | 9.95 |
| 1M | 23.2 | 43.13 | 39.87 | 55.04 | 81.13 |

- `100K` быстрее `1K`, потому что при `N < fullScanThreshold = 4000` уходим в перебор.
- `1M` резко медленнее `100K`, потому что множество кандидатов в band buckets становится намного больше, и стоимость уходит в дедупликацию и сортировку.

### 2.2 Потребление памяти

| N | bytes/entry |
|---|------------:|
| 1K | 1 668 |
| 100K | 1 073 |
| 1M | 678 |

С ростом `N` фиксированные расходы на projections, offsets и структуры бакетов амортизируются, поэтому bytes/entry монотонно падает.

### 2.3 Профиль CPU (async-profiler, N=1M)

![RandomProjectionLshIndex.findNear() — flamegraph](docs/img/lsh_find_near_flamegraph.svg)

- Профиль теперь почти целиком JDK/container-bound, а не GC-bound:
- `HashSet.iterator` 32.6%
- `HashMap.putVal` 15.5%
- `HashMap$HashIterator.nextNode` 13.3%
- `HashMap$Node.<init>` 7.2%,
- `TimSort` ~5%.
- Главный вывод по аномалии простой: `1K` медленный из-за full scan, `100K` — самый выгодный режим, `1M` — дорогой из-за large candidate union.

---

## 3. PerfectHashMap

Статическая двухуровневая FKS-таблица: build дорогой, lookup очень быстрый.

### 3.1 Время построения

| N | build time |
|---|-----------:|
| 1K | 98.61 ms |
| 100K | 9.14 s |
| 1M | 90.84 s |

Масштабирование близко к линейному: при росте на порядок build time тоже растёт примерно на порядок.

### 3.2 Lookup

| N | ops/ms | ns/op | p50 (ns) | p90 (ns) | p99 (ns) |
|---|-------:|------:|---------:|---------:|---------:|
| 1K | 27 559.8 | 36.29 | 42 | 83 | 125 |
| 100K | 13 114.6 | 76.25 | 42 | 166 | 250 |
| 1M | 6 512.3 | 153.56 | 208 | 292 | 458 |

- Throughput даёт здесь более честное сравнение, чем `SampleTime`, потому что для `1K` и `100K` значения местами упираются в разрешение таймера.

### 3.3 Память и структура

| N | heap bytes/entry | total slots | slots/entry |
|---|-----------------:|------------:|------------:|
| 1K | 96 | 4 110 | 4.11 |
| 100K | 74 | 409 484 | 4.095 |
| 1M | 74 | 4 040 136 | 4.04 |

Отношение slots/entry стабилизируется около `4N`, что заметно ниже теоретического потолка `6N`.

### 3.4 Профиль CPU (async-profiler, N=1M)

![PerfectHashMap.lookup() — профиль CPU](docs/img/ph_lookup_cpu.png)

![PerfectHashMap.lookup() — flamegraph](docs/img/ph_lookup_flamegraph.svg)

- Профиль compute-bound: `PerfectHashMap.hash()` 64.4%, `String.encodeUTF8` 14.5%, `StringCoding.hasNegatives` 9.1%, `Bucket.lookup` 3.1%.
---

## 4. Сравнение памяти

![Потребление памяти на запись](docs/img/memory_per_entry.png)

| Structure | 1K | 100K | 1M |
|-----------|---:|-----:|---:|
| HashTable (disk) | 32 | 32 | 32 |
| PerfectHash (heap) | 96 | 74 | 74 |
| LSH (heap) | 1 668 | 1 073 | 678 |

---

## 5. Короткие выводы

- Flamegraphs теперь строятся из настоящего профайлера и совпадают с JFR-данными по горячим методам.
- Аномалия `LSH 100K > 1K` объясняется полностью: `1K` всё ещё меряет fallback full scan, а не рабочий LSH-path.
- Главный benchmark-артефакт в `HashTable update` исправлен: после выравнивания длины value update вернулся в single-digit µs и перестал выглядеть как delete.