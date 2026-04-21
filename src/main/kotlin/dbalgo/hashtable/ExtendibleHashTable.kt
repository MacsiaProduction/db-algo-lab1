package dbalgo.hashtable

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import kotlin.math.max

class ExtendibleHashTable(
    private val dir: Path,
    val bucketCapacity: Int = 4,
    maxOpenChannels: Int = 256,
    private val persistDirectoryOnClose: Boolean = true,
) : AutoCloseable {

    internal var globalDepth = 0; private set
    internal var directory = intArrayOf(0); private set
    private var nextBucketId = 1
    private var _size = 0

    private val bucketMetadata = HashMap<Int, BucketMetadata>()
    private val channels = object : LinkedHashMap<Int, FileChannel>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Int, FileChannel>): Boolean {
            return if (size > maxOpenChannels) {
                eldest.value.close()
                true
            } else {
                false
            }
        }
    }

    private var readBuf = ByteBuffer.allocate(initialPageSize())
    private val headerBuf = ByteBuffer.allocate(BUCKET_HEADER_BYTES)
    private val slotBuf = ByteBuffer.allocate(SLOT_BYTES)

    val directorySize get() = directory.size
    val bucketCount get() = nextBucketId

    init {
        Files.createDirectories(dir)
        if (Files.exists(dirFile())) {
            loadDirectory()
        } else {
            writeFreshBucket(0, localDepth = 0, entries = emptyList())
        }
    }

    fun get(key: String): ByteArray? {
        val keyHash = hash(key)
        val id = directory[dirIndex(keyHash)]
        val meta = metadata(id)
        val page = readPage(id, meta)
        val slotIndex = findLiveSlot(page, key, keyHash)
        if (slotIndex < 0) {
            return null
        }
        val slot = readSlot(page, slotIndex)
        return readValue(page, slot)
    }

    fun insert(key: String, value: ByteArray) {
        put(key, value)
    }

    fun update(key: String, value: ByteArray): Boolean {
        val keyHash = hash(key)
        val id = directory[dirIndex(keyHash)]
        val meta = metadata(id)
        val channel = channel(id)
        val page = readPage(id, meta)
        val slotIndex = findLiveSlot(page, key, keyHash)
        if (slotIndex < 0) {
            return false
        }
        val slot = readSlot(page, slotIndex)
        if (value.size <= slot.valueLength) {
            writeBytes(channel, slot.payloadOffset + slot.keyLength, value)
            if (value.size != slot.valueLength) {
                val updatedSlot = slot.copy(valueLength = value.size)
                writeSlot(channel, slotIndex, updatedSlot)
            }
            return true
        }

        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val payloadBytes = keyBytes.size + value.size
        if (freePayloadBytes(meta) >= payloadBytes) {
            val payloadOffset = meta.freeEnd - payloadBytes
            writeRecord(channel, payloadOffset, keyBytes, value)
            val updatedSlot = slot.copy(
                valueLength = value.size,
                payloadOffset = payloadOffset,
            )
            val updatedMeta = meta.copy(freeEnd = payloadOffset)
            writeSlot(channel, slotIndex, updatedSlot)
            writeHeader(channel, updatedMeta)
            bucketMetadata[id] = updatedMeta
            return true
        }

        rewriteBucket(
            id = id,
            localDepth = meta.localDepth,
            entries = collectEntries(page, meta).map { entry ->
                if (entry.keyHash == keyHash && matchesKey(entry.keyBytes, key)) {
                    entry.copy(valueBytes = value)
                } else {
                    entry
                }
            },
        )
        return true
    }

    fun delete(key: String): Boolean {
        val keyHash = hash(key)
        val id = directory[dirIndex(keyHash)]
        val meta = metadata(id)
        val channel = channel(id)
        val page = readPage(id, meta)
        val slotIndex = findLiveSlot(page, key, keyHash)
        if (slotIndex < 0) {
            return false
        }

        val tombstoneSlot = Slot(
            state = SLOT_TOMBSTONE,
            keyHash = 0,
            keyLength = 0,
            valueLength = 0,
            payloadOffset = 0,
        )
        val updatedMeta = meta.copy(
            liveCount = meta.liveCount - 1,
            tombstoneCount = meta.tombstoneCount + 1,
        )
        writeSlot(channel, slotIndex, tombstoneSlot)
        writeHeader(channel, updatedMeta)
        bucketMetadata[id] = updatedMeta
        _size--
        return true
    }

    fun contains(key: String): Boolean = get(key) != null

    fun size() = _size

    override fun close() {
        if (persistDirectoryOnClose) {
            saveDirectory()
        }
        bucketMetadata.clear()
        channels.values.forEach { it.close() }
        channels.clear()
    }

    fun reloadFromDisk() {
        bucketMetadata.clear()
        channels.values.forEach { it.close() }
        channels.clear()
        loadDirectory()
    }

    private fun put(key: String, value: ByteArray) {
        val keyHash = hash(key)
        val dirIndex = dirIndex(keyHash)
        val id = directory[dirIndex]
        val meta = metadata(id)
        val channel = channel(id)
        val page = readPage(id, meta)
        val keyBytes = key.toByteArray(Charsets.UTF_8)
        val search = scanSlots(page, key, keyHash)

        if (search.liveSlotIndex >= 0) {
            val existing = readSlot(page, search.liveSlotIndex)
            if (value.size <= existing.valueLength) {
                writeBytes(channel, existing.payloadOffset + existing.keyLength, value)
                if (value.size != existing.valueLength) {
                    writeSlot(channel, search.liveSlotIndex, existing.copy(valueLength = value.size))
                }
                return
            }

            val payloadBytes = keyBytes.size + value.size
            if (freePayloadBytes(meta) >= payloadBytes) {
                val payloadOffset = meta.freeEnd - payloadBytes
                writeRecord(channel, payloadOffset, keyBytes, value)
                val updatedSlot = existing.copy(
                    valueLength = value.size,
                    payloadOffset = payloadOffset,
                )
                val updatedMeta = meta.copy(freeEnd = payloadOffset)
                writeSlot(channel, search.liveSlotIndex, updatedSlot)
                writeHeader(channel, updatedMeta)
                bucketMetadata[id] = updatedMeta
                return
            }

            rewriteBucket(
                id = id,
                localDepth = meta.localDepth,
                entries = collectEntries(page, meta).map { entry ->
                    if (entry.keyHash == keyHash && matchesKey(entry.keyBytes, key)) {
                        entry.copy(valueBytes = value)
                    } else {
                        entry
                    }
                },
            )
            return
        }

        val reusableSlot = when {
            search.tombstoneSlotIndex >= 0 -> search.tombstoneSlotIndex
            search.emptySlotIndex >= 0 -> search.emptySlotIndex
            else -> -1
        }
        val payloadBytes = keyBytes.size + value.size
        if (reusableSlot >= 0 && freePayloadBytes(meta) >= payloadBytes) {
            val payloadOffset = meta.freeEnd - payloadBytes
            writeRecord(channel, payloadOffset, keyBytes, value)
            val insertedSlot = Slot(
                state = SLOT_LIVE,
                keyHash = keyHash,
                keyLength = keyBytes.size,
                valueLength = value.size,
                payloadOffset = payloadOffset,
            )
            val updatedMeta = meta.copy(
                liveCount = meta.liveCount + 1,
                tombstoneCount = meta.tombstoneCount - if (search.tombstoneSlotIndex == reusableSlot) 1 else 0,
                freeEnd = payloadOffset,
            )
            writeSlot(channel, reusableSlot, insertedSlot)
            writeHeader(channel, updatedMeta)
            bucketMetadata[id] = updatedMeta
            _size++
            return
        }

        if (reusableSlot >= 0) {
            rewriteBucket(
                id = id,
                localDepth = meta.localDepth,
                entries = collectEntries(page, meta) + EntryData(
                    keyHash = keyHash,
                    keyBytes = keyBytes,
                    valueBytes = value,
                ),
            )
            _size++
            return
        }

        split(dirIndex)
        put(key, value)
    }

    private fun split(dirIndex: Int) {
        val id = directory[dirIndex]
        val meta = metadata(id)
        val page = readPage(id, meta)
        val entries = collectEntries(page, meta)
        val localDepth = meta.localDepth
        if (localDepth == globalDepth) {
            doubleDirectory()
        }

        val newId = nextBucketId++
        val leftEntries = ArrayList<EntryData>()
        val rightEntries = ArrayList<EntryData>()
        for (entry in entries) {
            if (((entry.keyHash ushr localDepth) and 1) == 0) {
                leftEntries += entry
            } else {
                rightEntries += entry
            }
        }

        rewriteBucket(id, localDepth + 1, leftEntries)
        writeFreshBucket(newId, localDepth + 1, rightEntries)

        val lowerMask = if (localDepth == 0) 0 else (1 shl localDepth) - 1
        val pattern = dirIndex and lowerMask
        for (i in directory.indices) {
            if ((i and lowerMask) == pattern) {
                directory[i] = if (((i ushr localDepth) and 1) == 0) id else newId
            }
        }
    }

    private fun rewriteBucket(id: Int, localDepth: Int, entries: List<EntryData>) {
        writeFreshBucket(id, localDepth, entries)
    }

    private fun writeFreshBucket(id: Int, localDepth: Int, entries: List<EntryData>) {
        require(entries.size <= bucketCapacity) {
            "Bucket $id overflow: ${entries.size} entries for capacity $bucketCapacity"
        }
        val pageClass = choosePageClass(entries)
        val freeStart = payloadStart()
        val pageSize = pageSize(pageClass)
        val buffer = ByteBuffer.allocate(pageSize)
        var freeEnd = pageSize

        entries.forEachIndexed { slotIndex, entry ->
            val payloadBytes = entry.keyBytes.size + entry.valueBytes.size
            freeEnd -= payloadBytes
            putBytes(buffer, freeEnd, entry.keyBytes)
            putBytes(buffer, freeEnd + entry.keyBytes.size, entry.valueBytes)
            writeSlotToBuffer(
                buffer = buffer,
                slotIndex = slotIndex,
                slot = Slot(
                    state = SLOT_LIVE,
                    keyHash = entry.keyHash,
                    keyLength = entry.keyBytes.size,
                    valueLength = entry.valueBytes.size,
                    payloadOffset = freeEnd,
                ),
            )
        }

        val meta = BucketMetadata(
            localDepth = localDepth,
            liveCount = entries.size,
            tombstoneCount = 0,
            freeStart = freeStart,
            freeEnd = freeEnd,
            pageClass = pageClass,
            pageSize = pageSize,
        )
        writeHeaderToBuffer(buffer, meta)
        writeWholePage(id, meta, buffer)
    }

    private fun metadata(id: Int): BucketMetadata =
        bucketMetadata[id] ?: readBucketMetadata(channel(id)).also { bucketMetadata[id] = it }

    private fun payloadStart(): Int = BUCKET_HEADER_BYTES + bucketCapacity * SLOT_BYTES

    private fun initialPageSize(): Int = pageSize(MIN_BUCKET_RESERVED_BYTES)

    private fun pageSize(pageClass: Int): Int = payloadStart() + pageClass

    private fun choosePageClass(entries: List<EntryData>): Int {
        val estimateBytesPerEntry = when {
            entries.isEmpty() -> DEFAULT_ENTRY_ESTIMATE_BYTES
            else -> max(
                entries.maxOf { it.payloadSize },
                (entries.sumOf { it.payloadSize } + entries.size - 1) / entries.size,
            )
        }
        return choosePageClass(estimateBytesPerEntry)
    }

    private fun choosePageClass(estimatedEntryBytes: Int): Int {
        var pageClass = MIN_BUCKET_RESERVED_BYTES
        val target = max(MIN_BUCKET_RESERVED_BYTES, estimatedEntryBytes * bucketCapacity * 2)
        while (pageClass < target) {
            pageClass *= 2
        }
        return pageClass
    }

    private fun freePayloadBytes(meta: BucketMetadata): Int = meta.freeEnd - meta.freeStart

    private fun readPage(id: Int, meta: BucketMetadata): ByteBuffer {
        ensureReadCapacity(meta.pageSize)
        readBuf.clear().limit(meta.pageSize)
        readFully(channel(id), readBuf, 0)
        readBuf.flip()
        return readBuf
    }

    private fun ensureReadCapacity(required: Int) {
        if (readBuf.capacity() >= required) {
            return
        }
        var nextCapacity = readBuf.capacity()
        while (nextCapacity < required) {
            nextCapacity *= 2
        }
        readBuf = ByteBuffer.allocate(nextCapacity)
    }

    private fun scanSlots(page: ByteBuffer, key: String, keyHash: Int): SlotScan {
        var liveSlotIndex = -1
        var emptySlotIndex = -1
        var tombstoneSlotIndex = -1
        for (slotIndex in 0 until bucketCapacity) {
            val slot = readSlot(page, slotIndex)
            when (slot.state) {
                SLOT_EMPTY -> if (emptySlotIndex < 0) emptySlotIndex = slotIndex
                SLOT_TOMBSTONE -> if (tombstoneSlotIndex < 0) tombstoneSlotIndex = slotIndex
                SLOT_LIVE -> if (slot.keyHash == keyHash && matchesUtf8(key, page, slot.payloadOffset, slot.keyLength)) {
                    liveSlotIndex = slotIndex
                    break
                }
            }
        }
        return SlotScan(liveSlotIndex, emptySlotIndex, tombstoneSlotIndex)
    }

    private fun findLiveSlot(page: ByteBuffer, key: String, keyHash: Int): Int =
        scanSlots(page, key, keyHash).liveSlotIndex

    private fun readValue(page: ByteBuffer, slot: Slot): ByteArray {
        val valueOffset = slot.payloadOffset + slot.keyLength
        return ByteArray(slot.valueLength).also { dst ->
            val copy = page.duplicate()
            copy.position(valueOffset)
            copy.get(dst)
        }
    }

    private fun collectEntries(page: ByteBuffer, meta: BucketMetadata): List<EntryData> {
        if (meta.liveCount == 0) {
            return emptyList()
        }
        val entries = ArrayList<EntryData>(meta.liveCount)
        for (slotIndex in 0 until bucketCapacity) {
            val slot = readSlot(page, slotIndex)
            if (slot.state != SLOT_LIVE) {
                continue
            }
            val keyBytes = ByteArray(slot.keyLength)
            val valueBytes = ByteArray(slot.valueLength)
            val copy = page.duplicate()
            copy.position(slot.payloadOffset)
            copy.get(keyBytes)
            copy.get(valueBytes)
            entries += EntryData(
                keyHash = slot.keyHash,
                keyBytes = keyBytes,
                valueBytes = valueBytes,
            )
        }
        return entries
    }

    private fun readSlot(page: ByteBuffer, slotIndex: Int): Slot {
        val offset = slotOffset(slotIndex)
        return Slot(
            state = page.getInt(offset),
            keyHash = page.getInt(offset + Int.SIZE_BYTES),
            keyLength = page.getInt(offset + Int.SIZE_BYTES * 2),
            valueLength = page.getInt(offset + Int.SIZE_BYTES * 3),
            payloadOffset = page.getInt(offset + Int.SIZE_BYTES * 4),
        )
    }

    private fun slotOffset(slotIndex: Int): Int = BUCKET_HEADER_BYTES + slotIndex * SLOT_BYTES

    private fun writeSlot(channel: FileChannel, slotIndex: Int, slot: Slot) {
        slotBuf.clear()
        slotBuf.putInt(slot.state)
        slotBuf.putInt(slot.keyHash)
        slotBuf.putInt(slot.keyLength)
        slotBuf.putInt(slot.valueLength)
        slotBuf.putInt(slot.payloadOffset)
        slotBuf.flip()
        writeFully(channel, slotBuf, slotOffset(slotIndex).toLong())
    }

    private fun writeSlotToBuffer(buffer: ByteBuffer, slotIndex: Int, slot: Slot) {
        val offset = slotOffset(slotIndex)
        buffer.putInt(offset, slot.state)
        buffer.putInt(offset + Int.SIZE_BYTES, slot.keyHash)
        buffer.putInt(offset + Int.SIZE_BYTES * 2, slot.keyLength)
        buffer.putInt(offset + Int.SIZE_BYTES * 3, slot.valueLength)
        buffer.putInt(offset + Int.SIZE_BYTES * 4, slot.payloadOffset)
    }

    private fun writeHeader(channel: FileChannel, meta: BucketMetadata) {
        headerBuf.clear()
        headerBuf.putInt(BUCKET_MAGIC)
        headerBuf.putInt(STORAGE_VERSION)
        headerBuf.putInt(meta.localDepth)
        headerBuf.putInt(meta.liveCount)
        headerBuf.putInt(meta.tombstoneCount)
        headerBuf.putInt(meta.freeStart)
        headerBuf.putInt(meta.freeEnd)
        headerBuf.putInt(meta.pageClass)
        headerBuf.flip()
        writeFully(channel, headerBuf, 0)
    }

    private fun writeHeaderToBuffer(buffer: ByteBuffer, meta: BucketMetadata) {
        buffer.putInt(0, BUCKET_MAGIC)
        buffer.putInt(Int.SIZE_BYTES, STORAGE_VERSION)
        buffer.putInt(Int.SIZE_BYTES * 2, meta.localDepth)
        buffer.putInt(Int.SIZE_BYTES * 3, meta.liveCount)
        buffer.putInt(Int.SIZE_BYTES * 4, meta.tombstoneCount)
        buffer.putInt(Int.SIZE_BYTES * 5, meta.freeStart)
        buffer.putInt(Int.SIZE_BYTES * 6, meta.freeEnd)
        buffer.putInt(Int.SIZE_BYTES * 7, meta.pageClass)
    }

    private fun writeWholePage(id: Int, meta: BucketMetadata, buffer: ByteBuffer) {
        val channel = channel(id)
        buffer.clear().limit(meta.pageSize)
        channel.truncate(meta.pageSize.toLong())
        writeFully(channel, buffer, 0)
        bucketMetadata[id] = meta
    }

    private fun writeRecord(channel: FileChannel, payloadOffset: Int, keyBytes: ByteArray, valueBytes: ByteArray) {
        writeBytes(channel, payloadOffset, keyBytes)
        writeBytes(channel, payloadOffset + keyBytes.size, valueBytes)
    }

    private fun writeBytes(channel: FileChannel, position: Int, bytes: ByteArray) {
        if (bytes.isEmpty()) {
            return
        }
        writeFully(channel, ByteBuffer.wrap(bytes), position.toLong())
    }

    private fun putBytes(buffer: ByteBuffer, offset: Int, bytes: ByteArray) {
        val copy = buffer.duplicate()
        copy.position(offset)
        copy.put(bytes)
    }

    private fun matchesUtf8(text: String, page: ByteBuffer, offset: Int, byteLength: Int): Boolean {
        val end = offset + byteLength
        var position = offset
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            when {
                ch.code < 0x80 -> {
                    if (position >= end || (page.get(position).toInt() and 0xFF) != ch.code) return false
                    position++
                    index++
                }
                ch.code < 0x800 -> {
                    if (!matchByte(page, position++, end, 0xC0 or (ch.code ushr 6))) return false
                    if (!matchByte(page, position++, end, 0x80 or (ch.code and 0x3F))) return false
                    index++
                }
                ch.isHighSurrogate() -> {
                    if (index + 1 < text.length && text[index + 1].isLowSurrogate()) {
                        val codePoint = Character.toCodePoint(ch, text[index + 1])
                        if (!matchByte(page, position++, end, 0xF0 or (codePoint ushr 18))) return false
                        if (!matchByte(page, position++, end, 0x80 or ((codePoint ushr 12) and 0x3F))) return false
                        if (!matchByte(page, position++, end, 0x80 or ((codePoint ushr 6) and 0x3F))) return false
                        if (!matchByte(page, position++, end, 0x80 or (codePoint and 0x3F))) return false
                        index += 2
                    } else {
                        if (!matchByte(page, position++, end, REPLACEMENT_BYTE)) return false
                        index++
                    }
                }
                ch.isLowSurrogate() -> {
                    if (!matchByte(page, position++, end, REPLACEMENT_BYTE)) return false
                    index++
                }
                else -> {
                    if (!matchByte(page, position++, end, 0xE0 or (ch.code ushr 12))) return false
                    if (!matchByte(page, position++, end, 0x80 or ((ch.code ushr 6) and 0x3F))) return false
                    if (!matchByte(page, position++, end, 0x80 or (ch.code and 0x3F))) return false
                    index++
                }
            }
        }
        return position == end
    }

    private fun matchByte(page: ByteBuffer, position: Int, end: Int, expected: Int): Boolean =
        position < end && (page.get(position).toInt() and 0xFF) == expected

    private fun matchesKey(keyBytes: ByteArray, key: String): Boolean =
        keyBytes.contentEquals(key.toByteArray(Charsets.UTF_8))

    private fun hash(key: String): Int = key.hashCode() and Int.MAX_VALUE

    private fun dirIndex(keyHash: Int): Int = keyHash and ((1 shl globalDepth) - 1)

    private fun bucketFile(id: Int): Path = dir.resolve("bucket_$id.dat")

    private fun dirFile(): Path = dir.resolve("_directory.dat")

    private fun channel(id: Int): FileChannel =
        channels.getOrPut(id) { FileChannel.open(bucketFile(id), READ, WRITE, CREATE) }

    private fun readBucketMetadata(channel: FileChannel): BucketMetadata {
        headerBuf.clear()
        readFully(channel, headerBuf, 0)
        headerBuf.flip()
        require(headerBuf.int == BUCKET_MAGIC) { "Unsupported bucket format" }
        require(headerBuf.int == STORAGE_VERSION) { "Unsupported bucket version" }
        val localDepth = headerBuf.int
        val liveCount = headerBuf.int
        val tombstoneCount = headerBuf.int
        val freeStart = headerBuf.int
        val freeEnd = headerBuf.int
        val pageClass = headerBuf.int
        require(liveCount >= 0) { "Negative live count: $liveCount" }
        require(tombstoneCount >= 0) { "Negative tombstone count: $tombstoneCount" }
        require(pageClass >= MIN_BUCKET_RESERVED_BYTES) { "Invalid page class: $pageClass" }
        val payloadStart = payloadStart()
        require(freeStart == payloadStart) { "Unexpected freeStart $freeStart, expected $payloadStart" }
        require(freeEnd in freeStart..pageSize(pageClass)) {
            "Invalid freeEnd $freeEnd for page class $pageClass"
        }
        return BucketMetadata(
            localDepth = localDepth,
            liveCount = liveCount,
            tombstoneCount = tombstoneCount,
            freeStart = freeStart,
            freeEnd = freeEnd,
            pageClass = pageClass,
            pageSize = pageSize(pageClass),
        )
    }

    private fun saveDirectory() {
        DataOutputStream(BufferedOutputStream(FileOutputStream(dirFile().toFile()))).use { dos ->
            dos.writeInt(DIRECTORY_MAGIC)
            dos.writeInt(STORAGE_VERSION)
            dos.writeInt(globalDepth)
            dos.writeInt(nextBucketId)
            dos.writeInt(_size)
            dos.writeInt(directory.size)
            for (id in directory) {
                dos.writeInt(id)
            }
        }
    }

    private fun loadDirectory() {
        DataInputStream(BufferedInputStream(FileInputStream(dirFile().toFile()))).use { din ->
            require(din.readInt() == DIRECTORY_MAGIC) { "Unsupported directory format" }
            require(din.readInt() == STORAGE_VERSION) { "Unsupported directory version" }
            globalDepth = din.readInt()
            nextBucketId = din.readInt()
            _size = din.readInt()
            directory = IntArray(din.readInt()) { din.readInt() }
        }
    }

    private fun doubleDirectory() {
        val old = directory
        directory = IntArray(old.size * 2)
        for (i in old.indices) {
            directory[i] = old[i]
            directory[i + old.size] = old[i]
        }
        globalDepth++
    }

    private fun readFully(channel: FileChannel, dst: ByteBuffer, position: Long) {
        var offset = position
        while (dst.hasRemaining()) {
            val read = channel.read(dst, offset)
            require(read >= 0) { "Unexpected EOF while reading bucket file" }
            offset += read
        }
    }

    private fun writeFully(channel: FileChannel, src: ByteBuffer, position: Long) {
        var offset = position
        while (src.hasRemaining()) {
            offset += channel.write(src, offset)
        }
    }

    private data class BucketMetadata(
        val localDepth: Int,
        val liveCount: Int,
        val tombstoneCount: Int,
        val freeStart: Int,
        val freeEnd: Int,
        val pageClass: Int,
        val pageSize: Int,
    )

    private data class Slot(
        val state: Int,
        val keyHash: Int,
        val keyLength: Int,
        val valueLength: Int,
        val payloadOffset: Int,
    )

    private data class SlotScan(
        val liveSlotIndex: Int,
        val emptySlotIndex: Int,
        val tombstoneSlotIndex: Int,
    )

    private data class EntryData(
        val keyHash: Int,
        val keyBytes: ByteArray,
        val valueBytes: ByteArray,
    ) {
        val payloadSize: Int get() = keyBytes.size + valueBytes.size
    }

    companion object {
        const val STORAGE_VERSION: Int = 2
        const val DIRECTORY_MAGIC: Int = 0x45485432
        const val BUCKET_MAGIC: Int = 0x424B5432
        const val BUCKET_HEADER_BYTES: Int = Int.SIZE_BYTES * 8
        const val SLOT_BYTES: Int = Int.SIZE_BYTES * 5
        const val MIN_BUCKET_RESERVED_BYTES: Int = 1_024

        private const val SLOT_EMPTY = 0
        private const val SLOT_LIVE = 1
        private const val SLOT_TOMBSTONE = 2
        private const val DEFAULT_ENTRY_ESTIMATE_BYTES = 32
        private const val REPLACEMENT_BYTE = 63
    }
}
