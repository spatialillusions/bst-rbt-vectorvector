package com.greensopinion.vectorvector

import com.greensopinion.vectorvector.cli.CliOptions
import com.greensopinion.vectorvector.cli.CliOutputFormat
import com.greensopinion.vectorvector.metrics.SingletonMetricsProvider
import com.greensopinion.vectorvector.repository.MbtilesTileRepository
import com.greensopinion.vectorvector.repository.TileRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.sqlite.JDBC
import java.io.File
import java.time.Duration
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class ShardBuildTest {
    @TempDir lateinit var dir: File

    @Test
    fun `additional ice input changes the resume fingerprint`() {
        val elevation = File(dir, "elevation").also { it.mkdirs() }
        File(elevation, "tile.tif").writeText("fixture")
        val mask = File(dir, "ice.wkb").also { it.writeBytes(byteArrayOf(1)) }
        val options = CliOptions().apply { dataDir = elevation; glacierWkb = mask }
        val args = arrayOf("--data", elevation.path, "--profile", "rbt", "--glacierWkb", mask.path)
        val before = buildIdentity(options, args)
        mask.appendBytes(byteArrayOf(2))
        assertThat(buildIdentity(options, args)).isNotEqualTo(before)
    }

    @Test
    fun `partitions cover precisely the filtered range with no duplicate tiles`() {
        val bounds = LonLatBounds(-140.0, -20.0, 20.0, 75.0)
        val range = TileRange(2, 5, 0, 3, 0, 3, bounds)
        val shards = buildShards(range, 3, 20)
        val all = shards.flatMap { it.range.tiles().toList() }
        val expected = TileRange(2, 5, 0, 3, 0, 3).tiles().filter { it.lonLatBounds().intersects(bounds) }.toSet()
        assertThat(all).hasSize(expected.size).containsExactlyInAnyOrderElementsOf(expected)
        assertThat(shards.sumOf { it.range.size }).isEqualTo(expected.size.toLong())
        assertThat(range.size).isEqualTo(expected.size.toLong())
        val only = expected.take(7).toSet() + TileId(0, 0, 0)
        val selected = TileRange(2, 5, 0, 3, 0, 3, bounds, only)
        assertThat(buildShards(selected, 2, 2).flatMap { it.range.tiles().toList() })
            .containsExactlyInAnyOrderElementsOf(expected.take(7))
        assertThat(selected.size).isEqualTo(7L)
    }

    @Test
    fun `planet counts exceed 32 bits without enumerating and exact bbox edges agree`() {
        assertTimeoutPreemptively(Duration.ofSeconds(2)) {
            assertThat(TileRange(0, 16, 0, 0, 0, 0).size).isEqualTo(5726623061L)
            assertThat(TileRange(0, 25, 0, 0, 0, 0, only = setOf(TileId(25, 1, 2))).size).isEqualTo(1L)
        }
        val b = TileId(2, 1, 1).lonLatBounds()
        val range = TileRange(0, 5, 0, 0, 0, 0, b)
        val expected = TileRange(0, 5, 0, 0, 0, 0).tiles().filter { it.lonLatBounds().intersects(b) }.toList()
        assertThat(range.tiles().toList()).containsExactlyElementsOf(expected)
        assertThat(range.size).isEqualTo(expected.size.toLong())
    }

    @Test
    fun `sparse planet pilots retain only selected roots and empty runs retain metadata partition`() {
        val selected = setOf(TileId(13, 0, 4096), TileId(13, 8191, 4096))
        val shards = buildShards(TileRange(6, 13, 0, 63, 0, 63, only = selected), 64, 64)
        assertThat(shards.map { it.name }).containsExactly("x0-y32", "x63-y32")
        assertThat(shards.flatMap { it.range.tiles().toList() }).containsExactlyInAnyOrderElementsOf(selected)
        val empty = buildShards(TileRange(6, 13, 0, 63, 0, 63, only = setOf(TileId(14, 0, 0))), 64, 64)
        assertThat(empty).hasSize(1)
        assertThat(empty.single().range.size).isZero()
    }

    @Test
    fun `workers bound active work and propagate tile failures without hanging`() {
        val active = AtomicInteger()
        val maximum = AtomicInteger()
        val seen = ConcurrentHashMap.newKeySet<TileId>()
        val sink = object : TileSink {
            override fun accept(tile: Tile): Boolean {
                val count = active.incrementAndGet()
                maximum.accumulateAndGet(count, ::maxOf)
                Thread.sleep(2)
                seen.add(tile.id)
                active.decrementAndGet()
                return true
            }
        }
        val range = TileRange(2, 3, 0, 3, 0, 3)
        Processor(range, sink, SingletonMetricsProvider(), parallelism = 3).process()
        assertThat(maximum.get()).isBetween(2, 3)
        assertThat(seen).hasSize(range.size.toInt())
        assertTimeoutPreemptively(Duration.ofSeconds(5)) {
            assertThrows(IllegalStateException::class.java) {
                Processor(TileRange(0, 20, 0, 0, 0, 0), object : TileSink {
                    override fun accept(tile: Tile): Boolean = error("failure")
                }, SingletonMetricsProvider(), parallelism = 2).process()
            }
        }
    }

    @Test
    fun `failed shard is rebuilt completed shards are reused and changed runs rejected`() {
        val options = CliOptions().apply {
            outputDir = dir
            outputFormat = CliOutputFormat.mbtiles
            hillshadeRaster = false
            minZ = 1; maxZ = 2; minX = 0; maxX = 1; minY = 0; maxY = 1
            shardsX = 2; resumableShards = true
        }
        val metrics = SingletonMetricsProvider()
        var fail = true
        var creations = 0
        val create: (RepositoryFactory) -> Map<TileSink, TileRepository> = { factory ->
            creations++
            val repository = factory("vector", "pbf", emptyList())
            mapOf(object : TileSink {
                override fun accept(tile: Tile): Boolean {
                    if (fail && tile.id == TileId(1, 1, 0)) error("interrupted")
                    repository.store(tile.id, "pbf", tile.id.toString().toByteArray())
                    return true
                }
            } to repository)
        }
        val unused: RepositoryFactory = { _, _, _ -> error("unexpected factory") }
        assertThrows(IllegalStateException::class.java) { runTileBuild(options, metrics, { "same" }, unused, create) }
        val completed = File(dir, ".shards/vector/x0-y0.mbtiles")
        val stamp = completed.lastModified()
        assertThat(File(dir, ".shards/x0-y0.done")).exists()
        assertThat(File(dir, ".shards/x1-y0.done")).doesNotExist()
        fail = false
        runTileBuild(options, metrics, { "same" }, unused, create)
        assertThat(creations).isEqualTo(3)
        assertThat(completed.lastModified()).isEqualTo(stamp)
        val output = File(dir, "vector.mbtiles")
        assertThat(readTiles(output)).hasSize(20)
        runTileBuild(options, metrics, { "same" }, unused, create)
        assertThat(creations).isEqualTo(3)
        assertThrows(IllegalArgumentException::class.java) { runTileBuild(options, metrics, { "changed" }, unused, create) }
        assertThat(readTiles(output)).hasSize(20)
    }

    @Test
    fun `merge rejects overlaps and keeps previous output intact`() {
        val shard = File(dir, "shard.mbtiles")
        MbtilesTileRepository(shard, SingletonMetricsProvider()).use {
            it.store(TileId(2, 1, 2), "pbf", byteArrayOf(1, 2, 3))
        }
        val output = File(dir, "output.mbtiles")
        mergeShardMbtiles(output, listOf(shard))
        val before = readTiles(output)
        assertThrows(Exception::class.java) { mergeShardMbtiles(output, listOf(shard, shard)) }
        assertThat(readTiles(output)).isEqualTo(before)
        assertThat(File(dir, "output.mbtiles.merging")).doesNotExist()
        assertThrows(Exception::class.java) { mergeShardMbtiles(output, listOf(shard, File(dir, "missing.mbtiles"))) }
        assertThat(readTiles(output)).isEqualTo(before)
        assertThat(File(dir, "output.mbtiles.merging")).doesNotExist()
        val staleJournal = File(dir, "output.mbtiles.merging-journal").also { it.writeText("interrupted merge") }
        mergeShardMbtiles(output, listOf(shard))
        assertThat(staleJournal).doesNotExist()
        assertThat(readTiles(output)).isEqualTo(before)
    }

    @Test
    fun `bulk merge indexes interleaved shards and preserves every payload`() {
        val expected = mutableMapOf<String, String>()
        val shards = (0..2).map { shardId ->
            File(dir, "shard $shardId #1.mbtiles").also { file ->
                MbtilesTileRepository(file, SingletonMetricsProvider()).use { repository ->
                    for (x in (0..20).reversed().filter { it % 3 == shardId }) {
                        val bytes = byteArrayOf(x.toByte(), shardId.toByte())
                        repository.store(TileId(6, x, 1), "pbf", bytes)
                        expected["6/$x/62"] = bytes.joinToString("") { "%02X".format(it) }
                    }
                }
            }
        }
        val output = File(dir, "merged.mbtiles")
        val sourceBytes = shards.map { it.readBytes() }
        mergeShardMbtiles(output, shards)
        shards.zip(sourceBytes).forEach { (file, bytes) -> assertThat(file.readBytes()).isEqualTo(bytes) }
        assertThat(readTiles(output)).isEqualTo(expected)
        JDBC.createConnection("jdbc:sqlite:${output.absolutePath}", Properties()).use { c ->
            c.createStatement().use { s ->
                s.executeQuery("PRAGMA page_size").use { r ->
                    assertThat(r.next()).isTrue()
                    assertThat(r.getInt(1)).isEqualTo(65536)
                }
                s.executeQuery("PRAGMA integrity_check").use { r ->
                    assertThat(r.next()).isTrue()
                    assertThat(r.getString(1)).isEqualTo("ok")
                }
                assertThrows(Exception::class.java) {
                    s.executeUpdate("INSERT INTO tiles SELECT * FROM tiles LIMIT 1")
                }
            }
        }
        assertThat(File(dir, "merged.mbtiles.merging")).doesNotExist()
        assertThat(File(dir, "merged.mbtiles.merging-journal")).doesNotExist()
    }

    @Test
    fun `merge page sizes preserve small and overflowing payloads`() {
        val shard = File(dir, "page-source.mbtiles")
        MbtilesTileRepository(shard, SingletonMetricsProvider()).use { repository ->
            repository.store(TileId(3, 1, 2), "pbf", byteArrayOf(1, 2, 3))
            repository.store(TileId(3, 2, 2), "pbf", ByteArray(150_000) { (it % 251).toByte() })
        }
        val expected = readTiles(shard)
        for (size in listOf(4096, 16384, 65536)) {
            val output = File(dir, "pages-$size.mbtiles")
            mergeShardMbtiles(output, listOf(shard), size)
            assertThat(readTiles(output)).isEqualTo(expected)
            JDBC.createConnection("jdbc:sqlite:${output.absolutePath}", Properties()).use { c ->
                c.createStatement().use { s ->
                    s.executeQuery("PRAGMA page_size").use { r ->
                        assertThat(r.next()).isTrue()
                        assertThat(r.getInt(1)).isEqualTo(size)
                    }
                    s.executeQuery("PRAGMA integrity_check").use { r ->
                        assertThat(r.next()).isTrue()
                        assertThat(r.getString(1)).isEqualTo("ok")
                    }
                }
            }
            assertThrows(IllegalArgumentException::class.java) { mergeShardMbtiles(output, listOf(shard), 4097) }
            assertThat(readTiles(output)).isEqualTo(expected)
        }
    }

    private fun readTiles(file: File): Map<String, String> =
        JDBC.createConnection("jdbc:sqlite:${file.absolutePath}", Properties()).use { c ->
            c.createStatement().use { s ->
                s.executeQuery("SELECT zoom_level,tile_column,tile_row,hex(tile_data) FROM tiles").use { r ->
                    buildMap { while (r.next()) put("${r.getInt(1)}/${r.getInt(2)}/${r.getInt(3)}", r.getString(4)) }
                }
            }
        }
}
