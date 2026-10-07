package com.songaudit.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val Sharp = RoundedCornerShape(0.dp)

/** Fills darker than this get the paper inline; Ink is 0.006, the primaries start at 0.09. */
private const val DARK_FILL = 0.05f
private val INLINE = 2.dp

/**
 * An ink slab on an ink shadow reads as one black blob. A paper line just
 * inside the border splits the body from its shadow; keeping the outer edge
 * ink keeps it aligned with its neighbours.
 */
private fun Modifier.body(fill: Color, border: Color, borderWidth: Dp): Modifier = this
    .background(fill, Sharp)
    .border(BorderStroke(borderWidth, border), Sharp)
    .then(
        if (fill.luminance() < DARK_FILL) {
            Modifier
                .padding(borderWidth)
                .border(BorderStroke(INLINE, Grid.Paper), Sharp)
        } else {
            Modifier
        },
    )

/**
 * The one primitive the rest of the UI is built from: a flat slab with a hard
 * black border and an unblurred offset shadow.
 *
 * [pressedDepth] slides the slab toward its own shadow instead of lifting it,
 * which is what gives the press its physical, printed-object feel. The caller
 * animates it; the slab itself is stateless.
 */
@Composable
fun BrutalSlab(
    modifier: Modifier = Modifier,
    fill: Color = Grid.Paper,
    border: Color = Grid.Ink,
    borderWidth: Dp = GridTokens.Border,
    shadow: Dp = GridTokens.Shadow,
    pressedDepth: Dp = 0.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        // Shadow plate: same silhouette, offset, no blur -- a printed drop, not a light source.
        if (shadow > 0.dp) {
            Box(
                Modifier
                    .matchParentSize()
                    .offset(x = shadow, y = shadow)
                    .background(Grid.Ink, Sharp),
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .offset(x = pressedDepth, y = pressedDepth)
                .body(fill, border, borderWidth)
                .padding(contentPadding),
            content = content,
        )
    }
}

/** The slab again, for content that decides its own height: dialogs, lists. */
@Composable
fun BrutalCard(
    modifier: Modifier = Modifier,
    fill: Color = Grid.Paper,
    shadow: Dp = GridTokens.Shadow,
    contentPadding: PaddingValues = PaddingValues(GridTokens.GapWide),
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(modifier) {
        Box(
            Modifier
                .matchParentSize()
                .offset(x = shadow, y = shadow)
                .background(Grid.Ink, Sharp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .body(fill, Grid.Ink, GridTokens.Border)
                .padding(GridTokens.Border)
                .padding(contentPadding),
            content = content,
        )
    }
}

/** Full-width action. Uppercase, tracked, pressed = pushed into its own shadow. */
@Composable
fun BrutalButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    fill: Color = Grid.Paper,
    contentColor: Color = Grid.Ink,
    enabled: Boolean = true,
    height: Dp = 60.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val depth by animateDpAsState(
        targetValue = if (pressed && enabled) GridTokens.Shadow else 0.dp,
        animationSpec = Motion.press(),
        label = "press",
    )

    // Reserve the shadow's travel so neighbouring content never reflows.
    Box(modifier.height(height + GridTokens.Shadow)) {
        BrutalSlab(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                ),
            fill = if (enabled) fill else Grid.PaperDeep,
            border = if (enabled) Grid.Ink else Grid.InkSoft,
            pressedDepth = depth,
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = if (enabled) contentColor else Grid.InkSoft,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

/**
 * One option of several. The chosen one is pushed into its shadow and stays
 * there -- a latched key, not a highlight.
 */
@Composable
fun BrutalChoice(
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 56.dp,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val depth by animateDpAsState(
        targetValue = if (selected) GridTokens.ShadowSmall else 0.dp,
        animationSpec = Motion.press(),
        label = "latch",
    )
    Box(modifier.height(height + GridTokens.ShadowSmall)) {
        BrutalSlab(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onSelect,
                ),
            fill = if (selected) Grid.Ink else Grid.Paper,
            shadow = GridTokens.ShadowSmall,
            pressedDepth = depth,
            contentPadding = contentPadding,
            content = content,
        )
    }
}

/** A rule used to separate sections. Thick enough to be a statement. */
@Composable
fun BrutalRule(modifier: Modifier = Modifier, thickness: Dp = GridTokens.Border) {
    Box(
        modifier
            .fillMaxWidth()
            .height(thickness)
            .background(Grid.Ink),
    )
}

/** Small square colour chip -- the Bauhaus "keyed" element. */
@Composable
fun ColorChip(color: Color, modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Box(
        modifier
            .size(size)
            .background(color, Sharp)
            .border(2.dp, Grid.Ink, Sharp),
    )
}

/** Uppercase tracked caption, the system's only secondary text treatment. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier, color: Color = Grid.InkSoft) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = 1,
        modifier = modifier,
    )
}

/**
 * A slow breath between 0 and 1: the app's only "this one is waiting for you"
 * affordance.
 *
 * When [active] goes false the breath is let out rather than cut: whatever is
 * scaled or faded by it eases back to rest instead of snapping there, which on
 * a die or a token reads as a flicker. At rest nothing runs, so the frame clock
 * is only kept awake while something is actually waiting.
 */
@Composable
fun rememberBreath(active: Boolean): State<Float> {
    val breath = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) {
            val half = tween<Float>(Motion.PULSE_MS, easing = Motion.EaseInOut)
            while (true) {
                breath.animateTo(1f, half)
                breath.animateTo(0f, half)
            }
        } else {
            breath.animateTo(0f, Motion.press())
        }
    }
    return breath.asState()
}

/** One line of text in a slab. The placeholder is a caption, so an empty field still says what it wants. */
@Composable
fun BrutalField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.bodyLarge,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    height: Dp = 56.dp,
) {
    Box(modifier.height(height + GridTokens.ShadowSmall)) {
        BrutalSlab(
            modifier = Modifier
                .fillMaxWidth()
                .height(height),
            shadow = GridTokens.ShadowSmall,
            contentPadding = PaddingValues(horizontal = GridTokens.GapWide),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = textStyle.copy(color = Grid.Ink),
                cursorBrush = SolidColor(Grid.Ink),
                keyboardOptions = keyboardOptions,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.CenterStart),
                decorationBox = { field ->
                    Box {
                        if (value.isEmpty()) Caption(placeholder)
                        field()
                    }
                },
            )
        }
    }
}
