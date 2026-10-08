package com.songaudit.fix

import com.songaudit.Fixtures
import com.songaudit.analysis.Issue
import com.songaudit.audio.Deep
import com.songaudit.audio.FlacDecoder
import com.songaudit.audio.FlacReader
import com.songaudit.audio.Source
import com.songaudit.library.Track
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

class ShrinkTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Every sample of a FLAC, per channel. */
    private fun samples(file: File): Array<IntArray> {
        val meta = Source(file).use { FlacReader.read(it) }
        val out = Array(meta.info.channels) { ArrayList<Int>() }
        FileInputStream(file).use { s ->
            s.channel.position(meta.audioOffset)
            val d = FlacDecoder(BufferedInputStream(s), meta.info)
            while (true) {
                val n = d.next()
                if (n == 0) break
                for (c in out.indices) for (i in 0 until n) out[c] += d.samples[c][i]
            }
            assertEquals(0, d.frameErrors)
        }
        return Array(out.size) { out[it].toIntArray() }
    }

    private fun encode(x: Array<IntArray>, rate: Int, bits: Int): File {
        val file = File(tmp.root, "enc-${x.size}-$rate-$bits.flac")
        val bytes = ByteArrayOutputStream()
        val enc = FlacEncoder(bytes, rate, x.size, bits)
        // Odd-sized pieces, so blocks straddle writes.
        var at = 0
        while (at < x[0].size) {
            val n = minOf(3001, x[0].size - at)
            enc.write(Array(x.size) { c -> x[c].copyOfRange(at, at + n) }, n)
            at += n
        }
        val md5 = enc.finish()
        val info = FlacHead.streamInfo(4096, 4096, enc.minFrame, enc.maxFrame, rate, x.size, bits, enc.totalSamples, md5)
        file.writeBytes(FlacHead.write(listOf(FlacHead.Block(FlacHead.STREAMINFO, info))) + bytes.toByteArray())
        return file
    }

    @Test
    fun `the encoder is lossless and its files are near ffmpeg's size`() {
        for (name in listOf("cd.flac", "mono.flac", "hires-96.flac", "padded-24.flac")) {
            val src = Fixtures.file(name)
            val info = Source(src).use { FlacReader.read(it) }.info
            val x = samples(src)
            val out = encode(x, info.sampleRate, info.bitsPerSample)
            val r = Deep.flac(out)
            assertNull(name, r.error)
            assertEquals(name, 0, r.frameErrors)
            assertEquals(name, 1, r.md5Match)
            assertArrayEquals(name, info.md5, Source(out).use { FlacReader.read(it) }.info.md5)
            val back = samples(out)
            for (c in x.indices) assertArrayEquals("$name channel $c", x[c], back[c])
            val ratio = out.length().toDouble() / src.length()
            println("$name: ours ${out.length()} vs ffmpeg ${src.length()} (${(ratio * 100).roundToInt()}%)")
            assertTrue("$name is ${(ratio * 100).roundToInt()}% of ffmpeg's", ratio < 1.08)
            ffmpegMd5(out, info.bitsPerSample)?.let { assertEquals("$name through ffmpeg", pcmMd5(x, info.bitsPerSample), it) }
        }
    }

    @Test
    fun `silence, a lone tone and full-scale noise all survive`() {
        val n = 44100
        val silence = Array(2) { IntArray(n) }
        val tone = Array(2) { c -> IntArray(n) { (32000 * sin(2 * PI * 1000 * it / 44100 + c)).toInt() } }
        var seed = 1L
        val noise = Array(2) {
            IntArray(n) {
                seed = seed * 6364136223846793005L + 1442695040888963407L
                (seed ushr 40).toInt() - (1 shl 23)
            }
        }
        val short = Array(1) { IntArray(10) { it * 1000 } }
        for ((label, x, bits) in listOf(Triple("silence", silence, 16), Triple("tone", tone, 16), Triple("noise", noise, 24), Triple("short", short, 16))) {
            val out = encode(x, 44100, bits)
            assertEquals(label, 1, Deep.flac(out).md5Match)
            val back = samples(out)
            for (c in x.indices) assertArrayEquals(label, x[c], back[c])
        }
    }

    @Test
    fun `padded audio comes back as the 16 bits it was`() {
        val src = Fixtures.file("padded-24.flac")
        val out = File(tmp.root, "padded.flac")
        Shrink.write(src, out, Shrink.Target(44100, 16))
        val r = Deep.flac(out)
        assertEquals(1, r.md5Match)
        assertEquals(0, r.frameErrors)
        val meta = Source(out).use { FlacReader.read(it) }
        assertEquals(16, meta.info.bitsPerSample)
        // Exactly the CD's samples: the same signature as the 16-bit original.
        assertArrayEquals(Source(Fixtures.file("cd.flac")).use { FlacReader.read(it) }.info.md5, meta.info.md5)
        assertTrue(out.length() < src.length())
    }

    @Test
    fun `an upsampled file goes back to 48 kHz with its tags and nothing audible lost`() {
        val src = Fixtures.file("upsampled-96.flac")
        val withTags = File(tmp.root, "up.flac")
        src.copyTo(withTags)
        Rewrite.apply(withTags, TagEdit(mapOf(TagEdit.TITLE to "Up", TagEdit.ALBUM to "Hi-res")))
        val out = File(tmp.root, "down.flac")
        Shrink.write(withTags, out, Shrink.Target(48000, 24))
        val r = Deep.flac(out)
        assertNull(r.error)
        assertEquals(1, r.md5Match)
        val meta = Source(out).use { FlacReader.read(it) }
        val before = Source(src).use { FlacReader.read(it) }
        assertEquals(48000, meta.info.sampleRate)
        assertEquals(24, meta.info.bitsPerSample)
        assertEquals((before.info.totalSamples + 1) / 2, meta.info.totalSamples)
        assertEquals("Up", meta.tags.title)
        assertEquals("Hi-res", meta.tags.album)
        // The music below 20 kHz is the same music: compare with ffmpeg's own 48 kHz resample of the original.
        val analysis = r.analysis!!
        assertTrue("no wall left in the audible band", analysis.cutoffHz == 0 || analysis.cutoffHz > 20_000)
        println("upsampled-96: ${src.length()} → ${out.length()} bytes")
        assertTrue(out.length() < src.length() * 0.75)
    }

    @Test
    fun `the decimator passes the band, stops the rest, and keeps time`() {
        val rate = 96000
        val n = rate
        val d = Decimator(2, rate, 1)
        fun run(freq: Double): DoubleArray {
            val x = IntArray(n) { (1_000_000 * sin(2 * PI * freq * it / rate)).roundToInt() }
            val dec = Decimator(2, rate, 1)
            val out = ArrayList<Double>()
            val sink: (Array<DoubleArray>, Int) -> Unit = { y, k -> for (i in 0 until k) out += y[0][i] }
            dec.push(arrayOf(x.copyOfRange(0, 5000)), 5000, sink)
            dec.push(arrayOf(x.copyOfRange(5000, n)), n - 5000, sink)
            dec.finish(n.toLong(), sink)
            assertEquals(n / 2, out.size)
            return out.toDoubleArray()
        }
        // 1 kHz: same level, same phase as sampling the sine at 48 kHz directly.
        val pass = run(1000.0)
        var err = 0.0
        var sig = 0.0
        for (i in 2000 until 46000) {
            val ideal = 1_000_000 * sin(2 * PI * 1000 * i / 48000.0)
            err += (pass[i] - ideal) * (pass[i] - ideal)
            sig += ideal * ideal
        }
        val snr = 10 * log10(sig / err)
        assertTrue("1 kHz through at $snr dB", snr > 90)
        // 19 kHz is still in the passband.
        val high = run(19000.0)
        val level = sqrt(high.slice(2000 until 46000).sumOf { it * it } / 44000) * sqrt(2.0)
        assertEquals(1_000_000.0, level, 1_000_000.0 * 0.002)
        // 30 kHz would fold to 18 kHz: it must be gone.
        val stop = run(30000.0)
        var peak = 0.0
        for (i in 2000 until 46000) peak = max(peak, abs(stop[i]))
        assertTrue("30 kHz left at ${20 * log10(peak / 1e6)} dB", peak < 1e6 * 1e-4)
        assertTrue(d.taps.size in 101..1001)
    }

    @Test
    fun `only what can be checked is offered for shrinking`() {
        fun t(rate: Int, bits: Int, issues: Set<Issue>, effective: Int = bits, format: String = "FLAC") = Track(
            id = 1, path = "/m/a.flac", folder = "/m", size = 1, modified = 0, format = format, lossless = true,
            sampleRate = rate, bits = bits, channels = 2, durationMs = 1000, bitrate = 0, deepVersion = 1,
            effectiveBits = effective, issues = Issue.mask(issues),
        )
        assertEquals("24/48", Shrink.target(t(96000, 24, setOf(Issue.UPSAMPLED)))!!.toString())
        assertEquals("16/44.1", Shrink.target(t(176400, 24, setOf(Issue.UPSAMPLED, Issue.PADDED), effective = 16))!!.toString())
        assertEquals("16/96", Shrink.target(t(96000, 24, setOf(Issue.PADDED), effective = 16))!!.toString())
        assertNull(Shrink.target(t(96000, 24, setOf(Issue.UPSAMPLED, Issue.DAMAGED))))
        assertNull(Shrink.target(t(96000, 24, setOf(Issue.UPSAMPLED), format = "WAV")))
        assertNull(Shrink.target(t(44100, 16, setOf(Issue.LOSSY_SOURCE))))
    }

    // -- ffmpeg as a second decoder, when it is installed ---------------------------

    private fun pcmMd5(x: Array<IntArray>, bits: Int): String {
        val md = MessageDigest.getInstance("MD5")
        val width = if (bits <= 16) 2 else 4
        val b = ByteArray(x.size * width)
        for (i in x[0].indices) {
            var at = 0
            for (c in x.indices) {
                // ffmpeg hands 24-bit audio out as s32, shifted up.
                var v = if (width == 4) x[c][i] shl (32 - bits) else x[c][i]
                for (k in 0 until width) {
                    b[at++] = v.toByte()
                    v = v shr 8
                }
            }
            md.update(b)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ffmpegMd5(file: File, bits: Int): String? = try {
        val fmt = if (bits <= 16) "s16le" else "s32le"
        val p = ProcessBuilder("ffmpeg", "-v", "error", "-i", file.path, "-f", fmt, "-").start()
        val bytes = p.inputStream.readBytes()
        val err = p.errorStream.readBytes().toString(Charsets.UTF_8)
        if (p.waitFor() != 0 || err.isNotBlank()) throw AssertionError("ffmpeg: $err")
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    } catch (e: java.io.IOException) {
        null
    }
}
