package com.songaudit.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songaudit.analysis.Issue
import com.songaudit.library.Doctor
import com.songaudit.library.Track
import com.songaudit.ui.AuditViewModel
import com.songaudit.ui.FAKE_FILTERS
import com.songaudit.ui.Library
import com.songaudit.ui.Route
import com.songaudit.ui.components.AlbumRow
import com.songaudit.ui.components.Badge
import com.songaudit.ui.components.Chips
import com.songaudit.ui.components.Empty
import com.songaudit.ui.components.Fact
import com.songaudit.ui.components.Look
import com.songaudit.ui.components.Page
import com.songaudit.ui.components.Spectrum
import com.songaudit.ui.components.TapSlab
import com.songaudit.ui.components.badgesFor
import com.songaudit.ui.theme.BrutalRule
import com.songaudit.ui.theme.Caption
import com.songaudit.ui.theme.Grid
import com.songaudit.ui.theme.GridTokens
import java.util.Locale
import kotlin.math.roundToInt

/** Damaged albums, or albums that are not what their files claim, filterable by finding. */
@Composable
fun AlbumsScreen(vm: AuditViewModel, damaged: Boolean, push: (Route) -> Unit, pop: () -> Unit) {
    val lib = vm.library.collectAsStateWithLifecycle().value ?: Library.EMPTY
    var filter by rememberSaveable { mutableStateOf<Int?>(null) }
    val issues = if (damaged) listOf(Issue.DAMAGED) else FAKE_FILTERS
    val source = if (damaged) lib.damaged else lib.fakes
    val shown = filter?.let { bit -> source.filter { a -> a.tracks.any { it.issues and bit != 0 } } } ?: source
    val partial = lib.analysed < lib.tracks.size

    Page(
        title = if (damaged) "Damaged" else "Not what it says",
        subtitle = "${n(source.size)} albums" + if (partial) " · ${n(lib.analysed)} of ${n(lib.tracks.size)} tracks listened" else "",
        onBack = pop,
    ) {
        if (!damaged) {
            val counts = FAKE_FILTERS.associateWith { i -> source.count { a -> a.count(i) > 0 } }
            Chips(
                listOf<Pair<Int?, String>>(null to "All") +
                    FAKE_FILTERS.filter { counts.getValue(it) > 0 }.map { it.bit to "${Look.label(it)} ${counts.getValue(it)}" },
                filter,
            ) { filter = it }
        }
        if (shown.isEmpty()) {
            Empty(
                "Nothing here",
                if (lib.analysed == 0) "These are found by listening. Run a scan and leave it going." else "Every track listened to so far is what it says.",
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(vertical = GridTokens.Gap)) {
                items(shown, key = { it.folder }) { album ->
                    AlbumRow(album, badgesFor(album, issues)) { push(Route.Album(album.folder)) }
                }
            }
        }
    }
}

/** One album: what it claims, its DR, and every track with what was found in it. */
@Composable
fun AlbumScreen(vm: AuditViewModel, folder: String, push: (Route) -> Unit, pop: () -> Unit) {
    val lib = vm.library.collectAsStateWithLifecycle().value ?: Library.EMPTY
    val album = lib.byFolder[folder]
    if (album == null) {
        Page("Album", onBack = pop) { Empty("Gone", "This folder is no longer in the library.") }
        return
    }
    val findings = lib.findings.filter { it.album.folder == folder }
    Page(title = album.title, subtitle = album.artist, onBack = pop) {
        LazyColumn(contentPadding = PaddingValues(bottom = GridTokens.GapWide)) {
            item {
                Column(Modifier.padding(horizontal = GridTokens.Page, vertical = GridTokens.GapWide)) {
                    Fact("Format", album.quality)
                    Fact("Tracks", "${album.tracks.size} · ${minutes(album.durationMs)}")
                    Fact("Size", Doctor.mb(album.size))
                    if (!album.dr.isNaN()) Fact("Dynamic range", "DR${album.dr.roundToInt()}")
                    for (f in findings) {
                        Spacer(Modifier.height(GridTokens.Gap))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Badge(f.problem.title, Grid.Paper)
                            Spacer(Modifier.width(GridTokens.Gap))
                            Text(f.detail, style = MaterialTheme.typography.bodySmall, color = Grid.InkSoft)
                        }
                    }
                    Spacer(Modifier.height(GridTokens.Gap))
                    Text(folder, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = Grid.InkSoft)
                }
                BrutalRule()
                Spacer(Modifier.height(GridTokens.Gap))
            }
            items(album.tracks, key = { it.id }) { t -> TrackRow(t) { push(Route.Track(t.id)) } }
        }
    }
}

@Composable
private fun TrackRow(t: Track, onClick: () -> Unit) {
    TapSlab(onClick, Modifier.fillMaxWidth().padding(horizontal = GridTokens.Page, vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (t.track > 0) String.format(Locale.US, "%02d", t.track) else "··",
                style = MaterialTheme.typography.titleLarge,
                color = Grid.InkSoft,
            )
            Spacer(Modifier.width(GridTokens.GapWide))
            Column(Modifier.weight(1f)) {
                Text(
                    t.title ?: t.name,
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    color = Grid.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Caption(
                    t.quality + if (!t.dr.isNaN()) " · DR${t.dr.roundToInt()}" else if (!t.analysed) " · not listened" else "",
                )
            }
            val first = t.issueList.firstOrNull()
            if (first != null) {
                Spacer(Modifier.width(GridTokens.Gap))
                Badge(Look.label(first), Look.fill(first))
            }
        }
    }
}

/** A track in full: the spectrum, the facts, and the finding in plain words. */
@Composable
fun TrackScreen(vm: AuditViewModel, id: Long, pop: () -> Unit) {
    val lib = vm.library.collectAsStateWithLifecycle().value ?: Library.EMPTY
    val t = lib.tracks.firstOrNull { it.id == id }
    if (t == null) {
        Page("Track", onBack = pop) { Empty("Gone", "This file is no longer in the library.") }
        return
    }
    val spectrum by produceState<ByteArray?>(null, id, t.deepVersion) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.spectrum(id) }
    }
    Page(title = t.title ?: t.name, subtitle = listOfNotNull(t.artist, t.album).joinToString(" · "), onBack = pop) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = GridTokens.Page, vertical = GridTokens.GapWide),
        ) {
            if (t.issueList.isNotEmpty()) {
                Row {
                    for (i in t.issueList) {
                        Badge(Look.label(i), Look.fill(i))
                        Spacer(Modifier.width(GridTokens.Gap))
                    }
                }
                Spacer(Modifier.height(GridTokens.Gap))
            }
            Text(Look.explain(t), style = MaterialTheme.typography.bodyLarge, color = Grid.Ink)
            Spacer(Modifier.height(GridTokens.GapWide))
            spectrum?.let {
                Caption("Average spectrum")
                Spacer(Modifier.height(GridTokens.Gap))
                Spectrum(it, t.sampleRate, t.cutoffHz)
                Spacer(Modifier.height(GridTokens.GapWide))
            }
            Fact("Claims", t.quality)
            if (t.analysed && t.lossless && t.bits >= 16 && t.effectiveBits > 0) {
                Fact("Bits in use", "${t.effectiveBits} of ${t.bits}", if (t.effectiveBits < t.bits && t.bits >= 24) Grid.Red else Grid.Ink)
            }
            if (t.bitrate > 0) Fact("Bitrate", "${t.bitrate} kbps")
            Fact("Length", minutes(t.durationMs))
            Fact("Size", Doctor.mb(t.size))
            if (!t.dr.isNaN()) Fact("Dynamic range", "DR${t.dr.roundToInt()}")
            if (!t.peakDb.isNaN()) Fact("Peak", String.format(Locale.US, "%.1f dBFS", t.peakDb))
            if (t.format == "FLAC" && t.analysed) {
                Fact(
                    "MD5 signature",
                    when (t.md5Match) {
                        1 -> "Matches"
                        0 -> "Does not match"
                        else -> "None stored"
                    },
                    if (t.md5Match == 0) Grid.Red else Grid.Ink,
                )
                Fact("Frame errors", t.frameErrors.toString(), if (t.frameErrors > 0) Grid.Red else Grid.Ink)
            }
            if (t.pictures > 0) {
                Fact(
                    "Cover",
                    Doctor.mb(t.pictureBytes.toLong()) + if (t.pictureWidth > 0) " · ${t.pictureWidth}×${t.pictureHeight}" else "",
                )
            }
            Spacer(Modifier.height(GridTokens.GapWide))
            Text(t.path, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = Grid.InkSoft)
        }
    }
}

fun minutes(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}
