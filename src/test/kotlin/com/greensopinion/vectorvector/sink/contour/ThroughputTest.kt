package com.greensopinion.vectorvector.sink.contour

import com.greensopinion.vectorvector.*
import com.greensopinion.vectorvector.elevation.ElevationDataStore
import com.greensopinion.vectorvector.elevation.mapterhorn.ElevationGrid
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.random.Random

class ThroughputTest {
    @Test
    fun `bounded lowland halo is rejected without materializing samples`() {
        val terrain = object : ElevationTile() {
            override val extent = 32
            override val empty = false
            override val conservativeBounds = ElevationBounds(Elevation(5.0), Elevation(6.0))
            override fun get(x: Int, y: Int): Elevation = error("No sample reads expected")
        }
        val store = object : ElevationDataStore { override fun get(tile: TileId) = terrain }
        val options = ContourOptions(minorLevel = 20, smoothing = SmoothingOptions(7.0))
        assertThat(ContourPostProcessor(options, store).generate(TileId(9, 1, 1))).isEmpty()
    }

    @Test
    fun `bounded lookup matches binary search at negative sparse and extreme levels`() {
        val random = Random(42)
        for (levels in listOf(intArrayOf(-200, -50, 20, 50, 60, 100, 200),
            intArrayOf(Int.MIN_VALUE, 0, Int.MAX_VALUE), intArrayOf(20), intArrayOf(10, 10, 20))) {
            val lookup = FastIsolines.LevelLookup(levels)
            val values = levels.flatMap { listOf(it.toDouble(), Math.nextDown(it.toDouble()), Math.nextUp(it.toDouble())) } +
                List(10000) { random.nextDouble(-10000.0, 10000.0) }
            for (value in values) {
                val expected = levels.indexOfFirst { it >= value }.let { if (it < 0) levels.size else it }
                assertThat(lookup.firstAtLeast(value)).isEqualTo(expected)
            }
        }
    }

    @Test
    fun `zero footprint includes neighbouring coastal terrain and world edge`() {
        val zero = ElevationGrid(16, FloatArray(256)).toTile()
        val land = ElevationGrid(16, FloatArray(256) { 100f }).toTile()
        val id = TileId(9, 0, 0)
        val store = object : ElevationDataStore {
            override fun get(tile: TileId): ElevationTile = if (tile.x == 1) land else zero
        }
        val area = ElevationTileArea(id, zero, store)
        assertThat(area.zeroFootprint(0)).isTrue()
        assertThat(area.zeroFootprint(1)).isFalse()
        val edge = object : ElevationDataStore {
            override fun get(tile: TileId): ElevationTile = if (tile.y < 0) EmptyTile(16) else zero
        }
        assertThat(ElevationTileArea(id, zero, edge).zeroFootprint(1)).isFalse()
    }

    @Test
    fun `early rejection preserves boundary levels negatives and NaNs`() {
        fun source(value: Double) = object : ElevationTile() {
            override val extent = 32
            override val empty = false
            override fun get(x: Int, y: Int) = Elevation(if (x == 5 && y == 5) Double.NaN else value)
        }
        fun blur(value: Double) = GaussianBlur.blurForContours(source(value), 2, 7.0, GaussianBlur.Workspace()) { lo, hi ->
            FastIsolines.levels(lo, hi, 20) { true }.isNotEmpty()
        }
        assertThat(blur(5.0)).isNull()
        assertThat(blur(20.0)).isNotNull()
        assertThat(blur(-20.0)).isNotNull()
        assertThat(blur(19.999999999)).isNotNull()
    }

    @Test
    fun `finite smoothing matches NaN aware accumulation away from missing corner`() {
        val r = Random(32)
        val data = DoubleArray(128 * 128) { r.nextDouble(-100.0, 3000.0) }
        fun tile(missing: Boolean) = object : ElevationTile() {
            override val extent = 64
            override val empty = false
            override fun get(x: Int, y: Int) = Elevation(
                if (missing && x == -23 && y == -23) Double.NaN else data[(y + 32) * 128 + x + 32])
        }
        // sigma 7 has a 20px halo; buffer 3 puts the isolated NaN in its outermost corner.
        val a = GaussianBlur.blur(tile(false), 3, 7.0)
        val b = GaussianBlur.blur(tile(true), 3, 7.0)
        for (y in 1 until 64) for (x in 1 until 64)
            assertThat(a.get(x,y).meters.toBits()).isEqualTo(b.get(x,y).meters.toBits())
    }
}
