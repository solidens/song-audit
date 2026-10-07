package com.songaudit.library

import com.songaudit.analysis.Issue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryTest {

    private var nextId = 1L

    private fun track(
        folder: String,
        n: Int,
        title: String = "Song $n",
        format: String = "FLAC",
        rate: Int = 44100,
        bits: Int = 16,
        size: Long = 30_000_000,
        issues: Set<Issue> = emptySet(),
        album: String? = "Album",
        artist: String? = "Artist",
        albumArtist: String? = null,
        pictures: Int = 1,
        pictureBytes: Int = 200_000,
        total: Int = 0,
        lossless: Boolean = format != "MP3",
        bitrate: Int = 900,
        duration: Long = 200_000L + n * 1000,
    ) = Track(
        id = nextId++, path = "$folder/$n.${format.lowercase()}", folder = folder, size = size, modified = 0,
        format = format, lossless = lossless, sampleRate = rate, bits = if (lossless) bits else 0, channels = 2,
        durationMs = duration, bitrate = bitrate, title = title, artist = artist, album = album,
        albumArtist = albumArtist, track = n, trackTotal = total, pictures = pictures, pictureBytes = pictureBytes,
        issues = Issue.mask(issues), deepVersion = 1,
    )

    /** Every track of [a] matched to the same-numbered track of [b] by fingerprint. */
    private fun twins(a: List<Track>, b: List<Track>, kind: Kind = Kind.SAME_RECORDING) =
        a.zip(b).map { (x, y) -> Edge(x.id, y.id, kind) }

    @Test
    fun `a CD rip beats its upsampled twin, for being smaller`() {
        val cd = (1..10).map { track("/Music/A/Album", it, size = 25_000_000) }
        val fake = (1..10).map { track("/Music/A/Album [24-96]", it, rate = 96000, bits = 24, size = 90_000_000, issues = setOf(Issue.UPSAMPLED)) }
        val groups = Duplicates.group(cd + fake, twins(cd, fake))
        assertEquals(1, groups.size)
        val g = groups[0]
        assertTrue(g.albums)
        assertEquals("/Music/A/Album", g.keep.folder)
        assertEquals(900_000_000L, g.reclaimable)
        assertEquals("Same sound, 3.6× smaller", g.reason)
    }

    @Test
    fun `genuine hi-res beats the CD`() {
        val cd = (1..10).map { track("/Music/A/CD", it, size = 25_000_000) }
        val hires = (1..10).map { track("/Music/A/HiRes", it, rate = 96000, bits = 24, size = 90_000_000) }
        val g = Duplicates.group(cd + hires, twins(cd, hires)).single()
        assertEquals("/Music/A/HiRes", g.keep.folder)
        assertTrue(g.reason, g.reason.startsWith("Holds more"))
    }

    @Test
    fun `lossless beats MP3, and a FLAC made from MP3 loses to a real one`() {
        val mp3 = (1..8).map { track("/Music/B/mp3", it, format = "MP3", bitrate = 320, size = 9_000_000) }
        val flac = (1..8).map { track("/Music/B/flac", it) }
        assertEquals("/Music/B/flac", Duplicates.group(mp3 + flac, twins(mp3, flac)).single().keep.folder)

        val fakeFlac = (1..8).map { track("/Music/C/fake", it, issues = setOf(Issue.LOSSY_SOURCE)) }
        val realFlac = (1..8).map { track("/Music/C/real", it) }
        val g = Duplicates.group(fakeFlac + realFlac, twins(fakeFlac, realFlac)).single()
        assertEquals("/Music/C/real", g.keep.folder)
        assertEquals("Real lossless; the other came from a lossy file", g.reason)
    }

    @Test
    fun `damage outranks resolution`() {
        val hires = (1..6).map { track("/M/hires", it, rate = 96000, bits = 24, issues = if (it == 3) setOf(Issue.DAMAGED) else emptySet()) }
        val cd = (1..6).map { track("/M/cd", it) }
        assertEquals("/M/cd", Duplicates.group(hires + cd, twins(hires, cd)).single().keep.folder)
    }

    @Test
    fun `the complete copy of an album wins over a partial one`() {
        val full = (1..12).map { track("/M/full", it) }
        val part = (1..10).map { track("/M/part", it) }
        val g = Duplicates.group(full + part, twins(full.take(10), part)).single()
        assertTrue(g.albums)
        assertEquals("/M/full", g.keep.folder)
    }

    @Test
    fun `a song also on a compilation is another release, not a duplicate`() {
        val album = (1..10).map { track("/M/Artist/Album", it) }
        val comp = (1..20).map { track("/M/Various/Hits", it, album = "Hits", artist = "Someone $it") }
        val edges = listOf(Edge(album[2].id, comp[7].id, Kind.SAME_RECORDING))
        val g = Duplicates.group(album + comp, edges).single()
        assertFalse(g.albums)
        assertTrue(g.otherRelease)
        assertEquals(0L, g.reclaimable)
    }

    @Test
    fun `a stray copy inside one album prefers the clean name`() {
        val a = track("/M/X", 1)
        val copy = a.copy(id = 999, path = "/M/X/1 (1).flac")
        val g = Duplicates.group(listOf(a, copy), listOf(Edge(a.id, copy.id, Kind.IDENTICAL))).single()
        assertFalse(g.otherRelease)
        assertEquals("/M/X/1.flac", g.keep.path)
        assertEquals("Identical; keeping the tidier path", g.reason)
    }

    @Test
    fun `a stray copy inside an album that is itself duplicated still gets its own group`() {
        val cd = (1..3).map { track("/M/H/Album", it) }
        val stray = cd[0].copy(id = 500, path = "/M/H/Album/1 (1).flac")
        val hires = (1..3).map { track("/M/H/Album [24-96]", it, rate = 96000, bits = 24, issues = setOf(Issue.UPSAMPLED)) }
        val edges = twins(cd, hires) + Edge(cd[0].id, stray.id, Kind.IDENTICAL) + Edge(stray.id, hires[0].id, Kind.SAME_RECORDING)
        val groups = Duplicates.group(cd + stray + hires, edges)
        val albums = groups.single { it.albums }
        assertEquals("/M/H/Album", albums.keep.folder)
        // The stray copy does not make the CD folder "more complete".
        assertFalse(albums.reason, albums.reason.startsWith("More complete"))
        val stray1 = groups.single { !it.albums }
        assertFalse(stray1.otherRelease)
        assertEquals(Kind.IDENTICAL, stray1.kind)
        assertEquals("/M/H/Album/1.flac", stray1.keep.path)
    }

    @Test
    fun `edges come from hashes, audio MD5 and titles`() {
        val a = track("/M/A", 1).copy(audioMd5 = "abc")
        val b = track("/M/B", 1).copy(audioMd5 = "abc")
        val c = track("/M/C", 2, title = "Song 1", duration = a.durationMs + 1500)
        val d = track("/M/D", 5, title = "Other")
        val edges = Duplicates.edges(listOf(a, b, c, d), emptyMap(), emptyMap())
        assertTrue(edges.any { setOf(it.a, it.b) == setOf(a.id, b.id) && it.kind == Kind.SAME_AUDIO })
        assertTrue(edges.any { setOf(it.a, it.b) == setOf(a.id, c.id) && it.kind == Kind.SAME_TITLE })
        assertFalse(edges.any { d.id == it.a || d.id == it.b })
    }

    @Test
    fun `doctor finds what players stumble on`() {
        val noCover = Album("/M/NoCover", (1..3).map { track("/M/NoCover", it, pictures = 0, pictureBytes = 0) })
        val coverFile = Album("/M/CoverFile", (1..3).map { track("/M/CoverFile", it, pictures = 0, pictureBytes = 0) })
        val heavy = Album("/M/Heavy", (1..3).map { track("/M/Heavy", it, pictureBytes = 4_000_000) })
        val split = Album("/M/Split", (1..4).map { track("/M/Split", it, album = if (it == 4) "Album " else "Album") })
        val various = Album("/M/Various", (1..4).map { track("/M/Various", it, artist = "Artist $it") })
        val gaps = Album("/M/Gaps", listOf(1, 2, 4, 5).map { track("/M/Gaps", it, total = 6) })
        val clean = Album("/M/Clean", (1..3).map { track("/M/Clean", it, total = 3, albumArtist = "Artist") })
        val found = Doctor.examine(listOf(noCover, coverFile, heavy, split, various, gaps, clean), setOf("/M/CoverFile"))
            .groupBy({ it.album.folder }, { it.problem })
        assertEquals(listOf(Problem.NO_COVER), found["/M/NoCover"]?.minus(Problem.INCOMPLETE))
        assertEquals(null, found["/M/CoverFile"]?.filter { it == Problem.NO_COVER }?.ifEmpty { null })
        assertTrue(Problem.HEAVY_COVER in found["/M/Heavy"]!!)
        assertTrue(Problem.SPLIT in found["/M/Split"]!!)
        assertTrue(Problem.SPLIT in found["/M/Various"]!!)
        assertTrue(Problem.INCOMPLETE in found["/M/Gaps"]!!)
        assertEquals(null, found["/M/Clean"])
    }
}
