package com.greensopinion.vectorvector.osm

import org.locationtech.jts.geom.Envelope

/** Query canonical OSM longitudes for a buffered tile; shift matches into that tile's world. */
internal fun longitudeQueries(envelope: Envelope): List<Pair<Envelope, Double>> = buildList {
    add(envelope to 0.0)
    if (envelope.minX < -180.0) {
        add(Envelope(envelope.minX + 360.0, 180.0, envelope.minY, envelope.maxY) to -360.0)
    }
    if (envelope.maxX > 180.0) {
        add(Envelope(-180.0, envelope.maxX - 360.0, envelope.minY, envelope.maxY) to 360.0)
    }
}
