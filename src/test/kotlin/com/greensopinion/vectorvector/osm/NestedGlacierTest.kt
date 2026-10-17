package com.greensopinion.vectorvector.osm

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.GeometryFactory
import java.io.File

class NestedGlacierTest {
    private fun loader(name: String) = GlacierLoader(File(javaClass.getResource("/osm/$name.osm.pbf")!!.toURI()))

    @Test
    fun `duplicate outer fragments do not break a complete ring`() {
        val loader = loader("duplicate-outer")
        assertThat(loader.load().single().geometry.area).isEqualTo(100.0)
        assertThat(loader.brokenRelations).isZero()
    }

    @Test
    fun `loads untagged descendants recursively and retains direct outer ways`() {
        val loader = loader("nested-outer")
        val glacier = loader.load().single()
        assertThat(glacier.osmId).isEqualTo("r30")
        assertThat(glacier.geometry.area).isCloseTo(101.0, within(1e-9))
        assertThat(loader.brokenRelations).isZero()
    }

    @Test
    fun `subtracting nested inner preserves its own hole as an ice island`() {
        val loader = loader("nested-inner")
        val geometry = loader.load().single().geometry
        val factory = GeometryFactory()
        assertThat(geometry.area).isCloseTo(68.0, within(1e-9))
        assertThat(geometry.covers(factory.createPoint(Coordinate(5.0, 5.0)))).isTrue()
        assertThat(geometry.covers(factory.createPoint(Coordinate(3.0, 3.0)))).isFalse()
        assertThat(loader.brokenRelations).isZero()
    }

    @Test
    fun `cyclic or missing inner relations reject the glacier instead of filling its hole`() {
        for (name in listOf("cyclic", "missing-inner")) {
            val loader = loader(name)
            assertThat(loader.load()).isEmpty()
            assertThat(loader.brokenRelations).isEqualTo(1)
        }
    }
}
