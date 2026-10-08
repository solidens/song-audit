package com.songaudit.fix

import com.songaudit.audio.FormatException
import com.songaudit.audio.Id3
import com.songaudit.audio.Source
import com.songaudit.audio.u32be
import com.songaudit.audio.u32le
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * A FLAC file's metadata blocks, held whole so they can be written back with
 * changes. Audio frames are never touched: a new head either takes the exact
 * room of the old one, the difference made up with PADDING, or the file is
 * copied behind it.
 */
class FlacHead private constructor(
    /** A leading ID3v2 tag, rare in FLAC and kept exactly as it is. */
    private val prefix: ByteArray,
    private val blocks: List<Block>,
    override val length: Long,
) : Head {

    class Block(val type: Int, val data: ByteArray)

    override val pictures: List<Pic> get() = blocks.filter { it.type == PICTURE }.mapNotNull { parsePicture(it.data) }

    /** The 34 bytes of STREAMINFO: what the audio is, which a tag edit must never change. */
    val streamInfo: ByteArray get() = blocks.first { it.type == STREAMINFO }.data

    override fun build(edit: TagEdit, fit: Long): ByteArray {
        val out = ArrayList<Block>()
        var comments: Block? = blocks.firstOrNull { it.type == COMMENTS }
        if (comments != null || edit.fields.isNotEmpty()) {
            comments = Block(COMMENTS, editComments(comments?.data, edit.fields))
        }
        val newPictures = edit.pictures?.let { transform ->
            transform(pictures).map { Block(PICTURE, writePicture(it)) }
        }
        var commentsPlaced = false
        var picturesPlaced = newPictures == null
        for (b in blocks) {
            when (b.type) {
                PADDING -> Unit
                COMMENTS -> if (!commentsPlaced) {
                    out += comments!!
                    commentsPlaced = true
                }
                PICTURE -> if (newPictures == null) {
                    out += b
                } else if (!picturesPlaced) {
                    out += newPictures
                    picturesPlaced = true
                }
                else -> out += b
            }
            // A file without comments gets them straight after STREAMINFO.
            if (b.type == STREAMINFO && comments != null && blocks.none { it.type == COMMENTS }) {
                out += comments
                commentsPlaced = true
            }
        }
        if (!picturesPlaced) out += newPictures!!

        val natural = prefix.size + 4L + out.sumOf { 4L + it.data.size }
        val spare = fit - natural
        val padding = when {
            spare == 0L -> -1
            spare in 4..RECLAIM -> (spare - 4).toInt()
            else -> ROOM
        }
        val bytes = ByteArrayOutputStream((natural + ROOM + 4).toInt())
        bytes.write(prefix)
        bytes.write("fLaC".toByteArray(Charsets.ISO_8859_1))
        val all = if (padding >= 0) out + Block(PADDING, ByteArray(padding)) else out
        for ((i, b) in all.withIndex()) {
            if (b.data.size >= 1 shl 24) throw WriteException("a metadata block is too large for FLAC")
            bytes.write((if (i == all.lastIndex) 0x80 else 0) or b.type)
            bytes.write(b.data.size ushr 16)
            bytes.write(b.data.size ushr 8)
            bytes.write(b.data.size)
            bytes.write(b.data)
        }
        return bytes.toByteArray()
    }

    companion object {
        const val STREAMINFO = 0
        const val PADDING = 1
        const val SEEKTABLE = 3
        const val COMMENTS = 4
        const val CUESHEET = 5
        const val PICTURE = 6

        /** Up to this much room left by a smaller head is kept as padding; more is given back by a rewrite. */
        const val RECLAIM = 256L * 1024

        /** Room left after a rewrite, so the next small edit fits in place. FLAC's own default. */
        const val ROOM = 8192

        private const val MAX_HEAD = 64L shl 20

        fun read(file: File): FlacHead = Source(file).use { src ->
            val start = Id3.skip(src)
            val prefix = src.bytes(start.toInt())
            if (src.ascii(4) != "fLaC") throw FormatException("not a FLAC stream")
            val blocks = ArrayList<Block>()
            while (true) {
                val header = src.u8()
                val length = src.u24be()
                if (src.position + length > MAX_HEAD) throw WriteException("metadata larger than 64 MB")
                blocks += Block(header and 0x7F, src.bytes(length))
                if (header and 0x80 != 0) break
            }
            if (blocks.firstOrNull()?.type != STREAMINFO) throw FormatException("no STREAMINFO")
            FlacHead(prefix, blocks, src.position)
        }

        /** The blocks of a file, for writing a new file around new audio. */
        fun blocksOf(file: File): List<Block> = read(file).blocks

        fun editComments(data: ByteArray?, fields: Map<String, String?>): ByteArray {
            var vendor = "SONG AUDIT"
            val entries = ArrayList<String>()
            if (data != null) {
                var at = 0
                val vendorLength = data.u32le(at).toInt()
                at += 4
                vendor = String(data, at, vendorLength, Charsets.UTF_8)
                at += vendorLength
                val count = data.u32le(at).toInt()
                at += 4
                repeat(count) {
                    if (at + 4 > data.size) return@repeat
                    val n = data.u32le(at).toInt()
                    at += 4
                    if (n < 0 || at + n > data.size) return@repeat
                    entries += String(data, at, n, Charsets.UTF_8)
                    at += n
                }
            }
            fun key(entry: String) = entry.substringBefore('=').uppercase()
            for ((field, value) in fields) {
                val spellings = TagEdit.spellings(field)
                val matches = entries.indices.filter { key(entries[it]) in spellings }
                val entry = value?.let { "$field=$it" }
                if (matches.isEmpty()) {
                    if (entry != null) entries += entry
                } else {
                    // The new value takes the first one's place; any other spelling goes.
                    for (i in matches.drop(1).reversed()) entries.removeAt(i)
                    if (entry == null) entries.removeAt(matches[0]) else entries[matches[0]] = entry
                }
            }
            // "3/12" in TRACKNUMBER would be read before a new TRACKTOTAL: the total lives in one place.
            if (TagEdit.TRACK_TOTAL in fields && TagEdit.TRACK !in fields) {
                for (i in entries.indices) {
                    if (key(entries[i]) == TagEdit.TRACK && '/' in entries[i]) {
                        entries[i] = entries[i].substringBefore('/')
                    }
                }
            }
            val out = ByteArrayOutputStream()
            fun le32(v: Int) {
                out.write(v)
                out.write(v ushr 8)
                out.write(v ushr 16)
                out.write(v ushr 24)
            }
            val v = vendor.toByteArray(Charsets.UTF_8)
            le32(v.size)
            out.write(v)
            le32(entries.size)
            for (e in entries) {
                val b = e.toByteArray(Charsets.UTF_8)
                le32(b.size)
                out.write(b)
            }
            return out.toByteArray()
        }

        fun parsePicture(b: ByteArray): Pic? = try {
            var at = 0
            fun u32(): Int = b.u32be(at).toInt().also { at += 4 }
            val type = u32()
            val mimeLength = u32()
            val mime = String(b, at, mimeLength, Charsets.ISO_8859_1)
            at += mimeLength
            val descLength = u32()
            val desc = String(b, at, descLength, Charsets.UTF_8)
            at += descLength
            val width = u32()
            val height = u32()
            u32()
            u32()
            val n = u32()
            Pic(type, mime, desc, width, height, b.copyOfRange(at, at + n))
        } catch (e: IndexOutOfBoundsException) {
            null
        }

        fun writePicture(p: Pic): ByteArray {
            val out = ByteArrayOutputStream(p.data.size + 64)
            fun be32(v: Int) {
                out.write(v ushr 24)
                out.write(v ushr 16)
                out.write(v ushr 8)
                out.write(v)
            }
            val mime = p.mime.toByteArray(Charsets.ISO_8859_1)
            val desc = p.description.toByteArray(Charsets.UTF_8)
            be32(p.type)
            be32(mime.size)
            out.write(mime)
            be32(desc.size)
            out.write(desc)
            be32(p.width)
            be32(p.height)
            be32(if (p.mime == "image/png") 32 else 24)
            be32(0)
            be32(p.data.size)
            out.write(p.data)
            return out.toByteArray()
        }

        /** A STREAMINFO block for new audio. */
        fun streamInfo(
            minBlock: Int,
            maxBlock: Int,
            minFrame: Int,
            maxFrame: Int,
            rate: Int,
            channels: Int,
            bits: Int,
            total: Long,
            md5: ByteArray,
        ): ByteArray {
            val b = ByteArray(34)
            b[0] = (minBlock ushr 8).toByte(); b[1] = minBlock.toByte()
            b[2] = (maxBlock ushr 8).toByte(); b[3] = maxBlock.toByte()
            b[4] = (minFrame ushr 16).toByte(); b[5] = (minFrame ushr 8).toByte(); b[6] = minFrame.toByte()
            b[7] = (maxFrame ushr 16).toByte(); b[8] = (maxFrame ushr 8).toByte(); b[9] = maxFrame.toByte()
            val packed = (rate.toLong() shl 44) or ((channels - 1).toLong() shl 41) or
                ((bits - 1).toLong() shl 36) or (total and 0xFFFFFFFFFL)
            for (i in 0 until 8) b[10 + i] = (packed ushr (56 - 8 * i)).toByte()
            System.arraycopy(md5, 0, b, 18, 16)
            return b
        }

        /** Serialises blocks as a FLAC head: the marker, then each block, the last one flagged. */
        fun write(blocks: List<Block>): ByteArray {
            val out = ByteArrayOutputStream()
            out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
            for ((i, b) in blocks.withIndex()) {
                out.write((if (i == blocks.lastIndex) 0x80 else 0) or b.type)
                out.write(b.data.size ushr 16)
                out.write(b.data.size ushr 8)
                out.write(b.data.size)
                out.write(b.data)
            }
            return out.toByteArray()
        }
    }
}
