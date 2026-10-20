package com.greensopinion.vectorvector.osm

import com.greensopinion.vectorvector.TileId
import com.greensopinion.vectorvector.WebMercator
import com.greensopinion.vectorvector.sink.LayerSchema
import com.greensopinion.vectorvector.sink.contour.command
import com.greensopinion.vectorvector.sink.contour.toTileValue
import com.greensopinion.vectorvector.sink.contour.zigzag
import io.github.oshai.kotlinlogging.KotlinLogging
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.index.strtree.STRtree
import vector_tile.VectorTile
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

private val log = KotlinLogging.logger { }

data class Peak(
    val osmId: Long,
    val lon: Double,
    val lat: Double,
    val name: String?,
    val nameEn: String?,
    val ele: Int?,
    val eleSource: String?,
    val kind: String
)

/** Parses OSM `ele` values: "2110", "2110 m", "2,110", "2110.4", "6923 ft", "6923'". Returns metres or null. */
object EleParser {
    private val number = Regex("""-?\d{1,3}(?:,\d{3})+(?:\.\d+)?|-?\d+(?:[.,]\d+)?""")

    fun parse(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        val text = value.trim().lowercase()
        val match = number.find(text) ?: return null
        val raw = match.value
        val numeric = if (Regex("""-?\d{1,3}(?:,\d{3})+(?:\.\d+)?""").matches(raw)) raw.replace(",", "") else raw.replace(",", ".")
        val v = numeric.toDoubleOrNull() ?: return null
        val rest = text.substring(match.range.last + 1).trim()
        val feet = rest.startsWith("ft") || rest.startsWith("feet") || rest.startsWith("'")
        val metres = if (feet) v * 0.3048 else v
        return if (metres in -500.0..9000.0) metres.roundToInt() else null
    }
}

/** Loads `natural=peak` and `natural=volcano` nodes in a single pass. */
class PeakLoader(private val file: File) {
    fun load(): List<Peak> {
        val peaks = mutableListOf<Peak>()
        OsmPbfScanner(file).scan(object : OsmHandler {
            override val wantsNodes = true
            override fun wantsNode(id: Long, tags: Map<String, String>) = tags["natural"].let { it == "peak" || it == "volcano" }
            override fun node(id: Long, lon: Double, lat: Double, tags: Map<String, String>) {
                val ele = EleParser.parse(tags["ele"])
                peaks += Peak(
                    osmId = id, lon = lon, lat = lat,
                    name = tags["name"], nameEn = tags["name:en"],
                    ele = ele, eleSource = if (ele != null) "osm" else null,
                    kind = tags.getValue("natural")
                )
            }
        })
        log.info { "${file.name}: ${peaks.size} peaks, ${peaks.count { it.ele == null }} without a usable ele" }
        return peaks
    }
}

/**
 * Peaks with a spatial index, encoded per tile as an OpenMapTiles-like `peaks` point layer:
 * `name`, `name_en`, `ele` (m), `ele_source` (osm|dem), `class` (peak|volcano), `rank` (1 = most important in the tile).
 */
class PeakIndex(
    peaks: List<Peak>,
    private val minZoom: Int,
    private val maxZoom: Int,
    /** Elevation lookup for peaks without a usable `ele`, in metres. */
    demElevation: (lon: Double, lat: Double) -> Double?,
    private val extent: Int = 4096,
    private val bufferUnits: Int = 80,
    private val maxPeaksPerTile: Int = 50,
    /** At this zoom and above all peaks are written. */
    private val allPeaksZoom: Int = 12
) {
    private val peaks = peaks
    private val elevations = ConcurrentHashMap<Long, Peak>()
    private val demElevation = demElevation

    private fun resolve(p: Peak): Peak = if (p.ele != null) p else elevations.computeIfAbsent(p.osmId) {
        val dem = demElevation(p.lon, p.lat)
        if (dem != null) p.copy(ele = dem.roundToInt(), eleSource = "dem") else p
    }
    private val tree = STRtree().also { tree ->
        this.peaks.forEach { tree.insert(Envelope(it.lon, it.lon, it.lat, it.lat), it) }
        tree.build()
    }

    val size get() = peaks.size

    fun schema(): LayerSchema = LayerSchema(
        id = LAYER,
        description = "Mountain peaks and volcanoes from OpenStreetMap",
        minZoom = minZoom,
        maxZoom = maxZoom,
        fields = linkedMapOf(
            "name" to "String",
            "name_en" to "String",
            "ele" to "Number",
            "ele_source" to "String",
            "class" to "String",
            "rank" to "Number"
        )
    )

    /** Peaks in the buffered tile, ranked: named first, then highest first. */
    fun ranked(tile: TileId): List<Pair<Peak, Pair<Int, Int>>> {
        val margin = bufferUnits.toDouble() / extent
        val envelope = Envelope(
            WebMercator.tileXToLon(tile.x - margin, tile.z), WebMercator.tileXToLon(tile.x + 1 + margin, tile.z),
            WebMercator.tileYToLat(tile.y + 1 + margin, tile.z), WebMercator.tileYToLat(tile.y - margin, tile.z)
        )
        @Suppress("UNCHECKED_CAST")
        val candidates = longitudeQueries(envelope).flatMap { (query, shift) ->
            (tree.query(query) as List<Peak>).map { resolve(it) to shift }
        }
        val sorted = candidates
            .sortedWith(compareBy<Pair<Peak, Double>>({ it.first.name == null },
                { -(it.first.ele ?: Int.MIN_VALUE / 2) }, { it.first.osmId }))
            .let { if (tile.z < allPeaksZoom) it.take(maxPeaksPerTile) else it }
        return sorted.map { (p, shift) ->
            val x = ((WebMercator.lonToTileX(p.lon + shift, tile.z) - tile.x) * extent).roundToInt()
            val y = ((WebMercator.latToTileY(p.lat, tile.z) - tile.y) * extent).roundToInt()
            Pair(p, Pair(x, y))
        }
    }

    fun layer(tile: TileId): VectorTile.Tile.Layer? {
        if (tile.z < minZoom || tile.z > maxZoom) return null
        val ranked = ranked(tile)
        if (ranked.isEmpty()) return null
        return VectorTile.Tile.Layer.newBuilder().apply {
            name = LAYER
            extent = this@PeakIndex.extent
            version = 2
            val keys = listOf("name", "name_en", "ele", "ele_source", "class", "rank")
            keys.forEach { addKeys(it) }
            val values = mutableMapOf<Any, Int>()
            fun value(v: Any): Int = values.getOrPut(v) {
                addValues(
                    when (v) {
                        is Int -> v.toTileValue()
                        else -> VectorTile.Tile.Value.newBuilder().setStringValue(v.toString()).build()
                    }
                )
                valuesList.size - 1
            }
            ranked.forEachIndexed { i, (peak, xy) ->
                addFeatures(VectorTile.Tile.Feature.newBuilder().apply {
                    type = VectorTile.Tile.GeomType.POINT
                    id = peak.osmId
                    addGeometry(command(1, 1))
                    addGeometry(zigzag(xy.first))
                    addGeometry(zigzag(xy.second))
                    fun tag(key: Int, v: Any?) {
                        if (v != null) {
                            addTags(key)
                            addTags(value(v))
                        }
                    }
                    tag(0, peak.name)
                    tag(1, peak.nameEn)
                    tag(2, peak.ele)
                    tag(3, peak.eleSource)
                    tag(4, peak.kind)
                    tag(5, i + 1)
                }.build())
            }
        }.build()
    }

    companion object {
        const val LAYER = "peaks"
    }
}
