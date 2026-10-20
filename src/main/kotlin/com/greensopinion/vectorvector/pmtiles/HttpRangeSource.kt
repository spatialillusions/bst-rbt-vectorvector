package com.greensopinion.vectorvector.pmtiles

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

private val log = KotlinLogging.logger { }

/**
 * Reads byte ranges over HTTP(S). Retries transient failures, and fails if the archive's ETag
 * changes mid-run (the archive was replaced, so cached directory offsets are no longer valid).
 */
class HttpRangeSource(
    private val url: String,
    private val maxAttempts: Int = 3,
    private val initialBackoff: Duration = Duration.ofMillis(500),
    maxConcurrentRequests: Int = 16,
    private val client: HttpClient = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build()
) : RangeSource {
    override val name: String = url
    private val permits = Semaphore(maxConcurrentRequests)

    @Volatile
    private var etag: String? = null

    val requests = AtomicLong()
    val bytesRead = AtomicLong()

    override fun read(offset: Long, length: Int): ByteArray {
        require(length > 0) { "length must be positive" }
        var backoff = initialBackoff
        var attempt = 1
        while (true) {
            try {
                return readOnce(offset, length)
            } catch (e: RetryableException) {
                if (attempt >= maxAttempts) {
                    throw IOException("$url: giving up after $attempt attempts: ${e.message}", e)
                }
                log.warn { "$url: ${e.message}, retrying in ${backoff.toMillis()}ms" }
                Thread.sleep(backoff.toMillis())
                backoff = backoff.multipliedBy(2)
                attempt += 1
            }
        }
    }

    private fun readOnce(offset: Long, length: Int): ByteArray {
        val request = HttpRequest.newBuilder(URI.create(url))
            .header("Range", "bytes=$offset-${offset + length - 1}")
            .header("User-Agent", USER_AGENT)
            .timeout(Duration.ofSeconds(120))
            .GET()
            .build()
        val response = try {
            permits.acquire()
            try {
                client.send(request, HttpResponse.BodyHandlers.ofByteArray())
            } finally {
                permits.release()
            }
        } catch (e: IOException) {
            throw RetryableException(e.message ?: e.javaClass.simpleName, e)
        }
        requests.incrementAndGet()
        val status = response.statusCode()
        if (status == 429 || status >= 500) {
            throw RetryableException("HTTP $status", null)
        }
        val body = response.body()
        when (status) {
            206 -> {}
            200 -> if (offset != 0L || body.size > length) {
                throw IOException("$url: server ignored the Range header (HTTP 200 with ${body.size} bytes)")
            }
            416 -> return ByteArray(0)
            else -> throw IOException("$url: unexpected HTTP $status")
        }
        checkEtag(response.headers().firstValue("ETag").orElse(null))
        bytesRead.addAndGet(body.size.toLong())
        return body
    }

    private fun checkEtag(value: String?) {
        if (value == null) {
            return
        }
        synchronized(this) {
            val current = etag
            if (current == null) {
                etag = value
            } else if (current != value) {
                throw IllegalStateException("$url changed during the run (ETag $current -> $value); restart the job")
            }
        }
    }

    override fun close() {
        log.info { "$url: ${requests.get()} requests, ${"%.1f".format(bytesRead.get() / 1e6)} MB downloaded" }
    }

    private class RetryableException(message: String, cause: Throwable?) : Exception(message, cause)

    companion object {
        const val USER_AGENT = "vectorvector-rbt/0.1"
    }
}
