package com.greensopinion.vectorvector.osm

import io.github.oshai.kotlinlogging.KotlinLogging
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.geom.Polygon
import org.locationtech.jts.geom.Polygonal
import org.locationtech.jts.geom.util.GeometryFixer
import org.locationtech.jts.operation.linemerge.LineMerger
import org.locationtech.jts.operation.overlayng.OverlayNGRobust
import org.locationtech.jts.operation.overlayng.OverlayNG
import org.locationtech.jts.operation.union.UnaryUnionOp
import java.io.File
import java.util.Arrays

private val log = KotlinLogging.logger { }

/** A glacier feature in EPSG:4326 lon/lat, already valid. */
data class Glacier(val osmId: String, val geometry: Geometry)

/**
 * Loads `natural=glacier` closed ways and multipolygon relations from an OSM PBF file.
 * Normally three passes (relations+tagged ways, member ways, nodes); nested relation discovery
 * uses additional passes, retaining only the referenced relations rather than every OSM relation.
 *
 * Overlaps never cancel out: every combination uses union semantics. Relation rings are assembled by
 * joining member ways end to end (no even-odd face extraction), each ring becomes a polygon that is fixed on
 * its own, outers are unioned, then the union of inners is subtracted.
 */
class GlacierLoader(private val file: File, private val factory: GeometryFactory = GeometryFactory()) {
    var brokenRelations = 0
        private set
    var openWays = 0
        private set

    fun load(): List<Glacier> {
        val taggedWays = mutableMapOf<Long, LongArray>()
        val relations = mutableMapOf<Long, List<RelationMember>>()
        OsmPbfScanner(file).scan(object : OsmHandler {
            override val wantsWays = true
            override val wantsRelations = true
            override fun way(id: Long, tags: Map<String, String>, nodes: LongArray) {
                if (tags["natural"] == "glacier") taggedWays[id] = nodes
            }

            override fun relation(id: Long, tags: Map<String, String>, members: List<RelationMember>) {
                if (tags["natural"] == "glacier" && (tags["type"] == "multipolygon" || tags["type"] == "boundary")) {
                    relations[id] = members
                }
            }
        })
        val glacierRelations = relations.keys.toList()
        val attempted = relations.keys.toMutableSet()
        var depth = 0
        while (depth++ < 64) {
            val needed = relations.values.asSequence().flatten().filter { it.type == MemberType.RELATION }
                .map { it.ref }.filter { it !in attempted }.toSet()
            if (needed.isEmpty()) break
            attempted += needed
            OsmPbfScanner(file).scan(object : OsmHandler {
                override val wantsRelations = true
                override fun relation(id: Long, tags: Map<String, String>, members: List<RelationMember>) {
                    if (id in needed && (tags["type"] == "multipolygon" || tags["type"] == "boundary")) relations[id] = members
                }
            })
        }
        val memberIds = relations.values.asSequence().flatten().filter { it.type == MemberType.WAY }
            .map { it.ref }.filter { it !in taggedWays }.toHashSet()
        val memberWays = mutableMapOf<Long, LongArray>()
        if (memberIds.isNotEmpty()) {
            OsmPbfScanner(file).scan(object : OsmHandler {
                override val wantsWays = true
                override fun way(id: Long, tags: Map<String, String>, nodes: LongArray) {
                    if (id in memberIds) memberWays[id] = nodes
                }
            })
        }
        val ways = taggedWays + memberWays
        val nodes = NodeLocations.load(file, ways.values)
        log.info { "${file.name}: ${taggedWays.size} glacier ways, ${glacierRelations.size} glacier relations, ${nodes.size} nodes" }

        val glaciers = mutableListOf<Glacier>()
        for ((id, wayNodes) in taggedWays) {
            if (wayNodes.size < 4 || wayNodes.first() != wayNodes.last()) {
                openWays += 1
                continue
            }
            val ring = nodes.lineString(wayNodes, factory) ?: continue
            polygon(ring)?.let { glaciers += Glacier("w$id", it) }
        }
        val resolved = mutableMapOf<Long, Geometry?>()
        fun resolve(id: Long, visiting: MutableSet<Long>): Geometry? {
            if (id in resolved) return resolved[id]
            require(visiting.size < 64 && visiting.add(id)) { "Cyclic or overly deep glacier relation $id" }
            try {
                val members = relations[id] ?: error("Missing or unsupported glacier member relation $id")
                fun lines(inner: Boolean) = members.filter { it.type == MemberType.WAY && (it.role == "inner") == inner }
                    .distinctBy { it.ref }
                    .map { member ->
                        ways[member.ref]?.let { nodes.lineString(it, factory) }
                            ?: error("Missing glacier member way/nodes ${member.ref}")
                    }
                fun nested(inner: Boolean) = members.filter { it.type == MemberType.RELATION && (it.role == "inner") == inner }
                    .distinctBy { it.ref }
                    .map { member -> resolve(member.ref, visiting) ?: error("Broken glacier member relation ${member.ref}") }
                val outer = rings(lines(false)) + nested(false)
                val inner = rings(lines(true)) + nested(true)
                val geometry = subtractInner(outer, inner)
                resolved[id] = geometry
                return geometry
            } finally {
                visiting.remove(id)
            }
        }
        for (id in glacierRelations) {
            val geometry = try {
                resolve(id, mutableSetOf())
            } catch (e: Exception) {
                log.warn { "glacier relation $id: ${e.message}" }
                null
            }
            if (geometry == null || geometry.isEmpty) {
                brokenRelations += 1
                continue
            }
            glaciers += Glacier("r$id", geometry)
        }
        if (openWays > 0 || brokenRelations > 0) {
            log.warn { "${file.name}: skipped $openWays unclosed glacier ways and $brokenRelations broken glacier relations" }
        }
        return glaciers
    }

    /**
     * Builds a multipolygon from outer and inner member ways: ways are joined end to end into rings, each
     * ring is fixed on its own, outers are unioned (overlapping outers stay covered), inners are subtracted.
     */
    fun multipolygon(outerWays: List<LineString>, innerWays: List<LineString>): Geometry? {
        return subtractInner(rings(outerWays), rings(innerWays))
    }

    private fun rings(ways: List<LineString>): List<Geometry> {
        val merger = LineMerger()
        ways.forEach { merger.add(it) }
        @Suppress("UNCHECKED_CAST")
        val merged = merger.mergedLineStrings as Collection<LineString>
        return merged.mapNotNull { polygon(it) }
    }

    private fun subtractInner(outers: List<Geometry>, inners: List<Geometry>): Geometry? {
        if (outers.isEmpty()) {
            return null
        }
        val outer = UnaryUnionOp.union(outers)
        val result = if (inners.isEmpty()) outer else OverlayNGRobust.overlay(outer, UnaryUnionOp.union(inners), OverlayNG.DIFFERENCE)
        return polygonalOnly(result)
    }

    /** A valid polygonal geometry from a closed ring; self-overlaps are fixed with union semantics. */
    fun polygon(ring: LineString): Geometry? {
        if (!ring.isClosed || ring.numPoints < 4) return null
        val polygon = factory.createPolygon(ring.coordinates)
        val fixed = if (polygon.isValid) polygon else GeometryFixer.fix(polygon)
        return polygonalOnly(fixed)
    }

    private fun polygonalOnly(g: Geometry): Geometry? {
        if (g is Polygonal) return if (g.isEmpty) null else g
        val parts = (0..<g.numGeometries).map { g.getGeometryN(it) }.filterIsInstance<Polygon>()
        return if (parts.isEmpty()) null else factory.buildGeometry(parts)
    }
}

/** Locations of a set of node ids: sorted ids with packed E7 coordinates. */
class NodeLocations private constructor(private val ids: LongArray, private val lons: IntArray, private val lats: IntArray) {
    val size: Int get() = ids.size

    fun coordinate(id: Long): Coordinate? {
        val i = Arrays.binarySearch(ids, id)
        if (i < 0 || lons[i] == Int.MIN_VALUE) return null
        return Coordinate(lons[i] / 1e7, lats[i] / 1e7)
    }

    fun lineString(nodes: LongArray, factory: GeometryFactory): LineString? {
        val coordinates = nodes.map { coordinate(it) ?: return null }
        return if (coordinates.size >= 2) factory.createLineString(coordinates.toTypedArray()) else null
    }

    companion object {
        fun load(file: File, ways: Collection<LongArray>): NodeLocations {
            // Avoid boxed Long lists/sets for tens of millions of glacier-node references.
            val count = ways.sumOf { it.size.toLong() }
            require(count <= Int.MAX_VALUE) { "Too many glacier references; preprocess OSM into regional extracts" }
            val packed = LongArray(count.toInt())
            var offset = 0
            for (way in ways) {
                way.copyInto(packed, offset)
                offset += way.size
            }
            packed.sort()
            var unique = 0
            for (id in packed) {
                if (unique == 0 || id != packed[unique - 1]) packed[unique++] = id
            }
            val ids = packed.copyOf(unique)
            val lons = IntArray(ids.size) { Int.MIN_VALUE }
            val lats = IntArray(ids.size)
            if (ids.isNotEmpty()) {
                OsmPbfScanner(file).scan(object : OsmHandler {
                    override val wantsNodes = true
                    override fun wantsNode(id: Long, tags: Map<String, String>) = Arrays.binarySearch(ids, id) >= 0
                    override fun node(id: Long, lon: Double, lat: Double, tags: Map<String, String>) {
                        val i = Arrays.binarySearch(ids, id)
                        lons[i] = Math.round(lon * 1e7).toInt()
                        lats[i] = Math.round(lat * 1e7).toInt()
                    }
                })
            }
            return NodeLocations(ids, lons, lats)
        }
    }
}
