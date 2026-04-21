package dbalgo.perfecthash

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PerfectHashMapTest {

    @Test
    fun `build и lookup базовый`() {
        val keys = arrayOf("red", "green", "blue", "yellow")
        val values = arrayOf(0xFF0000, 0x00FF00, 0x0000FF, 0xFFFF00)
        val ph = PerfectHashMap.build(keys, values)

        assertEquals(4, ph.size)
        for (i in keys.indices) assertEquals(values[i], ph.lookup(keys[i]))
    }

    @Test
    fun `lookup отсутствующего ключа возвращает null`() {
        val ph = PerfectHashMap.build(arrayOf("a", "b"), arrayOf(1, 2))
        assertNull(ph.lookup("c"))
        assertNull(ph.lookup(""))
    }

    @Test
    fun `пустая таблица`() {
        val ph = PerfectHashMap.build(emptyArray<String>(), emptyArray<Int>())
        assertEquals(0, ph.size)
        assertNull(ph.lookup("anything"))
    }

    @Test
    fun `один ключ`() {
        val ph = PerfectHashMap.build(arrayOf("solo"), arrayOf(42))
        assertEquals(42, ph.lookup("solo"))
        assertNull(ph.lookup("other"))
    }

    @Test
    fun `zero collisions - все ключи дают разные значения`() {
        val n = 500
        val keys = Array(n) { "key_$it" }
        val values = Array(n) { it }
        val ph = PerfectHashMap.build(keys, values)

        assertEquals(n, ph.size)
        for (i in 0 until n) assertEquals(i, ph.lookup(keys[i]))
    }

    @Test
    fun `строковые значения`() {
        val keys = arrayOf("name", "city", "country")
        val values = arrayOf("Alice", "Moscow", "Russia")
        val ph = PerfectHashMap.build(keys, values)

        assertEquals("Alice", ph.lookup("name"))
        assertEquals("Moscow", ph.lookup("city"))
        assertEquals("Russia", ph.lookup("country"))
    }

    @Test
    fun `unicode ключи`() {
        val keys = arrayOf("привет", "мир", "🌍")
        val values = arrayOf(1, 2, 3)
        val ph = PerfectHashMap.build(keys, values)

        assertEquals(1, ph.lookup("привет"))
        assertEquals(3, ph.lookup("🌍"))
    }

    @Test
    fun `lookup preserves utf8 semantics for malformed surrogate strings`() {
        val malformed = "\uD800"
        val valid = "ok"
        val ph = PerfectHashMap.build(arrayOf(malformed, valid), arrayOf(7, 9))

        assertEquals(7, ph.lookup(malformed))
        assertEquals(9, ph.lookup(valid))
        assertNull(ph.lookup("\uD801"))
    }

    @Test
    fun `похожие ключи не путаются`() {
        val keys = arrayOf("abc", "abd", "aec", "bbc")
        val values = arrayOf(1, 2, 3, 4)
        val ph = PerfectHashMap.build(keys, values)

        for (i in keys.indices) assertEquals(values[i], ph.lookup(keys[i]))
        assertNull(ph.lookup("ab"))
        assertNull(ph.lookup("abcd"))
    }

    @Test
    fun `struct size within FKS theoretical bound`() {
        // FKS guarantees: level-1 ≤ 2N, level-2 total ≤ 4N → totalSlots ≤ 6N
        val n = 1000
        val keys   = Array(n) { "key_$it" }
        val values = Array(n) { it }
        val ph = PerfectHashMap.build(keys, values)

        val total     = ph.totalSlots()
        val topLevel  = ph.topLevelSize()
        val secondary = total - topLevel

        assertTrue(total <= 6 * n,
            "totalSlots=$total exceeds 6N=${6 * n} — FKS bound violated")
        assertTrue(topLevel <= 2 * n,
            "topLevelSize=$topLevel exceeds 2N=${2 * n}")
        assertTrue(secondary <= 4 * n,
            "secondarySlots=$secondary exceeds 4N=${4 * n}")

        // Overhead ratio should be reasonable
        val ratio = total.toDouble() / n
        assertTrue(ratio <= 6.0,
            "Space overhead ratio ${"%.2f".format(ratio)}x exceeds FKS bound of 6x")
    }
}
