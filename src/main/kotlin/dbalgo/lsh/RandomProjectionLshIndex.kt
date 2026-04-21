package dbalgo.lsh

import java.util.Random
import kotlin.math.floor
import kotlin.math.sqrt

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

    private var size = 0
    private var ids = arrayOfNulls<String>(16)
    private var xs = DoubleArray(16)
    private var ys = DoubleArray(16)
    private var zs = DoubleArray(16)

    private val projectionX = DoubleArray(numHashes)
    private val projectionY = DoubleArray(numHashes)
    private val projectionZ = DoubleArray(numHashes)
    private val offsets = DoubleArray(numHashes)
    private val bandBuckets = Array(numBands) { HashMap<Long, IntBag>() }
    private val searchScratch = ThreadLocal.withInitial { SearchScratch(numHashes) }

    init {
        require(numHashes % numBands == 0) { "numHashes должен делиться на numBands" }
        require(binWidth > 0) { "binWidth должен быть положительным" }

        val rng = Random(RANDOM_SEED)
        for (i in 0 until numHashes) {
            val vector = randomUnitVector(rng)
            projectionX[i] = vector.x
            projectionY[i] = vector.y
            projectionZ[i] = vector.z
            offsets[i] = rng.nextDouble() * binWidth
        }
    }

    data class Point3D(val x: Double, val y: Double, val z: Double) {
        fun distanceTo(other: Point3D): Double {
            val dx = x - other.x
            val dy = y - other.y
            val dz = z - other.z
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
    }

    fun add(id: String, point: Point3D) {
        ensureCapacity(size + 1)
        ids[size] = id
        xs[size] = point.x
        ys[size] = point.y
        zs[size] = point.z

        val scratch = searchScratch.get()
        scratch.ensureCapacity(size + 1)
        computeHashes(point.x, point.y, point.z, scratch.queryHashes)
        for (band in 0 until numBands) {
            val bandHash = bandHash(scratch.queryHashes, band)
            bandBuckets[band].getOrPut(bandHash, ::IntBag).add(size)
        }
        size++
    }

    /** Ищет точки ближе maxDistance к заданной. */
    fun findNear(query: Point3D, maxDistance: Double): List<Pair<String, Double>> {
        if (size == 0) {
            return emptyList()
        }

        val scratch = searchScratch.get()
        scratch.ensureCapacity(size)
        computeHashes(query.x, query.y, query.z, scratch.queryHashes)

        val candidateCount = if (size <= fullScanThreshold) {
            size
        } else {
            collectCandidates(scratch)
        }
        val resultCount = filterMatches(
            candidateIds = scratch.candidateIds,
            candidateCount = candidateCount,
            fullScan = size <= fullScanThreshold,
            query = query,
            maxDistance = maxDistance,
            scratch = scratch,
        )
        if (resultCount == 0) {
            return emptyList()
        }

        sortResultsByDistance(scratch.resultIds, scratch.resultDistances, 0, resultCount - 1)
        val result = ArrayList<Pair<String, Double>>(resultCount)
        for (i in 0 until resultCount) {
            result += ids[scratch.resultIds[i]]!! to scratch.resultDistances[i]
        }
        return result
    }

    /** Все пары точек с расстоянием <= maxDistance. */
    fun findAllNearPairs(maxDistance: Double): List<Triple<String, String, Double>> {
        val result = mutableListOf<Triple<String, String, Double>>()
        val candidatePairs = HashSet<Long>()

        for (band in bandBuckets) {
            for (bucket in band.values) {
                if (bucket.size < 2) continue
                for (i in 0 until bucket.size) {
                    for (j in i + 1 until bucket.size) {
                        val a = bucket[i]
                        val b = bucket[j]
                        candidatePairs.add((minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong())
                    }
                }
            }
        }
        if (size <= fullScanThreshold) {
            for (i in 0 until size) {
                for (j in i + 1 until size) {
                    candidatePairs.add((i.toLong() shl 32) or j.toLong())
                }
            }
        }
        for (pair in candidatePairs) {
            val a = (pair ushr 32).toInt()
            val b = (pair and 0xFFFF_FFFFL).toInt()
            val dist = distance(queryX = xs[a], queryY = ys[a], queryZ = zs[a], pointIndex = b)
            if (dist <= maxDistance) {
                result += Triple(ids[a]!!, ids[b]!!, dist)
            }
        }
        return result
    }

    fun size() = size

    private fun collectCandidates(scratch: SearchScratch): Int {
        var epoch = scratch.epoch + 1
        if (epoch == Int.MAX_VALUE) {
            scratch.seenEpoch.fill(0)
            epoch = 1
        }
        scratch.epoch = epoch

        var candidateCount = 0
        for (band in 0 until numBands) {
            val bandHash = bandHash(scratch.queryHashes, band)
            val bucket = bandBuckets[band][bandHash] ?: continue
            for (i in 0 until bucket.size) {
                val candidateId = bucket[i]
                if (scratch.seenEpoch[candidateId] == epoch) {
                    continue
                }
                scratch.seenEpoch[candidateId] = epoch
                scratch.candidateIds[candidateCount++] = candidateId
            }
        }
        return candidateCount
    }

    private fun filterMatches(
        candidateIds: IntArray,
        candidateCount: Int,
        fullScan: Boolean,
        query: Point3D,
        maxDistance: Double,
        scratch: SearchScratch,
    ): Int {
        var resultCount = 0
        if (fullScan) {
            for (index in 0 until size) {
                val distance = distance(query.x, query.y, query.z, index)
                if (distance <= maxDistance) {
                    scratch.resultIds[resultCount] = index
                    scratch.resultDistances[resultCount] = distance
                    resultCount++
                }
            }
            return resultCount
        }

        for (i in 0 until candidateCount) {
            val candidateId = candidateIds[i]
            val distance = distance(query.x, query.y, query.z, candidateId)
            if (distance <= maxDistance) {
                scratch.resultIds[resultCount] = candidateId
                scratch.resultDistances[resultCount] = distance
                resultCount++
            }
        }
        return resultCount
    }

    private fun computeHashes(x: Double, y: Double, z: Double, out: IntArray) {
        for (i in 0 until numHashes) {
            val projection = x * projectionX[i] + y * projectionY[i] + z * projectionZ[i]
            out[i] = floor((projection + offsets[i]) / binWidth).toInt()
        }
    }

    /** Хеш полосы — комбинирует hashesPerBand целых значений в один Long. */
    private fun bandHash(hashes: IntArray, band: Int): Long {
        var hash = 1_000_003L
        val start = band * hashesPerBand
        for (i in start until start + hashesPerBand) {
            hash = hash * 1_000_003L xor hashes[i].toLong()
        }
        return hash
    }

    private fun distance(queryX: Double, queryY: Double, queryZ: Double, pointIndex: Int): Double {
        val dx = queryX - xs[pointIndex]
        val dy = queryY - ys[pointIndex]
        val dz = queryZ - zs[pointIndex]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun ensureCapacity(required: Int) {
        if (required <= ids.size) {
            return
        }
        var next = ids.size
        while (next < required) {
            next *= 2
        }
        ids = ids.copyOf(next)
        xs = xs.copyOf(next)
        ys = ys.copyOf(next)
        zs = zs.copyOf(next)
    }

    private fun sortResultsByDistance(ids: IntArray, distances: DoubleArray, left: Int, right: Int) {
        if (left >= right) {
            return
        }
        var i = left
        var j = right
        val pivot = distances[(left + right) ushr 1]
        while (i <= j) {
            while (distances[i] < pivot) {
                i++
            }
            while (distances[j] > pivot) {
                j--
            }
            if (i <= j) {
                swap(ids, i, j)
                swap(distances, i, j)
                i++
                j--
            }
        }
        if (left < j) {
            sortResultsByDistance(ids, distances, left, j)
        }
        if (i < right) {
            sortResultsByDistance(ids, distances, i, right)
        }
    }

    private fun swap(values: IntArray, i: Int, j: Int) {
        if (i == j) return
        val tmp = values[i]
        values[i] = values[j]
        values[j] = tmp
    }

    private fun swap(values: DoubleArray, i: Int, j: Int) {
        if (i == j) return
        val tmp = values[i]
        values[i] = values[j]
        values[j] = tmp
    }

    private data class UnitVector(val x: Double, val y: Double, val z: Double)

    private class IntBag {
        private var values = IntArray(8)
        var size: Int = 0
            private set

        fun add(value: Int) {
            if (size == values.size) {
                values = values.copyOf(values.size * 2)
            }
            values[size++] = value
        }

        operator fun get(index: Int): Int = values[index]
    }

    private class SearchScratch(hashCount: Int) {
        val queryHashes = IntArray(hashCount)
        var seenEpoch = IntArray(16)
        var candidateIds = IntArray(16)
        var resultIds = IntArray(16)
        var resultDistances = DoubleArray(16)
        var epoch: Int = 0

        fun ensureCapacity(entryCount: Int) {
            if (seenEpoch.size < entryCount) {
                seenEpoch = seenEpoch.copyOf(growTo(entryCount, seenEpoch.size))
            }
            if (candidateIds.size < entryCount) {
                candidateIds = candidateIds.copyOf(growTo(entryCount, candidateIds.size))
            }
            if (resultIds.size < entryCount) {
                val next = growTo(entryCount, resultIds.size)
                resultIds = resultIds.copyOf(next)
                resultDistances = resultDistances.copyOf(next)
            }
        }

        private fun growTo(required: Int, current: Int): Int {
            var next = maxOf(16, current)
            while (next < required) {
                next *= 2
            }
            return next
        }
    }

    companion object {
        private const val RANDOM_SEED = 0xA15C0DEL

        private fun randomUnitVector(rng: Random): UnitVector {
            val x = rng.nextGaussian()
            val y = rng.nextGaussian()
            val z = rng.nextGaussian()
            val len = sqrt(x * x + y * y + z * z)
            return UnitVector(x / len, y / len, z / len)
        }
    }
}
