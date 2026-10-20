package com.greensopinion.vectorvector.pmtiles

import com.greensopinion.vectorvector.TileId

/** Hilbert-curve tile ids, ported from the PMTiles v3 reference implementation (pmtiles/js). */
object TileIds {
    private val zoomBase = LongArray(28).also {
        var acc = 0L
        for (z in it.indices) {
            it[z] = acc
            acc += (1L shl z) * (1L shl z)
        }
    }

    fun zxyToTileId(z: Int, x: Int, y: Int): Long {
        require(z in 0..26) { "zoom $z out of range" }
        val n = 1L shl z
        require(x in 0..<n && y in 0..<n) { "tile $z/$x/$y out of range" }
        val xy = longArrayOf(x.toLong(), y.toLong())
        var d = 0L
        var s = n / 2
        while (s > 0) {
            val rx = if (xy[0] and s > 0) 1L else 0L
            val ry = if (xy[1] and s > 0) 1L else 0L
            d += s * s * ((3 * rx) xor ry)
            rotate(s, xy, rx, ry)
            s /= 2
        }
        return zoomBase[z] + d
    }

    fun tileIdToZxy(id: Long): TileId {
        for (z in 0..26) {
            val count = (1L shl z) * (1L shl z)
            if (zoomBase[z] + count > id) {
                return idOnLevel(z, id - zoomBase[z])
            }
        }
        throw IllegalArgumentException("tile id $id out of range")
    }

    private fun idOnLevel(z: Int, position: Long): TileId {
        val n = 1L shl z
        val xy = longArrayOf(0, 0)
        var t = position
        var s = 1L
        while (s < n) {
            val rx = 1L and (t / 2)
            val ry = 1L and (t xor rx)
            rotate(s, xy, rx, ry)
            xy[0] += s * rx
            xy[1] += s * ry
            t /= 4
            s *= 2
        }
        return TileId(z, xy[0].toInt(), xy[1].toInt())
    }

    private fun rotate(n: Long, xy: LongArray, rx: Long, ry: Long) {
        if (ry == 0L) {
            if (rx == 1L) {
                xy[0] = n - 1 - xy[0]
                xy[1] = n - 1 - xy[1]
            }
            val t = xy[0]
            xy[0] = xy[1]
            xy[1] = t
        }
    }
}
