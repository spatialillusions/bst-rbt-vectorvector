package com.greensopinion.vectorvector

class TileRange(
    val minZ: Int,
    val maxZ: Int,
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int,
    /** Optional geographic clip: tiles that don't intersect it are skipped. */
    val bounds: LonLatBounds? = null,
    /** Optional explicit tile set: only these tiles are produced. */
    val only: Set<TileId>? = null,
) {
    init {
        require(maxZ >= minZ) { "maxZ=$maxZ must be greater or equal to minZ=$minZ" }
        require(minX <= maxX && minY <= maxY) { "Tile bounds must be ordered" }
        val c = TileCoordinates(minZ)
        c.checkZ(minZ)
        c.checkZ(maxZ)
        c.checkX(minX)
        c.checkX(maxX)
        c.checkY(minY)
        c.checkY(maxY)
    }

    /** Count without enumerating tiles; a planet through z16 exceeds Int.MAX_VALUE. */
    val size: Long get() {
        only?.let { return it.count { contains(it) }.toLong() }
        return (minZ..maxZ).sumOf { z ->
            val (xs, ys) = ranges(z)
            (xs.last.toLong() - xs.first + 1).coerceAtLeast(0) *
                (ys.last.toLong() - ys.first + 1).coerceAtLeast(0)
        }
    }

    private fun contains(tile: TileId): Boolean = tile.z in minZ..maxZ &&
        (tile.x shr (tile.z - minZ)) in minX..maxX &&
        (tile.y shr (tile.z - minZ)) in minY..maxY &&
        (bounds == null || tile.lonLatBounds().intersects(bounds))

    private fun ranges(z: Int): Pair<IntRange, IntRange> {
        val factor = 1 shl (z - minZ)
        val xs = minX * factor until (maxX + 1) * factor
        val ys = minY * factor until (maxY + 1) * factor
        val b = bounds ?: return xs to ys
        // Binary searches use the exact same strict boundaries as lonLatBounds().intersects().
        fun first(range: IntRange, predicate: (Int) -> Boolean): Int {
            var low = range.first
            var high = range.last + 1
            while (low < high) {
                val mid = low + (high - low) / 2
                if (predicate(mid)) high = mid else low = mid + 1
            }
            return low
        }
        val left = first(xs) { WebMercator.tileXToLon((it + 1).toDouble(), z) > b.minLon }
        val right = first(xs) { WebMercator.tileXToLon(it.toDouble(), z) >= b.maxLon } - 1
        val top = first(ys) { WebMercator.tileYToLat((it + 1).toDouble(), z) < b.maxLat }
        val bottom = first(ys) { WebMercator.tileYToLat(it.toDouble(), z) <= b.minLat } - 1
        return (left..right) to (top..bottom)
    }

    fun tiles(): Sequence<TileId> {
        only?.let { selected ->
            return selected.asSequence().filter { contains(it) }.sortedWith(
                compareBy<TileId>({ it.y shr (it.z - minZ) }, { it.x shr (it.z - minZ) },
                    { it.z }, { it.x }, { it.y })
            )
        }
        val ranges = (minZ..maxZ).associateWith { ranges(it) }
        return sequence {
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    for (z in minZ..maxZ) {
                        val factor = 1 shl (z - minZ)
                        val (xs, ys) = ranges.getValue(z)
                        for (innerX in maxOf(x * factor, xs.first)..minOf((x + 1) * factor - 1, xs.last)) {
                            for (innerY in maxOf(y * factor, ys.first)..minOf((y + 1) * factor - 1, ys.last)) {
                                yield(TileId(z, innerX, innerY))
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** The tile range at [minZ] that covers [bounds]. */
        fun covering(bounds: LonLatBounds, minZ: Int, maxZ: Int): TileRange {
            val topLeft = WebMercator.tileAt(bounds.minLon, bounds.maxLat, minZ)
            val bottomRight = WebMercator.tileAt(bounds.maxLon, bounds.minLat, minZ)
            return TileRange(
                minZ = minZ, maxZ = maxZ,
                minX = topLeft.x, maxX = bottomRight.x,
                minY = topLeft.y, maxY = bottomRight.y,
                bounds = bounds
            )
        }
    }
}
