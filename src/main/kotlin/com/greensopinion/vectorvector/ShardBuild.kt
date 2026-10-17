package com.greensopinion.vectorvector

import com.greensopinion.vectorvector.cli.CliOptions
import com.greensopinion.vectorvector.cli.toTileRange
import com.greensopinion.vectorvector.metrics.SingletonMetricsProvider
import com.greensopinion.vectorvector.repository.TileRepository
import com.greensopinion.vectorvector.sink.CompositeTileSink
import com.greensopinion.vectorvector.util.closeSafely
import io.github.oshai.kotlinlogging.KotlinLogging
import org.sqlite.JDBC
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.security.MessageDigest
import java.util.Properties

private val shardLog = KotlinLogging.logger { }

internal data class TileShard(val name: String, val range: TileRange)

internal fun buildShards(range: TileRange, shardsX: Int, shardsY: Int): List<TileShard> {
    require(shardsX > 0 && shardsY > 0)
    fun slices(min: Int, max: Int, requested: Int): List<IntRange> {
        val size = max - min + 1
        val count = minOf(size, requested)
        return (0 until count).map { i ->
            (min + (size.toLong() * i / count).toInt())..
                (min + (size.toLong() * (i + 1) / count).toInt() - 1)
        }
    }
    val partitions = slices(range.minY, range.maxY, shardsY).flatMapIndexed { y, ys ->
        slices(range.minX, range.maxX, shardsX).mapIndexed { x, xs ->
            TileShard("x$x-y$y", TileRange(range.minZ, range.maxZ, xs.first, xs.last,
                ys.first, ys.last, range.bounds, range.only))
        }
    }
    // Sparse pilots should not create thousands of databases for unselected roots.
    // Keep one partition for an entirely empty selection so output metadata still exists.
    return if (range.only == null) partitions else partitions.filter { it.range.size > 0 }.ifEmpty { partitions.take(1) }
}

/** Conservative identity: exact CLI, local input size/mtime and executable bytecode. No planet-file hashing. */
internal fun buildIdentity(options: CliOptions, args: Array<String>): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun add(value: String) { digest.update(value.toByteArray(Charsets.UTF_8)); digest.update(0.toByte()) }
    add("vectorvector-shards-v1")
    args.forEach(::add)
    val inputs = options.pmtiles.filterNot { it.startsWith("http://") || it.startsWith("https://") }.map(::File) +
        listOfNotNull(options.dataDir, options.glaciers, options.glacierWkb, options.resolvedPeaks, options.tileList)
    inputs.distinctBy { it.canonicalPath }.sortedBy { it.canonicalPath }.forEach { file ->
        require(file.exists()) { "Missing input: $file" }
        if (file.isDirectory) {
            file.walkTopDown().filter { it.isFile }.sortedBy { it.absolutePath }.forEach {
                add("${it.canonicalPath}:${it.length()}:${it.lastModified()}")
            }
        } else add("${file.canonicalPath}:${file.length()}:${file.lastModified()}")
    }
    val code = File(TileShard::class.java.protectionDomain.codeSource.location.toURI())
    val codeFiles = if (code.isDirectory) code.walkTopDown().filter { it.isFile && it.extension == "class" }
        .sortedBy { it.relativeTo(code).path }.toList() else listOf(code)
    val buffer = ByteArray(65536)
    for (file in codeFiles) {
        add(if (code.isDirectory) file.relativeTo(code).path else "jar")
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun runTileBuild(
    options: CliOptions,
    metrics: SingletonMetricsProvider,
    identity: () -> String,
    defaultFactory: RepositoryFactory,
    createSinks: (RepositoryFactory) -> Map<TileSink, TileRepository>
) {
    val shards = buildShards(options.toTileRange(), options.shardsX, options.shardsY)
    fun process(shard: TileShard, sinks: Map<TileSink, TileRepository>) {
        shardLog.info { "Processing shard ${shard.name} (${shard.range.size} tiles)" }
        Processor(shard.range, CompositeTileSink(sinks.keys.toList()), metrics, options.parallelism).process()
    }
    if (!options.resumableShards) {
        val sinks = createSinks(defaultFactory)
        try { shards.forEach { process(it, sinks) } } finally { closeSafely(sinks.values) }
        return
    }
    val work = (options.shardWorkDir ?: File(options.outputDir, ".shards")).canonicalFile
    require(work.isDirectory || work.mkdirs()) { "Cannot create shard directory $work" }
    FileChannel.open(File(work, "build.lock").toPath(), CREATE, WRITE).use { channel ->
        val lock = channel.tryLock() ?: error("Another build is using $work")
        lock.use {
            val fingerprint = identity()
            val manifest = File(work, "build.sha256")
            if (manifest.exists()) {
                require(manifest.readText().trim() == fingerprint) {
                    "Shard configuration, code or inputs changed. Use a new --shardWorkDir to avoid stale output: $work"
                }
            } else {
                require(work.listFiles().orEmpty().all { it.name == "build.lock" }) {
                    "Shard directory has artifacts but no manifest: $work. Use a new --shardWorkDir."
                }
                atomicText(manifest, fingerprint)
            }
            val names = buildList {
                if (options.terrarium) add("terrarium")
                if (options.resolvedHillshadeRaster) add("hillshade")
                if (options.vector) add(options.resolvedVectorName)
            }
            require(names.isNotEmpty() && names.distinct().size == names.size) { "Output names must be nonempty and distinct" }
            require(names.all { File(it).name == it && it != "." && it != ".." }) { "Output names must be filenames" }
            for (shard in shards) {
                val files = names.associateWith { File(work, "$it/${shard.name}.mbtiles") }
                val marker = File(work, "${shard.name}.done")
                fun stamp() = files.entries.joinToString("\n") { (name, f) -> "$name:${f.length()}:${f.lastModified()}" }
                if (files.values.all { it.isFile } && marker.isFile && marker.readText() == stamp()) {
                    shardLog.info { "Reusing completed shard ${shard.name}" }
                    continue
                }
                Files.deleteIfExists(marker.toPath())
                val sinks = createSinks { name, format, layers ->
                    createMbTilesRepository(options, format, files.getValue(name), metrics, layers)
                }
                try { process(shard, sinks) } finally { closeSafely(sinks.values) }
                // A marker is published only after every asynchronous writer closes successfully.
                atomicText(marker, stamp())
            }
            for (name in names) {
                mergeShardMbtiles(File(options.outputDir, "$name.mbtiles"),
                    shards.map { File(work, "$name/${it.name}.mbtiles") })
            }
        }
    }
}

private fun atomicText(file: File, text: String) {
    val temporary = File(file.parentFile, "${file.name}.tmp")
    temporary.writeText(text)
    Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
}

/** Bulk-load a disposable database, then publish only after indexing, closing and flushing it. */
@JvmOverloads
internal fun mergeShardMbtiles(output: File, shards: List<File>, pageSize: Int = 65536) {
    require(shards.isNotEmpty())
    require(pageSize in 512..65536 && pageSize.countOneBits() == 1) { "Invalid SQLite page size: $pageSize" }
    output.absoluteFile.parentFile.mkdirs()
    val temporary = File(output.absoluteFile.parentFile, "${output.name}.merging")
    require(shards.none { it.canonicalFile == output.canonicalFile || it.canonicalFile == temporary.canonicalFile })
    Files.deleteIfExists(temporary.toPath())
    // A killed merge may leave a hot journal belonging to the discarded temporary database.
    for (suffix in listOf("-journal", "-wal", "-shm")) {
        Files.deleteIfExists(File(temporary.path + suffix).toPath())
    }
    try {
        JDBC.createConnection("jdbc:sqlite:${temporary.absolutePath}", Properties()).use { c ->
            fun sql(value: String) { c.createStatement().use { it.execute(value) } }
            sql("PRAGMA main.page_size=$pageSize")
            // This file is never resumed or exposed to readers. On any error discard it;
            // OFF mode cannot safely roll back a failed statement or transaction.
            sql("PRAGMA main.journal_mode=OFF")
            sql("PRAGMA main.synchronous=OFF")
            sql("PRAGMA cache_size=-16384")
            sql("PRAGMA temp_store=FILE")
            sql("CREATE TABLE metadata (name TEXT PRIMARY KEY, value TEXT)")
            sql("CREATE TABLE tiles (zoom_level INTEGER, tile_column INTEGER, tile_row INTEGER, tile_data BLOB)")
            for ((index, shard) in shards.withIndex()) {
                require(shard.isFile) { "Missing shard: $shard" }
                shardLog.info { "Merging shard ${index + 1}/${shards.size}: ${shard.name}" }
                c.prepareStatement("ATTACH DATABASE ? AS shard").use {
                    it.setString(1, shard.toPath().toAbsolutePath().toUri().toASCIIString() + "?mode=ro")
                    it.execute()
                }
                try {
                    // A bounded read-only mapping avoids a syscall for every cold
                    // 4 KiB source page. It is released when this shard detaches.
                    sql("PRAGMA shard.mmap_size=268435456")
                    if (index == 0) sql("INSERT INTO metadata SELECT name,value FROM shard.metadata")
                    // Scan source rows directly; sorting through its index adds random
                    // reads to cold shards. The destination index is built afterwards.
                    sql("INSERT INTO tiles SELECT zoom_level,tile_column,tile_row,tile_data FROM shard.tiles")
                } finally { sql("DETACH DATABASE shard") }
            }
            // Build once, rather than updating a growing B-tree for every shard.
            // Overlaps still fail here before publication; never silently drop duplicates.
            shardLog.info { "Building merged tile index" }
            sql("CREATE UNIQUE INDEX tile_index ON tiles (zoom_level,tile_column,tile_row)")
        }
        // No per-shard fsyncs are needed, but the finished file must be flushed before rename.
        FileChannel.open(temporary.toPath(), WRITE).use { it.force(true) }
        Files.move(temporary.toPath(), output.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    } catch (e: Exception) {
        try { Files.deleteIfExists(temporary.toPath()) }
        catch (cleanup: Exception) { e.addSuppressed(cleanup) }
        throw e
    }
}
