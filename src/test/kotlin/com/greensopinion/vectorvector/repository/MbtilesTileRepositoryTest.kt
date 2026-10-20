package com.greensopinion.vectorvector.repository

import com.greensopinion.vectorvector.TileId
import com.greensopinion.vectorvector.metrics.SingletonMetricsProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertThrows
import org.sqlite.JDBC
import java.io.File
import java.util.Properties
import kotlin.random.Random

class MbtilesTileRepositoryTest {
    private val file = File("target/tmp/${javaClass.simpleName}/example.mbtiles")
    private val metricsProvider = SingletonMetricsProvider()

    @BeforeEach
    fun setup() {
        file.parentFile.mkdirs()
        file.delete()
    }

    @Test
    fun `can create a file`() {
        assertThat(file).doesNotExist()
        val tile = TileId(6, 10, 21)
        val bytes = ByteArray(256).also {
            Random(System.currentTimeMillis()).nextBytes(it)
        }
        MbtilesTileRepository(file, metricsProvider).use {
            it.store(tile, "pbf", bytes)
        }
        assertThat(file).exists()
        MbtilesTileRepository(file, metricsProvider, truncate = false).use {
            val tileBytes = it.read(tile).get()
            assertThat(tileBytes).isEqualTo(bytes)
        }
    }

    @Test
    fun `provides metadata`() {
        MbtilesTileRepository(file, metricsProvider).use {
            it.setMetadata(MbtilesMetadata(
                name = "a name",
                minZoom = 0,
                maxZoom = 6,
                json = "{ \"a\": \"json\" }",
                format = "pbf"
            ))
        }
    }

    @Test
    fun `commits full batches and the final partial batch on close`() {
        MbtilesTileRepository(file, metricsProvider).use { repository ->
            repeat(1005) { x -> repository.store(TileId(11, x, 0), "pbf", byteArrayOf(1, 2, 3)) }
        }
        JDBC.createConnection("jdbc:sqlite:${file.absolutePath}", Properties()).use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT count(*) FROM tiles").use { r ->
                    r.next()
                    assertThat(r.getInt(1)).isEqualTo(1005)
                }
                s.executeQuery("PRAGMA integrity_check").use { r ->
                    r.next()
                    assertThat(r.getString(1)).isEqualTo("ok")
                }
            }
        }
    }

    @Test
    fun `asynchronous write errors fail close and the archive can be rebuilt`() {
        assertThrows(Exception::class.java) {
            MbtilesTileRepository(file, metricsProvider).use { repository ->
                repeat(2) { repository.store(TileId(2, 1, 1), "pbf", byteArrayOf(1)) }
            }
        }
        MbtilesTileRepository(file, metricsProvider).use {
            it.store(TileId(2, 1, 1), "pbf", byteArrayOf(2))
        }
        MbtilesTileRepository(file, metricsProvider, truncate = false).use {
            assertThat(it.read(TileId(2, 1, 1)).get()).isEqualTo(byteArrayOf(2))
        }
    }
}
