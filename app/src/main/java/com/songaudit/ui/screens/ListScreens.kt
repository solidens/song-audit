package com.songaudit.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songaudit.fix.Fixes
import com.songaudit.library.Copy
import com.songaudit.library.Db
import com.songaudit.library.Doctor
import com.songaudit.library.DupGroup
import com.songaudit.library.Problem
import com.songaudit.ui.AuditViewModel
import com.songaudit.ui.Library
import com.songaudit.ui.Route
import com.songaudit.ui.components.AlbumRow
import com.songaudit.ui.components.Badge
import com.songaudit.ui.components.Chips
import com.songaudit.ui.components.Confirm
import com.songaudit.ui.components.Empty
import com.songaudit.ui.components.Look
import com.songaudit.ui.components.Page
import com.songaudit.ui.components.TapSlab
import com.songaudit.ui.theme.BrutalButton
import com.songaudit.ui.theme.Caption
import com.songaudit.ui.theme.Grid
import com.songaudit.ui.theme.GridTokens
import kotlin.math.roundToInt

// -- Duplicates ---------------------------------------------------------------

@Composable
fun DuplicatesScreen(vm: AuditViewModel, push: (Route) -> Unit, pop: () -> Unit) {
    val lib = vm.library.collectAsStateWithLifecycle().value ?: Library.EMPTY
    val picks by vm.keep.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf(false) }
    val groups = lib.duplicates
    val copies = groups.sumOf { it.copies.size - 1 }
    val bytes = groups.sumOf { g -> g.copies.filter { it !== vm.kept(g, picks) }.sumOf { it.size } }

    Page(
        title = "Duplicates",
        subtitle = if (groups.isEmpty()) "Nothing to win back" else "${n(groups.size)} groups · ${Doctor.mb(lib.reclaimable)} to win back",
        onBack = pop,
        bottom = if (groups.isEmpty()) null else {
            {
                BrutalButton(
                    "Set aside $copies · ${Doctor.mb(bytes)}",
                    onClick = { confirm = true },
                    fill = Grid.Blue,
                    contentColor = Grid.Paper,
                )
            }
        },
    ) {
        if (groups.isEmpty() && lib.otherReleases.isEmpty()) {
            Empty("No duplicates", "No two files in the library are the same file, the same audio or the same recording.")
            return@Page
        }
        LazyColumn(contentPadding = PaddingValues(vertical = GridTokens.Gap)) {
            if (lib.analysed < lib.tracks.size) {
                item {
                    Caption(
                        "Until a track is listened to it is matched by title and length only",
                        modifier = Modifier.padding(horizontal = GridTokens.Page, vertical = GridTokens.Gap),
                    )
                }
            }
            items(groups, key = { it.id }) { g -> GroupRow(g, vm.kept(g, picks)) { push(Route.Group(g.id)) } }
            if (lib.otherReleases.isNotEmpty()) {
                item {
                    Column(Modifier.padding(horizontal = GridTokens.Page, vertical = GridTokens.GapWide)) {
                        Caption("Also on another release", color = Grid.Ink)
                        Text(
                            "The same recording on a different album: a single and its album, a song and its compilation. " +
                                "Not set aside unless you choose to.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Grid.InkSoft,
                        )
                    }
                }
                items(lib.otherReleases, key = { it.id }) { g -> GroupRow(g, vm.kept(g, picks)) { push(Route.Group(g.id)) } }
            }
        }
    }

    if (confirm) {
        Confirm(
            title = "Set aside $copies copies?",
            text = "They move to a hidden quarantine folder on the same card: ${Doctor.mb(bytes)}, freed only when you empty it. " +
                "The kept copy of each group stays where it is. Rescan your player's library afterwards.",
            action = "Set aside",
            fill = Grid.Blue,
            onConfirm = { vm.quarantine(groups) },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun GroupRow(g: DupGroup, kept: Copy, onClick: () -> Unit) {
    TapSlab(onClick, Modifier.fillMaxWidth().padding(horizontal = GridTokens.Page, vertical = 4.dp)) {
        Caption(g.kind.label + if (g.albums) " · albums" else " · tracks")
        val head = if (g.albums) kept.album.title else kept.tracks[0].title ?: kept.tracks[0].name
        Text(head, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = Grid.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Caption(kept.album.artist)
        Spacer(Modifier.height(6.dp))
        if (g.otherRelease) {
            // Nothing is suggested here, so there is no "keep": just where else it appears.
            Caption("Also on " + g.copies.filter { it !== kept }.joinToString(" · ") { it.album.title })
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge("Keep", Grid.Ink)
                Spacer(Modifier.width(6.dp))
                Caption(kept.album.quality, color = Grid.Ink, modifier = Modifier.weight(1f))
                val others = g.copies.filter { it !== kept }
                Caption("+${others.size} · ${Doctor.mb(others.sumOf { it.size })}", color = Grid.Blue)
            }
        }
    }
}

/** One group: every copy, the suggestion latched, any of them choosable. */
@Composable
fun GroupScreen(vm: AuditViewModel, id: String, push: (Route) -> Unit, pop: () -> Unit) {
    val lib = vm.library.collectAsStateWithLifecycle().value ?: Library.EMPTY
    val picks by vm.keep.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf(false) }
    val g = lib.groups.firstOrNull { it.id == id }
    if (g == null) {
        Page("Duplicates", onBack = pop) { Empty("Done", "These copies are no longer in the library.") }
        return
    }
    val kept = vm.kept(g, picks)
    val others = g.copies.filter { it !== kept }
    val bytes = others.sumOf { it.size }
    val title = if (g.albums) kept.album.title else kept.tracks[0].title ?: kept.tracks[0].name

    Page(
        title = title,
        subtitle = g.kind.label,
        onBack = pop,
        bottom = {
            BrutalButton("Set aside ${others.size} · ${Doctor.mb(bytes)}", onClick = { confirm = true }, fill = Grid.Blue, contentColor = Grid.Paper)
        },
    ) {
        LazyColumn(contentPadding = PaddingValues(vertical = GridTokens.Gap)) {
            item {
                Column(Modifier.padding(horizontal = GridTokens.Page, vertical = GridTokens.Gap)) {
                    Text(
                        if (kept === g.keep) g.reason else "Your choice",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Grid.Ink,
                    )
                    Caption("Tap a copy to keep it instead")
                }
            }
            items(g.copies, key = { it.path }) { c ->
                CopyCard(c, keep = c === kept, onKeep = { vm.choose(g, c) }, onOpen = { push(Route.Album(c.folder)) })
            }
        }
    }

    if (confirm) {
        Confirm(
            title = "Set aside ${others.size}?",
            text = "Moved to the quarantine folder, not deleted. You can put them back until you empty it.",
            action = "Set aside",
            fill = Grid.Blue,
            onConfirm = {
                vm.quarantine(listOf(g))
                pop()
            },
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun CopyCard(c: Copy, keep: Boolean, onKeep: () -> Unit, onOpen: () -> Unit) {
    val ink = if (keep) Grid.Paper else Grid.Ink
    val soft = if (keep) Grid.Paper.copy(alpha = 0.75f) else Grid.InkSoft
    TapSlab(onKeep, Modifier.fillMaxWidth().padding(horizontal = GridTokens.Page, vertical = 4.dp), fill = if (keep) Grid.Ink else Grid.Paper) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (keep) "KEEP" else "SET ASIDE",
                style = MaterialTheme.typography.titleLarge,
                color = if (keep) Grid.Yellow else Grid.Blue,
                modifier = Modifier.weight(1f),
            )
            Text(Doctor.mb(c.size), style = MaterialTheme.typography.titleLarge, color = ink)
        }
        Spacer(Modifier.height(4.dp))
        val album = c.album
        Text(
            album.quality + " · " + Doctor.count(c.tracks.size, "track") + if (!album.dr.isNaN()) " · DR${album.dr.roundToInt()}" else "",
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            color = ink,
        )
        val issues = c.tracks.flatMap { it.issueList }.distinct()
        if (issues.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Row {
                for (i in issues.take(3)) {
                    Badge(Look.label(i), Look.fill(i))
                    Spacer(Modifier.width(4.dp))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(c.path, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = soft, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Caption(
            "Open album →",
            color = if (keep) Grid.Yellow else Grid.Blue,
            modifier = Modifier
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onOpen)
                .padding(vertical = 6.dp),
        )
    }
}

// -- Tags & covers ------------------------------------------------------------

@Composable
fun DoctorScreen(vm: AuditViewModel, push: (Route) -> Unit, pop: () -> Unit) {
    val lib = vm.library.collectAsStateWithLifecycle().value ?: Library.EMPTY
    var filter by rememberSaveable { mutableStateOf<Problem?>(null) }
    val counts = Problem.entries.associateWith { p -> lib.findings.count { it.problem == p } }
    val shown = lib.findings.filter { filter == null || it.problem == filter }
    val fixable = shown.filter { it.problem in Fixes.FIXABLE }
    val folders = fixable.map { it.album.folder }.distinct()

    Page(
        title = "Tags & covers",
        subtitle = Doctor.count(lib.findings.map { it.album.folder }.distinct().size, "album"),
        onBack = pop,
        bottom = if (folders.isEmpty()) null else {
            {
                BrutalButton(
                    "Fix ${Doctor.count(folders.size, "album")}",
                    onClick = { push(Route.Fix(folders, fixable.map { it.problem }.toSet())) },
                    fill = Grid.Yellow,
                )
            }
        },
    ) {
        Chips(
            listOf<Pair<Problem?, String>>(null to "All") + Problem.entries.filter { counts.getValue(it) > 0 }.map { it to "${it.title} ${counts.getValue(it)}" },
            filter,
        ) { filter = it }
        if (shown.isEmpty()) {
            Empty("All tidy", "Every album has art, consistent tags and all its tracks.")
        } else if (filter == Problem.INCOMPLETE) {
            LazyColumn(contentPadding = PaddingValues(vertical = GridTokens.Gap)) {
                item {
                    Caption(
                        "Tracks that are not on the player cannot be fixed from here",
                        modifier = Modifier.padding(horizontal = GridTokens.Page, vertical = GridTokens.Gap),
                    )
                }
                items(shown, key = { it.album.folder + it.problem }) { f ->
                    AlbumRow(f.album, listOf(f.problem.title to Grid.Paper), f.detail) { push(Route.Album(f.album.folder)) }
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(vertical = GridTokens.Gap)) {
                items(shown, key = { it.album.folder + it.problem }) { f ->
                    AlbumRow(f.album, listOf(f.problem.title to Grid.Paper), f.detail) { push(Route.Album(f.album.folder)) }
                }
            }
        }
    }
}

// -- Quarantine ---------------------------------------------------------------

@Composable
fun QuarantineScreen(vm: AuditViewModel, pop: () -> Unit) {
    val lib = vm.library.collectAsStateWithLifecycle().value ?: Library.EMPTY
    var confirm by rememberSaveable { mutableStateOf(false) }
    val entries = lib.quarantine
    // One card per thing done: a set of duplicates, an album fixed, a batch shrunk.
    val batches = entries.groupBy { it.batch }.values.toList()
    Page(
        title = "Quarantine",
        subtitle = "${n(entries.size)} items · ${Doctor.mb(lib.quarantineSize)}",
        onBack = pop,
        bottom = if (entries.isEmpty()) null else {
            { BrutalButton("Empty · free ${Doctor.mb(lib.quarantineSize)}", onClick = { confirm = true }, fill = Grid.Red, contentColor = Grid.Paper) }
        },
    ) {
        if (entries.isEmpty()) {
            Empty("Empty", "Nothing is set aside.")
            return@Page
        }
        LazyColumn(contentPadding = PaddingValues(vertical = GridTokens.Gap)) {
            item {
                Text(
                    "Set aside, not deleted, and every fix as it was before. Put back what you want; empty the rest to free the space.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Grid.InkSoft,
                    modifier = Modifier.padding(horizontal = GridTokens.Page, vertical = GridTokens.Gap),
                )
            }
            items(batches, key = { it.first().batch }) { batch -> BatchCard(batch) { vm.restore(batch) } }
        }
    }
    if (confirm) {
        Confirm(
            title = "Empty the quarantine?",
            text = "${n(entries.size)} items, ${Doctor.mb(lib.quarantineSize)}, are deleted for good, and fixes can no longer be undone. " +
                "This cannot be undone.",
            action = "Delete for good",
            fill = Grid.Red,
            onConfirm = vm::emptyQuarantine,
            onDismiss = { confirm = false },
        )
    }
}

@Composable
private fun BatchCard(batch: List<Db.Moved>, onRestore: () -> Unit) {
    val kinds = batch.map { it.kind }.toSet()
    val kind = when {
        Db.Moved.REPLACED in kinds -> Db.Moved.REPLACED
        Db.Moved.TAGS in kinds || Db.Moved.ADDED in kinds -> Db.Moved.TAGS
        else -> Db.Moved.SET_ASIDE
    }
    // Labels are "what · Artist — Album · file" for fixes and "Artist — Album · file" for set-asides.
    val parts = batch.map { (it.label ?: it.original.substringAfterLast('/')).split(" · ") }
    val what = when (kind) {
        Db.Moved.TAGS -> "Tags and covers before a fix"
        Db.Moved.REPLACED -> "Originals before shrinking"
        else -> "Set aside"
    }
    val albums = parts.map { p -> if (kind == Db.Moved.SET_ASIDE) p.first() else p.getOrElse(1) { p.first() } }.distinct()
    val title = albums.take(2).joinToString(" · ") + if (albums.size > 2) " and ${albums.size - 2} more" else ""
    val size = batch.sumOf { it.size }
    TapSlab(null, Modifier.fillMaxWidth().padding(horizontal = GridTokens.Page, vertical = 4.dp)) {
        Caption(what, color = if (kind == Db.Moved.SET_ASIDE) Grid.InkSoft else Grid.Blue)
        Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = Grid.Ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Caption(Doctor.count(batch.size, "item") + " · " + Doctor.mb(size))
        if (batch.size == 1) {
            Text(batch[0].original, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = Grid.InkSoft, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(GridTokens.Gap))
        Caption(
            if (kind == Db.Moved.SET_ASIDE) "Put back →" else "Undo the fix →",
            color = Grid.Blue,
            modifier = Modifier
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onRestore)
                .padding(vertical = 6.dp),
        )
    }
}
