package com.greensopinion.vectorvector.osm

import com.greensopinion.vectorvector.util.budgetedCache
import com.google.common.cache.CacheLoader
import com.greensopinion.vectorvector.TileId
import com.greensopinion.vectorvector.WebMercator
import com.greensopinion.vectorvector.lonLatBounds
import org.locationtech.jts.geom.CoordinateSequence
import org.locationtech.jts.geom.CoordinateSequenceFilter
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.Polygonal
import org.locationtech.jts.geom.util.AffineTransformation
import org.locationtech.jts.index.strtree.STRtree
import org.locationtech.jts.operation.overlayng.OverlayNG
import org.locationtech.jts.operation.overlayng.OverlayNGRobust
import org.locationtech.jts.operation.union.UnaryUnionOp
import java.util.Optional

/**
 * Glacier polygons in lon/lat with a spatial index, plus per-tile glacier geometry in tile coordinates.
 *
 * Per-tile geometry is the **union** of all glaciers touching the buffered tile, so overlapping glaciers
 * never cancel out. Clipping is hierarchical: a tile's lon/lat geometry is clipped from its parent's
 * (cached), so huge glaciers like Vatnajökull are only intersected with small geometries at high zooms.
 */
class GlacierIndex @JvmOverloads constructor(
    glaciers: List<Glacier>,
    private val extent: Int = 4096,
    /** Buffer around each tile, in tile units; must exceed the contour clip buffer. */
    private val bufferUnits: Int = 256,
    /** Zoom where clipping starts from the full index instead of a parent tile. */
    private val baseZoom: Int = 6,
    cacheSize: Long = 4000,
    private val factory: GeometryFactory = GeometryFactory(),
    cacheBytes: Long = 0,
    private val preparedRoot: Pair<TileId, Geometry?>? = null
) {
    private val tree = STRtree().also { tree ->
        glaciers.forEach { tree.insert(it.geometry.envelopeInternal, it.geometry) }
        tree.build()
    }
    val size = glaciers.size

    /** Approximate total area in km² (overlaps counted once). */
    val areaKm2: Double by lazy {
        if (glaciers.isEmpty()) 0.0 else areaKm2(UnaryUnionOp.union(glaciers.map { it.geometry }))
    }

    private val lonLatCache = budgetedCache<TileId, Optional<Geometry>>(cacheSize, cacheBytes) { it.orElse(null)?.let { g -> g.numPoints * 48L + 1024 } ?: 64L }
        .build(object : CacheLoader<TileId, Optional<Geometry>>() {
            override fun load(key: TileId): Optional<Geometry> = Optional.ofNullable(computeLonLat(key))
        })

    /** The glacier area within the buffered tile, in lon/lat, or null if there is none. */
    fun lonLatForTile(tile: TileId): Geometry? {
        // The prepared store already owns this mask. Charging it again to the
        // child cache can evict useful ancestors and repeatedly redo overlays.
        if (preparedRoot != null && tile == preparedRoot.first) return preparedRoot.second
        return lonLatCache.get(tile).orElse(null)
    }

    /** The glacier area within the buffered tile in tile coordinates (y down, [extent] units), or null. */
    fun forTile(tile: TileId): Geometry? {
        val g = lonLatForTile(tile) ?: return null
        val projected = g.copy()
        val z = tile.z
        projected.apply(object : CoordinateSequenceFilter {
            override fun filter(seq: CoordinateSequence, i: Int) {
                seq.setOrdinate(i, 0, (WebMercator.lonToTileX(seq.getX(i), z) - tile.x) * extent)
                seq.setOrdinate(i, 1, (WebMercator.latToTileY(seq.getY(i), z) - tile.y) * extent)
            }

            override fun isDone() = false
            override fun isGeometryChanged() = true
        })
        projected.geometryChanged()
        return projected
    }

    private fun bufferedEnvelope(tile: TileId): Envelope {
        val b = tile.lonLatBounds()
        val margin = bufferUnits.toDouble() / extent
        val minLon = WebMercator.tileXToLon(tile.x - margin, tile.z)
        val maxLon = WebMercator.tileXToLon(tile.x + 1 + margin, tile.z)
        val maxLat = WebMercator.tileYToLat(tile.y - margin, tile.z)
        val minLat = WebMercator.tileYToLat(tile.y + 1 + margin, tile.z)
        return Envelope(minOf(minLon, b.minLon), maxOf(maxLon, b.maxLon), minOf(minLat, b.minLat), maxOf(maxLat, b.maxLat))
    }

    private fun computeLonLat(tile: TileId): Geometry? {
        if (tile.z <= baseZoom && preparedRoot != null) {
            require(tile == preparedRoot.first) { "Prepared glacier root mismatch: $tile" }
            return preparedRoot.second
        }
        val envelope = bufferedEnvelope(tile)
        val source: Geometry = if (tile.z > baseZoom) {
            lonLatForTile(TileId(tile.z - 1, tile.x / 2, tile.y / 2)) ?: return null
        } else {
            val clip = factory.toGeometry(envelope)
            @Suppress("UNCHECKED_CAST")
            val candidates = longitudeQueries(envelope).flatMap { (query, shift) ->
                val queryClip = factory.toGeometry(query)
                (tree.query(query) as List<Geometry>).mapNotNull { geometry ->
                    // Clip before unioning: overlapping continent-sized ice sheets must
                    // not be unioned in full whenever a bounded cache reloads a root.
                    val local = if (query.contains(geometry.envelopeInternal)) geometry else
                        polygonal(OverlayNGRobust.overlay(geometry, queryClip, OverlayNG.INTERSECTION)) ?: return@mapNotNull null
                    if (shift == 0.0) local else AffineTransformation.translationInstance(shift, 0.0).transform(local)
                }
            }
            if (candidates.isEmpty()) return null
            // Wrapped query strips and the normal query are all within this clip.
            val union = OverlayNGRobust.union(candidates) ?: return null
            return polygonal(if (envelope.contains(union.envelopeInternal)) union else
                OverlayNGRobust.overlay(union, clip, OverlayNG.INTERSECTION))
        }
        if (!source.envelopeInternal.intersects(envelope)) return null
        if (envelope.contains(source.envelopeInternal)) return source
        val clipped = OverlayNGRobust.overlay(source, factory.toGeometry(envelope), OverlayNG.INTERSECTION)
        return polygonal(clipped)
    }

    private fun polygonal(g: Geometry): Geometry? {
        if (g.isEmpty) return null
        if (g is Polygonal) return g
        val parts = (0..<g.numGeometries).map { g.getGeometryN(it) }.filterIsInstance<Polygon>()
        return if (parts.isEmpty()) null else factory.buildGeometry(parts)
    }

    companion object {
        /** Spherical area approximation, good enough for sanity logging. */
        fun areaKm2(g: Geometry): Double {
            val projected = g.copy()
            projected.apply(object : CoordinateSequenceFilter {
                override fun filter(seq: CoordinateSequence, i: Int) {
                    val lat = Math.toRadians(seq.getY(i))
                    seq.setOrdinate(i, 0, Math.toRadians(seq.getX(i)) * 6371.0)
                    seq.setOrdinate(i, 1, Math.sin(lat) * 6371.0)
                }

                override fun isDone() = false
                override fun isGeometryChanged() = true
            })
            return projected.area
        }
    }
}
