package dbalgo.hashtable

import org.openjdk.jmh.annotations.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@State(Scope.Thread)
@AuxCounters(AuxCounters.Type.OPERATIONS)
open class DiskUsageCounters {
    @JvmField var diskBytesPerEntry: Long = 0
}

@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput, Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
open class HashTableBenchmark {

    @Param("1000", "100000", "1000000")
    var dataSize: Int = 0

    private lateinit var dir: Path
    private lateinit var ht: ExtendibleHashTable
    private lateinit var keys: Array<String>
    private lateinit var values: Array<ByteArray>
    private lateinit var deleted: BooleanArray
    private var opIdx = 0
    private var diskBytesPerEntry = 0L

    @Setup(Level.Trial)
    fun setup() {
        dir = Files.createTempDirectory("ht_bench")
        ht = ExtendibleHashTable(dir, bucketCapacity = 64, maxOpenChannels = dataSize / 16 + 128)
        keys = Array(dataSize) { "key_${it}_${System.nanoTime()}" }
        values = Array(dataSize) { "value_$it".toByteArray() }
        for (i in 0 until dataSize / 2) ht.insert(keys[i], values[i])
        deleted = BooleanArray(dataSize) { i -> i >= dataSize / 2 }
        val totalDiskBytes = Files.walk(dir).filter(Files::isRegularFile).mapToLong(Files::size).sum()
        diskBytesPerEntry = totalDiskBytes / (dataSize / 2)
    }

    @TearDown(Level.Trial)
    fun tearDown() {
        ht.close()
        dir.toFile().deleteRecursively()
    }

    @Benchmark
    fun benchGet(): ByteArray? {
        val key = keys[opIdx++ % dataSize]
        return ht.get(key)
    }

    @Benchmark
    fun benchInsert() {
        val i = opIdx++
        ht.insert("ins_${i % (dataSize / 2)}", values[i % dataSize])
    }

    @Benchmark
    fun benchUpdate() {
        val key = keys[opIdx++ % dataSize]
        ht.update(key, "updated".toByteArray())
    }

    @Benchmark
    fun benchDelete() {
        val i = opIdx++ % dataSize
        if (deleted[i]) { ht.insert(keys[i], values[i]); deleted[i] = false }
        else            { ht.delete(keys[i]);             deleted[i] = true  }
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    fun benchDiskBytesPerEntry(c: DiskUsageCounters): Long {
        c.diskBytesPerEntry += diskBytesPerEntry
        return diskBytesPerEntry
    }
}
