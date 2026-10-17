package com.greensopinion.vectorvector.cli

import com.greensopinion.vectorvector.LonLatBounds
import com.greensopinion.vectorvector.TileId
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class CliOptionsTest {
    @TempDir lateinit var dir: File
    private val output = arrayOf("-o", "out", "-f", "mbtiles")

    private fun parse(vararg args: String) = parseCliOptions(arrayOf(*output, *args))

    @Test
    fun `additional ice masks require RBT and an existing input`() {
        val mask = File(dir, "ice.wkb").also { it.writeBytes(byteArrayOf(1)) }
        assertThatThrownBy { parse("--data", "tif", "--glacierWkb", mask.path) }
            .hasMessageContaining("--profile rbt")
        assertThat(parse("--data", "tif", "--profile", "rbt", "--glacierWkb", mask.path).glacierWkb)
            .isEqualTo(mask)
        assertThatThrownBy { parse("--data", "tif", "--profile", "rbt", "--glacierWkb", File(dir, "missing").path) }
            .hasMessageContaining("Missing glacier WKB")
    }

    @Test
    fun `planet options validate before opening outputs`() {
        assertThat(parse("--data", "tif", "--parallelism", "4", "--shardsX", "8", "--resumableShards").parallelism)
            .isEqualTo(4)
        assertThatThrownBy { parse("--data", "tif", "--parallelism", "0") }.hasMessageContaining("parallelism")
        assertThatThrownBy { parse("--data", "tif", "--shardsX", "0") }.hasMessageContaining("shardsX")
        assertThatThrownBy { parse("--data", "tif", "--shardWorkDir", "work") }.hasMessageContaining("resumableShards")
        assertThatThrownBy { parse("--dataFormat", "mapterhorn", "--pmtiles", "https://x/a.pmtiles", "--resumableShards") }
            .hasMessageContaining("local PMTiles")
        assertThatThrownBy { parse("--data", "tif", "--vectorName", "../outside") }.hasMessageContaining("vectorName")
    }

    @Test
    fun `upstream options keep their defaults`() {
        val options = parse("--data", "tif", "-a", "pnw")
        assertThat(options.dataFormat).isEqualTo(DataSourceFormat.aw3d30)
        assertThat(options.resolvedHillshadeRaster).isTrue()
        assertThat(options.vector).isTrue()
        assertThat(options.resolvedVectorName).isEqualTo("vector")
        assertThat(options.resolvedElevationExtent).isEqualTo(256)
        assertThat(options.profile).isEqualTo(ProfileName.default)
    }

    @Test
    fun `boolean options accept explicit values`() {
        val options = parse("--data", "tif", "--hillshadeRaster=false", "--vector=true", "--terrarium")
        assertThat(options.resolvedHillshadeRaster).isFalse()
        assertThat(options.vector).isTrue()
        assertThat(options.terrarium).isTrue()
        assertThat(parse("--data", "tif", "--hillshadeRaster").resolvedHillshadeRaster).isTrue()
    }

    @Test
    fun `data is required for GeoTIFF formats`() {
        assertThatThrownBy { parse("--dataFormat", "srtm") }.hasMessageContaining("--data is required")
        assertThatThrownBy { parse() }.hasMessageContaining("--data is required")
    }

    @Test
    fun `mapterhorn requires pmtiles and defaults to 512`() {
        assertThatThrownBy { parse("--dataFormat", "mapterhorn") }.hasMessageContaining("--pmtiles is required")
        val options = parse("--dataFormat", "mapterhorn", "--pmtiles", "a.pmtiles", "--pmtiles", "https://x/b.pmtiles")
        assertThat(options.pmtiles).containsExactly("a.pmtiles", "https://x/b.pmtiles")
        assertThat(options.resolvedElevationExtent).isEqualTo(512)
        assertThat(options.resolvedHillshadeRaster).isFalse()
    }

    @Test
    fun `512 extent rejects raster outputs`() {
        val base = arrayOf("--dataFormat", "mapterhorn", "--pmtiles", "a.pmtiles")
        assertThatThrownBy { parse(*base, "--terrarium") }.hasMessageContaining("--terrarium requires")
        assertThatThrownBy { parse(*base, "--hillshadeRaster") }.hasMessageContaining("--hillshadeRaster requires")
        assertThat(parse(*base, "--elevationExtent", "256", "--terrarium").terrarium).isTrue()
        assertThatThrownBy { parse(*base, "--elevationExtent", "300") }.hasMessageContaining("256 or 512")
        assertThatThrownBy { parse("--data", "tif", "--elevationExtent", "512") }.hasMessageContaining("must be 256")
    }

    @Test
    fun `rbt profile defaults`() {
        val options = parse("--dataFormat", "mapterhorn", "--pmtiles", "a.pmtiles", "--profile", "rbt", "--elevationExtent", "256")
        assertThat(options.resolvedHillshadeRaster).isFalse()
        assertThat(options.resolvedVectorName).isEqualTo("contours")
        assertThat(options.resolvedGzipTiles).isTrue()
        assertThat(parse("--data", "tif").resolvedGzipTiles).isFalse()
        assertThat(parse("--data", "tif", "--profile", "rbt", "--gzipTiles=false").resolvedGzipTiles).isFalse()
        assertThat(parse("--data", "tif", "--profile", "rbt", "--vectorName", "x").resolvedVectorName).isEqualTo("x")
    }

    @Test
    fun `peaks default to the glaciers file`() {
        val base = arrayOf("--dataFormat", "mapterhorn", "--pmtiles", "a.pmtiles", "--profile", "rbt")
        assertThat(parse(*base, "--glaciers", "g.pbf", "--peaks").resolvedPeaks).isEqualTo(File("g.pbf"))
        assertThat(parse(*base, "--glaciers", "g.pbf", "--peaks=p.pbf").resolvedPeaks).isEqualTo(File("p.pbf"))
        assertThat(parse(*base).resolvedPeaks).isNull()
        assertThatThrownBy { parse(*base, "--peaks") }.hasMessageContaining("requires --glaciers")
        assertThatThrownBy { parse("--data", "tif", "--glaciers", "g.pbf") }.hasMessageContaining("requires --profile rbt")
    }

    @Test
    fun `bbox is parsed and validated`() {
        val options = parse("--data", "tif", "-a", "iceland", "--bbox=-24.6,63.2,-13.3,66.7")
        assertThat(options.resolvedBbox).isEqualTo(LonLatBounds(-24.6, 63.2, -13.3, 66.7))
        assertThatThrownBy { parse("--data", "tif", "--bbox=1,2,3") }.hasMessageContaining("--bbox")
        assertThatThrownBy { parse("--data", "tif", "--bbox=3,2,1,4") }.hasMessageContaining("--bbox")
    }

    @Test
    fun `iceland area and bbox clip`() {
        val area = parse("--data", "tif", "-a", "iceland")
        assertThat(listOf(area.minZ, area.maxZ, area.minX, area.maxX, area.minY, area.maxY))
            .containsExactly(6, 13, 27, 29, 15, 17)
        val unclipped = area.toTileRange()
        val clipped = parse("--data", "tif", "-a", "iceland", "--bbox=-24.6,63.2,-13.3,66.7").toTileRange()
        assertThat(clipped.size).isLessThan(unclipped.size)
        val tiles = clipped.tiles().toSet()
        // Hvannadalshnukur at z12, and open ocean south of Iceland
        assertThat(tiles).contains(TileId(12, 1858, 1092))
        assertThat(tiles).doesNotContain(TileId(12, 1800, 1150))
    }

    @Test
    fun `bbox without area derives the tile range`() {
        val range = parse("--data", "tif", "-minZ", "9", "-maxZ", "9", "--bbox=-24.6,63.2,-13.3,66.7").toTileRange()
        assertThat(listOf(range.minX, range.maxX, range.minY, range.maxY)).containsExactly(221, 237, 127, 139)
    }
}
