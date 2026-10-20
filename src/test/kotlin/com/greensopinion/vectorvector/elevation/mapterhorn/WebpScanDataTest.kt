package com.greensopinion.vectorvector.elevation.mapterhorn

import com.greensopinion.vectorvector.WebMercator
import com.greensopinion.vectorvector.pmtiles.FileRangeSource
import com.greensopinion.vectorvector.pmtiles.PmtilesReader
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

class WebpScanDataTest {
    @Test
    fun scan() {
        val file = File("data/iceland/mapterhorn-iceland-z0-12.pmtiles")
        assumeTrue(System.getProperty("webpScan") != null && file.exists())
        val maxZ = System.getProperty("webpScan").toInt()
        PmtilesReader(FileRangeSource(file)).use { reader ->
            var ok = 0; var failed = 0
            for (z in 0..maxZ) {
                val a = WebMercator.tileAt(-24.8, 66.8, z); val b = WebMercator.tileAt(-13.2, 63.1, z)
                for (x in a.x..b.x) for (y in a.y..b.y) {
                    val bytes = reader.getTile(z, x, y) ?: continue
                    try { TerrariumDecoder.decode(bytes); ok++ } catch (e: Exception) {
                        failed++
                        if (failed <= 10) { println("FAIL $z/$x/$y ${bytes.size}B ${e.cause ?: e}"); File("target/tmp").mkdirs(); File("target/tmp/fail-$z-$x-$y.webp").writeBytes(bytes) }
                    }
                }
                println("z$z ok=$ok failed=$failed")
            }
        }
    }
}
