package com.greensopinion.vectorvector.sink.contour

import com.greensopinion.vectorvector.Elevation
import com.greensopinion.vectorvector.ElevationTile
import com.greensopinion.vectorvector.INVALID_ELEVATION
import com.greensopinion.vectorvector.Tile
import com.greensopinion.vectorvector.TileId
import com.greensopinion.vectorvector.metrics.SingletonMetricsProvider
import com.greensopinion.vectorvector.profile.RbtProfile
import com.greensopinion.vectorvector.sink.CapturingRepository
import com.greensopinion.vectorvector.sink.SyntheticTerrain
import com.greensopinion.vectorvector.sink.VectorTileSink
import com.greensopinion.vectorvector.tile.TileFeature
import com.greensopinion.vectorvector.tile.TileModelReader
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import vector_tile.VectorTile
import kotlin.math.abs
import kotlin.math.hypot

class RbtIndexTest {
    @Test
    fun `index is the largest divisor`() {
        val cases = mapOf(
            800 to 800, 1600 to 800, 1200 to 400, 600 to 200, 300 to 100, 150 to 50, 40 to 20, 20 to 20,
            2060 to 20, 2050 to 50, 0 to 800, -20 to 20, -50 to 50, -400 to 400, -1200 to 400, 10 to 10
        )
        cases.forEach { (ele, index) -> assertThat(rbtIndex(ele)).describedAs("ele $ele").isEqualTo(index) }
    }
}

class RbtProfileTest {
    private val profile = RbtProfile()

    private fun levelsAt(z: Int, range: IntRange): List<Int> {
        val o = profile.contourOptions(TileId(z, 0, 0))!!
        return range.filter { it % o.minorLevel == 0 && o.levelFilter?.invoke(it) != false && it != 0 }
    }

    @Test
    fun `levels per zoom match RBT`() {
        assertThat(profile.contourOptions(TileId(8, 0, 0))).isNull()
        assertThat(profile.contourOptions(TileId(14, 0, 0))).isNull()
        assertThat(levelsAt(9, 0..1000)).containsExactly(200, 400, 600, 800, 1000)
        assertThat(levelsAt(10, 0..600)).containsExactly(200, 400, 600)
        assertThat(levelsAt(11, 0..400)).containsExactly(100, 200, 300, 400)
        assertThat(levelsAt(12, 0..200)).containsExactly(20, 40, 50, 60, 80, 100, 120, 140, 150, 160, 180, 200)
        assertThat(levelsAt(13, -60..0)).containsExactly(-60, -50, -40, -20)
    }
}

class ChaikinTest {
    @Test
    fun `open lines keep their endpoints`() {
        val line = Line(listOf(DoublePoint(0.0, 0.0), DoublePoint(10.0, 0.0), DoublePoint(10.0, 10.0)))
        val smoothed = Chaikin.smooth(line, 3)
        assertThat(smoothed.points.first()).isEqualTo(DoublePoint(0.0, 0.0))
        assertThat(smoothed.points.last()).isEqualTo(DoublePoint(10.0, 10.0))
        assertThat(smoothed.points.size).isGreaterThan(line.points.size)
    }

    @Test
    fun `rings stay closed`() {
        val ring = Line(
            listOf(
                DoublePoint(0.0, 0.0), DoublePoint(10.0, 0.0), DoublePoint(10.0, 10.0),
                DoublePoint(0.0, 10.0), DoublePoint(0.0, 0.0)
            )
        )
        val smoothed = Chaikin.smooth(ring, 2)
        assertThat(smoothed.isClosed).isTrue()
        assertThat(smoothed.points).doesNotContain(DoublePoint(0.0, 0.0)) // corners are cut
        assertThat(smoothed.points.size).isEqualTo(4 * 4 + 1)
    }

    @Test
    fun `zero iterations is the identity`() {
        val line = Line(listOf(DoublePoint(0.0, 0.0), DoublePoint(1.0, 2.0), DoublePoint(3.0, 1.0)))
        assertThat(Chaikin.smooth(line, 0).points).isEqualTo(line.points)
    }
}

class GaussianBlurTest {
    private fun tile(f: (Int, Int) -> Double) = object : ElevationTile() {
        override val empty = false
        override val extent = 16
        override fun get(x: Int, y: Int) = Elevation(f(x, y))
    }

    @Test
    fun `reused scratch matches fresh blur exactly and never aliases retained output`() {
        val workspace = GaussianBlur.Workspace()
        val field = tile { x, y -> if ((x + y) % 13 == 0) Double.NEGATIVE_INFINITY else 75 * Math.sin(x * .3) + y * .27 }
        val retained = mutableListOf<Pair<BufferedGridTile, DoubleArray>>()
        for (sigma in listOf(14.0, 7.0, 1.5, 4.0, .8, 2.5, 14.0)) {
            for (buffer in listOf(11, 0, 3)) {
                val expected = GaussianBlur.blur(field, buffer, sigma)
                val actual = GaussianBlur.blur(field, buffer, sigma, workspace)
                assertThat(actual.data).containsExactly(*expected.data)
                retained += actual to actual.data.copyOf()
            }
        }
        for ((grid, before) in retained) assertThat(grid.data).containsExactly(*before)
    }

    @Test
    fun `zero footprint optimization still includes relief in the neighbour halo`() {
        val ocean = GaussianBlur.blur(tile { _, _ -> 0.0 }, 2, 7.0, GaussianBlur.Workspace())
        assertThat(ocean.data).containsOnly(0.0)
        val coast = GaussianBlur.blur(tile { x, _ -> if (x >= 16) 200.0 else 0.0 }, 2, 7.0, GaussianBlur.Workspace())
        assertThat(coast.get(15, 8).meters).isGreaterThan(0.0)
        val missing = GaussianBlur.blur(tile { _, _ -> Double.NEGATIVE_INFINITY }, 2, 7.0, GaussianBlur.Workspace())
        assertThat(missing.data.all { it.isNaN() }).isTrue()
    }

    @Test
    fun `constant grid is unchanged`() {
        val blurred = GaussianBlur.blur(tile { _, _ -> 123.0 }, 2, 1.5)
        for (x in -2..17) for (y in -2..17) {
            assertThat(blurred.get(x, y).meters).isCloseTo(123.0, within(1e-9))
        }
    }

    @Test
    fun `a step is blurred symmetrically`() {
        // step between x=7 and x=8
        val blurred = GaussianBlur.blur(tile { x, _ -> if (x >= 8) 100.0 else 0.0 }, 0, 1.0)
        for (d in 0..3) {
            val left = blurred.get(7 - d, 5).meters
            val right = blurred.get(8 + d, 5).meters
            assertThat(left + right).isCloseTo(100.0, within(1e-9))
        }
        assertThat(blurred.get(7, 5).meters).isBetween(1.0, 50.0)
    }

    @Test
    fun `box approximation keeps constants and is close to the exact Gaussian`() {
        val constant = GaussianBlur.blur(tile { _, _ -> 55.0 }, 3, 6.0)
        for (x in -3..18) for (y in -3..18) {
            assertThat(constant.get(x, y).meters).isCloseTo(55.0, within(1e-9))
        }
        val field = { x: Int, y: Int -> 100 * Math.sin(x * 0.3) + 80 * Math.cos(y * 0.21) + 30 * Math.sin((x + y) * 0.9) }
        val sigma = 2.5
        val box = GaussianBlur.blur(tile(field), 2, sigma)
        val exact = object : ElevationTile() {
            // reference: direct 2D Gaussian over a wide window
            override val empty = false
            override val extent = 16
            override fun get(x: Int, y: Int): Elevation {
                var sum = 0.0
                var w = 0.0
                for (dx in -12..12) for (dy in -12..12) {
                    val k = Math.exp(-(dx * dx + dy * dy) / (2 * sigma * sigma))
                    sum += k * field(x + dx, y + dy)
                    w += k
                }
                return Elevation(sum / w)
            }
        }
        for (x in -2..17) for (y in -2..17) {
            assertThat(box.get(x, y).meters).isCloseTo(exact.get(x, y).meters, within(3.0))
        }
        assertThat(GaussianBlur.boxRadii(14.0).sum()).isBetween(38, 44)
    }

    @Test
    fun `invalid samples are ignored`() {
        val t = object : ElevationTile() {
            override val empty = false
            override val extent = 8
            override fun get(x: Int, y: Int) = if (x == 3) INVALID_ELEVATION else Elevation(10.0)
        }
        val blurred = GaussianBlur.blur(t, 0, 1.0)
        assertThat(blurred.get(3, 3).valid).isFalse()
        assertThat(blurred.get(4, 3).meters).isCloseTo(10.0, within(1e-9))
    }
}

class BoxClipperTest {
    private val clipper = BoxClipper(-80.0, 4176.0)

    @Test
    fun `clips at the box and splits re-entering lines`() {
        val line = Line(
            listOf(
                DoublePoint(-200.0, 100.0), DoublePoint(200.0, 100.0), DoublePoint(200.0, 5000.0),
                DoublePoint(300.0, 5000.0), DoublePoint(300.0, 200.0)
            )
        )
        val parts = clipper.clip(line)
        assertThat(parts).hasSize(2)
        assertThat(parts[0].points).containsExactly(DoublePoint(-80.0, 100.0), DoublePoint(200.0, 100.0), DoublePoint(200.0, 4176.0))
        assertThat(parts[1].points).containsExactly(DoublePoint(300.0, 4176.0), DoublePoint(300.0, 200.0))
    }

    @Test
    fun `lines inside are unchanged and outside are dropped`() {
        val inside = Line(listOf(DoublePoint(0.0, 0.0), DoublePoint(10.0, 10.0)))
        assertThat(clipper.clip(inside).single().points).isEqualTo(inside.points)
        assertThat(clipper.clip(Line(listOf(DoublePoint(-100.0, 0.0), DoublePoint(-100.0, 10.0))))).isEmpty()
    }
}

class RbtVectorTileTest {
    private fun render(tile: TileId, extent: Int = 512): VectorTile.Tile {
        val output = mutableMapOf<TileId, ByteArray>()
        val profile = RbtProfile()
        VectorTileSink(
            contourOptionsProvider = { profile.contourOptions(it.id) },
            repository = CapturingRepository(output),
            elevationDataStore = SyntheticTerrain(extent),
            metricsProvider = SingletonMetricsProvider()
        ).accept(Tile(tile))
        return VectorTile.Tile.parseFrom(output.getValue(tile))
    }

    private fun ele(f: TileFeature) = (f.properties["ele"] as Number).toInt()

    @Test
    fun `emits exactly ele, index and is_ice as integers`() {
        val tile = render(TileId(12, 1930, 1286))
        val layer = tile.layersList.single()
        assertThat(layer.name).isEqualTo("contours")
        assertThat(layer.extent).isEqualTo(4096)
        assertThat(layer.keysList).containsExactly("ele", "index", "is_ice")
        assertThat(layer.valuesList).allSatisfy { assertThat(it.hasIntValue() || it.hasSintValue()).isTrue() }
        val model = TileModelReader().read(tile).layers.single()
        assertThat(model.features).isNotEmpty()
        for (f in model.features) {
            assertThat(ele(f) % 20 == 0 || ele(f) % 50 == 0).isTrue()
            assertThat((f.properties["index"] as Number).toInt()).isEqualTo(rbtIndex(ele(f)))
            assertThat((f.properties["is_ice"] as Number).toInt()).isEqualTo(0)
            for (line in f.lines) for (p in line.points) {
                assertThat(p.x).isBetween(-80, 4176)
                assertThat(p.y).isBetween(-80, 4176)
            }
        }
    }

    @Test
    fun `lines continue across tile borders`() {
        val left = TileModelReader().read(render(TileId(12, 1929, 1286))).layers.single()
        val right = TileModelReader().read(render(TileId(12, 1930, 1286))).layers.single()
        fun segments(features: List<TileFeature>, level: Int, dx: Int) =
            features.filter { ele(it) == level }.flatMap { f ->
                f.lines.flatMap { l ->
                    l.points.zipWithNext().map { (a, b) ->
                        doubleArrayOf(a.x + dx.toDouble(), a.y.toDouble(), b.x + dx.toDouble(), b.y.toDouble())
                    }
                }
            }

        fun distance(px: Double, py: Double, s: DoubleArray): Double {
            val vx = s[2] - s[0]
            val vy = s[3] - s[1]
            val len2 = vx * vx + vy * vy
            val t = if (len2 == 0.0) 0.0 else (((px - s[0]) * vx + (py - s[1]) * vy) / len2).coerceIn(0.0, 1.0)
            return hypot(px - (s[0] + t * vx), py - (s[1] + t * vy))
        }
        var checked = 0
        for (level in left.features.map { ele(it) }.toSet()) {
            val rightSegments = segments(right.features, level, 4096)
            for (s in segments(left.features, level, 0)) {
                // points sampled along the line in the overlap, away from the clip edge of the right tile
                for (i in 0..20) {
                    val px = s[0] + (s[2] - s[0]) * i / 20
                    val py = s[1] + (s[3] - s[1]) * i / 20
                    if (px in 4030.0..4140.0 && abs(py - 2048) < 2000) {
                        val d = rightSegments.minOfOrNull { distance(px, py, it) } ?: Double.MAX_VALUE
                        assertThat(d).describedAs("ele $level point $px,$py").isLessThan(4.0)
                        checked++
                    }
                }
            }
        }
        assertThat(checked).isGreaterThan(10)
    }
}
