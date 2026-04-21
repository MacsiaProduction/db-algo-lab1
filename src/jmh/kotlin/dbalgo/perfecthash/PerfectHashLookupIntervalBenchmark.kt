package dbalgo.perfecthash

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
open class PerfectHashLookupIntervalBenchmark {

    @Param("1000", "100000", "1000000")
    var dataSize: Int = 0

    private lateinit var map: PerfectHashMap<Int>
    private lateinit var queryPool: Array<String>
    private var cursor = 0

    @Setup(Level.Trial)
    fun setupTrial() {
        val keys = Array(dataSize, ::fixedKey)
        val values = Array(dataSize) { it }
        map = PerfectHashMap.build(keys, values)
        queryPool = shuffledCopy(keys.asList(), 12_345L).take(minOf(CANONICAL_QUERY_COUNT, keys.size)).toTypedArray()
    }

    @Setup(Level.Iteration)
    fun setupIteration(iterationParams: IterationParams) {
        cursor = 0
        if (iterationParams.type == IterationType.MEASUREMENT) {
            repeat(batchOpsFor(dataSize)) {
                map.lookup(queryPool[it % queryPool.size])
            }
            cursor = 0
        }
    }

    @Benchmark
    fun benchLookup(blackhole: Blackhole) {
        repeat(batchOpsFor(dataSize)) {
            val key = queryPool[cursor]
            cursor++
            if (cursor == queryPool.size) {
                cursor = 0
            }
            blackhole.consume(map.lookup(key))
        }
    }

    private fun fixedKey(index: Int): String = buildString(12) {
        append("key_")
        append(index.toString().padStart(8, '0'))
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
        private const val CANONICAL_QUERY_COUNT = 16_384

        internal fun batchOpsFor(dataSize: Int): Int = when (dataSize) {
            1_000 -> 1_048_576
            100_000 -> 262_144
            1_000_000 -> 131_072
            else -> 131_072
        }
    }
}
