package com.greensopinion.vectorvector.osm

import com.greensopinion.vectorvector.sink.contour.DoublePoint
import com.greensopinion.vectorvector.sink.contour.IceSplitter
import com.greensopinion.vectorvector.sink.contour.Line
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.prep.PreparedGeometryFactory
import org.locationtech.jts.operation.linemerge.LineMerger
import org.locationtech.jts.operation.overlayng.OverlayNG
import org.locationtech.jts.operation.overlayng.OverlayNGRobust

/**
 * Splits contour lines at the outline of [glacier] (tile coordinates). Pieces shorter than [minPieceLength]
 * tile units are dropped, which avoids stubs where a line just grazes an outline.
 */
class GlacierIceSplitter(
    private val glacier: Geometry,
    private val minPieceLength: Double = 4.0,
    private val factory: GeometryFactory = GeometryFactory()
) : IceSplitter {
    private val prepared = PreparedGeometryFactory.prepare(glacier)

    override fun split(line: Line): List<Pair<Line, Boolean>> {
        if (line.points.size < 2) {
            return listOf()
        }
        val lineString = factory.createLineString(line.points.map { Coordinate(it.x, it.y) }.toTypedArray())
        if (prepared.disjoint(lineString)) {
            return listOf(Pair(line, false))
        }
        if (prepared.containsProperly(lineString)) {
            return listOf(Pair(line, true))
        }
        val ice = OverlayNGRobust.overlay(lineString, glacier, OverlayNG.INTERSECTION)
        val land = OverlayNGRobust.overlay(lineString, glacier, OverlayNG.DIFFERENCE)
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
}
