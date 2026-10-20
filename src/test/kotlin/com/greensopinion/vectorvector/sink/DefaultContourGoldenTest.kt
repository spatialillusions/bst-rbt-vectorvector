package com.greensopinion.vectorvector.sink

import com.greensopinion.vectorvector.Elevation
import com.greensopinion.vectorvector.ElevationTile
import com.greensopinion.vectorvector.Tile
import com.greensopinion.vectorvector.TileId
import com.greensopinion.vectorvector.elevation.ElevationDataStore
import com.greensopinion.vectorvector.metrics.SingletonMetricsProvider
import com.greensopinion.vectorvector.repository.TileRepository
import com.greensopinion.vectorvector.profile.DefaultProfile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Guards the default pipeline on fixed smooth terrain against unrelated changes. Saddle cells are covered separately.
 * The expected hashes were recorded on the unmodified upstream code.
 */
class DefaultContourGoldenTest {
    private val tiles = listOf(
        TileId(8, 120, 80), TileId(10, 482, 321), TileId(11, 965, 643), TileId(12, 1930, 1286)
    )

    @Test
    fun `default contour output is unchanged`() {
        val output = mutableMapOf<TileId, ByteArray>()
        val profile = DefaultProfile(epsilon = 3)
        val sink = VectorTileSink(
            contourOptionsProvider = { tile -> profile.contourOptions(tile.id) },
            repository = CapturingRepository(output),
            elevationDataStore = SyntheticTerrain(),
            metricsProvider = SingletonMetricsProvider()
        )
        tiles.forEach { sink.accept(Tile(it)) }
        val hashes = tiles.associate { "${it.z}/${it.x}/${it.y}" to sha256(output[it]!!) }
        println(hashes)
        assertThat(hashes).isEqualTo(EXPECTED)
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)

    companion object {
        val EXPECTED = mapOf(
            "8/120/80" to "d9bf0a3fb25c8fae",
            "10/482/321" to "7227ae86a8d03795",
            "11/965/643" to "4bb8088dfef514b3",
            "12/1930/1286" to "390a01ebde7c91f2"
        )
    }
}

class CapturingRepository(private val output: MutableMap<TileId, ByteArray>) : TileRepository {
    override fun store(tile: TileId, extension: String, bytes: ByteArray) {
        output[tile] = bytes
    }

    override fun close() {}
}

/** A deterministic, smooth global terrain in metres, sampled at 256 px per tile. */
class SyntheticTerrain(private val extent: Int = 256) : ElevationDataStore {
    override fun get(tile: TileId): ElevationTile = object : ElevationTile() {
        override val empty = false
        override val extent = this@SyntheticTerrain.extent
        override fun get(x: Int, y: Int): Elevation {
            val scale = (1 shl tile.z).toDouble() * extent
            val u = (tile.x * extent + x) / scale
            val v = (tile.y * extent + y) / scale
            return Elevation(
                800 + 700 * sin(u * 2 * PI * 40) * cos(v * 2 * PI * 30) + 60 * sin(u * 2 * PI * 900 + v * 2 * PI * 500)
            )
        }
    }
}
