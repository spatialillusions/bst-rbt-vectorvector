package com.greensopinion.vectorvector.profile

import com.greensopinion.vectorvector.TileId
import com.greensopinion.vectorvector.sink.LayerSchema
import com.greensopinion.vectorvector.sink.contour.ContourOptions
import com.greensopinion.vectorvector.sink.contourLayerSchema

/** Decides which contours are generated per zoom level and how they are attributed. */
interface ContourProfile {
    /** The contour options for [tile], or null when the tile gets no contours. */
    fun contourOptions(tile: TileId): ContourOptions?

    /** The contour layer schema for a run over zooms [minZoom]..[maxZoom], or null if no contours are written. */
    fun contourLayer(minZoom: Int, maxZoom: Int): LayerSchema?
}

/** Upstream vectorvector behaviour: `ele` + `level` attributes, intervals coarsening below z12. */
class DefaultProfile(epsilon: Int) : ContourProfile {
    private val baseOptions = ContourOptions(minorLevel = 10, majorLevel = 50, epsilon = epsilon)

    override fun contourOptions(tile: TileId): ContourOptions =
        if (tile.z < 9) {
            baseOptions.copy(minorLevel = 500, majorLevel = 1000)
        } else if (tile.z <= 10) {
            baseOptions.copy(minorLevel = 100, majorLevel = 200)
        } else if (tile.z < 12) {
            baseOptions.copy(minorLevel = 20, majorLevel = 100)
        } else
            baseOptions

    override fun contourLayer(minZoom: Int, maxZoom: Int): LayerSchema = contourLayerSchema(
        minZoom = minZoom,
        maxZoom = maxZoom,
        contourLayer = baseOptions.contourLayer,
        levelName = baseOptions.levelKey,
        elevationName = baseOptions.elevationKey
    )
}
