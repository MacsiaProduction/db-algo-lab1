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
    private lateinit var queryPoints: Array<RandomProjectionLshIndex.Point3D>
    private var heapBytesPerEntry = 0L
    private var queryIdx = 0

    @Setup(Level.Trial)
    fun setup() {
        val rng = java.util.Random(123)
        val rt = Runtime.getRuntime()
        System.gc(); System.gc()
        val heapBefore = rt.totalMemory() - rt.freeMemory()
        rpIndex = RandomProjectionLshIndex(numHashes = 64, numBands = 8)
        val points = Array(dataSize) {
            RandomProjectionLshIndex.Point3D(
                rng.nextDouble() * 100,
                rng.nextDouble() * 100,
                rng.nextDouble() * 100
            )
        }
        points.forEachIndexed { i, point ->
            rpIndex.add("pt_${i.toString().padStart(8, '0')}", point)
        }
        val queryCount = minOf(points.size, 1024)
        queryPoints = Array(queryCount) { i ->
            points[i * points.size / queryCount]
        }
        System.gc(); System.gc()
        val heapAfter = rt.totalMemory() - rt.freeMemory()
        heapBytesPerEntry = maxOf(0L, heapAfter - heapBefore) / dataSize
    }

    @Benchmark
    fun benchRpFindNear(): List<Pair<String, Double>> {
        return rpIndex.findNear(queryPoints[queryIdx++ % queryPoints.size], 5.0)
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    fun benchHeapBytesPerEntry(c: LshHeapCounters): Long {
        c.heapBytesPerEntry += heapBytesPerEntry
        return heapBytesPerEntry
    }
}
