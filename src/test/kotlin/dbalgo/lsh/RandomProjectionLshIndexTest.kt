package dbalgo.lsh

import dbalgo.lsh.RandomProjectionLshIndex.Point3D
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class RandomProjectionLshIndexTest {

    private fun newIndex() = RandomProjectionLshIndex(numHashes = 64, numBands = 8)

    @Test
    fun `близкие точки находятся`() {
        val idx = newIndex()
        idx.add("p1", Point3D(1.0, 2.0, 3.0))
        idx.add("p2", Point3D(1.01, 2.01, 3.01))
        idx.add("far", Point3D(100.0, 100.0, 100.0))

        val near = idx.findNear(Point3D(1.0, 2.0, 3.0), 0.1)
        val ids = near.map { it.first }
        assertTrue("p1" in ids)
        assertTrue("p2" in ids)
        assertFalse("far" in ids)
    }

    @Test
    fun `далёкие точки не попадают`() {
        val idx = newIndex()
        idx.add("a", Point3D(0.0, 0.0, 0.0))
        idx.add("b", Point3D(1000.0, 1000.0, 1000.0))

        val near = idx.findNear(Point3D(0.0, 0.0, 0.0), 1.0)
        val ids = near.map { it.first }
        assertTrue("a" in ids)
        assertFalse("b" in ids)
    }

    @Test
    fun `findAllNearPairs`() {
        val idx = newIndex()
        idx.add("a", Point3D(0.0, 0.0, 0.0))
        idx.add("b", Point3D(0.01, 0.01, 0.01))
        idx.add("c", Point3D(50.0, 50.0, 50.0))

        val pairs = idx.findAllNearPairs(0.1)
        assertTrue(pairs.any { setOf(it.first, it.second) == setOf("a", "b") })
        assertFalse(pairs.any { "c" in setOf(it.first, it.second) })
    }

    @Test
    fun `пустой индекс`() {
        val idx = newIndex()
        assertTrue(idx.findNear(Point3D(0.0, 0.0, 0.0), 1.0).isEmpty())
        assertTrue(idx.findAllNearPairs(1.0).isEmpty())
    }

    @Test
    fun `size`() {
        val idx = newIndex()
        assertEquals(0, idx.size())
        idx.add("a", Point3D(1.0, 2.0, 3.0))
        assertEquals(1, idx.size())
    }

    @Test
    fun `точка найдёт саму себя`() {
        val idx = newIndex()
        val p = Point3D(5.0, 5.0, 5.0)
        idx.add("self", p)
        val near = idx.findNear(p, 0.001)
        assertTrue(near.any { it.first == "self" && it.second < 0.001 })
    }

    @Test
    fun `кластер из близких точек`() {
        val idx = newIndex()
        for (i in 0 until 50) {
            idx.add("p$i", Point3D(i * 0.001, i * 0.001, i * 0.001))
        }
        idx.add("far", Point3D(999.0, 999.0, 999.0))

        val near = idx.findNear(Point3D(0.025, 0.025, 0.025), 0.1)
        assertTrue(near.size >= 5)
        assertFalse(near.any { it.first == "far" })
    }

    @Test
    fun `расстояние точно вычисляется`() {
        val a = Point3D(0.0, 0.0, 0.0)
        val b = Point3D(3.0, 4.0, 0.0)
        assertEquals(5.0, a.distanceTo(b), 1e-10)
    }
}
