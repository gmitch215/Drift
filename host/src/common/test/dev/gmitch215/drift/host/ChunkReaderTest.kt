package dev.gmitch215.drift.host

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class ChunkReaderTest {
    private fun source(data: ByteArray): (ByteArray) -> Int {
        var at = 0
        return { buffer ->
            val n = minOf(buffer.size, data.size - at)
            data.copyInto(buffer, 0, at, at + n)
            at += n
            n
        }
    }

    @Test
    fun joinsEveryChunkIncludingAShortLastOne() {
        val data = ByteArray(25) { it.toByte() }
        assertContentEquals(data, readAllBytes(10, source(data)))
        assertContentEquals(data, readAllBytes(25, source(data)))
        assertContentEquals(data, readAllBytes(1000, source(data)))
    }

    @Test
    fun emptyInputGivesAnEmptyArray() {
        assertEquals(0, readAllBytes(8, source(ByteArray(0))).size)
    }

    @Test
    fun stopsAtANegativeCountAndKeepsWhatCameBefore() {
        var calls = 0
        val bytes = readAllBytes(4) { buffer ->
            buffer.fill(7)
            if (calls++ == 0) 3 else -1
        }
        assertContentEquals(byteArrayOf(7, 7, 7), bytes)
    }
}
