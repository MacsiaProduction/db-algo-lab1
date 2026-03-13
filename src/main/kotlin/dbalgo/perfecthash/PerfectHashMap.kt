package dbalgo.perfecthash

import org.jetbrains.annotations.TestOnly

class PerfectHashMap<V : Any> private constructor(
    private val m: Int,
    private val seedTop: Long,
    private val buckets: Array<Bucket?>,
    private val _totalSlots: Int
) {
    @Suppress("UNCHECKED_CAST")
    fun lookup(key: String): V? {
        val bucketIndex = hash(key, seedTop, m)
        return buckets[bucketIndex]?.lookup(key) as V?
    }

    val size: Int get() = buckets.sumOf { it?.size ?: 0 }

    companion object {
        private const val TOP_SEED_TRIES = 16_000
        private const val BUCKET_SEED_TRIES = 16_000

        fun <V : Any> build(keys: Array<String>, values: Array<V>): PerfectHashMap<V> {
            require(keys.size == values.size) { "keys и values должны быть одного размера" }
            if (keys.isEmpty()) return PerfectHashMap(1, 0L, arrayOfNulls(1), 1)

            var topSize = keys.size
            while (true) {
                val seedTop = findTopSeed(keys, topSize) ?: run {
                    topSize = topSize * 2 + 1
                    continue
                }

                val groups = Array(topSize) { mutableListOf<Int>() }
                for (i in keys.indices) {
                    groups[hash(keys[i], seedTop, topSize)].add(i)
                }

                val buckets = arrayOfNulls<Bucket>(topSize)
                var allBucketsBuilt = true
                for (idx in 0 until topSize) {
                    val group = groups[idx]
                    if (group.isEmpty()) continue

                    val bucketKeys = Array(group.size) { keys[group[it]] }
                    val bucketVals = Array<Any?>(group.size) { values[group[it]] }
                    val bucket = buildBucket(bucketKeys, bucketVals)
                    if (bucket == null) {
                        allBucketsBuilt = false
                        break
                    }
                    buckets[idx] = bucket
                }

                if (allBucketsBuilt) {
                    val totalSlots = topSize + buckets.sumOf { it?.slotCount ?: 0 }
                    return PerfectHashMap(topSize, seedTop, buckets, totalSlots)
                }

                topSize = topSize * 2 + 1
            }
        }

        private fun findTopSeed(keys: Array<String>, tableSize: Int): Long? {
            val n = keys.size
            val limit = 4L * n
            val counts = IntArray(tableSize)
            var seed = 0L
            repeat(TOP_SEED_TRIES) {
                counts.fill(0)
                for (key in keys) {
                    counts[hash(key, seed, tableSize)]++
                }
                // ∑ count[i]² ≤ 4n
                if (counts.sumOf { it.toLong() * it } <= limit) return seed
                seed++
            }
            return null
        }

        private fun buildBucket(keys: Array<String>, values: Array<Any?>): Bucket? {
            if (keys.isEmpty()) return null
            if (keys.size == 1) {
                val bucketKeys = arrayOfNulls<String>(1)
                val bucketVals = arrayOfNulls<Any>(1)
                bucketKeys[0] = keys[0]
                bucketVals[0] = values[0]
                return Bucket(1, 0L, bucketKeys, bucketVals)
            }

            var tableSize = keys.size * keys.size
            while (true) {
                var seed = 0L
                val used = arrayOfNulls<String>(tableSize)
                repeat(BUCKET_SEED_TRIES) {
                    used.fill(null)
                    var collision = false
                    for (i in keys.indices) {
                        val h = hash(keys[i], seed, tableSize)
                        if (used[h] != null) {
                            collision = true
                            break
                        }
                        used[h] = keys[i]
                    }
                    if (!collision) {
                        val bucketKeys = arrayOfNulls<String>(tableSize)
                        val bucketVals = arrayOfNulls<Any>(tableSize)
                        for (i in keys.indices) {
                            val h = hash(keys[i], seed, tableSize)
                            bucketKeys[h] = keys[i]
                            bucketVals[h] = values[i]
                        }
                        return Bucket(tableSize, seed, bucketKeys, bucketVals)
                    }
                    seed++
                }
                tableSize = tableSize * 2 + 1
            }
        }

        // FNV-1a https://en.wikipedia.org/wiki/Fowler–Noll–Vo_hash_function
        private fun hash(key: String, seed: Long, m: Int): Int {
            var h = seed xor -3750763034362895579L
            for (b in key.toByteArray(Charsets.UTF_8)) {
                h = h xor b.toLong()
                h = h * 1099511628211L
            }
            return ((h and Long.MAX_VALUE) % m).toInt()
        }
    }

    // суммарных слотов, FKS ≤ 6N
    @TestOnly
    fun totalSlots(): Int = _totalSlots

    // размер L1-таблицы
    @TestOnly
    fun topLevelSize(): Int = m

    class Bucket(
        private val m: Int,
        private val seed: Long,
        private val keys: Array<String?>,
        private val values: Array<Any?>
    ) {
        val size: Int get() = keys.count { it != null }
        val slotCount: Int get() = m

        fun lookup(key: String): Any? {
            val h = hash(key, seed, m)
            return if (keys[h] == key) values[h] else null
        }
    }
}
