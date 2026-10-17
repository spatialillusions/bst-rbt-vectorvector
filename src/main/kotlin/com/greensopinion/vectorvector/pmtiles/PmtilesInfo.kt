package com.greensopinion.vectorvector.pmtiles

import java.security.MessageDigest

/**
 * Prints a PMTiles header and optionally a tile digest.
 * Usage: PmtilesInfoKt <path-or-url> [z/x/y ...]
 */
fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: <path-or-url> [z/x/y ...]" }
    PmtilesReader(rangeSourceFor(args[0])).use { reader ->
        val h = reader.header
        println("${reader.name}: tileType=${h.tileType} minZoom=${h.minZoom} maxZoom=${h.maxZoom} " +
                "bounds=${h.minLon},${h.minLat},${h.maxLon},${h.maxLat} internal=${h.internalCompression} " +
                "tile=${h.tileCompression} entries=${h.tileEntriesCount} root=${h.rootDirectoryLength}B")
        for (zxy in args.drop(1)) {
            val (z, x, y) = zxy.split("/").map { it.toInt() }
            val bytes = reader.getTile(z, x, y)
            val digest = bytes?.let { b ->
                MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }.take(16)
            }
            println("  $zxy: ${bytes?.size ?: "missing"} bytes sha256=$digest")
        }
    }
}
