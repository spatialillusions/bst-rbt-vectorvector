package com.greensopinion.vectorvector.pmtiles

import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class Compression(val code: Int) {
    UNKNOWN(0), NONE(1), GZIP(2), BROTLI(3), ZSTD(4);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

enum class TileType(val code: Int) {
    UNKNOWN(0), MVT(1), PNG(2), JPEG(3), WEBP(4), AVIF(5);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

/** PMTiles v3 header, see https://github.com/protomaps/PMTiles/blob/main/spec/v3/spec.md */
data class PmtilesHeader(
    val rootDirectoryOffset: Long,
    val rootDirectoryLength: Long,
    val metadataOffset: Long,
    val metadataLength: Long,
    val leafDirectoriesOffset: Long,
    val leafDirectoriesLength: Long,
    val tileDataOffset: Long,
    val tileDataLength: Long,
    val addressedTilesCount: Long,
    val tileEntriesCount: Long,
    val tileContentsCount: Long,
    val clustered: Boolean,
    val internalCompression: Compression,
    val tileCompression: Compression,
    val tileType: TileType,
    val minZoom: Int,
    val maxZoom: Int,
    val minLon: Double,
    val minLat: Double,
    val maxLon: Double,
    val maxLat: Double,
    val centerZoom: Int,
    val centerLon: Double,
    val centerLat: Double
) {
    companion object {
        const val LENGTH = 127

        fun parse(bytes: ByteArray): PmtilesHeader {
            require(bytes.size >= LENGTH) { "header too short: ${bytes.size} bytes" }
            val magic = String(bytes, 0, 7, Charsets.US_ASCII)
            require(magic == "PMTiles") { "not a PMTiles archive (magic '$magic')" }
            val version = bytes[7].toInt()
            require(version == 3) { "unsupported PMTiles version $version, expected 3" }
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            fun u8(offset: Int) = bytes[offset].toInt() and 0xFF
            fun e7(offset: Int) = b.getInt(offset) / 10_000_000.0
            return PmtilesHeader(
                rootDirectoryOffset = b.getLong(8),
                rootDirectoryLength = b.getLong(16),
                metadataOffset = b.getLong(24),
                metadataLength = b.getLong(32),
                leafDirectoriesOffset = b.getLong(40),
                leafDirectoriesLength = b.getLong(48),
                tileDataOffset = b.getLong(56),
                tileDataLength = b.getLong(64),
                addressedTilesCount = b.getLong(72),
                tileEntriesCount = b.getLong(80),
                tileContentsCount = b.getLong(88),
                clustered = u8(96) == 1,
                internalCompression = Compression.of(u8(97)),
                tileCompression = Compression.of(u8(98)),
                tileType = TileType.of(u8(99)),
                minZoom = u8(100),
                maxZoom = u8(101),
                minLon = e7(102),
                minLat = e7(106),
                maxLon = e7(110),
                maxLat = e7(114),
                centerZoom = u8(118),
                centerLon = e7(119),
                centerLat = e7(123)
            )
        }
    }
}
