package com.songaudit.fix

import com.songaudit.library.Album
import com.songaudit.library.Problem
import com.songaudit.library.Track

/** Tag changes for one album, worked out before anything is written, and said in words. */
class TagPlan(
    val album: Album,
    /** Per track id, the fields to set. */
    val edits: Map<Long, Map<String, String?>>,
    val lines: List<String>,
    /** What was asked for but has nothing to go on. */
    val unknown: List<String>,
) {
    val isEmpty: Boolean get() = edits.isEmpty()
}

/**
 * Turns the doctor's findings into tag changes. Everything comes from the
 * album itself -- the other tracks' tags, file names, folder names -- and
 * nothing is invented: a field with no source is left as it is and said so.
 */
object Planner {

    const val VARIOUS = "Various Artists"

    fun tags(album: Album, problems: Set<Problem>): TagPlan {
        val edits = LinkedHashMap<Long, MutableMap<String, String?>>()
        val lines = ArrayList<String>()
        val unknown = ArrayList<String>()
        fun set(t: Track, field: String, value: String?) {
            edits.getOrPut(t.id) { LinkedHashMap() }[field] = value
        }
        fun say(what: String, value: String, n: Int) {
            if (n > 0) lines += "$what → “$value” · ${count(n, album.tracks.size)}"
        }

        val tracks = album.tracks
        if (Problem.SPLIT in problems) {
            val names = tracks.mapNotNull { it.album }
            if (names.distinct().size > 1) {
                val target = Album.mostCommon(names)!!
                val change = tracks.filter { it.album != target }
                change.forEach { set(it, TagEdit.ALBUM, target) }
                say("Album", target, change.size)
            }
            val artist = albumArtist(tracks)
            if (artist != null) {
                val change = tracks.filter { it.albumArtist != artist }
                change.forEach { set(it, TagEdit.ALBUM_ARTIST, artist) }
                say("Album artist", artist, change.size)
                if (artist == VARIOUS) {
                    val flag = tracks.filter { !it.compilation }
                    flag.forEach { set(it, TagEdit.COMPILATION, "1") }
                    if (flag.isNotEmpty()) lines += "Marked as a compilation · ${count(flag.size, tracks.size)}"
                }
            }
        }

        if (Problem.MISSING_TAGS in problems) {
            val folder = FolderName.parse(album.folder)
            val knownArtist = Album.mostCommon(tracks.mapNotNull { it.albumArtist })
                ?: tracks.mapNotNull { it.artist }.distinct().singleOrNull()
                ?: folder.artist
            val albumName = Album.mostCommon(tracks.mapNotNull { it.album }) ?: folder.album

            val titles = ArrayList<Track>()
            val numbers = ArrayList<Track>()
            var noTitle = 0
            var noNumber = 0
            for (t in tracks) {
                val name = FileName.parse(t.name, knownArtist)
                if (t.title == null) {
                    if (name.title != null) {
                        set(t, TagEdit.TITLE, name.title)
                        titles += t
                    } else {
                        noTitle++
                    }
                }
                if (t.track == 0) {
                    if (name.number > 0) {
                        set(t, TagEdit.TRACK, name.number.toString())
                        numbers += t
                    } else {
                        noNumber++
                    }
                }
            }
            if (titles.isNotEmpty()) lines += "Titles from file names · ${count(titles.size, tracks.size)}"
            if (numbers.isNotEmpty()) lines += "Track numbers from file names · ${count(numbers.size, tracks.size)}"
            if (noTitle > 0) unknown += "No title to be found for ${count(noTitle, tracks.size)}"
            if (noNumber > 0) unknown += "No track number in the file name of ${count(noNumber, tracks.size)}"

            val noAlbum = tracks.filter { it.album == null && TagEdit.ALBUM !in edits[it.id].orEmpty() }
            if (noAlbum.isNotEmpty()) {
                if (albumName != null) {
                    noAlbum.forEach { set(it, TagEdit.ALBUM, albumName) }
                    say("Album", albumName, noAlbum.size)
                } else {
                    unknown += "No album name to be found"
                }
            }
            val noArtist = tracks.filter { it.artist == null }
            if (noArtist.isNotEmpty()) {
                val byTrack = noArtist.groupBy { (it.albumArtist ?: knownArtist)?.takeIf { a -> a != VARIOUS } }
                for ((artist, list) in byTrack) {
                    if (artist == null) {
                        unknown += "No artist to be found for ${count(list.size, tracks.size)}"
                    } else {
                        list.forEach { set(it, TagEdit.ARTIST, artist) }
                        say("Artist", artist, list.size)
                    }
                }
            }
        }

        if (Problem.INCOMPLETE in problems) unknown += "Missing tracks cannot be fixed from here: they are not on the player"
        return TagPlan(album, edits, lines, unknown)
    }

    /**
     * The album artist an album should carry: the one most of its tracks
     * already have; else the artist of more than half its tracks, ignoring
     * guests; else, for an album of many artists, Various Artists.
     */
    fun albumArtist(tracks: List<Track>): String? {
        val tagged = tracks.mapNotNull { it.albumArtist }
        if (tagged.isNotEmpty()) {
            val target = Album.mostCommon(tagged)!!
            return target.takeIf { tracks.any { it.albumArtist != target } }
        }
        val artists = tracks.mapNotNull { it.artist }
        if (artists.distinct().size < 2) return null
        Album.mostCommon(artists)?.let { a -> if (artists.count { it == a } * 2 > tracks.size) return a }
        val leads = artists.map(::lead)
        Album.mostCommon(leads)?.let { a -> if (leads.count { it == a } * 2 > tracks.size) return a }
        return VARIOUS
    }

    /** "Artist feat. Guest" is Artist's track. "Simon & Garfunkel" stays whole. */
    fun lead(artist: String): String =
        artist.split(GUEST, limit = 2)[0].trim().ifEmpty { artist }

    private val GUEST = Regex("""\s+(?:feat\.?|ft\.?|featuring|with)\s+|\s*[(\[](?:feat\.?|ft\.?|featuring)\s""", RegexOption.IGNORE_CASE)

    private fun count(n: Int, of: Int) = when {
        n == 1 -> "1 track"
        n == of && n == 2 -> "both tracks"
        n == of -> "all $n tracks"
        else -> "$n tracks"
    }
}

/** What a file name says about its track, as rippers and shops name them. */
class FileName(val number: Int, val title: String?) {
    companion object {
        // "01 Title", "01 - Title", "01. Title", "1-01 Title", "101 Title"
        private val NUMBERED = Regex("""^(?:(\d)[-.])?(\d{1,3})(?:\s*[-._)\]]\s*|\s+)(.+)$""")
        // "Artist - 01 - Title"
        private val ARTIST_FIRST = Regex("""^(.+?)\s+-\s+(\d{1,3})\s+-\s+(.+)$""")

        fun parse(file: String, artist: String?): FileName {
            var name = file.substringBeforeLast('.')
            if (' ' !in name) name = name.replace('_', ' ')
            name = name.trim()
            ARTIST_FIRST.matchEntire(name)?.let { m ->
                return FileName(m.groupValues[2].toInt(), m.groupValues[3].trim().ifEmpty { null })
            }
            val m = NUMBERED.matchEntire(name)
            if (m == null) return FileName(0, name.ifEmpty { null })
            var number = m.groupValues[2].toInt()
            // 101 is disc 1, track 1.
            if (m.groupValues[1].isEmpty() && number > 100 && number % 100 in 1..99) number %= 100
            var title = m.groupValues[3].trim()
            if (artist != null) {
                val prefix = "$artist - "
                if (title.startsWith(prefix, ignoreCase = true)) title = title.substring(prefix.length).trim()
            }
            return FileName(number, title.ifEmpty { null })
        }
    }
}

/** What an album's folder, and the one above it, say: "Music/Artist/Album (2019)", "Artist - Album [FLAC]". */
class FolderName(val artist: String?, val album: String?) {
    companion object {
        private val TRAILING = Regex("""\s*[\[({][^\])}]*[\])}]\s*$""")
        private val YEAR_FIRST = Regex("""^[\[(]?(?:19|20)\d\d[\])]?\s*[-.]?\s+""")
        private val DISC = Regex("""^(?:cd|disc|disk|part)\s*\d+$""", RegexOption.IGNORE_CASE)
        private val NOT_ARTISTS = setOf("music", "musik", "musique", "audio", "media", "download", "downloads", "flac", "mp3", "albums", "0", "emulated", "storage", "sdcard")

        fun parse(folder: String): FolderName {
            val parts = folder.trimEnd('/').split('/')
            var at = parts.lastIndex
            // "Album/CD1": the album is the folder above.
            if (at > 0 && DISC.matches(parts[at].trim())) at--
            val name = clean(parts[at])
            val parent = parts.getOrNull(at - 1)?.trim()
            val dash = name.split(" - ")
            if (dash.size >= 2) {
                val artist = dash.first().trim()
                val album = clean(dash.drop(1).joinToString(" - ").replace(YEAR_FIRST, ""))
                return FolderName(artist.ifEmpty { null }, album.ifEmpty { null })
            }
            val artist = parent?.takeIf { p ->
                p.isNotEmpty() && p.lowercase() !in NOT_ARTISTS && !p.matches(Regex("""[0-9A-F]{4}-[0-9A-F]{4}"""))
            }?.let(::clean)
            return FolderName(artist, name.ifEmpty { null })
        }

        /** Takes off "(2019)", "[24-96]", "{FLAC}" at the end and a year at the start, as often as they come. */
        private fun clean(raw: String): String {
            var s = raw.trim().replace(YEAR_FIRST, "")
            while (true) {
                val next = s.replace(TRAILING, "").trim()
                if (next == s || next.isEmpty()) break
                s = next
            }
            return s
        }
    }
}
