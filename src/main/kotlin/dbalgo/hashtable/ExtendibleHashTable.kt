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
import java.nio.file.StandardOpenOption.*

class ExtendibleHashTable(
    private val dir: Path,
    val bucketCapacity: Int = 4,
    maxOpenChannels: Int = 256
) : AutoCloseable {

    internal var globalDepth = 0; private set
    internal var directory = intArrayOf(0); private set
    private var nextBucketId = 1
    private var _size = 0
    private val channels = object : LinkedHashMap<Int, FileChannel>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Int, FileChannel>): Boolean {
            return if (size > maxOpenChannels) { eldest.value.close(); true } else false
        }
    }
    private var ioBuf = ByteBuffer.allocate(maxOf(4096, bucketCapacity * 128))

    val directorySize get() = directory.size
    val bucketCount get() = nextBucketId

    init {
        Files.createDirectories(dir)
        if (Files.exists(dirFile())) loadDirectory()
        else writeBucket(0, Bucket(0, mutableMapOf()))
    }

    fun get(key: String): ByteArray? = readBucket(directory[dirIndex(key)]).entries[key]

    fun insert(key: String, value: ByteArray) = put(key, value)

    fun update(key: String, value: ByteArray): Boolean {
        val id = directory[dirIndex(key)]
        val bucket = readBucket(id)
        if (key !in bucket.entries) return false
        bucket.entries[key] = value
        writeBucket(id, bucket)
        return true
    }

    fun delete(key: String): Boolean {
        val id = directory[dirIndex(key)]
        val bucket = readBucket(id)
        if (key !in bucket.entries) return false
        bucket.entries.remove(key)
        writeBucket(id, bucket)
        _size--
        return true
    }

    fun contains(key: String) = key in readBucket(directory[dirIndex(key)]).entries

    fun size() = _size

    override fun close() {
        saveDirectory()
        channels.values.forEach { it.close() }
        channels.clear()
    }

    private fun put(key: String, value: ByteArray) {
        val idx = dirIndex(key)
        val id = directory[idx]
        val bucket = readBucket(id)
        if (key in bucket.entries) {
            bucket.entries[key] = value
            writeBucket(id, bucket)
            return
        }
        if (bucket.entries.size < bucketCapacity) {
            bucket.entries[key] = value
            writeBucket(id, bucket)
            _size++
        } else {
            split(idx)
            put(key, value)
        }
    }

    private fun split(dirIdx: Int) {
        val id = directory[dirIdx]
        val bucket = readBucket(id)
        val L = bucket.localDepth
        if (L == globalDepth) doubleDirectory()

        val newId = nextBucketId++
        val entries0 = mutableMapOf<String, ByteArray>()
        val entries1 = mutableMapOf<String, ByteArray>()
        for ((k, v) in bucket.entries)
            if ((hash(k) ushr L) and 1 == 0) entries0[k] = v else entries1[k] = v

        writeBucket(id, Bucket(L + 1, entries0))
        writeBucket(newId, Bucket(L + 1, entries1))

        val lowerMask = if (L == 0) 0 else (1 shl L) - 1
        val pattern = dirIdx and lowerMask
        for (i in directory.indices)
            if ((i and lowerMask) == pattern)
                directory[i] = if ((i ushr L) and 1 == 0) id else newId
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

    private fun dirIndex(key: String) = hash(key) and ((1 shl globalDepth) - 1)

    private fun hash(key: String) = key.hashCode() and Int.MAX_VALUE

    // Работа с файловой системой

    private fun bucketFile(id: Int): Path = dir.resolve("bucket_$id.dat")
    private fun dirFile(): Path = dir.resolve("_directory.dat")

    private fun channel(id: Int) =
        channels.getOrPut(id) { FileChannel.open(bucketFile(id), READ, WRITE, CREATE) }

    private fun readBucket(id: Int): Bucket {
        val ch = channel(id)
        val fileSize = ch.size().toInt()
        if (fileSize == 0) return Bucket(0, mutableMapOf())
        if (ioBuf.capacity() < fileSize) ioBuf = ByteBuffer.allocate(fileSize * 2)
        ioBuf.clear().limit(fileSize)
        ch.read(ioBuf, 0)
        ioBuf.flip()
        val localDepth = ioBuf.int
        val count = ioBuf.int
        val entries = HashMap<String, ByteArray>(count * 2)
        repeat(count) {
            val keyBytes = ByteArray(ioBuf.short.toInt() and 0xFFFF).also { ioBuf.get(it) }
            val valBytes = ByteArray(ioBuf.int).also { ioBuf.get(it) }
            entries[String(keyBytes, Charsets.UTF_8)] = valBytes
        }
        return Bucket(localDepth, entries)
    }

    private fun writeBucket(id: Int, bucket: Bucket) {
        while (true) {
            ioBuf.clear()
            ioBuf.putInt(bucket.localDepth)
            ioBuf.putInt(bucket.entries.size)
            var overflow = false
            for ((k, v) in bucket.entries) {
                val kb = k.toByteArray(Charsets.UTF_8)
                if (ioBuf.remaining() < 6 + kb.size + v.size) {
                    ioBuf = ByteBuffer.allocate(ioBuf.capacity() * 2)
                    overflow = true; break
                }
                ioBuf.putShort(kb.size.toShort()); ioBuf.put(kb)
                ioBuf.putInt(v.size); ioBuf.put(v)
            }
            if (!overflow) break
        }
        ioBuf.flip()
        val ch = channel(id)
        val newSize = ioBuf.limit().toLong()
        if (newSize < ch.size()) ch.truncate(newSize)
        ch.write(ioBuf, 0)
    }

    private fun saveDirectory() {
        DataOutputStream(BufferedOutputStream(FileOutputStream(dirFile().toFile()))).use { dos ->
            dos.writeInt(globalDepth)
            dos.writeInt(nextBucketId)
            dos.writeInt(_size)
            dos.writeInt(directory.size)
            for (id in directory) dos.writeInt(id)
        }
    }

    private fun loadDirectory() {
        DataInputStream(BufferedInputStream(FileInputStream(dirFile().toFile()))).use { din ->
            globalDepth = din.readInt()
            nextBucketId = din.readInt()
            _size = din.readInt()
            directory = IntArray(din.readInt()) { din.readInt() }
        }
    }

    private data class Bucket(val localDepth: Int, val entries: MutableMap<String, ByteArray>)
}
