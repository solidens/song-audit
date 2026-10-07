package com.songaudit.audio

import java.io.File

/**
 * WAV and AIFF: where the samples are and how they are packed, plus whatever
 * tags ride along in a LIST/INFO or ID3 chunk.
 */
class PcmInfo(
    val sampleRate: Int,
    val channels: Int,
    val bits: Int,
    val float: Boolean,
    val bigEndian: Boolean,
    val dataOffset: Long,
    val dataLength: Long,
    val tags: Tags,
    val pictures: List<Picture>,
) {
    val frameBytes get() = channels * ((bits + 7) / 8)
    val totalSamples get() = if (frameBytes > 0) dataLength / frameBytes else 0
    val durationMs get() = if (sampleRate > 0) totalSamples * 1000 / sampleRate else 0
}

object PcmReader {

    fun read(file: File): PcmInfo = Source(file).use { src ->
        when (src.ascii(4)) {
            "RIFF", "RF64" -> wav(src)
            "FORM" -> aiff(src)
            else -> throw FormatException("not WAV or AIFF")
        }
    }

    private fun wav(src: Source): PcmInfo {
        src.skip(4)
        if (src.ascii(4) != "WAVE") throw FormatException("not WAVE")
        var rate = 0
        var channels = 0
        var bits = 0
        var float = false
        var dataOffset = -1L
        var dataLength = 0L
        var ds64Data = -1L
        val tags = Tags()
        var pictures: List<Picture> = emptyList()
        while (src.position + 8 <= src.length) {
            val id = src.ascii(4)
            var size = src.u32le()
            val start = src.position
            when (id) {
                "ds64" -> {
                    src.skip(8)
                    ds64Data = src.u64le()
                }
                "fmt " -> {
                    val format = src.u16le()
                    channels = src.u16le()
                    rate = src.u32le().toInt()
                    src.skip(6)
                    bits = src.u16le()
                    float = format == 3
                    if (format == 0xFFFE && size >= 40) {
                        src.skip(8)
                        float = src.u16le() == 3
                    }
                }
                "data" -> {
                    dataOffset = start
                    if (size == 0xFFFFFFFFL && ds64Data >= 0) size = ds64Data
                    dataLength = minOf(size, src.length - start)
                }
                "LIST" -> if (size >= 4 && src.ascii(4) == "INFO") info(src, start + size, tags)
                "id3 ", "ID3 " -> Id3.read(src, tags)?.let { pictures = it.pictures }
            }
            src.position = start + size + (size and 1)
        }
        if (dataOffset < 0 || channels == 0 || bits == 0) throw FormatException("no audio in WAV")
        return PcmInfo(rate, channels, bits, float, false, dataOffset, dataLength, tags, pictures)
    }

    private fun info(src: Source, end: Long, tags: Tags) {
        while (src.position + 8 <= end) {
            val id = src.ascii(4)
            val size = src.u32le()
            val start = src.position
            val text = String(src.bytes(size.toInt().coerceAtMost(4096)), Charsets.UTF_8)
            when (id) {
                "INAM" -> tags.set("TITLE", text)
                "IART" -> tags.set("ARTIST", text)
                "IPRD" -> tags.set("ALBUM", text)
                "ITRK", "IPRT" -> tags.set("TRACKNUMBER", text)
                "ICRD" -> tags.set("DATE", text)
                "IGNR" -> tags.set("GENRE", text)
            }
            src.position = start + size + (size and 1)
        }
    }

    private fun aiff(src: Source): PcmInfo {
        src.skip(4)
        val kind = src.ascii(4)
        if (kind != "AIFF" && kind != "AIFC") throw FormatException("not AIFF")
        var rate = 0
        var channels = 0
        var bits = 0
        var littleEndian = false
        var float = false
        var dataOffset = -1L
        var dataLength = 0L
        val tags = Tags()
        var pictures: List<Picture> = emptyList()
        while (src.position + 8 <= src.length) {
            val id = src.ascii(4)
            val size = src.u32be()
            val start = src.position
            when (id) {
                "COMM" -> {
                    channels = src.u16be()
                    src.skip(4)
                    bits = src.u16be()
                    rate = extended(src.bytes(10)).toInt()
                    if (kind == "AIFC" && size >= 22) {
                        when (src.ascii(4)) {
                            "sowt" -> littleEndian = true
                            "fl32", "FL32" -> float = true
                        }
                    }
                }
                "SSND" -> {
                    val offset = src.u32be()
                    src.skip(4)
                    dataOffset = src.position + offset
                    dataLength = minOf(size - 8 - offset, src.length - dataOffset)
                }
                "NAME" -> tags.set("TITLE", String(src.bytes(size.toInt().coerceAtMost(4096)), Charsets.ISO_8859_1))
                "ID3 ", "id3 " -> Id3.read(src, tags)?.let { pictures = it.pictures }
            }
            src.position = start + size + (size and 1)
        }
        if (dataOffset < 0 || channels == 0 || bits == 0) throw FormatException("no audio in AIFF")
        return PcmInfo(rate, channels, bits, float, !littleEndian, dataOffset, dataLength, tags, pictures)
    }

    /** The 80-bit IEEE extended float AIFF stores its sample rate in. */
    private fun extended(b: ByteArray): Double {
        val exponent = ((b[0].toInt() and 0x7F) shl 8) or (b[1].toInt() and 0xFF)
        var mantissa = 0L
        for (i in 2 until 10) mantissa = (mantissa shl 8) or (b[i].toLong() and 0xFF)
        if (exponent == 0 && mantissa == 0L) return 0.0
        // The mantissa is unsigned with an explicit integer bit, so it can fill all 64 bits.
        val m = (mantissa ushr 1).toDouble() * 2 + (mantissa and 1)
        return Math.scalb(m, exponent - 16383 - 63)
    }
}
