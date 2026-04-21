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
        val bucketIndex = hashUtf8(key, seedTop, m)
        return buckets[bucketIndex]?.lookup(key) as V?
    }

    val size: Int get() = buckets.sumOf { it?.size ?: 0 }

    companion object {
        private const val TOP_SEED_TRIES = 16_000
        private const val BUCKET_SEED_TRIES = 16_000
        private const val FNV_OFFSET_BASIS = -3750763034362895579L
        private const val FNV_PRIME = 1099511628211L
        private const val REPLACEMENT_BYTE = 63

        fun <V : Any> build(keys: Array<String>, values: Array<V>): PerfectHashMap<V> {
            require(keys.size == values.size) { "keys и values должны быть одного размера" }
            return buildFromEncoded(encodeKeys(keys), values)
        }

        internal fun encodeKeys(keys: Array<String>): Array<EncodedKey> =
            Array(keys.size) { index ->
                val text = keys[index]
                EncodedKey(text, text.toByteArray(Charsets.UTF_8))
            }

        internal fun <V : Any> buildFromEncoded(encodedKeys: Array<EncodedKey>, values: Array<V>): PerfectHashMap<V> {
            require(encodedKeys.size == values.size) { "keys и values должны быть одного размера" }
            if (encodedKeys.isEmpty()) return PerfectHashMap(1, 0L, arrayOfNulls(1), 1)

            var topSize = encodedKeys.size
            val valuesAny = Array<Any?>(values.size) { values[it] }
            while (true) {
                val seedTop = findTopSeed(encodedKeys, topSize) ?: run {
                    topSize = topSize * 2 + 1
                    continue
                }

                val counts = IntArray(topSize)
                for (key in encodedKeys) {
                    counts[hashUtf8(key.utf8, seedTop, topSize)]++
                }

                val offsets = IntArray(topSize + 1)
                for (i in 0 until topSize) {
                    offsets[i + 1] = offsets[i] + counts[i]
                }
                val positions = offsets.copyOf()
                val groupedIndices = IntArray(encodedKeys.size)
                for (index in encodedKeys.indices) {
                    val bucketIndex = hashUtf8(encodedKeys[index].utf8, seedTop, topSize)
                    groupedIndices[positions[bucketIndex]++] = index
                }

                val buckets = arrayOfNulls<Bucket>(topSize)
                var allBucketsBuilt = true
                for (idx in 0 until topSize) {
                    val start = offsets[idx]
                    val end = offsets[idx + 1]
                    if (start == end) continue
                    val bucket = buildBucket(encodedKeys, valuesAny, groupedIndices, start, end)
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

        private fun findTopSeed(keys: Array<EncodedKey>, tableSize: Int): Long? {
            val limit = 4L * keys.size
            val counts = IntArray(tableSize)
            var seed = 0L
            repeat(TOP_SEED_TRIES) {
                counts.fill(0)
                for (key in keys) {
                    counts[hashUtf8(key.utf8, seed, tableSize)]++
                }
                if (counts.sumOf { count -> count.toLong() * count } <= limit) return seed
                seed++
            }
            return null
        }

        private fun buildBucket(
            keys: Array<EncodedKey>,
            values: Array<Any?>,
            groupedIndices: IntArray,
            start: Int,
            end: Int,
        ): Bucket? {
            val size = end - start
            if (size <= 0) return null
            if (size == 1) {
                val keyIndex = groupedIndices[start]
                val bucketKeys = arrayOfNulls<String>(1)
                val bucketVals = arrayOfNulls<Any>(1)
                bucketKeys[0] = keys[keyIndex].text
                bucketVals[0] = values[keyIndex]
                return Bucket(1, 0L, bucketKeys, bucketVals, 1)
            }

            var tableSize = size * size
            while (true) {
                var seed = 0L
                val usedStamp = IntArray(tableSize)
                val usedIndex = IntArray(tableSize)
                var generation = 1
                repeat(BUCKET_SEED_TRIES) {
                    if (generation == Int.MAX_VALUE) {
                        usedStamp.fill(0)
                        generation = 1
                    }

                    var collision = false
                    for (position in start until end) {
                        val keyIndex = groupedIndices[position]
                        val slot = hashUtf8(keys[keyIndex].utf8, seed, tableSize)
                        if (usedStamp[slot] == generation) {
                            collision = true
                            break
                        }
                        usedStamp[slot] = generation
                        usedIndex[slot] = keyIndex
                    }
                    if (!collision) {
                        val bucketKeys = arrayOfNulls<String>(tableSize)
                        val bucketVals = arrayOfNulls<Any>(tableSize)
                        for (slot in 0 until tableSize) {
                            if (usedStamp[slot] != generation) continue
                            val keyIndex = usedIndex[slot]
                            bucketKeys[slot] = keys[keyIndex].text
                            bucketVals[slot] = values[keyIndex]
                        }
                        return Bucket(tableSize, seed, bucketKeys, bucketVals, size)
                    }
                    seed++
                    generation++
                }
                tableSize = tableSize * 2 + 1
            }
        }

        private fun hashUtf8(key: EncodedKey, seed: Long, m: Int): Int =
            hashUtf8(key.utf8, seed, m)

        private fun hashUtf8(bytes: ByteArray, seed: Long, m: Int): Int {
            var hash = seed xor FNV_OFFSET_BASIS
            for (byte in bytes) {
                hash = hash xor byte.toLong()
                hash *= FNV_PRIME
            }
            return ((hash and Long.MAX_VALUE) % m).toInt()
        }

        private fun hashUtf8(text: String, seed: Long, m: Int): Int {
            var hash = seed xor FNV_OFFSET_BASIS
            var index = 0
            while (index < text.length) {
                val ch = text[index]
                when {
                    ch.code < 0x80 -> {
                        hash = updateHash(hash, ch.code)
                    }
                    ch.code < 0x800 -> {
                        hash = updateHash(hash, 0xC0 or (ch.code ushr 6))
                        hash = updateHash(hash, 0x80 or (ch.code and 0x3F))
                    }
                    ch.isHighSurrogate() -> {
                        if (index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                            val codePoint = Character.toCodePoint(ch, text[index + 1])
                            hash = updateHash(hash, 0xF0 or (codePoint ushr 18))
                            hash = updateHash(hash, 0x80 or ((codePoint ushr 12) and 0x3F))
                            hash = updateHash(hash, 0x80 or ((codePoint ushr 6) and 0x3F))
                            hash = updateHash(hash, 0x80 or (codePoint and 0x3F))
                            index++
                        } else {
                            hash = updateHash(hash, REPLACEMENT_BYTE)
                        }
                    }
                    ch.isLowSurrogate() -> {
                        hash = updateHash(hash, REPLACEMENT_BYTE)
                    }
                    else -> {
                        hash = updateHash(hash, 0xE0 or (ch.code ushr 12))
                        hash = updateHash(hash, 0x80 or ((ch.code ushr 6) and 0x3F))
                        hash = updateHash(hash, 0x80 or (ch.code and 0x3F))
                    }
                }
                index++
            }
            return ((hash and Long.MAX_VALUE) % m).toInt()
        }

        private fun updateHash(hash: Long, byteValue: Int): Long {
            var next = hash xor byteValue.toByte().toLong()
            next *= FNV_PRIME
            return next
        }
    }

    internal data class EncodedKey(val text: String, val utf8: ByteArray)

    @TestOnly
    fun totalSlots(): Int = _totalSlots

    @TestOnly
    fun topLevelSize(): Int = m

    class Bucket(
        private val m: Int,
        private val seed: Long,
        private val keys: Array<String?>,
        private val values: Array<Any?>,
        val size: Int,
    ) {
        val slotCount: Int get() = m

        fun lookup(key: String): Any? {
            val hash = hashUtf8(key, seed, m)
            return if (keys[hash] == key) values[hash] else null
        }
    }
}
