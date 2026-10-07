package com.songaudit.audio

import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class FormatException(message: String) : IOException(message)

/**
 * Seekable reads for the header parsers.
 *
 * Headers are small and pictures are not, so everything here reads exactly
 * what it asks for and seeks past the rest: a 4 MB cover costs one seek, not
 * 4 MB of a slow SD card.
 */
class Source(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val length: Long = raf.length()

    var position: Long
        get() = raf.filePointer
        set(value) = raf.seek(value)

    fun skip(n: Long) {
        position += n
    }

    fun bytes(n: Int): ByteArray {
        if (n < 0 || position + n > length) throw EOFException("wanted $n bytes at $position of $length")
        return ByteArray(n).also { raf.readFully(it) }
    }

    /** As many bytes as there are, up to [n]. */
    fun upTo(n: Int): ByteArray {
        val count = minOf(n.toLong(), length - position).toInt().coerceAtLeast(0)
        return bytes(count)
    }

    fun u8(): Int = raf.read().also { if (it < 0) throw EOFException() }
    fun u16be(): Int = (u8() shl 8) or u8()
    fun u16le(): Int = u8() or (u8() shl 8)
    fun u24be(): Int = (u8() shl 16) or (u8() shl 8) or u8()
    fun u32be(): Long = (u16be().toLong() shl 16) or u16be().toLong()
    fun u32le(): Long = u16le().toLong() or (u16le().toLong() shl 16)
    fun u64le(): Long = u32le() or (u32le() shl 32)
    fun ascii(n: Int): String = String(bytes(n), Charsets.ISO_8859_1)

    override fun close() = raf.close()
}

fun ByteArray.u16be(at: Int): Int = ((this[at].toInt() and 0xFF) shl 8) or (this[at + 1].toInt() and 0xFF)
fun ByteArray.u32be(at: Int): Long = (u16be(at).toLong() shl 16) or u16be(at + 2).toLong()
fun ByteArray.u16le(at: Int): Int = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)
fun ByteArray.u32le(at: Int): Long = u16le(at).toLong() or (u16le(at + 2).toLong() shl 16)
fun ByteArray.ascii(at: Int, n: Int): String = String(this, at, n, Charsets.ISO_8859_1)

/**
 * Width and height of an embedded JPEG or PNG, read from its own header
 * rather than trusting the container: FLAC pictures often say 0x0.
 */
object ImageSize {
    fun read(src: Source, offset: Long, length: Int): Pair<Int, Int> = try {
        src.position = offset
        val head = src.upTo(minOf(length, 32))
        when {
            head.size >= 24 && head[0] == 0x89.toByte() && head.ascii(1, 3) == "PNG" ->
                head.u32be(16).toInt() to head.u32be(20).toInt()
            head.size >= 4 && head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() -> jpeg(src, offset, length)
            else -> 0 to 0
        }
    } catch (e: IOException) {
        0 to 0
    }

    /** Walks the JPEG's segments by their lengths until the first frame header. */
    private fun jpeg(src: Source, offset: Long, length: Int): Pair<Int, Int> {
        var at = offset + 2
        val end = offset + length
        while (at + 4 <= end) {
            src.position = at
            if (src.u8() != 0xFF) return 0 to 0
            var marker = src.u8()
            while (marker == 0xFF) marker = src.u8()
            val segment = src.u16be()
            val isFrame = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
            if (isFrame) {
                src.u8() // precision
                val height = src.u16be()
                val width = src.u16be()
                return width to height
            }
            at = src.position - 2 + segment
        }
        return 0 to 0
    }
}
