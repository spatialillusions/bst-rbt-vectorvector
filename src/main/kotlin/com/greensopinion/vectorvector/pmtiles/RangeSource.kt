package com.greensopinion.vectorvector.pmtiles

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** Random access to the bytes of an archive. Implementations must be thread-safe. */
interface RangeSource : Closeable {
    val name: String

    /** Reads up to [length] bytes at [offset]; fewer bytes are returned only at the end of the archive. */
    fun read(offset: Long, length: Int): ByteArray
    fun reopen() {}
}

fun rangeSourceFor(pathOrUrl: String, cacheDir: File? = null): RangeSource {
    val isUrl = pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")
    if (!isUrl) {
        return FileRangeSource(File(pathOrUrl))
    }
    val http = HttpRangeSource(pathOrUrl)
    return if (cacheDir != null) CachingRangeSource(http, cacheDir) else http
}

class FileRangeSource(private val file: File) : RangeSource {
    override val name: String = file.path
    private var channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
    private val lock = ReentrantReadWriteLock()
    private val initialSize = file.length()
    private val initialModified = file.lastModified()
    private var closed = false

    override fun read(offset: Long, length: Int): ByteArray = lock.read {
        check(!closed) { "Archive is closed: $file" }
        val buffer = ByteBuffer.allocate(length)
        var position = offset
        while (buffer.hasRemaining()) {
            val read = channel.read(buffer, position)
            if (read < 0) {
                break
            }
            position += read
        }
        if (buffer.position() == length) buffer.array() else buffer.array().copyOf(buffer.position())
    }

    override fun reopen() = lock.write {
        check(!closed) { "Archive is closed: $file" }
        check(file.length() == initialSize && file.lastModified() == initialModified) { "Archive changed: $file" }
        channel.close()
        try { channel = FileChannel.open(file.toPath(), StandardOpenOption.READ) }
        catch (error: Throwable) { closed = true; throw error }
    }

    override fun close() = lock.write {
        if (!closed) { closed = true; channel.close() }
    }
}
