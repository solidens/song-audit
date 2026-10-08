package com.songaudit.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songaudit.fix.AlbumFix
import com.songaudit.library.Doctor
import com.songaudit.library.Problem
import com.songaudit.ui.AuditViewModel
import com.songaudit.ui.components.Empty
import com.songaudit.ui.components.Page
import com.songaudit.ui.components.TapSlab
import com.songaudit.ui.theme.BrutalButton
import com.songaudit.ui.theme.Caption
import com.songaudit.ui.theme.Grid
import com.songaudit.ui.theme.GridTokens
import kotlinx.coroutines.launch

/**
 * Every change a fix would make, album by album, before a byte is written.
 * Nothing here is final: each file's old tags go to the quarantine first.
 */
@Composable
fun FixScreen(vm: AuditViewModel, folders: List<String>, problems: Set<Problem>, pop: () -> Unit) {
    val lib = vm.library.collectAsStateWithLifecycle().value
    var picked by remember { mutableStateOf(mapOf<String, ByteArray>()) }
    var choosing by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val plans by produceState<List<AlbumFix>?>(null, lib, picked) { value = vm.plan(folders, problems, picked) }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val folder = choosing
        if (uri != null && folder != null) {
            scope.launch { vm.read(uri)?.let { picked = picked + (folder to it) } }
        }
    }

    val ready = plans.orEmpty().filter { it.tracks.isNotEmpty() }
    val files = ready.sumOf { it.tracks.size }
    Page(
        title = "Fix tags & covers",
        subtitle = if (plans == null) "Working it out" else "${Doctor.count(ready.size, "album")} · ${Doctor.count(files, "file")}",
        onBack = pop,
        bottom = if (files == 0) null else {
            {
                BrutalButton(
                    "Write ${Doctor.count(files, "file")}",
                    onClick = {
                        vm.fix(ready)
                        pop()
                    },
                    fill = Grid.Yellow,
                )
            }
        },
    ) {
        val list = plans
        when {
            list == null -> Empty("Looking", "Working out the changes, and looking for covers on the player.")
            list.isEmpty() -> Empty("Nothing to fix", "These albums have nothing that can be fixed from here.")
            else -> LazyColumn(contentPadding = PaddingValues(vertical = GridTokens.Gap)) {
                item {
                    Text(
                        "Each file's old tags go to the quarantine before it is written: put them back from there if anything looks wrong.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Grid.InkSoft,
                        modifier = Modifier.padding(horizontal = GridTokens.Page, vertical = GridTokens.Gap),
                    )
                }
                items(list, key = { it.album.folder }) { fix ->
                    FixCard(fix) {
                        choosing = fix.album.folder
                        chooser.launch(arrayOf("image/*"))
                    }
                }
            }
        }
    }
}

@Composable
private fun FixCard(fix: AlbumFix, onChoose: () -> Unit) {
    TapSlab(null, Modifier.fillMaxWidth().padding(horizontal = GridTokens.Page, vertical = 4.dp)) {
        Caption(fix.album.artist)
        Text(
            fix.album.title,
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            color = Grid.Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // Two discs of one album have the same title: the folder tells them apart.
        Caption(fix.album.folder.split('/').takeLast(2).joinToString("/"))
        Spacer(Modifier.height(6.dp))
        for (line in fix.lines) Line("→", line, Grid.Ink)
        for (line in fix.unknown) Line("·", line, Grid.InkSoft)
        if (fix.coverWanted) {
            Caption(
                if (fix.cover == null) "Choose an image →" else "Choose another image →",
                color = Grid.Blue,
                modifier = Modifier
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onChoose)
                    .padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun Line(mark: String, text: String, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.padding(vertical = 2.dp)) {
        Text(mark, style = MaterialTheme.typography.bodyLarge, color = color)
        Spacer(Modifier.width(GridTokens.Gap))
        Column { Text(text, style = MaterialTheme.typography.bodySmall, color = color) }
    }
}
