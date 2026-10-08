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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.remember
import com.songaudit.analysis.Issue
import com.songaudit.fix.Fixes
import com.songaudit.fix.Shrink
import com.songaudit.ui.components.Action
import com.songaudit.ui.components.Actions
import com.songaudit.ui.theme.BrutalButton
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
    var chosen by rememberSaveable { mutableStateOf<Int?>(null) }
    val issues = if (damaged) listOf(Issue.DAMAGED) else FAKE_FILTERS
    val all = Issue.mask(issues)
    val source = if (damaged) lib.damaged else lib.fakes
    // Findings the person kept: off the list, one tap away from going back on it.
    val kept = lib.albums.filter { a -> a.tracks.any { it.issues and it.accepted and all != 0 } }
    val counts = issues.associateWith { i -> source.count { a -> a.count(i) > 0 } }
    // A filter whose last album was just dealt with falls back to all.
    val filter = chosen?.takeIf { f -> if (f == KEPT) kept.isNotEmpty() else counts.any { (i, n) -> i.bit == f && n > 0 } }
    val shown = when (filter) {
        null -> source
        KEPT -> kept
        else -> source.filter { a -> a.tracks.any { it.open and filter != 0 } }
    }
    val partial = lib.analysed < lib.tracks.size
    val mask = if (filter == null || filter == KEPT) all else filter
    val flagged = shown.flatMap { a ->
        a.tracks.filter { t -> if (filter == KEPT) t.issues and t.accepted and mask != 0 else t.open and mask != 0 }
    }
    var sheet by rememberSaveable { mutableStateOf(false) }

    Page(
        title = if (damaged) "Damaged" else "Not what it says",
        subtitle = Doctor.count(source.size, "album") + if (partial) " · ${n(lib.analysed)} of ${n(lib.tracks.size)} tracks listened" else "",
        onBack = pop,
        bottom = if (flagged.isEmpty()) null else {
            {
                BrutalButton(
                    "${Doctor.count(flagged.size, "track")} · what to do",
                    onClick = { sheet = true },
                    fill = if (damaged) Grid.Red else Grid.Yellow,
                    contentColor = if (damaged) Grid.Paper else Grid.Ink,
                )
            }
        },
    ) {
        val chips = listOf<Pair<Int?, String>>(null to "All") +
            (if (damaged) emptyList() else issues.filter { counts.getValue(it) > 0 }.map { it.bit to "${Look.label(it)} ${counts.getValue(it)}" }) +
            (if (kept.isEmpty()) emptyList() else listOf(KEPT to "Kept ${kept.size}"))
        if (chips.size > 1) Chips(chips, filter) { chosen = it }
        if (shown.isEmpty()) {
            Empty(
                "Nothing here",
                when {
                    lib.analysed == 0 -> "These are found by listening. Run a scan and leave it going."
                    damaged -> "Every track listened to so far plays as written."
                    else -> "Every track listened to so far is what it says."
                },
            )
        } else {
            LazyColumn(contentPadding = PaddingValues(vertical = GridTokens.Gap)) {
                items(shown, key = { it.folder }) { album ->
                    AlbumRow(album, badgesFor(album, issues, kept = filter == KEPT)) { push(Route.Album(album.folder)) }
                }
            }
        }
    }

    if (sheet) {
        val maybe = flagged.count { it.flags(Issue.MAYBE_LOSSY) }
        Actions(
            title = Doctor.count(flagged.size, "track") + " · " + Doctor.mb(flagged.sumOf { it.size }),
            text = if (maybe > 0) "${Doctor.count(maybe, "track")} only maybe lossy: worth a listen before deciding." else null,
            actions = trackActions(vm, flagged, mask),
            onDismiss = { sheet = false },
        )
    }
}

/**
 * What can be done with tracks flagged for [mask]: shrink those that are
 * bigger than they are, set them aside, or keep them as they are.
 */
fun trackActions(vm: AuditViewModel, tracks: List<Track>, mask: Int, after: () -> Unit = {}): List<Action> {
    val open = tracks.filter { it.open and mask != 0 }
    val kept = tracks.filter { it.issues and it.accepted and mask != 0 }
    val shrinkable = tracks.filter { Shrink.target(it) != null }
    val out = ArrayList<Action>()
    if (shrinkable.isNotEmpty()) {
        val to = shrinkable.mapNotNull { Shrink.target(it)?.toString() }.distinct().joinToString(" or ")
        out += Action(
            "Shrink ${shrinkable.size} to true size",
            "Each is written again as FLAC $to, the resolution it really has, and checked sample by sample " +
                "before it takes the old one's place. The originals wait in quarantine until you empty it.",
            Grid.Yellow,
        ) {
            vm.shrink(shrinkable)
            after()
        }
    }
    if (open.isNotEmpty()) {
        out += Action(
            "Set aside ${open.size} · ${Doctor.mb(open.sumOf { it.size })}",
            "Moved to the quarantine folder on the same card. Put back any time until you empty it.",
            Grid.Red,
        ) {
            vm.setAside(open)
            after()
        }
        out += Action(
            if (open.size == 1) "Keep as it is" else "Keep as they are",
            "Off this list. What was found stays on each track's page.",
            Grid.Paper,
        ) { vm.keep(open, mask) }
    }
    if (kept.isNotEmpty()) {
        out += Action("Back on the list · ${kept.size}", "You kept ${if (kept.size == 1) "this" else "these"} as found; list again.", Grid.Paper) {
            vm.keep(kept, mask, keep = false)
        }
    }
    return out
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
    val fixable = findings.map { it.problem }.filter { it in Fixes.FIXABLE }
    val flagged = album.tracks.filter { it.issues != 0 }
    var sheet by rememberSaveable { mutableStateOf(false) }
    Page(
        title = album.title,
        subtitle = album.artist,
        onBack = pop,
        bottom = { BrutalButton("What to do", onClick = { sheet = true }) },
    ) {
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
                    if (fixable.isNotEmpty()) {
                        Caption(
                            "Fix tags & covers →",
                            color = Grid.Blue,
                            modifier = Modifier
                                .clickable(remember { MutableInteractionSource() }, indication = null) {
                                    push(Route.Fix(listOf(folder), fixable.toSet()))
                                }
                                .padding(vertical = GridTokens.Gap),
                        )
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

    if (sheet) {
        val actions = trackActions(vm, flagged, ALL)
        val whole = Action(
            "Set aside album",
            "All ${Doctor.count(album.tracks.size, "track")}, ${Doctor.mb(album.size)}, with the folder's cover and anything else in it, " +
                "moved to the quarantine. Put back any time until you empty it.",
            Grid.Red,
        ) {
            vm.setAside(album.tracks)
            pop()
        }
        // When every track is flagged, setting aside the flagged ones is setting aside the album.
        val list = if (flagged.size == album.tracks.size) actions.filterNot { it.label.startsWith("Set aside") } + whole
        else actions + whole
        Actions(
            title = album.title,
            text = when {
                flagged.isEmpty() -> null
                flagged.size == album.tracks.size -> "Every track has findings."
                else -> "${flagged.size} of ${album.tracks.size} tracks have findings."
            },
            actions = list,
            onDismiss = { sheet = false },
        )
    }
}

private val ALL = Issue.mask(Issue.entries)

/** The filter key for findings the person kept. */
private const val KEPT = -1

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
    var sheet by rememberSaveable { mutableStateOf(false) }
    Page(
        title = t.title ?: t.name,
        subtitle = listOfNotNull(t.artist, t.album).joinToString(" · "),
        onBack = pop,
        bottom = { BrutalButton("What to do", onClick = { sheet = true }) },
    ) {
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
            if (t.issues and t.accepted != 0) {
                Spacer(Modifier.height(GridTokens.Gap))
                Caption("You chose to keep it as it is", color = Grid.Ink)
            }
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

    if (sheet) {
        val actions = trackActions(vm, listOf(t), t.issues, after = pop).ifEmpty {
            listOf(
                Action("Set aside · ${Doctor.mb(t.size)}", "Moved to the quarantine folder. Put back any time until you empty it.", Grid.Red) {
                    vm.setAside(listOf(t))
                    pop()
                },
            )
        }
        Actions(title = t.title ?: t.name, text = null, actions = actions, onDismiss = { sheet = false })
    }
}

fun minutes(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
    else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
}
