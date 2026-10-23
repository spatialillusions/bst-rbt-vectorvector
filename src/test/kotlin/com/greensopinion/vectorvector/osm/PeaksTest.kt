package com.greensopinion.vectorvector.osm

import com.greensopinion.vectorvector.TileId
import com.greensopinion.vectorvector.WebMercator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

class EleParserTest {
    @Test
    fun `parses common ele formats`() {
        val cases = mapOf(
            "2110" to 2110, "2110 m" to 2110, "2110m" to 2110, "2,110" to 2110, "2110.4" to 2110, "2110,6" to 2111,
            "6923 ft" to 2110, "6923'" to 2110, " 1 234 " to 1, "-12" to -12, "ca. 850" to 850,
            "" to null, "unknown" to null, "99999" to null
        )
        cases.forEach { (text, expected) -> assertThat(EleParser.parse(text)).describedAs(text).isEqualTo(expected) }
        assertThat(EleParser.parse(null)).isNull()
    }
}

class PeakIndexTest {
    private val tile = TileId(12, 1858, 1092)
    private val centreLon = WebMercator.tileXToLon(tile.x + 0.5, 12)
    private val centreLat = WebMercator.tileYToLat(tile.y + 0.5, 12)

    private fun peak(id: Long, name: String?, ele: Int?, dx: Double = 0.0) =
        Peak(id, centreLon + dx * 0.001, centreLat, name, null, ele, ele?.let { "osm" }, "peak")

    @Test
    fun `dateline buffers include opposite-world peaks without shifting DEM lookup`() {
        val latitude = WebMercator.tileYToLat(4096.5, 13)
        val lookups = mutableListOf<Double>()
        val index = PeakIndex(listOf(
            Peak(1, 179.9998, latitude, "East", null, null, null, "peak"),
            Peak(2, -179.9998, latitude, "West", null, 100, "osm", "peak")
        ), 9, 13, demElevation = { lon, _ -> lookups += lon; 200.0 })
        val west = index.ranked(TileId(13, 0, 4096))
        val east = index.ranked(TileId(13, 8191, 4096))
        assertThat(west.map { it.first.osmId }).containsExactly(1L, 2L)
        assertThat(east.map { it.first.osmId }).containsExactly(1L, 2L)
        assertThat(west.first().second.first).isBetween(-80, -1)
        assertThat(east.last().second.first).isBetween(4097, 4176)
        assertThat(lookups).containsExactly(179.9998)
        assertThat(west.first().first.lon).isEqualTo(179.9998)
    }

    @Test
    fun `DEM elevations are lazy and cached before ranking`() {
        var calls = 0
        val index = PeakIndex(listOf(peak(1, "DEM", null), peak(2, "OSM", 500)), 9, 13,
            demElevation = { _, _ -> calls++; 1000.0 })
        assertThat(calls).isZero()
        assertThat(index.layer(TileId(9, 0, 0))).isNull()
        assertThat(calls).isZero()
        assertThat(index.ranked(tile).map { it.first.osmId }).containsExactly(1L, 2L)
        index.ranked(TileId(11, tile.x / 2, tile.y / 2))
        assertThat(calls).isEqualTo(1)
    }

    @Test
    fun `ranks named peaks first, then by elevation`() {
        val index = PeakIndex(
            listOf(peak(1, null, 3000), peak(2, "Low", 500, 1.0), peak(3, "High", 2000, 2.0), peak(4, "NoEle", null, 3.0)),
            minZoom = 9, maxZoom = 13, demElevation = { _, _ -> 1000.0 }
        )
        val ranked = index.ranked(tile).map { it.first.osmId }
        assertThat(ranked).containsExactly(3L, 4L, 2L, 1L)
        val noEle = index.ranked(tile).first { it.first.osmId == 4L }.first
        assertThat(noEle.ele).isEqualTo(1000)
        assertThat(noEle.eleSource).isEqualTo("dem")
    }

    @Test
    fun `limits peaks per tile at low zoom only`() {
        val peaks = (1L..80L).map { peak(it, "p$it", it.toInt(), it * 0.0001) }
        val index = PeakIndex(peaks, minZoom = 9, maxZoom = 13, demElevation = { _, _ -> null })
        assertThat(index.ranked(TileId(10, tile.x / 4, tile.y / 4)).map { it.first.osmId }).containsExactly(80L)
        assertThat(index.ranked(tile)).hasSize(80)
    }

    @Test
    fun `zoom budgets retain spaced peaks and are independent of input order`() {
        for ((zoom, budget) in listOf(9 to 4, 10 to 8, 11 to 16)) {
            val target = TileId(zoom, 100, 100)
            val peaks = (0 until 16).map { i ->
                Peak(i.toLong(), WebMercator.tileXToLon(100 + (i % 4 + 0.5) / 4, zoom),
                    WebMercator.tileYToLat(100 + (i / 4 + 0.5) / 4, zoom),
                    "Peak", null, 1000, "osm", "peak")
            }
            fun selected(records: List<Peak>) = PeakIndex(records, 9, 13, { _, _ -> null })
                .ranked(target).map { it.first.osmId }
            assertThat(selected(peaks)).hasSize(budget)
            assertThat(selected(peaks.reversed())).isEqualTo(selected(peaks))
        }
    }

    @Test
    fun `overview thinning prefers named peaks even over higher unnamed neighbours`() {
        val index = PeakIndex(listOf(peak(1, null, 3000), peak(2, "Named", 500)),
            9, 13, { _, _ -> null })
        val overview = TileId(9, tile.x / 8, tile.y / 8)
        assertThat(index.ranked(overview).map { it.first.osmId }).containsExactly(2L)
    }

    @Test
    fun `encodes points with attributes`() {
        val index = PeakIndex(listOf(peak(7, "Hvannadalshnukur", 2110)), minZoom = 9, maxZoom = 13, demElevation = { _, _ -> null })
        val layer = index.layer(tile)!!
        assertThat(index.layer(TileId(8, 0, 0))).isNull()
        assertThat(layer.name).isEqualTo("peaks")
        val feature = layer.featuresList.single()
        assertThat(feature.type).isEqualTo(vector_tile.VectorTile.Tile.GeomType.POINT)
        assertThat(feature.geometryList).containsExactly(9, 4096, 4096) // MoveTo(1), zigzag(2048), zigzag(2048)
        val props = feature.tagsList.chunked(2).associate { (k, v) ->
            val value = layer.getValues(v)
            layer.getKeys(k) to (if (value.hasStringValue()) value.stringValue else value.intValue)
        }
        assertThat(props).isEqualTo(mapOf("name" to "Hvannadalshnukur", "ele" to 2110L, "ele_source" to "osm", "class" to "peak", "rank" to 1L))
    }
}

/** Runs against data/iceland; skipped when the data is absent. */
class PeakIcelandDataTest {
    @Test
    fun `loads Iceland peaks`() {
        val file = File("data/iceland/iceland-latest.osm.pbf")
        assumeTrue(file.exists(), "no Iceland data")
        val peaks = PeakLoader(file).load()
        println("peaks=${peaks.size} named=${peaks.count { it.name != null }} withEle=${peaks.count { it.ele != null }}")
        assertThat(peaks.size).isGreaterThan(500)
        val hvanna = peaks.filter { it.name?.startsWith("Hvannadalshn") == true }
        println(hvanna)
        assertThat(hvanna).isNotEmpty()
        assertThat(hvanna.first().ele).isBetween(2100, 2120)
        assertThat(peaks.any { it.name == "Snæfell" }).isTrue()
    }
}
