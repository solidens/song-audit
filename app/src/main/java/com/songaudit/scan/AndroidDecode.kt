package com.songaudit.scan

import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import com.songaudit.analysis.Analyzer
import com.songaudit.audio.DeepResult
import com.songaudit.audio.Picture
import com.songaudit.audio.Probed
import com.songaudit.audio.Tags
import java.io.File
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/**
 * The formats Android parses better than this app would: MP4 (AAC and ALAC),
 * Ogg Vorbis and Opus. Tags come from the media retriever, samples from
 * MediaCodec.
 */
object AndroidDecode {

    fun probe(file: File): Probed {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(file.path)
            fun key(k: Int) = mmr.extractMetadata(k)
            val tags = Tags()
            key(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { tags.set("TITLE", it) }
            key(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { tags.set("ARTIST", it) }
            key(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { tags.set("ALBUM", it) }
            key(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.let { tags.set("ALBUMARTIST", it) }
            key(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.let { tags.set("TRACKNUMBER", it) }
            key(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)?.let { tags.set("DISCNUMBER", it) }
            key(MediaMetadataRetriever.METADATA_KEY_YEAR)?.let { tags.set("DATE", it) }
            key(MediaMetadataRetriever.METADATA_KEY_GENRE)?.let { tags.set("GENRE", it) }
            key(MediaMetadataRetriever.METADATA_KEY_COMPILATION)?.let { tags.set("COMPILATION", it) }
            val duration = key(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val bitrate = (key(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0) / 1000

            val pictures = ArrayList<Picture>()
            mmr.embeddedPicture?.let { bytes ->
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                pictures += Picture(3, o.outMimeType ?: "image", o.outWidth, o.outHeight, bytes.size)
            }

            val (mime, format) = trackFormat(file)
            val rate = format?.int(MediaFormat.KEY_SAMPLE_RATE) ?: 0
            val channels = format?.int(MediaFormat.KEY_CHANNEL_COUNT) ?: 0
            var bits = 0
            if (Build.VERSION.SDK_INT >= 31) {
                bits = key(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() ?: 0
            }
            val (label, lossless) = when (mime) {
                "audio/alac" -> "ALAC" to true
                "audio/flac" -> "FLAC" to true
                "audio/mp4a-latm" -> "AAC" to false
                "audio/vorbis" -> "OGG" to false
                "audio/opus" -> "OPUS" to false
                "audio/mpeg" -> "MP3" to false
                else -> file.extension.uppercase() to false
            }
            return Probed(label, lossless, rate, if (lossless) bits.takeIf { it > 0 } ?: 16 else 0, channels, duration, bitrate.toInt(), tags, pictures)
        } finally {
            mmr.release()
        }
    }

    private fun MediaFormat.int(key: String): Int? = if (containsKey(key)) getInteger(key) else null

    private fun trackFormat(file: File): Pair<String?, MediaFormat?> {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.path)
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) return mime to f
            }
            null to null
        } catch (e: Exception) {
            null to null
        } finally {
            ex.release()
        }
    }

    /** Decodes the whole file through MediaCodec and runs the same analysis the FLAC path does. */
    fun deep(file: File, cancelled: () -> Boolean): DeepResult {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(file.path)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    track = i
                    format = f
                    break
                }
            }
            if (track < 0 || format == null) return DeepResult(null, 0, 0, 0, -1, "no audio track")
            ex.selectTrack(track)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            var rate = format.int(MediaFormat.KEY_SAMPLE_RATE) ?: 44100
            var channels = format.int(MediaFormat.KEY_CHANNEL_COUNT) ?: 2
            var float = false
            var analyzer: Analyzer? = null
            var ch = Array(channels) { IntArray(0) }
            var decoded = 0L
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            var loops = 0
            while (true) {
                if (++loops and 63 == 0 && cancelled()) throw CancellationException()
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        rate = f.int(MediaFormat.KEY_SAMPLE_RATE) ?: rate
                        channels = f.int(MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                        float = f.int(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    o >= 0 -> {
                        val buf = codec.getOutputBuffer(o)!!.order(ByteOrder.nativeOrder())
                        buf.position(info.offset)
                        buf.limit(info.offset + info.size)
                        val a = analyzer ?: Analyzer(rate, channels, if (float) 24 else 16).also { analyzer = it }
                        val frames = info.size / (channels * if (float) 4 else 2)
                        if (ch.size != channels || ch[0].size < frames) ch = Array(channels) { IntArray(frames) }
                        if (float) {
                            val fb = buf.asFloatBuffer()
                            for (s in 0 until frames) for (c in 0 until channels) {
                                ch[c][s] = (fb.get() * 8388607f).toInt().coerceIn(-8388608, 8388607)
                            }
                        } else {
                            val sb = buf.asShortBuffer()
                            for (s in 0 until frames) for (c in 0 until channels) ch[c][s] = sb.get().toInt()
                        }
                        a.feed(ch, frames)
                        decoded += frames
                        codec.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            val result = analyzer?.finish() ?: return DeepResult(null, 0, 0, 0, -1, "no audio decoded")
            return DeepResult(result, 0, decoded, 0, -1, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return DeepResult(null, 0, 0, 0, -1, e.message ?: e.javaClass.simpleName)
        } finally {
            try {
                codec?.stop()
            } catch (_: Exception) {
            }
            codec?.release()
            ex.release()
        }
    }
}
