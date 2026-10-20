package com.greensopinion.vectorvector.pmtiles

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import io.github.oshai.kotlinlogging.KotlinLogging
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

private val log = KotlinLogging.logger { }

/**
 * Caches ranges of a (remote) source on disk, keyed by sha1(name) + offset + length.
 * Writes go to a temp file followed by an atomic move, so concurrent readers never see partial files.
 */
class CachingRangeSource(
    private val delegate: RangeSource,
    cacheDir: File
) : RangeSource {
    override val name: String = delegate.name
    private val dir = File(cacheDir, sha1(delegate.name)).also { it.mkdirs() }

    private val hits = AtomicLong()
    private val misses = AtomicLong()

    override fun read(offset: Long, length: Int): ByteArray {
        val file = File(dir, "$offset-$length.bin")
        if (file.exists()) {
            hits.incrementAndGet()
            return file.readBytes()
        }
        misses.incrementAndGet()
        val bytes = delegate.read(offset, length)
        val temp = File.createTempFile("range", ".tmp", dir)
        temp.writeBytes(bytes)
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        return bytes
    }

    override fun close() {
        log.info { "${delegate.name}: disk cache ${hits.get()} hits, ${misses.get()} misses" }
        delegate.close()
    }
}

private fun sha1(value: String): String =
    MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
