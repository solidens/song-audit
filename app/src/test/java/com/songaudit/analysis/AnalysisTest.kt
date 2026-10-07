package com.songaudit.analysis

import com.songaudit.Fixtures
import com.songaudit.audio.Deep
import com.songaudit.audio.Probe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalysisTest {

    private fun issues(name: String): Set<Issue> {
        val file = Fixtures.file(name)
        val p = Probe.probe(file)!!
        val d = Deep.flac(file)
        val a = d.analysis!!
        return Verdict.issues(p.lossless, p.sampleRate, p.bits, p.bitrate, d.damaged, a.cutoffHz, a.ultrasonicDropDb, a.effectiveBits)
    }

    @Test
    fun `genuine files pass`() {
        assertEquals(emptySet<Issue>(), issues("cd.flac"))
        assertEquals(emptySet<Issue>(), issues("hires-96.flac"))
        assertEquals(emptySet<Issue>(), issues("cd-48k.flac"))
        assertEquals(emptySet<Issue>(), issues("mono.flac"))
    }

    @Test
    fun `an MP3 inside a FLAC is found, and how good the MP3 was`() {
        assertEquals(setOf(Issue.LOSSY_SOURCE), issues("transcoded-128.flac"))
        val cut = Deep.flac(Fixtures.file("transcoded-128.flac")).analysis!!.cutoffHz
        assertTrue("cutoff $cut", cut in 16_000..17_500)
        assertEquals("128 kbps", Verdict.likeBitrate(cut))
        assertEquals(setOf(Issue.MAYBE_LOSSY), issues("transcoded-320.flac"))
    }

    @Test
    fun `hi-res made from a CD is called out`() {
        assertEquals(setOf(Issue.UPSAMPLED), issues("upsampled-96.flac"))
    }

    @Test
    fun `16 bits in a 24-bit box is called out`() {
        assertEquals(setOf(Issue.PADDED), issues("padded-24.flac"))
        assertEquals(24, Deep.flac(Fixtures.file("hires-96.flac")).analysis!!.effectiveBits)
    }

    @Test
    fun `damage outranks everything`() {
        assertTrue(Issue.DAMAGED in issues("damaged.flac"))
    }

    @Test
    fun `dynamic range is in the range a meter would show`() {
        val dr = Deep.flac(Fixtures.file("cd.flac")).analysis!!.dr
        assertTrue("dr $dr", dr in 6f..14f)
    }

    @Test
    fun `a pure tone has the textbook DR and a known peak`() {
        // A sine's RMS times root two is its peak, so TT DR reads 0.
        val rate = 44100
        val a = Analyzer(rate, 1, 16)
        val n = rate * 10
        val s = IntArray(n) { (16384 * Math.sin(2 * Math.PI * 1000 * it / rate)).toInt() }
        a.feed(arrayOf(s), n)
        val r = a.finish()
        assertEquals(0f, r.dr, 0.1f)
        assertEquals(-6.02f, r.peakDb, 0.05f)
    }

    @Test
    fun `the same recording matches across formats, rates and offsets`() {
        val cd = Deep.flac(Fixtures.file("cd.flac")).analysis!!.fingerprint
        for (name in listOf("transcoded-128.flac", "transcoded-320.flac", "upsampled-96.flac", "cd-48k.flac", "padded-24.flac")) {
            val m = Fingerprint.best(cd, Deep.flac(Fixtures.file(name)).analysis!!.fingerprint)
            assertTrue("$name ber ${m.ber}", m.ber < 0.12f)
            assertEquals(name, 0, m.offset)
        }
        val delayed = Fingerprint.best(cd, Deep.flac(Fixtures.file("delayed.flac")).analysis!!.fingerprint, radius = 20)
        assertTrue("delayed ber ${delayed.ber}", delayed.ber < 0.12f)
        assertEquals(11, delayed.offset) // half a second is 10.8 hops
    }

    @Test
    fun `different music does not match`() {
        val cd = Deep.flac(Fixtures.file("cd.flac")).analysis!!.fingerprint
        val other = Deep.flac(Fixtures.file("other.flac")).analysis!!.fingerprint
        val m = Fingerprint.best(cd, other, radius = 40)
        assertTrue("ber ${m.ber}", m.ber > 0.4f)
    }

    @Test
    fun `the index finds every true pair and nothing else`() {
        val names = listOf("cd.flac", "other.flac", "transcoded-128.flac", "hires-96.flac", "delayed.flac", "cd-48k.flac")
        val prints = names.map { Deep.flac(Fixtures.file(it)).analysis!!.fingerprint }
        val pairs = FingerprintIndex(prints).matches().map { names[it.a] to names[it.b] }.toSet()
        val same = listOf("cd.flac", "transcoded-128.flac", "delayed.flac", "cd-48k.flac")
        val expected = same.flatMap { a -> same.filter { names.indexOf(it) > names.indexOf(a) }.map { a to it } }.toSet()
        assertEquals(expected, pairs)
    }
}
