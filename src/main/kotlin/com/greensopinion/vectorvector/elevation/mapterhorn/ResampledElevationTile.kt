package com.greensopinion.vectorvector.elevation.mapterhorn

import com.greensopinion.vectorvector.Elevation
import com.greensopinion.vectorvector.ElevationBounds
import com.greensopinion.vectorvector.ElevationTile
import com.greensopinion.vectorvector.INVALID_ELEVATION
import kotlin.math.floor

/** A read-only child view. Float operations deliberately match ElevationGrid.resample. */
internal class ResampledElevationTile(
    private val source: ElevationGrid, x0: Int, y0: Int, regionSize: Int,
    override val extent: Int
) : ElevationTile() {
    private class Axis(origin: Int, region: Int, size: Int, limit: Int) {
        val first = IntArray(size)
        val next = IntArray(size)
        val fraction = FloatArray(size)
        init {
            val scale = region.toDouble() / size
            for (i in 0 until size) {
                val position = origin + (i + 0.5) * scale - 0.5
                val lower = floor(position).toInt()
                first[i] = lower.coerceIn(0, limit - 1)
                next[i] = (lower + 1).coerceIn(0, limit - 1)
                fraction[i] = (position - lower).toFloat()
            }
        }
    }
    private val horizontal = Axis(x0, regionSize, extent, source.size)
    override val allZero get() = source.allZero
    override val conservativeBounds by lazy {
        source.conservativeBounds?.let { b ->
            val magnitude = maxOf(kotlin.math.abs(b.min.meters), kotlin.math.abs(b.max.meters))
            if (magnitude > 1e6) null else {
                // Bilinear weights are nonnegative. Allow ample float rounding headroom
                // for the original multiply/add order, including cancellation around zero.
                val padding = 16 * Math.ulp(magnitude.toFloat()).toDouble()
                ElevationBounds(Elevation(b.min.meters - padding), Elevation(b.max.meters + padding))
            }
        }
    }
    private val vertical = Axis(y0, regionSize, extent, source.size)
    override val retainedSamples get() = source.data
    override val retainedBytes: Long get() = source.data.size * 4L + extent * 24L + 256
    override val empty: Boolean by lazy {
        (0 until extent).all { y -> (0 until extent).all { x -> sample(x, y).isNaN() } }
    }
    private fun sample(x: Int, y: Int): Float {
        val fx = horizontal.fraction[x]
        val fy = vertical.fraction[y]
        val a = vertical.first[y] * source.size
        val b = vertical.next[y] * source.size
        val left = horizontal.first[x]
        val right = horizontal.next[x]
        val top = source.data[a + left] * (1 - fx) + source.data[a + right] * fx
        val bottom = source.data[b + left] * (1 - fx) + source.data[b + right] * fx
        return top * (1 - fy) + bottom * fy
    }
    override fun get(x: Int, y: Int): Elevation {
        if (x !in 0 until extent || y !in 0 until extent) return INVALID_ELEVATION
        val value = sample(x, y)
        return if (value.isNaN()) INVALID_ELEVATION else Elevation(value.toDouble())
    }
    override fun copySamples(x: Int, y: Int, width: Int, height: Int, out: DoubleArray,
                             offset: Int, stride: Int) {
        require(x >= 0 && y >= 0 && x + width <= extent && y + height <= extent)
        for (row in 0 until height) for (column in 0 until width) {
            out[offset + row * stride + column] = sample(x + column, y + row).toDouble()
        }
    }
}
