package com.greensopinion.vectorvector.sink.contour

import com.greensopinion.vectorvector.Elevation
import com.greensopinion.vectorvector.ElevationTile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SaddleCellTest {
    private fun reference(data: DoubleArray, size: Int = 2) =
        MarchingSquares(Grid(data, size), extent = size, buffer = 0, roundPoints = false)
            .generateIsolines(20).getOrDefault(20, emptyList())

    private fun fast(data: DoubleArray, size: Int = 2) =
        FastIsolines(data, size, 0, 1.0).generate(intArrayOf(20), 0, size - 1).getOrDefault(20, emptyList())

    private fun endpoints(lines: List<List<DoublePoint>>) =
        lines.map { setOf(it.first(), it.last()) }.toSet()

    @Test
    fun `every cell case preserves exactly its straddling edge crossings`() {
        val corners = listOf(0, 1, 3, 2)
        val edges = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 0)
        val centers = listOf(DoublePoint(.5, 0.0), DoublePoint(1.0, .5), DoublePoint(.5, 1.0), DoublePoint(0.0, .5))
        for (case in 0..15) {
            val data = DoubleArray(4) { i -> if (case and (1 shl corners.indexOf(i)) != 0) 30.0 else 10.0 }
            val expected = edges.indices.filter { edge ->
                val (a, b) = edges[edge]
                (case and (1 shl a) != 0) != (case and (1 shl b) != 0)
            }.map { centers[it] }.toSet()
            for (lines in listOf(reference(data), fast(data))) {
                assertThat(lines.flatten().toSet()).describedAs("case $case").isEqualTo(expected)
                assertThat(lines).hasSize(expected.size / 2)
            }
        }
    }

    @Test
    fun `both saddle orientations retain all four edge crossings`() {
        for (grid in listOf(doubleArrayOf(30.0, 10.0, 10.0, 30.0), doubleArrayOf(10.0, 30.0, 30.0, 10.0))) {
            for (lines in listOf(reference(grid), fast(grid))) {
                assertThat(lines).hasSize(2)
                assertThat(lines.flatten().toSet()).containsExactlyInAnyOrder(
                    DoublePoint(.5, 0.0), DoublePoint(1.0, .5), DoublePoint(.5, 1.0), DoublePoint(0.0, .5))
            }
            assertThat(endpoints(fast(grid))).isEqualTo(endpoints(reference(grid)))
        }
    }

    @Test
    fun `bilinear determinant selects connectivity rather than average corner height`() {
        // The center is above 20 but the bilinear saddle is below it: high corners stay separate.
        val grid = doubleArrayOf(70.0, 0.0, 0.0, 25.0)
        for (lines in listOf(reference(grid), fast(grid))) {
            assertThat(lines).hasSize(2)
            assertThat(endpoints(lines)).containsExactlyInAnyOrder(
                setOf(DoublePoint(50.0 / 70, 0.0), DoublePoint(0.0, 50.0 / 70)),
                setOf(DoublePoint(1.0, .8), DoublePoint(.8, 1.0)))
        }
    }

    @Test
    fun `connectivity follows the opposite determinant sign and is invariant under height inversion`() {
        val grid = doubleArrayOf(50.0, 0.0, 0.0, 50.0)
        val inverted = DoubleArray(4) { 40.0 - grid[it] }
        val expected = setOf(
            setOf(DoublePoint(.6, 0.0), DoublePoint(1.0, .4)),
            setOf(DoublePoint(0.0, .6), DoublePoint(.4, 1.0)))
        for (values in listOf(grid, inverted)) {
            assertThat(endpoints(reference(values))).isEqualTo(expected)
            assertThat(endpoints(fast(values))).isEqualTo(expected)
        }
    }

    @Test
    fun `a checkerboard has no dangling interior contour ends`() {
        val size = 4
        val grid = DoubleArray(size * size) { if ((it % size + it / size) % 2 == 0) 30.0 else 10.0 }
        for (lines in listOf(reference(grid, size), fast(grid, size))) {
            val ends = lines.filter { it.first() != it.last() }.flatMap { listOf(it.first(), it.last()) }
            assertThat(ends).hasSize(12)
            assertThat(ends.all { it.x == 0.0 || it.y == 0.0 || it.x == 3.0 || it.y == 3.0 }).isTrue()
        }
    }

    private class Grid(private val data: DoubleArray, override val extent: Int) : ElevationTile() {
        override val empty = false
        override fun get(x: Int, y: Int) = Elevation(data[y * extent + x])
    }
}
