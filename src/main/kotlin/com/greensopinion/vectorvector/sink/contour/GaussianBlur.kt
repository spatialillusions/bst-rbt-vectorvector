package com.greensopinion.vectorvector.sink.contour

import com.greensopinion.vectorvector.Elevation
import com.greensopinion.vectorvector.ElevationTile
import com.greensopinion.vectorvector.INVALID_ELEVATION
import kotlin.math.ceil
import kotlin.math.exp

/**
 * Separable Gaussian blur. Reads [source] (normally an ElevationTileArea, so reads beyond the tile hit the
 * neighbours) over the tile plus [buffer] plus the kernel radius, so blurred values near tile edges equal those
 * computed by the neighbouring tile and no seams appear. Invalid samples are left out of the weighted average;
 * an invalid centre stays invalid.
 */
object GaussianBlur {
    /** Reusable scratch only: each returned grid still owns its output. Confine to one thread. */
    class Workspace {
        private var first = DoubleArray(0)
        private var second = DoubleArray(0)

        internal fun input(length: Int): DoubleArray {
            if (first.size < length) first = DoubleArray(length)
            return first
        }

        internal fun next(source: DoubleArray, length: Int): DoubleArray {
            if (source === first) {
                if (second.size < length) second = DoubleArray(length)
                return second
            }
            return input(length)
        }
    }
    /** Copies [source] over the tile plus [buffer] into an array-backed tile, without blurring. */
    fun materialize(source: ElevationTile, buffer: Int): BufferedGridTile {
        val size = source.extent + 2 * buffer
        val data = DoubleArray(size * size)
        for (y in 0..<size) {
            for (x in 0..<size) {
                val e = source.get(x - buffer, y - buffer)
                data[y * size + x] = if (e.valid) e.meters else Double.NaN
            }
        }
        return BufferedGridTile(source.extent, buffer, data)
    }

    fun radius(sigma: Double): Int = ceil(3 * sigma).toInt()

    /** Above this sigma, three box passes approximate the Gaussian; cost no longer grows with sigma. */
    const val BOX_THRESHOLD = 2.0

    fun blur(source: ElevationTile, buffer: Int, sigma: Double, workspace: Workspace? = null): BufferedGridTile {
        require(sigma > 0) { "sigma must be positive" }
        if (sigma > BOX_THRESHOLD) {
            return boxBlur(source, buffer, sigma, workspace)
        }
        val r = radius(sigma)
        val kernel = DoubleArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }
        val extent = source.extent
        val outSize = extent + 2 * buffer
        val inSize = outSize + 2 * r
        val origin = -buffer - r

        val input = workspace?.input(inSize * inSize) ?: DoubleArray(inSize * inSize)
        for (y in 0..<inSize) {
            for (x in 0..<inSize) {
                val e = source.get(origin + x, origin + y)
                input[y * inSize + x] = if (e.valid) e.meters else Double.NaN
            }
        }
        // horizontal pass: rows over the full input height, columns over the output width
        val horizontal = workspace?.next(input, inSize * outSize) ?: DoubleArray(inSize * outSize)
        for (y in 0..<inSize) {
            for (x in 0..<outSize) {
                horizontal[y * outSize + x] = weighted(input, y * inSize + x + r, 1, kernel, r)
            }
        }
        val output = DoubleArray(outSize * outSize)
        for (y in 0..<outSize) {
            for (x in 0..<outSize) {
                output[y * outSize + x] = weighted(horizontal, (y + r) * outSize + x, outSize, kernel, r)
            }
        }
        return BufferedGridTile(extent, buffer, output)
    }

    /** Radii of [n] box filters whose combination approximates a Gaussian of [sigma] (Kovesi / Ivan Kutskir). */
    fun boxRadii(sigma: Double, n: Int = 3): IntArray {
        val ideal = Math.sqrt(12 * sigma * sigma / n + 1)
        var lower = Math.floor(ideal).toInt()
        if (lower % 2 == 0) lower--
        val upper = lower + 2
        val mIdeal = (12 * sigma * sigma - n * lower * lower - 4 * n * lower - 3 * n) / (-4 * lower - 4)
        val m = Math.round(mIdeal).toInt()
        return IntArray(n) { if (it < m) (lower - 1) / 2 else (upper - 1) / 2 }
    }

    /**
     * Three successive box filters per axis (running sums, O(1) per pixel). Invalid samples are excluded from
     * each box average; an invalid centre stays invalid.
     */
    private fun boxBlur(source: ElevationTile, buffer: Int, sigma: Double, workspace: Workspace?): BufferedGridTile {
        val radii = boxRadii(sigma)
        val total = radii.sum()
        val extent = source.extent
        val outSize = extent + 2 * buffer
        var width = outSize + 2 * total
        var height = width
        val origin = -buffer - total
        var data = workspace?.input(width * height) ?: DoubleArray(width * height)
        var allZero = true
        for (y in 0..<height) {
            for (x in 0..<width) {
                val e = source.get(origin + x, origin + y)
                val value = if (e.valid) e.meters else Double.NaN
                data[y * width + x] = value
                if (allZero && value != 0.0) allZero = false
            }
        }
        // Exact special case: the entire buffered kernel footprint is valid sea-level zero.
        // Check neighbours too; a zero centre alone is insufficient near coastlines.
        if (allZero) return BufferedGridTile(extent, buffer, DoubleArray(outSize * outSize))
        for (r in radii) {
            data = horizontalBox(data, width, height, r, workspace?.next(data, (width - 2 * r) * height))
            width -= 2 * r
        }
        for ((index, r) in radii.withIndex()) {
            val destination = if (index == radii.lastIndex) null else workspace?.next(data, width * (height - 2 * r))
            data = verticalBox(data, width, height, r, destination)
            height -= 2 * r
        }
        check(width == outSize && height == outSize)
        return BufferedGridTile(extent, buffer, data)
    }

    private fun horizontalBox(data: DoubleArray, width: Int, height: Int, r: Int, destination: DoubleArray? = null): DoubleArray {
        val outWidth = width - 2 * r
        val out = destination ?: DoubleArray(outWidth * height)
        for (y in 0..<height) {
            val base = y * width
            val outBase = y * outWidth
            var sum = 0.0
            var count = 0
            for (i in 0..<2 * r) {
                val v = data[base + i]
                if (!v.isNaN()) {
                    sum += v
                    count++
                }
            }
            for (o in 0..<outWidth) {
                val add = data[base + o + 2 * r]
                if (!add.isNaN()) {
                    sum += add
                    count++
                }
                val centre = data[base + o + r]
                out[outBase + o] = if (centre.isNaN() || count == 0) Double.NaN else sum / count
                val remove = data[base + o]
                if (!remove.isNaN()) {
                    sum -= remove
                    count--
                }
            }
        }
        return out
    }

    /** Row-major vertical pass with per-column running sums (cache friendly). */
    private fun verticalBox(data: DoubleArray, width: Int, height: Int, r: Int, destination: DoubleArray? = null): DoubleArray {
        val outHeight = height - 2 * r
        val out = destination ?: DoubleArray(width * outHeight)
        val sums = DoubleArray(width)
        val counts = IntArray(width)
        fun add(row: Int, sign: Int) {
            val base = row * width
            for (x in 0..<width) {
                val v = data[base + x]
                if (!v.isNaN()) {
                    sums[x] += sign * v
                    counts[x] += sign
                }
            }
        }
        for (row in 0..<2 * r) add(row, 1)
        for (o in 0..<outHeight) {
            add(o + 2 * r, 1)
            val centreBase = (o + r) * width
            val outBase = o * width
            for (x in 0..<width) {
                val centre = data[centreBase + x]
                out[outBase + x] = if (centre.isNaN() || counts[x] == 0) Double.NaN else sums[x] / counts[x]
            }
            add(o, -1)
        }
        return out
    }

    private fun weighted(data: DoubleArray, centre: Int, stride: Int, kernel: DoubleArray, r: Int): Double {
        if (data[centre].isNaN()) {
            return Double.NaN
        }
        var sum = 0.0
        var weights = 0.0
        for (k in -r..r) {
            val v = data[centre + k * stride]
            if (!v.isNaN()) {
                val w = kernel[k + r]
                sum += v * w
                weights += w
            }
        }
        return sum / weights
    }
}

/** A materialized tile with [buffer] pixels around it; NaN is invalid. */
class BufferedGridTile(
    override val extent: Int,
    val buffer: Int,
    val data: DoubleArray
) : ElevationTile() {
    val size = extent + 2 * buffer
    override val empty: Boolean = data.all { it.isNaN() }

    override fun get(x: Int, y: Int): Elevation {
        val ix = x + buffer
        val iy = y + buffer
        if (ix < 0 || iy < 0 || ix >= size || iy >= size) {
            return INVALID_ELEVATION
        }
        val v = data[iy * size + ix]
        return if (v.isNaN()) INVALID_ELEVATION else Elevation(v)
    }

    override fun materialize(buffer: Int): ElevationTile = this
}
