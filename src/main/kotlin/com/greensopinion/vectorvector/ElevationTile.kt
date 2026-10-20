package com.greensopinion.vectorvector

@JvmInline
value class Elevation(val meters: Double) {
    val valid: Boolean get() = meters > INVALID_ELEVATION.meters
    operator fun times(multiplier: Double) = Elevation(meters = meters * multiplier)
    operator fun minus(other: Elevation): Elevation =
        if (other.valid && valid) Elevation(meters = meters - other.meters) else this

    operator fun compareTo(other: Elevation): Int {
        return this.meters.compareTo(other.meters)
    }

    operator fun compareTo(other: Int): Int {
        return this.meters.compareTo(other)
    }
    
    operator fun compareTo(other: Double): Int {
        return this.meters.compareTo(other)
    }

    override fun toString(): String = "$meters"
}

data class ElevationBounds(val min: Elevation, val max: Elevation)

val INVALID_ELEVATION = Elevation(meters = Double.NEGATIVE_INFINITY)

abstract class ElevationTile {
    /** True only when every sample is known to be valid sea-level zero. */
    open val allZero: Boolean get() = false
    /** Optional conservative range, including any interpolation rounding; unknown means no shortcut. */
    open val conservativeBounds: ElevationBounds? get() = null
    /** Conservative owned/referenced sample storage for cache budgeting. */
    open val retainedBytes: Long get() = 128
    internal open val retainedSamples: FloatArray? get() = null
    abstract val empty: Boolean
    abstract val extent: Int
    val elevationBounds: ElevationBounds by lazy {
        var bounds = ElevationBounds(min = INVALID_ELEVATION, max = INVALID_ELEVATION)
        for (x in 0..<extent) {
            for (y in 0..<extent) {
                val elevation = get(x, y)
                if (elevation.valid) {
                    if (!bounds.min.valid || bounds.min > elevation) {
                        bounds = bounds.copy(min = elevation)
                    }
                    if (!bounds.max.valid || bounds.max < elevation) {
                        bounds = bounds.copy(max = elevation)
                    }
                }
            }
        }
        bounds
    }

    abstract fun get(x: Int, y: Int): Elevation

    /** Row-major materialization hook; implementations may fuse decoding/resampling. */
    open fun copySamples(x: Int, y: Int, width: Int, height: Int, out: DoubleArray,
                         offset: Int = 0, stride: Int = width) {
        for (row in 0 until height) for (column in 0 until width) {
            val value = get(x + column, y + row)
            out[offset + row * stride + column] = if (value.valid) value.meters else Double.NaN
        }
    }

    open fun materialize(buffer: Int = 0): ElevationTile = if (empty) this else MaterializedTile(this, buffer)

    fun scale(multiplier: Double): ElevationTile =
        if (multiplier == 1.0 || empty) this else ScaledTile(multiplier, this)
}

class EmptyTile(
    override val extent: Int
) : ElevationTile() {
    override val empty = true
    override fun get(x: Int, y: Int): Elevation = INVALID_ELEVATION
}

private class ScaledTile(
    private val multiplier: Double,
    private val delegate: ElevationTile
) : ElevationTile() {
    override val empty = delegate.empty
    override val extent = delegate.extent

    override fun get(x: Int, y: Int): Elevation {
        val elevation = delegate.get(x, y)
        return if (elevation == INVALID_ELEVATION) INVALID_ELEVATION else elevation * multiplier
    }
}

private class MaterializedTile(delegate: ElevationTile, private val buffer: Int) : ElevationTile() {
    override val empty = delegate.empty
    override val extent = delegate.extent
    private val length = extent + 2*buffer
    private val data = DoubleArray(length * length)

    private val lower = 0 - buffer
    private val upper = extent + buffer
    init {
        for (x in lower..<upper) {
            for (y in lower..<upper) {
                data[(x+buffer) + (y+buffer) * length] = delegate.get(x, y).meters
            }
        }
    }

    override fun get(x: Int, y: Int): Elevation {
        if (x < lower || x >= upper || y < lower || y >= upper) {
            return INVALID_ELEVATION
        }
        return Elevation(meters = data[(x + buffer) + (y + buffer) * length])
    }

    override fun materialize(buffer: Int): ElevationTile = this
}

