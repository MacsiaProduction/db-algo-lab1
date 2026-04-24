package dbalgo.hashtable

import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import org.openjdk.jmh.infra.BenchmarkParams
import org.openjdk.jmh.infra.Blackhole
import org.openjdk.jmh.infra.IterationParams
import org.openjdk.jmh.runner.IterationType
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.EnumMap
import java.util.Random
import java.util.concurrent.TimeUnit

@State(Scope.Benchmark)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(1)
open class HashTableIntervalBenchmark {

    @Param("1000000")
    var dataSize: Int = 0

    private lateinit var scenario: Scenario
    private lateinit var snapshotDir: Path
    private lateinit var originalSnapshotCache: Map<String, ByteArray>
    private lateinit var snapshotLayout: SnapshotLayout

    private var workingDir: Path? = null
    private var ht: ExtendibleHashTable? = null

    private lateinit var presentKeys: Array<String>
    private lateinit var missingKeys: Array<String>
    private lateinit var growthKeys: Array<String>

    private lateinit var payloadA: ByteArray
    private lateinit var payloadB: ByteArray
    private var usePayloadA = false

    private val stringBatches = EnumMap<StringBatchKind, Array<String>>(StringBatchKind::class.java)
    private val insertBatches = EnumMap<InsertBatchKind, Array<InsertOp>>(InsertBatchKind::class.java)

    @Setup(Level.Trial)
    fun setupTrial(params: BenchmarkParams) {
        scenario = Scenario.fromBenchmark(params.benchmark)

        val presentCount = dataSize / 2
        val missingCount = dataSize - presentCount
        presentKeys = Array(presentCount, ::baseKey)
        missingKeys = Array(missingCount) { index -> baseKey(index + presentCount) }
        growthKeys = Array(presentCount, ::growthKey)
        payloadA = fixedString("patch", 0).toByteArray()
        payloadB = fixedString("delta", 0).toByteArray()

        snapshotDir = Files.createTempDirectory("ht_interval_snapshot")
        buildHalfFilledSnapshot(snapshotDir)
        originalSnapshotCache = cacheDirectory(snapshotDir)
        snapshotLayout = readSnapshotLayout(originalSnapshotCache)

        buildStringBatches()
        buildDeleteBatches()
        buildInsertBatches()

        if (!scenario.spec.requiresFreshBaseline) {
            resetWorkingCopy(originalSnapshotCache)
        }
    }

    @Setup(Level.Iteration)
    fun setupIteration(iterationParams: IterationParams) {
        if (scenario.spec.requiresFreshBaseline) {
            resetWorkingCopy(originalSnapshotCache)
        }
        if (scenario.spec.payloadBehavior == PayloadBehavior.ALTERNATE) {
            usePayloadA = !usePayloadA
        }
        if (iterationParams.type == IterationType.MEASUREMENT && scenario.spec.shouldPrewarm) {
            prewarmScenario()
        }
    }

    @TearDown(Level.Trial)
    fun tearDownTrial() {
        closeWorkingCopy()
        deleteDirectory(snapshotDir)
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OperationsPerInvocation(GET_BATCH_OPS)
    fun benchGetHit(blackhole: Blackhole) {
        val table = table()
        for (key in stringBatch(StringBatchKind.GET_HIT)) {
            blackhole.consume(table.get(key))
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OperationsPerInvocation(GET_BATCH_OPS)
    fun benchGetMiss(blackhole: Blackhole) {
        val table = table()
        for (key in stringBatch(StringBatchKind.GET_MISS)) {
            blackhole.consume(table.get(key))
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OperationsPerInvocation(UPDATE_BATCH_OPS)
    fun benchUpdateHit() {
        val table = table()
        val payload = activePayload()
        for (key in stringBatch(StringBatchKind.UPDATE_HIT)) {
            table.update(key, payload)
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OperationsPerInvocation(UPDATE_BATCH_OPS)
    fun benchUpdateMiss() {
        val table = table()
        for (key in stringBatch(StringBatchKind.UPDATE_MISS)) {
            table.update(key, payloadA)
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OperationsPerInvocation(INSERT_OVERWRITE_BATCH_OPS)
    fun benchInsertOverwrite() {
        val table = table()
        val payload = activePayload()
        for (key in stringBatch(StringBatchKind.INSERT_OVERWRITE)) {
            table.insert(key, payload)
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OperationsPerInvocation(INSERT_GROWTH_NO_SPLIT_BATCH_OPS)
    fun benchInsertGrowthNoSplit() {
        val table = table()
        for (op in insertBatch(InsertBatchKind.NO_SPLIT)) {
            table.insert(op.key, op.value)
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OperationsPerInvocation(INSERT_GROWTH_SPLIT_BATCH_OPS)
    fun benchInsertGrowthSplit() {
        val table = table()
        for (op in insertBatch(InsertBatchKind.SPLIT)) {
            table.insert(op.key, op.value)
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OperationsPerInvocation(DELETE_BATCH_OPS)
    fun benchDeleteDense() {
        val table = table()
        for (key in stringBatch(StringBatchKind.DELETE_DENSE)) {
            table.delete(key)
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.SingleShotTime)
    @OperationsPerInvocation(DELETE_BATCH_OPS)
    fun benchDeleteSparse() {
        val table = table()
        for (key in stringBatch(StringBatchKind.DELETE_SPARSE)) {
            table.delete(key)
        }
    }

    private fun buildStringBatches() {
        stringBatches[StringBatchKind.GET_HIT] = buildCanonicalBucketBatch(
            keys = shuffledCopy(presentKeys.asList(), GET_HIT_SEED),
            bucketCount = GET_HIT_BUCKET_COUNT,
            keysPerBucket = GET_HIT_KEYS_PER_BUCKET,
            seed = GET_HIT_SEED,
            label = "getHit",
        )
        stringBatches[StringBatchKind.GET_MISS] = buildCanonicalBucketBatch(
            keys = shuffledCopy(missingKeys.asList(), GET_MISS_SEED),
            bucketCount = GET_MISS_BUCKET_COUNT,
            keysPerBucket = GET_MISS_KEYS_PER_BUCKET,
            seed = GET_MISS_SEED,
            label = "getMiss",
        )
        stringBatches[StringBatchKind.UPDATE_HIT] = buildCanonicalBucketBatch(
            keys = shuffledCopy(presentKeys.asList(), UPDATE_HIT_SEED),
            bucketCount = UPDATE_HIT_BUCKET_COUNT,
            keysPerBucket = UPDATE_HIT_KEYS_PER_BUCKET,
            seed = UPDATE_HIT_SEED,
            label = "updateHit",
        )
        stringBatches[StringBatchKind.UPDATE_MISS] = buildCanonicalBucketBatch(
            keys = shuffledCopy(missingKeys.asList(), UPDATE_MISS_SEED),
            bucketCount = UPDATE_MISS_BUCKET_COUNT,
            keysPerBucket = UPDATE_MISS_KEYS_PER_BUCKET,
            seed = UPDATE_MISS_SEED,
            label = "updateMiss",
        )
        stringBatches[StringBatchKind.INSERT_OVERWRITE] = buildCanonicalBucketBatch(
            keys = shuffledCopy(presentKeys.asList(), INSERT_OVERWRITE_SEED),
            bucketCount = INSERT_OVERWRITE_BUCKET_COUNT,
            keysPerBucket = INSERT_OVERWRITE_KEYS_PER_BUCKET,
            seed = INSERT_OVERWRITE_SEED,
            label = "insertOverwrite",
        )
    }

    private fun buildCanonicalBucketBatch(
        keys: List<String>,
        bucketCount: Int,
        keysPerBucket: Int,
        seed: Long,
        label: String,
    ): Array<String> {
        val groupedKeys = groupKeysByBucket(keys)
        val allEntryCounts = snapshotLayout.bucketStats.values.map { it.entryCount }.sorted()
        require(allEntryCounts.isNotEmpty()) { "No baseline buckets available for $label" }

        val minEntryCount = allEntryCounts.first()
        val maxEntryCount = allEntryCounts.last()
        var lowEntryCount = quantileValue(allEntryCounts, 1, 4)
        var highEntryCount = quantileValue(allEntryCounts, 3, 4)

        while (true) {
            val candidateBucketIds = groupedKeys.keys.filter { bucketId ->
                val entryCount = snapshotLayout.bucketStats.getValue(bucketId).entryCount
                entryCount in lowEntryCount..highEntryCount && groupedKeys.getValue(bucketId).size >= keysPerBucket
            }
            if (candidateBucketIds.size >= bucketCount) {
                val orderedBuckets = shuffledCopy(candidateBucketIds, seed).take(bucketCount)
                val batch = ArrayList<String>(bucketCount * keysPerBucket)
                for (bucketId in orderedBuckets) {
                    batch += groupedKeys.getValue(bucketId).take(keysPerBucket)
                }
                require(batch.size == bucketCount * keysPerBucket) {
                    "$label built batch of ${batch.size} ops, expected ${bucketCount * keysPerBucket}"
                }
                return batch.toTypedArray()
            }
            if (lowEntryCount == minEntryCount && highEntryCount == maxEntryCount) {
                break
            }
            if (lowEntryCount > minEntryCount) lowEntryCount--
            if (highEntryCount < maxEntryCount) highEntryCount++
        }

        error(
            "Need $bucketCount buckets with $keysPerBucket keys each for $label, " +
                "but could not assemble a canonical batch"
        )
    }

    private fun groupKeysByBucket(keys: List<String>): Map<Int, List<String>> {
        val grouped = LinkedHashMap<Int, MutableList<String>>()
        for (key in keys) {
            grouped.getOrPut(bucketIdForKey(key, snapshotLayout), ::ArrayList) += key
        }
        return grouped
    }

    private fun buildDeleteBatches() {
        val orderedStats = snapshotLayout.bucketStats.values.sortedBy { it.bytesPerEntry }
        val quartileSize = maxOf(1, orderedStats.size / 4)
        val sparseBucketIds = orderedStats.take(quartileSize).map { it.bucketId }.toHashSet()
        val denseBucketIds = orderedStats.takeLast(quartileSize).map { it.bucketId }.toHashSet()

        stringBatches[StringBatchKind.DELETE_DENSE] = buildBucketClassBatch(
            keys = presentKeys.asList(),
            allowedBucketIds = denseBucketIds,
            bucketCount = DELETE_DENSE_BUCKET_COUNT,
            keysPerBucket = DELETE_DENSE_KEYS_PER_BUCKET,
            seed = DELETE_DENSE_SEED,
            label = "deleteDense",
        )
        stringBatches[StringBatchKind.DELETE_SPARSE] = buildBucketClassBatch(
            keys = presentKeys.asList(),
            allowedBucketIds = sparseBucketIds,
            bucketCount = DELETE_SPARSE_BUCKET_COUNT,
            keysPerBucket = DELETE_SPARSE_KEYS_PER_BUCKET,
            seed = DELETE_SPARSE_SEED,
            label = "deleteSparse",
        )

        validateDeleteBatch(stringBatch(StringBatchKind.DELETE_DENSE), denseBucketIds, "deleteDense")
        validateDeleteBatch(stringBatch(StringBatchKind.DELETE_SPARSE), sparseBucketIds, "deleteSparse")
    }

    private fun buildBucketClassBatch(
        keys: List<String>,
        allowedBucketIds: Set<Int>,
        bucketCount: Int,
        keysPerBucket: Int,
        seed: Long,
        label: String,
    ): Array<String> {
        val grouped = groupKeysByBucket(keys).filterKeys { it in allowedBucketIds }
        val candidateBucketIds = grouped.keys.filter { bucketId ->
            grouped.getValue(bucketId).size >= keysPerBucket
        }
        require(candidateBucketIds.size >= bucketCount) {
            "Need $bucketCount buckets with >=$keysPerBucket keys for $label, got ${candidateBucketIds.size}"
        }

        val orderedBuckets = shuffledCopy(candidateBucketIds, seed).take(bucketCount)
        val batch = ArrayList<String>(bucketCount * keysPerBucket)
        for (bucketId in orderedBuckets) {
            batch += grouped.getValue(bucketId).take(keysPerBucket)
        }
        require(batch.size == bucketCount * keysPerBucket) {
            "$label built batch of ${batch.size} ops, expected ${bucketCount * keysPerBucket}"
        }
        return batch.toTypedArray()
    }

    private fun buildInsertBatches() {
        val growthReplayOrder = shuffledOrder(growthKeys.size, GROWTH_REPLAY_SEED)
        insertBatches[InsertBatchKind.NO_SPLIT] = exactInsertBatch(buildCanonicalNoSplitIndices(growthReplayOrder))
        insertBatches[InsertBatchKind.SPLIT] = exactInsertBatch(buildSplitIndices(growthReplayOrder))

        validateInsertBatch(insertBatch(InsertBatchKind.NO_SPLIT), expectDirectoryChange = false, label = "insertGrowthNoSplit")
        validateInsertBatch(insertBatch(InsertBatchKind.SPLIT), expectDirectoryChange = true, label = "insertGrowthSplit")
    }

    private fun buildCanonicalNoSplitIndices(order: IntArray): IntArray {
        val slackByBucket = snapshotLayout.bucketStats.mapValuesTo(HashMap()) { (_, stat) ->
            BUCKET_CAPACITY - stat.entryCount
        }
        val positiveSlackValues = slackByBucket.values.filter { it > 0 }.sorted()
        require(positiveSlackValues.isNotEmpty()) { "No positive-slack buckets available for insertGrowthNoSplit" }

        val minSlack = positiveSlackValues.first()
        val maxSlack = positiveSlackValues.last()
        var lowSlack = quantileValue(positiveSlackValues, 1, 4)
        var highSlack = quantileValue(positiveSlackValues, 3, 4)

        while (true) {
            val selected = selectNoSplitIndices(order, slackByBucket, lowSlack, highSlack)
            if (selected.size >= INSERT_GROWTH_NO_SPLIT_BATCH_OPS) {
                val shuffled = shuffledCopy(selected, GROWTH_NO_SPLIT_BATCH_SEED)
                return shuffled.take(INSERT_GROWTH_NO_SPLIT_BATCH_OPS).toIntArray()
            }
            if (lowSlack == minSlack && highSlack == maxSlack) {
                break
            }
            if (lowSlack > minSlack) lowSlack--
            if (highSlack < maxSlack) highSlack++
        }

        error("Need $INSERT_GROWTH_NO_SPLIT_BATCH_OPS canonical no-split inserts, but could not assemble a stable batch")
    }

    private fun selectNoSplitIndices(
        order: IntArray,
        slackByBucket: Map<Int, Int>,
        lowSlack: Int,
        highSlack: Int,
    ): List<Int> {
        val selected = ArrayList<Int>(INSERT_GROWTH_NO_SPLIT_BATCH_OPS)
        val perBucketUsage = HashMap<Int, Int>()
        for (index in order) {
            val bucketId = bucketIdForKey(growthKeys[index], snapshotLayout)
            val slack = slackByBucket.getValue(bucketId)
            if (slack < lowSlack || slack > highSlack) {
                continue
            }
            val used = perBucketUsage.getOrDefault(bucketId, 0)
            if (used >= minOf(NO_SPLIT_BUCKET_QUOTA, slack)) {
                continue
            }
            perBucketUsage[bucketId] = used + 1
            selected += index
        }
        return selected
    }

    private fun buildSplitIndices(order: IntArray): IntArray {
        val slackByBucket = snapshotLayout.bucketStats.mapValuesTo(HashMap()) { (_, stat) ->
            BUCKET_CAPACITY - stat.entryCount
        }
        val splitIndices = ArrayList<Int>(INSERT_GROWTH_SPLIT_BATCH_OPS)

        for (index in order) {
            val bucketId = bucketIdForKey(growthKeys[index], snapshotLayout)
            val slack = slackByBucket.getValue(bucketId)
            if (snapshotLayout.bucketStats.getValue(bucketId).entryCount >= BUCKET_CAPACITY &&
                splitIndices.size < INSERT_GROWTH_SPLIT_BATCH_OPS
            ) {
                splitIndices += index
            }
            if (slack > 0) {
                slackByBucket[bucketId] = slack - 1
            }
            if (splitIndices.size >= INSERT_GROWTH_SPLIT_BATCH_OPS) {
                break
            }
        }

        require(splitIndices.size >= INSERT_GROWTH_SPLIT_BATCH_OPS) {
            "Need $INSERT_GROWTH_SPLIT_BATCH_OPS split insert candidates, got ${splitIndices.size}"
        }
        return splitIndices.toIntArray()
    }

    private fun validateDeleteBatch(
        batch: Array<String>,
        expectedBucketIds: Set<Int>,
        label: String,
    ) {
        for (key in batch) {
            require(bucketIdForKey(key, snapshotLayout) in expectedBucketIds) {
                "$label picked key outside expected bucket class: $key"
            }
        }
    }

    private fun validateInsertBatch(
        batch: Array<InsertOp>,
        expectDirectoryChange: Boolean,
        label: String,
    ) {
        withFreshTable(originalSnapshotCache) { table ->
            val beforeBucketCount = table.bucketCount
            val beforeDirectorySize = table.directorySize
            var changed = false
            for (op in batch) {
                val opBeforeBucketCount = table.bucketCount
                val opBeforeDirectorySize = table.directorySize
                table.insert(op.key, op.value)
                if (table.bucketCount != opBeforeBucketCount || table.directorySize != opBeforeDirectorySize) {
                    changed = true
                }
            }
            val finalChanged = table.bucketCount != beforeBucketCount || table.directorySize != beforeDirectorySize
            if (expectDirectoryChange) {
                require(changed && finalChanged) { "$label batch did not change bucketCount/directorySize" }
            } else {
                require(!changed && !finalChanged) { "$label batch unexpectedly changed bucketCount/directorySize" }
            }
        }
    }

    private fun prewarmScenario() {
        val table = table()
        for (key in scenarioPrewarmKeys()) {
            table.get(key)
        }
    }

    private fun scenarioPrewarmKeys(): Array<String> {
        val spec = scenario.spec
        val stringBatch = spec.stringBatch
        if (stringBatch != null) {
            return stringBatch(stringBatch)
        }
        val insertBatch = spec.insertBatch
        if (insertBatch != null) {
            return insertBatch(insertBatch).map { it.key }.toTypedArray()
        }
        return emptyArray()
    }

    private fun buildHalfFilledSnapshot(dir: Path) {
        ExtendibleHashTable(dir, bucketCapacity = BUCKET_CAPACITY, maxOpenChannels = maxOpenChannels()).use { table ->
            for (index in presentKeys.indices) {
                table.insert(presentKeys[index], valueBytes(index))
            }
        }
    }

    private fun cacheDirectory(dir: Path): Map<String, ByteArray> {
        val cached = LinkedHashMap<String, ByteArray>()
        Files.walk(dir).use { paths ->
            paths.forEach { current ->
                if (Files.isRegularFile(current)) {
                    cached[dir.relativize(current).toString()] = Files.readAllBytes(current)
                }
            }
        }
        return cached
    }

    private fun readSnapshotLayout(cache: Map<String, ByteArray>): SnapshotLayout {
        val directoryBytes = cache[DIRECTORY_FILE] ?: error("Missing $DIRECTORY_FILE in snapshot cache")
        val directoryStream = DataInputStream(ByteArrayInputStream(directoryBytes))
        require(directoryStream.readInt() == ExtendibleHashTable.DIRECTORY_MAGIC) { "Unsupported directory format" }
        require(directoryStream.readInt() == ExtendibleHashTable.STORAGE_VERSION) { "Unsupported directory version" }
        val globalDepth = directoryStream.readInt()
        directoryStream.readInt()
        directoryStream.readInt()
        val directorySize = directoryStream.readInt()
        val directory = IntArray(directorySize) { directoryStream.readInt() }

        val bucketStats = LinkedHashMap<Int, BucketStat>()
        for ((relativePath, bytes) in cache) {
            if (!relativePath.startsWith("bucket_") || !relativePath.endsWith(".dat")) {
                continue
            }
            val bucketId = relativePath.removePrefix("bucket_").removeSuffix(".dat").toInt()
            val data = DataInputStream(ByteArrayInputStream(bytes))
            require(data.readInt() == ExtendibleHashTable.BUCKET_MAGIC) { "Unsupported bucket format" }
            require(data.readInt() == ExtendibleHashTable.STORAGE_VERSION) { "Unsupported bucket version" }
            data.readInt()
            val entryCount = data.readInt()
            data.readInt()
            val freeStart = data.readInt()
            val freeEnd = data.readInt()
            val pageClass = data.readInt()
            val totalPageBytes = bytes.size
            val payloadUsedBytes = totalPageBytes - freeEnd
            bucketStats[bucketId] = BucketStat(
                bucketId = bucketId,
                entryCount = entryCount,
                pageBytes = totalPageBytes,
                payloadUsedBytes = payloadUsedBytes,
                pageClass = pageClass,
                freePayloadBytes = freeEnd - freeStart,
            )
        }
        return SnapshotLayout(globalDepth, directory, bucketStats)
    }

    private fun bucketIdForKey(key: String, layout: SnapshotLayout): Int {
        val mask = if (layout.globalDepth == 0) 0 else (1 shl layout.globalDepth) - 1
        val dirIndex = (key.hashCode() and Int.MAX_VALUE) and mask
        return layout.directory[dirIndex]
    }

    private fun resetWorkingCopy(cache: Map<String, ByteArray>) {
        val dir = workingDir ?: Files.createTempDirectory("ht_interval_work").also { workingDir = it }
        rewriteWorkingDir(dir, cache)
        val currentTable = ht
        if (currentTable == null) {
            ht = ExtendibleHashTable(
                dir,
                bucketCapacity = BUCKET_CAPACITY,
                maxOpenChannels = maxOpenChannels(),
                persistDirectoryOnClose = false,
            )
        } else {
            currentTable.reloadFromDisk()
        }
    }

    private fun rewriteWorkingDir(dir: Path, cache: Map<String, ByteArray>) {
        Files.createDirectories(dir)
        val expectedFiles = cache.keys.toHashSet()
        Files.walk(dir).sorted(Comparator.reverseOrder()).use { paths ->
            paths.forEach { current ->
                if (current == dir) return@forEach
                if (Files.isRegularFile(current)) {
                    val relativePath = dir.relativize(current).toString()
                    if (relativePath !in expectedFiles) {
                        Files.deleteIfExists(current)
                    }
                } else if (Files.isDirectory(current) && directoryIsEmpty(current)) {
                    Files.deleteIfExists(current)
                }
            }
        }
        for ((relativePath, bytes) in cache) {
            val file = dir.resolve(relativePath)
            Files.createDirectories(file.parent)
            Files.write(file, bytes)
        }
    }

    private fun directoryIsEmpty(dir: Path): Boolean =
        Files.list(dir).use { children -> !children.findAny().isPresent }

    private fun materializeWorkingDir(cache: Map<String, ByteArray>): Path {
        val dir = Files.createTempDirectory("ht_interval_work")
        rewriteWorkingDir(dir, cache)
        return dir
    }

    private fun withFreshTable(cache: Map<String, ByteArray>, action: (ExtendibleHashTable) -> Unit) {
        val dir = materializeWorkingDir(cache)
        try {
            ExtendibleHashTable(dir, bucketCapacity = BUCKET_CAPACITY, maxOpenChannels = maxOpenChannels()).use(action)
        } finally {
            deleteDirectory(dir)
        }
    }

    private fun closeWorkingCopy() {
        ht?.close()
        ht = null
        workingDir?.let(::deleteDirectory)
        workingDir = null
    }

    private fun deleteDirectory(dir: Path?) {
        if (dir == null || !Files.exists(dir)) {
            return
        }
        Files.walk(dir).sorted(Comparator.reverseOrder()).use { paths ->
            paths.forEach(Files::deleteIfExists)
        }
    }

    private fun table(): ExtendibleHashTable = ht ?: error("Working table is not initialized for $scenario")

    private fun stringBatch(kind: StringBatchKind): Array<String> =
        stringBatches[kind] ?: error("Missing string batch for $kind")

    private fun insertBatch(kind: InsertBatchKind): Array<InsertOp> =
        insertBatches[kind] ?: error("Missing insert batch for $kind")

    private fun activePayload(): ByteArray = if (usePayloadA) payloadA else payloadB

    private fun exactInsertBatch(indices: IntArray): Array<InsertOp> =
        Array(indices.size) { position ->
            val index = indices[position]
            InsertOp(growthKeys[index], valueBytes(index))
        }

    private fun shuffledOrder(size: Int, seed: Long): IntArray {
        val order = IntArray(size) { it }
        val rng = Random(seed)
        for (i in order.lastIndex downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = order[i]
            order[i] = order[j]
            order[j] = tmp
        }
        return order
    }

    private fun <T> shuffledCopy(items: List<T>, seed: Long): List<T> {
        val copy = items.toMutableList()
        val rng = Random(seed)
        for (i in copy.lastIndex downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = copy[i]
            copy[i] = copy[j]
            copy[j] = tmp
        }
        return copy
    }

    private fun maxOpenChannels(): Int = dataSize / 16 + 128

    private fun baseKey(index: Int): String = fixedString("key", index)

    private fun growthKey(index: Int): String = fixedString("ins", index)

    private fun valueBytes(index: Int): ByteArray = fixedString("value", index).toByteArray()

    private fun fixedString(prefix: String, index: Int): String = buildString(16) {
        append(prefix)
        append('_')
        append(index.toString().padStart(8, '0'))
    }

    private enum class Tier {
        STRICT,
        DIAGNOSTIC,
    }

    private enum class BenchmarkModeKind {
        AVERAGE_TIME,
        SINGLE_SHOT_TIME,
    }

    private enum class BaselineKind {
        ORIGINAL,
    }

    private enum class PayloadBehavior {
        FIXED,
        ALTERNATE,
    }

    private enum class ValidationMode {
        NONE,
        NO_SPLIT,
        SPLIT,
        DELETE_DENSE,
        DELETE_SPARSE,
    }

    private enum class StringBatchKind {
        GET_HIT,
        GET_MISS,
        UPDATE_HIT,
        UPDATE_MISS,
        INSERT_OVERWRITE,
        DELETE_DENSE,
        DELETE_SPARSE,
    }

    private enum class InsertBatchKind {
        NO_SPLIT,
        SPLIT,
    }

    private data class ScenarioSpec(
        val tier: Tier,
        val benchmarkMode: BenchmarkModeKind,
        val batchOps: Int,
        val baselineKind: BaselineKind,
        val requiresFreshBaseline: Boolean,
        val shouldPrewarm: Boolean,
        val payloadBehavior: PayloadBehavior,
        val validationMode: ValidationMode,
        val stringBatch: StringBatchKind? = null,
        val insertBatch: InsertBatchKind? = null,
    )

    private enum class Scenario(val spec: ScenarioSpec) {
        GET_HIT(
            ScenarioSpec(
                tier = Tier.STRICT,
                benchmarkMode = BenchmarkModeKind.AVERAGE_TIME,
                batchOps = GET_BATCH_OPS,
                baselineKind = BaselineKind.ORIGINAL,
                requiresFreshBaseline = false,
                shouldPrewarm = true,
                payloadBehavior = PayloadBehavior.FIXED,
                validationMode = ValidationMode.NONE,
                stringBatch = StringBatchKind.GET_HIT,
            )
        ),
        GET_MISS(
            ScenarioSpec(
                tier = Tier.STRICT,
                benchmarkMode = BenchmarkModeKind.AVERAGE_TIME,
                batchOps = GET_BATCH_OPS,
                baselineKind = BaselineKind.ORIGINAL,
                requiresFreshBaseline = false,
                shouldPrewarm = true,
                payloadBehavior = PayloadBehavior.FIXED,
                validationMode = ValidationMode.NONE,
                stringBatch = StringBatchKind.GET_MISS,
            )
        ),
        UPDATE_HIT(
            ScenarioSpec(
                tier = Tier.STRICT,
                benchmarkMode = BenchmarkModeKind.AVERAGE_TIME,
                batchOps = UPDATE_BATCH_OPS,
                baselineKind = BaselineKind.ORIGINAL,
                requiresFreshBaseline = false,
                shouldPrewarm = true,
                payloadBehavior = PayloadBehavior.ALTERNATE,
                validationMode = ValidationMode.NONE,
                stringBatch = StringBatchKind.UPDATE_HIT,
            )
        ),
        UPDATE_MISS(
            ScenarioSpec(
                tier = Tier.STRICT,
                benchmarkMode = BenchmarkModeKind.AVERAGE_TIME,
                batchOps = UPDATE_BATCH_OPS,
                baselineKind = BaselineKind.ORIGINAL,
                requiresFreshBaseline = false,
                shouldPrewarm = true,
                payloadBehavior = PayloadBehavior.FIXED,
                validationMode = ValidationMode.NONE,
                stringBatch = StringBatchKind.UPDATE_MISS,
            )
        ),
        INSERT_OVERWRITE(
            ScenarioSpec(
                tier = Tier.STRICT,
                benchmarkMode = BenchmarkModeKind.AVERAGE_TIME,
                batchOps = INSERT_OVERWRITE_BATCH_OPS,
                baselineKind = BaselineKind.ORIGINAL,
                requiresFreshBaseline = false,
                shouldPrewarm = true,
                payloadBehavior = PayloadBehavior.ALTERNATE,
                validationMode = ValidationMode.NONE,
                stringBatch = StringBatchKind.INSERT_OVERWRITE,
            )
        ),
        INSERT_GROWTH_NO_SPLIT(
            ScenarioSpec(
                tier = Tier.STRICT,
                benchmarkMode = BenchmarkModeKind.SINGLE_SHOT_TIME,
                batchOps = INSERT_GROWTH_NO_SPLIT_BATCH_OPS,
                baselineKind = BaselineKind.ORIGINAL,
                requiresFreshBaseline = true,
                shouldPrewarm = true,
                payloadBehavior = PayloadBehavior.FIXED,
                validationMode = ValidationMode.NO_SPLIT,
                insertBatch = InsertBatchKind.NO_SPLIT,
            )
        ),
        INSERT_GROWTH_SPLIT(
            ScenarioSpec(
                tier = Tier.DIAGNOSTIC,
                benchmarkMode = BenchmarkModeKind.SINGLE_SHOT_TIME,
                batchOps = INSERT_GROWTH_SPLIT_BATCH_OPS,
                baselineKind = BaselineKind.ORIGINAL,
                requiresFreshBaseline = true,
                shouldPrewarm = false,
                payloadBehavior = PayloadBehavior.FIXED,
                validationMode = ValidationMode.SPLIT,
                insertBatch = InsertBatchKind.SPLIT,
            )
        ),
        DELETE_DENSE(
            ScenarioSpec(
                tier = Tier.STRICT,
                benchmarkMode = BenchmarkModeKind.SINGLE_SHOT_TIME,
                batchOps = DELETE_BATCH_OPS,
                baselineKind = BaselineKind.ORIGINAL,
                requiresFreshBaseline = true,
                shouldPrewarm = true,
                payloadBehavior = PayloadBehavior.FIXED,
                validationMode = ValidationMode.DELETE_DENSE,
                stringBatch = StringBatchKind.DELETE_DENSE,
            )
        ),
        DELETE_SPARSE(
            ScenarioSpec(
                tier = Tier.DIAGNOSTIC,
                benchmarkMode = BenchmarkModeKind.SINGLE_SHOT_TIME,
                batchOps = DELETE_BATCH_OPS,
                baselineKind = BaselineKind.ORIGINAL,
                requiresFreshBaseline = true,
                shouldPrewarm = false,
                payloadBehavior = PayloadBehavior.FIXED,
                validationMode = ValidationMode.DELETE_SPARSE,
                stringBatch = StringBatchKind.DELETE_SPARSE,
            )
        );

        companion object {
            fun fromBenchmark(benchmark: String): Scenario = when {
                benchmark.endsWith(".benchGetHit") -> GET_HIT
                benchmark.endsWith(".benchGetMiss") -> GET_MISS
                benchmark.endsWith(".benchUpdateHit") -> UPDATE_HIT
                benchmark.endsWith(".benchUpdateMiss") -> UPDATE_MISS
                benchmark.endsWith(".benchInsertOverwrite") -> INSERT_OVERWRITE
                benchmark.endsWith(".benchInsertGrowthNoSplit") -> INSERT_GROWTH_NO_SPLIT
                benchmark.endsWith(".benchInsertGrowthSplit") -> INSERT_GROWTH_SPLIT
                benchmark.endsWith(".benchDeleteDense") -> DELETE_DENSE
                benchmark.endsWith(".benchDeleteSparse") -> DELETE_SPARSE
                else -> error("Unsupported interval benchmark method: $benchmark")
            }
        }
    }

    private data class InsertOp(val key: String, val value: ByteArray)

    private data class SnapshotLayout(
        val globalDepth: Int,
        val directory: IntArray,
        val bucketStats: Map<Int, BucketStat>,
    )

    private data class BucketStat(
        val bucketId: Int,
        val entryCount: Int,
        val pageBytes: Int,
        val payloadUsedBytes: Int,
        val pageClass: Int,
        val freePayloadBytes: Int,
    ) {
        val bytesPerEntry: Double = pageBytes.toDouble() / maxOf(1, entryCount)
    }

    companion object {
        private const val RANDOM_SEED = 12345L
        private const val BUCKET_CAPACITY = 64
        private const val DIRECTORY_FILE = "_directory.dat"
        private const val NO_SPLIT_BUCKET_QUOTA = 2

        private const val GET_BATCH_OPS = 16_384
        private const val UPDATE_BATCH_OPS = 16_384
        private const val INSERT_OVERWRITE_BATCH_OPS = 8_192
        private const val INSERT_GROWTH_NO_SPLIT_BATCH_OPS = 8_192
        private const val INSERT_GROWTH_SPLIT_BATCH_OPS = 4_096
        private const val DELETE_BATCH_OPS = 8_192

        private const val GET_MISS_BUCKET_COUNT = 1_024
        private const val GET_HIT_BUCKET_COUNT = 2_048
        private const val GET_HIT_KEYS_PER_BUCKET = 8
        private const val UPDATE_HIT_BUCKET_COUNT = 2_048
        private const val UPDATE_HIT_KEYS_PER_BUCKET = 8
        private const val UPDATE_MISS_BUCKET_COUNT = 1_024
        private const val INSERT_OVERWRITE_BUCKET_COUNT = 512
        private const val GET_MISS_KEYS_PER_BUCKET = 16
        private const val UPDATE_MISS_KEYS_PER_BUCKET = 16
        private const val INSERT_OVERWRITE_KEYS_PER_BUCKET = 16

        private const val DELETE_DENSE_BUCKET_COUNT = 512
        private const val DELETE_DENSE_KEYS_PER_BUCKET = 16
        private const val DELETE_SPARSE_BUCKET_COUNT = 512
        private const val DELETE_SPARSE_KEYS_PER_BUCKET = 16

        private const val GET_HIT_SEED = RANDOM_SEED + 1
        private const val GET_MISS_SEED = RANDOM_SEED + 2
        private const val UPDATE_HIT_SEED = RANDOM_SEED + 3
        private const val UPDATE_MISS_SEED = RANDOM_SEED + 4
        private const val INSERT_OVERWRITE_SEED = RANDOM_SEED + 5
        private const val DELETE_DENSE_SEED = RANDOM_SEED + 6
        private const val DELETE_SPARSE_SEED = RANDOM_SEED + 7
        private const val GROWTH_REPLAY_SEED = RANDOM_SEED + 8
        private const val GROWTH_NO_SPLIT_BATCH_SEED = RANDOM_SEED + 9
    }

    private fun quantileValue(values: List<Int>, numerator: Int, denominator: Int): Int {
        val index = (values.lastIndex * numerator) / denominator
        return values[index]
    }
}
