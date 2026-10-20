package com.greensopinion.vectorvector.sink.contour

import kotlin.math.roundToInt

val Line.isClosed: Boolean get() = points.size > 2 && points.first() == points.last()

/**
 * Chaikin corner cutting. Open lines keep their endpoints fixed, so lines still meet the
 * neighbouring tile's lines; closed rings (first == last) are smoothed cyclically and stay closed.
 */
object Chaikin {
    fun smooth(line: Line, iterations: Int): Line {
        var points = line.points
        repeat(iterations) {
            if (points.size < 3) {
                return Line(points)
            }
            points = if (points.first() == points.last()) closed(points) else open(points)
        }
        return Line(points)
    }

    private fun open(points: List<DoublePoint>): List<DoublePoint> {
        val out = ArrayList<DoublePoint>(points.size * 2)
        out.add(points.first())
        for (i in 0..<points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            out.add(a * 0.75 + b * 0.25)
            out.add(a * 0.25 + b * 0.75)
        }
        out.add(points.last())
        return out
    }

    private fun closed(points: List<DoublePoint>): List<DoublePoint> {
        val ring = points.dropLast(1)
        val out = ArrayList<DoublePoint>(ring.size * 2 + 1)
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[(i + 1) % ring.size]
            out.add(a * 0.75 + b * 0.25)
            out.add(a * 0.25 + b * 0.75)
        }
        out.add(out.first())
        return out
    }
}

/** Clips lines to an axis-aligned box (Liang–Barsky per segment). A line leaving and re-entering yields several parts. */
class BoxClipper(private val min: Double, private val max: Double) {
    fun clip(line: Line): List<Line> {
        val parts = mutableListOf<Line>()
        var current = mutableListOf<DoublePoint>()
        for (i in 0..<line.points.size - 1) {
            val a = line.points[i]
            val b = line.points[i + 1]
            val segment = clipSegment(a, b)
            if (segment == null) {
                if (current.size > 1) parts.add(Line(current))
                current = mutableListOf()
                continue
            }
            val (p, q) = segment
            if (current.isEmpty() || current.last() != p) {
                if (current.size > 1) parts.add(Line(current))
                current = mutableListOf(p)
            }
            current.add(q)
            if (q != b) {
                parts.add(Line(current))
                current = mutableListOf()
            }
        }
        if (current.size > 1) parts.add(Line(current))
        return parts
    }

    private fun clipSegment(a: DoublePoint, b: DoublePoint): Pair<DoublePoint, DoublePoint>? {
        var t0 = 0.0
        var t1 = 1.0
        val dx = b.x - a.x
        val dy = b.y - a.y
        val p = doubleArrayOf(-dx, dx, -dy, dy)
        val q = doubleArrayOf(a.x - min, max - a.x, a.y - min, max - a.y)
        for (i in 0..3) {
            if (p[i] == 0.0) {
                if (q[i] < 0) return null
            } else {
                val t = q[i] / p[i]
                if (p[i] < 0) {
                    if (t > t1) return null
                    if (t > t0) t0 = t
                } else {
                    if (t < t0) return null
                    if (t < t1) t1 = t
                }
            }
        }
        val start = if (t0 == 0.0) a else DoublePoint(a.x + t0 * dx, a.y + t0 * dy)
        val end = if (t1 == 1.0) b else DoublePoint(a.x + t1 * dx, a.y + t1 * dy)
        return Pair(start, end)
    }
}

/** Rounds to integer tile coordinates and removes consecutive duplicates; null if fewer than 2 points remain. */
fun Line.roundToGrid(): Line? {
    val out = ArrayList<DoublePoint>(points.size)
    for (p in points) {
        val r = DoublePoint(p.x.roundToInt().toDouble(), p.y.roundToInt().toDouble())
        if (out.isEmpty() || out.last() != r) {
            out.add(r)
        }
    }
    return if (out.size >= 2) Line(out) else null
}

/**
 * Ramer-Douglas-Peucker with the same result as [LineSimplifier] (distance to the infinite line through the
 * segment endpoints, or to the start point when the endpoints coincide), but index based: no list copies and
 * one square root per segment instead of one per point.
 */
object Rdp {
    fun simplify(line: Line, epsilon: Double): Line {
        val points = line.points
        if (points.size <= 2 || epsilon <= 0) return line
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.size - 1] = true
        val stack = ArrayDeque<Long>()
        stack.addLast(pack(0, points.size - 1))
        while (stack.isNotEmpty()) {
            val range = stack.removeLast()
            val first = (range ushr 32).toInt()
            val last = range.toInt()
            if (last - first < 2) continue
            val a = points[first]
            val b = points[last]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val degenerate = a == b
            val c = a.x * b.y - b.x * a.y
            var maxDistance = 0.0
            var index = first
            for (i in first + 1..<last) {
                val p = points[i]
                val d = if (degenerate) Math.hypot(p.x - a.x, p.y - a.y) else Math.abs(-dy * p.x + dx * p.y + c)
                if (d > maxDistance) {
                    maxDistance = d
                    index = i
                }
            }
            val distance = if (degenerate) maxDistance else maxDistance / Math.hypot(-dy, dx)
            if (distance > epsilon) {
                keep[index] = true
                stack.addLast(pack(first, index))
                stack.addLast(pack(index, last))
            }
        }
        val out = ArrayList<DoublePoint>()
        for (i in points.indices) if (keep[i]) out.add(points[i])
        return Line(out)
    }

    private fun pack(first: Int, last: Int): Long = (first.toLong() shl 32) or (last.toLong() and 0xFFFFFFFFL)
}
