package dbalgo.lsh

import org.openjdk.jmh.annotations.*
import java.util.concurrent.TimeUnit

@State(Scope.Thread)
@AuxCounters(AuxCounters.Type.OPERATIONS)
open class LshHeapCounters {
    @JvmField var heapBytesPerEntry: Long = 0
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput, Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
open class LshBenchmark {

    @Param("1000", "100000", "1000000")
    var dataSize: Int = 0

    private lateinit var rpIndex: RandomProjectionLshIndex
    private val rng = java.util.Random(123)
    private var heapBytesPerEntry = 0L

    @Setup(Level.Trial)
    fun setup() {
        val rt = Runtime.getRuntime()
        System.gc(); System.gc()
        val heapBefore = rt.totalMemory() - rt.freeMemory()
        rpIndex = RandomProjectionLshIndex(numHashes = 64, numBands = 8)
        for (i in 0 until dataSize) {
            rpIndex.add("pt$i", RandomProjectionLshIndex.Point3D(
                rng.nextDouble() * 100, rng.nextDouble() * 100, rng.nextDouble() * 100
            ))
        }
        System.gc(); System.gc()
        val heapAfter = rt.totalMemory() - rt.freeMemory()
        heapBytesPerEntry = maxOf(0L, heapAfter - heapBefore) / dataSize
    }

    @Benchmark
    fun benchRpFindNear(): List<Pair<String, Double>> {
        return rpIndex.findNear(
            RandomProjectionLshIndex.Point3D(50.0, 50.0, 50.0), 5.0
        )
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    fun benchHeapBytesPerEntry(c: LshHeapCounters): Long {
        c.heapBytesPerEntry += heapBytesPerEntry
        return heapBytesPerEntry
    }
}
