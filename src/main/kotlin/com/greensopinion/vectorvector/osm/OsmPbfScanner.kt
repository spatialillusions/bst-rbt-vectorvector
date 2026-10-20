package com.greensopinion.vectorvector.osm

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.zip.Inflater

enum class MemberType { NODE, WAY, RELATION }

data class RelationMember(val type: MemberType, val ref: Long, val role: String)

/** Callbacks for [OsmPbfScanner]. Only the element types a handler wants are decoded. Calls arrive in file order. */
interface OsmHandler {
    val wantsNodes: Boolean get() = false
    val wantsWays: Boolean get() = false
    val wantsRelations: Boolean get() = false

    /** Prefilter for nodes, called concurrently from decoder threads; must be thread-safe. */
    fun wantsNode(id: Long, tags: Map<String, String>): Boolean = true

    /** Nodes. [tags] is empty for most nodes. */
    fun node(id: Long, lon: Double, lat: Double, tags: Map<String, String>) {}
    fun way(id: Long, tags: Map<String, String>, nodes: LongArray) {}
    fun relation(id: Long, tags: Map<String, String>, members: List<RelationMember>) {}
}

/**
 * Streams an OSM PBF file (https://wiki.openstreetmap.org/wiki/PBF_Format) without generated protobuf
 * classes. Blobs are inflated and decoded in parallel; handler callbacks are delivered in file order on the
 * calling thread, so handlers need no synchronisation.
 */
class OsmPbfScanner(private val file: File, private val threads: Int = minOf(8, Runtime.getRuntime().availableProcessors())) {

    fun scan(handler: OsmHandler) {
        val executor = Executors.newFixedThreadPool(threads)
        try {
            val pending = ArrayDeque<Future<List<Element>>>()
            DataInputStream(file.inputStream().buffered(1 shl 20)).use { input ->
                while (true) {
                    val headerLength = try {
                        input.readInt()
                    } catch (e: EOFException) {
                        break
                    }
                    val header = ByteArray(headerLength).also { input.readFully(it) }
                    var type = ""
                    var dataSize = 0
                    val h = ProtoReader(header)
                    while (h.next()) {
                        when (h.field) {
                            1 -> type = h.string()
                            3 -> dataSize = h.int()
                            else -> h.skip()
                        }
                    }
                    val blob = ByteArray(dataSize).also { input.readFully(it) }
                    if (type != "OSMData") {
                        continue
                    }
                    pending.addLast(executor.submit<List<Element>> { decodeBlock(inflate(blob), handler) })
                    while (pending.size > threads * 4) {
                        deliver(pending.removeFirst().get(), handler)
                    }
                }
            }
            while (pending.isNotEmpty()) {
                deliver(pending.removeFirst().get(), handler)
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun deliver(elements: List<Element>, handler: OsmHandler) {
        for (e in elements) {
            when (e) {
                is Element.Node -> handler.node(e.id, e.lon, e.lat, e.tags)
                is Element.Way -> handler.way(e.id, e.tags, e.nodes)
                is Element.Relation -> handler.relation(e.id, e.tags, e.members)
            }
        }
    }

    private sealed class Element {
        class Node(val id: Long, val lon: Double, val lat: Double, val tags: Map<String, String>) : Element()
        class Way(val id: Long, val tags: Map<String, String>, val nodes: LongArray) : Element()
        class Relation(val id: Long, val tags: Map<String, String>, val members: List<RelationMember>) : Element()
    }

    private fun inflate(blob: ByteArray): ByteArray {
        val r = ProtoReader(blob)
        var raw: ByteArray? = null
        var rawSize = 0
        var zlib: ByteArray? = null
        while (r.next()) {
            when (r.field) {
                1 -> raw = r.bytes()
                2 -> rawSize = r.int()
                3 -> zlib = r.bytes()
                4, 6, 7 -> throw UnsupportedOperationException("${file.name}: only raw and zlib PBF blobs are supported")
                else -> r.skip()
            }
        }
        raw?.let { return it }
        val inflater = Inflater()
        try {
            inflater.setInput(zlib ?: throw IllegalStateException("${file.name}: empty blob"))
            val out = ByteArray(rawSize)
            var n = 0
            while (n < rawSize) {
                val read = inflater.inflate(out, n, rawSize - n)
                if (read == 0 && (inflater.finished() || inflater.needsInput())) break
                n += read
            }
            require(n == rawSize) { "${file.name}: inflated $n of $rawSize bytes" }
            return out
        } finally {
            inflater.end()
        }
    }

    private fun decodeBlock(block: ByteArray, handler: OsmHandler): List<Element> {
        val strings = mutableListOf<String>()
        val groups = mutableListOf<ProtoReader>()
        var granularity = 100L
        var latOffset = 0L
        var lonOffset = 0L
        val r = ProtoReader(block)
        while (r.next()) {
            when (r.field) {
                1 -> {
                    val st = r.message()
                    while (st.next()) if (st.field == 1) strings.add(st.string()) else st.skip()
                }
                2 -> groups.add(r.message())
                17 -> granularity = r.long()
                19 -> latOffset = r.long()
                20 -> lonOffset = r.long()
                else -> r.skip()
            }
        }
        val ctx = BlockContext(strings, granularity, latOffset, lonOffset)
        val out = mutableListOf<Element>()
        for (g in groups) {
            while (g.next()) {
                when (g.field) {
                    1 -> if (handler.wantsNodes) node(g.message(), ctx).let { if (handler.wantsNode(it.id, it.tags)) out.add(it) } else g.skip()
                    2 -> if (handler.wantsNodes) denseNodes(g.message(), ctx, out, handler) else g.skip()
                    3 -> if (handler.wantsWays) out.add(way(g.message(), ctx)) else g.skip()
                    4 -> if (handler.wantsRelations) out.add(relation(g.message(), ctx)) else g.skip()
                    else -> g.skip()
                }
            }
        }
        return out
    }

    private class BlockContext(val strings: List<String>, val granularity: Long, val latOffset: Long, val lonOffset: Long) {
        fun lat(v: Long) = 1e-9 * (latOffset + granularity * v)
        fun lon(v: Long) = 1e-9 * (lonOffset + granularity * v)
        fun tags(keys: LongList, values: LongList): Map<String, String> =
            if (keys.size == 0) emptyMap() else HashMap<String, String>(keys.size * 2).also {
                for (i in 0..<keys.size) it[strings[keys[i].toInt()]] = strings[values[i].toInt()]
            }
    }

    private fun node(r: ProtoReader, ctx: BlockContext): Element.Node {
        var id = 0L
        var lat = 0L
        var lon = 0L
        val keys = LongList()
        val values = LongList()
        while (r.next()) {
            when (r.field) {
                1 -> id = r.sint()
                2 -> r.repeatedVarint(keys, false)
                3 -> r.repeatedVarint(values, false)
                8 -> lat = r.sint()
                9 -> lon = r.sint()
                else -> r.skip()
            }
        }
        return Element.Node(id, ctx.lon(lon), ctx.lat(lat), ctx.tags(keys, values))
    }

    private fun denseNodes(r: ProtoReader, ctx: BlockContext, out: MutableList<Element>, handler: OsmHandler) {
        val ids = LongList(8000)
        val lats = LongList(8000)
        val lons = LongList(8000)
        val keysVals = LongList()
        while (r.next()) {
            when (r.field) {
                1 -> r.repeatedVarint(ids, true)
                8 -> r.repeatedVarint(lats, true)
                9 -> r.repeatedVarint(lons, true)
                10 -> r.repeatedVarint(keysVals, false)
                else -> r.skip()
            }
        }
        var id = 0L
        var lat = 0L
        var lon = 0L
        var kv = 0
        for (i in 0..<ids.size) {
            id += ids[i]
            lat += lats[i]
            lon += lons[i]
            var tags: Map<String, String> = emptyMap()
            if (keysVals.size > 0) {
                while (kv < keysVals.size && keysVals[kv] != 0L) {
                    if (tags.isEmpty()) tags = HashMap()
                    (tags as HashMap)[ctx.strings[keysVals[kv].toInt()]] = ctx.strings[keysVals[kv + 1].toInt()]
                    kv += 2
                }
                kv += 1
            }
            if (handler.wantsNode(id, tags)) {
                out.add(Element.Node(id, ctx.lon(lon), ctx.lat(lat), tags))
            }
        }
    }

    private fun way(r: ProtoReader, ctx: BlockContext): Element.Way {
        var id = 0L
        val keys = LongList()
        val values = LongList()
        val refs = LongList(64)
        while (r.next()) {
            when (r.field) {
                1 -> id = r.long()
                2 -> r.repeatedVarint(keys, false)
                3 -> r.repeatedVarint(values, false)
                8 -> r.repeatedVarint(refs, true)
                else -> r.skip()
            }
        }
        val nodes = LongArray(refs.size)
        var ref = 0L
        for (i in 0..<refs.size) {
            ref += refs[i]
            nodes[i] = ref
        }
        return Element.Way(id, ctx.tags(keys, values), nodes)
    }

    private fun relation(r: ProtoReader, ctx: BlockContext): Element.Relation {
        var id = 0L
        val keys = LongList()
        val values = LongList()
        val roles = LongList()
        val memberIds = LongList()
        val types = LongList()
        while (r.next()) {
            when (r.field) {
                1 -> id = r.long()
                2 -> r.repeatedVarint(keys, false)
                3 -> r.repeatedVarint(values, false)
                8 -> r.repeatedVarint(roles, false)
                9 -> r.repeatedVarint(memberIds, true)
                10 -> r.repeatedVarint(types, false)
                else -> r.skip()
            }
        }
        var ref = 0L
        val members = List(memberIds.size) { i ->
            ref += memberIds[i]
            RelationMember(MemberType.entries[types[i].toInt()], ref, ctx.strings[roles[i].toInt()])
        }
        return Element.Relation(id, ctx.tags(keys, values), members)
    }
}
