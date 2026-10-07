package com.songaudit.audio

import java.io.InputStream

class StreamInfo(
    val minBlockSize: Int,
    val maxBlockSize: Int,
    val maxFrameSize: Int,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val totalSamples: Long,
    val md5: ByteArray,
) {
    /** Some encoders leave the signature as zeros; then there is nothing to check against. */
    val md5Known: Boolean get() = md5.any { it != 0.toByte() }
    val durationMs: Long get() = if (sampleRate > 0) totalSamples * 1000 / sampleRate else 0
}

class FlacMeta(
    val info: StreamInfo,
    val tags: Tags,
    val pictures: List<Picture>,
    /** Where the first audio frame starts. */
    val audioOffset: Long,
)

object FlacReader {

    fun read(src: Source): FlacMeta {
        src.position = Id3.skip(src)
        if (src.ascii(4) != "fLaC") throw FormatException("not a FLAC stream")

        var info: StreamInfo? = null
        val tags = Tags()
        val pictures = ArrayList<Picture>()
        while (true) {
            val header = src.u8()
            val last = header and 0x80 != 0
            val type = header and 0x7F
            val length = src.u24be()
            val start = src.position
            when (type) {
                0 -> info = streamInfo(src.bytes(34))
                4 -> comments(src.bytes(length), tags)
                6 -> picture(src, length)?.let(pictures::add)
                127 -> throw FormatException("invalid metadata block")
            }
            src.position = start + length
            if (last) break
        }
        return FlacMeta(info ?: throw FormatException("no STREAMINFO"), tags, pictures, src.position)
    }

    private fun streamInfo(b: ByteArray): StreamInfo {
        val packed = b.u32be(10) shl 32 or b.u32be(14)
        return StreamInfo(
            minBlockSize = b.u16be(0),
            maxBlockSize = b.u16be(2),
            maxFrameSize = ((b[7].toInt() and 0xFF) shl 16) or b.u16be(8),
            sampleRate = (packed ushr 44).toInt(),
            channels = ((packed ushr 41) and 0x7).toInt() + 1,
            bitsPerSample = ((packed ushr 36) and 0x1F).toInt() + 1,
            totalSamples = packed and 0xFFFFFFFFFL,
            md5 = b.copyOfRange(18, 34),
        )
    }

    /** Vorbis comments: little-endian lengths, "KEY=value" in UTF-8. */
    fun comments(b: ByteArray, tags: Tags) {
        var at = 0
        fun len(): Int = b.u32le(at).toInt().also { at += 4 }
        val vendor = len()
        at += vendor
        if (at + 4 > b.size) return
        val count = len()
        repeat(count) {
            if (at + 4 > b.size) return
            val n = len()
            if (n < 0 || at + n > b.size) return
            val entry = String(b, at, n, Charsets.UTF_8)
            at += n
            val eq = entry.indexOf('=')
            if (eq > 0) tags.set(entry.substring(0, eq), entry.substring(eq + 1))
        }
    }

    private fun picture(src: Source, length: Int): Picture? {
        if (length < 32) return null
        val type = src.u32be().toInt()
        val mime = src.ascii(src.u32be().toInt().coerceIn(0, 256))
        src.skip(src.u32be()) // description
        var width = src.u32be().toInt()
        var height = src.u32be().toInt()
        src.skip(8) // colour depth, palette size
        val bytes = src.u32be().toInt()
        if (width == 0 || height == 0) {
            val (w, h) = ImageSize.read(src, src.position, bytes)
            width = w
            height = h
        }
        return Picture(type, mime, width, height, bytes)
    }
}

/**
 * A FLAC frame decoder, written for checking files rather than playing them.
 *
 * Every frame's header CRC-8 and body CRC-16 are verified; a frame that fails
 * either is counted in [frameErrors] and skipped by searching for the next
 * sync code, so one bad sector costs one frame and not the rest of the track.
 *
 * Samples come out as integers at the stream's own bit depth, which is what
 * the MD5 check and the padded-bits check both need.
 */
class FlacDecoder(private val input: InputStream, private val info: StreamInfo) {

    val channels = info.channels
    val bitsPerSample = info.bitsPerSample

    var frameErrors = 0
        private set
    var samplesDecoded = 0L
        private set

    /** One frame's worth of samples per channel, valid up to the count [next] returned. */
    var samples: Array<IntArray> = Array(channels) { IntArray(maxOf(info.maxBlockSize, 4608)) }
        private set

    private val bound: Int = run {
        val worst = 65535 * channels * (bitsPerSample + 1) / 8 + 64
        val block = if (info.maxBlockSize > 0) info.maxBlockSize else 65535
        val likely = block * channels * (bitsPerSample + 1) / 8 + 64
        if (info.maxFrameSize > 0) maxOf(info.maxFrameSize + 64, 1024) else minOf(likely * 2, worst)
    }
    private val capacity = maxOf(1 shl 20, bound * 3)
    private val buf = ByteArray(capacity + PAD)
    private var end = 0
    private var eof = false
    private var bitPos = 0
    private var residualScratch = IntArray(0)

    /**
     * Decodes the next frame into [samples] and returns its block size, or 0
     * at the end of the stream.
     */
    fun next(): Int {
        while (true) {
            refill()
            val frameStart = bitPos ushr 3
            if (frameStart >= end) return 0
            try {
                val n = frame()
                samplesDecoded += n
                return n
            } catch (e: FormatException) {
                // Trailing junk after the last sample -- an ID3v1 tag, say -- is not damage.
                if (info.totalSamples in 1..samplesDecoded) return 0
                frameErrors++
            } catch (e: IndexOutOfBoundsException) {
                if (info.totalSamples in 1..samplesDecoded) return 0
                frameErrors++
            }
            if (!seekSync(frameStart + 1)) return 0
        }
    }

    // -- Buffer ---------------------------------------------------------------

    /** Keeps at least one whole frame ahead of the read position, or the rest of the file. */
    private fun refill() {
        val pos = bitPos ushr 3
        if (end - pos >= bound || eof) return
        System.arraycopy(buf, pos, buf, 0, end - pos)
        end -= pos
        bitPos = 0
        while (end < capacity && !eof) {
            val n = input.read(buf, end, capacity - end)
            if (n < 0) eof = true else end += n
        }
    }

    private fun seekSync(from: Int): Boolean {
        bitPos = from shl 3
        while (true) {
            refill()
            var p = bitPos ushr 3
            while (p + 1 < end) {
                if (buf[p] == 0xFF.toByte() && (buf[p + 1].toInt() and 0xFE) == 0xF8) {
                    bitPos = p shl 3
                    return true
                }
                p++
            }
            if (eof) return false
            // Keep the last byte: it may be the first half of a sync code.
            bitPos = p shl 3
        }
    }

    private fun load64(byte: Int): Long =
        ((buf[byte].toLong() and 0xFF) shl 56) or
            ((buf[byte + 1].toLong() and 0xFF) shl 48) or
            ((buf[byte + 2].toLong() and 0xFF) shl 40) or
            ((buf[byte + 3].toLong() and 0xFF) shl 32) or
            ((buf[byte + 4].toLong() and 0xFF) shl 24) or
            ((buf[byte + 5].toLong() and 0xFF) shl 16) or
            ((buf[byte + 6].toLong() and 0xFF) shl 8) or
            (buf[byte + 7].toLong() and 0xFF)

    /** Up to 32 bits, unsigned. */
    private fun bits(n: Int): Int {
        if (n == 0) return 0
        val p = bitPos
        bitPos = p + n
        return ((load64(p ushr 3) shl (p and 7)) ushr (64 - n)).toInt()
    }

    /** Up to 32 bits, two's complement. */
    private fun signed(n: Int): Int {
        if (n == 0) return 0
        val p = bitPos
        bitPos = p + n
        return ((load64(p ushr 3) shl (p and 7)) shr (64 - n)).toInt()
    }

    // -- Frame ----------------------------------------------------------------

    private fun frame(): Int {
        val start = bitPos ushr 3
        if (bits(15) != 0x7FFC) throw FormatException("lost sync")
        bits(1) // blocking strategy
        val blockCode = bits(4)
        val rateCode = bits(4)
        val channelCode = bits(4)
        val sizeCode = bits(3)
        if (bits(1) != 0) throw FormatException("reserved bit")

        // The frame or sample number, UTF-8 style; only its length matters here.
        val first = bits(8)
        val extra = when {
            first and 0x80 == 0 -> 0
            first and 0xE0 == 0xC0 -> 1
            first and 0xF0 == 0xE0 -> 2
            first and 0xF8 == 0xF0 -> 3
            first and 0xFC == 0xF8 -> 4
            first and 0xFE == 0xFC -> 5
            first == 0xFE -> 6
            else -> throw FormatException("bad frame number")
        }
        repeat(extra) { if (bits(8) and 0xC0 != 0x80) throw FormatException("bad frame number") }

        val blockSize = when (blockCode) {
            0 -> throw FormatException("reserved block size")
            1 -> 192
            in 2..5 -> 576 shl (blockCode - 2)
            6 -> bits(8) + 1
            7 -> bits(16) + 1
            else -> 256 shl (blockCode - 8)
        }
        when (rateCode) {
            12 -> bits(8)
            13, 14 -> bits(16)
            15 -> throw FormatException("bad sample rate")
        }
        val bps = when (sizeCode) {
            0 -> bitsPerSample
            1 -> 8
            2 -> 12
            4 -> 16
            5 -> 20
            6 -> 24
            else -> throw FormatException("unsupported sample size")
        }
        if (bps != bitsPerSample) throw FormatException("sample size changed mid-stream")

        val headerEnd = bitPos ushr 3
        if (bits(8) != Crc.crc8(buf, start, headerEnd)) throw FormatException("header CRC")

        val frameChannels = when {
            channelCode < 8 -> channelCode + 1
            channelCode <= 10 -> 2
            else -> throw FormatException("reserved channel assignment")
        }
        if (frameChannels != channels) throw FormatException("channel count changed mid-stream")
        if (samples[0].size < blockSize) samples = Array(channels) { IntArray(blockSize) }

        for (c in 0 until channels) {
            val side = (channelCode == 8 && c == 1) || (channelCode == 9 && c == 0) || (channelCode == 10 && c == 1)
            subframe(samples[c], blockSize, if (side) bps + 1 else bps)
        }

        bitPos = (bitPos + 7) and 7.inv()
        val bodyEnd = bitPos ushr 3
        if (bodyEnd + 2 > end) throw FormatException("truncated frame")
        if (bits(16) != Crc.crc16(buf, start, bodyEnd)) throw FormatException("frame CRC")

        decorrelate(channelCode, blockSize)
        return blockSize
    }

    private fun decorrelate(code: Int, n: Int) {
        if (code < 8) return
        val a = samples[0]
        val b = samples[1]
        when (code) {
            8 -> for (i in 0 until n) b[i] = a[i] - b[i] // left, side
            9 -> for (i in 0 until n) a[i] += b[i] // side, right
            10 -> for (i in 0 until n) { // mid, side
                val side = b[i]
                val mid = (a[i] shl 1) or (side and 1)
                a[i] = (mid + side) shr 1
                b[i] = (mid - side) shr 1
            }
        }
    }

    // -- Subframes ------------------------------------------------------------

    private fun subframe(out: IntArray, n: Int, frameBps: Int) {
        if (bits(1) != 0) throw FormatException("subframe padding")
        val type = bits(6)
        var wasted = 0
        if (bits(1) == 1) {
            wasted = 1
            while (bits(1) == 0) {
                wasted++
                if (wasted >= frameBps) throw FormatException("wasted bits")
            }
        }
        val bps = frameBps - wasted
        when {
            type == 0 -> out.fill(signed(bps), 0, n)
            type == 1 -> for (i in 0 until n) out[i] = signed(bps)
            type in 8..12 -> fixed(out, n, bps, type - 8)
            type >= 32 -> lpc(out, n, bps, (type and 31) + 1)
            else -> throw FormatException("reserved subframe type")
        }
        if (wasted > 0) for (i in 0 until n) out[i] = out[i] shl wasted
    }

    private fun fixed(out: IntArray, n: Int, bps: Int, order: Int) {
        if (order > n) throw FormatException("order beyond block")
        for (i in 0 until order) out[i] = signed(bps)
        residual(out, n, order)
        when (order) {
            1 -> for (i in 1 until n) out[i] += out[i - 1]
            2 -> for (i in 2 until n) out[i] += 2 * out[i - 1] - out[i - 2]
            3 -> for (i in 3 until n) out[i] += 3 * (out[i - 1] - out[i - 2]) + out[i - 3]
            4 -> for (i in 4 until n) out[i] += 4 * (out[i - 1] + out[i - 3]) - 6 * out[i - 2] - out[i - 4]
        }
    }

    private fun lpc(out: IntArray, n: Int, bps: Int, order: Int) {
        if (order > n) throw FormatException("order beyond block")
        for (i in 0 until order) out[i] = signed(bps)
        val precision = bits(4) + 1
        if (precision == 16) throw FormatException("bad LPC precision")
        val shift = signed(5)
        if (shift < 0) throw FormatException("negative LPC shift")
        val coefs = IntArray(order) { signed(precision) }
        residual(out, n, order)
        for (i in order until n) {
            var sum = 0L
            var j = 0
            while (j < order) {
                sum += coefs[j].toLong() * out[i - 1 - j]
                j++
            }
            out[i] += (sum shr shift).toInt()
        }
    }

    private fun residual(out: IntArray, n: Int, order: Int) {
        val method = bits(2)
        if (method > 1) throw FormatException("reserved residual coding")
        val paramBits = if (method == 0) 4 else 5
        val escape = (1 shl paramBits) - 1
        val partitionOrder = bits(4)
        val partitionLength = n ushr partitionOrder
        if (partitionLength shl partitionOrder != n || partitionLength < order) {
            throw FormatException("bad partition order")
        }
        var at = order
        for (p in 0 until (1 shl partitionOrder)) {
            val count = if (p == 0) partitionLength - order else partitionLength
            val param = bits(paramBits)
            if (param == escape) {
                val raw = bits(5)
                for (i in 0 until count) out[at + i] = signed(raw)
            } else {
                rice(out, at, count, param)
            }
            at += count
        }
    }

    /**
     * The hot loop. Unary quotient by counting leading zeros of 64 bits at a
     * time, then the binary remainder, then zigzag back to signed.
     */
    private fun rice(out: IntArray, from: Int, count: Int, param: Int) {
        var p = bitPos
        val limit = (end + 8) shl 3
        for (i in from until from + count) {
            var q = 0
            while (true) {
                val shift = p and 7
                val word = load64(p ushr 3) shl shift
                if (word != 0L) {
                    val zeros = java.lang.Long.numberOfLeadingZeros(word)
                    q += zeros
                    p += zeros + 1
                    break
                }
                q += 64 - shift
                p += 64 - shift
                if (p > limit) throw FormatException("runaway residual")
            }
            val low = if (param == 0) 0 else ((load64(p ushr 3) shl (p and 7)) ushr (64 - param)).toInt()
            p += param
            val u = (q shl param) or low
            out[i] = (u ushr 1) xor -(u and 1)
        }
        bitPos = p
    }

    private companion object {
        const val PAD = 16
    }
}

object Crc {
    private val table8 = IntArray(256) { i ->
        var c = i
        repeat(8) { c = if (c and 0x80 != 0) (c shl 1) xor 0x07 else c shl 1 }
        c and 0xFF
    }
    private val table16 = IntArray(256) { i ->
        var c = i shl 8
        repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x8005 else c shl 1 }
        c and 0xFFFF
    }

    fun crc8(b: ByteArray, from: Int, to: Int): Int {
        var c = 0
        for (i in from until to) c = table8[c xor (b[i].toInt() and 0xFF)]
        return c
    }

    fun crc16(b: ByteArray, from: Int, to: Int): Int {
        var c = 0
        for (i in from until to) c = ((c shl 8) xor table16[(c ushr 8) xor (b[i].toInt() and 0xFF)]) and 0xFFFF
        return c
    }
}
