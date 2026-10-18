package com.greensopinion.vectorvector.osm

import com.greensopinion.vectorvector.TileId
import com.greensopinion.vectorvector.WebMercator
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import java.nio.file.Path
import java.util.Properties

class PreparedSpatialStoreTest {
    @TempDir lateinit var directory: Path
    private val factory = GeometryFactory()
    private fun rectangle(left: Double, right: Double) = factory.createPolygon(arrayOf(
        Coordinate(left, -0.5), Coordinate(right, -0.5), Coordinate(right, 0.0),
        Coordinate(left, 0.0), Coordinate(left, -0.5)))
    private fun peakIndex(peaks: List<Peak>) = PeakIndex(peaks, 6, 13,
        demElevation = { lon, lat -> lon + lat + 2000 })

    @Test fun `prepared masks and peak DEM ranking preserve wrapped tile output`() {
        val root = TileId(6, 0, 32)
        val ice = GlacierIndex(listOf(Glacier("east", rectangle(179.9, 180.0)),
            Glacier("west", rectangle(-180.0, -179.8))))
        val peaks = listOf(Peak(1, 179.999, -0.1, "wrapped", null, null, null, "peak"),
            Peak(2, -179.999, -0.1, null, "west", 400, "osm", "volcano"))
        val globalPeaks = peakIndex(peaks)
        val file = directory.resolve("x0-y32.bin").toFile()
        SpatialCodec.write(file, SpatialPartition(root, ice.lonLatForTile(root), peaks))
        val properties = Properties().also {
            it["version"] = "1"; it["complete"] = "true"; it["partitions"] = "4096"
            it["rootZoom"] = "6"; it["peaks"] = "true"; it["sha.${file.name}"] = spatialHash(file)
        }
        directory.resolve("manifest.properties").toFile().outputStream().use { properties.store(it, "test") }
        val prepared = PreparedSpatialStore(directory.toFile(), ::peakIndex, 64, 8L * 1048576)
        val loaded = SpatialCodec.read(file, root)
        val local = GlacierIndex(emptyList(), preparedRoot = root to loaded.mask)
        for (z in 6..13) {
            val tile = WebMercator.tileAt(-179.999, -0.1, z)
            assertThat(local.forTile(tile)!!.equalsTopo(ice.forTile(tile))).isTrue()
            assertThat(prepared.peaks(tile)!!.toByteArray()).isEqualTo(globalPeaks.layer(tile)!!.toByteArray())
        }
        file.appendBytes(byteArrayOf(1))
        val fresh = PreparedSpatialStore(directory.toFile(), ::peakIndex, 64, 8L * 1048576)
        assertThatThrownBy { fresh.peaks(WebMercator.tileAt(-179.999, -0.1, 13)) }.hasMessageContaining("corrupt")
    }

    @Test fun `codec rejects mismatched roots and truncated inputs`() {
        val file = directory.resolve("partition").toFile()
        SpatialCodec.write(file, SpatialPartition(TileId(6, 1, 2), null, emptyList()))
        assertThatThrownBy { SpatialCodec.read(file, TileId(6, 1, 3)) }.hasMessageContaining("mismatch")
        file.writeBytes(file.readBytes().take(8).toByteArray())
        assertThatThrownBy { SpatialCodec.read(file, TileId(6, 1, 2)) }.isInstanceOf(java.io.EOFException::class.java)
    }
}
