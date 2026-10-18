package com.greensopinion.vectorvector.elevation

import com.greensopinion.vectorvector.ElevationTile
import com.greensopinion.vectorvector.TileId

interface ElevationDataStore {
    fun get(tile: TileId): ElevationTile
    /** Called only after all input readers for a shard have finished. */
    fun afterShard() {}
}
