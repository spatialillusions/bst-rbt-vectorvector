package com.greensopinion.vectorvector.sink.contour

import com.greensopinion.vectorvector.Elevation
import vector_tile.VectorTile
import kotlin.math.roundToInt

class ContourVectorTileMapper(
    val options: ContourOptions,
    val contours: Map<Elevation, List<Line>>
) {

    fun apply() : VectorTile.Tile.Layer {
        val elevations = contours.keys.sortedBy { it.meters }
        return VectorTile.Tile.Layer.newBuilder().apply {
            name = options.contourLayer
            extent = options.extent
            version = 2
            val elevationKeyIndex = keysList.size
            addKeys(options.elevationKey)
            val levelKeyIndex = keysList.size
            addKeys(options.levelKey)

            val valueToIndex = mutableMapOf<Int, Int>()
            fun indexedValue(value: Int): Int {
                var index = valueToIndex[value]
                if (index == null) {
                    index = valuesList.size
                    valueToIndex[value] = index
                    addValues(value.toTileValue())
                }
                return index
            }
            for (elevation in elevations.filter { it.meters > options.minLevelExclusive }) {
                val elevationIndex = indexedValue(elevation.meters.roundToInt())
                val levelIndex = indexedValue(if (elevation.meters.roundToInt() % options.majorLevel == 0) 1 else 0)
                for (line in contours[elevation]!!) {
                    addFeatures(VectorTile.Tile.Feature.newBuilder().apply {
                        type = VectorTile.Tile.GeomType.LINESTRING
                        line.encodeLineString(this)
                        addTags(elevationKeyIndex)
                        addTags(elevationIndex)
                        addTags(levelKeyIndex)
                        addTags(levelIndex)
                    }.build())
                }
            }
        }.build()
    }
}

internal fun command(cmd: Int, length: Int): Int {
    return (length shl 3) + (cmd and 0x7)
}
internal fun zigzag(num: Int): Int {
    return (num shl 1) xor (num shr 31)
}
internal fun Line.encodeLineString(geometry: VectorTile.Tile.Feature.Builder) {
    var x = 0;
    var y = 0;
    geometry.addGeometry(command(1,1))
    for (i in 0..<points.size) {
        val point = points[i]
        val dx = point.x.roundToInt() - x;
        val dy = point.y.roundToInt() - y;
        if (i == 1) {
            geometry.addGeometry(command(2, points.size - 1));
        }
        geometry.addGeometry(zigzag(dx));
        geometry.addGeometry(zigzag(dy));
        x += dx;
        y += dy;
    }
}

internal fun Int.toTileValue(): VectorTile.Tile.Value {
    val l = this.toLong()
    return VectorTile.Tile.Value.newBuilder().apply {
        if (l >= 0) {
            intValue = l
        } else {
            sintValue = l
        }
    }.build()
}

