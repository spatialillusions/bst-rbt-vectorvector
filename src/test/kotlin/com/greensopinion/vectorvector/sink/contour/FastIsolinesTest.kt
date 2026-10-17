package com.greensopinion.vectorvector.sink.contour

import com.greensopinion.vectorvector.ElevationTileArea
import com.greensopinion.vectorvector.TileId
import com.greensopinion.vectorvector.sink.SyntheticTerrain
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import kotlin.math.hypot

class FastIsolinesTest {
    @Test
    fun `matches MarchingSquares point for point`() {
        val store = SyntheticTerrain(128)
        val tile = TileId(9, 241, 160)
        val buffer = 3
        val area = ElevationTileArea(tile, store.get(tile), store)
        val grid = GaussianBlur.materialize(area, buffer)
        val factor = 4096.0 / 128
        val reference = MarchingSquares(grid, 4096, buffer, roundPoints = false).generateIsolines(20)
        val levels = FastIsolines.levels(grid.data.min(), grid.data.max(), 20) { true }
        val fast = FastIsolines(grid.data, grid.size, grid.buffer, factor).generate(levels, -buffer, 128 - 1 + buffer)

        assertThat(fast.keys).isEqualTo(reference.keys)
        assertThat(fast.keys).hasSizeGreaterThan(5)
        for (level in reference.keys) {
            val expected = reference.getValue(level).flatten().toSet()
            val actual = fast.getValue(level).flatten().toSet()
            assertThat(actual).describedAs("level $level").isEqualTo(expected)
            fun length(lines: List<List<DoublePoint>>) = lines.sumOf { l -> l.zipWithNext().sumOf { (a, b) -> hypot(a.x - b.x, a.y - b.y) } }
            assertThat(length(fast.getValue(level))).isCloseTo(length(reference.getValue(level)), within(1e-6))
        }
    }
}
