package com.songaudit.analysis

import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Everything one pass over a track's samples finds out. */
class Analysis(
    val samples: Long,
    /** MD5 of the samples, packed the way FLAC's STREAMINFO signature packs them. */
    val md5: ByteArray,
    /** Bit depth actually in use: 24 minus the low bits that are zero in every sample. 0 for digital silence. */
    val effectiveBits: Int,
    /** TT DR, the number foobar's meter shows. NaN when the track is too short or silent. */
    val dr: Float,
    val peakDb: Float,
    /** Average spectrum, [SPECTRUM_POINTS] points from 0 Hz to Nyquist, each the level in dB below the loudest mid band. */
    val spectrum: ByteArray,
    /** Where a brick wall cuts the spectrum off, 0 when there is none. */
    val cutoffHz: Int,
    val cliffDb: Float,
    /** For files above 48 kHz: the fall across the 22.05 or 24 kHz line, whichever is larger. 0 otherwise. */
    val ultrasonicDropDb: Float,
    val fingerprint: IntArray,
) {
    companion object {
        const val SPECTRUM_POINTS = 512
    }
}

/**
 * Takes a track's samples once, in blocks, and keeps only what it needs from
 * them: a running MD5, the OR of every sample, three-second DR blocks, an
 * averaged spectrum from a window every quarter second, and the first
 * [fingerprintSeconds] of a decimated mono mix for the fingerprint.
 *
 * Memory does not grow with the length of the track beyond one float per DR
 * block, so a two-hour concert costs what a single does.
 */
class Analyzer(
    private val sampleRate: Int,
    private val channels: Int,
    private val bits: Int,
    private val fingerprintSeconds: Int = 30,
) {
    private val md5 = MessageDigest.getInstance("MD5")
    private val bytesPerSample = (bits + 7) / 8
    private var pack = ByteArray(0)
    private var orAll = 0
    private var samples = 0L
    private val scale = 1.0 / (1L shl (bits - 1))

    // -- DR ----------------------------------------------------------------
    private val drBlock = sampleRate * 3
    private var drFill = 0
    private val drSum = DoubleArray(channels)
    private val drMax = DoubleArray(channels)
    private val drRms = Array(channels) { FloatList() }
    private val drPeaks = Array(channels) { FloatList() }
    private var peak = 0.0

    // -- Spectrum ----------------------------------------------------------
    private val fft = Fft(SPECTRUM_N)
    private val window = Fft.hann(SPECTRUM_N)
    private val hop = max(SPECTRUM_N, sampleRate / 4)
    private val re = DoubleArray(SPECTRUM_N)
    private val im = DoubleArray(SPECTRUM_N)
    private val power = DoubleArray(SPECTRUM_N / 2 + 1)
    private var windows = 0
    private var specFill = 0
    private var specSkip = 0

    // -- Fingerprint -------------------------------------------------------
    private val decimate = max(1, sampleRate / 11025)
    private val fpRate = sampleRate.toDouble() / decimate
    private val taps = lowPass(sampleRate, decimate)
    private val ring = DoubleArray(taps.size * 2)
    private var ringAt = 0
    private var phase = 0
    private val fpLimit = sampleRate.toLong() * fingerprintSeconds
    private val mono = FloatArray((fpRate * fingerprintSeconds).toInt() + 2)
    private var monoCount = 0

    fun feed(ch: Array<IntArray>, n: Int) {
        if (n <= 0) return
        digest(ch, n)
        for (c in 0 until channels) {
            val s = ch[c]
            var o = orAll
            for (i in 0 until n) o = o or s[i]
            orAll = o
        }
        var i = 0
        while (i < n) {
            val take = min(n - i, drBlock - drFill)
            dr(ch, i, take)
            i += take
        }
        spectrum(ch, n)
        if (samples < fpLimit) fingerprintInput(ch, min(n.toLong(), fpLimit - samples).toInt())
        samples += n
    }

    private fun digest(ch: Array<IntArray>, n: Int) {
        val size = n * channels * bytesPerSample
        if (pack.size < size) pack = ByteArray(size)
        var at = 0
        for (i in 0 until n) {
            for (c in 0 until channels) {
                val v = ch[c][i]
                pack[at] = v.toByte()
                if (bytesPerSample > 1) pack[at + 1] = (v shr 8).toByte()
                if (bytesPerSample > 2) pack[at + 2] = (v shr 16).toByte()
                if (bytesPerSample > 3) pack[at + 3] = (v shr 24).toByte()
                at += bytesPerSample
            }
        }
        md5.update(pack, 0, size)
    }

    private fun dr(ch: Array<IntArray>, from: Int, count: Int) {
        for (c in 0 until channels) {
            val s = ch[c]
            var sum = drSum[c]
            var mx = drMax[c]
            for (i in from until from + count) {
                val x = s[i] * scale
                sum += x * x
                val a = abs(x)
                if (a > mx) mx = a
            }
            drSum[c] = sum
            drMax[c] = mx
        }
        drFill += count
        if (drFill == drBlock) closeDrBlock()
    }

    private fun closeDrBlock() {
        if (drFill == 0) return
        for (c in 0 until channels) {
            drRms[c].add(sqrt(2 * drSum[c] / drFill).toFloat())
            drPeaks[c].add(drMax[c].toFloat())
            peak = max(peak, drMax[c])
            drSum[c] = 0.0
            drMax[c] = 0.0
        }
        drFill = 0
    }

    /** Collects SPECTRUM_N samples, then skips to the next hop. Left and right share one complex FFT. */
    private fun spectrum(ch: Array<IntArray>, n: Int) {
        val left = ch[0]
        val right = if (channels > 1) ch[1] else ch[0]
        var i = 0
        while (i < n) {
            if (specSkip > 0) {
                val skip = min(specSkip, n - i)
                specSkip -= skip
                i += skip
                continue
            }
            val take = min(SPECTRUM_N - specFill, n - i)
            for (k in 0 until take) {
                val w = window[specFill + k] * scale
                re[specFill + k] = left[i + k] * w
                im[specFill + k] = right[i + k] * w
            }
            specFill += take
            i += take
            if (specFill == SPECTRUM_N) {
                accumulateWindow()
                specFill = 0
                specSkip = hop - SPECTRUM_N
            }
        }
    }

    private fun accumulateWindow() {
        fft.transform(re, im)
        val n = SPECTRUM_N
        for (k in 0..n / 2) {
            val j = (n - k) and (n - 1)
            // Split the packed transform back into the two real channels.
            val lr = (re[k] + re[j]) * 0.5
            val li = (im[k] - im[j]) * 0.5
            val rr = (im[k] + im[j]) * 0.5
            val ri = (re[j] - re[k]) * 0.5
            power[k] += lr * lr + li * li + rr * rr + ri * ri
        }
        windows++
    }

    private fun fingerprintInput(ch: Array<IntArray>, n: Int) {
        val size = taps.size
        val mix = scale / channels
        for (i in 0 until n) {
            var m = 0.0
            for (c in 0 until channels) m += ch[c][i]
            m *= mix
            ring[ringAt] = m
            ring[ringAt + size] = m
            ringAt = if (ringAt + 1 == size) 0 else ringAt + 1
            if (++phase == decimate) {
                phase = 0
                // ringAt now points at the oldest sample of the last `size`.
                var acc = 0.0
                for (t in 0 until size) acc += taps[t] * ring[ringAt + t]
                if (monoCount < mono.size) mono[monoCount++] = acc.toFloat()
            }
        }
    }

    fun finish(): Analysis {
        if (drFill >= sampleRate) closeDrBlock() else drFill = 0

        val effective = if (orAll == 0) 0 else bits - Integer.numberOfTrailingZeros(orAll)

        val binHz = sampleRate.toDouble() / SPECTRUM_N
        val db = DoubleArray(power.size) { k ->
            val p = if (windows > 0) power[k] / windows else 0.0
            10 * log10(p + 1e-30)
        }
        val cliff = Cliff.find(db, binHz)
        val reference = Cliff.reference(db, binHz)
        val ultrasonic = if (sampleRate >= 88200) {
            max(Cliff.across(db, binHz, 22050.0), Cliff.across(db, binHz, 24000.0))
        } else {
            0.0
        }

        return Analysis(
            samples = samples,
            md5 = md5.digest(),
            effectiveBits = effective,
            dr = drValue(),
            peakDb = if (peak > 0) (20 * log10(peak)).toFloat() else Float.NEGATIVE_INFINITY,
            spectrum = compress(db, reference),
            cutoffHz = cliff?.hz?.roundToInt() ?: 0,
            cliffDb = cliff?.drop?.toFloat() ?: 0f,
            ultrasonicDropDb = ultrasonic.toFloat(),
            fingerprint = Fingerprint.compute(mono, monoCount, fpRate),
        )
    }

    /** TT DR: peak is the second-loudest block peak, RMS is the loudest fifth of blocks. */
    private fun drValue(): Float {
        var total = 0.0
        var counted = 0
        for (c in 0 until channels) {
            val rms = drRms[c].sortedDescending()
            val peaks = drPeaks[c].sortedDescending()
            if (rms.isEmpty()) continue
            val top = max(1, (rms.size * 0.2).roundToInt())
            var sq = 0.0
            for (i in 0 until top) sq += rms[i].toDouble() * rms[i]
            val loud = sqrt(sq / top)
            val p = if (peaks.size >= 2) peaks[1] else peaks[0]
            if (loud <= 0 || p <= 0) continue
            total += 20 * log10(p / loud)
            counted++
        }
        return if (counted == 0 || drRms[0].size < 2) Float.NaN else (total / counted).toFloat()
    }

    private fun compress(db: DoubleArray, reference: Double): ByteArray {
        val points = Analysis.SPECTRUM_POINTS
        val per = (db.size - 1).toDouble() / points
        return ByteArray(points) { j ->
            val from = (j * per).toInt()
            val to = max(from + 1, ((j + 1) * per).toInt())
            var p = 0.0
            for (k in from until to) p += Math.pow(10.0, db[k] / 10)
            val level = 10 * log10(p / (to - from) + 1e-30) - reference
            (-level).roundToInt().coerceIn(0, 255).toByte()
        }
    }

    private class FloatList {
        private var a = FloatArray(64)
        var size = 0
            private set

        fun add(v: Float) {
            if (size == a.size) a = a.copyOf(size * 2)
            a[size++] = v
        }

        fun isEmpty() = size == 0
        fun sortedDescending(): FloatArray = a.copyOf(size).also { it.sort() }.reversedArray()
    }

    companion object {
        const val SPECTRUM_N = 4096

        /** A Blackman-windowed sinc at 4 kHz, long enough that nothing above 6 kHz survives to alias down. */
        fun lowPass(sampleRate: Int, decimate: Int): DoubleArray {
            if (decimate == 1) return doubleArrayOf(1.0)
            val cutoff = 4000.0 / sampleRate
            var n = (5.5 * sampleRate / 4000).toInt() or 1
            n = max(n, 9)
            val mid = (n - 1) / 2.0
            val h = DoubleArray(n) { i ->
                val x = i - mid
                val sinc = if (x == 0.0) 2 * cutoff else Math.sin(2 * Math.PI * cutoff * x) / (Math.PI * x)
                val w = 0.42 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)) + 0.08 * Math.cos(4 * Math.PI * i / (n - 1))
                sinc * w
            }
            val sum = h.sum()
            for (i in h.indices) h[i] /= sum
            return h
        }
    }
}
