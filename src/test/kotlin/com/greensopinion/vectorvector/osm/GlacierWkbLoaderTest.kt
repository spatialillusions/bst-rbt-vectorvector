package com.greensopinion.vectorvector.osm

import com.greensopinion.vectorvector.TileId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.io.WKBWriter
import org.locationtech.jts.io.WKTReader
import java.io.File

class GlacierWkbLoaderTest {
    @TempDir lateinit var dir: File
    private val reader = WKTReader()

    private fun write(geometry: Geometry, name: String = "ice.wkb"): File = File(dir, name).also {
        it.writeBytes(WKBWriter(2, true).write(geometry))
    }

    @Test
    fun `loads polygon parts and preserves rock holes`() {
        val geometry = reader.read("MULTIPOLYGON (((-70 -85,-60 -85,-60 -80,-70 -80,-70 -85)," +
            "(-68 -84,-67 -84,-67 -83,-68 -83,-68 -84)),((10 10,11 10,11 11,10 11,10 10)))")
        geometry.srid = 4326
        val glaciers = GlacierWkbLoader(write(geometry)).load()
        assertThat(glaciers).hasSize(2)
        val factory = GeometryFactory()
        assertThat(glaciers[0].geometry.covers(factory.createPoint(Coordinate(-65.0, -84.5)))).isTrue()
        assertThat(glaciers[0].geometry.covers(factory.createPoint(Coordinate(-67.5, -83.5)))).isFalse()
    }

    @Test
    fun `rejects nonpolygons wrong CRS and invalid coordinates`() {
        assertThatThrownBy { GlacierWkbLoader(write(reader.read("POINT (1 2)"))).load() }
            .hasMessageContaining("Polygon")
        val projected = reader.read("POLYGON ((0 0,1 0,1 1,0 0))").also { it.srid = 3857 }
        assertThatThrownBy { GlacierWkbLoader(write(projected)).load() }.hasMessageContaining("EPSG:4326")
        val outside = reader.read("POLYGON ((181 0,182 0,182 1,181 0))")
        assertThatThrownBy { GlacierWkbLoader(write(outside)).load() }.hasMessageContaining("longitude/latitude")
    }

    @Test
    fun `repairs self crossings into valid polygon parts`() {
        val glaciers = GlacierWkbLoader(write(reader.read("POLYGON ((0 0,2 2,0 2,2 0,0 0))"))).load()
        assertThat(glaciers).hasSize(2)
        assertThat(glaciers.all { it.geometry.isValid }).isTrue()
        assertThat(glaciers.sumOf { it.geometry.area }).isEqualTo(2.0)
    }

    @Test
    fun `clips pole coverage before Mercator projection`() {
        val glaciers = GlacierWkbLoader(write(reader.read("POLYGON ((-180 -90,180 -90,180 -60,-180 -60,-180 -90))"))).load()
        val geometry = GlacierIndex(glaciers).forTile(TileId(13, 4000, 8191))!!
        assertThat(geometry.coordinates.all { it.x.isFinite() && it.y.isFinite() }).isTrue()
        assertThat(geometry.covers(GeometryFactory().createPoint(Coordinate(2048.0, 2048.0)))).isTrue()
        assertThat(glaciers.single().geometry.envelopeInternal.minY).isEqualTo(-90.0)
    }
}
