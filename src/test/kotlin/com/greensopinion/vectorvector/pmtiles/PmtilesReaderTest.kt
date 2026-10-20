package com.greensopinion.vectorvector.pmtiles

import com.greensopinion.vectorvector.WebMercator
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress

val fixtureFile = File("src/test/resources/pmtiles/fixture.pmtiles")

class PmtilesReaderTest {
    private val hvannadalshnukur = WebMercator.tileAt(-16.677, 63.999, 10)

    @Test
    fun `reads the header`() {
        PmtilesReader(FileRangeSource(fixtureFile)).use { reader ->
            val header = reader.header
            assertThat(header.tileType).isEqualTo(TileType.WEBP)
            assertThat(header.minZoom).isEqualTo(8)
            assertThat(header.maxZoom).isEqualTo(10)
            assertThat(header.internalCompression).isEqualTo(Compression.GZIP)
            assertThat(header.tileCompression).isEqualTo(Compression.NONE)
            assertThat(header.minLon).isEqualTo(-16.7)
            assertThat(header.maxLat).isEqualTo(64.02)
        }
    }

    @Test
    fun `reads a webp tile`() {
        PmtilesReader(FileRangeSource(fixtureFile)).use { reader ->
            val bytes = reader.getTile(hvannadalshnukur.z, hvannadalshnukur.x, hvannadalshnukur.y)!!
            assertWebp(bytes)
        }
    }

    @Test
    fun `returns null for missing tiles`() {
        PmtilesReader(FileRangeSource(fixtureFile)).use { reader ->
            assertThat(reader.getTile(10, 0, 0)).isNull()
            assertThat(reader.getTile(11, hvannadalshnukur.x * 2, hvannadalshnukur.y * 2)).isNull()
            assertThat(reader.getTile(7, 57, 33)).isNull()
        }
    }

    @Test
    fun `http source matches file source, also through the disk cache`() {
        val bytes = fixtureFile.readBytes()
        var requests = 0
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/fixture.pmtiles") { exchange ->
            requests += 1
            val range = exchange.requestHeaders.getFirst("Range")!!
            val (start, end) = range.removePrefix("bytes=").split("-").map { it.toLong() }
            val last = minOf(end, bytes.size - 1L)
            val body = bytes.copyOfRange(start.toInt(), last.toInt() + 1)
            exchange.responseHeaders.add("ETag", "\"v1\"")
            exchange.responseHeaders.add("Content-Range", "bytes $start-$last/${bytes.size}")
            exchange.sendResponseHeaders(206, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/fixture.pmtiles"
            val t = hvannadalshnukur
            val expected = PmtilesReader(FileRangeSource(fixtureFile)).use { it.getTile(t.z, t.x, t.y) }
            PmtilesReader(HttpRangeSource(url)).use { reader ->
                assertThat(reader.getTile(t.z, t.x, t.y)).isEqualTo(expected)
            }
            val cacheDir = File("target/tmp/pmtiles-cache").also { it.deleteRecursively() }
            PmtilesReader(rangeSourceFor(url, cacheDir)).use { assertThat(it.getTile(t.z, t.x, t.y)).isEqualTo(expected) }
            val before = requests
            PmtilesReader(rangeSourceFor(url, cacheDir)).use { assertThat(it.getTile(t.z, t.x, t.y)).isEqualTo(expected) }
            assertThat(requests).isEqualTo(before)
        } finally {
            server.stop(0)
        }
    }
}

fun assertWebp(bytes: ByteArray) {
    assertThat(String(bytes, 0, 4, Charsets.US_ASCII)).isEqualTo("RIFF")
    assertThat(String(bytes, 8, 4, Charsets.US_ASCII)).isEqualTo("WEBP")
}
