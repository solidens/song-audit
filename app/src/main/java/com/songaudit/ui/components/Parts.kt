package com.songaudit.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.songaudit.ui.theme.BrutalButton
import com.songaudit.ui.theme.BrutalCard
import com.songaudit.ui.theme.BrutalRule
import com.songaudit.ui.theme.BrutalSlab
import com.songaudit.ui.theme.Caption
import com.songaudit.ui.theme.Grid
import com.songaudit.ui.theme.GridTokens
import com.songaudit.ui.theme.Motion
import com.songaudit.ui.theme.onAccent
import kotlinx.coroutines.delay

private val Sharp = RoundedCornerShape(0.dp)

/** The four Bauhaus shapes. Each finding has one, so it reads without colour too. */
enum class Shape { SQUARE, TRIANGLE, CIRCLE, DIAMOND }

@Composable
fun Glyph(shape: Shape, fill: Color, size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = 2.dp.toPx()
        val inset = stroke / 2
        val path = Path().apply {
            when (shape) {
                Shape.SQUARE -> {
                    moveTo(inset, inset); lineTo(s - inset, inset); lineTo(s - inset, s - inset); lineTo(inset, s - inset); close()
                }
                Shape.TRIANGLE -> {
                    moveTo(s / 2, inset); lineTo(s - inset, s - inset); lineTo(inset, s - inset); close()
                }
                Shape.DIAMOND -> {
                    moveTo(s / 2, inset); lineTo(s - inset, s / 2); lineTo(s / 2, s - inset); lineTo(inset, s / 2); close()
                }
                Shape.CIRCLE -> addOval(androidx.compose.ui.geometry.Rect(inset, inset, s - inset, s - inset))
            }
        }
        drawPath(path, fill)
        drawPath(path, Grid.Ink, style = Stroke(stroke))
    }
}

/**
 * A screen below the home one: a back key, an uppercase title, the content,
 * and optionally an action pinned to the bottom edge the way Start is on the
 * game screens.
 */
@Composable
fun Page(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    bottom: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Grid.Paper)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = GridTokens.Page)
                .padding(top = GridTokens.GapWide, bottom = GridTokens.Gap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SquareKey("←", onBack, label = "Back")
            Spacer(Modifier.width(GridTokens.GapWide))
            Column(Modifier.weight(1f)) {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.titleLarge,
                    color = Grid.Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) Caption(subtitle)
            }
        }
        BrutalRule()
        Column(Modifier.weight(1f), content = content)
        if (bottom != null) {
            BrutalRule()
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = GridTokens.Page)
                    .padding(top = GridTokens.GapWide, bottom = GridTokens.Gap),
                content = bottom,
            )
        }
    }
}

/** A small square key: back, close. Pressed into its shadow like every other slab. */
@Composable
fun SquareKey(symbol: String, onClick: () -> Unit, label: String, size: Dp = 44.dp) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(Modifier.size(size + GridTokens.ShadowSmall)) {
        BrutalSlab(
            modifier = Modifier
                .size(size)
                .clickable(interaction, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick),
            shadow = GridTokens.ShadowSmall,
            pressedDepth = if (pressed) GridTokens.ShadowSmall else 0.dp,
        ) {
            Text(symbol, style = MaterialTheme.typography.titleLarge, color = Grid.Ink, modifier = Modifier.align(Alignment.Center))
        }
    }
}

/**
 * A slab you tap to go somewhere: the rows of the home screen and every
 * list. Its press slides it into its shadow; the shadow stays put.
 */
@Composable
fun TapSlab(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    fill: Color = Grid.Paper,
    contentPadding: PaddingValues = PaddingValues(horizontal = GridTokens.GapWide, vertical = 12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val depth by animateFloatAsState(if (pressed && onClick != null) 1f else 0f, Motion.press(), label = "press")
    val shadow = GridTokens.ShadowSmall
    // The padding reserves the shadow's room, so a list of these never overlaps.
    Box(modifier.padding(end = shadow, bottom = shadow)) {
        Box(
            Modifier
                .matchParentSize()
                .offset(shadow, shadow)
                .background(Grid.Ink, Sharp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .offset(shadow * depth, shadow * depth)
                .background(fill, Sharp)
                .border(GridTokens.Border, Grid.Ink, Sharp)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(interaction, indication = null, role = Role.Button, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .padding(contentPadding),
            content = content,
        )
    }
}

/** A label on a flat chip: what is wrong, in one or two words. */
@Composable
fun Badge(text: String, fill: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(fill, Sharp)
            .border(2.dp, Grid.Ink, Sharp)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text.uppercase(),
            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
            color = onAccent(fill).takeIf { fill != Grid.Paper } ?: Grid.Ink,
            maxLines = 1,
        )
    }
}

/** A row of latched keys for filtering a list; scrolls sideways when it must. */
@Composable
fun <T> Chips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = GridTokens.Page, vertical = GridTokens.Gap),
        horizontalArrangement = Arrangement.spacedBy(GridTokens.Gap),
    ) {
        for ((value, label) in options) Chip(label, value == selected) { onSelect(value) }
    }
}

/**
 * A key sized by its label. The chosen one is pushed into its shadow and
 * stays there, like BrutalChoice; unlike it, it does not ask for a width.
 */
@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    val shadow = GridTokens.ShadowSmall
    val depth by animateFloatAsState(if (on) 1f else 0f, Motion.press(), label = "latch")
    Box(Modifier.padding(end = shadow, bottom = shadow)) {
        Box(
            Modifier
                .matchParentSize()
                .offset(shadow, shadow)
                .background(Grid.Ink, Sharp),
        )
        Box(
            Modifier
                .offset(shadow * depth, shadow * depth)
                .background(if (on) Grid.Ink else Grid.Paper, Sharp)
                .border(GridTokens.Border, Grid.Ink, Sharp)
                .clickable(remember { MutableInteractionSource() }, indication = null, role = Role.Tab, onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                color = if (on) Grid.Paper else Grid.Ink,
                maxLines = 1,
            )
        }
    }
}

/** Ink frame, yellow fill. Eases to each new value. */
@Composable
fun Bar(fraction: Float, modifier: Modifier = Modifier, fill: Color = Grid.Yellow) {
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), Motion.settle(), label = "bar")
    Box(
        modifier
            .fillMaxWidth()
            .height(16.dp)
            .background(Grid.Paper, Sharp)
            .border(GridTokens.Border, Grid.Ink, Sharp)
            .padding(GridTokens.Border),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(f)
                .background(fill),
        )
    }
}

/** One fact: a caption on the left, the value on the right. */
@Composable
fun Fact(label: String, value: String, valueColor: Color = Grid.Ink) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Caption(label, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold), color = valueColor)
    }
}

/** A question before anything that moves or deletes files. */
@Composable
fun Confirm(
    title: String,
    text: String,
    action: String,
    fill: Color,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        BrutalCard(Modifier.padding(GridTokens.Gap)) {
            Text(title.uppercase(), style = MaterialTheme.typography.titleLarge, color = Grid.Ink)
            Spacer(Modifier.height(GridTokens.Gap))
            Text(text, style = MaterialTheme.typography.bodyLarge, color = Grid.InkSoft)
            Spacer(Modifier.height(GridTokens.GapWide))
            BrutalButton(action, onClick = { onConfirm(); onDismiss() }, fill = fill, contentColor = onAccent(fill), height = 52.dp)
            Spacer(Modifier.height(GridTokens.Gap))
            BrutalButton("Cancel", onClick = onDismiss, height = 52.dp)
        }
    }
}

/** One thing that can be done, with a line on what it means. */
class Action(val label: String, val detail: String, val fill: Color, val run: () -> Unit)

/** A choice between a few things to do, each with its consequence spelled out under it. */
@Composable
fun Actions(title: String, text: String?, actions: List<Action>, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        BrutalCard(Modifier.padding(GridTokens.Gap)) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(title.uppercase(), style = MaterialTheme.typography.titleLarge, color = Grid.Ink)
                if (text != null) {
                    Spacer(Modifier.height(GridTokens.Gap))
                    Text(text, style = MaterialTheme.typography.bodyLarge, color = Grid.InkSoft)
                }
                for (a in actions) {
                    Spacer(Modifier.height(GridTokens.GapWide))
                    BrutalButton(
                        a.label,
                        onClick = { a.run(); onDismiss() },
                        fill = a.fill,
                        contentColor = if (a.fill == Grid.Paper) Grid.Ink else onAccent(a.fill),
                        height = 52.dp,
                    )
                    Text(a.detail, style = MaterialTheme.typography.bodySmall, color = Grid.InkSoft)
                }
                Spacer(Modifier.height(GridTokens.GapWide))
                BrutalButton("Cancel", onClick = onDismiss, height = 52.dp)
            }
        }
    }
}

/** What just happened, in one line at the foot of the screen, gone after a few seconds. */
@Composable
fun BoxScope.Notice(text: String?, onShown: () -> Unit) {
    LaunchedEffect(text) {
        if (text != null) {
            delay(3500)
            onShown()
        }
    }
    AnimatedVisibility(
        visible = text != null,
        enter = slideInVertically(Motion.enter()) { it } + fadeIn(Motion.enter()),
        exit = slideOutVertically(Motion.exit()) { it } + fadeOut(Motion.exit()),
        // Above the action pinned to the bottom edge, never over it.
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .navigationBarsPadding()
            .padding(horizontal = GridTokens.Page)
            .padding(bottom = 100.dp),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Grid.Ink, Sharp)
                .border(GridTokens.Border, Grid.Ink, Sharp)
                .padding(GridTokens.GapWide),
        ) {
            Text(text ?: "", style = MaterialTheme.typography.bodyLarge, color = Grid.Paper)
        }
    }
}

/** Centered message for a list with nothing in it. */
@Composable
fun Empty(title: String, text: String) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(GridTokens.Page),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Glyph(Shape.CIRCLE, Grid.Paper, size = 40.dp)
        Spacer(Modifier.height(GridTokens.GapWide))
        Text(title.uppercase(), style = MaterialTheme.typography.titleLarge, color = Grid.Ink)
        Spacer(Modifier.height(GridTokens.Gap))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = Grid.InkSoft)
    }
}
