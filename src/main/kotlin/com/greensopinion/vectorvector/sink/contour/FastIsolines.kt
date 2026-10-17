package com.greensopinion.vectorvector.sink.contour

/**
 * Marching squares over all levels in one pass: each cell is visited once and only emits the levels between
 * its corner minimum and maximum, so cost grows with the number of output segments, not levels × cells.
 *
 * Same cell convention and case table as [MarchingSquares] (a corner is "inside" when its value > level).
 * Points are exact (unrounded) and computed in a canonical edge direction, so the two cells sharing an edge
 * produce bit-identical points and segments are chained by exact lookups.
 *
 * @param data elevations row-major, `size × size`, where index (i, j) is elevation pixel (i - origin, j - origin); NaN = no data
 * @param factor tile units per elevation pixel
 */
class FastIsolines(
    private val data: DoubleArray,
    private val size: Int,
    private val origin: Int,
    private val factor: Double
) {
    init {
        require(data.size == size * size) { "expected ${size * size} values" }
    }

    /** Isolines for [levels] (any order) over cells whose corners lie in pixel range [from, to] on both axes. */
    fun generate(levels: IntArray, from: Int, to: Int): Map<Int, List<List<DoublePoint>>> {
        if (levels.isEmpty()) return emptyMap()
        val sorted = levels.sortedArray()
        val step = sorted.fold(0) { g, v -> gcd(g, Math.abs(v)) }.toDouble()
        val segments = arrayOfNulls<SegmentList>(sorted.size)
        val i0 = from + origin
        val i1 = to + origin
        require(i0 >= 0 && i1 < size) { "cell range outside of the data" }
        for (j in i0..<i1) {
            for (i in i0..<i1) {
                val bl = data[j * size + i]
                val br = data[j * size + i + 1]
                val tr = data[(j + 1) * size + i + 1]
                val tl = data[(j + 1) * size + i]
                if (bl.isNaN() || br.isNaN() || tr.isNaN() || tl.isNaN()) continue
                val lo = minOf(minOf(bl, br), minOf(tr, tl))
                val hi = maxOf(maxOf(bl, br), maxOf(tr, tl))
                // levels L with lo <= L < hi
                // all levels are multiples of step: most cells contain none, skip them without a search
                if (step > 0 && Math.ceil(lo / step) * step >= hi) continue
                var k = firstAtLeast(sorted, lo)
                while (k < sorted.size && sorted[k] < hi) {
                    val level = sorted[k]
                    val list = segments[k] ?: SegmentList().also { segments[k] = it }
                    cell(i - origin, j - origin, bl, br, tr, tl, level, list)
                    k++
                }
            }
        }
        val result = LinkedHashMap<Int, List<List<DoublePoint>>>()
        for (k in sorted.indices) {
            segments[k]?.let { result[sorted[k]] = chain(it) }
        }
        return result
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private fun firstAtLeast(sorted: IntArray, value: Double): Int {
        var low = 0
        var high = sorted.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (sorted[mid] < value) low = mid + 1 else high = mid
        }
        return low
    }

    private fun cell(x: Int, y: Int, bl: Double, br: Double, tr: Double, tl: Double, level: Int, out: SegmentList) {
        var case = 0
        if (bl > level) case = case or 1
        if (br > level) case = case or 2
        if (tr > level) case = case or 4
        if (tl > level) case = case or 8
        // edges: bottom (bl-br), right (br-tr), top (tl-tr), left (bl-tl)
        fun bottom() = point(x, y, x + 1, y, bl, br, level)
        fun right() = point(x + 1, y, x + 1, y + 1, br, tr, level)
        fun top() = point(x, y + 1, x + 1, y + 1, tl, tr, level)
        fun left() = point(x, y, x, y + 1, bl, tl, level)
        when (case) {
            0, 15 -> {}
            1, 14 -> out.add(bottom(), left())
            2, 13 -> out.add(bottom(), right())
            3, 12 -> out.add(left(), right())
            4, 11 -> out.add(right(), top())
            6, 9 -> out.add(bottom(), top())
            7, 8 -> out.add(left(), top())
            // saddles: same choice as MarchingSquares (one segment)
            5 -> out.add(bottom(), top())
            10 -> out.add(left(), right())
        }
    }

    /** Edge crossing between grid points (x1, y1) and (x2, y2), where x1 <= x2 and y1 <= y2 (canonical direction). */
    private fun point(x1: Int, y1: Int, x2: Int, y2: Int, v1: Double, v2: Double, level: Int): DoublePoint {
        val t = (level - v1) / (v2 - v1)
        return DoublePoint((x1 + t * (x2 - x1)) * factor, (y1 + t * (y2 - y1)) * factor)
    }

    private class SegmentList {
        var points = arrayOfNulls<DoublePoint>(64)
        var count = 0

        fun add(a: DoublePoint, b: DoublePoint) {
            if (a == b) return
            if (count + 2 > points.size) points = points.copyOf(points.size * 2)
            points[count++] = a
            points[count++] = b
        }
    }

    /** Chains segments into polylines: open chains start at degree-1 points, the rest are closed rings. */
    private fun chain(list: SegmentList): List<List<DoublePoint>> {
        val n = list.count / 2
        val pts = list.points
        val byPoint = HashMap<DoublePoint, IntArray>(n * 2)
        fun link(p: DoublePoint, segment: Int) {
            val existing = byPoint[p]
            byPoint[p] = if (existing == null) intArrayOf(segment) else existing + segment
        }
        for (s in 0..<n) {
            link(pts[2 * s]!!, s)
            link(pts[2 * s + 1]!!, s)
        }
        val used = BooleanArray(n)
        val lines = mutableListOf<List<DoublePoint>>()

        fun walk(startSegment: Int, startPoint: DoublePoint): MutableList<DoublePoint> {
            val line = mutableListOf(startPoint)
            var segment = startSegment
            var point = startPoint
            while (true) {
                used[segment] = true
                val a = pts[2 * segment]!!
                val b = pts[2 * segment + 1]!!
                point = if (a == point) b else a
                line.add(point)
                val next = byPoint[point]?.firstOrNull { !used[it] } ?: break
                segment = next
            }
            return line
        }

        // open lines from endpoints first
        for ((p, segs) in byPoint) {
            if (segs.size == 1 && !used[segs[0]]) {
                lines.add(walk(segs[0], p))
            }
        }
        for (s in 0..<n) {
            if (!used[s]) {
                val line = walk(s, pts[2 * s]!!)
                lines.add(line)
            }
        }
        return lines
    }

    companion object {
        fun levels(min: Double, max: Double, interval: Int, filter: (Int) -> Boolean): IntArray {
            val lower = Math.floorDiv(Math.floor(min).toInt(), interval) * interval
            val upper = Math.floorDiv(Math.ceil(max).toInt(), interval) * interval
            return (lower..upper step interval).filter { it != 0 && filter(it) }.toIntArray()
        }
    }
}
