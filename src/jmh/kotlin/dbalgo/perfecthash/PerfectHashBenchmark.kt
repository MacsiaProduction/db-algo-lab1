package dbalgo.perfecthash

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

/** Reports struct size directly in JMH results via OPERATIONS-mode aux counters. */
@State(Scope.Thread)
@AuxCounters(AuxCounters.Type.OPERATIONS)
open class StructSizeCounters {
    @JvmField var totalSlots: Long = 0
    @JvmField var topLevelSize: Long = 0
    @JvmField var secondarySlots: Long = 0
    @JvmField var heapBytesPerEntry: Long = 0
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput, Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
open class PerfectHashBenchmark {

    @Param("1000", "100000", "1000000")
    var dataSize: Int = 0

    private lateinit var ph: PerfectHashMap<Int>
    private lateinit var keys: Array<String>
    private var opIdx = 0

    private var heapBytesPerEntry = 0L

    @Setup(Level.Trial)
    fun setup() {
        keys = Array(dataSize) { "key_$it" }
        val values = Array(dataSize) { it }
        val rt = Runtime.getRuntime()
        System.gc(); System.gc()
        val heapBefore = rt.totalMemory() - rt.freeMemory()
        ph = PerfectHashMap.build(keys, values)
        System.gc(); System.gc()
        val heapAfter = rt.totalMemory() - rt.freeMemory()
        heapBytesPerEntry = maxOf(0L, heapAfter - heapBefore) / dataSize
    }

    @Benchmark
    fun benchLookup(): Int? {
        return ph.lookup(keys[opIdx++ % dataSize])
    }

    @Benchmark
    @Warmup(iterations = 1, time = 1)
    @Measurement(iterations = 2, time = 1)
    fun benchBuild(): PerfectHashMap<Int> {
        return PerfectHashMap.build(
            Array(dataSize) { "k$it" },
            Array(dataSize) { it }
        )
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    fun benchStructSize(c: StructSizeCounters): Int {
        val total = ph.totalSlots()
        c.totalSlots += total.toLong()
        c.topLevelSize += ph.topLevelSize().toLong()
        c.secondarySlots += (total - ph.topLevelSize()).toLong()
        return total
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    fun benchHeapBytesPerEntry(c: StructSizeCounters): Long {
        c.heapBytesPerEntry += heapBytesPerEntry
        return heapBytesPerEntry
    }
}
