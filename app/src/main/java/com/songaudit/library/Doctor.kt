package com.songaudit.library

import java.util.Locale

/** Things about an album's tags and art that make a player show it wrongly, or slowly. */
enum class Problem(val title: String) {
    NO_COVER("No cover"),
    HEAVY_COVER("Heavy cover"),
    SPLIT("Shows as several albums"),
    MISSING_TAGS("Missing tags"),
    INCOMPLETE("Missing tracks"),
}

class Finding(val problem: Problem, val album: Album, val detail: String, val tracks: List<Track>)

/**
 * Reads the tags the scan collected and says what is wrong, per album.
 *
 * It only reports. Every check here is about how a player will present the
 * album, which is what the person holding the player sees.
 */
object Doctor {

    /** An embedded picture past this is heavy on a small player: it is decoded for every track shown. */
    const val HEAVY_BYTES = 1_000_000
    const val HEAVY_PIXELS = 1600

    fun examine(albums: List<Album>, coverFiles: Set<String>): List<Finding> {
        val out = ArrayList<Finding>()
        for (album in albums) {
            noCover(album, coverFiles)?.let(out::add)
            heavyCover(album)?.let(out::add)
            split(album)?.let(out::add)
            missingTags(album)?.let(out::add)
            incomplete(album)?.let(out::add)
        }
        return out
    }

    private fun noCover(album: Album, coverFiles: Set<String>): Finding? {
        if (album.folder in coverFiles || album.tracks.any { it.pictures > 0 }) return null
        return Finding(Problem.NO_COVER, album, "No embedded art and no image in the folder", album.tracks)
    }

    private fun heavyCover(album: Album): Finding? {
        val heavy = album.tracks.filter {
            it.pictureBytes > HEAVY_BYTES || maxOf(it.pictureWidth, it.pictureHeight) > HEAVY_PIXELS
        }
        if (heavy.isEmpty()) return null
        val biggest = heavy.maxBy { it.pictureBytes }
        val total = heavy.sumOf { it.pictureBytes.toLong() }
        val dims = if (biggest.pictureWidth > 0) " · ${biggest.pictureWidth}×${biggest.pictureHeight}" else ""
        val detail = "${mb(biggest.pictureBytes.toLong())}$dims in ${count(heavy.size, "track")} · ${mb(total)} in all"
        return Finding(Problem.HEAVY_COVER, album, detail, heavy)
    }

    /**
     * Players group by album and album artist, compared exactly. Any second
     * spelling in one folder, or a various-artists album without an album
     * artist, comes out as more than one album.
     */
    private fun split(album: Album): Finding? {
        val tracks = album.tracks
        if (tracks.size < 2) return null
        val albums = tracks.mapNotNull { it.album }.distinct()
        // A folder of loose singles is not one album split in pieces.
        if (albums.size > 3) return null
        val reasons = ArrayList<String>()
        if (albums.size > 1) reasons += "${albums.size} album names: " + albums.joinToString(" / ") { "“$it”" }
        val albumArtists = tracks.mapNotNull { it.albumArtist }.distinct()
        if (albumArtists.size > 1) reasons += "${albumArtists.size} album artists"
        val artists = tracks.mapNotNull { it.artist }.distinct()
        val missingAlbumArtist = tracks.filter { it.albumArtist == null }
        if (artists.size > 1 && missingAlbumArtist.isNotEmpty() && albums.size == 1) {
            reasons += "${artists.size} artists and no album artist"
        }
        if (reasons.isEmpty()) return null
        return Finding(Problem.SPLIT, album, reasons.joinToString(" · "), tracks)
    }

    private fun missingTags(album: Album): Finding? {
        val tracks = album.tracks
        val parts = ArrayList<String>()
        fun check(label: String, missing: (Track) -> Boolean) {
            val n = tracks.count(missing)
            if (n > 0) parts += "$label in ${if (n == tracks.size) "all" else n.toString()}"
        }
        check("No title") { it.title == null }
        check("no artist") { it.artist == null }
        check("no album") { it.album == null }
        check("no track number") { it.track == 0 }
        if (parts.isEmpty()) return null
        val bad = tracks.filter { it.title == null || it.artist == null || it.album == null || it.track == 0 }
        return Finding(Problem.MISSING_TAGS, album, parts.joinToString(" · ").replaceFirstChar { it.uppercase() }, bad)
    }

    /** Track numbers or a track total that say some of the album is not here. */
    private fun incomplete(album: Album): Finding? {
        val tracks = album.tracks
        if (tracks.any { it.track == 0 }) return null
        var have = 0
        var want = 0
        for ((_, disc) in tracks.groupBy { it.disc }) {
            val numbers = disc.map { it.track }.toSet()
            val total = maxOf(disc.maxOf { it.trackTotal }, numbers.max())
            // A lone track numbered 7 is a single, not an album missing six.
            if (disc.size == 1 && disc[0].trackTotal == 0) continue
            have += numbers.count { it in 1..total }
            want += total
        }
        if (want == 0 || have >= want || want > 99) return null
        return Finding(Problem.INCOMPLETE, album, "$have of $want tracks", tracks)
    }

    fun mb(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1e9)
        bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1e6)
        else -> String.format(Locale.US, "%d KB", bytes / 1000)
    }

    fun count(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"
}
