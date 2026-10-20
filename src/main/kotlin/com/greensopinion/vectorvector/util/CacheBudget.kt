package com.greensopinion.vectorvector.util

import com.google.common.cache.CacheBuilder
import kotlin.math.max

/** Conservative per-cache ownership budget; shared arrays may be charged twice. */
internal fun <K : Any, V : Any> budgetedCache(
    entries: Long, bytes: Long, weight: (V) -> Long
): CacheBuilder<K, V> {
    require(entries > 0 && bytes >= 0)
    @Suppress("UNCHECKED_CAST")
    // A single weight segment lets one large geometry use the whole budget;
    // Guava's default segmented quotas can otherwise evict it immediately.
    val builder = CacheBuilder.newBuilder().concurrencyLevel(1).recordStats() as CacheBuilder<K, V>
    if (bytes == 0L) return builder.maximumSize(entries)
    val minimum = (bytes + entries - 1) / entries
    require(minimum <= Int.MAX_VALUE) { "Cache budget per entry is too large" }
    return builder.maximumWeight(bytes).weigher<K, V> { _, value ->
        max(minimum, weight(value)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
