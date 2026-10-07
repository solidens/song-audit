package com.songaudit.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songaudit.library.Doctor
import com.songaudit.scan.Format
import com.songaudit.scan.Phase
import com.songaudit.scan.Progress
import com.songaudit.ui.AuditViewModel
import com.songaudit.ui.Library
import com.songaudit.ui.Options
import com.songaudit.ui.Route
import com.songaudit.ui.components.Bar
import com.songaudit.ui.components.Glyph
import com.songaudit.ui.components.Shape
import com.songaudit.ui.components.TapSlab
import com.songaudit.ui.theme.BrutalButton
import com.songaudit.ui.theme.BrutalChoice
import com.songaudit.ui.theme.BrutalRule
import com.songaudit.ui.theme.Caption
import com.songaudit.ui.theme.Grid
import com.songaudit.ui.theme.GridTokens
import java.util.Locale

@Composable
fun HomeScreen(vm: AuditViewModel, push: (Route) -> Unit) {
    val lib by vm.library.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val options by vm.options.collectAsStateWithLifecycle()
    val library = lib ?: Library.EMPTY
    // The progress notification needs permission from Android 13 on. The scan runs either way.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.scan() }
    val context = LocalContext.current
    val scan = {
        val ask = Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (ask) notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) else vm.scan()
    }

    // Scan is pinned to the bottom edge; only what is above it scrolls.
    Column(
        Modifier
            .fillMaxSize()
            .background(Grid.Paper)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = GridTokens.Page)
                .padding(top = GridTokens.GapWide),
        ) {
            Masthead()
            Spacer(Modifier.height(GridTokens.Gap))
            BrutalRule()
            Spacer(Modifier.height(GridTokens.Gap))
            Caption(
                if (library.tracks.isEmpty()) "Offline · nothing leaves the player"
                else "${n(library.tracks.size)} tracks · ${n(library.albums.size)} albums · ${Doctor.mb(library.size)}",
            )

            Spacer(Modifier.height(GridTokens.GapSection))
            Status(progress, library, options)

            Spacer(Modifier.height(GridTokens.GapSection))
            Caption("Findings")
            Spacer(Modifier.height(GridTokens.Gap))
            val partial = library.analysed < library.tracks.size
            val soFar = if (partial && library.analysed > 0) " so far" else ""
            Finding(
                Shape.SQUARE, Grid.Red, "Damaged",
                if (library.analysed == 0) "Found by listening" else "Errors, cut short, unreadable$soFar",
                library.damaged.size,
            ) { push(Route.Albums(damaged = true)) }
            Finding(
                Shape.TRIANGLE, Grid.Yellow, "Not what it says",
                if (library.analysed == 0) "Found by listening" else "Lossy, upsampled, padded$soFar",
                library.fakes.size,
            ) { push(Route.Albums(damaged = false)) }
            Finding(
                Shape.CIRCLE, Grid.Blue, "Duplicates",
                if (library.reclaimable > 0) "${Doctor.mb(library.reclaimable)} to win back" else "Files, audio, recordings",
                library.duplicates.size,
            ) { push(Route.Duplicates) }
            Finding(
                Shape.DIAMOND, Grid.Paper, "Tags & covers",
                "Art, tags, missing tracks",
                library.findings.map { it.album.folder }.distinct().size,
            ) { push(Route.Doctor) }
            if (library.quarantine.isNotEmpty()) {
                Finding(
                    Shape.SQUARE, Grid.Ink, "Quarantine",
                    "${Doctor.mb(library.quarantineSize)} set aside, not yet deleted",
                    library.quarantine.size,
                ) { push(Route.Quarantine) }
            }

            Spacer(Modifier.height(GridTokens.GapSection))
            Caption("Where to look")
            Spacer(Modifier.height(GridTokens.Gap))
            for (v in options.volumes) {
                Toggle(v.label, v.path !in options.skipped) { vm.toggleVolume(v.path) }
                Spacer(Modifier.height(GridTokens.Gap))
            }
            Toggle("Listen on charger only", options.onlyWhileCharging) { vm.toggleCharging() }
            Spacer(Modifier.height(GridTokens.GapWide))
        }

        BrutalRule()
        BrutalButton(
            label = if (progress.running) "Stop" else if (library.tracks.isEmpty()) "Scan" else "Scan again",
            onClick = if (progress.running) vm::stop else scan,
            fill = if (progress.running) Grid.Paper else Grid.Yellow,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GridTokens.Page)
                .padding(top = GridTokens.GapWide, bottom = GridTokens.Gap),
        )
    }
}

@Composable
private fun Masthead() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("SONG", style = MaterialTheme.typography.displayLarge, color = Grid.Ink)
        Spacer(Modifier.width(GridTokens.GapWide))
        // Red is the word below; these are the other two findings and the doctor.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Glyph(Shape.TRIANGLE, Grid.Yellow, size = 22.dp)
            Glyph(Shape.CIRCLE, Grid.Blue, size = 22.dp)
            Glyph(Shape.DIAMOND, Grid.Paper, size = 22.dp)
        }
    }
    Wordmark("AUDIT", Grid.Red)
}

/** What the scanner is doing now, or what the last one left. */
@Composable
private fun ColumnScope.Status(p: Progress, lib: Library, options: Options) {
    if (p.running) {
        Caption(if (p.waitingForCharger) "Waiting for the charger" else p.phase.label, color = Grid.Ink)
        Spacer(Modifier.height(GridTokens.Gap))
        val fraction = when {
            p.phase == Phase.LISTENING && p.bytesTotal > 0 -> p.bytesDone.toFloat() / p.bytesTotal
            p.total > 0 -> p.done.toFloat() / p.total
            else -> 0f
        }
        Bar(fraction)
        Spacer(Modifier.height(GridTokens.Gap))
        val count = when {
            p.phase == Phase.FINDING -> "${n(p.found)} files"
            p.total > 0 -> "${n(p.done)} of ${n(p.total)}" + (p.etaSeconds?.let { " · ${Format.duration(it)} left" } ?: "")
            else -> ""
        }
        if (count.isNotEmpty()) Caption(count)
        if (p.current.isNotEmpty()) {
            Text(p.current, style = MaterialTheme.typography.bodySmall, color = Grid.InkSoft, maxLines = 1)
        }
    } else if (p.error != null) {
        Caption("The scan stopped", color = Grid.Red)
        Text(p.error, style = MaterialTheme.typography.bodyLarge, color = Grid.InkSoft)
    } else if (lib.tracks.isEmpty()) {
        Text(
            "A scan reads every track's tags in a few minutes, then listens to each one in full. " +
                "The listen takes hours for a big library; leave the player on the charger overnight. " +
                "Stop whenever you like: the next scan carries on.",
            style = MaterialTheme.typography.bodyLarge,
            color = Grid.InkSoft,
        )
    } else {
        val left = lib.tracks.size - lib.analysed
        Caption(if (left == 0) "Every track listened to" else "${n(lib.analysed)} of ${n(lib.tracks.size)} listened to", color = Grid.Ink)
        Spacer(Modifier.height(GridTokens.Gap))
        Bar(lib.analysed.toFloat() / lib.tracks.size.coerceAtLeast(1))
        Spacer(Modifier.height(GridTokens.Gap))
        if (left > 0) Caption("Scan again to carry on")
        else if (options.lastScan > 0) Caption("Last scan ${ago(options.lastScan)}")
    }
}

@Composable
private fun Finding(shape: Shape, fill: Color, title: String, detail: String, count: Int, onClick: () -> Unit) {
    TapSlab(
        onClick = if (count > 0) onClick else null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = GridTokens.Gap),
        fill = Grid.Paper,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(shape, fill, size = 20.dp)
            Spacer(Modifier.width(GridTokens.GapWide))
            Column(Modifier.weight(1f)) {
                Text(title.uppercase(), style = MaterialTheme.typography.titleLarge, color = Grid.Ink, maxLines = 1)
                Text(detail.uppercase(), style = MaterialTheme.typography.bodySmall, color = Grid.InkSoft, maxLines = 2)
            }
            Spacer(Modifier.width(GridTokens.Gap))
            Text(
                n(count),
                style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Black),
                color = if (count > 0) Grid.Ink else Grid.SquareDark,
            )
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onToggle: () -> Unit) {
    BrutalChoice(
        selected = on,
        onSelect = onToggle,
        height = 52.dp,
        contentPadding = PaddingValues(horizontal = GridTokens.GapWide),
    ) {
        val ink = if (on) Grid.Paper else Grid.Ink
        Row(
            Modifier
                .fillMaxWidth()
                .align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label.uppercase(), style = MaterialTheme.typography.titleLarge, color = ink, maxLines = 1, modifier = Modifier.weight(1f))
            Text(if (on) "ON" else "OFF", style = MaterialTheme.typography.titleLarge, color = ink)
        }
    }
}

/** The first screen, until the app may read the files it is meant to check. */
@Composable
fun AccessScreen(onResult: () -> Unit) {
    val context = LocalContext.current
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onResult() }
    Column(
        Modifier
            .fillMaxSize()
            .background(Grid.Paper)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = GridTokens.Page)
                .padding(top = GridTokens.GapWide),
        ) {
            Masthead()
            Spacer(Modifier.height(GridTokens.Gap))
            BrutalRule()
            Spacer(Modifier.height(GridTokens.GapSection))
            Text(
                "To check your music the app reads every audio file on the player, and to set duplicates aside " +
                    "it moves them. Android calls that all files access.",
                style = MaterialTheme.typography.bodyLarge,
                color = Grid.Ink,
            )
            Spacer(Modifier.height(GridTokens.GapWide))
            Text(
                "Nothing leaves the device: the app has no internet permission. Nothing is deleted unless you empty the quarantine yourself.",
                style = MaterialTheme.typography.bodyLarge,
                color = Grid.InkSoft,
            )
        }
        BrutalRule()
        BrutalButton(
            label = "Allow file access",
            fill = Grid.Yellow,
            onClick = {
                if (Build.VERSION.SDK_INT >= 30) {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                    try {
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                } else {
                    legacy.launch(
                        arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = GridTokens.Page)
                .padding(top = GridTokens.GapWide, bottom = GridTokens.Gap),
        )
    }
}

/**
 * A word set as large as the display style allows and no larger than the
 * column, as on the game screens.
 */
@Composable
fun Wordmark(word: String, color: Color) {
    val style = MaterialTheme.typography.displayLarge
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val natural = measurer.measure(word, style).size.width
        val room = constraints.maxWidth
        val scale = if (natural > room) room / natural.toFloat() else 1f
        Text(
            word,
            style = style.copy(
                fontSize = style.fontSize * scale,
                lineHeight = style.lineHeight * scale,
                letterSpacing = style.letterSpacing * scale,
            ),
            color = color,
            maxLines = 1,
            softWrap = false,
        )
    }
}

fun n(v: Int): String = String.format(Locale.US, "%,d", v)

private fun ago(ms: Long): String {
    val minutes = (System.currentTimeMillis() - ms) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}
