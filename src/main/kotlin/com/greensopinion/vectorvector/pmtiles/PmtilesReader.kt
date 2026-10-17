package com.greensopinion.vectorvector.pmtiles

import com.google.common.cache.CacheBuilder
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.util.zip.GZIPInputStream

/** Archive-relative payload identity. Equal references contain exactly the same encoded tile. */
data class PmtilesTileRef(val offset: Long, val length: Int)

/**
 * Reads tiles from a PMTiles v3 archive. Thread-safe: directories are cached in a concurrent LRU.
 *
 * @param source used for header and directory reads
 * @param tileSource used for tile payload reads, e.g. a disk-caching wrapper of [source]
 */
class PmtilesReader(
    private val source: RangeSource,
    private val tileSource: RangeSource = source
) : Closeable {
    val name: String get() = source.name
    val header: PmtilesHeader
    private val rootDirectory: List<Entry>

    private val leafCache = CacheBuilder.newBuilder()
        .maximumSize(256)
        .build<Pair<Long, Int>, List<Entry>>()

    init {
        val initial = source.read(0, INITIAL_READ)
        header = PmtilesHeader.parse(initial)
        val rootEnd = header.rootDirectoryOffset + header.rootDirectoryLength
        val rootBytes = if (rootEnd <= initial.size) {
            initial.copyOfRange(header.rootDirectoryOffset.toInt(), rootEnd.toInt())
        } else {
            source.read(header.rootDirectoryOffset, header.rootDirectoryLength.toInt())
        }
        rootDirectory = PmtilesDirectory.deserialize(decompress(rootBytes, header.internalCompression))
    }

    /** Returns the (decompressed) tile bytes, or null if the archive has no such tile. */
    fun getTile(z: Int, x: Int, y: Int): ByteArray? = locateTile(z, x, y)?.let(::readTile)

    fun readTile(reference: PmtilesTileRef): ByteArray =
        decompress(tileSource.read(reference.offset, reference.length), header.tileCompression)

    /** Lookup without reading the payload, allowing shared sea tiles to decode only once. */
    fun locateTile(z: Int, x: Int, y: Int): PmtilesTileRef? {
        if (z < header.minZoom || z > header.maxZoom) {
            return null
        }
        val tileId = TileIds.zxyToTileId(z, x, y)
        var directory = rootDirectory
        for (depth in 0..3) {
            val entry = PmtilesDirectory.find(directory, tileId) ?: return null
            if (entry.runLength > 0) {
                return PmtilesTileRef(header.tileDataOffset + entry.offset, entry.length)
            }
            directory = leafDirectory(entry)
        }
        throw IllegalStateException("$name: directory nesting deeper than 4 levels for tile $z/$x/$y")
    }

    private fun leafDirectory(entry: Entry): List<Entry> =
        leafCache.get(Pair(entry.offset, entry.length)) {
            val bytes = source.read(header.leafDirectoriesOffset + entry.offset, entry.length)
            PmtilesDirectory.deserialize(decompress(bytes, header.internalCompression))
        }

    private fun decompress(bytes: ByteArray, compression: Compression): ByteArray = when (compression) {
        Compression.NONE, Compression.UNKNOWN -> bytes
        Compression.GZIP -> GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readAllBytes() }
        else -> throw UnsupportedOperationException("$name: $compression compression is not supported, only gzip or none")
    }

    override fun close() {
        source.close()
        if (tileSource !== source) {
            tileSource.close()
        }
    }

    companion object {
        const val INITIAL_READ = 16384
    }
}
