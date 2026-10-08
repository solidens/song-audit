package com.songaudit.fix

import com.songaudit.audio.FormatException
import com.songaudit.audio.Source
import com.songaudit.audio.u16be
import com.songaudit.audio.u32be
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * The ID3v2 tag at the start of an MP3, frame by frame. Frames this app does
 * not change are written back byte for byte, in their own version's layout;
 * a file without a tag gets a new ID3v2.3 one, which every player reads.
 */
class Id3Head private constructor(
    /** 3 or 4; 0 when the file has no tag yet. */
    private val version: Int,
    private val frames: List<Frame>,
    override val length: Long,
) : Head {

    class Frame(val id: String, val flags: Int, val body: ByteArray)

    override val pictures: List<Pic> get() = frames.filter { it.id == "APIC" && plain(it) }.mapNotNull { parsePicture(it.body) }

    /** A v2.4 frame that is compressed, encrypted or unsynchronised on its own is kept whole and not read. */
    private fun plain(f: Frame) = when (version) {
        3 -> f.flags and 0x00C0 == 0
        4 -> f.flags and 0x000F == 0
        else -> true
    }

    override fun build(edit: TagEdit, fit: Long): ByteArray {
        val v = if (version == 0) 3 else version
        val out = frames.toMutableList()

        fun put(id: String, text: String?, drop: (Frame) -> Boolean = { false }) {
            val matches = out.indices.filter { out[it].id == id || drop(out[it]) }
            val frame = text?.let { Frame(id, 0, textBody(it, v)) }
            if (matches.isEmpty()) {
                if (frame != null) out += frame
            } else {
                for (i in matches.drop(1).reversed()) out.removeAt(i)
                if (frame == null) out.removeAt(matches[0]) else out[matches[0]] = frame
            }
        }
        fun txxx(names: List<String>): (Frame) -> Boolean = { f ->
            f.id == "TXXX" && userKey(f.body)?.uppercase()?.let { it in names } == true
        }

        val fields = edit.fields
        for ((field, value) in fields) {
            when (field) {
                TagEdit.TITLE -> put("TIT2", value)
                TagEdit.ARTIST -> put("TPE1", value)
                TagEdit.ALBUM -> put("TALB", value)
                TagEdit.ALBUM_ARTIST -> put("TPE2", value, txxx(TagEdit.spellings(TagEdit.ALBUM_ARTIST)))
                TagEdit.COMPILATION -> put("TCMP", value)
            }
        }
        // ID3 keeps the track total in the same frame as the number: "3/12".
        if (TagEdit.TRACK in fields || TagEdit.TRACK_TOTAL in fields) {
            val old = frames.firstOrNull { it.id == "TRCK" }?.let { readText(it.body) }.orEmpty()
            val number = if (TagEdit.TRACK in fields) fields[TagEdit.TRACK] else old.substringBefore('/').trim().ifEmpty { null }
            val total = if (TagEdit.TRACK_TOTAL in fields) fields[TagEdit.TRACK_TOTAL] else old.substringAfter('/', "").trim().ifEmpty { null }
            put("TRCK", number?.let { if (total != null) "$it/$total" else it }, txxx(TagEdit.spellings(TagEdit.TRACK_TOTAL)))
        }

        edit.pictures?.let { transform ->
            val first = out.indexOfFirst { it.id == "APIC" && plain(it) }
            val fresh = transform(pictures).map { Frame("APIC", 0, pictureBody(it)) }
            out.removeAll { it.id == "APIC" && plain(it) }
            out.addAll(if (first < 0) out.size else first.coerceAtMost(out.size), fresh)
        }

        val body = ByteArrayOutputStream()
        for (f in out) {
            body.write(f.id.toByteArray(Charsets.ISO_8859_1))
            val n = f.body.size
            if (v == 4) body.write(syncsafe(n)) else body.write(be32(n))
            body.write(f.flags ushr 8)
            body.write(f.flags)
            body.write(f.body)
        }
        val natural = 10L + body.size()
        val spare = fit - natural
        // A file with no tag has no room to fill: it is always rewritten.
        val padding = if (fit > 0 && spare in 0..FlacHead.RECLAIM) spare.toInt() else FlacHead.ROOM
        val size = body.size() + padding
        if (size >= 1 shl 28) throw WriteException("tag too large for ID3v2")
        val tag = ByteArrayOutputStream(10 + size)
        tag.write("ID3".toByteArray(Charsets.ISO_8859_1))
        tag.write(v)
        tag.write(0)
        tag.write(0) // no unsynchronisation, extended header or footer
        tag.write(syncsafe(size))
        body.writeTo(tag)
        tag.write(ByteArray(padding))
        return tag.toByteArray()
    }

    companion object {

        fun read(file: File): Id3Head = Source(file).use { src ->
            if (src.length < 10) return Id3Head(0, emptyList(), 0)
            val head = src.bytes(10)
            if (String(head, 0, 3, Charsets.ISO_8859_1) != "ID3") return Id3Head(0, emptyList(), 0)
            val version = head[3].toInt()
            val flags = head[5].toInt()
            val size = unsyncsafe(head, 6)
            val end = 10L + size + if (flags and 0x10 != 0) 10 else 0
            if (version == 2) throw WriteException("ID3v2.2 tags are not written")
            if (version !in 3..4) throw FormatException("unknown ID3v2 version $version")
            var body = src.bytes(size)
            if (flags and 0x80 != 0 && version == 3) body = unsync(body)
            var at = 0
            if (flags and 0x40 != 0) at = if (version == 4) unsyncsafe(body, 0) else body.u32be(0).toInt() + 4
            val frames = ArrayList<Frame>()
            while (at + 10 <= body.size) {
                val id = String(body, at, 4, Charsets.ISO_8859_1)
                if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
                val n = if (version == 4) unsyncsafe(body, at + 4) else body.u32be(at + 4).toInt()
                if (n < 0 || at + 10 + n > body.size) throw FormatException("frame $id runs past the tag")
                frames += Frame(id, body.u16be(at + 8), body.copyOfRange(at + 10, at + 10 + n))
                at += 10 + n
            }
            Id3Head(version, frames, end)
        }

        private fun textBody(text: String, version: Int): ByteArray {
            val latin = text.all { it.code < 0x100 }
            val out = ByteArrayOutputStream()
            when {
                latin -> {
                    out.write(0)
                    out.write(text.toByteArray(Charsets.ISO_8859_1))
                }
                version == 4 -> {
                    out.write(3)
                    out.write(text.toByteArray(Charsets.UTF_8))
                }
                else -> {
                    out.write(1)
                    out.write(0xFF)
                    out.write(0xFE)
                    out.write(text.toByteArray(Charsets.UTF_16LE))
                }
            }
            return out.toByteArray()
        }

        /** The first value of a text frame. */
        fun readText(body: ByteArray): String {
            if (body.isEmpty()) return ""
            val (charset, width) = encoding(body[0].toInt())
            val end = terminator(body, 1, width).let { if (it < 0) body.size else it }
            return String(body, 1, end - 1, charset).trim()
        }

        private fun userKey(body: ByteArray): String? {
            if (body.isEmpty()) return null
            val (charset, width) = encoding(body[0].toInt())
            val end = terminator(body, 1, width)
            return if (end < 0) null else String(body, 1, end - 1, charset)
        }

        private fun encoding(code: Int) = when (code) {
            1 -> Charsets.UTF_16 to 2
            2 -> Charsets.UTF_16BE to 2
            3 -> Charsets.UTF_8 to 1
            else -> Charsets.ISO_8859_1 to 1
        }

        private fun terminator(b: ByteArray, from: Int, width: Int): Int {
            var i = from
            while (i + width <= b.size) {
                if (b[i] == 0.toByte() && (width == 1 || b[i + 1] == 0.toByte())) return i
                i += width
            }
            return -1
        }

        fun parsePicture(b: ByteArray): Pic? {
            if (b.size < 4) return null
            val (charset, width) = encoding(b[0].toInt())
            val mimeEnd = terminator(b, 1, 1)
            if (mimeEnd < 0 || mimeEnd + 2 > b.size) return null
            val mime = String(b, 1, mimeEnd - 1, Charsets.ISO_8859_1)
            val type = b[mimeEnd + 1].toInt() and 0xFF
            val descStart = mimeEnd + 2
            val descEnd = terminator(b, descStart, width)
            if (descEnd < 0) return null
            val desc = String(b, descStart, descEnd - descStart, charset)
            val data = b.copyOfRange(descEnd + width, b.size)
            return Pic(type, mime.ifEmpty { "image/jpeg" }, desc, 0, 0, data)
        }

        private fun pictureBody(p: Pic): ByteArray {
            val out = ByteArrayOutputStream(p.data.size + 32)
            out.write(0)
            out.write(p.mime.toByteArray(Charsets.ISO_8859_1))
            out.write(0)
            out.write(p.type)
            if (p.description.all { it.code in 1..0xFF }) out.write(p.description.toByteArray(Charsets.ISO_8859_1))
            out.write(0)
            out.write(p.data)
            return out.toByteArray()
        }

        private fun be32(n: Int) = byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte())

        private fun syncsafe(n: Int) = byteArrayOf(
            ((n ushr 21) and 0x7F).toByte(), ((n ushr 14) and 0x7F).toByte(),
            ((n ushr 7) and 0x7F).toByte(), (n and 0x7F).toByte(),
        )

        private fun unsyncsafe(b: ByteArray, at: Int): Int =
            ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or
                ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)

        private fun unsync(b: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(b.size)
            var i = 0
            while (i < b.size) {
                out.write(b[i].toInt())
                if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1] == 0.toByte()) i++
                i++
            }
            return out.toByteArray()
        }
    }
}
