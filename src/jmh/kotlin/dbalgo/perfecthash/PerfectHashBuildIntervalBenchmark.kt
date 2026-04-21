package dbalgo.perfecthash

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 1)
@Measurement(iterations = 3)
@Fork(1)
open class PerfectHashBuildIntervalBenchmark {

    @Param("1000", "100000", "1000000")
    var dataSize: Int = 0

    private lateinit var encodedKeys: Array<PerfectHashMap.EncodedKey>
    private lateinit var values: Array<Int>

    @Setup
    fun setup() {
        val keys = Array(dataSize) { i -> buildKey(i) }
        encodedKeys = PerfectHashMap.encodeKeys(keys)
        values = Array(dataSize) { i -> i }
    }

    @Benchmark
    fun benchBuild(): PerfectHashMap<Int> = PerfectHashMap.buildFromEncoded(encodedKeys, values)

    private fun buildKey(index: Int): String = buildString(12) {
        append("src_")
        append(index.toString().padStart(8, '0'))
    }
}
