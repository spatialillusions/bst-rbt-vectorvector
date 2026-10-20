package com.greensopinion.vectorvector.profile

import com.greensopinion.vectorvector.TileId
import com.greensopinion.vectorvector.sink.LayerSchema
import com.greensopinion.vectorvector.sink.contour.AttributeStyle
import com.greensopinion.vectorvector.sink.contour.ContourOptions
import com.greensopinion.vectorvector.sink.contour.SmoothingOptions

/** Per-zoom pipeline settings of the RBT profile. */
data class RbtZoomSettings(
    val sigmaPx: Double,
    val chaikinIterations: Int,
    val epsilon: Double
)

/**
 * Matches the `contours` layer of the Releasable Basemap Tiles (RBT), see docs/rbt-reference-report.md:
 * z9–z13; multiples of 200 m at z9–10, 100 m at z11, and multiples of 20 m or 50 m at z12–13;
 * attributes `ele`, `index`, `is_ice`; lines clipped with an 80 unit buffer.
 *
 * Smoothing values are tuned against RBT in Phase 7 (docs/tuning-log.md).
 */
class RbtProfile(
    private val settings: Map<Int, RbtZoomSettings> = DEFAULT_SETTINGS,
    val minZoom: Int = 9,
    val maxZoom: Int = 13
) : ContourProfile {
    override fun contourOptions(tile: TileId): ContourOptions? {
        if (tile.z < minZoom || tile.z > maxZoom) {
            return null
        }
        val (interval, filter) = levels(tile.z)
        val s = settings[tile.z] ?: settings.getValue(settings.keys.filter { it <= tile.z }.maxOrNull() ?: settings.keys.min())
        return ContourOptions(
            minorLevel = interval,
            majorLevel = interval,
            rdpEpsilon = s.epsilon,
            attributeStyle = AttributeStyle.RBT,
            levelFilter = filter,
            // RBT has a few negative contours (inland depressions); Mapterhorn has no bathymetry
            minLevelExclusive = Int.MIN_VALUE,
            smoothing = SmoothingOptions(sigmaPx = s.sigmaPx, chaikinIterations = s.chaikinIterations),
            clipBuffer = 80
        )
    }

    override fun contourLayer(minZoom: Int, maxZoom: Int): LayerSchema? {
        val min = maxOf(minZoom, this.minZoom)
        val max = minOf(maxZoom, this.maxZoom)
        if (min > max) {
            return null
        }
        return LayerSchema(
            id = "contours",
            description = "Elevation contour lines, split at glacier outlines",
            minZoom = min,
            maxZoom = max,
            fields = linkedMapOf(
                "ele" to "Number",
                "index" to "Number",
                "is_ice" to "Number"
            )
        )
    }

    companion object {
        /** The levels written at [z]: the generation interval, plus an optional filter. */
        fun levels(z: Int): Pair<Int, ((Int) -> Boolean)?> = when {
            z <= 10 -> Pair(200, null)
            z == 11 -> Pair(100, null)
            else -> Pair(10) { it % 20 == 0 || it % 50 == 0 }
        }

        /** Tuned against RBT over Iceland, 512 px elevation tiles (docs/tuning-log.md, run J). */
        val DEFAULT_SETTINGS = mapOf(
            9 to RbtZoomSettings(sigmaPx = 0.8, chaikinIterations = 1, epsilon = 2.0),
            10 to RbtZoomSettings(sigmaPx = 2.5, chaikinIterations = 2, epsilon = 2.5),
            11 to RbtZoomSettings(sigmaPx = 4.0, chaikinIterations = 2, epsilon = 2.5),
            12 to RbtZoomSettings(sigmaPx = 7.0, chaikinIterations = 2, epsilon = 2.5),
            13 to RbtZoomSettings(sigmaPx = 14.0, chaikinIterations = 2, epsilon = 2.5)
        )
    }
}
