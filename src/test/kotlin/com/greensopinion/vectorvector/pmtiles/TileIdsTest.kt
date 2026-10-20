package com.greensopinion.vectorvector.pmtiles

import com.greensopinion.vectorvector.TileId
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import kotlin.random.Random

class TileIdsTest {
    @Test
    fun `reference vectors`() {
        assertThat(TileIds.zxyToTileId(0, 0, 0)).isEqualTo(0)
        assertThat(TileIds.zxyToTileId(1, 0, 0)).isEqualTo(1)
        assertThat(TileIds.zxyToTileId(1, 0, 1)).isEqualTo(2)
        assertThat(TileIds.zxyToTileId(1, 1, 1)).isEqualTo(3)
        assertThat(TileIds.zxyToTileId(1, 1, 0)).isEqualTo(4)
        assertThat(TileIds.zxyToTileId(2, 0, 0)).isEqualTo(5)
        // from the pmtiles js test suite
        assertThat(TileIds.zxyToTileId(20, 0, 0)).isEqualTo(366503875925L)
    }

    @Test
    fun `round trips`() {
        val random = Random(42)
        for (z in listOf(0, 1, 6, 12, 14, 20)) {
            repeat(200) {
                val n = 1 shl z
                val tile = TileId(z, random.nextInt(n), random.nextInt(n))
                assertThat(TileIds.tileIdToZxy(TileIds.zxyToTileId(tile.z, tile.x, tile.y))).isEqualTo(tile)
            }
        }
    }
}
