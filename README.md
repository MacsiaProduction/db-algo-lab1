# Database Algorithms Lab 1

Реализация и исследование трёх алгоритмов работы с данными на Kotlin/JVM.

## Алгоритмы

1. **File-based Hash Table** — персистентная хеш-таблица с bucket-файлами, append-only записями и auto-compaction
2. **Perfect Hash (FKS)** — двухуровневое идеальное хеширование, O(1) worst-case lookup без коллизий
3. **LSH** — RandomProjection LSH для 3D-точек

## Быстрый старт

```bash
# Тесты
./gradlew test

# Полные JMH-результаты (build/results/jmh/results.json)
./gradlew jmh

# Профили async-profiler для representative N=1M бенчмарков
./gradlew jmh -Pprofile -PjmhInclude='dbalgo.hashtable.HashTableBenchmark.bench(Get|Insert|Update|Delete)$' -PjmhDataSize=1000000
./gradlew jmh -Pprofile -PjmhInclude='dbalgo.lsh.LshBenchmark.benchRpFindNear$' -PjmhDataSize=1000000
./gradlew jmh -Pprofile -PjmhInclude='dbalgo.perfecthash.PerfectHashBenchmark.benchLookup$' -PjmhDataSize=1000000

# Графики и SVG flamegraphs в docs/img
python3 docs/gen_charts.py
```

Сводный отчёт по результатам находится в `BENCHMARK_REPORT.md`.