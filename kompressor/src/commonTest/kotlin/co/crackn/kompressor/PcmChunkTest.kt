package co.crackn.kompressor

import co.crackn.kompressor.audio.PcmChunk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PcmChunkTest {

    @Test
    fun propertiesReflectConstructorArgs() {
        val data = byteArrayOf(1, 2, 3, 4)
        val chunk = PcmChunk(data, size = 4, presentationTimeUs = 1000L, isEndOfStream = false)
        assertEquals(4, chunk.size)
        assertEquals(1000L, chunk.presentationTimeUs)
        assertFalse(chunk.isEndOfStream)
        assertEquals(4, chunk.data.size)
    }

    @Test
    fun endOfStreamChunkMayHaveZeroSize() {
        val chunk = PcmChunk(ByteArray(0), size = 0, presentationTimeUs = 5000L, isEndOfStream = true)
        assertTrue(chunk.isEndOfStream)
        assertEquals(0, chunk.size)
    }
}
