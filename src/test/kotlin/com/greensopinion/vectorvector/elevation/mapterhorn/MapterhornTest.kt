package com.greensopinion.vectorvector.elevation.mapterhorn

import com.greensopinion.vectorvector.TileId
import com.greensopinion.vectorvector.WebMercator
import com.greensopinion.vectorvector.metrics.SingletonMetricsProvider
import com.greensopinion.vectorvector.pmtiles.FileRangeSource
import com.greensopinion.vectorvector.pmtiles.PmtilesReader
import com.greensopinion.vectorvector.pmtiles.PmtilesTileRef
import com.greensopinion.vectorvector.pmtiles.fixtureFile
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

class TerrariumDecoderTest {
    @Test
    fun `native WebP matches Java elevation bits for fixture and transparent encoded pixels`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(dev.matrixlab.webp4j.WebPCodec.isAvailable())
        val tile = WebMercator.tileAt(-16.677, 63.999, 10)
        val fixture = PmtilesReader(FileRangeSource(fixtureFile)).use { it.getTile(tile.z, tile.x, tile.y)!! }
        val image = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 16) for (x in 0 until 16) {
            val alpha = when ((x + y) % 3) { 0 -> 0; 1 -> 128; else -> 255 }
            image.setRGB(x, y, (alpha shl 24) or (128 shl 16) or (x shl 8) or y)
        }
        val encoded = dev.matrixlab.webp4j.WebPCodec.encodeLosslessImage(image)
        for (bytes in listOf(fixture, encoded)) {
            val java = TerrariumDecoder.decode(javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes)))
            val native = TerrariumDecoder.decode(dev.matrixlab.webp4j.WebPCodec.decodeImage(bytes))
            assertThat(native.data.map { it.toBits() }).containsExactlyElementsOf(java.data.map { it.toBits() })
        }
    }
    @Test
    fun `memory input retains the same decoded float bits as standard ImageIO input`() {
        val tile = WebMercator.tileAt(-16.677, 63.999, 10)
        val webp = PmtilesReader(FileRangeSource(fixtureFile)).use { it.getTile(tile.z, tile.x, tile.y)!! }
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        repeat(8) { x -> repeat(8) { y -> image.setRGB(x, y, if (x == y) 0 else 0xff880120.toInt()) } }
        val png = java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(image, "png", it) }.toByteArray()
        for (bytes in listOf(webp, png)) {
            val baseline = TerrariumDecoder.decode(javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes)))
            val actual = TerrariumDecoder.decode(bytes)
            assertThat(actual.data.map { it.toRawBits() }).containsExactlyElementsOf(baseline.data.map { it.toRawBits() })
        }
    }
    @Test
    fun `decodes pixels`() {
        assertThat(TerrariumDecoder.decodePixel(128, 0, 0)).isEqualTo(0f)
        assertThat(TerrariumDecoder.decodePixel(136, 62, 0)).isEqualTo(2110f)
        assertThat(TerrariumDecoder.decodePixel(127, 255, 128)).isEqualTo(-0.5f)
    }

    @Test
    fun `transparent pixels have no data`() {
        val image = BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(0, 0, (0xFF shl 24) or (136 shl 16) or (62 shl 8))
        image.setRGB(1, 0, (0xFF shl 24) or (128 shl 16))
        val grid = TerrariumDecoder.decode(image)
        assertThat(grid[0, 0]).isEqualTo(2110f)
        assertThat(grid[1, 0]).isEqualTo(0f)
        assertThat(grid[0, 1]).isNaN()
    }

    @Test
    fun `decodes the webp fixture`() {
        val tile = WebMercator.tileAt(-16.677, 63.999, 10)
        val bytes = PmtilesReader(FileRangeSource(fixtureFile)).use { it.getTile(tile.z, tile.x, tile.y)!! }
        val grid = TerrariumDecoder.decode(bytes)
        assertThat(grid.size).isEqualTo(512)
        assertThat(grid[0, 0]).isBetween(-100f, 2200f)
        val max = grid.data.max()
        // Hvannadalshnukur is 2110 m; z10 pixels are ~130 m wide so the peak is smoothed a little
        assertThat(max).isBetween(1950f, 2130f)
    }
}

class ElevationGridTest {
    private fun ramp(size: Int) = ElevationGrid(size, FloatArray(size * size) { (it % size).toFloat() })

    @Test
    fun `resample retains exact float results including clamped edges and missing pixels`() {
        val random = kotlin.random.Random(81)
        val grid = ElevationGrid(64, FloatArray(64 * 64) { if (it % 19 == 0) Float.NaN else random.nextFloat() * 9000 - 500 })
        for ((x0, y0, region, target) in listOf(
            listOf(0, 0, 64, 64), listOf(0, 0, 32, 64), listOf(32, 32, 32, 64),
            listOf(16, 0, 16, 128), listOf(63, 63, 1, 512), listOf(0, 0, 64, 7)
        )) {
            val expected = FloatArray(target * target)
            val scale = region.toDouble() / target
            for (ty in 0 until target) {
                val sy = y0 + (ty + .5) * scale - .5
                val iy = kotlin.math.floor(sy).toInt()
                val fy = (sy - iy).toFloat()
                val y1 = iy.coerceIn(0, 63)
                val y2 = (iy + 1).coerceIn(0, 63)
                for (tx in 0 until target) {
                    val sx = x0 + (tx + .5) * scale - .5
                    val ix = kotlin.math.floor(sx).toInt()
                    val fx = (sx - ix).toFloat()
                    val x1 = ix.coerceIn(0, 63)
                    val x2 = (ix + 1).coerceIn(0, 63)
                    val top = grid[x1, y1] * (1 - fx) + grid[x2, y1] * fx
                    val bottom = grid[x1, y2] * (1 - fx) + grid[x2, y2] * fx
                    expected[ty * target + tx] = top * (1 - fy) + bottom * fy
                }
            }
            assertThat(grid.resample(x0, y0, region, target).data.map { it.toRawBits() })
                .containsExactlyElementsOf(expected.map { it.toRawBits() })
        }
    }

    @Test
    fun `crops exact pixels`() {
        val grid = ElevationGrid(4, FloatArray(16) { it.toFloat() })
        val q = grid.crop(2, 2, 2)
        assertThat(q.data).containsExactly(10f, 11f, 14f, 15f)
    }

    @Test
    fun `upsamples a 2x2 ramp bilinearly`() {
        val grid = ramp(2) // values 0,1 / 0,1
        val up = grid.resample(0, 0, 2, 4)
        // cell centred: target x 0..3 -> source -0.25, 0.25, 0.75, 1.25 (clamped)
        assertThat(up[0, 0]).isEqualTo(0f)
        assertThat(up[1, 0]).isCloseTo(0.25f, within(1e-6f))
        assertThat(up[2, 0]).isCloseTo(0.75f, within(1e-6f))
        assertThat(up[3, 3]).isEqualTo(1f)
    }

    @Test
    fun `upsampling a quadrant uses neighbouring pixels`() {
        val grid = ramp(8)
        val up = grid.resample(4, 0, 4, 8)
        // first target pixel samples source x = 4 + 0.25 - 0.5 = 3.75, outside the region but inside the grid
        assertThat(up[0, 0]).isCloseTo(3.75f, within(1e-5f))
        assertThat(up[7, 0]).isEqualTo(7f) // clamped at the grid edge
    }
}

class MapterhornElevationDataStoreTest {
    private val tile = WebMercator.tileAt(-16.677, 63.999, 10)

    @Test
    fun `different coordinates sharing a payload decode once and archives stay distinct`() {
        val first = org.mockito.Mockito.mock(PmtilesReader::class.java)
        val second = org.mockito.Mockito.mock(PmtilesReader::class.java)
        val header = PmtilesReader(FileRangeSource(fixtureFile)).use { it.header }
            .copy(minLon = -180.0, minLat = -85.0, maxLon = 180.0, maxLat = 85.0)
        val ref = PmtilesTileRef(100, 200)
        val bytes = PmtilesReader(FileRangeSource(fixtureFile)).use { it.getTile(tile.z, tile.x, tile.y)!! }
        for (reader in listOf(first, second)) {
            org.mockito.Mockito.`when`(reader.header).thenReturn(header)
            org.mockito.Mockito.`when`(reader.readTile(ref)).thenReturn(bytes)
        }
        org.mockito.Mockito.`when`(first.locateTile(10, 1, 1)).thenReturn(ref)
        org.mockito.Mockito.`when`(first.locateTile(10, 2, 1)).thenReturn(ref)
        org.mockito.Mockito.`when`(second.locateTile(10, 3, 1)).thenReturn(ref)
        MapterhornSourceSet(listOf(first, second)).use { sources ->
            val a = sources.decoded(10, 1, 1)
            assertThat(sources.decoded(10, 2, 1)).isSameAs(a)
            assertThat(sources.decoded(10, 3, 1)).isNotSameAs(a)
            org.mockito.Mockito.verify(first, org.mockito.Mockito.times(1)).readTile(ref)
            org.mockito.Mockito.verify(second, org.mockito.Mockito.times(1)).readTile(ref)
        }
    }

    private fun store(extent: Int) = MapterhornElevationDataStore(
        MapterhornSourceSet(listOf(PmtilesReader(FileRangeSource(fixtureFile)))), extent, SingletonMetricsProvider()
    )

    @Test
    fun `native 512 tiles`() {
        store(512).use { store ->
            val t = store.get(tile)
            assertThat(t.extent).isEqualTo(512)
            assertThat(t.empty).isFalse()
        }
    }

    @Test
    fun `dateline neighbours wrap longitude while polar rows remain invalid`() {
        val sources = org.mockito.Mockito.mock(MapterhornSourceSet::class.java)
        val west = ElevationGrid(512, FloatArray(512 * 512) { 100f + it % 512 })
        val east = ElevationGrid(512, FloatArray(512 * 512) { 1000f + it % 512 })
        org.mockito.Mockito.`when`(sources.decoded(2, 0, 1)).thenReturn(west)
        org.mockito.Mockito.`when`(sources.decoded(2, 3, 1)).thenReturn(east)
        MapterhornElevationDataStore(sources, 512, SingletonMetricsProvider()).use { store ->
            val left = TileId(2, 0, 1)
            val right = TileId(2, 3, 1)
            val leftArea = com.greensopinion.vectorvector.ElevationTileArea(left, store.get(left), store)
            val rightArea = com.greensopinion.vectorvector.ElevationTileArea(right, store.get(right), store)
            assertThat(leftArea.get(-1, 20).meters).isEqualTo(1511.0)
            assertThat(rightArea.get(512, 20).meters).isEqualTo(100.0)
            assertThat(store.get(TileId(2, -1, 1))).isSameAs(store.get(right))
            assertThat(store.get(TileId(2, 7, 1))).isSameAs(store.get(right))
            assertThat(store.get(TileId(2, 0, -1)).empty).isTrue()
            assertThat(store.get(TileId(2, 0, 4)).empty).isTrue()
        }
    }

    @Test
    fun `256 quadrants are exact native pixels`() {
        store(256).use { store ->
            val z11 = TileId(11, tile.x * 2 + 1, tile.y * 2 + 1)
            val quadrant = store.get(z11)
            val native = store.grid(tile)!!
            assertThat(quadrant.extent).isEqualTo(256)
            assertThat(quadrant.get(0, 0).meters).isEqualTo(native[256, 256].toDouble())
            assertThat(quadrant.get(255, 10).meters).isEqualTo(native[511, 266].toDouble())
        }
    }

    @Test
    fun `missing tiles overzoom from ancestors`() {
        store(512).use { store ->
            val child = TileId(12, tile.x * 4 + 3, tile.y * 4)
            val t = store.get(child)
            assertThat(t.empty).isFalse()
            val native = store.grid(tile)!!
            // the centre of child pixel (256,256) maps to native (3*128 + 64 - 0.5 + 0.25, 64 - 0.25)
            assertThat(t.get(256, 256).meters).isBetween(
                minOf(native[447, 63], native[448, 64]).toDouble() - 1, maxOf(native[447, 63], native[448, 64]).toDouble() + 1
            )
            assertThat(store.get(TileId(10, 0, 0)).empty).isTrue()
            assertThat(store.get(TileId(10, -1, 0)).empty).isTrue()
        }
    }
}
