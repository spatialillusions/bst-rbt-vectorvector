package com.greensopinion.vectorvector.elevation.mapterhorn

import org.junit.jupiter.api.Test
import org.assertj.core.api.Assertions.assertThat
import java.util.Random

class MemoryElevationTest {
    @Test fun `child views preserve every float sample and bulk materialization`() {
        val random = Random(17)
        val source = ElevationGrid(512, FloatArray(512 * 512) {
            if (it % 37 == 0) Float.NaN else (random.nextDouble() * 10000 - 500).toFloat()
        })
        for (levels in 1..4) {
            val region = 512 shr levels
            for ((x, y) in listOf(0 to 0, 512 - region to 512 - region)) {
                val old = source.resample(x, y, region, 512)
                val view = ResampledElevationTile(source, x, y, region, 512)
                val bulk = DoubleArray(512 * 512)
                view.copySamples(0, 0, 512, 512, bulk)
                for (i in old.data.indices) {
                    assertThat(bulk[i].toBits()).isEqualTo(old.data[i].toDouble().toBits())
                    val actual = view.get(i % 512, i / 512)
                    if (old.data[i].isNaN()) assertThat(actual.valid).isFalse()
                    else assertThat(actual.meters.toBits()).isEqualTo(old.data[i].toDouble().toBits())
                }
            }
        }
    }
}
