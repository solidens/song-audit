package com.songaudit.analysis

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds a brick wall in an averaged spectrum.
 *
 * Lossy encoders and resamplers both leave the same mark: content up to some
 * frequency, then a drop of tens of decibels within a few hundred hertz, then
 * nothing all the way to Nyquist. Real recordings roll off gradually, or run
 * into the converter's own filter right at Nyquist. So the search looks for
 * the largest difference between the kilohertz below a point and the kilohertz
 * above it, and only believes it if the floor above stays down.
 */
object Cliff {

    class Found(val hz: Double, val drop: Double, val below: Double, val above: Double)

    /** Drops smaller than this are a roll-off, not a wall. */
    const val MIN_DROP = 20.0

    /** Below this nothing is judged: a lowpassed synth bass is not an MP3. */
    const val LOWEST_HZ = 10_500.0

    fun find(db: DoubleArray, binHz: Double): Found? {
        val smooth = smooth(db, binHz)
        val n = smooth.size
        val ref = reference(smooth, binHz, smoothed = true)
        val prefix = DoubleArray(n + 1)
        for (k in 0 until n) prefix[k + 1] = prefix[k] + (smooth[k] - ref)
        fun mean(from: Int, to: Int): Double = (prefix[to + 1] - prefix[from]) / (to - from + 1)
        fun bin(hz: Double) = (hz / binHz).roundToInt()

        val near = max(1, bin(200.0))
        val far = max(near + 1, bin(1200.0))
        val top = n - 1 - max(1, bin(50.0))
        val first = max(bin(LOWEST_HZ), far)
        val last = top - max(near + 1, bin(300.0)) - near

        var best: Found? = null
        for (k in first..last) {
            val below = mean(k - far, k - near)
            val above = mean(k + near, min(k + far, top))
            val drop = below - above
            if (drop > (best?.drop ?: MIN_DROP)) best = Found(k * binHz, drop, below, above)
        }
        best ?: return null

        // Content coming back above the wall means it was a gap, not a cutoff.
        val wall = bin(best.hz)
        var highest = Double.NEGATIVE_INFINITY
        for (k in wall + near..top) highest = max(highest, smooth[k] - ref)
        if (highest > best.above + 15) return null
        // The wall is where the level crosses halfway between the two plateaus.
        val halfway = ref + (best.below + best.above) / 2
        var edge = best.hz
        for (k in wall - near..min(top, wall + far)) {
            if (smooth[k] < halfway) {
                edge = k * binHz
                break
            }
        }
        return Found(edge, best.drop, best.below, best.above)
    }

    /**
     * How far the level falls across [boundary] when the windows either side
     * stand well apart: 4 to 1 kHz below it against 1.5 to 4.5 kHz above it.
     *
     * Resamplers do not all cut as steeply as an MP3 encoder. A gentle one
     * spreads its roll-off over several kilohertz, which [find] reads as a
     * slope. Across a hi-res file's 22.05 or 24 kHz line, though, a CD source
     * still leaves a fall of fifty decibels or more, where a real recording
     * carries on with cymbals, air and its own noise.
     */
    fun across(db: DoubleArray, binHz: Double, boundary: Double): Double {
        val smooth = smooth(db, binHz)
        val ref = reference(smooth, binHz, smoothed = true)
        val top = smooth.size - 1
        fun mean(fromHz: Double, toHz: Double): Double? {
            val a = (fromHz / binHz).roundToInt()
            val b = (toHz / binHz).roundToInt()
            if (a < 0 || b > top || b <= a) return null
            var sum = 0.0
            for (k in a..b) sum += smooth[k] - ref
            return sum / (b - a + 1)
        }
        val below = mean(boundary - 4000, boundary - 1000) ?: return 0.0
        val above = mean(boundary + 1500, boundary + 4500) ?: return 0.0
        return below - above
    }

    /** The loudest smoothed level between 200 Hz and 4 kHz: what "0 dB" means for a track. */
    fun reference(db: DoubleArray, binHz: Double, smoothed: Boolean = false): Double {
        val s = if (smoothed) db else smooth(db, binHz)
        val from = (200 / binHz).roundToInt().coerceIn(0, s.size - 1)
        val to = (4000 / binHz).roundToInt().coerceIn(from, s.size - 1)
        var m = Double.NEGATIVE_INFINITY
        for (k in from..to) m = max(m, s[k])
        return m
    }

    /** A 120 Hz moving average, so a single loud harmonic cannot pose as a plateau. */
    fun smooth(db: DoubleArray, binHz: Double): DoubleArray {
        val half = max(1, (60 / binHz).roundToInt())
        val n = db.size
        val prefix = DoubleArray(n + 1)
        for (k in 0 until n) prefix[k + 1] = prefix[k] + db[k]
        return DoubleArray(n) { k ->
            val a = max(0, k - half)
            val b = min(n - 1, k + half)
            (prefix[b + 1] - prefix[a]) / (b - a + 1)
        }
    }
}
