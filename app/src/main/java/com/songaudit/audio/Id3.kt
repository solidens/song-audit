package com.songaudit.audio

import java.nio.charset.Charset

/**
 * ID3v2.2, 2.3 and 2.4: the text frames the audit cares about and the size
 * of every attached picture. Picture data is seeked past, never read.
 */
object Id3 {

    class Result(val tags: Tags, val pictures: List<Picture>, val end: Long)

    /** Where the stream continues after a leading ID3v2 tag, if there is one. */
    fun skip(src: Source): Long {
        val start = src.position
        if (src.length - start < 10) return start
        val head = src.bytes(10)
        src.position = start
        if (head.ascii(0, 3) != "ID3") return start
        val footer = if (head[5].toInt() and 0x10 != 0) 10 else 0
        return start + 10 + syncsafe(head, 6) + footer
    }

    /** Reads a tag starting at the current position, or null if there is none. */
    fun read(src: Source, tags: Tags = Tags()): Result? {
        val start = src.position
        if (src.length - start < 10) return null
        val head = src.bytes(10)
        if (head.ascii(0, 3) != "ID3") {
            src.position = start
            return null
        }
        val version = head[3].toInt()
        val flags = head[5].toInt()
        val size = syncsafe(head, 6)
        val end = start + 10 + size + if (flags and 0x10 != 0) 10 else 0
        if (version !in 2..4) return Result(tags, emptyList(), end)

        // Whole-tag unsynchronisation predates 2.4 and is rare enough to read
        // the tag into memory and undo it there; pictures then cost memory, so
        // only their sizes are kept.
        val pictures = ArrayList<Picture>()
        if (flags and 0x80 != 0 && version < 4) {
            val body = unsync(src.upTo(size))
            frames(ByteSource(body), version, tags, pictures, null)
        } else {
            if (flags and 0x40 != 0 && version >= 3) {
                val ext = if (version == 4) syncsafe(src.bytes(4), 0) else src.u32be().toInt() + 4
                src.position = start + 10 + ext
            }
            frames(FileFrames(src, start + 10 + size), version, tags, pictures, src)
        }
        src.position = end
        return Result(tags, pictures, end)
    }

    /** Frames are read through this so the in-memory (unsynchronised) and on-disk paths share a parser. */
    private interface Frames {
        val position: Long
        val limit: Long
        fun bytes(n: Int): ByteArray
        fun skip(n: Long)
    }

    private class FileFrames(val src: Source, override val limit: Long) : Frames {
        override val position get() = src.position
        override fun bytes(n: Int) = src.bytes(n)
        override fun skip(n: Long) = src.skip(n)
    }

    private class ByteSource(val b: ByteArray) : Frames {
        var at = 0
        override val position get() = at.toLong()
        override val limit get() = b.size.toLong()
        override fun bytes(n: Int) = b.copyOfRange(at, at + n).also { at += n }
        override fun skip(n: Long) {
            at += n.toInt()
        }
    }

    private fun frames(f: Frames, version: Int, tags: Tags, pictures: MutableList<Picture>, file: Source?) {
        val idLength = if (version == 2) 3 else 4
        val headerLength = if (version == 2) 6 else 10
        while (f.position + headerLength <= f.limit) {
            val h = f.bytes(headerLength)
            if (h[0] == 0.toByte()) return // padding
            val id = h.ascii(0, idLength)
            val size = when (version) {
                2 -> ((h[3].toInt() and 0xFF) shl 16) or h.u16be(4)
                3 -> h.u32be(4).toInt()
                else -> syncsafe(h, 4)
            }
            if (size <= 0 || f.position + size > f.limit) return
            val frameFlags = if (version == 2) 0 else h.u16be(8)
            // Compressed or encrypted frames are skipped rather than half-read.
            val opaque = when (version) {
                3 -> frameFlags and 0x00C0 != 0
                4 -> frameFlags and 0x000C != 0
                else -> false
            }
            val bodyStart = f.position
            if (!opaque) {
                when {
                    id == "APIC" || id == "PIC" -> picture(f, size, version, file)?.let(pictures::add)
                    id == "TXXX" || id == "TXX" -> {
                        val b = f.bytes(size)
                        val parts = strings(b, 1, b[0].toInt())
                        if (parts.size >= 2) tags.set(parts[0], parts[1])
                    }
                    id[0] == 'T' -> KEYS[id]?.let { key ->
                        val b = f.bytes(size)
                        val text = strings(b, 1, b[0].toInt()).joinToString("; ")
                        if (key == "GENRE") tags.set(key, genre(text)) else tags.set(key, text)
                    }
                }
            }
            f.skip(bodyStart + size - f.position)
        }
    }

    private fun picture(f: Frames, size: Int, version: Int, file: Source?): Picture? {
        val head = f.bytes(minOf(size, 512))
        val encoding = head[0].toInt()
        var at = 1
        val mime: String
        if (version == 2) {
            mime = "image/" + head.ascii(1, 3).lowercase()
            at = 4
        } else {
            val zero = head.indexOfZero(at, 1)
            if (zero < 0) return null
            mime = head.ascii(at, zero - at)
            at = zero + 1
        }
        val type = head[at].toInt() and 0xFF
        at++
        val descEnd = head.indexOfZero(at, if (encoding == 1 || encoding == 2) 2 else 1)
        if (descEnd < 0) return null
        at = descEnd + if (encoding == 1 || encoding == 2) 2 else 1
        val bytes = size - at
        val dataStart = f.position - head.size + at
        val (w, h) = if (file != null) ImageSize.read(file, dataStart, bytes) else 0 to 0
        return Picture(type, mime, w, h, bytes)
    }

    private fun ByteArray.indexOfZero(from: Int, width: Int): Int {
        var i = from
        while (i + width <= size) {
            if (width == 1 && this[i] == 0.toByte()) return i
            if (width == 2 && this[i] == 0.toByte() && this[i + 1] == 0.toByte()) return i
            i += width
        }
        return -1
    }

    /** A text frame's value or values, split on the encoding's own terminator. */
    private fun strings(b: ByteArray, from: Int, encoding: Int): List<String> {
        if (from >= b.size) return emptyList()
        val charset: Charset = when (encoding) {
            1 -> Charsets.UTF_16
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        val width = if (encoding == 1 || encoding == 2) 2 else 1
        val out = ArrayList<String>()
        var start = from
        var i = from
        while (i + width <= b.size) {
            val zero = if (width == 1) b[i] == 0.toByte() else b[i] == 0.toByte() && b[i + 1] == 0.toByte()
            if (zero) {
                out += String(b, start, i - start, charset)
                start = i + width
            }
            i += width
        }
        if (start < b.size) out += String(b, start, b.size - start, charset)
        // UTF-16 values after the first each carry their own BOM, which the decoder handles.
        return out.filter { it.isNotEmpty() }
    }

    /** "(17)" and "17" are ID3v1 genre numbers; the audit only needs to know there is a genre. */
    private fun genre(text: String): String = text.trim()

    private fun syncsafe(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or
            ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)

    private fun unsync(b: ByteArray): ByteArray {
        val out = java.io.ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            out.write(b[i].toInt())
            if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1] == 0.toByte()) i++
            i++
        }
        return out.toByteArray()
    }

    private val KEYS = mapOf(
        "TIT2" to "TITLE", "TT2" to "TITLE",
        "TPE1" to "ARTIST", "TP1" to "ARTIST",
        "TPE2" to "ALBUMARTIST", "TP2" to "ALBUMARTIST",
        "TALB" to "ALBUM", "TAL" to "ALBUM",
        "TRCK" to "TRACKNUMBER", "TRK" to "TRACKNUMBER",
        "TPOS" to "DISCNUMBER", "TPA" to "DISCNUMBER",
        "TDRC" to "DATE", "TYER" to "DATE", "TYE" to "DATE", "TDOR" to "DATE",
        "TCON" to "GENRE", "TCO" to "GENRE",
        "TCMP" to "COMPILATION", "TCP" to "COMPILATION",
    )
}
