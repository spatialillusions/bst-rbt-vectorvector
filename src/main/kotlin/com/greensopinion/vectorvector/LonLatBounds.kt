package com.greensopinion.vectorvector

import kotlin.math.PI
import kotlin.math.asinh
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.sinh
import kotlin.math.tan

/** A geographic bounding box in degrees (EPSG:4326). */
data class LonLatBounds(val minLon: Double, val minLat: Double, val maxLon: Double, val maxLat: Double) {
    init {
        require(minLon < maxLon && minLat < maxLat) { "invalid bounds: $this" }
    }

    fun intersects(other: LonLatBounds): Boolean =
        minLon < other.maxLon && maxLon > other.minLon && minLat < other.maxLat && maxLat > other.minLat

    fun contains(lon: Double, lat: Double): Boolean = lon in minLon..maxLon && lat in minLat..maxLat

    override fun toString(): String = "$minLon,$minLat,$maxLon,$maxLat"

    companion object {
        /** Parses `minLon,minLat,maxLon,maxLat`. */
        fun parse(value: String): LonLatBounds {
            val parts = value.split(",").map { it.trim().toDouble() }
            require(parts.size == 4) { "bounds must be minLon,minLat,maxLon,maxLat but was '$value'" }
            return LonLatBounds(parts[0], parts[1], parts[2], parts[3])
        }
    }
}

/** Web Mercator (XYZ) tile math. y grows southwards, matching MVT tile coordinates. */
object WebMercator {
    fun lonToTileX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * (1 shl z)

    fun latToTileY(lat: Double, z: Int): Double =
        (1.0 - asinh(tan(Math.toRadians(lat))) / PI) / 2.0 * (1 shl z)

    fun tileXToLon(x: Double, z: Int): Double = x / (1 shl z) * 360.0 - 180.0

    fun tileYToLat(y: Double, z: Int): Double = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / (1 shl z)))))

    fun tileAt(lon: Double, lat: Double, z: Int): TileId {
        val max = (1 shl z) - 1
        return TileId(
            z,
            floor(lonToTileX(lon, z)).toInt().coerceIn(0, max),
            floor(latToTileY(lat, z)).toInt().coerceIn(0, max)
        )
    }
}

fun TileId.lonLatBounds(): LonLatBounds = LonLatBounds(
    minLon = WebMercator.tileXToLon(x.toDouble(), z),
    minLat = WebMercator.tileYToLat((y + 1).toDouble(), z),
    maxLon = WebMercator.tileXToLon((x + 1).toDouble(), z),
    maxLat = WebMercator.tileYToLat(y.toDouble(), z)
)
