package com.songaudit.analysis

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * An acoustic fingerprint in the manner of Haitsma and Kalker (Philips, 2002).
 *
 * Every 46 ms, the energy of 33 bands between 300 Hz and 2 kHz, measured
 * over the next 370 ms, is compared
 * with its neighbour and with the same pair one frame earlier; each of the 32
 * comparisons is one bit. The signs of those differences survive MP3, AAC,
 * resampling and level changes, so the same recording gives nearly the same
 * bits in any format, and a different one gives noise.
 *
 * Frames sit at fixed times rather than fixed sample counts, so a 44.1 kHz and
 * a 48 kHz copy line up frame for frame.
 */
object Fingerprint {

    const val HOP_SECONDS = 0.0464
    const val FRAME_SECONDS = 0.3715
    private const val BANDS = 33

    fun compute(mono: FloatArray, count: Int, rate: Double): IntArray {
        val frame = (FRAME_SECONDS * rate).roundToInt()
        if (count < frame * 4) return IntArray(0)
        val n = Fft.nextPow2(frame)
        val fft = Fft(n)
        val window = Fft.hann(frame)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val binHz = rate / n
        val edges = IntArray(BANDS + 1) { m -> (300.0 * (2000.0 / 300.0).pow(m.toDouble() / BANDS) / binHz).roundToInt() }
        for (m in 1..BANDS) if (edges[m] <= edges[m - 1]) edges[m] = edges[m - 1] + 1

        val frames = ((count - frame) / (HOP_SECONDS * rate)).toInt() + 1
        val out = IntArray(max(0, frames - 1))
        var previous = DoubleArray(BANDS)
        var current = DoubleArray(BANDS)
        for (f in 0 until frames) {
            val start = (f * HOP_SECONDS * rate).roundToInt()
            for (i in 0 until n) {
                re[i] = if (i < frame) mono[start + i] * window[i] else 0.0
                im[i] = 0.0
            }
            fft.transform(re, im)
            for (m in 0 until BANDS) {
                var e = 0.0
                for (k in edges[m] until edges[m + 1]) e += re[k] * re[k] + im[k] * im[k]
                current[m] = e
            }
            if (f > 0) {
                var word = 0
                for (m in 0 until BANDS - 1) {
                    val d = (current[m] - current[m + 1]) - (previous[m] - previous[m + 1])
                    if (d > 0) word = word or (1 shl m)
                }
                out[f - 1] = word
            }
            val t = previous
            previous = current
            current = t
        }
        return out
    }

    /** Frames that must overlap before a bit error rate means anything: about 4.6 seconds. */
    const val MIN_OVERLAP = 100

    /** Below this two prints are the same recording. Different songs sit near 0.5. */
    const val SAME = 0.25f

    /** Bit error rate of [b] shifted by [offset] frames against [a], or 1 when they barely overlap. */
    fun ber(a: IntArray, b: IntArray, offset: Int): Float {
        val from = max(0, -offset)
        val to = min(a.size, b.size - offset)
        if (to - from < MIN_OVERLAP) return 1f
        var errors = 0
        for (i in from until to) errors += Integer.bitCount(a[i] xor b[i + offset])
        return errors / (32f * (to - from))
    }

    class Match(val offset: Int, val ber: Float)

    fun best(a: IntArray, b: IntArray, around: Int = 0, radius: Int = 3): Match {
        var best = Match(around, 1f)
        for (o in around - radius..around + radius) {
            val e = ber(a, b, o)
            if (e < best.ber) best = Match(o, e)
        }
        return best
    }
}

/**
 * Finds which fingerprints are the same recording without comparing every
 * pair: each frame's 32-bit word is looked up exactly in a sorted table of
 * every other track's words. A real match shares many exact words at one
 * consistent offset; only those candidates get the full bit-error check.
 *
 * Only every [STRIDE]th frame of each print goes into the table, which keeps
 * a 20,000-track library to tens of megabytes; the queries use every frame,
 * so a match still finds its offset.
 */
class FingerprintIndex(private val prints: List<IntArray>) {

    private val table: LongArray

    init {
        var size = 0
        for (p in prints) size += (p.size + STRIDE - 1) / STRIDE
        val t = LongArray(size)
        var at = 0
        for ((track, p) in prints.withIndex()) {
            var f = 0
            while (f < p.size) {
                // Silence and full-scale noise both produce degenerate words that would match everything.
                val w = p[f]
                if (w != 0 && w != -1) t[at++] = (w.toLong() shl 32) or (track.toLong() shl 12) or f.toLong()
                f += STRIDE
            }
        }
        table = t.copyOf(at).also { it.sort() }
    }

    class Pair(val a: Int, val b: Int, val offset: Int, val ber: Float)

    /** Every pair of tracks whose prints match, each pair once with a < b. */
    fun matches(accept: (Int, Int) -> Boolean = { _, _ -> true }): List<Pair> {
        val out = ArrayList<Pair>()
        val votes = HashMap<Long, Int>()
        for ((a, p) in prints.withIndex()) {
            votes.clear()
            for ((f, w) in p.withIndex()) {
                if (w == 0 || w == -1) continue
                var i = lowerBound(w)
                var hits = 0
                while (i < table.size && (table[i] ushr 32).toInt() == w) {
                    val b = ((table[i] ushr 12) and 0xFFFFF).toInt()
                    val fb = (table[i] and 0xFFF).toInt()
                    if (b > a && accept(a, b)) {
                        val key = (b.toLong() shl 20) or ((fb - f + OFFSET_BIAS).toLong() and 0xFFFFF)
                        votes[key] = (votes[key] ?: 0) + 1
                    }
                    i++
                    // A word shared by hundreds of tracks says nothing about any of them.
                    if (++hits > COMMON) break
                }
            }
            val tried = HashSet<Int>()
            for ((key, count) in votes.entries.sortedByDescending { it.value }) {
                if (count < MIN_VOTES) break
                val b = (key ushr 20).toInt()
                if (!tried.add(b)) continue
                val offset = (key and 0xFFFFF).toInt() - OFFSET_BIAS
                val m = Fingerprint.best(p, prints[b], offset, radius = 2)
                if (m.ber < Fingerprint.SAME) out += Pair(a, b, m.offset, m.ber)
            }
        }
        return out
    }

    private fun lowerBound(w: Int): Int {
        val key = w.toLong() shl 32
        var lo = 0
        var hi = table.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (table[mid] < key) lo = mid + 1 else hi = mid
        }
        return lo
    }

    companion object {
        const val STRIDE = 3
        const val MIN_VOTES = 2
        private const val COMMON = 64
        private const val OFFSET_BIAS = 1 shl 19
    }
}
