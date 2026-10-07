package com.songaudit.analysis

import java.util.Locale

/**
 * What a file turned out to be, against what it says it is.
 *
 * Ordered by how much it matters: a damaged file is lost audio, a lossy
 * source is lost quality, an upsampled or padded file is only wasted space.
 */
enum class Issue(val bit: Int) {
    DAMAGED(1),
    LOSSY_SOURCE(2),
    MAYBE_LOSSY(4),
    UPSAMPLED(8),
    PADDED(16),
    REENCODED(32),
    ;

    companion object {
        fun of(mask: Int): List<Issue> = entries.filter { mask and it.bit != 0 }
        fun mask(issues: Collection<Issue>): Int = issues.fold(0) { m, i -> m or i.bit }
    }
}

object Verdict {

    /** A cliff below this in a lossless file is an encoder's lowpass beyond reasonable doubt. */
    const val LOSSY_HZ = 19_000

    /** Up to here it could be a 256-320 kbps encode, or a mastering lowpass. */
    const val MAYBE_LOSSY_HZ = 20_600

    /** A hi-res file whose wall sits here was a CD or a 48 kHz master. */
    const val UPSAMPLED_HZ = 24_500

    /** A fall this deep across 22.05 or 24 kHz means nothing real lives above it. */
    const val ULTRASONIC_DB = 40f

    /** A lossy file this rich that cuts off this low was made from a poorer one. */
    const val REENCODE_KBPS = 256
    const val REENCODE_HZ = 17_000

    fun issues(
        lossless: Boolean,
        sampleRate: Int,
        bits: Int,
        bitrate: Int,
        damaged: Boolean,
        cutoffHz: Int,
        ultrasonicDropDb: Float,
        effectiveBits: Int,
    ): Set<Issue> {
        val out = LinkedHashSet<Issue>()
        if (damaged) out += Issue.DAMAGED
        if (lossless) {
            when {
                cutoffHz in 1..LOSSY_HZ -> out += Issue.LOSSY_SOURCE
                cutoffHz in LOSSY_HZ + 1..MAYBE_LOSSY_HZ -> out += Issue.MAYBE_LOSSY
            }
            if (sampleRate >= 88_200 &&
                (cutoffHz in 1..UPSAMPLED_HZ || ultrasonicDropDb >= ULTRASONIC_DB)
            ) {
                out += Issue.UPSAMPLED
            }
            if (bits >= 24 && effectiveBits in 1..16) out += Issue.PADDED
        } else if (bitrate >= REENCODE_KBPS && cutoffHz in 1..REENCODE_HZ) {
            out += Issue.REENCODED
        }
        return out
    }

    /** The MP3 bitrate a cutoff is typical of, as LAME sets its lowpass. */
    fun likeBitrate(cutoffHz: Int): String = when {
        cutoffHz <= 11_500 -> "64 kbps or less"
        cutoffHz <= 15_500 -> "96–112 kbps"
        cutoffHz <= 17_200 -> "128 kbps"
        cutoffHz <= 18_200 -> "160 kbps"
        cutoffHz <= 19_200 -> "192 kbps"
        else -> "256–320 kbps"
    }

    fun khz(hz: Int): String = String.format(Locale.US, "%.1f kHz", hz / 1000.0)
}
