package com.greensopinion.vectorvector.sink.contour

import com.greensopinion.vectorvector.ElevationTileArea
import com.greensopinion.vectorvector.TileId
import com.greensopinion.vectorvector.elevation.ElevationDataStore
import kotlin.math.ceil

/** One contour line piece as written to the tile. */
data class ContourFeature(val elevation: Int, val isIce: Boolean, val line: Line)

/** Splits a line into ice and land pieces. */
fun interface IceSplitter {
    fun split(line: Line): List<Pair<Line, Boolean>>
}

/**
 * The RBT contour pipeline, in this order:
 * Gaussian blur → marching squares (unrounded) → Chaikin → RDP → ice split → clip to the buffered tile → round.
 */
class ContourPostProcessor(
    private val options: ContourOptions,
    private val dataStore: ElevationDataStore
) {
    fun generate(tile: TileId, iceSplitter: IceSplitter? = null): List<ContourFeature> {
        val raw = isolines(tile)
        val epsilon = options.rdpEpsilon ?: options.epsilon?.toDouble() ?: 0.0
        val clipper = BoxClipper(-options.clipBuffer.toDouble(), (options.extent + options.clipBuffer).toDouble())
        val features = mutableListOf<ContourFeature>()
        for ((level, lines) in raw.entries.sortedBy { it.key }) {
            for (line in lines) {
                features += process(level, line, epsilon, clipper, iceSplitter)
            }
        }
        return features
    }

    internal fun process(
        level: Int,
        line: Line,
        epsilon: Double,
        clipper: BoxClipper,
        iceSplitter: IceSplitter?
    ): List<ContourFeature> {
        val l = PrimitiveLineOperations.process(line, options.smoothing.chaikinIterations, epsilon)
        val pieces = iceSplitter?.split(l) ?: listOf(Pair(l, false))
        return pieces.flatMap { (piece, ice) ->
            clipper.clip(piece).mapNotNull { it.roundToGrid() }.map { ContourFeature(level, ice, it) }
        }
    }

    /** Marching-squares isolines in tile units (unrounded), keyed by level. */
    fun isolines(tile: TileId): Map<Int, List<Line>> {
        val elevationExtent = dataStore.get(tile).extent
        val factor = options.extent.toDouble() / elevationExtent
        // generate a little beyond the clip box so clipping produces clean cuts
        val contourBuffer = maxOf(options.buffer, ceil(options.clipBuffer / factor).toInt() + 1)
        val sigma = options.smoothing.sigmaPx
        val area = ElevationTileArea(tile, dataStore.get(tile), dataStore).scale(options.multiplier)
        if (area.empty) {
            return mapOf()
        }
        val grid = if (sigma > 0) {
            GaussianBlur.blur(area, contourBuffer, sigma, blurWorkspace.get())
        } else {
            GaussianBlur.materialize(area, contourBuffer)
        }
        var min = Double.POSITIVE_INFINITY
        var max = Double.NEGATIVE_INFINITY
        for (v in grid.data) {
            if (!v.isNaN()) {
                if (v < min) min = v
                if (v > max) max = v
            }
        }
        if (min > max) {
            return mapOf()
        }
        val levels = FastIsolines.levels(min, max, options.minorLevel) {
            it > options.minLevelExclusive && options.levelFilter?.invoke(it) != false
        }
        val isolines = FastIsolines(grid.data, grid.size, grid.buffer, factor)
            .generate(levels, -contourBuffer, elevationExtent - 1 + contourBuffer)
        return isolines.mapValues { e -> e.value.map { Line(it) } }
    }

    companion object {
        private val blurWorkspace = ThreadLocal.withInitial { GaussianBlur.Workspace() }
    }
}
