package com.readcomic.shelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class ComicShapeTest {
    @Test
    fun bindingFollowsThePageCount() {
        assertEquals(Binding.STAPLED, ComicShape.binding(24))
        assertEquals(Binding.PAPERBACK, ComicShape.binding(160))
        assertEquals(Binding.PAPERBACK, ComicShape.binding(199))
        assertEquals(Binding.HARDCOVER, ComicShape.binding(200))
        assertEquals(Binding.HARDCOVER, ComicShape.binding(420))
        assertTrue(ComicShape.thickness(420) > ComicShape.thickness(160))
        assertTrue(ComicShape.thickness(160) > ComicShape.thickness(24))
    }

    @Test
    fun everyShapeFillsItsBoxAndHasValidTriangles() {
        for (pages in listOf(8, 24, 64, 120, 290, 420, 900)) {
            val binding = ComicShape.binding(pages)
            val t = ComicShape.thickness(pages)
            val mesh = ComicShape.build(0.17f, 0.258f, t, binding)
            val count = mesh.vertices.size / 9
            assertTrue(count in 3..32767)
            assertEquals(0, mesh.indices.size % 3)
            assertTrue(mesh.indices.all { it in 0 until count })
            var min = floatArrayOf(1f, 1f, 1f)
            var max = floatArrayOf(-1f, -1f, -1f)
            for (i in 0 until count) {
                for (axis in 0..2) {
                    val value = mesh.vertices[i * 9 + axis]
                    assertTrue("$binding $pages: vertex outside the box", abs(value) <= 0.5f + 1e-3f)
                    min[axis] = minOf(min[axis], value)
                    max[axis] = maxOf(max[axis], value)
                }
                val n = (3..5).map { mesh.vertices[i * 9 + it] }
                assertEquals(1f, sqrt(n.sumOf { (it * it).toDouble() }).toFloat(), 1e-3f)
                val part = mesh.vertices[i * 9 + 8].toInt()
                assertTrue(part in 0..7)
            }
            // The shape reaches every side of its box.
            for (axis in 0..2) {
                assertEquals("$binding $pages axis $axis", -0.5f, min[axis], 0.02f)
                assertEquals("$binding $pages axis $axis", 0.5f, max[axis], 0.02f)
            }
        }
    }
}
