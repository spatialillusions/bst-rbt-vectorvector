package com.greensopinion.vectorvector.pmtiles

/** A directory entry. [runLength] == 0 means the entry points to a leaf directory. */
data class Entry(val tileId: Long, val offset: Long, val length: Int, val runLength: Int)

object PmtilesDirectory {
    /** Deserializes an (already decompressed) directory. */
    fun deserialize(bytes: ByteArray): List<Entry> {
        val reader = VarintReader(bytes)
        val count = reader.next().toInt()
        val tileIds = LongArray(count)
        var lastId = 0L
        for (i in 0..<count) {
            lastId += reader.next()
            tileIds[i] = lastId
        }
        val runLengths = IntArray(count) { reader.next().toInt() }
        val lengths = IntArray(count) { reader.next().toInt() }
        val offsets = LongArray(count)
        for (i in 0..<count) {
            val value = reader.next()
            offsets[i] = if (value == 0L && i > 0) offsets[i - 1] + lengths[i - 1] else value - 1
        }
        return List(count) { Entry(tileIds[it], offsets[it], lengths[it], runLengths[it]) }
    }

    /** Finds the entry containing [tileId]: an exact/run match, or the leaf directory that may contain it. */
    fun find(entries: List<Entry>, tileId: Long): Entry? {
        var low = 0
        var high = entries.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val cmp = tileId.compareTo(entries[mid].tileId)
            when {
                cmp > 0 -> low = mid + 1
                cmp < 0 -> high = mid - 1
                else -> return entries[mid]
            }
        }
        if (high >= 0) {
            val entry = entries[high]
            if (entry.runLength == 0 || tileId - entry.tileId < entry.runLength) {
                return entry
            }
        }
        return null
    }
}

class VarintReader(private val bytes: ByteArray) {
    private var position = 0

    fun next(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(position < bytes.size) { "truncated varint" }
            val b = bytes[position++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) {
                return result
            }
            shift += 7
            require(shift < 64) { "varint too long" }
        }
    }
}
