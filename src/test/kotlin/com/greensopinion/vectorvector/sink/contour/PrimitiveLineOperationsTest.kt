package com.greensopinion.vectorvector.sink.contour

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.Random

class PrimitiveLineOperationsTest {
    @Test fun `primitive smoothing and RDP match the previous pipeline exactly`() {
        val random = Random(832)
        val cases = mutableListOf<List<DoublePoint>>(emptyList(), listOf(DoublePoint(0.0, -0.0)),
            listOf(DoublePoint(0.0, 0.0), DoublePoint(-0.0, 0.0), DoublePoint(0.0, 0.0)))
        repeat(150) {
            val points = List(2 + random.nextInt(100)) { i ->
                DoublePoint(i * 0.125, random.nextDouble() * 100 - 50)
            }
            cases.add(points); cases.add(points + points.first())
        }
        for (points in cases) for (iterations in 0..3) for (epsilon in listOf(0.0, 0.01, 2.5, 100.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            val line = Line(points)
            val smooth = Chaikin.smooth(line, iterations)
            val old = if (epsilon > 0) Rdp.simplify(smooth, epsilon) else smooth
            assertThat(PrimitiveLineOperations.process(line, iterations, epsilon).points).isEqualTo(old.points)
        }
    }
}
