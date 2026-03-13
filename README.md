# Database Algorithms Lab 1

Реализация и исследование трёх алгоритмов работы с данными на Kotlin/JVM.

## Алгоритмы

1. **File-based Hash Table** — персистентная хеш-таблица с bucket-файлами, append-only записями и auto-compaction
2. **Perfect Hash (FKS)** — двухуровневое идеальное хеширование, O(1) worst-case lookup без коллизий
3. **LSH** — RandomProjection/SimHash для 3D-точек

## Быстрый старт

```bash
# Тесты
./gradlew test

# JMH бенчмарки
./gradlew jmh
```