package com.greensopinion.vectorvector.sink.contour

/** Preserve the old Chaikin/RDP arithmetic and ordering without intermediate point objects. */
internal object PrimitiveLineOperations {
    private fun same(ax: Double, ay: Double, bx: Double, by: Double) =
        ax.toBits() == bx.toBits() && ay.toBits() == by.toBits()

    fun process(line: Line, iterations: Int, epsilon: Double): Line {
        if (iterations == 0 && (!(epsilon > 0) || line.points.size <= 2)) return line
        var data = DoubleArray(line.points.size * 2)
        line.points.forEachIndexed { i, p -> data[2 * i] = p.x; data[2 * i + 1] = p.y }
        repeat(iterations) {
            val count = data.size / 2
            if (count >= 3) {
                val closed = same(data[0], data[1], data[data.size - 2], data.last())
                val ring = if (closed) count - 1 else count
                val segments = if (closed) ring else count - 1
                val output = DoubleArray((if (closed) 2 * ring + 1 else 2 * count) * 2)
                var offset = 0
                if (!closed) { output[offset++] = data[0]; output[offset++] = data[1] }
                for (i in 0 until segments) {
                    val next = (i + 1) % ring
                    val ax = data[2 * i]; val ay = data[2 * i + 1]
                    val bx = data[2 * next]; val by = data[2 * next + 1]
                    output[offset++] = ax * 0.75 + bx * 0.25
                    output[offset++] = ay * 0.75 + by * 0.25
                    output[offset++] = ax * 0.25 + bx * 0.75
                    output[offset++] = ay * 0.25 + by * 0.75
                }
                output[offset++] = if (closed) output[0] else data[data.size - 2]
                output[offset] = if (closed) output[1] else data.last()
                data = output
            }
        }
        val count = data.size / 2
        val keep = BooleanArray(count) { !(epsilon > 0) || count <= 2 }
        if (count > 2 && epsilon > 0) {
            keep[0] = true; keep[count - 1] = true
            var stack = LongArray(64)
            var size = 0
            fun push(first: Int, last: Int) {
                if (size == stack.size) stack = stack.copyOf(stack.size * 2)
                stack[size++] = (first.toLong() shl 32) or (last.toLong() and 0xffffffffL)
            }
            push(0, count - 1)
            while (size > 0) {
                val range = stack[--size]
                val first = (range ushr 32).toInt(); val last = range.toInt()
                if (last - first < 2) continue
                val ax = data[2 * first]; val ay = data[2 * first + 1]
                val bx = data[2 * last]; val by = data[2 * last + 1]
                val dx = bx - ax; val dy = by - ay
                val degenerate = same(ax, ay, bx, by)
                val c = ax * by - bx * ay
                var maximum = 0.0; var index = first
                for (i in first + 1 until last) {
                    val px = data[2 * i]; val py = data[2 * i + 1]
                    val distance = if (degenerate) Math.hypot(px - ax, py - ay) else
                        Math.abs(-dy * px + dx * py + c)
                    if (distance > maximum) { maximum = distance; index = i }
                }
                val distance = if (degenerate) maximum else maximum / Math.hypot(-dy, dx)
                if (distance > epsilon) {
                    keep[index] = true
                    push(first, index); push(index, last)
                }
            }
        }
        val output = ArrayList<DoublePoint>()
        for (i in 0 until count) if (keep[i]) output.add(DoublePoint(data[2 * i], data[2 * i + 1]))
        return Line(output)
    }
}
