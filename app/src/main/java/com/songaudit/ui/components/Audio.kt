package com.songaudit.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.songaudit.analysis.Issue
import com.songaudit.analysis.Verdict
import com.songaudit.library.Album
import com.songaudit.library.Doctor
import com.songaudit.library.Track
import com.songaudit.scan.Scanner
import com.songaudit.ui.theme.Caption
import com.songaudit.ui.theme.Grid
import com.songaudit.ui.theme.GridTokens
import java.util.Locale
import kotlin.math.roundToInt

/** How each finding looks: a word, a colour, a shape. */
object Look {
    fun label(issue: Issue): String = when (issue) {
        Issue.DAMAGED -> "Damaged"
        Issue.LOSSY_SOURCE -> "From lossy"
        Issue.MAYBE_LOSSY -> "Maybe lossy"
        Issue.UPSAMPLED -> "Upsampled"
        Issue.PADDED -> "16-bit inside"
        Issue.REENCODED -> "Re-encoded"
    }

    fun fill(issue: Issue): Color = when (issue) {
        Issue.DAMAGED -> Grid.Red
        Issue.MAYBE_LOSSY -> Grid.Paper
        else -> Grid.Yellow
    }

    /** One plain paragraph on what was found in a track and what it means. */
    fun explain(t: Track): String {
        if (t.probeError != null) return "The header could not be read: ${t.probeError}."
        if (!t.analysed) return "Not listened to yet. Its tags have been read; the audio is checked during the next scan."
        if (t.deepError == Scanner.NOT_DECODED) return "${t.format} is not decoded on Android, so only its tags were checked."
        val parts = ArrayList<String>()
        if (t.has(Issue.DAMAGED)) {
            parts += when {
                t.deepError != null -> "It could not be decoded: ${t.deepError}."
                t.truncated -> "It is cut short: the file ends before the audio its header promises."
                t.frameErrors > 0 -> "${Doctor.count(t.frameErrors, "frame")} failed the checksum and could not be played as written."
                t.md5Match == 0 -> "The audio does not match the MD5 signature the encoder stored in the file."
                else -> "The file is damaged."
            }
        }
        if (t.has(Issue.LOSSY_SOURCE)) {
            parts += "The spectrum stops dead at ${Verdict.khz(t.cutoffHz)}, the way a ${Verdict.likeBitrate(t.cutoffHz)} MP3 does. " +
                "The ${t.format} is lossless; what went into it was not."
        }
        if (t.has(Issue.MAYBE_LOSSY)) {
            parts += "A wall at ${Verdict.khz(t.cutoffHz)} is typical of a 256–320 kbps MP3 or AAC, " +
                "but some masters are cut there too. Worth a listen."
        }
        if (t.has(Issue.UPSAMPLED)) {
            parts += if (t.cutoffHz in 1..Verdict.UPSAMPLED_HZ) {
                "Nothing real above ${Verdict.khz(t.cutoffHz)}: this is a CD or 48 kHz recording in a ${Track.khz(t.sampleRate)} kHz file."
            } else {
                String.format(Locale.US, "The level falls %.0f dB across the CD line and stays down: a 44.1 or 48 kHz recording in a %s kHz file.", t.ultrasonicDb, Track.khz(t.sampleRate))
            }
        }
        if (t.has(Issue.PADDED)) parts += "The low ${t.bits - t.effectiveBits} bits of every sample are zero: ${t.effectiveBits}-bit audio in a ${t.bits}-bit file."
        if (t.has(Issue.REENCODED)) parts += "A ${t.bitrate} kbps file that cuts off at ${Verdict.khz(t.cutoffHz)} was made from a ${Verdict.likeBitrate(t.cutoffHz)} one."
        if (parts.isEmpty()) {
            parts += if (t.lossless) {
                "Nothing found. " + (if (t.md5Match == 1) "Every sample matches the file's own signature, and the " else "The ") +
                    "spectrum carries on to the top."
            } else {
                "Nothing found."
            }
        }
        return parts.joinToString(" ")
    }
}

/** An album in a list: artist above, title, what it claims, and what was found. */
@Composable
fun AlbumRow(album: Album, badges: List<Pair<String, Color>>, detail: String? = null, onClick: () -> Unit) {
    TapSlab(onClick, Modifier.fillMaxWidth().padding(horizontal = GridTokens.Page, vertical = 4.dp)) {
        Caption(album.artist)
        Text(
            album.title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            color = Grid.Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Caption("${album.quality} · ${Doctor.count(album.tracks.size, "track")}")
        if (badges.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((text, fill) in badges.take(3)) Badge(text, fill)
            }
        }
        if (detail != null) {
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Grid.InkSoft)
        }
    }
}

fun badgesFor(album: Album, issues: List<Issue>, kept: Boolean = false): List<Pair<String, Color>> =
    issues.mapNotNull { i ->
        val n = if (kept) album.tracks.count { it.has(i) && it.accepted and i.bit != 0 } else album.count(i)
        if (n == 0) null else (if (n == album.tracks.size) Look.label(i) else "${Look.label(i)} $n") to Look.fill(i)
    }

/**
 * The averaged spectrum, ink on paper, with the wall marked in red. This is
 * the picture people post on forums to argue about a file; here it comes
 * with the argument already made.
 */
@Composable
fun Spectrum(levels: ByteArray, sampleRate: Int, cutoffHz: Int, modifier: Modifier = Modifier) {
    val nyquist = sampleRate / 2f
    val floor = 140f
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(Grid.Paper, RoundedCornerShape(0.dp))
                .border(GridTokens.Border, Grid.Ink, RoundedCornerShape(0.dp))
                .padding(GridTokens.Border),
        ) {
            Canvas(Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height
                // A thin rule every 5 kHz.
                var khz = 5000f
                while (khz < nyquist) {
                    val x = w * khz / nyquist
                    drawLine(Grid.SquareDark, Offset(x, 0f), Offset(x, h), strokeWidth = 1.dp.toPx())
                    khz += 5000f
                }
                val path = Path().apply {
                    moveTo(0f, h)
                    for ((i, b) in levels.withIndex()) {
                        val db = (b.toInt() and 0xFF).toFloat().coerceAtMost(floor)
                        lineTo(w * (i + 0.5f) / levels.size, h * db / floor)
                    }
                    lineTo(w, h)
                    close()
                }
                drawPath(path, Grid.Ink)
                if (sampleRate >= 88200) {
                    val x = w * 22050f / nyquist
                    drawLine(
                        Grid.Blue, Offset(x, 0f), Offset(x, h), strokeWidth = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)),
                    )
                }
                if (cutoffHz > 0) {
                    val x = w * cutoffHz / nyquist
                    drawLine(Grid.Red, Offset(x, 0f), Offset(x, h), strokeWidth = 3.dp.toPx())
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Caption("0")
            Spacer(Modifier.weight(1f))
            if (cutoffHz > 0) {
                Caption("Wall ${Verdict.khz(cutoffHz)}", color = Grid.Red)
                Spacer(Modifier.weight(1f))
            }
            Caption("${(nyquist / 1000).roundToInt()} kHz")
        }
    }
}
