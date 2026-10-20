package com.greensopinion.vectorvector.sink

import com.greensopinion.vectorvector.Tile
import com.greensopinion.vectorvector.TileId
import com.greensopinion.vectorvector.TileSink
import com.greensopinion.vectorvector.elevation.ElevationDataStore
import com.greensopinion.vectorvector.metrics.MetricsProvider
import com.greensopinion.vectorvector.repository.TileRepository
import com.greensopinion.vectorvector.sink.contour.AttributeStyle
import com.greensopinion.vectorvector.sink.contour.ContourLineGenerator
import com.greensopinion.vectorvector.sink.contour.ContourPostProcessor
import com.greensopinion.vectorvector.sink.contour.IceSplitter
import com.greensopinion.vectorvector.sink.contour.RbtContourVectorTileMapper
import com.greensopinion.vectorvector.sink.contour.ContourOptions
import com.greensopinion.vectorvector.sink.contour.ContourVectorTileMapper
import com.greensopinion.vectorvector.sink.contour.LineSimplifier
import vector_tile.VectorTile
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

class VectorTileSink(
    private val contourOptionsProvider: (tile: Tile) -> ContourOptions?,
    private val repository: TileRepository,
    private val elevationDataStore: ElevationDataStore,
    private val metricsProvider: MetricsProvider,
    /** RBT pipeline: splits lines into ice/land pieces, null when the tile has no glaciers. */
    private val iceSplitterProvider: (tile: TileId) -> IceSplitter? = { null },
    /** Additional layers written into the same tile, e.g. peaks. */
    private val extraLayers: List<(tile: TileId) -> VectorTile.Tile.Layer?> = listOf(),
    /** Gzip each tile, as tippecanoe, RBT and most MBTiles readers (QGIS, tileserver-gl) expect. */
    private val gzip: Boolean = false
) : TileSink {
    override fun accept(tile: Tile): Boolean {
        val layers = mutableListOf<VectorTile.Tile.Layer>()
        contourOptionsProvider(tile)?.let { options -> contourLayer(tile, options)?.let { layers.add(it) } }
        extraLayers.forEach { provider -> provider(tile.id)?.let { layers.add(it) } }
        if (layers.isEmpty()) {
            return false
        }
        val vectorTile = VectorTile.Tile.newBuilder().addAllLayers(layers).build()
        val bytes = vectorTile.toByteArray()
        repository.store(tile.id, "pbf", if (gzip) gzip(bytes) else bytes)
        metricsProvider.get().addCount("VectorTile")
        return true
    }

    private fun contourLayer(tile: Tile, contourOptions: ContourOptions): VectorTile.Tile.Layer? {
        val elevationTile = elevationDataStore.get(tile.id)
        if (elevationTile.empty) {
            return null
        }
        if (contourOptions.attributeStyle == AttributeStyle.RBT) {
            val features = ContourPostProcessor(contourOptions, elevationDataStore)
                .generate(tile.id, iceSplitterProvider(tile.id))
            if (features.isEmpty()) {
                return null
            }
            metricsProvider.get().addCount("ContourFeature", features.size)
            return RbtContourVectorTileMapper(contourOptions.contourLayer, contourOptions.extent, features).apply()
        }
        var linesByElevation = ContourLineGenerator(contourOptions, elevationDataStore).generate(tile.id)
        if (linesByElevation.isEmpty() || linesByElevation.all { it.value.isEmpty() }) {
            return null
        }
        val epsilon = contourOptions.epsilon?.toDouble()
        if (epsilon != null && epsilon > 0.0) {
            linesByElevation = linesByElevation.map {
                Pair(it.key, it.value.map { line -> LineSimplifier(epsilon).simplify(line) })
            }.toMap()
        }
        return ContourVectorTileMapper(contourOptions, linesByElevation).apply()
    }
}

data class LayerSchema(
    val id: String,
    val description: String,
    val minZoom: Int,
    val maxZoom: Int,
    /** field name to description */
    val fields: Map<String, String>
)

fun contourLayerSchema(minZoom: Int, maxZoom: Int, contourLayer: String, levelName: String, elevationName: String) =
    LayerSchema(
        id = contourLayer,
        description = "Elevation contour lines",
        minZoom = minZoom,
        maxZoom = maxZoom,
        fields = linkedMapOf(
            elevationName to "Integer. The contour line elevation in meters.",
            levelName to "Indicator for major or minor contour lines. A value of 1 indicates a major line, otherwise 0."
        )
    )

private fun gzip(bytes: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(bytes.size / 2 + 64)
    GZIPOutputStream(out).use { it.write(bytes) }
    return out.toByteArray()
}

fun vectorSchema(minZoom: Int, maxZoom: Int, contourLayer: String, levelName: String, elevationName: String): String =
    vectorSchema(minZoom, maxZoom, listOf(contourLayerSchema(minZoom, maxZoom, contourLayer, levelName, elevationName)))

/** TileJSON with `vector_layers`, as stored in the mbtiles `json` metadata row. */
fun vectorSchema(minZoom: Int, maxZoom: Int, layers: List<LayerSchema>): String {
    fun quote(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    val layerJson = layers.joinToString(",\n") { layer ->
        val fields = layer.fields.entries.joinToString(",\n") { "                ${quote(it.key)}: ${quote(it.value)}" }
        """        {
            "id": ${quote(layer.id)},
            "description": ${quote(layer.description)},
            "maxzoom": ${layer.maxZoom},
            "minzoom": ${layer.minZoom},
            "fields": {
$fields
            }
        }"""
    }
    return """{
    "tilejson": "3.0.0",
    "format": "pbf",
    "maxzoom": $maxZoom,
    "minzoom": $minZoom,
    "scheme": "xyz",
    "vector_layers": [
$layerJson
    ]
}"""
}
