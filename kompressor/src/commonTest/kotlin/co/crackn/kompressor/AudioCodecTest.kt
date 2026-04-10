package co.crackn.kompressor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains

class AudioCodecTest {

    @Test
    fun aacEntryExists() {
        val codec = AudioCodec.valueOf("AAC")
        assertEquals(AudioCodec.AAC, codec)
    }

    @Test
    fun entriesContainsAllCodecs() {
        val entries = AudioCodec.entries
        assertEquals(1, entries.size)
        assertContains(entries, AudioCodec.AAC)
    }
}
