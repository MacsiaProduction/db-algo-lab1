package dbalgo.report

import dbalgo.lsh.RandomProjectionLshIndex
import dbalgo.perfecthash.PerfectHashMap
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import java.util.Random

private const val PERFECT_HASH_QUERY_COUNT = 16_384
private const val LSH_QUERY_COUNT = 1_024
private const val LSH_MAX_DISTANCE = 5.0
private const val WARMUP_REPETITIONS = 2
private const val MEASUREMENT_REPETITIONS = 10
private const val POINT_REPETITIONS = 1
private const val BYTES_PER_GIB = 1_073_741_824L

private val DEFAULT_LSH_SIZES = listOf(1_000) + (1..100).map { it * 10_000 }
private val DEFAULT_PERFECT_HASH_SIZES = listOf(
    1_000,
    2_000,
    5_000,
    10_000,
    20_000,
    50_000,
    100_000,
    200_000,
    500_000,
    1_000_000,
    2_000_000,
    5_000_000,
    10_000_000,
    20_000_000,
    50_000_000,
)

private data class CliConfig(
    val output: Path,
    val dataSizes: List<Int>?,
    val ramBudgetBytes: Long,
    val series: SeriesSelection,
    val warmupRepetitions: Int,
    val measurementRepetitions: Int,
    val pointRepetitions: Int,
)

private enum class SeriesSelection {
    ALL,
    PERFECT_HASH,
    LSH,
}

private data class ScalingPoint(
    val dataSize: Int,
    val latencyNsPerOp: Double,
    val totalHeapBytes: Long,
    val samplesNsPerOp: List<Double>,
    val avgCandidateCount: Double? = null,
    val avgMatchCount: Double? = null,
    val candidateSamples: List<Double> = emptyList(),
    val matchSamples: List<Double> = emptyList(),
    val source: String? = null,
    val runLatenciesNsPerOp: List<Double> = emptyList(),
    val runHeapBytes: List<Long> = emptyList(),
)

private data class LshBatchStats(
    val sink: Long,
    val avgCandidateCount: Double,
    val avgMatchCount: Double,
)

@Volatile
private var sinkLong = 0L

fun main(args: Array<String>) {
    val config = parseArgs(args)
    println("RAM budget: ${formatDouble(config.ramBudgetBytes.toDouble() / BYTES_PER_GIB, 2)} GiB")
    println("Series selection: ${config.series}")
    println("Warmup repetitions: ${config.warmupRepetitions}")
    println("Measurement repetitions: ${config.measurementRepetitions}")
    println("Point repetitions: ${config.pointRepetitions}")

    val perfectHashSizes = config.dataSizes ?: DEFAULT_PERFECT_HASH_SIZES
    val lshSizes = config.dataSizes ?: DEFAULT_LSH_SIZES

    val perfectHash = if (config.series == SeriesSelection.ALL || config.series == SeriesSelection.PERFECT_HASH) {
        println("PerfectHash lookup scaling data sizes: ${perfectHashSizes.joinToString()}")
        measurePerfectHashSeries(
            dataSizes = perfectHashSizes,
            warmupRepetitions = config.warmupRepetitions,
            measurementRepetitions = config.measurementRepetitions,
            pointRepetitions = config.pointRepetitions,
        )
    } else {
        emptyList()
    }
    forceGc()
    val lsh = if (config.series == SeriesSelection.ALL || config.series == SeriesSelection.LSH) {
        println("LSH lookup scaling data sizes: ${lshSizes.first()}..${lshSizes.last()} (${lshSizes.size} points)")
        measureLshSeries(
            dataSizes = lshSizes,
            ramBudgetBytes = config.ramBudgetBytes,
            warmupRepetitions = config.warmupRepetitions,
            measurementRepetitions = config.measurementRepetitions,
        )
    } else {
        emptyList()
    }

    writeReport(
        output = config.output,
        ramBudgetBytes = config.ramBudgetBytes,
        perfectHash = perfectHash,
        lsh = lsh,
    )
    println("Saved detailed lookup scaling data to ${config.output}")
}

private fun parseArgs(args: Array<String>): CliConfig {
    var output = Path.of("build/results/lookup_scaling.json")
    var dataSizes: List<Int>? = null
    var ramBudgetBytes = 10L * BYTES_PER_GIB
    var series = SeriesSelection.ALL
    var warmupRepetitions = WARMUP_REPETITIONS
    var measurementRepetitions = MEASUREMENT_REPETITIONS
    var pointRepetitions = POINT_REPETITIONS

    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--output" -> {
                require(index + 1 < args.size) { "--output requires a value" }
                output = Path.of(args[index + 1])
                index += 2
            }

            "--sizes" -> {
                require(index + 1 < args.size) { "--sizes requires a comma-separated value" }
                dataSizes = args[index + 1]
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .map(String::toInt)
                    .distinct()
                    .sorted()
                require(dataSizes.isNotEmpty()) { "--sizes must include at least one value" }
                index += 2
            }

            "--ram-budget-gib" -> {
                require(index + 1 < args.size) { "--ram-budget-gib requires a numeric value" }
                ramBudgetBytes = (args[index + 1].toDouble() * BYTES_PER_GIB).toLong()
                require(ramBudgetBytes > 0L) { "--ram-budget-gib must be positive" }
                index += 2
            }

            "--series" -> {
                require(index + 1 < args.size) { "--series requires one of: all, perfecthash, lsh" }
                series = when (args[index + 1].lowercase(Locale.US)) {
                    "all" -> SeriesSelection.ALL
                    "perfecthash" -> SeriesSelection.PERFECT_HASH
                    "lsh" -> SeriesSelection.LSH
                    else -> error("Unsupported --series value: ${args[index + 1]}")
                }
                index += 2
            }

            "--warmup-repetitions" -> {
                require(index + 1 < args.size) { "--warmup-repetitions requires a positive integer value" }
                warmupRepetitions = args[index + 1].toInt()
                require(warmupRepetitions >= 0) { "--warmup-repetitions must be non-negative" }
                index += 2
            }

            "--measurement-repetitions" -> {
                require(index + 1 < args.size) { "--measurement-repetitions requires a positive integer value" }
                measurementRepetitions = args[index + 1].toInt()
                require(measurementRepetitions > 0) { "--measurement-repetitions must be positive" }
                index += 2
            }

            "--point-repetitions" -> {
                require(index + 1 < args.size) { "--point-repetitions requires a positive integer value" }
                pointRepetitions = args[index + 1].toInt()
                require(pointRepetitions > 0) { "--point-repetitions must be positive" }
                index += 2
            }

            else -> error("Unknown argument: ${args[index]}")
        }
    }

    return CliConfig(
        output = output,
        dataSizes = dataSizes,
        ramBudgetBytes = ramBudgetBytes,
        series = series,
        warmupRepetitions = warmupRepetitions,
        measurementRepetitions = measurementRepetitions,
        pointRepetitions = pointRepetitions,
    )
}

private fun measurePerfectHashSeries(
    dataSizes: List<Int>,
    warmupRepetitions: Int,
    measurementRepetitions: Int,
    pointRepetitions: Int,
): List<ScalingPoint> {
    val points = mutableListOf<ScalingPoint>()
    for (dataSize in dataSizes) {
        val point = try {
            measurePerfectHash(
                dataSize = dataSize,
                warmupRepetitions = warmupRepetitions,
                measurementRepetitions = measurementRepetitions,
                pointRepetitions = pointRepetitions,
            )
        } catch (oom: OutOfMemoryError) {
            println("PerfectHash scaling: out of memory at N=$dataSize, stopping this series")
            forceGc()
            break
        }
        points += point
        forceGc()
    }
    return points
}

private fun measurePerfectHash(
    dataSize: Int,
    warmupRepetitions: Int,
    measurementRepetitions: Int,
    pointRepetitions: Int,
): ScalingPoint {
    if (pointRepetitions == 1) {
        return measurePerfectHashOnce(
            dataSize = dataSize,
            warmupRepetitions = warmupRepetitions,
            measurementRepetitions = measurementRepetitions,
            repetition = 1,
            totalRepetitions = 1,
        )
    }

    val runs = List(pointRepetitions) { index ->
        val point = measurePerfectHashOnce(
            dataSize = dataSize,
            warmupRepetitions = warmupRepetitions,
            measurementRepetitions = measurementRepetitions,
            repetition = index + 1,
            totalRepetitions = pointRepetitions,
        )
        forceGc()
        point
    }
    val latencyNsPerOp = medianDouble(runs.map { it.latencyNsPerOp })
    val totalHeapBytes = medianLong(runs.map { it.totalHeapBytes })
    println(
        "PerfectHash lookup scaling median: N=$dataSize " +
            "latency=${formatDouble(latencyNsPerOp, 2)} ns/op, " +
            "totalRAM=${formatTotalRam(totalHeapBytes)}, runs=$pointRepetitions",
    )
    return ScalingPoint(
        dataSize = dataSize,
        latencyNsPerOp = latencyNsPerOp,
        totalHeapBytes = totalHeapBytes,
        samplesNsPerOp = runs.flatMap { it.samplesNsPerOp },
        source = "median $pointRepetitions runs",
        runLatenciesNsPerOp = runs.map { it.latencyNsPerOp },
        runHeapBytes = runs.map { it.totalHeapBytes },
    )
}

private fun measurePerfectHashOnce(
    dataSize: Int,
    warmupRepetitions: Int,
    measurementRepetitions: Int,
    repetition: Int,
    totalRepetitions: Int,
): ScalingPoint {
    println("PerfectHash lookup scaling: N=$dataSize run=$repetition/$totalRepetitions")
    val heapBefore = usedHeapBytes()

    var keys = Array(dataSize, ::perfectHashKey)
    var values = Array(dataSize) { 1 }
    val queryPool = equallySpacedStrings(keys, PERFECT_HASH_QUERY_COUNT)
    val map = PerfectHashMap.build(keys, values)
    keys = emptyArray()
    values = emptyArray()

    val totalHeapBytes = maxOf(0L, usedHeapBytes() - heapBefore)
    val batchOps = perfectHashBatchOps(dataSize)
    repeat(warmupRepetitions) {
        sinkLong = sinkLong xor runPerfectHashLookupBatch(map, queryPool, batchOps)
    }
    val samples = List(measurementRepetitions) {
        val startedAt = System.nanoTime()
        sinkLong = sinkLong xor runPerfectHashLookupBatch(map, queryPool, batchOps)
        (System.nanoTime() - startedAt).toDouble() / batchOps
    }

    val latencyNsPerOp = samples.average()
    println(
        "  latency=${formatDouble(latencyNsPerOp, 2)} ns/op, " +
            "totalRAM=${formatTotalRam(totalHeapBytes)}, batchOps=$batchOps",
    )
    return ScalingPoint(
        dataSize = dataSize,
        latencyNsPerOp = latencyNsPerOp,
        totalHeapBytes = totalHeapBytes,
        samplesNsPerOp = samples,
    )
}

private fun measureLshSeries(
    dataSizes: List<Int>,
    ramBudgetBytes: Long,
    warmupRepetitions: Int,
    measurementRepetitions: Int,
): List<ScalingPoint> {
    val checkpoints = dataSizes.sorted()
    require(checkpoints.isNotEmpty()) { "LSH data sizes must not be empty" }

    val points = mutableListOf<ScalingPoint>()
    val heapBefore = usedHeapBytes()
    val index = RandomProjectionLshIndex(numHashes = 64, numBands = 8, fullScanThreshold = 0)
    val queryPool = ArrayList<RandomProjectionLshIndex.Point3D>(LSH_QUERY_COUNT)
    val queryStride = maxOf(1, checkpoints.last() / LSH_QUERY_COUNT)
    val rng = Random(123L)
    var checkpointCursor = 0

    for (pointIndex in 0 until checkpoints.last()) {
        val point = RandomProjectionLshIndex.Point3D(
            rng.nextDouble() * 100.0,
            rng.nextDouble() * 100.0,
            rng.nextDouble() * 100.0,
        )
        index.add("pt_${pointIndex.toString().padStart(8, '0')}", point)
        if (pointIndex % queryStride == 0 && queryPool.size < LSH_QUERY_COUNT) {
            queryPool += point
        }

        val dataSize = pointIndex + 1
        if (checkpointCursor >= checkpoints.size || dataSize != checkpoints[checkpointCursor]) {
            continue
        }

        val pointStats = measureLshCheckpoint(
            index = index,
            dataSize = dataSize,
            queryPool = queryPool.toTypedArray(),
            heapBefore = heapBefore,
            warmupRepetitions = warmupRepetitions,
            measurementRepetitions = measurementRepetitions,
        )
        points += pointStats
        if (pointStats.totalHeapBytes > ramBudgetBytes) {
            println("LSH scaling: RAM budget exceeded at N=$dataSize")
            break
        }
        checkpointCursor++
    }
    return points
}

private fun measureLshCheckpoint(
    index: RandomProjectionLshIndex,
    dataSize: Int,
    queryPool: Array<RandomProjectionLshIndex.Point3D>,
    heapBefore: Long,
    warmupRepetitions: Int,
    measurementRepetitions: Int,
): ScalingPoint {
    val totalHeapBytes = maxOf(0L, usedHeapBytes() - heapBefore)
    val batchOps = lshBatchOps(dataSize)
    repeat(warmupRepetitions) {
        sinkLong = sinkLong xor runLshLookupBatch(index, queryPool, batchOps).sink
    }

    val samples = mutableListOf<Double>()
    val candidateSamples = mutableListOf<Double>()
    val matchSamples = mutableListOf<Double>()
    repeat(measurementRepetitions) {
        val startedAt = System.nanoTime()
        val stats = runLshLookupBatch(index, queryPool, batchOps)
        sinkLong = sinkLong xor stats.sink
        samples += (System.nanoTime() - startedAt).toDouble() / batchOps
        candidateSamples += stats.avgCandidateCount
        matchSamples += stats.avgMatchCount
    }

    val latencyNsPerOp = samples.average()
    val avgCandidates = candidateSamples.average()
    val avgMatches = matchSamples.average()
    println(
        "LSH lookup scaling: N=$dataSize " +
            "latency=${formatDouble(latencyNsPerOp / 1_000.0, 2)} us/op, " +
            "candidates=${formatDouble(avgCandidates, 1)}, matches=${formatDouble(avgMatches, 1)}, " +
            "totalRAM=${formatTotalRam(totalHeapBytes)}, batchOps=$batchOps",
    )
    return ScalingPoint(
        dataSize = dataSize,
        latencyNsPerOp = latencyNsPerOp,
        totalHeapBytes = totalHeapBytes,
        samplesNsPerOp = samples,
        avgCandidateCount = avgCandidates,
        avgMatchCount = avgMatches,
        candidateSamples = candidateSamples,
        matchSamples = matchSamples,
    )
}

private fun runPerfectHashLookupBatch(
    map: PerfectHashMap<Int>,
    queryPool: Array<String>,
    batchOps: Int,
): Long {
    var sum = 0L
    var cursor = 0
    repeat(batchOps) {
        sum += (map.lookup(queryPool[cursor]) ?: 0).toLong()
        cursor++
        if (cursor == queryPool.size) {
            cursor = 0
        }
    }
    return sum
}

private fun runLshLookupBatch(
    index: RandomProjectionLshIndex,
    queryPool: Array<RandomProjectionLshIndex.Point3D>,
    batchOps: Int,
): LshBatchStats {
    var sink = 0L
    var cursor = 0
    var candidateCount = 0L
    var matchCount = 0L
    repeat(batchOps) {
        val stats = index.findNearWithStats(queryPool[cursor], LSH_MAX_DISTANCE)
        sink += stats.results.size.toLong()
        candidateCount += stats.candidateCount.toLong()
        matchCount += stats.matchCount.toLong()
        cursor++
        if (cursor == queryPool.size) {
            cursor = 0
        }
    }
    return LshBatchStats(
        sink = sink,
        avgCandidateCount = candidateCount.toDouble() / batchOps,
        avgMatchCount = matchCount.toDouble() / batchOps,
    )
}

private fun perfectHashBatchOps(dataSize: Int): Int = when {
    dataSize <= 100_000 -> 1_000_000
    dataSize <= 2_000_000 -> 500_000
    dataSize <= 10_000_000 -> 250_000
    else -> 100_000
}

private fun lshBatchOps(dataSize: Int): Int = when {
    dataSize <= 10_000 -> 20_000
    dataSize <= 100_000 -> 10_000
    else -> 2_000
}

private fun usedHeapBytes(): Long {
    forceGc()
    val runtime = Runtime.getRuntime()
    return runtime.totalMemory() - runtime.freeMemory()
}

private fun forceGc() {
    repeat(3) {
        System.gc()
        Thread.sleep(25L)
    }
}

private fun perfectHashKey(index: Int): String = buildString(12) {
    append("key_")
    append(index.toString().padStart(8, '0'))
}

private fun equallySpacedStrings(values: Array<String>, count: Int): Array<String> {
    val sampleCount = minOf(count, values.size)
    return Array(sampleCount) { index ->
        val sampleIndex = ((index.toLong() * values.size) / sampleCount).toInt()
        values[sampleIndex]
    }
}

private fun writeReport(
    output: Path,
    ramBudgetBytes: Long,
    perfectHash: List<ScalingPoint>,
    lsh: List<ScalingPoint>,
) {
    output.parent?.let(Files::createDirectories)
    Files.writeString(
        output,
        buildString {
            appendLine("{")
            appendLine("""  "generatedAt": "${Instant.now()}",""")
            appendLine("""  "ramBudgetBytes": $ramBudgetBytes,""")
            appendLine("  \"perfectHash\": [")
            appendPoints(perfectHash)
            appendLine("  ],")
            appendLine("  \"lsh\": [")
            appendPoints(lsh)
            appendLine("  ]")
            appendLine("}")
        },
    )
}

private fun StringBuilder.appendPoints(points: List<ScalingPoint>) {
    points.forEachIndexed { index, point ->
        appendLine("    {")
        appendLine("""      "dataSize": ${point.dataSize},""")
        appendLine("""      "latencyNsPerOp": ${formatDouble(point.latencyNsPerOp, 6)},""")
        appendLine("""      "totalHeapBytes": ${point.totalHeapBytes},""")
        point.avgCandidateCount?.let {
            appendLine("""      "avgCandidateCount": ${formatDouble(it, 6)},""")
        }
        point.avgMatchCount?.let {
            appendLine("""      "avgMatchCount": ${formatDouble(it, 6)},""")
        }
        append("""      "samplesNsPerOp": [""")
        append(point.samplesNsPerOp.joinToString(", ") { formatDouble(it, 6) })
        appendLine("],")
        append("""      "candidateSamples": [""")
        append(point.candidateSamples.joinToString(", ") { formatDouble(it, 6) })
        appendLine("],")
        append("""      "matchSamples": [""")
        append(point.matchSamples.joinToString(", ") { formatDouble(it, 6) })
        append("]")
        point.source?.let {
            appendLine(",")
            appendLine("""      "source": "$it",""")
            append("""      "runLatenciesNsPerOp": [""")
            append(point.runLatenciesNsPerOp.joinToString(", ") { value -> formatDouble(value, 6) })
            appendLine("],")
            append("""      "runHeapBytes": [""")
            append(point.runHeapBytes.joinToString(", "))
            appendLine("]")
        } ?: appendLine()
        append("    }")
        if (index != points.lastIndex) {
            append(',')
        }
        appendLine()
    }
}

private fun formatTotalRam(totalBytes: Long): String =
    if (totalBytes >= BYTES_PER_GIB) {
        "${formatDouble(totalBytes.toDouble() / BYTES_PER_GIB, 2)} GiB"
    } else {
        "${formatDouble(totalBytes.toDouble() / (1024.0 * 1024.0), 0)} MiB"
    }

private fun formatDouble(value: Double, digits: Int): String =
    String.format(Locale.US, "%.${digits}f", value)

private fun medianDouble(values: List<Double>): Double {
    require(values.isNotEmpty()) { "Cannot calculate median for empty values" }
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[middle]
    } else {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    }
}

private fun medianLong(values: List<Long>): Long {
    require(values.isNotEmpty()) { "Cannot calculate median for empty values" }
    val sorted = values.sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 1) {
        sorted[middle]
    } else {
        ((sorted[middle - 1] + sorted[middle]) / 2.0).toLong()
    }
}
