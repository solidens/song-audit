package com.songaudit.fix

import com.songaudit.audio.Crc
import java.io.OutputStream
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * A FLAC encoder for the one job this app has for one: writing a file again
 * at the resolution it really has.
 *
 * Fixed 4096-sample blocks; per channel the cheapest of constant, the five
 * fixed predictors and LPC up to order 8 (12 above 48 kHz) from a Tukey-
 * windowed autocorrelation; stereo as left/right, left/side, side/right or
 * mid/side, whichever estimates smallest; partitioned Rice residuals. That is
 * roughly what `flac -5` does, and the files come out within a few percent
 * of its size. Every file it writes is decoded again and checked against its
 * own MD5 before it replaces anything.
 */
class FlacEncoder(
    private val out: OutputStream,
    val sampleRate: Int,
    val channels: Int,
    val bits: Int,
    private val blockSize: Int = 4096,
) {
    var totalSamples = 0L
        private set
    var minFrame = Int.MAX_VALUE
        private set
    var maxFrame = 0
        private set

    private val md5 = MessageDigest.getInstance("MD5")
    private val bytesPerSample = (bits + 7) / 8
    private var md5Buf = ByteArray(blockSize * channels * bytesPerSample)
    private val pending = Array(channels) { IntArray(blockSize) }
    private var fill = 0
    private var frameNumber = 0L

    private val maxOrder = if (sampleRate > 48000) 12 else 8
    private val precision = if (bits <= 16) 12 else 14
    private val writer = BitWriter(blockSize * channels * 4 + 1024)
    private val window = tukey(blockSize)
    private val residual = IntArray(blockSize)
    private val best = IntArray(blockSize)
    private val mid = IntArray(blockSize)
    private val side = IntArray(blockSize)
    private val weighted = DoubleArray(blockSize)
    private val shifted = IntArray(blockSize)

    /** Takes [n] samples per channel; whole blocks are encoded as they fill. */
    fun write(samples: Array<IntArray>, n: Int) {
        var at = 0
        while (at < n) {
            val take = min(n - at, blockSize - fill)
            for (c in 0 until channels) System.arraycopy(samples[c], at, pending[c], fill, take)
            fill += take
            at += take
            if (fill == blockSize) {
                frame(pending, blockSize)
                fill = 0
            }
        }
    }

    /** Encodes what is left and returns the MD5 of every sample written. */
    fun finish(): ByteArray {
        if (fill > 0) frame(pending, fill)
        fill = 0
        return md5.digest()
    }

    // -- Frame ------------------------------------------------------------------

    private fun frame(x: Array<IntArray>, n: Int) {
        signature(x, n)
        totalSamples += n
        val w = writer
        w.reset()

        var assignment = channels - 1
        if (channels == 2) {
            val l = x[0]
            val r = x[1]
            for (i in 0 until n) {
                side[i] = l[i] - r[i]
                mid[i] = (l[i] + r[i]) shr 1
            }
            val cl = estimate(l, n)
            val cr = estimate(r, n)
            val cm = estimate(mid, n)
            val cs = estimate(side, n)
            val options = longArrayOf(cl + cr, cl + cs, cs + cr, cm + cs)
            assignment = when (options.indices.minBy { options[it] }) {
                0 -> 1
                1 -> 8
                2 -> 9
                else -> 10
            }
        }

        // Header.
        w.bits(0xFFF8, 16)
        val sizeCode = blockSizeCode(n)
        w.bits(sizeCode, 4)
        w.bits(rateCode(sampleRate), 4)
        w.bits(assignment, 4)
        w.bits(depthCode(bits), 3)
        w.bits(0, 1)
        utf8(w, frameNumber++)
        if (sizeCode == 6) w.bits(n - 1, 8) else if (sizeCode == 7) w.bits(n - 1, 16)
        w.bits(Crc.crc8(w.buf, 0, w.length), 8)

        when (assignment) {
            8 -> { subframe(x[0], n, bits); subframe(side, n, bits + 1) }
            9 -> { subframe(side, n, bits + 1); subframe(x[1], n, bits) }
            10 -> { subframe(mid, n, bits); subframe(side, n, bits + 1) }
            else -> for (c in 0 until channels) subframe(x[c], n, bits)
        }
        w.align()
        val crc = Crc.crc16(w.buf, 0, w.length)
        w.bits(crc, 16)
        out.write(w.buf, 0, w.length)
        minFrame = min(minFrame, w.length)
        maxFrame = max(maxFrame, w.length)
    }

    private fun signature(x: Array<IntArray>, n: Int) {
        val need = n * channels * bytesPerSample
        if (md5Buf.size < need) md5Buf = ByteArray(need)
        var at = 0
        for (i in 0 until n) {
            for (c in 0 until channels) {
                var v = x[c][i]
                for (b in 0 until bytesPerSample) {
                    md5Buf[at++] = v.toByte()
                    v = v shr 8
                }
            }
        }
        md5.update(md5Buf, 0, need)
    }

    /** A quick cost for choosing the stereo mode: the smallest sum of fixed-predictor residuals. */
    private fun estimate(x: IntArray, n: Int): Long {
        val sums = LongArray(5)
        for (i in 4 until n) {
            val e0 = x[i].toLong()
            val e1 = e0 - x[i - 1]
            val e2 = e1 - (x[i - 1].toLong() - x[i - 2])
            val e3 = e2 - (x[i - 1].toLong() - 2L * x[i - 2] + x[i - 3])
            val e4 = e3 - (x[i - 1].toLong() - 3L * x[i - 2] + 3L * x[i - 3] - x[i - 4])
            sums[0] += abs(e0); sums[1] += abs(e1); sums[2] += abs(e2); sums[3] += abs(e3); sums[4] += abs(e4)
        }
        return sums.min()
    }

    // -- Subframes --------------------------------------------------------------

    private fun subframe(input: IntArray, n: Int, frameBps: Int) {
        val w = writer
        if ((1 until n).all { input[it] == input[0] }) {
            w.bits(0, 8) // padding bit, CONSTANT, no wasted bits
            w.bits(input[0] and mask(frameBps), frameBps)
            return
        }
        // Low bits that are zero in every sample -- 16-bit audio in a 24-bit file -- are not stored.
        var used = 0
        for (i in 0 until n) used = used or input[i]
        val wasted = Integer.numberOfTrailingZeros(used).coerceAtMost(frameBps - 1)
        val x: IntArray
        if (wasted > 0) {
            for (i in 0 until n) shifted[i] = input[i] shr wasted
            x = shifted
        } else {
            x = input
        }
        val bps = frameBps - wasted

        // Best so far: verbatim.
        var bestBits = n.toLong() * bps
        var bestKind = VERBATIM
        var bestOrder = 0
        var bestCoefs = IntArray(0)
        var bestShift = 0
        var bestRice: Rice? = null

        for (order in 0..min(4, n - 1)) {
            fixedResidual(x, n, order, residual)
            val rice = Rice.plan(residual, n, order)
            val cost = 8L + order * bps + rice.bits
            if (cost < bestBits) {
                bestBits = cost
                bestKind = FIXED
                bestOrder = order
                bestRice = rice
                System.arraycopy(residual, 0, best, 0, n)
            }
        }

        if (n > maxOrder * 2) {
            val lpc = lpc(x, n)
            for (order in 1..lpc.size) {
                val q = quantize(lpc[order - 1]) ?: continue
                if (!lpcResidual(x, n, q.first, q.second, residual)) continue
                val rice = Rice.plan(residual, n, order)
                val cost = 8L + order * bps + 4 + 5 + order * precision + rice.bits
                if (cost < bestBits) {
                    bestBits = cost
                    bestKind = LPC
                    bestOrder = order
                    bestCoefs = q.first
                    bestShift = q.second
                    bestRice = rice
                    System.arraycopy(residual, 0, best, 0, n)
                }
            }
        }

        val m = mask(bps)
        // Header: a zero, the type, and the wasted-bits count in unary after a flag.
        fun header(type: Int) {
            w.bits(type shl 1 or (if (wasted > 0) 1 else 0), 8)
            if (wasted > 0) w.unary(wasted - 1)
        }
        when (bestKind) {
            VERBATIM -> {
                header(1)
                for (i in 0 until n) w.bits(x[i] and m, bps)
            }
            FIXED -> {
                header(8 + bestOrder)
                for (i in 0 until bestOrder) w.bits(x[i] and m, bps)
                bestRice!!.write(w, best, n, bestOrder)
            }
            else -> {
                header(32 + bestOrder - 1)
                for (i in 0 until bestOrder) w.bits(x[i] and m, bps)
                w.bits(precision - 1, 4)
                w.bits(bestShift, 5)
                for (c in bestCoefs) w.bits(c and mask(precision), precision)
                bestRice!!.write(w, best, n, bestOrder)
            }
        }
    }

    private fun fixedResidual(x: IntArray, n: Int, order: Int, r: IntArray) {
        when (order) {
            0 -> for (i in 0 until n) r[i] = x[i]
            1 -> for (i in 1 until n) r[i] = x[i] - x[i - 1]
            2 -> for (i in 2 until n) r[i] = x[i] - 2 * x[i - 1] + x[i - 2]
            3 -> for (i in 3 until n) r[i] = x[i] - 3 * x[i - 1] + 3 * x[i - 2] - x[i - 3]
            4 -> for (i in 4 until n) r[i] = x[i] - 4 * x[i - 1] + 6 * x[i - 2] - 4 * x[i - 3] + x[i - 4]
        }
    }

    /** False when a wild predictor would need a residual past what decoders hold in 32 bits. */
    private fun lpcResidual(x: IntArray, n: Int, q: IntArray, shift: Int, r: IntArray): Boolean {
        val order = q.size
        for (i in order until n) {
            var sum = 0L
            for (j in 0 until order) sum += q[j].toLong() * x[i - 1 - j]
            val e = x[i] - (sum shr shift)
            if (e > LIMIT || e < -LIMIT) return false
            r[i] = e.toInt()
        }
        return true
    }

    /** Predictor coefficients for every order up to [maxOrder], by Levinson-Durbin. */
    private fun lpc(x: IntArray, n: Int): List<DoubleArray> {
        val win = if (n == blockSize) window else tukey(n)
        for (i in 0 until n) weighted[i] = x[i] * win[i]
        val r = DoubleArray(maxOrder + 1)
        for (lag in 0..maxOrder) {
            var s = 0.0
            for (i in lag until n) s += weighted[i] * weighted[i - lag]
            r[lag] = s
        }
        if (r[0] <= 0.0) return emptyList()
        r[0] *= 1.0 + 1e-9 // a touch of white noise keeps the recursion stable on pure tones
        val out = ArrayList<DoubleArray>()
        val c = DoubleArray(maxOrder)
        val prev = DoubleArray(maxOrder)
        var err = r[0]
        for (i in 0 until maxOrder) {
            var acc = r[i + 1]
            for (j in 0 until i) acc -= c[j] * r[i - j]
            val k = acc / err
            System.arraycopy(c, 0, prev, 0, i)
            for (j in 0 until i) c[j] = prev[j] - k * prev[i - 1 - j]
            c[i] = k
            err *= 1 - k * k
            out += c.copyOf(i + 1)
            if (err <= 0.0) break
        }
        return out
    }

    /** Coefficients as [precision]-bit integers and the shift that scales them back, or null if they do not fit. */
    private fun quantize(c: DoubleArray): Pair<IntArray, Int>? {
        var cmax = 0.0
        for (v in c) cmax = max(cmax, abs(v))
        if (cmax <= 0.0 || cmax.isNaN()) return null
        val log2 = Math.getExponent(cmax) + 1
        val shift = min(MAX_SHIFT, precision - 1 - log2)
        if (shift < 0) return null
        val hi = (1 shl (precision - 1)) - 1
        val lo = -(1 shl (precision - 1))
        val q = IntArray(c.size)
        var error = 0.0
        for (j in c.indices) {
            error += c[j] * (1 shl shift)
            val v = error.roundToLong().coerceIn(lo.toLong(), hi.toLong()).toInt()
            q[j] = v
            error -= v
        }
        return q to shift
    }

    // -- Residual coding ---------------------------------------------------------

    /** A partitioned Rice coding chosen for one residual: the partition order and each partition's parameter. */
    private class Rice(val partitionOrder: Int, val params: IntArray, val bits: Long) {
        fun write(w: BitWriter, r: IntArray, n: Int, order: Int) {
            val wide = params.any { it > 14 }
            w.bits(if (wide) 1 else 0, 2)
            w.bits(partitionOrder, 4)
            val length = n shr partitionOrder
            var at = order
            for (p in params.indices) {
                val k = params[p]
                w.bits(k, if (wide) 5 else 4)
                val end = (p + 1) * length
                while (at < end) {
                    val v = r[at++]
                    val u = (v shl 1) xor (v shr 31)
                    w.unary(u ushr k)
                    if (k > 0) w.bits(u and ((1 shl k) - 1), k)
                }
            }
        }

        companion object {
            private const val MAX_PARTITION_ORDER = 8

            fun plan(r: IntArray, n: Int, order: Int): Rice {
                // The finest partitioning first; coarser ones are sums of its sums.
                var top = 0
                while (top < MAX_PARTITION_ORDER && n % (2 shl top) == 0 && (n shr (top + 1)) > order && (n shr (top + 1)) >= 16) top++
                val parts = 1 shl top
                val length = n shr top
                val sums = LongArray(parts)
                for (p in 0 until parts) {
                    var s = 0L
                    for (i in max(order, p * length) until (p + 1) * length) {
                        val v = r[i]
                        s += ((v shl 1) xor (v shr 31)).toLong() and 0xFFFFFFFFL
                    }
                    sums[p] = s
                }
                var bestBits = Long.MAX_VALUE
                var best: Rice? = null
                var level = sums
                for (po in top downTo 0) {
                    val count = 1 shl po
                    val len = n shr po
                    val params = IntArray(count)
                    var total = 0L
                    var wide = false
                    for (p in 0 until count) {
                        val samples = (len - if (p == 0) order else 0).toLong()
                        val (k, bits) = param(level[p], samples)
                        params[p] = k
                        if (k > 14) wide = true
                        total += bits
                    }
                    total += 6 + count * (if (wide) 5L else 4L)
                    if (total < bestBits) {
                        bestBits = total
                        best = Rice(po, params, total)
                    }
                    if (po > 0) level = LongArray(count / 2) { level[2 * it] + level[2 * it + 1] }
                }
                return best!!
            }

            /** The Rice parameter for a partition, and the bits it costs: unary quotients plus k bits each. */
            private fun param(sum: Long, samples: Long): Pair<Int, Long> {
                if (samples <= 0) return 0 to 0L
                var bestK = 0
                var bestBits = Long.MAX_VALUE
                val mean = sum / samples
                val guess = if (mean <= 0) 0 else 63 - java.lang.Long.numberOfLeadingZeros(mean)
                for (k in max(0, guess - 1)..min(30, guess + 1)) {
                    val bits = samples * (k + 1) + (sum ushr k)
                    if (bits < bestBits) {
                        bestBits = bits
                        bestK = k
                    }
                }
                return bestK to bestBits
            }
        }
    }

    private companion object {
        const val VERBATIM = 0
        const val FIXED = 1
        const val LPC = 2
        const val MAX_SHIFT = 15
        const val LIMIT = 1L shl 30

        fun mask(bits: Int): Int = if (bits >= 32) -1 else (1 shl bits) - 1

        fun tukey(n: Int, p: Double = 0.5): DoubleArray {
            val w = DoubleArray(n) { 1.0 }
            val np = (p / 2 * n).toInt()
            if (np > 1) {
                for (i in 0 until np) {
                    val v = 0.5 - 0.5 * cos(Math.PI * i / np)
                    w[i] = v
                    w[n - 1 - i] = v
                }
            }
            return w
        }

        fun blockSizeCode(n: Int): Int = when (n) {
            192 -> 1
            576 -> 2
            1152 -> 3
            2304 -> 4
            4608 -> 5
            256 -> 8
            512 -> 9
            1024 -> 10
            2048 -> 11
            4096 -> 12
            8192 -> 13
            16384 -> 14
            32768 -> 15
            else -> if (n <= 256) 6 else 7
        }

        fun rateCode(rate: Int): Int = when (rate) {
            88200 -> 1
            176400 -> 2
            192000 -> 3
            8000 -> 4
            16000 -> 5
            22050 -> 6
            24000 -> 7
            32000 -> 8
            44100 -> 9
            48000 -> 10
            96000 -> 11
            else -> 0
        }

        fun depthCode(bits: Int): Int = when (bits) {
            8 -> 1
            12 -> 2
            16 -> 4
            20 -> 5
            24 -> 6
            else -> 0
        }

        /** The frame number the way FLAC writes it: UTF-8's length scheme, up to 36 bits. */
        fun utf8(w: BitWriter, v: Long) {
            when {
                v < 0x80 -> w.bits(v.toInt(), 8)
                v < 0x800 -> {
                    w.bits(0xC0 or (v ushr 6).toInt(), 8)
                    w.bits(0x80 or (v and 0x3F).toInt(), 8)
                }
                else -> {
                    val extra = when {
                        v < 0x10000 -> 2
                        v < 0x200000 -> 3
                        v < 0x4000000 -> 4
                        v < 0x80000000L -> 5
                        else -> 6
                    }
                    val lead = if (extra == 6) 0xFE else (0xFF shl (7 - extra)) and 0xFF
                    w.bits(lead or (v ushr (6 * extra)).toInt(), 8)
                    for (i in extra - 1 downTo 0) w.bits(0x80 or ((v ushr (6 * i)) and 0x3F).toInt(), 8)
                }
            }
        }
    }
}

/** Bits into bytes, most significant first. */
class BitWriter(capacity: Int) {
    var buf = ByteArray(capacity)
        private set
    var length = 0
        private set
    private var acc = 0L
    private var pending = 0

    fun reset() {
        length = 0
        acc = 0
        pending = 0
    }

    /** The low [n] bits of [v], n up to 32. */
    fun bits(v: Int, n: Int) {
        if (n == 0) return
        acc = (acc shl n) or (v.toLong() and ((1L shl n) - 1))
        pending += n
        while (pending >= 8) {
            pending -= 8
            put((acc ushr pending).toInt())
        }
        acc = acc and ((1L shl pending) - 1)
    }

    /** [q] zeros and a one. */
    fun unary(q: Int) {
        var left = q
        while (left >= 32) {
            bits(0, 32)
            left -= 32
        }
        bits(1, left + 1)
    }

    fun align() {
        if (pending > 0) bits(0, 8 - pending)
    }

    private fun put(b: Int) {
        if (length == buf.size) buf = buf.copyOf(buf.size * 2)
        buf[length++] = b.toByte()
    }
}
