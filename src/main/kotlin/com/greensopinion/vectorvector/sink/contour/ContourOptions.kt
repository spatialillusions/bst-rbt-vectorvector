package com.greensopinion.vectorvector.sink.contour

data class ContourOptions(
    /** Factor to scale elevation meters, to support different units */
    val multiplier: Double = 1.0,

    /** The key for the elevation property to set on each contour line. */
    val elevationKey: String = "ele",

    /** The key for the level property to set on each contour line. Minor levels have level=0 and
    major levels have level=1 */
    val levelKey: String = "level",

    /** The name of the vector tile layer for contour lines. */
    val contourLayer: String = "contours",

    /** The extent of the vector tile. */
    val extent: Int = 4096,

    /** The number of indices to generate into the neighbouring tile to reduce rendering artifacts. */
    val buffer: Int = 1,

    /** The threshold used for minor contour lines */
    val minorLevel: Int = 50,

    /** The threshold used for major contour lines */
    val majorLevel: Int = 200,

    /** The minimum level, exclusive, to include in contour lines */
    val minLevelExclusive: Int = 0,
    /** The epsilon to apply with the Ramer–Douglas–Peucker algorithm, used to reduce points in contour lines. */
    val epsilon: Int? = null,

    /** Which attributes are written. [AttributeStyle.RBT] also selects the smoothing pipeline. */
    val attributeStyle: AttributeStyle = AttributeStyle.VECTORVECTOR,

    /** Keeps only levels (multiples of [minorLevel]) for which this returns true. */
    val levelFilter: ((Int) -> Boolean)? = null,

    /** Smoothing applied by the RBT pipeline. */
    val smoothing: SmoothingOptions = SmoothingOptions(),

    /** RBT pipeline: fractional RDP epsilon in tile units; overrides [epsilon] when set. */
    val rdpEpsilon: Double? = null,

    /** RBT pipeline: lines are clipped to the tile box grown by this many tile units. */
    val clipBuffer: Int = 80
)

enum class AttributeStyle {
    /** `ele` + `level` (upstream vectorvector). */
    VECTORVECTOR,

    /** `ele` + `index` + `is_ice` (Releasable Basemap Tiles). */
    RBT
}

data class SmoothingOptions(
    /** Gaussian blur of the elevation grid before contouring, in elevation pixels. 0 disables it. */
    val sigmaPx: Double = 0.0,
    /** Chaikin corner-cutting iterations after contouring. 0 disables it. */
    val chaikinIterations: Int = 0
)
