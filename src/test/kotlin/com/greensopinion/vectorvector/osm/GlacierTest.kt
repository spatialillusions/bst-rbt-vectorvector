package com.greensopinion.vectorvector.osm

import com.greensopinion.vectorvector.TileId
import com.greensopinion.vectorvector.WebMercator
import com.greensopinion.vectorvector.lonLatBounds
import com.greensopinion.vectorvector.sink.contour.DoublePoint
import com.greensopinion.vectorvector.sink.contour.Line
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.LineString
import org.locationtech.jts.operation.overlayng.OverlayNG
import org.locationtech.jts.operation.overlayng.OverlayNGRobust
import org.locationtech.jts.operation.union.UnaryUnionOp
import java.io.File

private val factory = GeometryFactory()

private fun ring(vararg xy: Double): LineString =
    factory.createLineString(xy.toList().chunked(2).map { Coordinate(it[0], it[1]) }.toTypedArray())

private fun square(x0: Double, y0: Double, x1: Double, y1: Double) = ring(x0, y0, x1, y0, x1, y1, x0, y1, x0, y0)

private fun horizontal(y: Double, x0: Double = 0.0, x1: Double = 4096.0) =
    Line(listOf(DoublePoint(x0, y), DoublePoint(x1, y)))

/** The ice/land sequence along a horizontal line, as (startX, endX, isIce) sorted by x. */
private fun sequence(pieces: List<Pair<Line, Boolean>>) =
    pieces.map { (l, ice) -> Triple(l.points.minOf { it.x }, l.points.maxOf { it.x }, ice) }.sortedBy { it.first }

class GlacierIceSplitterTest {
    private val glacier: Geometry = factory.createPolygon(square(1000.0, 1000.0, 2000.0, 2000.0).coordinates)

    @Test
    fun `a line crossing a glacier gets land, ice, land`() {
        val pieces = sequence(GlacierIceSplitter(glacier).split(horizontal(1500.0)))
        assertThat(pieces).containsExactly(
            Triple(0.0, 1000.0, false),
            Triple(1000.0, 2000.0, true),
            Triple(2000.0, 4096.0, false)
        )
    }

    @Test
    fun `lines fully inside or outside keep one piece`() {
        val inside = GlacierIceSplitter(glacier).split(horizontal(1500.0, 1100.0, 1900.0))
        assertThat(inside.single().second).isTrue()
        assertThat(inside.single().first.points).containsExactly(DoublePoint(1100.0, 1500.0), DoublePoint(1900.0, 1500.0))
        val outside = GlacierIceSplitter(glacier).split(horizontal(500.0))
        assertThat(outside.single().second).isFalse()
    }

    @Test
    fun `short grazing pieces are dropped`() {
        // touches the corner region for 2 units only
        val pieces = GlacierIceSplitter(glacier, minPieceLength = 4.0).split(Line(listOf(DoublePoint(0.0, 1001.0), DoublePoint(1002.0, 999.0))))
        assertThat(pieces.filter { it.second }).isEmpty()
    }

    @Test
    fun `self intersecting mask is repaired once and keeps both lobes`() {
        val broken = factory.createPolygon(ring(1000.0, 1000.0, 3000.0, 3000.0,
            1000.0, 3000.0, 3000.0, 1000.0, 1000.0, 1000.0).coordinates)
        assertThat(broken.isValid).isFalse()
        val splitter = GlacierIceSplitter(broken)
        for (y in listOf(1500.0, 2500.0, 1500.0)) {
            assertThat(sequence(splitter.split(horizontal(y)))).containsExactly(
                Triple(0.0, 1500.0, false), Triple(1500.0, 2500.0, true), Triple(2500.0, 4096.0, false))
        }
        assertThat(splitter.repairCount).isEqualTo(1)
    }

    @Test
    fun `repair preserves holes and all contour length`() {
        val broken = factory.createPolygon(
            factory.createLinearRing(ring(1000.0, 1000.0, 3000.0, 3000.0,
                1000.0, 3000.0, 3000.0, 1000.0, 1000.0, 1000.0).coordinates),
            arrayOf(factory.createLinearRing(square(1900.0, 2400.0, 2100.0, 2600.0).coordinates)))
        val splitter = GlacierIceSplitter(broken)
        val parts = sequence(splitter.split(horizontal(2500.0)))
        assertThat(parts).containsExactly(Triple(0.0, 1500.0, false), Triple(1500.0, 1900.0, true),
            Triple(1900.0, 2100.0, false), Triple(2100.0, 2500.0, true), Triple(2500.0, 4096.0, false))
        assertThat(parts.sumOf { it.second - it.first }).isEqualTo(4096.0)
        assertThat(splitter.repairCount).isEqualTo(1)
    }

    @Test
    fun `valid masks use original geometry without repair`() {
        val splitter = GlacierIceSplitter(glacier)
        for (y in listOf(0.0, 1500.0, 4096.0)) splitter.split(horizontal(y))
        assertThat(splitter.repairCount).isZero()
    }
}

class GlacierGeometryTest {
    private val loader = GlacierLoader(File("unused"))

    @Test
    fun `a relation with a hole gives ice, land, ice`() {
        val g = loader.multipolygon(listOf(square(1000.0, 1000.0, 3000.0, 3000.0)), listOf(square(1500.0, 1500.0, 2500.0, 2500.0)))!!
        val pieces = sequence(GlacierIceSplitter(g).split(horizontal(2000.0)))
        assertThat(pieces.map { it.third }).containsExactly(false, true, false, true, false)
        assertThat(pieces[2].first).isEqualTo(1500.0)
        assertThat(pieces[2].second).isEqualTo(2500.0)
    }

    @Test
    fun `outer rings split over several ways are joined`() {
        val a = ring(1000.0, 1000.0, 2000.0, 1000.0, 2000.0, 2000.0)
        val b = ring(1000.0, 1000.0, 1000.0, 2000.0, 2000.0, 2000.0) // reversed direction
        val g = loader.multipolygon(listOf(a, b), listOf())!!
        assertThat(g.area).isCloseTo(1_000_000.0, within(1e-6))
    }

    @Test
    fun `overlapping outers of one relation do not cancel out`() {
        val g = loader.multipolygon(
            listOf(square(1000.0, 1000.0, 2000.0, 2000.0), square(1500.0, 1200.0, 2500.0, 1800.0)), listOf()
        )!!
        val pieces = sequence(GlacierIceSplitter(g).split(horizontal(1500.0)))
        assertThat(pieces).containsExactly(
            Triple(0.0, 1000.0, false),
            Triple(1000.0, 2500.0, true),
            Triple(2500.0, 4096.0, false)
        )
    }

    @Test
    fun `a self-overlapping ring keeps the overlap as ice`() {
        // a loop that crosses itself and overlaps: outline goes around 1000..2000 twice with an offset
        val g = loader.polygon(
            ring(1000.0, 1000.0, 2000.0, 1000.0, 2000.0, 2000.0, 1000.0, 2000.0, 1000.0, 1200.0, 1800.0, 1200.0, 1800.0, 1800.0, 1200.0, 1800.0, 1200.0, 1000.0, 1000.0, 1000.0)
        )!!
        assertThat(g.isValid).isTrue()
        val pieces = sequence(GlacierIceSplitter(g).split(horizontal(1500.0)))
        // the inner square must not become a hole
        assertThat(pieces.filter { it.third }.map { Pair(it.first, it.second) }).containsExactly(Pair(1000.0, 2000.0))
    }

    @Test
    fun `overlapping separate glaciers are unioned per tile`() {
        val tile = TileId(10, 465, 273)
        val b = tile.lonLatBounds()
        fun lonLatSquare(fx0: Double, fy0: Double, fx1: Double, fy1: Double): Geometry {
            val x0 = b.minLon + (b.maxLon - b.minLon) * fx0
            val x1 = b.minLon + (b.maxLon - b.minLon) * fx1
            val y0 = b.maxLat - (b.maxLat - b.minLat) * fy0
            val y1 = b.maxLat - (b.maxLat - b.minLat) * fy1
            return factory.createPolygon(square(x0, y1, x1, y0).coordinates)
        }
        val index = GlacierIndex(
            listOf(
                Glacier("w1", lonLatSquare(0.25, 0.25, 0.5, 0.75)),
                Glacier("w2", lonLatSquare(0.4, 0.3, 0.6, 0.7))
            )
        )
        val glacier = index.forTile(tile)!!
        val y = 2048.0
        val pieces = sequence(GlacierIceSplitter(glacier).split(horizontal(y)))
        val ice = pieces.filter { it.third }
        assertThat(ice).hasSize(1)
        assertThat(ice.single().first).isCloseTo(1024.0, within(1.0))
        assertThat(ice.single().second).isCloseTo(0.6 * 4096, within(1.0))
    }
}

class GlacierProjectionTest {
    @Test
    fun `valid geographic polygon with a near edge hole can need repair after Mercator projection`() {
        val source = factory.createPolygon(
            factory.createLinearRing(ring(20.0, -70.0, 22.0, -72.0, 20.0, -72.0, 20.0, -70.0).coordinates),
            arrayOf(factory.createLinearRing(ring(21.0, -71.0001, 20.9, -71.2, 20.7, -71.2, 21.0, -71.0001).coordinates)))
        assertThat(source.isValid).isTrue()
        val tile = WebMercator.tileAt(21.0, -71.0, 6)
        val projected = GlacierIndex(listOf(Glacier("synthetic", source))).forTile(tile)!!
        assertThat(projected.isValid).isFalse()
        val y = (WebMercator.latToTileY(-71.05, tile.z) - tile.y) * 4096
        val line = horizontal(y, -256.0, 4352.0)
        val splitter = GlacierIceSplitter(projected, minPieceLength = 0.0)
        val parts = sequence(splitter.split(line))
        assertThat(splitter.repairCount).isEqualTo(1)
        assertThat(parts.sumOf { it.second - it.first }).isCloseTo(4608.0, within(1e-8))
        assertThat(parts.any { it.third }).isTrue()
        assertThat(source.isValid).isTrue()
    }

    @Test
    fun `local unions preserve overlapping ice and holes after cache eviction`() {
        val tile = TileId(9, 300, 180)
        val b = tile.lonLatBounds()
        val dx = b.maxLon - b.minLon
        val dy = b.maxLat - b.minLat
        val first = factory.createPolygon(
            factory.createLinearRing(square(b.minLon - 20 * dx, b.minLat - 20 * dy,
                b.maxLon + 20 * dx, b.maxLat + 20 * dy).coordinates),
            arrayOf(factory.createLinearRing(square(b.minLon + .2 * dx, b.minLat + .2 * dy,
                b.minLon + .8 * dx, b.minLat + .8 * dy).coordinates))
        )
        val second = factory.createPolygon(square(b.minLon + .5 * dx, b.minLat - 10 * dy,
            b.maxLon + 10 * dx, b.maxLat + 10 * dy).coordinates)
        val glaciers = listOf(Glacier("large", first), Glacier("overlap", second))
        val margin = 256.0 / 4096
        val envelope = org.locationtech.jts.geom.Envelope(
            WebMercator.tileXToLon(tile.x - margin, tile.z), WebMercator.tileXToLon(tile.x + 1 + margin, tile.z),
            WebMercator.tileYToLat(tile.y + 1 + margin, tile.z), WebMercator.tileYToLat(tile.y - margin, tile.z))
        val expected = OverlayNGRobust.overlay(UnaryUnionOp.union(glaciers.map { it.geometry }),
            factory.toGeometry(envelope), OverlayNG.INTERSECTION)
        val index = GlacierIndex(glaciers, baseZoom = tile.z, cacheSize = 1)
        assertThat(index.lonLatForTile(tile)!!.symDifference(expected).area).isLessThan(1e-10)
        index.lonLatForTile(TileId(tile.z, tile.x + 1, tile.y))
        assertThat(index.lonLatForTile(tile)!!.symDifference(expected).area).isLessThan(1e-10)
    }

    @Test
    fun `dateline buffers wrap glacier copies through hierarchical clipping`() {
        val z = 13
        val y = 4096
        val south = WebMercator.tileYToLat(y + 0.75, z)
        val north = WebMercator.tileYToLat(y + 0.25, z)
        val glaciers = listOf(
            Glacier("east", factory.createPolygon(square(179.9998, south, 180.0, north).coordinates)),
            Glacier("west", factory.createPolygon(square(-180.0, south, -179.9998, north).coordinates))
        )
        val originals = glaciers.map { it.geometry.copy() }
        val hierarchical = GlacierIndex(glaciers, baseZoom = 6)
        val direct = GlacierIndex(glaciers, baseZoom = z)
        for ((x, bufferX) in listOf(0 to -10.0, 8191 to 4106.0)) {
            val tile = TileId(z, x, y)
            val geometry = hierarchical.forTile(tile)!!
            assertThat(geometry.covers(factory.createPoint(Coordinate(bufferX, 2048.0)))).isTrue()
            assertThat(geometry.symDifference(direct.forTile(tile)).area).isLessThan(1e-4)
        }
        glaciers.zip(originals).forEach { (glacier, original) ->
            assertThat(glacier.geometry.equalsExact(original)).isTrue()
        }
    }

    @Test
    fun `tile corners map to 0 and extent`() {
        val tile = TileId(12, 1858, 1092)
        val b = tile.lonLatBounds()
        val corner = factory.createPolygon(
            square(b.minLon, b.minLat, b.maxLon, b.maxLat).coordinates
        )
        val g = GlacierIndex(listOf(Glacier("w", corner)), bufferUnits = 256).forTile(tile)!!
        val env = g.envelopeInternal
        assertThat(env.minX).isCloseTo(0.0, within(1e-6))
        assertThat(env.minY).isCloseTo(0.0, within(1e-6))
        assertThat(env.maxX).isCloseTo(4096.0, within(1e-6))
        assertThat(env.maxY).isCloseTo(4096.0, within(1e-6))
        // north is y = 0: the northern edge of the tile has the max latitude
        assertThat(WebMercator.latToTileY(b.maxLat, 12)).isCloseTo(1092.0, within(1e-9))
    }

    @Test
    fun `hierarchical clipping matches direct clipping`() {
        val tile = TileId(13, 3717, 2184)
        val b = TileId(9, 232, 136).lonLatBounds()
        val big = factory.createPolygon(ring(b.minLon, b.minLat, b.maxLon, b.minLat, (b.minLon + b.maxLon) / 2, b.maxLat, b.minLon, b.minLat).coordinates)
        val hierarchical = GlacierIndex(listOf(Glacier("w", big)), baseZoom = 6).forTile(tile)
        val direct = GlacierIndex(listOf(Glacier("w", big)), baseZoom = 13).forTile(tile)
        if (direct == null) {
            assertThat(hierarchical).isNull()
        } else {
            assertThat(hierarchical!!.symDifference(direct).area).isLessThan(1e-3)
        }
    }
}

/** Runs against data/iceland (see data/iceland/SOURCES.md); skipped when the data is absent. */
class GlacierIcelandDataTest {
    @Test
    fun `loads Iceland glaciers`() {
        val file = File("data/iceland/iceland-latest.osm.pbf")
        assumeTrue(file.exists(), "no Iceland data")
        val loader = GlacierLoader(file)
        val start = System.currentTimeMillis()
        val glaciers = loader.load()
        val index = GlacierIndex(glaciers)
        println("glaciers=${glaciers.size} area=${index.areaKm2} km2 broken=${loader.brokenRelations} open=${loader.openWays} in ${System.currentTimeMillis() - start} ms")
        assertThat(glaciers.size).isGreaterThan(100)
        assertThat(index.areaKm2).isBetween(9_000.0, 13_000.0)
        // Hvannadalshnukur summit is on Oraefajokull (part of Vatnajokull)
        val summit = factory.createPoint(Coordinate(-16.6767, 64.0147))
        assertThat(glaciers.any { it.geometry.contains(summit) }).isTrue()
        // Reykjavik is not
        val reykjavik = factory.createPoint(Coordinate(-21.94, 64.15))
        assertThat(glaciers.none { it.geometry.contains(reykjavik) }).isTrue()
    }
}
