package com.greensopinion.vectorvector.elevation.mapterhorn

import com.greensopinion.vectorvector.WebMercator
import com.greensopinion.vectorvector.metrics.SingletonMetricsProvider
import com.greensopinion.vectorvector.pmtiles.FileRangeSource
import com.greensopinion.vectorvector.pmtiles.PmtilesReader
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.floor

/** Runs against data/iceland (see data/iceland/SOURCES.md); skipped when the data is absent. */
class MapterhornIcelandDataTest {
    private val file = File("data/iceland/mapterhorn-iceland-z0-12.pmtiles")

    private fun elevationAt(store: MapterhornElevationDataStore, lon: Double, lat: Double, z: Int): Double {
        val tile = WebMercator.tileAt(lon, lat, z)
        val px = floor((WebMercator.lonToTileX(lon, z) - tile.x) * 512).toInt()
        val py = floor((WebMercator.latToTileY(lat, z) - tile.y) * 512).toInt()
        return store.get(tile).get(px, py).meters
    }

    @Test
    fun `known elevations in Iceland`() {
        assumeTrue(file.exists(), "no Iceland data")
        MapterhornElevationDataStore(
            MapterhornSourceSet(listOf(PmtilesReader(FileRangeSource(file)))), 512, SingletonMetricsProvider()
        ).use { store ->
            // summit 64.0147 N, 16.6767 W (PLAN.md had 63.999 N, ~1.7 km south)
            val peak = (-5..5).flatMap { dx -> (-5..5).map { dy -> elevationAt(store, -16.6767 + dx * 0.0004, 64.0147 + dy * 0.0002, 12) } }.max()
            println("Hvannadalshnukur: $peak")
            assertThat(peak).isBetween(2050.0, 2130.0)
            val harbour = elevationAt(store, -21.9386, 64.1530, 12)
            println("Reykjavik harbour: $harbour")
            assertThat(harbour).isBetween(-5.0, 10.0)
            val ocean = elevationAt(store, -20.0, 63.25, 12)
            println("Ocean south of Iceland: $ocean")
        }
    }
}
