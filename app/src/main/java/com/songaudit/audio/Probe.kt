package com.songaudit.audio

import java.io.File
import java.io.IOException

/** What a file's headers say, without decoding any audio. */
class Probed(
    val format: String,
    val lossless: Boolean,
    val sampleRate: Int,
    /** 0 for lossy formats, where bit depth does not exist. */
    val bits: Int,
    val channels: Int,
    val durationMs: Long,
    /** Average, in kbps; for lossless formats the compressed rate. */
    val bitrate: Int,
    val tags: Tags,
    val pictures: List<Picture>,
    /** FLAC's own MD5 of its audio, when the encoder wrote one. */
    val audioMd5: ByteArray? = null,
)

/**
 * The fast pass for formats parsed in plain Kotlin. Returns null for formats
 * left to Android's own retriever (MP4, Ogg, Opus).
 */
object Probe {

    val EXTENSIONS = setOf("flac", "mp3", "wav", "aif", "aiff", "aifc", "dsf", "dff", "ape", "wv", "m4a", "mp4", "aac", "alac", "ogg", "oga", "opus")
    val LOSSLESS = setOf("FLAC", "WAV", "AIFF", "DSF", "DFF", "APE", "WV", "ALAC")

    fun probe(file: File): Probed? {
        val size = file.length()
        return when (file.extension.lowercase()) {
            "flac" -> Source(file).use { src ->
                val m = FlacReader.read(src)
                Probed(
                    "FLAC", true, m.info.sampleRate, m.info.bitsPerSample, m.info.channels, m.info.durationMs,
                    rate(size - m.audioOffset, m.info.durationMs), m.tags, m.pictures,
                    m.info.md5.takeIf { m.info.md5Known },
                )
            }
            "mp3" -> Source(file).use { Mp3.probe(it) }
            "wav", "aif", "aiff", "aifc" -> {
                val p = PcmReader.read(file)
                Probed(
                    if (file.extension.lowercase() == "wav") "WAV" else "AIFF", true, p.sampleRate, p.bits, p.channels,
                    p.durationMs, rate(p.dataLength, p.durationMs), p.tags, p.pictures,
                )
            }
            "dsf" -> Source(file).use { Dsd.dsf(it) }
            "dff" -> Source(file).use { Dsd.dff(it) }
            "ape" -> Source(file).use { Monkey.probe(it) }
            "wv" -> Source(file).use { WavPack.probe(it) }
            else -> null
        }
    }

    fun rate(bytes: Long, ms: Long): Int = if (ms > 0) (bytes * 8 / ms).toInt() else 0
}

object Mp3 {
    private val BITRATE_V1 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320)
    private val BITRATE_V2 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160)
    private val RATE = intArrayOf(44100, 48000, 32000)

    private class Header(val version: Int, val bitrate: Int, val sampleRate: Int, val length: Int, val mono: Boolean) {
        val samples get() = if (version == 3) 1152 else 576
    }

    /** Layer III only; version 3 is MPEG-1, 2 is MPEG-2, 0 is MPEG-2.5. */
    private fun header(b: ByteArray, at: Int): Header? {
        if (at + 4 > b.size) return null
        val h = b.u32be(at).toInt()
        if (h ushr 21 != 0x7FF) return null
        val version = (h ushr 19) and 3
        val layer = (h ushr 17) and 3
        val bitrateIndex = (h ushr 12) and 15
        val rateIndex = (h ushr 10) and 3
        if (version == 1 || layer != 1 || bitrateIndex == 0 || bitrateIndex == 15 || rateIndex == 3) return null
        val bitrate = (if (version == 3) BITRATE_V1 else BITRATE_V2)[bitrateIndex]
        val rate = RATE[rateIndex] shr (if (version == 3) 0 else if (version == 2) 1 else 2)
        val padding = (h ushr 9) and 1
        val length = (if (version == 3) 144000 else 72000) * bitrate / rate + padding
        return Header(version, bitrate, rate, length, (h ushr 6) and 3 == 3)
    }

    fun probe(src: Source): Probed {
        val tags = Tags()
        var pictures: List<Picture> = emptyList()
        var start = 0L
        // Some files carry two leading tags; read them all.
        while (true) {
            src.position = start
            val r = Id3.read(src, tags) ?: break
            pictures = pictures + r.pictures
            start = r.end
        }
        var end = src.length
        if (end >= 128) {
            src.position = end - 128
            val v1 = src.bytes(128)
            if (v1.ascii(0, 3) == "TAG") {
                end -= 128
                id3v1(v1, tags)
            }
        }
        Ape.footer(src, end)?.let { (apeStart, items) ->
            end = apeStart
            Ape.apply(items, tags)
        }

        // Find the first frame that is followed by another: a lone sync pattern is too easy to hit by chance.
        src.position = start
        val window = src.upTo(256 * 1024)
        var at = 0
        var first: Header? = null
        while (at + 4 < window.size) {
            val h = header(window, at)
            if (h != null) {
                val next = header(window, at + h.length)
                if (next != null || at + h.length >= window.size) {
                    first = h
                    break
                }
            }
            at++
        }
        first ?: throw FormatException("no MPEG audio frames")
        val frameStart = start + at

        val side = if (first.version == 3) (if (first.mono) 17 else 32) else (if (first.mono) 9 else 17)
        val xing = at + 4 + side
        var frames = 0L
        if (xing + 12 <= window.size) {
            val id = window.ascii(xing, 4)
            if ((id == "Xing" || id == "Info") && window.u32be(xing + 4) and 1 == 1L) frames = window.u32be(xing + 8)
        }
        val vbri = at + 4 + 32
        if (frames == 0L && vbri + 18 <= window.size && window.ascii(vbri, 4) == "VBRI") frames = window.u32be(vbri + 14)

        val audioBytes = end - frameStart
        val ms = if (frames > 0) frames * first.samples * 1000 / first.sampleRate
        else audioBytes * 8 / first.bitrate
        return Probed(
            "MP3", false, first.sampleRate, 0, if (first.mono) 1 else 2, ms,
            Probe.rate(audioBytes, ms), tags, pictures,
        )
    }

    private fun id3v1(b: ByteArray, tags: Tags) {
        fun text(at: Int, n: Int) = String(b, at, n, Charsets.ISO_8859_1).substringBefore('\u0000').trim()
        tags.set("TITLE", text(3, 30))
        tags.set("ARTIST", text(33, 30))
        tags.set("ALBUM", text(63, 30))
        tags.set("DATE", text(93, 4))
        if (b[125] == 0.toByte() && b[126] != 0.toByte()) tags.set("TRACKNUMBER", (b[126].toInt() and 0xFF).toString())
    }
}

/** APEv2 tags, at the end of APE, WavPack and the odd MP3. */
object Ape {
    class Item(val key: String, val text: String?, val binaryBytes: Int)

    /** The tag ending at [end], as its start offset and its items, or null. */
    fun footer(src: Source, end: Long): Pair<Long, List<Item>>? {
        if (end < 32) return null
        src.position = end - 32
        val f = src.bytes(32)
        if (f.ascii(0, 8) != "APETAGEX") return null
        val size = f.u32le(12)
        val count = f.u32le(16).toInt()
        val flags = f.u32le(20)
        val itemsStart = end - size
        if (itemsStart < 0 || count > 10_000) return null
        val hasHeader = flags and 0x80000000L != 0L
        val tagStart = itemsStart - if (hasHeader) 32 else 0
        src.position = itemsStart
        val items = ArrayList<Item>()
        repeat(count) {
            if (src.position + 8 > end) return tagStart to items
            val valueSize = src.u32le().toInt()
            val itemFlags = src.u32le()
            val key = StringBuilder()
            while (true) {
                val c = src.u8()
                if (c == 0) break
                key.append(c.toChar())
            }
            val binary = (itemFlags ushr 1) and 3 == 1L
            if (binary || valueSize > 1 shl 16) {
                items += Item(key.toString(), null, valueSize)
                src.skip(valueSize.toLong())
            } else {
                items += Item(key.toString(), String(src.bytes(valueSize), Charsets.UTF_8), 0)
            }
        }
        return tagStart to items
    }

    fun apply(items: List<Item>, tags: Tags): List<Picture> {
        val pictures = ArrayList<Picture>()
        for (item in items) {
            if (item.text != null) {
                // Multiple values are separated by NUL; the first is enough.
                tags.set(item.key, item.text.substringBefore('\u0000'))
            } else if (item.key.startsWith("Cover Art", ignoreCase = true)) {
                pictures += Picture(3, "image/jpeg", 0, 0, item.binaryBytes)
            }
        }
        return pictures
    }
}

object Dsd {
    fun dsf(src: Source): Probed {
        if (src.ascii(4) != "DSD ") throw FormatException("not DSF")
        src.skip(16)
        val metadata = src.u64le()
        if (src.ascii(4) != "fmt ") throw FormatException("no fmt chunk")
        src.skip(8 + 4 + 4 + 4)
        val channels = src.u32le().toInt()
        val rate = src.u32le().toInt()
        src.skip(4)
        val samples = src.u64le()
        val ms = if (rate > 0) samples * 1000 / rate else 0
        val tags = Tags()
        var pictures: List<Picture> = emptyList()
        if (metadata in 1 until src.length) {
            src.position = metadata
            Id3.read(src, tags)?.let { pictures = it.pictures }
        }
        return Probed("DSF", true, rate, 1, channels, ms, Probe.rate(src.length, ms), tags, pictures)
    }

    fun dff(src: Source): Probed {
        if (src.ascii(4) != "FRM8") throw FormatException("not DSDIFF")
        src.skip(8)
        if (src.ascii(4) != "DSD ") throw FormatException("not DSDIFF")
        var rate = 0
        var channels = 0
        var dataBytes = 0L
        val tags = Tags()
        var pictures: List<Picture> = emptyList()
        fun chunks(end: Long, inProp: Boolean) {
            while (src.position + 12 <= end) {
                val id = src.ascii(4)
                val size = src.u32be() shl 32 or src.u32be()
                val start = src.position
                when {
                    id == "PROP" -> if (src.ascii(4) == "SND ") chunks(start + size, true)
                    inProp && id == "FS  " -> rate = src.u32be().toInt()
                    inProp && id == "CHNL" -> channels = src.u16be()
                    id == "DSD " -> dataBytes = size
                    id == "ID3 " -> Id3.read(src, tags)?.let { pictures = it.pictures }
                }
                src.position = start + size + (size and 1)
            }
        }
        chunks(src.length, false)
        val ms = if (rate > 0 && channels > 0) dataBytes * 8 * 1000 / channels / rate else 0
        return Probed("DFF", true, rate, 1, channels, ms, Probe.rate(src.length, ms), tags, pictures)
    }
}

/** Monkey's Audio. Only the 3.98+ header, which is everything made this century. */
object Monkey {
    fun probe(src: Source): Probed {
        val start = Id3.skip(src)
        src.position = start
        if (src.ascii(4) != "MAC ") throw FormatException("not APE")
        val version = src.u16le()
        var rate = 0
        var channels = 0
        var bits = 0
        var samples = 0L
        if (version >= 3980) {
            src.skip(2)
            val descriptor = src.u32le()
            src.position = start + descriptor
            src.skip(4) // compression level, format flags
            val perFrame = src.u32le()
            val finalFrame = src.u32le()
            val frames = src.u32le()
            bits = src.u16le()
            channels = src.u16le()
            rate = src.u32le().toInt()
            if (frames > 0) samples = (frames - 1) * perFrame + finalFrame
        }
        val tags = Tags()
        val pictures = Ape.footer(src, tailEnd(src))?.let { Ape.apply(it.second, tags) } ?: emptyList()
        val ms = if (rate > 0) samples * 1000 / rate else 0
        return Probed("APE", true, rate, bits, channels, ms, Probe.rate(src.length, ms), tags, pictures)
    }

    fun tailEnd(src: Source): Long {
        if (src.length < 128) return src.length
        src.position = src.length - 128
        return if (src.ascii(3) == "TAG") src.length - 128 else src.length
    }
}

object WavPack {
    private val RATES = intArrayOf(6000, 8000, 9600, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000, 64000, 88200, 96000, 192000)

    fun probe(src: Source): Probed {
        src.position = Id3.skip(src)
        val b = src.bytes(32)
        if (b.ascii(0, 4) != "wvpk") throw FormatException("not WavPack")
        val totalLow = b.u32le(12)
        val totalHigh = (b[11].toLong() and 0xFF)
        val samples = if (totalLow == 0xFFFFFFFFL) 0 else (totalHigh shl 32) or totalLow
        val flags = b.u32le(24)
        val rateIndex = ((flags ushr 23) and 15).toInt()
        val rate = if (rateIndex < RATES.size) RATES[rateIndex] else 0
        val bits = (((flags and 3) + 1) * 8).toInt()
        val channels = if (flags and 4 != 0L) 1 else 2
        // A hybrid file without its correction file is lossy.
        val hybrid = flags and 8 != 0L
        val tags = Tags()
        val pictures = Ape.footer(src, Monkey.tailEnd(src))?.let { Ape.apply(it.second, tags) } ?: emptyList()
        val ms = if (rate > 0) samples * 1000 / rate else 0
        return Probed("WV", !hybrid, rate, if (hybrid) 0 else bits, channels, ms, Probe.rate(src.length, ms), tags, pictures)
    }
}

/** Reads a probe that fails as null rather than an exception: one broken header must not stop a scan. */
fun probeOrNull(file: File): Result<Probed?> = try {
    Result.success(Probe.probe(file))
} catch (e: IOException) {
    Result.failure(e)
} catch (e: RuntimeException) {
    Result.failure(e)
}
