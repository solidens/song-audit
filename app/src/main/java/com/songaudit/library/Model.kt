package com.songaudit.library

import com.songaudit.analysis.Issue

/** One audio file, as the last scan saw it. */
data class Track(
    val id: Long,
    val path: String,
    val folder: String,
    val size: Long,
    val modified: Long,
    val format: String,
    val lossless: Boolean,
    val sampleRate: Int,
    val bits: Int,
    val channels: Int,
    val durationMs: Long,
    val bitrate: Int,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtist: String? = null,
    val track: Int = 0,
    val trackTotal: Int = 0,
    val disc: Int = 0,
    val discTotal: Int = 0,
    val date: String? = null,
    val genre: String? = null,
    val compilation: Boolean = false,
    val pictures: Int = 0,
    /** The largest embedded picture. */
    val pictureBytes: Int = 0,
    val pictureWidth: Int = 0,
    val pictureHeight: Int = 0,
    /** FLAC's own audio MD5, hex. */
    val audioMd5: String? = null,
    val probeError: String? = null,

    // Filled by the deep pass; deepVersion 0 means it has not run yet.
    val deepVersion: Int = 0,
    val deepError: String? = null,
    val frameErrors: Int = 0,
    val md5Match: Int = -1,
    val truncated: Boolean = false,
    val decodedMd5: String? = null,
    val effectiveBits: Int = 0,
    val dr: Float = Float.NaN,
    val peakDb: Float = Float.NaN,
    val cutoffHz: Int = 0,
    val cliffDb: Float = 0f,
    val ultrasonicDb: Float = 0f,
    val issues: Int = 0,
    /** Findings the person has chosen to live with: off the lists, still on the track's page. */
    val accepted: Int = 0,
) {
    val name: String get() = path.substringAfterLast('/')
    val analysed: Boolean get() = deepVersion > 0
    val issueList: List<Issue> get() = Issue.of(issues)
    fun has(issue: Issue) = issues and issue.bit != 0

    /** Findings still waiting for a decision. */
    val open: Int get() = issues and accepted.inv()
    fun flags(issue: Issue) = open and issue.bit != 0

    /** "FLAC 24/96", "MP3 320", "DSF 2.8 MHz": what the file claims, in the shortest form. */
    val quality: String get() = when {
        format == "DSF" || format == "DFF" -> "$format ${dsdRate(sampleRate)}"
        lossless && bits > 0 -> "$format $bits/${khz(sampleRate)}"
        lossless -> "$format ${khz(sampleRate)}"
        bitrate > 0 -> "$format $bitrate"
        else -> format
    }

    companion object {
        fun khz(rate: Int): String = when {
            rate <= 0 -> "?"
            rate % 1000 == 0 -> (rate / 1000).toString()
            else -> String.format(java.util.Locale.US, "%.1f", rate / 1000.0)
        }

        fun dsdRate(rate: Int): String = "DSD" + (rate / 44100).coerceAtLeast(1)
    }
}

/** A folder of tracks: the unit people think in, and the unit players show. */
class Album(val folder: String, val tracks: List<Track>) {
    val title: String = mostCommon(tracks.mapNotNull { it.album }) ?: folder.substringAfterLast('/')
    val artist: String = mostCommon(tracks.mapNotNull { it.albumArtist })
        ?: tracks.mapNotNull { it.artist }.distinct().let { if (it.size > 1) "Various artists" else it.firstOrNull() }
        ?: folder.substringBeforeLast('/').substringAfterLast('/')
    val size: Long = tracks.sumOf { it.size }
    val durationMs: Long = tracks.sumOf { it.durationMs }
    val issues: Int = tracks.fold(0) { m, t -> m or t.issues }
    val quality: String = mostCommon(tracks.map { it.quality }) ?: ""
    val analysed: Boolean = tracks.all { it.analysed }

    /** Album DR the way the meter reports it: the mean of its tracks, rounded. */
    val dr: Float = tracks.map { it.dr }.filter { !it.isNaN() }.let { if (it.isEmpty()) Float.NaN else it.average().toFloat() }

    /** Tracks with [issue] still open: what the lists count. */
    fun count(issue: Issue) = tracks.count { it.flags(issue) }

    companion object {
        fun of(tracks: Collection<Track>): List<Album> =
            tracks.groupBy { it.folder }.map { (folder, t) -> Album(folder, t.sortedWith(trackOrder)) }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, Album::artist).thenBy(String.CASE_INSENSITIVE_ORDER, Album::title))

        val trackOrder: Comparator<Track> = compareBy<Track>({ it.disc }, { it.track }, { it.name.lowercase() })

        fun <T> mostCommon(values: List<T>): T? = values.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }
}
