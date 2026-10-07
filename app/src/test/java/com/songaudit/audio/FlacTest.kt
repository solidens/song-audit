package com.songaudit.audio

import com.songaudit.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FlacTest {

    @Test
    fun `clean files decode bit-exact at every compression level`() {
        for (name in listOf(
            "cd.flac", "cd-fast.flac", "cd-max.flac", "mono.flac", "padded-24.flac",
            "upsampled-96.flac", "hires-96.flac", "cd-48k.flac", "transcoded-128.flac",
        )) {
            val r = Deep.flac(Fixtures.file(name))
            assertEquals("$name error", null, r.error)
            assertEquals("$name frame errors", 0, r.frameErrors)
            assertEquals("$name md5", 1, r.md5Match)
            assertEquals("$name samples", r.expectedSamples, r.decodedSamples)
            assertFalse("$name damaged", r.damaged)
        }
    }

    @Test
    fun `metadata comes from the header`() {
        val meta = Source(Fixtures.file("cd.flac")).use { FlacReader.read(it) }
        assertEquals(44100, meta.info.sampleRate)
        assertEquals(2, meta.info.channels)
        assertEquals(16, meta.info.bitsPerSample)
        assertEquals(8 * 44100L, meta.info.totalSamples)
        assertEquals("First", meta.tags.title)
        assertEquals("Synth", meta.tags.artist)
        assertEquals("Synth", meta.tags.albumArtist)
        assertEquals("Fixtures", meta.tags.album)
        assertEquals(1, meta.tags.track)
        assertEquals(3, meta.tags.trackTotal)

        val hires = Source(Fixtures.file("hires-96.flac")).use { FlacReader.read(it) }
        assertEquals(96000, hires.info.sampleRate)
        assertEquals(24, hires.info.bitsPerSample)
    }

    @Test
    fun `flipped bytes are caught and the rest of the track still decodes`() {
        val r = Deep.flac(Fixtures.file("damaged.flac"))
        assertTrue(r.damaged)
        assertTrue("frame errors", r.frameErrors >= 1)
        assertEquals(0, r.md5Match)
        // One bad patch costs a frame or two, not the track.
        assertTrue(r.decodedSamples > r.expectedSamples * 9 / 10)
    }

    @Test
    fun `a cut-off copy reads as truncated`() {
        val r = Deep.flac(Fixtures.file("truncated.flac"))
        assertTrue(r.truncated)
        assertTrue(r.damaged)
        assertTrue(r.decodedSamples in r.expectedSamples / 2 until r.expectedSamples)
    }

    @Test
    fun `wav reads and analyses`() {
        val info = PcmReader.read(Fixtures.file("short.wav"))
        assertEquals(44100, info.sampleRate)
        assertEquals(16, info.bits)
        assertEquals(2 * 44100L, info.totalSamples)
        val r = Deep.pcm(Fixtures.file("short.wav"))
        assertEquals(2 * 44100L, r.decodedSamples)
        assertEquals(16, r.analysis!!.effectiveBits)
    }
}
