package com.greensopinion.vectorvector.osm

import com.greensopinion.vectorvector.TileId
import com.greensopinion.vectorvector.WebMercator
import com.greensopinion.vectorvector.sink.contour.IceSplitter
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.index.strtree.STRtree
import org.locationtech.jts.io.WKBReader
import org.locationtech.jts.io.WKBWriter
import java.io.*
import java.nio.file.Files
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.Properties
import io.github.oshai.kotlinlogging.KotlinLogging

internal fun spatialHash(file: File): String = file.inputStream().buffered().use { stream ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(65536)
    while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    digest.digest().joinToString("") { "%02x".format(it) }
}

internal data class SpatialPartition(val root: TileId, val mask: Geometry?, val peaks: List<Peak>) {
    val estimatedBytes get() = (mask?.numPoints ?: 0) * 64L + peaks.sumOf {
        512L + 4L * ((it.name?.length ?: 0) + (it.nameEn?.length ?: 0))
    } + 1024
}

internal object SpatialCodec {
    private const val MAGIC = 0x56565331
    private fun DataOutputStream.optional(value: String?) { writeBoolean(value != null); value?.let(::writeUTF) }
    private fun DataInputStream.optional(): String? = if (readBoolean()) readUTF() else null
    fun write(file: File, value: SpatialPartition) {
        val temporary = File(file.path + ".tmp")
        FileOutputStream(temporary).use { raw ->
            val output = DataOutputStream(BufferedOutputStream(raw))
            output.writeInt(MAGIC); output.writeInt(value.root.x); output.writeInt(value.root.y)
            val mask = value.mask?.let { WKBWriter(2).write(it) }
            output.writeInt(mask?.size ?: 0); mask?.let(output::write)
            output.writeInt(value.peaks.size)
            for (p in value.peaks) {
                output.writeLong(p.osmId); output.writeDouble(p.lon); output.writeDouble(p.lat)
                output.optional(p.name); output.optional(p.nameEn)
                output.writeBoolean(p.ele != null); p.ele?.let(output::writeInt)
                output.optional(p.eleSource); output.writeUTF(p.kind)
            }
            output.flush(); raw.fd.sync()
        }
        Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }
    fun read(file: File, expected: TileId): SpatialPartition = DataInputStream(file.inputStream().buffered()).use { input ->
        require(file.length() <= 128L * 1048576) { "Spatial partition is too large: $file" }
        require(input.readInt() == MAGIC) { "Invalid spatial format: $file" }
        val root = TileId(6, input.readInt(), input.readInt())
        require(root == expected) { "Spatial root mismatch: $file" }
        val bytes = input.readInt()
        require(bytes in 0..(128 * 1048576) && bytes.toLong() <= file.length()) { "Invalid mask length" }
        val mask = if (bytes == 0) null else {
            val encoded = input.readNBytes(bytes); require(encoded.size == bytes) { "Truncated mask" }
            WKBReader().read(encoded)
        }
        require(mask == null || mask is org.locationtech.jts.geom.Polygonal) { "Invalid spatial mask geometry" }
        val count = input.readInt(); require(count in 0..2000000) { "Invalid peak count" }
        val peaks = ArrayList<Peak>(minOf(count, 10000))
        repeat(count) {
            val id = input.readLong(); val lon = input.readDouble(); val lat = input.readDouble()
            require(lon.isFinite() && lat.isFinite() && lon in -180.0..180.0 && lat in -90.0..90.0)
            val name = input.optional(); val en = input.optional()
            val elevation = if (input.readBoolean()) input.readInt() else null
            peaks.add(Peak(id, lon, lat, name, en, elevation, input.optional(), input.readUTF()))
        }
        require(input.read() == -1) { "Trailing spatial data: $file" }
        SpatialPartition(root, mask, peaks)
    }
}

/** At most two prepared roots. Oversize active roots remain usable instead of reloading per tile. */
internal class PreparedSpatialStore(
    private val directory: File,
    private val peakFactory: (List<Peak>) -> PeakIndex,
    private val glacierEntries: Long,
    private val glacierBytes: Long,
    private val rootBytes: Long = 64L * 1048576
) {
    private val manifest = Properties().also { p -> File(directory, "manifest.properties").inputStream().use(p::load) }
    val hasPeaks: Boolean
    val peakSchema get() = if (hasPeaks) peakFactory(emptyList()).schema() else null
    private data class Loaded(val value: SpatialPartition, val ice: GlacierIndex, val peaks: PeakIndex)
    private val cache = LinkedHashMap<TileId, Loaded>(4, 0.75f, true)
    init {
        require(manifest.getProperty("version") == "1" && manifest.getProperty("complete") == "true" &&
            manifest.getProperty("rootZoom") == "6" && manifest.getProperty("partitions") == "4096") {
            "Incomplete or incompatible prepared spatial store: $directory"
        }
        hasPeaks = manifest.getProperty("peaks") == "true"
    }
    fun validateSource(role: String, file: File?) {
        if (file == null) return
        require(manifest.getProperty("$role.path") == file.canonicalPath &&
            manifest.getProperty("$role.bytes") == file.length().toString() &&
            manifest.getProperty("$role.modified") == file.lastModified().toString()) {
            "Prepared spatial source mismatch: $role $file"
        }
    }
    @Synchronized private fun partition(tile: TileId): Loaded {
        require(tile.z >= 6)
        val root = TileId(6, Math.floorMod(tile.x, 1 shl tile.z) shr (tile.z - 6), tile.y shr (tile.z - 6))
        cache[root]?.let { return it }
        val name = "x${root.x}-y${root.y}.bin"
        val file = File(directory, name)
        require(file.isFile && spatialHash(file) == manifest.getProperty("sha.$name")) { "Missing/corrupt spatial partition: $file" }
        val data = SpatialCodec.read(file, root)
        val loaded = Loaded(data, GlacierIndex(emptyList(), cacheSize = glacierEntries,
            cacheBytes = glacierBytes / 2, preparedRoot = root to data.mask), peakFactory(data.peaks))
        cache[root] = loaded
        while (cache.size > 1 && (cache.size > 2 || cache.values.sumOf { it.value.estimatedBytes } > rootBytes)) {
            cache.remove(cache.keys.first())
        }
        KotlinLogging.logger {}.info {
            "Prepared spatial root $root: peaks=${data.peaks.size}, maskPoints=${data.mask?.numPoints ?: 0}, " +
                "estimatedRootMiB=${data.estimatedBytes / 1048576}, retainedRoots=${cache.size}"
        }
        return loaded
    }
    fun ice(tile: TileId): IceSplitter? = partition(tile).ice.forTile(tile)?.let { GlacierIceSplitter(it) }
    fun peaks(tile: TileId) = if (hasPeaks && tile.z >= 6) partition(tile).peaks.layer(tile) else null
}

/** Offline preparation entry point: out-directory glacier-pbf-or-- ice-wkb-or-- peak-pbf-or--. */
object PrepareSpatialInputs {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 4) { "Usage: PrepareSpatialInputs <out> <glaciers.pbf|-> <ice.wkb|-> <peaks.pbf|->" }
        val out = File(args[0]).canonicalFile
        require(out.isDirectory || out.mkdirs())
        FileChannel.open(File(out, "prepare.lock").toPath(), CREATE, WRITE).use { channel ->
            val lock = channel.tryLock() ?: error("Another preparer is using $out")
            lock.use { prepare(args) }
        }
    }
    private fun prepare(args: Array<String>) {
        val out = File(args[0]).canonicalFile
        require(out.isDirectory || out.mkdirs())
        val inputs = listOf("glaciers", "ice", "peaks").zip(args.drop(1)).mapNotNull { (role, path) ->
            if (path == "-") null else role to File(path).canonicalFile.also { require(it.isFile) }
        }.toMap()
        val provenance = Properties().also { p ->
            p["version"] = "1"; p["rootZoom"] = "6"; p["partitions"] = "4096"
            p["peaks"] = inputs.containsKey("peaks").toString()
            for ((role, file) in inputs) {
                p["$role.path"] = file.canonicalPath; p["$role.bytes"] = file.length().toString()
                p["$role.modified"] = file.lastModified().toString(); p["$role.sha256"] = spatialHash(file)
            }
        }
        val sourceFile = File(out, "sources.properties")
        if (sourceFile.exists()) {
            val old = Properties().also { p -> sourceFile.inputStream().use(p::load) }
            require(old == provenance) { "Preparation inputs changed; use a fresh directory" }
        } else {
            require(out.listFiles().orEmpty().all { it.name == "prepare.lock" }) { "Preparation directory is not empty" }
            sourceFile.outputStream().use { provenance.store(it, "Spatial input provenance") }
        }
        val completed = File(out, "manifest.properties")
        if (completed.isFile) {
            val previous = Properties().also { p -> completed.inputStream().use(p::load) }
            require(previous.getProperty("complete") == "true")
            for (y in 0..63) for (x in 0..63) {
                val file = File(out, "x$x-y$y.bin")
                require(file.isFile && spatialHash(file) == previous.getProperty("sha.${file.name}")) {
                    "Completed spatial store is corrupt: $file"
                }
            }
            println("Existing spatial store verified: $out")
            return
        }
        val glaciers = buildList {
            inputs["glaciers"]?.let { addAll(GlacierLoader(it).load()) }
            inputs["ice"]?.let { addAll(GlacierWkbLoader(it).load()) }
        }
        val ice = GlacierIndex(glaciers, cacheSize = 16, cacheBytes = 64L * 1048576)
        val peaks = inputs["peaks"]?.let { PeakLoader(it).load() }.orEmpty()
        val tree = STRtree().also { t -> peaks.forEach { p -> t.insert(Envelope(p.lon, p.lon, p.lat, p.lat), p) }; t.build() }
        for (y in 0..63) for (x in 0..63) {
            val root = TileId(6, x, y)
            val file = File(out, "x$x-y$y.bin"); val stamp = File(file.path + ".sha256")
            if (file.isFile && stamp.isFile && spatialHash(file) == stamp.readText().trim()) {
                provenance["sha.${file.name}"] = stamp.readText().trim()
                continue
            }
            val margin = 256.0 / 4096
            val envelope = Envelope(WebMercator.tileXToLon(x - margin, 6), WebMercator.tileXToLon(x + 1 + margin, 6),
                WebMercator.tileYToLat(y + 1 + margin, 6), WebMercator.tileYToLat(y - margin, 6))
            @Suppress("UNCHECKED_CAST")
            val localPeaks = longitudeQueries(envelope).flatMap { (query, _) -> tree.query(query) as List<Peak> }
                .distinctBy { it.osmId }.sortedBy { it.osmId }
            SpatialCodec.write(file, SpatialPartition(root, ice.lonLatForTile(root), localPeaks))
            val hash = spatialHash(file); stamp.writeText(hash); provenance["sha.${file.name}"] = hash
            if ((y * 64 + x) % 64 == 0) println("Prepared ${y * 64 + x + 1}/4096 spatial partitions")
        }
        provenance["complete"] = "true"
        val pending = File(out, "manifest.properties.tmp")
        FileOutputStream(pending).use { provenance.store(it, "Prepared spatial store v1"); it.fd.sync() }
        Files.move(pending.toPath(), File(out, "manifest.properties").toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        println("Prepared spatial store complete: $out")
    }
}
