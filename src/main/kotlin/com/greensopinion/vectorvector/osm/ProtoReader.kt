package com.greensopinion.vectorvector.osm

/** A minimal protobuf wire-format reader over a byte range, enough for the OSM PBF messages. */
class ProtoReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {
    var field: Int = 0
        private set
    var wireType: Int = 0
        private set

    /** Advances to the next field; false at the end of the message. */
    fun next(): Boolean {
        if (pos >= end) {
            return false
        }
        val key = varint()
        field = (key ushr 3).toInt()
        wireType = (key and 7).toInt()
        return true
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = buf[pos++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) {
                return result
            }
            shift += 7
        }
    }

    fun int(): Int = varint().toInt()
    fun long(): Long = varint()
    fun sint(): Long = varint().let { (it ushr 1) xor -(it and 1) }

    fun bytes(): ByteArray {
        val length = int()
        val out = buf.copyOfRange(pos, pos + length)
        pos += length
        return out
    }

    fun string(): String {
        val length = int()
        val out = String(buf, pos, length, Charsets.UTF_8)
        pos += length
        return out
    }

    /** A reader over the embedded message or packed field at the current position. */
    fun message(): ProtoReader {
        val length = int()
        val reader = ProtoReader(buf, pos, pos + length)
        pos += length
        return reader
    }

    /** Reads a repeated varint field, packed or not, into [out]. */
    fun repeatedVarint(out: LongList, signed: Boolean) {
        if (wireType == 2) {
            val packed = message()
            while (packed.pos < packed.end) {
                out.add(if (signed) packed.sint() else packed.varint())
            }
        } else {
            out.add(if (signed) sint() else varint())
        }
    }

    fun skip() {
        when (wireType) {
            0 -> varint()
            1 -> pos += 8
            2 -> {
                val length = int()
                pos += length
            }
            5 -> pos += 4
            else -> throw IllegalStateException("unsupported wire type $wireType")
        }
    }
}

/** A growable list of primitive longs. */
class LongList(capacity: Int = 16) {
    private var data = LongArray(capacity)
    var size = 0
        private set

    fun add(value: Long) {
        if (size == data.size) {
            data = data.copyOf(maxOf(16, size * 2))
        }
        data[size++] = value
    }

    operator fun get(index: Int): Long = data[index]

    fun clear() {
        size = 0
    }

    fun toLongArray(): LongArray = data.copyOf(size)
}
