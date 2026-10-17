package com.greensopinion.vectorvector.elevation.mapterhorn

import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.greensopinion.vectorvector.ElevationTile
import com.greensopinion.vectorvector.EmptyTile
import com.greensopinion.vectorvector.LonLatBounds
import com.greensopinion.vectorvector.TileId
import com.greensopinion.vectorvector.elevation.ElevationDataStore
import com.greensopinion.vectorvector.lonLatBounds
import com.greensopinion.vectorvector.metrics.MetricsProvider
import com.greensopinion.vectorvector.pmtiles.PmtilesReader
import com.greensopinion.vectorvector.pmtiles.PmtilesTileRef
import java.io.Closeable
import java.util.Optional

/**
 * Routes tile requests to the PMTiles archives that hold them, e.g. the planet (z0–12) plus
 * high-zoom `6-x-y` archives. Archives are tried highest max zoom first; the first one that has the tile wins.
 */
class MapterhornSourceSet(readers: List<PmtilesReader>, cacheSize: Long = 128) : Closeable {
    private val readers = readers.sortedByDescending { it.header.maxZoom }
    private val archiveBounds = this.readers.associateWith {
        LonLatBounds(it.header.minLon, it.header.minLat, it.header.maxLon, it.header.maxLat)
    }
    private val decoded = CacheBuilder.newBuilder().maximumSize(cacheSize)
        .build<Pair<PmtilesReader, PmtilesTileRef>, ElevationGrid>()

    fun decoded(z: Int, x: Int, y: Int): ElevationGrid? {
        val (reader, reference) = locate(z, x, y) ?: return null
        return decoded.get(reader to reference) { TerrariumDecoder.decode(reader.readTile(reference)) }
    }

    /** The encoded tile bytes, or null if no archive has the tile. */
    fun fetch(z: Int, x: Int, y: Int): ByteArray? {
        val (reader, reference) = locate(z, x, y) ?: return null
        return reader.readTile(reference)
    }

    private fun locate(z: Int, x: Int, y: Int): Pair<PmtilesReader, PmtilesTileRef>? {
        val n = 1 shl z
        if (x !in 0..<n || y !in 0..<n) {
            return null
        }
        val tileBounds by lazy { TileId(z, x, y).lonLatBounds() }
        for (reader in readers) {
            if (z < reader.header.minZoom || z > reader.header.maxZoom) {
                continue
            }
            if (!archiveBounds.getValue(reader).intersects(tileBounds)) {
                continue
            }
            reader.locateTile(z, x, y)?.let { return reader to it }
        }
        return null
    }

    override fun close() {
        readers.forEach { it.close() }
    }
}

/**
 * Elevation from Mapterhorn terrarium tiles (512 px).
 *
 * * [extent] 512: output tile z/x/y is Mapterhorn tile z/x/y.
 * * [extent] 256: output tile z/x/y is the exact quadrant of Mapterhorn tile (z-1)/(x/2)/(y/2), no resampling.
 *
 * Missing tiles fall back to an ancestor (up to [maxOverzoom] levels), bilinearly upsampled.
 * Pixel registration: terrarium pixels are cell-centred, while the contour code treats index i as position i,
 * a half-pixel shift (≤ 4 m at z12 in Iceland) that is accepted for now.
 */
class MapterhornElevationDataStore(
    private val sources: MapterhornSourceSet,
    private val extent: Int,
    private val metricsProvider: MetricsProvider,
    private val maxOverzoom: Int = 4,
    cacheSize: Long = 1500
) : ElevationDataStore, Closeable {
    private val nativeSize = 512

    init {
        require(extent == 256 || extent == 512) { "extent must be 256 or 512" }
    }

    /** Decoded native tiles, keyed by Mapterhorn z/x/y, so neighbours read via ElevationTileArea decode once. */
    private val nativeCache = CacheBuilder.newBuilder()
        .maximumSize(cacheSize)
        .build(object : CacheLoader<TileId, Optional<ElevationGrid>>() {
            override fun load(key: TileId): Optional<ElevationGrid> {
                val grid = sources.decoded(key.z, key.x, key.y) ?: return Optional.empty()
                metricsProvider.get().addCount("Mapterhorn.fetched")
                return Optional.of(grid.also {
                    require(it.size == nativeSize) { "expected $nativeSize px Mapterhorn tiles but $key has ${it.size}" }
                })
            }
        })

    private val tileCache = CacheBuilder.newBuilder()
        .maximumSize(cacheSize)
        .build(object : CacheLoader<TileId, ElevationTile>() {
            override fun load(key: TileId): ElevationTile = loadTile(key)
        })

    override fun get(tile: TileId): ElevationTile {
        val n = 1 shl tile.z
        if (tile.y !in 0..<n) {
            return EmptyTile(extent)
        }
        // Buffered contour kernels cross the antimeridian. Longitude is periodic;
        // latitude stops at the Web Mercator limits and must never wrap.
        return tileCache.get(if (tile.x in 0..<n) tile else tile.copy(x = Math.floorMod(tile.x, n)))
    }

    private fun loadTile(tile: TileId): ElevationTile {
        val grid = if (extent == nativeSize) {
            grid(tile)
        } else if (tile.z == 0) {
            grid(tile)?.resample(0, 0, nativeSize, extent)
        } else {
            val half = nativeSize / 2
            grid(TileId(tile.z - 1, tile.x / 2, tile.y / 2))?.crop((tile.x % 2) * half, (tile.y % 2) * half, half)
        }
        return grid?.toTile() ?: EmptyTile(extent)
    }

    /** The 512 px grid for Mapterhorn tile [tile], overzoomed from an ancestor if needed. */
    fun grid(tile: TileId): ElevationGrid? {
        nativeCache.get(tile).orElse(null)?.let { return it }
        for (levels in 1..minOf(maxOverzoom, tile.z)) {
            val ancestor = TileId(tile.z - levels, tile.x shr levels, tile.y shr levels)
            val grid = nativeCache.get(ancestor).orElse(null) ?: continue
            metricsProvider.get().addCount("Mapterhorn.overzoom")
            val regionSize = nativeSize shr levels
            val mask = (1 shl levels) - 1
            return grid.resample((tile.x and mask) * regionSize, (tile.y and mask) * regionSize, regionSize, nativeSize)
        }
        return null
    }

    override fun close() {
        sources.close()
    }
}
