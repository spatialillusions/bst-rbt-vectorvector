package com.greensopinion.vectorvector.pmtiles

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/** Random access to the bytes of an archive. Implementations must be thread-safe. */
interface RangeSource : Closeable {
    val name: String

    /** Reads up to [length] bytes at [offset]; fewer bytes are returned only at the end of the archive. */
    fun read(offset: Long, length: Int): ByteArray
}

fun rangeSourceFor(pathOrUrl: String, cacheDir: File? = null): RangeSource {
    val isUrl = pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")
    if (!isUrl) {
        return FileRangeSource(File(pathOrUrl))
    }
    val http = HttpRangeSource(pathOrUrl)
    return if (cacheDir != null) CachingRangeSource(http, cacheDir) else http
}

class FileRangeSource(file: File) : RangeSource {
    override val name: String = file.path
    private val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)

    override fun read(offset: Long, length: Int): ByteArray {
        val buffer = ByteBuffer.allocate(length)
        var position = offset
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, position)
            if (read < 0) {
                break
            }
            position += read
        }
        return if (buffer.position() == length) buffer.array() else buffer.array().copyOf(buffer.position())
    }

    override fun close() {
        channel.close()
    }
}
