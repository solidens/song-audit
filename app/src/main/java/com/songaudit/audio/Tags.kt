package com.songaudit.audio

/**
 * The handful of tags the audit reads, whatever container they came from.
 *
 * Every format's reader turns its own field names into Vorbis-comment names
 * and hands them to [set], so the rules for "3/12" track numbers and the
 * three spellings of album artist live in one place.
 */
class Tags {
    var title: String? = null
    var artist: String? = null
    var album: String? = null
    var albumArtist: String? = null
    var track = 0
    var trackTotal = 0
    var disc = 0
    var discTotal = 0
    var date: String? = null
    var genre: String? = null
    var compilation = false

    fun set(key: String, raw: String) {
        val value = raw.trim().trimEnd('\u0000').trim()
        if (value.isEmpty()) return
        when (key.uppercase().replace("_", " ").trim()) {
            "TITLE" -> title = title ?: value
            "ARTIST" -> artist = artist ?: value
            "ALBUM" -> album = album ?: value
            "ALBUMARTIST", "ALBUM ARTIST", "ALBUM_ARTIST", "BAND" -> albumArtist = albumArtist ?: value
            "TRACKNUMBER", "TRACK" -> {
                val (n, total) = numberPair(value)
                if (track == 0) track = n
                if (trackTotal == 0) trackTotal = total
            }
            "TRACKTOTAL", "TOTALTRACKS" -> if (trackTotal == 0) trackTotal = number(value)
            "DISCNUMBER", "DISC" -> {
                val (n, total) = numberPair(value)
                if (disc == 0) disc = n
                if (discTotal == 0) discTotal = total
            }
            "DISCTOTAL", "TOTALDISCS" -> if (discTotal == 0) discTotal = number(value)
            "DATE", "YEAR", "ORIGINALDATE" -> date = date ?: value
            "GENRE" -> genre = genre ?: value
            "COMPILATION" -> compilation = compilation || value == "1" || value.equals("true", ignoreCase = true)
        }
    }

    private fun numberPair(value: String): Pair<Int, Int> {
        val slash = value.indexOf('/')
        return if (slash < 0) number(value) to 0
        else number(value.substring(0, slash)) to number(value.substring(slash + 1))
    }

    /** Leading digits only: "03", "3 of 12" and "3a" all read as 3. */
    private fun number(value: String): Int =
        value.trim().takeWhile { it.isDigit() }.take(4).toIntOrNull() ?: 0
}

/** An embedded picture: what is there and how much of the file it takes. */
class Picture(val type: Int, val mime: String, val width: Int, val height: Int, val bytes: Int)
