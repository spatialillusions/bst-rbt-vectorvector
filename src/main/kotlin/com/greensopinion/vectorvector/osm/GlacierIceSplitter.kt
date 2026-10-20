package com.greensopinion.vectorvector.osm

import com.greensopinion.vectorvector.sink.contour.DoublePoint
import com.greensopinion.vectorvector.sink.contour.IceSplitter
import com.greensopinion.vectorvector.sink.contour.Line
import com.greensopinion.vectorvector.TileId
import io.github.oshai.kotlinlogging.KotlinLogging
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.Polygonal
import org.locationtech.jts.geom.TopologyException
import org.locationtech.jts.geom.util.GeometryFixer
import org.locationtech.jts.geom.prep.PreparedGeometry
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import org.locationtech.jts.operation.linemerge.LineMerger
import org.locationtech.jts.operation.overlayng.OverlayNG
import org.locationtech.jts.operation.overlayng.OverlayNGRobust

/**
 * Splits contour lines at the outline of [glacier] (tile coordinates). Pieces shorter than [minPieceLength]
 * tile units are dropped, which avoids stubs where a line just grazes an outline.
 */
class GlacierIceSplitter @JvmOverloads constructor(
    private val glacier: Geometry,
    private val minPieceLength: Double = 4.0,
    private val factory: GeometryFactory = GeometryFactory(),
    private val tile: TileId? = null
) : IceSplitter {
    private data class Mask(val geometry: Geometry, val prepared: PreparedGeometry)
    private var mask = Mask(glacier, PreparedGeometryFactory.prepare(glacier))
    internal var repairCount = 0
        private set

    override fun split(line: Line): List<Pair<Line, Boolean>> {
        if (line.points.size < 2) {
            return listOf()
        }
        val lineString = factory.createLineString(line.points.map { Coordinate(it.x, it.y) }.toTypedArray())
        return try {
            split(line, lineString, mask)
        } catch (failure: TopologyException) {
            // Source geometry may be valid but become invalid after clipping or
            // non-linear projection. Keep the successful path byte-identical;
            // repair this tile's mask once, then retry BOTH ice and land pieces.
            if (repairCount != 0) throw failure
            val fixed = try {
                GeometryFixer.fix(glacier).also {
                    check(it is Polygonal && it.isValid) { "Glacier repair did not produce valid polygons for $tile" }
                }
            } catch (repairFailure: RuntimeException) {
                repairFailure.addSuppressed(failure)
                throw repairFailure
            }
            mask = Mask(fixed, PreparedGeometryFactory.prepare(fixed))
            repairCount++
            log.warn {
                "Repaired glacier mask for ${tile ?: "unspecified tile"}: " +
                    "points=${glacier.numPoints}->${fixed.numPoints}, area=${glacier.area}->${fixed.area}; ${failure.message}"
            }
            // An unsuccessful retry propagates. Never omit contours or mark an
            // entire tile as land merely to make a planet build continue.
            try {
                split(line, lineString, mask)
            } catch (retryFailure: RuntimeException) {
                retryFailure.addSuppressed(failure)
                throw retryFailure
            }
        }
    }

    private fun split(line: Line, lineString: LineString, mask: Mask): List<Pair<Line, Boolean>> {
        if (mask.prepared.disjoint(lineString)) {
            return listOf(Pair(line, false))
        }
        if (mask.prepared.containsProperly(lineString)) {
            return listOf(Pair(line, true))
        }
        val ice = OverlayNGRobust.overlay(lineString, mask.geometry, OverlayNG.INTERSECTION)
        val land = OverlayNGRobust.overlay(lineString, mask.geometry, OverlayNG.DIFFERENCE)
        return pieces(ice).map { Pair(it, true) } + pieces(land).map { Pair(it, false) }
    }

    private fun pieces(g: Geometry): List<Line> {
        if (g.isEmpty) return listOf()
        val merger = LineMerger()
        merger.add(g)
        @Suppress("UNCHECKED_CAST")
        val merged = merger.mergedLineStrings as Collection<LineString>
        return merged.filter { it.length >= minPieceLength }
            .map { ls -> Line(ls.coordinates.map { DoublePoint(it.x, it.y) }) }
    }

    companion object {
        private val log = KotlinLogging.logger {}
    }
}
