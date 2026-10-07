package com.songaudit.audio

import com.songaudit.analysis.Analysis
import com.songaudit.analysis.Analyzer
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.CancellationException

/** What listening to a whole file found. */
class DeepResult(
    val analysis: Analysis?,
    /** Frames that failed their CRC or could not be parsed. */
    val frameErrors: Int,
    val decodedSamples: Long,
    /** What the header promised; 0 when it does not say. */
    val expectedSamples: Long,
    /** 1 the audio matches the file's own MD5, 0 it does not, -1 the file carries none. */
    val md5Match: Int,
    /** Why the file could not be decoded at all, or null. */
    val error: String?,
) {
    val truncated get() = expectedSamples > 0 && decodedSamples < expectedSamples
    val damaged get() = error != null || frameErrors > 0 || md5Match == 0 || truncated
}

/**
 * The deep pass for the formats decoded in plain Kotlin: FLAC with its own
 * decoder, WAV and AIFF straight from disk. Nothing here touches Android, so
 * all of it runs in the unit tests. Other formats go through MediaCodec.
 */
object Deep {

    fun flac(file: File, cancelled: () -> Boolean = { false }): DeepResult {
        val meta = try {
            Source(file).use { FlacReader.read(it) }
        } catch (e: IOException) {
            return DeepResult(null, 0, 0, 0, -1, e.message ?: "unreadable")
        }
        val info = meta.info
        if (info.bitsPerSample > 24 || info.channels > 8 || info.sampleRate <= 0) {
            return DeepResult(null, 0, 0, info.totalSamples, -1, "unsupported: ${info.bitsPerSample}-bit")
        }
        return try {
            FileInputStream(file).use { stream ->
                stream.channel.position(meta.audioOffset)
                val decoder = FlacDecoder(BufferedInputStream(stream, 1 shl 16), info)
                val analyzer = Analyzer(info.sampleRate, info.channels, info.bitsPerSample)
                var frames = 0
                while (true) {
                    val n = decoder.next()
                    if (n == 0) break
                    analyzer.feed(decoder.samples, n)
                    if (++frames and 63 == 0 && cancelled()) throw CancellationException()
                }
                val analysis = analyzer.finish()
                val md5 = when {
                    !info.md5Known -> -1
                    analysis.md5.contentEquals(info.md5) -> 1
                    else -> 0
                }
                DeepResult(analysis, decoder.frameErrors, decoder.samplesDecoded, info.totalSamples, md5, null)
            }
        } catch (e: IOException) {
            DeepResult(null, 0, 0, info.totalSamples, -1, e.message ?: "read error")
        }
    }

    fun pcm(file: File, cancelled: () -> Boolean = { false }): DeepResult {
        val info = try {
            PcmReader.read(file)
        } catch (e: IOException) {
            return DeepResult(null, 0, 0, 0, -1, e.message ?: "unreadable")
        }
        val width = (info.bits + 7) / 8
        if (info.float && info.bits != 32 || width !in 1..4 || info.channels !in 1..8) {
            return DeepResult(null, 0, 0, info.totalSamples, -1, "unsupported PCM")
        }
        // Float WAV is analysed as 24-bit: the padded-bits check means nothing for it, the rest still does.
        val bits = if (info.float) 24 else info.bits
        val analyzer = Analyzer(info.sampleRate, info.channels, bits)
        val frames = 4096
        val raw = ByteArray(frames * info.frameBytes)
        val ch = Array(info.channels) { IntArray(frames) }
        var decoded = 0L
        return try {
            FileInputStream(file).use { stream ->
                stream.channel.position(info.dataOffset)
                val input = BufferedInputStream(stream, 1 shl 16)
                var remaining = info.dataLength
                while (remaining >= info.frameBytes) {
                    val want = minOf(raw.size.toLong(), remaining - remaining % info.frameBytes).toInt()
                    var got = 0
                    while (got < want) {
                        val r = input.read(raw, got, want - got)
                        if (r < 0) break
                        got += r
                    }
                    val n = got / info.frameBytes
                    if (n == 0) break
                    unpack(raw, n, info, width, ch)
                    analyzer.feed(ch, n)
                    decoded += n
                    remaining -= got
                    if (got < want) break
                    if (cancelled()) throw CancellationException()
                }
                DeepResult(analyzer.finish(), 0, decoded, info.totalSamples, -1, null)
            }
        } catch (e: IOException) {
            DeepResult(null, 0, decoded, info.totalSamples, -1, e.message ?: "read error")
        }
    }

    private fun unpack(raw: ByteArray, n: Int, info: PcmInfo, width: Int, ch: Array<IntArray>) {
        var at = 0
        for (i in 0 until n) {
            for (c in 0 until info.channels) {
                var v = 0
                if (info.bigEndian) {
                    for (b in 0 until width) v = (v shl 8) or (raw[at + b].toInt() and 0xFF)
                } else {
                    for (b in width - 1 downTo 0) v = (v shl 8) or (raw[at + b].toInt() and 0xFF)
                }
                at += width
                ch[c][i] = when {
                    info.float -> (Float.fromBits(v) * 8388607f).toInt().coerceIn(-8388608, 8388607)
                    width == 1 && !info.bigEndian -> v - 128 // 8-bit WAV is unsigned
                    else -> (v shl (32 - width * 8)) shr (32 - width * 8)
                }
            }
        }
    }
}
