package dbalgo.lsh

import kotlin.math.floor
import kotlin.math.sqrt
import java.util.Random

/**
 * LSH на основе p-stable L2 хеширования для поиска близких 3D-точек.
 *
 * Каждая хеш-функция: h_i(p) = floor((dot(p, r_i) + b_i) / w)
 * где r_i — случайный гауссов вектор, b_i ~ Uniform[0, w], w = binWidth.
 *
 * При d << w точки почти гарантированно попадают в один бакет.
 * При d >> w вероятность коллизии ≈ 0.
 *
 * @param numHashes число хеш-функций (делится на число полос)
 * @param numBands  число полос
 * @param binWidth  ширина интервала квантования (≈ ожидаемый maxDistance)
 */
class RandomProjectionLshIndex(
    private val numHashes: Int = 64,
    private val numBands: Int = 8,
    private val binWidth: Double = 5.0
) {
    private val hashesPerBand = numHashes / numBands
    private val fullScanThreshold = 4_000

    init {
        require(numHashes % numBands == 0) { "numHashes должен делиться на numBands" }
        require(binWidth > 0) { "binWidth должен быть положительным" }
    }

    data class Point3D(val x: Double, val y: Double, val z: Double) {
        fun distanceTo(other: Point3D): Double {
            val dx = x - other.x; val dy = y - other.y; val dz = z - other.z
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
    }

    private data class Entry(val id: String, val point: Point3D, val hashes: IntArray)

    private val entries = mutableListOf<Entry>()
    // Случайные единичные векторы для проекций
    private val projections = Array(numHashes) { randomUnitVector() }
    // Случайные смещения b_i ~ Uniform[0, binWidth]
    private val offsets = DoubleArray(numHashes) { rng.nextDouble() * binWidth }
    // Полосы -> бакет -> список индексов
    private val bandBuckets = Array(numBands) { HashMap<Long, MutableList<Int>>() }

    fun add(id: String, point: Point3D) {
        val hashes = computeHash(point)
        val idx = entries.size
        entries.add(Entry(id, point, hashes))
        for (band in 0 until numBands) {
            val bh = bandHash(hashes, band)
            bandBuckets[band].getOrPut(bh) { mutableListOf() }.add(idx)
        }
    }

    /** Ищет точки ближе maxDistance к заданной. */
    fun findNear(query: Point3D, maxDistance: Double): List<Pair<String, Double>> {
        val hashes = computeHash(query)
        val candidates = HashSet<Int>()
        for (band in 0 until numBands) {
            val bh = bandHash(hashes, band)
            bandBuckets[band][bh]?.let { candidates.addAll(it) }
        }
        val pool = if (entries.size <= fullScanThreshold) entries.indices.toHashSet() else candidates
        return pool.mapNotNull { idx ->
            val dist = query.distanceTo(entries[idx].point)
            if (dist <= maxDistance) entries[idx].id to dist else null
        }.sortedBy { it.second }
    }

    /** Все пары точек с расстоянием <= maxDistance. */
    fun findAllNearPairs(maxDistance: Double): List<Triple<String, String, Double>> {
        val result = mutableListOf<Triple<String, String, Double>>()
        val candidatePairs = HashSet<Long>()

        for (band in bandBuckets) {
            for (bucket in band.values) {
                if (bucket.size < 2) continue
                for (i in bucket.indices) for (j in i + 1 until bucket.size) {
                    val a = bucket[i]; val b = bucket[j]
                    candidatePairs.add((minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong())
                }
            }
        }
        if (entries.size <= fullScanThreshold) {
            for (i in 0 until entries.size) for (j in i + 1 until entries.size) {
                candidatePairs.add((i.toLong() shl 32) or j.toLong())
            }
        }
        for (pair in candidatePairs) {
            val a = (pair ushr 32).toInt()
            val b = (pair and 0xFFFF_FFFFL).toInt()
            val dist = entries[a].point.distanceTo(entries[b].point)
            if (dist <= maxDistance) result.add(Triple(entries[a].id, entries[b].id, dist))
        }
        return result
    }

    fun size() = entries.size

    /**
     * p-stable L2: hash_i(p) = floor((dot(p, r_i) + b_i) / w).
     * Точки с малым расстоянием имеют близкие проекции → часто одинаковый бин.
     * В отличие от SimHash нет ограничения в 2^bitsPerBand уникальных значений.
     */
    private fun computeHash(p: Point3D): IntArray {
        return IntArray(numHashes) { i ->
            val (rx, ry, rz) = projections[i]
            val proj = p.x * rx + p.y * ry + p.z * rz
            floor((proj + offsets[i]) / binWidth).toInt()
        }
    }

    /** Хеш полосы — комбинирует hashesPerBand целых значений в один Long. */
    private fun bandHash(hashes: IntArray, band: Int): Long {
        var h = 1000003L
        val start = band * hashesPerBand
        for (i in start until start + hashesPerBand) {
            h = h * 1000003L xor hashes[i].toLong()
        }
        return h
    }

    companion object {
        private val rng = Random(0xA15C0DE)

        private fun randomUnitVector(): Triple<Double, Double, Double> {
            val x = rng.nextGaussian()
            val y = rng.nextGaussian()
            val z = rng.nextGaussian()
            val len = sqrt(x * x + y * y + z * z)
            return Triple(x / len, y / len, z / len)
        }
    }
}
