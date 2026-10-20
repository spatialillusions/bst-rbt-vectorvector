package com.greensopinion.vectorvector.pmtiles

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream

class PmtilesDirectoryTest {
    private fun ByteArrayOutputStream.varint(value: Long) {
        var v = value
        while (v >= 0x80) {
            write(((v and 0x7F) or 0x80).toInt())
            v = v ushr 7
        }
        write(v.toInt())
    }

    @Test
    fun `deserializes entries and applies the offset-0 rule`() {
        val out = ByteArrayOutputStream()
        out.varint(4)
        listOf(1L, 1L, 5L, 300L).forEach { out.varint(it) } // ids 1, 2, 7, 307
        listOf(1L, 3L, 0L, 1L).forEach { out.varint(it) } // run lengths
        listOf(10L, 20L, 30L, 40L).forEach { out.varint(it) } // lengths
        listOf(1L, 0L, 0L, 1001L).forEach { out.varint(it) } // offsets: 0, 0+10, 10+20, 1000
        val entries = PmtilesDirectory.deserialize(out.toByteArray())
        assertThat(entries).containsExactly(
            Entry(1, 0, 10, 1),
            Entry(2, 10, 20, 3),
            Entry(7, 30, 30, 0),
            Entry(307, 1000, 40, 1)
        )
    }

    @Test
    fun `finds tiles, runs and leaves`() {
        val entries = listOf(Entry(1, 0, 10, 1), Entry(2, 10, 20, 3), Entry(7, 30, 30, 0), Entry(307, 1000, 40, 1))
        assertThat(PmtilesDirectory.find(entries, 0)).isNull()
        assertThat(PmtilesDirectory.find(entries, 1)).isEqualTo(entries[0])
        assertThat(PmtilesDirectory.find(entries, 4)).isEqualTo(entries[1])
        assertThat(PmtilesDirectory.find(entries, 5)).isNull()
        assertThat(PmtilesDirectory.find(entries, 100)).isEqualTo(entries[2]) // leaf
        assertThat(PmtilesDirectory.find(entries, 307)).isEqualTo(entries[3])
        assertThat(PmtilesDirectory.find(entries, 308)).isNull()
    }
}
