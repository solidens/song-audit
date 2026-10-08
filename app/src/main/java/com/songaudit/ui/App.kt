package com.songaudit.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.songaudit.analysis.Issue
import com.songaudit.ui.components.Notice
import com.songaudit.ui.screens.AccessScreen
import com.songaudit.ui.screens.AlbumScreen
import com.songaudit.ui.screens.AlbumsScreen
import com.songaudit.ui.screens.DoctorScreen
import com.songaudit.ui.screens.DuplicatesScreen
import com.songaudit.ui.screens.FixScreen
import com.songaudit.ui.screens.GroupScreen
import com.songaudit.ui.screens.HomeScreen
import com.songaudit.ui.screens.QuarantineScreen
import com.songaudit.ui.screens.TrackScreen
import com.songaudit.ui.theme.Grid
import com.songaudit.ui.theme.Motion

sealed interface Route {
    data object Home : Route
    /** Albums with damage, or with tracks that are not what they claim. */
    data class Albums(val damaged: Boolean) : Route
    data object Doctor : Route
    data object Duplicates : Route
    data class Group(val id: String) : Route
    data class Album(val folder: String) : Route
    data class Track(val id: Long) : Route
    data object Quarantine : Route
    /** Tag and cover fixes for these albums' findings, shown before they are written. */
    data class Fix(val folders: List<String>, val problems: Set<com.songaudit.library.Problem>) : Route
}

/**
 * A stack of screens and nothing more. A push slides the new screen in from
 * the right while the old one drifts a third of the way out beneath it, as in
 * GridFlow; back pops.
 */
@Composable
fun AuditApp(vm: AuditViewModel) {
    val access by vm.access.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    val push: (Route) -> Unit = { stack.add(it) }
    val pop: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    BackHandler(enabled = stack.size > 1, onBack = pop)

    Box(Modifier.fillMaxSize().background(Grid.Paper)) {
        if (!access) {
            AccessScreen(onResult = vm::refreshAccess)
        } else {
            val top = stack.last()
            val depth = stack.size
            AnimatedContent(
                targetState = top to depth,
                transitionSpec = {
                    val forward = targetState.second >= initialState.second
                    val spec = tween<androidx.compose.ui.unit.IntOffset>(NAV_MS, easing = Motion.EaseOut)
                    val fade = tween<Float>(NAV_MS, easing = Motion.EaseOut)
                    if (forward) {
                        (slideInHorizontally(spec) { it } + fadeIn(fade)) togetherWith
                            (slideOutHorizontally(spec) { -it / 3 } + fadeOut(fade))
                    } else {
                        (slideInHorizontally(spec) { -it / 3 } + fadeIn(fade)) togetherWith
                            (slideOutHorizontally(spec) { it } + fadeOut(fade))
                    }
                },
                label = "nav",
            ) { (route, _) ->
                when (route) {
                    Route.Home -> HomeScreen(vm, push)
                    is Route.Albums -> AlbumsScreen(vm, route.damaged, push, pop)
                    Route.Doctor -> DoctorScreen(vm, push, pop)
                    Route.Duplicates -> DuplicatesScreen(vm, push, pop)
                    is Route.Group -> GroupScreen(vm, route.id, push, pop)
                    is Route.Album -> AlbumScreen(vm, route.folder, push, pop)
                    is Route.Track -> TrackScreen(vm, route.id, pop)
                    Route.Quarantine -> QuarantineScreen(vm, pop)
                    is Route.Fix -> FixScreen(vm, route.folders, route.problems, pop)
                }
            }
        }
        Notice(notice, vm::noticeShown)
    }
}

private const val NAV_MS = 320

/** The issues shown on the "not what it says" list, in the order of their filter keys. */
val FAKE_FILTERS = listOf(Issue.LOSSY_SOURCE, Issue.UPSAMPLED, Issue.PADDED, Issue.MAYBE_LOSSY, Issue.REENCODED)
