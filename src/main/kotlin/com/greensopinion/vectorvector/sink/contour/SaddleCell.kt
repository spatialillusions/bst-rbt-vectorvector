package com.greensopinion.vectorvector.sink.contour

/**
 * Resolve a bilinear saddle using the determinant of corner heights relative to the level.
 * Positive connects bottom-right / left-top; negative connects bottom-left / right-top.
 * An exact zero uses the first pairing consistently, including when heights are inverted.
 * Both pairings retain all four crossings as two independent segments.
 */
internal fun saddleBottomToRight(bl: Double, br: Double, tr: Double, tl: Double, level: Int): Boolean {
    val a = bl - level
    val b = br - level
    val c = tr - level
    val d = tl - level
    return a * c - b * d >= 0.0
}
