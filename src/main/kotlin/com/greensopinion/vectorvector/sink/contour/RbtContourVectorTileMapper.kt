package com.greensopinion.vectorvector.sink.contour

import vector_tile.VectorTile

/** Encodes contour features with the RBT attributes `ele`, `index` and `is_ice` (all integers). */
class RbtContourVectorTileMapper(
    private val layerName: String,
    private val extent: Int,
    private val features: List<ContourFeature>
) {
    fun apply(): VectorTile.Tile.Layer = VectorTile.Tile.Layer.newBuilder().apply {
        name = layerName
        extent = this@RbtContourVectorTileMapper.extent
        version = 2
        addKeys("ele")
        addKeys("index")
        addKeys("is_ice")
        val valueToIndex = mutableMapOf<Int, Int>()
        fun indexedValue(value: Int): Int = valueToIndex.getOrPut(value) {
            addValues(value.toTileValue())
            valuesList.size - 1
        }
        for (feature in features) {
            addFeatures(VectorTile.Tile.Feature.newBuilder().apply {
                type = VectorTile.Tile.GeomType.LINESTRING
                feature.line.encodeLineString(this)
                addTags(0)
                addTags(indexedValue(feature.elevation))
                addTags(1)
                addTags(indexedValue(rbtIndex(feature.elevation)))
                addTags(2)
                addTags(indexedValue(if (feature.isIce) 1 else 0))
            }.build())
        }
    }.build()
}

private val RBT_INDEX_LEVELS = listOf(800, 400, 200, 100, 50, 20)

/** The largest of 800, 400, 200, 100, 50, 20 that divides [elevation] (negative values too), else 10. */
fun rbtIndex(elevation: Int): Int = RBT_INDEX_LEVELS.firstOrNull { Math.floorMod(elevation, it) == 0 } ?: 10
