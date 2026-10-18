package com.greensopinion.vectorvector

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TileBlockTest {
    @Test fun `spatial blocks preserve complete and sparse tile membership without duplicates`() {
        val bounds = LonLatBounds(-20.0, 40.0, 10.0, 65.0)
        for (clip in listOf(null, bounds)) {
            val legacy = TileRange(4, 8, 7, 8, 4, 6, clip)
            val expected = legacy.tiles().toSet()
            for (size in listOf(1, 3, 8, 32)) {
                val blocked = TileRange(4, 8, 7, 8, 4, 6, clip, blockSize = size).tiles().toList()
                assertThat(blocked.size.toLong()).isEqualTo(legacy.size)
                assertThat(blocked.toSet()).isEqualTo(expected)
                val selected = expected.filterIndexed { i, _ -> i % 13 == 0 }.toSet()
                val sparse = TileRange(4, 8, 7, 8, 4, 6, clip, selected, size).tiles().toList()
                assertThat(sparse.toSet()).isEqualTo(selected)
                assertThat(sparse.size).isEqualTo(selected.size)
            }
        }
    }
}
