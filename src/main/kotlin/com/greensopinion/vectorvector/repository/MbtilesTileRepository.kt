package com.greensopinion.vectorvector.repository

import com.greensopinion.vectorvector.TileId
import com.greensopinion.vectorvector.metrics.MetricsProvider
import com.greensopinion.vectorvector.util.newThreadFactory
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sqlite.JDBC
import java.io.File
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.Properties
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Metadata per the [spec](https://github.com/mapbox/mbtiles-spec/blob/master/1.3/spec.md)
 */
class MbtilesMetadata(
    val name: String,
    val format: String,
    val minZoom: Int,
    val maxZoom: Int,
    val type: String = "overlay",
    val json: String,
    val bounds: String = "-180.0,-85,180,85",
    val attribution: String? = null,
    val description: String? = null,
    val version: String? = null
) {
    /** Rows of the metadata table, using the key names from the MBTiles 1.3 spec. */
    fun rows(): Map<String, String> = linkedMapOf(
        "name" to name,
        "format" to format,
        "minzoom" to minZoom.toString(),
        "maxzoom" to maxZoom.toString(),
        "type" to type,
        "json" to json,
        "bounds" to bounds,
        "attribution" to attribution,
        "description" to description,
        "version" to version
    ).filterValues { !it.isNullOrBlank() }.mapValues { it.value!! }
}

class MbtilesTileRepository(
    private val outputFile: File,
    private val metricsProvider: MetricsProvider,
    private val truncate: Boolean = true,
    private val log: KLogger = KotlinLogging.logger { }
) : TileRepository {
    private val errors = CopyOnWriteArrayList<Exception>()
    private val maximumQueueSize = 200
    private val executor = ThreadPoolExecutor(
        1, 1,
        0L, TimeUnit.MILLISECONDS,
        LinkedBlockingQueue<Runnable>(maximumQueueSize),
        newThreadFactory("Mbtiles-writer")
    ) { r, e ->
        e.queue.put(r)
    }

    private val connection: Connection = createConnection()
    private val insert = prepareInsertStatement()
    private var pendingWrites = 0

    fun setMetadata(metadata: MbtilesMetadata) {
        for ((name, value) in metadata.rows()) {
            connection.prepareStatement("INSERT INTO metadata (name,value) VALUES (?,?) ON CONFLICT(name) DO UPDATE SET value=excluded.value;").use {
                it.setString(1, name)
                it.setString(2, value)
                if (it.executeUpdate() != 1) {
                    throw Exception("unexpected result for $name:$value")
                }
            }
        }
        connection.commit()
    }

    override fun store(tile: TileId, extension: String, bytes: ByteArray) {
        check(!executor.isShutdown) { "MBTiles repository is closed" }
        if (errors.isNotEmpty()) throw IllegalStateException("MBTiles writer failed", errors.first())
        executor.submit { write(tile, bytes) }
    }

    fun read(tile: TileId): Future<ByteArray> {
        val result = CompletableFuture<ByteArray>()
        executor.submit {
            try {
                result.complete(readBytes(tile))
            } catch (e: Exception) {
                result.completeExceptionally(e)
            }
        }
        return result
    }

    private fun readBytes(tile: TileId): ByteArray {
        val statement =
            connection.prepareStatement("SELECT tile_data FROM tiles WHERE zoom_level = ? AND tile_column = ? AND tile_row = ?")
        statement.use {
            it.setInt(1, tile.z)
            it.setInt(2, tile.x)
            it.setInt(3, tile.y.toTms(tile.z))
            val results = it.executeQuery()
            results.use { r ->
                if (r.next()) {
                    return r.getBinaryStream(1).readAllBytes()
                }
                throw Exception("not found: $tile")
            }
        }
    }

    private fun write(tile: TileId, bytes: ByteArray) {
        try {
            insert.setInt(1, tile.z)
            insert.setInt(2, tile.x)
            insert.setInt(3, tile.y.toTms(tile.z))
            insert.setBytes(4, bytes)
            insert.execute()
            if (++pendingWrites >= 1000) {
                connection.commit()
                pendingWrites = 0
            }
            metricsProvider.get().addCount("Mbtiles.written")
        } catch (e: Exception) {
            log.error(e) { "Cannot write tile $tile: ${e.message}" }
            errors.add(e)
            if (errors.size > 10) {
                errors.removeAt(0)
            }
        }
    }

    override fun close() {
        executor.shutdown()
        check(executor.awaitTermination(10, TimeUnit.MINUTES)) { "Timed out draining MBTiles writer" }
        try {
            if (errors.isEmpty()) connection.commit() else connection.rollback()
        } finally {
            try { insert.close() } finally { connection.close() }
        }
        if (errors.isNotEmpty()) throw errors.first()
    }

    private fun createConnection(): Connection {
        outputFile.absoluteFile.parentFile.mkdirs()
        val exists = outputFile.exists()
        if (exists && truncate) {
            java.nio.file.Files.delete(outputFile.toPath())
            for (suffix in listOf("-journal", "-wal", "-shm")) {
                java.nio.file.Files.deleteIfExists(File(outputFile.path + suffix).toPath())
            }
        }
        return JDBC.createConnection("jdbc:sqlite:${outputFile.absolutePath}", Properties()).also {
            it.executeStatement("PRAGMA synchronous=NORMAL")
            it.executeStatement("PRAGMA cache_size=-16384")
            initialize(it)
            it.autoCommit = false
        }
    }

    private fun prepareInsertStatement(): PreparedStatement =
        connection.prepareStatement("INSERT INTO tiles (zoom_level,tile_column,tile_row,tile_data) VALUES (?,?,?,?)")


    private fun initialize(connection: Connection) {
        if (!tableExists(connection, "metadata")) {
            connection.executeStatement("CREATE TABLE metadata (name text,value text);")
            connection.executeStatement("CREATE UNIQUE INDEX name on metadata (name);")
        }
        if (!tableExists(connection, "tiles")) {
            connection.executeStatement("CREATE TABLE tiles (zoom_level integer, tile_column integer, tile_row integer, tile_data blob);")
            connection.executeStatement("CREATE UNIQUE INDEX tile_index on tiles (zoom_level, tile_column, tile_row);")
        }
    }

    private fun tableExists(connection: Connection, name: String): Boolean =
        connection.createStatement().use {
            it.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name = '$name';").use { result ->
                result.next()
            }
        }

}

private fun Connection.executeStatement(sql: String) {
    createStatement().use { statement ->
        statement.execute(sql)
    }
}

private fun Int.toTms(z: Int) = (1 shl z) - 1 - this