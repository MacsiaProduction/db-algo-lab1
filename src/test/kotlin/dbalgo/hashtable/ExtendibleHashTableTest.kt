package dbalgo.hashtable

import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path

class ExtendibleHashTableTest {

    private lateinit var dir: Path
    private lateinit var ht: ExtendibleHashTable

    @BeforeEach
    fun setUp() {
        dir = Files.createTempDirectory("ext_ht_test")
        ht = ExtendibleHashTable(dir, bucketCapacity = 4)
    }

    @AfterEach
    fun tearDown() {
        ht.close()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `insert and get`() {
        ht.insert("key1", "value1".toByteArray())
        assertArrayEquals("value1".toByteArray(), ht.get("key1"))
        assertEquals(1, ht.size())
    }

    @Test
    fun `get несуществующего ключа возвращает null`() {
        assertNull(ht.get("absent"))
    }

    @Test
    fun `update перезаписывает значение`() {
        ht.insert("k", "v1".toByteArray())
        assertTrue(ht.update("k", "v2".toByteArray()))
        assertArrayEquals("v2".toByteArray(), ht.get("k"))
        assertEquals(1, ht.size())
    }

    @Test
    fun `update несуществующего ключа возвращает false`() {
        assertFalse(ht.update("ghost", "v".toByteArray()))
    }

    @Test
    fun `delete удаляет ключ`() {
        ht.insert("k", "v".toByteArray())
        assertTrue(ht.delete("k"))
        assertNull(ht.get("k"))
        assertFalse(ht.contains("k"))
        assertEquals(0, ht.size())
    }

    @Test
    fun `delete несуществующего ключа возвращает false`() {
        assertFalse(ht.delete("ghost"))
    }

    @Test
    fun `пустые ключ и значение`() {
        ht.insert("", ByteArray(0))
        assertArrayEquals(ByteArray(0), ht.get(""))
    }

    @Test
    fun `unicode ключи`() {
        ht.insert("ключ_юникод_🔑", "данные".toByteArray())
        assertArrayEquals("данные".toByteArray(), ht.get("ключ_юникод_🔑"))
    }

    @Test
    fun `split occurs when bucket overflows`() {
        val keys = (0..9).map { "key_$it" }
        keys.forEach { ht.insert(it, it.toByteArray()) }
        assertEquals(keys.size, ht.size())
        keys.forEach { assertArrayEquals(it.toByteArray(), ht.get(it)) }
    }

    @Test
    fun `directory doubles when local depth equals global depth`() {
        val depthBefore = ht.globalDepth   // starts at 0
        for (i in 0..19) ht.insert("k$i", "v$i".toByteArray())
        assertTrue(ht.globalDepth > depthBefore,
            "globalDepth should grow from $depthBefore, got ${ht.globalDepth}")
    }

    @Test
    fun `multiple splits - все ключи доступны`() {
        val n = 200
        for (i in 0 until n) ht.insert("key_$i", "val_$i".toByteArray())
        assertEquals(n, ht.size())
        for (i in 0 until n) assertArrayEquals("val_$i".toByteArray(), ht.get("key_$i"))
    }

    @Test
    fun `split after delete - no phantom entries`() {
        for (i in 0..7)  ht.insert("k$i", "v$i".toByteArray())
        for (i in 0..3)  ht.delete("k$i")
        for (i in 8..15) ht.insert("k$i", "v$i".toByteArray())

        for (i in 0..3)  assertNull(ht.get("k$i"), "k$i should be deleted")
        for (i in 4..15) assertArrayEquals("v$i".toByteArray(), ht.get("k$i"))
    }

    @Test
    fun `массовые операции - 10K записей`() {
        val n = 10_000
        for (i in 0 until n) ht.insert("k$i", "v$i".toByteArray())
        assertEquals(n, ht.size())
        for (i in 0 until n) assertArrayEquals("v$i".toByteArray(), ht.get("k$i"))

        for (i in 0 until n step 2) ht.delete("k$i")
        assertEquals(n / 2, ht.size())
        for (i in 0 until n step 2) assertNull(ht.get("k$i"))
        for (i in 1 until n step 2) assertArrayEquals("v$i".toByteArray(), ht.get("k$i"))
    }

    @Test
    fun `persistence - данные сохраняются после переоткрытия`() {
        ht.insert("persist", "data".toByteArray())
        ht.close()

        val ht2 = ExtendibleHashTable(dir, bucketCapacity = 4)
        assertArrayEquals("data".toByteArray(), ht2.get("persist"))
        ht2.close()
    }

    @Test
    fun `persistence - delete виден после переоткрытия`() {
        ht.insert("a", "1".toByteArray())
        ht.insert("b", "2".toByteArray())
        ht.delete("a")
        ht.close()

        val ht2 = ExtendibleHashTable(dir, bucketCapacity = 4)
        assertNull(ht2.get("a"))
        assertArrayEquals("2".toByteArray(), ht2.get("b"))
        assertEquals(1, ht2.size())
        ht2.close()
    }

    @Test
    fun `persistence - directory и splits сохраняются после переоткрытия`() {
        val n = 100
        for (i in 0 until n) ht.insert("k$i", "v$i".toByteArray())
        val depthAfterInserts = ht.globalDepth
        ht.close()

        val ht2 = ExtendibleHashTable(dir, bucketCapacity = 4)
        assertEquals(depthAfterInserts, ht2.globalDepth)
        assertEquals(n, ht2.size())
        for (i in 0 until n) assertArrayEquals("v$i".toByteArray(), ht2.get("k$i"))
        ht2.close()
    }

    @Test
    fun `insert duplicate key overwrites value and does not increment size`() {
        ht.insert("k", "v1".toByteArray())
        ht.insert("k", "v2".toByteArray())
        assertArrayEquals("v2".toByteArray(), ht.get("k"))
        assertEquals(1, ht.size())
    }

    @Test
    fun `lru channel eviction - evicted channels are transparently reopened`() {
        val smallHt = ExtendibleHashTable(dir.resolve("lru"), bucketCapacity = 4, maxOpenChannels = 2)
        val n = 50
        for (i in 0 until n) smallHt.insert("k$i", "v$i".toByteArray())
        for (i in 0 until n) assertArrayEquals("v$i".toByteArray(), smallHt.get("k$i"))
        smallHt.close()
    }

    @Test
    fun `ioBuf grows when bucket entry exceeds initial buffer`() {
        val bigValue = ByteArray(8192) { it.toByte() }
        ht.insert("big", bigValue)
        assertArrayEquals(bigValue, ht.get("big"))
    }
}
