package com.greensopinion.vectorvector.osm

import org.locationtech.jts.geom.CoordinateFilter
import org.locationtech.jts.geom.Polygonal
import org.locationtech.jts.geom.util.GeometryFixer
import org.locationtech.jts.io.WKBReader
import java.io.File

/** Additional OSM-derived ice polygons, notably the implicit Antarctic ice sheet.
 * Input is a WGS84 Polygon/MultiPolygon in binary WKB/EWKB, prepared once offline.
 */
class GlacierWkbLoader(private val file: File) {
    fun load(): List<Glacier> {
        require(file.isFile) { "Missing glacier WKB: $file" }
        val geometry = WKBReader().read(file.readBytes())
        require(geometry is Polygonal) { "Glacier WKB must contain a Polygon or MultiPolygon" }
        require(geometry.srid == 0 || geometry.srid == 4326) { "Glacier WKB must use WGS84 (EPSG:4326)" }
        geometry.apply(CoordinateFilter { coordinate ->
            require(coordinate.x.isFinite() && coordinate.y.isFinite() &&
                coordinate.x in -180.0..180.0 && coordinate.y in -90.0..90.0) {
                "Glacier WKB coordinates must be finite WGS84 longitude/latitude"
            }
        })
        return buildList {
            for (i in 0 until geometry.numGeometries) {
                val polygon = geometry.getGeometryN(i)
                val fixed = if (polygon.isValid) polygon else GeometryFixer.fix(polygon)
                require(fixed is Polygonal) { "Glacier WKB polygon $i could not be repaired" }
                for (j in 0 until fixed.numGeometries) {
                    val part = fixed.getGeometryN(j)
                    if (!part.isEmpty) add(Glacier("wkb:${file.name}:$i:$j", part))
                }
            }
        }
    }
}
