package com.songaudit.fix

import com.songaudit.Fixtures
import com.songaudit.audio.Deep
import com.songaudit.audio.FlacReader
import com.songaudit.audio.Probe
import com.songaudit.audio.Source
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TagWriteTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun copy(name: String): File = Fixtures.file(name).copyTo(File(tmp.root, name))

    /** Pictures are opaque bytes to the writers; these pretend to know their sizes. */
    private val images = object : Images {
        override fun size(data: ByteArray) = if (data.size > 4) (data[2].toInt() and 0xFF) * 10 to (data[3].toInt() and 0xFF) * 10 else 0 to 0
        override fun fit(data: ByteArray, max: Int) = fakeJpeg(1000, 100)
    }

    private fun fakeJpeg(bytes: Int, px: Int) = ByteArray(bytes) { i ->
        when (i) {
            0 -> 0xFF.toByte()
            1 -> 0xD8.toByte()
            2, 3 -> (px / 10).toByte()
            else -> (i * 31).toByte()
        }
    }

    private fun audioOf(file: File): ByteArray {
        val head = Rewrite.head(file)
        return file.readBytes().copyOfRange(head.length.toInt(), file.length().toInt())
    }

    @Test
    fun `flac tags are rewritten in place and the audio is untouched`() {
        val file = copy("cd.flac")
        val before = file.length()
        val audio = audioOf(file)
        Rewrite.apply(
            file,
            TagEdit(mapOf(TagEdit.ALBUM to "Fixtures (Deluxe)", TagEdit.ALBUM_ARTIST to "Разные исполнители", TagEdit.TRACK_TOTAL to "9")),
        )
        assertEquals("fits in the padding", before, file.length())
        assertArrayEquals(audio, audioOf(file))
        val tags = Source(file).use { FlacReader.read(it) }.tags
        assertEquals("Fixtures (Deluxe)", tags.album)
        assertEquals("Разные исполнители", tags.albumArtist)
        assertEquals("First", tags.title)
        assertEquals(1, tags.track)
        assertEquals(9, tags.trackTotal)
        assertEquals(1, Deep.flac(file).md5Match)
        ffprobe(file)?.let {
            assertEquals("Fixtures (Deluxe)", it["album"])
            assertEquals("Разные исполнители", it["album_artist"])
        }
    }

    @Test
    fun `a picture too big for the padding rewrites the file, and undo restores every byte`() {
        val file = copy("cd.flac")
        val original = file.readBytes()
        val undo = Rewrite.snapshot(file)
        val cover = fakeJpeg(300_000, 3000)
        Rewrite.apply(file, TagEdit(mapOf(TagEdit.TITLE to "Second"), pictures = { listOf(Pic(Pic.FRONT, "image/jpeg", "", 3000, 3000, cover)) + it }))
        assertTrue(file.length() > original.size + 300_000)
        val meta = Source(file).use { FlacReader.read(it) }
        assertEquals("Second", meta.tags.title)
        assertEquals(1, meta.pictures.size)
        assertEquals(300_000, meta.pictures[0].bytes)
        assertArrayEquals(cover, Rewrite.head(file).pictures[0].data)
        assertEquals(1, Deep.flac(file).md5Match)
        assertTrue("no temp file left", tmp.root.listFiles()!!.none { it.name.startsWith(".") })

        // Shrinking the picture gives the room back rather than padding it.
        Rewrite.apply(file, TagEdit(pictures = { pics -> pics.map { Pic(it.type, it.mime, it.description, 100, 100, images.fit(it.data, 1000)!!) } }))
        assertTrue(file.length() < original.size + 20_000)
        assertEquals(1, Deep.flac(file).md5Match)

        Rewrite.undo(file, undo)
        assertArrayEquals(original, file.readBytes())
    }

    @Test
    fun `undo files survive the round trip`() {
        val file = copy("cd.flac")
        val undo = Rewrite.snapshot(file)
        val saved = File(tmp.root, "q/cd.flac.tags")
        Rewrite.save(undo, saved)
        val back = Rewrite.load(saved)
        assertArrayEquals(undo.head, back.head)
        assertEquals(undo.audioLength, back.audioLength)
    }

    @Test
    fun `mp3 tags are rewritten and the frames after them are untouched`() {
        val file = copy("cd-320.mp3")
        val audio = audioOf(file)
        Rewrite.apply(
            file,
            TagEdit(
                mapOf(
                    TagEdit.TITLE to "Первая", TagEdit.ARTIST to "Synth", TagEdit.ALBUM to "Fixtures",
                    TagEdit.ALBUM_ARTIST to "Synth", TagEdit.TRACK to "2", TagEdit.TRACK_TOTAL to "12", TagEdit.COMPILATION to "1",
                ),
                pictures = { listOf(Pic(Pic.FRONT, "image/jpeg", "", 500, 500, fakeJpeg(20_000, 500))) },
            ),
        )
        assertArrayEquals(audio, audioOf(file))
        val p = Probe.probe(file)!!
        assertEquals("Первая", p.tags.title)
        assertEquals("Fixtures", p.tags.album)
        assertEquals("Synth", p.tags.albumArtist)
        assertEquals(2, p.tags.track)
        assertEquals(12, p.tags.trackTotal)
        assertTrue(p.tags.compilation)
        assertEquals(1, p.pictures.size)
        assertEquals(20_000, p.pictures[0].bytes)
        ffprobe(file)?.let {
            assertEquals("Первая", it["title"])
            assertEquals("2/12", it["track"])
            assertEquals("Synth", it["album_artist"])
        }

        // A second edit keeps what the first wrote and fits in its padding.
        val size = file.length()
        Rewrite.apply(file, TagEdit(mapOf(TagEdit.TRACK_TOTAL to "3")))
        assertEquals(size, file.length())
        val again = Probe.probe(file)!!
        assertEquals(2, again.tags.track)
        assertEquals(3, again.tags.trackTotal)
        assertEquals("Первая", again.tags.title)
        assertEquals(1, again.pictures.size)
    }

    @Test
    fun `an existing id3v2_4 tag keeps its version and its other frames`() {
        val file = copy("cd-128.mp3")
        // ffmpeg writes 2.4 with an encoder frame; add tags through ffmpeg first if it is there.
        assumeTrue(ffmpeg())
        val tagged = File(tmp.root, "tagged.mp3")
        run("ffmpeg", "-v", "error", "-y", "-i", file.path, "-c", "copy", "-metadata", "title=One", "-metadata", "genre=Ambient", "-metadata", "date=2019", tagged.path)
        assertEquals(4, tagged.readBytes()[3].toInt())
        Rewrite.apply(tagged, TagEdit(mapOf(TagEdit.ALBUM to "Ünïcödé — 日本")))
        assertEquals(4, tagged.readBytes()[3].toInt())
        val tags = ffprobe(tagged)!!
        assertEquals("One", tags["title"])
        assertEquals("Ambient", tags["genre"])
        assertEquals("Ünïcödé — 日本", tags["album"])
    }

    @Test
    fun `an mp3 without a tag gets one`() {
        val src = copy("cd-128.mp3")
        val head = Rewrite.head(src)
        val bare = File(tmp.root, "bare.mp3")
        bare.writeBytes(src.readBytes().copyOfRange(head.length.toInt(), src.length().toInt()))
        assertEquals(0L, Rewrite.head(bare).length)
        Rewrite.apply(bare, TagEdit(mapOf(TagEdit.TITLE to "Named", TagEdit.TRACK to "4")))
        val p = Probe.probe(bare)!!
        assertEquals("Named", p.tags.title)
        assertEquals(4, p.tags.track)
        assertEquals(3, bare.readBytes()[3].toInt())
    }

    // -- ffmpeg as a second opinion, when it is installed --------------------------

    private fun ffmpeg(): Boolean = try {
        ProcessBuilder("ffprobe", "-version").redirectErrorStream(true).start().waitFor() == 0
    } catch (e: Exception) {
        false
    }

    private fun run(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
        assertEquals(out, 0, p.waitFor())
        return out
    }

    /** The tags ffprobe reads, lower-cased keys; null when ffprobe is not installed. */
    private fun ffprobe(file: File): Map<String, String>? {
        if (!ffmpeg()) return null
        val out = run("ffprobe", "-v", "error", "-show_entries", "format_tags", "-of", "default=noprint_wrappers=1", file.path)
        return out.lines().filter { it.startsWith("TAG:") }.associate { line ->
            val kv = line.removePrefix("TAG:")
            kv.substringBefore('=').lowercase() to kv.substringAfter('=')
        }
    }
}
