package com.greensopinion.vectorvector.elevation.mapterhorn

import com.greensopinion.vectorvector.Elevation
import com.greensopinion.vectorvector.ElevationTile
import com.greensopinion.vectorvector.INVALID_ELEVATION
import kotlin.math.floor

/** A square grid of elevations in metres, row-major with y down. NaN means no data. */
class ElevationGrid(val size: Int, val data: FloatArray) {
    init {
        require(data.size == size * size) { "expected ${size * size} values but got ${data.size}" }
    }

    operator fun get(x: Int, y: Int): Float = data[y * size + x]

    /** The square region at ([x0], [y0]) of [regionSize] pixels, copied without resampling. */
    fun crop(x0: Int, y0: Int, regionSize: Int): ElevationGrid {
        require(x0 >= 0 && y0 >= 0 && x0 + regionSize <= size && y0 + regionSize <= size) { "crop out of bounds" }
        val out = FloatArray(regionSize * regionSize)
        for (y in 0..<regionSize) {
            System.arraycopy(data, (y0 + y) * size + x0, out, y * regionSize, regionSize)
        }
        return ElevationGrid(regionSize, out)
    }

    /**
     * Bilinearly resamples the square region at ([x0], [y0]) of [regionSize] pixels to [targetSize] pixels.
     * Pixels are treated as cell-centred. Samples next to the region use real neighbouring pixels from this
     * grid where available and are clamped at the grid border. A NaN neighbour makes the sample NaN.
     */
    fun resample(x0: Int, y0: Int, regionSize: Int, targetSize: Int): ElevationGrid {
        val out = FloatArray(targetSize * targetSize)
        val scale = regionSize.toDouble() / targetSize
        // Every row uses the same x mapping. Keep the original float arithmetic/order exactly,
        // but compute floor, clamps and fractions once per column rather than once per pixel.
        val left = IntArray(targetSize)
        val right = IntArray(targetSize)
        val fraction = FloatArray(targetSize)
        for (tx in 0..<targetSize) {
            val sx = x0 + (tx + 0.5) * scale - 0.5
            val ix = floor(sx).toInt()
            fraction[tx] = (sx - ix).toFloat()
            left[tx] = ix.coerceIn(0, size - 1)
            right[tx] = (ix + 1).coerceIn(0, size - 1)
        }
        for (ty in 0..<targetSize) {
            val sy = y0 + (ty + 0.5) * scale - 0.5
            val iy = floor(sy).toInt()
            val fy = (sy - iy).toFloat()
            val row1 = iy.coerceIn(0, size - 1) * size
            val row2 = (iy + 1).coerceIn(0, size - 1) * size
            for (tx in 0..<targetSize) {
                val fx = fraction[tx]
                val x1 = left[tx]
                val x2 = right[tx]
                val top = data[row1 + x1] * (1 - fx) + data[row1 + x2] * fx
                val bottom = data[row2 + x1] * (1 - fx) + data[row2 + x2] * fx
                out[ty * targetSize + tx] = top * (1 - fy) + bottom * fy
            }
        }
        return ElevationGrid(targetSize, out)
    }

    fun toTile(): ElevationTile = GridElevationTile(this)
}

private class GridElevationTile(private val grid: ElevationGrid) : ElevationTile() {
    override val extent: Int = grid.size
    override val empty: Boolean = grid.data.all { it.isNaN() }

    override fun get(x: Int, y: Int): Elevation {
        if (x < 0 || y < 0 || x >= extent || y >= extent) {
            return INVALID_ELEVATION
        }
        val value = grid[x, y]
        return if (value.isNaN()) INVALID_ELEVATION else Elevation(value.toDouble())
    }

    /** Already backed by an array. */
    override fun materialize(buffer: Int): ElevationTile = if (buffer == 0) this else super.materialize(buffer)
}
