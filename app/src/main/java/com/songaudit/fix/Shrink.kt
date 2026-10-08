package com.songaudit.fix

import com.songaudit.analysis.Issue
import com.songaudit.audio.FlacDecoder
import com.songaudit.audio.FlacReader
import com.songaudit.audio.Source
import com.songaudit.library.Track
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.concurrent.CancellationException
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Writing a FLAC again at the resolution it really has: a CD upsampled to
 * 96 kHz back to 48, 16-bit audio padded to 24 back to 16. The tags and
 * pictures come across unchanged; the seek table and cue sheet, which count
 * samples at the old rate, are left behind.
 */
object Shrink {

    class Target(val rate: Int, val bits: Int) {
        override fun toString() = "${bits}/${com.songaudit.library.Track.khz(rate)}"
    }

    /** What [t] can become, or null when there is nothing to shrink or no way to check the result. */
    fun target(t: Track): Target? {
        if (t.format != "FLAC" || !t.analysed || t.has(Issue.DAMAGED) || t.channels !in 1..8) return null
        val upsampled = t.has(Issue.UPSAMPLED)
        val padded = t.has(Issue.PADDED)
        if (!upsampled && !padded) return null
        val base = when {
            t.sampleRate % 44100 == 0 -> 44100
            t.sampleRate % 48000 == 0 -> 48000
            else -> t.sampleRate
        }
        val rate = if (upsampled && t.sampleRate > base) base else t.sampleRate
        val bits = if (padded && t.effectiveBits in 1..16) 16 else t.bits
        if (rate == t.sampleRate && bits == t.bits) return null
        return Target(rate, bits)
    }

    /**
     * Writes [source] as a new FLAC at [target] into [dest]. The caller checks
     * the result by decoding it before it replaces anything.
     */
    fun write(source: File, dest: File, target: Target, cancelled: () -> Boolean = { false }) {
        val meta = Source(source).use { FlacReader.read(it) }
        val info = meta.info
        val factor = info.sampleRate / target.rate
        if (factor < 1 || info.sampleRate % target.rate != 0) throw WriteException("cannot go from ${info.sampleRate} Hz to ${target.rate} Hz")
        val drop = info.bitsPerSample - target.bits

        // Everything but what the new audio makes wrong; STREAMINFO is filled in at the end.
        val kept = FlacHead.blocksOf(source).filter {
            it.type !in setOf(FlacHead.STREAMINFO, FlacHead.SEEKTABLE, FlacHead.CUESHEET, FlacHead.PADDING)
        }
        val blocks = listOf(FlacHead.Block(FlacHead.STREAMINFO, ByteArray(34))) + kept +
            FlacHead.Block(FlacHead.PADDING, ByteArray(FlacHead.ROOM))
        val head = FlacHead.write(blocks)

        val md5: ByteArray
        val encoder: FlacEncoder
        FileOutputStream(dest).use { file ->
            val out = BufferedOutputStream(file, 1 shl 16)
            out.write(head)
            encoder = FlacEncoder(out, target.rate, info.channels, target.bits)
            FileInputStream(source).use { stream ->
                stream.channel.position(meta.audioOffset)
                val decoder = FlacDecoder(BufferedInputStream(stream, 1 shl 16), info)
                val decimator = if (factor > 1) Decimator(factor, info.sampleRate, info.channels) else null
                val quantize = Quantizer(info.bitsPerSample, target.bits, dither = decimator != null && drop > 0)
                val ints = Array(info.channels) { IntArray(8192) }
                val sink: (Array<DoubleArray>, Int) -> Unit = { y, n ->
                    for (c in 0 until info.channels) {
                        if (ints[c].size < n) ints[c] = IntArray(n)
                        for (i in 0 until n) ints[c][i] = quantize.next(y[c][i])
                    }
                    encoder.write(ints, n)
                }
                var frames = 0
                while (true) {
                    val n = decoder.next()
                    if (n == 0) break
                    val x = decoder.samples
                    if (decimator != null) {
                        decimator.push(x, n, sink)
                    } else {
                        // Padded only: the low bits are zero, so this is exact.
                        for (c in 0 until info.channels) {
                            if (ints[c].size < n) ints[c] = IntArray(n)
                            for (i in 0 until n) {
                                val v = x[c][i]
                                if (drop > 0 && v and ((1 shl drop) - 1) != 0) throw WriteException("the low bits are not all zero")
                                ints[c][i] = v shr drop
                            }
                        }
                        encoder.write(ints, n)
                    }
                    if (++frames and 31 == 0 && cancelled()) throw CancellationException()
                }
                if (decoder.frameErrors > 0) throw WriteException("the original has damaged frames")
                decimator?.finish(decoder.samplesDecoded, sink)
            }
            md5 = encoder.finish()
            out.flush()
            file.fd.sync()
        }
        val expected = if (factor > 1) ceil(info.totalSamples.toDouble() / factor).toLong() else info.totalSamples
        if (info.totalSamples > 0 && encoder.totalSamples != expected) {
            throw WriteException("wrote ${encoder.totalSamples} samples, expected $expected")
        }
        val streamInfo = FlacHead.streamInfo(
            4096, 4096, encoder.minFrame.coerceAtMost(encoder.maxFrame), encoder.maxFrame,
            target.rate, info.channels, target.bits, encoder.totalSamples, md5,
        )
        RandomAccessFile(dest, "rw").use {
            it.seek(8) // "fLaC" and the block header
            it.write(streamInfo)
            it.fd.sync()
        }
    }
}

/**
 * Integer-factor decimation: a linear-phase Kaiser-windowed sinc, passing
 * everything to 90% of the new Nyquist and down 100 dB at it, computed only
 * at the output samples. The output is aligned with the input, and there are
 * ceil(input / factor) samples of it.
 */
class Decimator(private val factor: Int, rate: Int, private val channels: Int) {
    val taps: DoubleArray = design(factor, rate)
    private val n = taps.size
    private val delay = (n - 1) / 2
    private var buf = Array(channels) { DoubleArray(1 shl 16) }
    /** Input index of buf[0]; starts before zero so the first outputs see silence before the track. */
    private var base = -delay.toLong()
    private var length = delay
    private var received = 0L
    private var emitted = 0L
    private val out = Array(channels) { DoubleArray(4096) }

    fun push(x: Array<IntArray>, count: Int, sink: (Array<DoubleArray>, Int) -> Unit) {
        reserve(count)
        for (c in 0 until channels) {
            val b = buf[c]
            val src = x[c]
            for (i in 0 until count) b[length + i] = src[i].toDouble()
        }
        length += count
        received += count
        drain(Long.MAX_VALUE, sink)
    }

    /** Runs the filter off the end of the track, into silence, for the last outputs. */
    fun finish(total: Long, sink: (Array<DoubleArray>, Int) -> Unit) {
        val pad = delay + factor
        reserve(pad)
        for (c in 0 until channels) java.util.Arrays.fill(buf[c], length, length + pad, 0.0)
        length += pad
        drain((total + factor - 1) / factor, sink)
    }

    private fun drain(limit: Long, sink: (Array<DoubleArray>, Int) -> Unit) {
        var produced = 0
        // Output m is centred on input m * factor and needs inputs up to m * factor + delay.
        while (emitted < limit && emitted * factor + delay < base + length) {
            val start = (emitted * factor - delay - base).toInt()
            for (c in 0 until channels) {
                val b = buf[c]
                var acc = 0.0
                for (j in 0 until n) acc += taps[j] * b[start + j]
                out[c][produced] = acc
            }
            produced++
            emitted++
            if (produced == out[0].size) {
                sink(out, produced)
                produced = 0
            }
        }
        if (produced > 0) sink(out, produced)
        val keep = (emitted * factor - delay - base).toInt()
        if (keep > 0) {
            for (c in 0 until channels) System.arraycopy(buf[c], keep, buf[c], 0, length - keep)
            base += keep
            length -= keep
        }
    }

    private fun reserve(count: Int) {
        if (length + count > buf[0].size) {
            val size = maxOf(buf[0].size * 2, length + count)
            buf = Array(channels) { buf[it].copyOf(size) }
        }
    }

    companion object {
        fun design(factor: Int, rate: Int): DoubleArray {
            if (factor == 1) return doubleArrayOf(1.0)
            val nyquist = rate / factor / 2.0
            val pass = nyquist * 0.907 // 20 kHz at 44.1
            val stop = nyquist
            val attenuation = 100.0
            val beta = 0.1102 * (attenuation - 8.7)
            val width = (stop - pass) / rate
            var n = ceil((attenuation - 7.95) / (14.36 * width)).toInt() + 1
            if (n % 2 == 0) n++
            val fc = (pass + stop) / 2 / rate
            val mid = (n - 1) / 2.0
            val i0beta = bessel0(beta)
            val h = DoubleArray(n) { i ->
                val x = i - mid
                val sinc = if (x == 0.0) 2 * fc else sin(2 * PI * fc * x) / (PI * x)
                val r = x / mid
                sinc * bessel0(beta * sqrt(1 - r * r)) / i0beta
            }
            val sum = h.sum()
            for (i in h.indices) h[i] /= sum
            return h
        }

        private fun bessel0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val half = x / 2
            var k = 1
            while (term > 1e-12 * sum) {
                term *= (half / k) * (half / k)
                sum += term
                k++
            }
            return sum
        }
    }
}

/**
 * Filter output back to integers. Kept at the same depth it is rounded; taken
 * to fewer bits it gets triangular dither first, so the lost bits become a
 * little steady noise rather than distortion.
 */
class Quantizer(inBits: Int, outBits: Int, private val dither: Boolean) {
    private val scale = Math.scalb(1.0, outBits - inBits)
    private val hi = (1L shl (outBits - 1)) - 1
    private val lo = -(1L shl (outBits - 1))
    private var seed = 0x2545F4914F6CDD1DL

    fun next(v: Double): Int {
        var y = v * scale
        if (dither) y += random() - random()
        return y.roundToLong().coerceIn(lo, hi).toInt()
    }

    /** Uniform in [-0.5, 0.5), from xorshift: fast, and the same every run. */
    private fun random(): Double {
        seed = seed xor (seed shl 13)
        seed = seed xor (seed ushr 7)
        seed = seed xor (seed shl 17)
        return (seed ushr 11) * (1.0 / (1L shl 53)) - 0.5
    }
}
