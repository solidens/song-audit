package com.songaudit.audio

import com.songaudit.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeTest {
    @Test
    fun `mp3 duration and bitrate come from the Info frame`() {
        val p = Probe.probe(Fixtures.file("cd-128.mp3"))!!
        assertEquals("MP3", p.format)
        assertFalse(p.lossless)
        assertEquals(44100, p.sampleRate)
        assertTrue("duration ${p.durationMs}", p.durationMs in 7_950..8_100)
        assertTrue("bitrate ${p.bitrate}", p.bitrate in 120..136)
    }

    @Test
    fun `flac probe carries the MD5 and a compressed bitrate`() {
        val p = Probe.probe(Fixtures.file("cd.flac"))!!
        assertTrue(p.lossless)
        assertEquals(8000, p.durationMs)
        assertEquals(16, p.audioMd5!!.size)
        assertTrue("bitrate ${p.bitrate}", p.bitrate in 500..1411)
    }

    @Test
    fun `track numbers in every spelling`() {
        val t = Tags()
        t.set("TRACKNUMBER", "03/12")
        t.set("DISCNUMBER", "2")
        t.set("DISCTOTAL", "2")
        t.set("album artist", "Various Artists")
        assertEquals(3, t.track)
        assertEquals(12, t.trackTotal)
        assertEquals(2, t.disc)
        assertEquals(2, t.discTotal)
        assertEquals("Various Artists", t.albumArtist)
    }
}
