package com.greensopinion.vectorvector

import com.greensopinion.vectorvector.elevation.ElevationDataStore

/**
 * A tile that provides access outside of its extent.
 */
class ElevationTileArea(
    private val tileId: TileId,
    private val tile: ElevationTile,
    private val store: ElevationDataStore
) : ElevationTile() {

    override val empty = tile.empty
    override val extent = tile.extent

    override fun copySamples(x: Int, y: Int, width: Int, height: Int, out: DoubleArray,
                             offset: Int, stride: Int) {
        var row = 0
        while (row < height) {
            val tileY = Math.floorDiv(y + row, extent)
            val localY = Math.floorMod(y + row, extent)
            val rows = minOf(height - row, extent - localY)
            var column = 0
            while (column < width) {
                val tileX = Math.floorDiv(x + column, extent)
                val localX = Math.floorMod(x + column, extent)
                val columns = minOf(width - column, extent - localX)
                val source = if (tileX == 0 && tileY == 0) tile else
                    store.get(tileId.copy(x = tileId.x + tileX, y = tileId.y + tileY))
                source.copySamples(localX, localY, columns, rows, out,
                    offset + row * stride + column, stride)
                column += columns
            }
            row += rows
        }
    }

    private val top: ElevationTile by lazy {
        store.get(tileId.copy(y = tileId.y - 1))
    }
    private val topLeft: ElevationTile by lazy {
        store.get(tileId.copy(x = tileId.x - 1, y = tileId.y - 1))
    }
    private val topRight: ElevationTile by lazy {
        store.get(tileId.copy(x = tileId.x + 1, y = tileId.y - 1))
    }
    private val left: ElevationTile by lazy {
        store.get(tileId.copy(x = tileId.x - 1))
    }
    private val right: ElevationTile by lazy {
        store.get(tileId.copy(x = tileId.x + 1))
    }
    private val bottom: ElevationTile by lazy {
        store.get(tileId.copy(y = tileId.y + 1))
    }
    private val bottomLeft: ElevationTile by lazy {
        store.get(tileId.copy(x = tileId.x - 1, y = tileId.y + 1))
    }
    private val bottomRight: ElevationTile by lazy {
        store.get(tileId.copy(x = tileId.x + 1, y = tileId.y + 1))
    }
    override fun get(x: Int, y: Int): Elevation {
        var xOffset = 0
        var yOffset = 0
        var delegate = tile;
        if (x < 0) {
            xOffset = tile.extent
            if (y < 0) {
                delegate = topLeft
                yOffset = tile.extent
            } else if (y >= tile.extent) {
                delegate = bottomLeft
                yOffset = -tile.extent
            } else {
                delegate = left
            }
        } else if (x >= tile.extent) {
            xOffset = -tile.extent
            if (y < 0) {
                delegate = topRight
                yOffset = tile.extent
            } else if (y >= tile.extent) {
                delegate = bottomRight
                yOffset = -tile.extent
            } else {
                delegate = right
            }
        } else if (y < 0) {
            yOffset = tile.extent
            delegate = top
        } else if (y >= tile.extent) {
            yOffset = -tile.extent
            delegate = bottom
        }
        return delegate.get(x + xOffset, y + yOffset)
    }
}
