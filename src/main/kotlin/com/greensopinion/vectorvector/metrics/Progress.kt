package com.greensopinion.vectorvector.metrics

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KotlinLogging
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

class Progress(
    private val total: Long,
    private val interval: Duration = Duration.ofSeconds(30),
    private val log: KLogger = KotlinLogging.logger { }
) {
    private val completed = AtomicLong()
    private val processed = AtomicLong()

    private val start = Instant.now()
    val elapsed: Duration get() = Duration.between(start, Instant.now())

    @Volatile
    private var last = start


    fun completedOne(processed: Boolean) {
        if (processed) {
            this.processed.incrementAndGet()
        }
        if (completed.incrementAndGet() % 1000L == 0L) {
            val last = this.last
            val elapsed = Duration.between(last, Instant.now())
            if (elapsed > interval) {
                this.last = Instant.now()
                logEstimate()
            }
        }
    }

    private fun logEstimate() {
        val elapsed = this.elapsed
        val completed = this.completed.get()
        val processed = this.processed.get()
        val remaining = total - completed
        val estimation: String
        if (completed == 0L) {
            estimation = "no tiles processed, cannot estimate"
        } else {
            val perSecond = completed.toDouble() / (elapsed.toMillis().coerceAtLeast(1) / 1000.0)
            val estimated = Duration.ofSeconds((remaining / perSecond).toLong())
            estimation = estimated.toLogString()
        }
        log.info { "$completed completed ($processed with output), $remaining remaining (%${((remaining.toDouble() / total) * 100)})" }
        log.info { "elapsed time: ${elapsed.toLogString()}" }
        log.info { "estimated time remaining: $estimation" }
    }
}

fun Duration.toLogString(): String {
    val days = this.toDays()
    val hours = (this - Duration.ofDays(days)).toHours()
    val minutes = (this - Duration.ofDays(days) - Duration.ofHours(hours)).toMinutes()
    return "$days days $hours hours $minutes minutes"
}
