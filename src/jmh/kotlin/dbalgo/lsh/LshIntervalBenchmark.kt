package dbalgo.lsh

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.Blackhole
import org.openjdk.jmh.infra.IterationParams
import org.openjdk.jmh.runner.IterationType
import java.util.Random
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
open class LshIntervalBenchmark {

    @Param("1000", "100000", "1000000")
    var dataSize: Int = 0

    private lateinit var index: RandomProjectionLshIndex
    private lateinit var queryPool: Array<RandomProjectionLshIndex.Point3D>
    private var cursor = 0

    @Setup(Level.Trial)
    fun setupTrial() {
        val rng = Random(123L)
        index = RandomProjectionLshIndex(numHashes = 64, numBands = 8)
        repeat(dataSize) { indexValue ->
            index.add(
                "pt_${indexValue.toString().padStart(8, '0')}",
                RandomProjectionLshIndex.Point3D(
                    rng.nextDouble() * 100.0,
                    rng.nextDouble() * 100.0,
                    rng.nextDouble() * 100.0,
                ),
            )
        }

        val corpus = Array(QUERY_CORPUS_SIZE) {
            RandomProjectionLshIndex.Point3D(
                rng.nextDouble() * 100.0,
                rng.nextDouble() * 100.0,
                rng.nextDouble() * 100.0,
            )
        }
        queryPool = selectCanonicalQueries(corpus)
    }

    @Setup(Level.Iteration)
    fun setupIteration(iterationParams: IterationParams) {
        cursor = 0
        if (iterationParams.type == IterationType.MEASUREMENT) {
            repeat(batchOpsFor(dataSize)) {
                index.findNear(queryPool[it % queryPool.size], MAX_DISTANCE)
            }
            cursor = 0
        }
    }

    @Benchmark
    fun benchFindNear(blackhole: Blackhole) {
        repeat(batchOpsFor(dataSize)) {
            val query = queryPool[cursor]
            cursor++
            if (cursor == queryPool.size) {
                cursor = 0
            }
            blackhole.consume(index.findNear(query, MAX_DISTANCE))
        }
    }

    private fun selectCanonicalQueries(
        corpus: Array<RandomProjectionLshIndex.Point3D>,
    ): Array<RandomProjectionLshIndex.Point3D> {
        val selected = shuffledCopy(corpus.asList(), QUERY_SELECTION_SEED).take(CANONICAL_QUERY_COUNT)
        require(selected.size == CANONICAL_QUERY_COUNT) {
            "Could not select $CANONICAL_QUERY_COUNT canonical LSH queries for dataSize=$dataSize"
        }
        return selected.toTypedArray()
    }

    private fun <T> shuffledCopy(values: List<T>, seed: Long): List<T> {
        val copy = values.toMutableList()
        val random = Random(seed)
        for (i in copy.lastIndex downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = copy[i]
            copy[i] = copy[j]
            copy[j] = tmp
        }
        return copy
    }

    companion object {
        private const val MAX_DISTANCE = 5.0
        private const val QUERY_CORPUS_SIZE = 4_096
        private const val CANONICAL_QUERY_COUNT = 1_024
        private const val QUERY_SELECTION_SEED = 12_345L

        internal fun batchOpsFor(dataSize: Int): Int = when (dataSize) {
            1_000 -> 4_096
            100_000 -> 16_384
            1_000_000 -> 2_048
            else -> 2_048
        }
    }
}
