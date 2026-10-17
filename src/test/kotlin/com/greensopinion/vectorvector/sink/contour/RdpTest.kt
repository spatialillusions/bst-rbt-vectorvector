package com.greensopinion.vectorvector.sink.contour

import com.greensopinion.vectorvector.WebMercator
import com.greensopinion.vectorvector.elevation.mapterhorn.TerrariumDecoder
import com.greensopinion.vectorvector.pmtiles.FileRangeSource
import com.greensopinion.vectorvector.pmtiles.PmtilesReader
import com.greensopinion.vectorvector.pmtiles.fixtureFile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.random.Random

class RdpTest {
    @Test
    fun `matches LineSimplifier`() {
        val random = Random(3)
        repeat(300) {
            var x = 0.0
            var y = 0.0
            val points = List(2 + random.nextInt(400)) {
                x += random.nextDouble(-3.0, 8.0)
                y += random.nextDouble(-6.0, 6.0)
                DoublePoint(x, y)
            }
            val closed = if (random.nextBoolean()) points + points.first() else points
            for (epsilon in listOf(0.5, 2.0, 2.5, 6.0)) {
                val line = Line(closed)
                assertThat(Rdp.simplify(line, epsilon).points).isEqualTo(LineSimplifier(epsilon).simplify(line).points)
            }
        }
    }
}

class TerrariumFastDecodeTest {
    @Test
    fun `raster fast path equals getRGB decoding`() {
        val tile = WebMercator.tileAt(-16.677, 63.999, 10)
        val bytes = PmtilesReader(FileRangeSource(fixtureFile)).use { it.getTile(tile.z, tile.x, tile.y)!! }
        val image = ImageIO.read(ByteArrayInputStream(bytes))
        val fast = TerrariumDecoder.decode(image)
        val argb = image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
        for (i in argb.indices) {
            val p = argb[i]
            val expected = TerrariumDecoder.decodePixel((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)
            assertThat(fast.data[i]).isEqualTo(expected)
        }
    }
}
