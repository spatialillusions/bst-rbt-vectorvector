package com.greensopinion.vectorvector

import com.greensopinion.vectorvector.util.budgetedCache
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CacheBudgetTest {
    @Test fun `byte budget evicts large entries and count bound covers tiny entries`() {
        val cache = budgetedCache<Int, ByteArray>(4, 100) { it.size.toLong() }.build<Int, ByteArray>()
        repeat(10) { cache.put(it, ByteArray(1)) }
        cache.cleanUp()
        assertThat(cache.size()).isLessThanOrEqualTo(4)
        cache.invalidateAll()
        repeat(10) { cache.put(it, ByteArray(60)) }
        cache.cleanUp()
        assertThat(cache.size()).isEqualTo(1)
    }
    @Test fun `zero byte budget preserves count-only mode`() {
        val cache = budgetedCache<Int, ByteArray>(2, 0) { it.size.toLong() }.build<Int, ByteArray>()
        repeat(10) { cache.put(it, ByteArray(200)) }
        cache.cleanUp()
        assertThat(cache.size()).isEqualTo(2)
    }
}
