package com.songaudit.fix

import com.songaudit.library.Album
import com.songaudit.library.Problem
import com.songaudit.library.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerTest {

    private var id = 0L

    private fun track(
        folder: String,
        name: String,
        title: String? = null,
        artist: String? = null,
        album: String? = null,
        albumArtist: String? = null,
        number: Int = 0,
    ) = Track(
        id = ++id, path = "$folder/$name", folder = folder, size = 1, modified = 0, format = "FLAC", lossless = true,
        sampleRate = 44100, bits = 16, channels = 2, durationMs = 1000, bitrate = 0,
        title = title, artist = artist, album = album, albumArtist = albumArtist, track = number,
    )

    @Test
    fun `a compilation gets various artists and the compilation flag`() {
        val a = Album("/M/Night Drive", (1..3).map { track("/M/Night Drive", "0$it.flac", "T$it", listOf("Halden", "Iver", "Lune")[it - 1], "Night Drive", number = it) })
        val plan = Planner.tags(a, setOf(Problem.SPLIT))
        assertEquals(3, plan.edits.size)
        assertTrue(plan.edits.values.all { it[TagEdit.ALBUM_ARTIST] == Planner.VARIOUS && it[TagEdit.COMPILATION] == "1" })
        assertTrue(plan.lines.first(), plan.lines.first().startsWith("Album artist → “Various Artists” · all 3 tracks"))
    }

    @Test
    fun `guests do not make an album a compilation`() {
        val artists = listOf("Morrow", "Morrow feat. Lune", "Morrow", "Morrow (feat. Iver)")
        val tracks = artists.mapIndexed { i, a -> track("/M/X", "$i.flac", "T", a, "X", number = i + 1) }
        assertEquals("Morrow", Planner.albumArtist(tracks))
        assertEquals("Simon & Garfunkel", Planner.lead("Simon & Garfunkel"))
    }

    @Test
    fun `two spellings of one album become the common one`() {
        val tracks = (1..4).map { track("/M/A", "$it.flac", "T", "A", if (it == 4) "Big sky" else "Big Sky", "A", it) }
        val plan = Planner.tags(Album("/M/A", tracks), setOf(Problem.SPLIT))
        assertEquals(1, plan.edits.size)
        assertEquals("Big Sky", plan.edits.values.single()[TagEdit.ALBUM])
    }

    @Test
    fun `missing tags come from file and folder names, and nothing is invented`() {
        val folder = "/storage/emulated/0/Music/Iver/Quiet Rooms (2021) [FLAC]"
        val tracks = listOf(
            track(folder, "01 - Stairwell.flac"),
            track(folder, "02. Iver - Lantern.flac", artist = "Iver"),
            track(folder, "Harbour.flac"),
        )
        val plan = Planner.tags(Album(folder, tracks), setOf(Problem.MISSING_TAGS))
        val e = tracks.map { plan.edits[it.id].orEmpty() }
        assertEquals("Stairwell", e[0][TagEdit.TITLE])
        assertEquals("1", e[0][TagEdit.TRACK])
        assertEquals("Lantern", e[1][TagEdit.TITLE])
        assertEquals("2", e[1][TagEdit.TRACK])
        assertEquals("Harbour", e[2][TagEdit.TITLE])
        assertNull(e[2][TagEdit.TRACK])
        assertTrue(e.all { it[TagEdit.ALBUM] == "Quiet Rooms" })
        assertEquals("Iver", e[0][TagEdit.ARTIST])
        assertTrue(plan.unknown.single(), plan.unknown.single().startsWith("No track number"))
    }

    @Test
    fun `file and folder names as people write them`() {
        fun f(name: String, artist: String? = null) = FileName.parse(name, artist).let { it.number to it.title }
        assertEquals(1 to "Northern Line", f("01 Northern Line.flac"))
        assertEquals(3 to "Low Tide", f("1-03 Low Tide.flac"))
        assertEquals(7 to "Arrow", f("107 Arrow.mp3"))
        assertEquals(4 to "Neon", f("Kestrel - 04 - Neon.flac"))
        assertEquals(12 to "Big Sky", f("12_Big_Sky.flac"))
        assertEquals(0 to "Glass House", f("Glass House.flac"))
        assertEquals(2 to "Paper Moon", f("02 - Halden - Paper Moon.flac", "Halden"))

        fun d(path: String) = FolderName.parse(path).let { it.artist to it.album }
        assertEquals("Halden" to "Northern Line", d("/sdcard/Music/Halden/Northern Line (2019)"))
        assertEquals("Halden" to "Northern Line", d("/sdcard/Music/Halden - 2019 - Northern Line [24-96]"))
        assertEquals("Lune" to "Big Sky", d("/sdcard/Music/Lune/Big Sky/CD1"))
        assertEquals(null to "Loose", d("/storage/emulated/0/Music/Loose"))
        assertEquals(null to "Album", d("/storage/1A2B-3C4D/Album"))
    }
}
