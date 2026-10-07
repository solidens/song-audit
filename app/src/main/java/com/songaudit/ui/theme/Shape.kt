package com.songaudit.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** No radii anywhere. The corner is the statement. */
private val Square = RoundedCornerShape(0.dp)

val GridShapes = Shapes(
    extraSmall = Square,
    small = Square,
    medium = Square,
    large = Square,
    extraLarge = Square,
)

object GridTokens {
    /** Border weight, the single most load-bearing value in the system. */
    val Border = 3.dp
    val BorderThick = 4.dp

    /** Hard shadow offset. No blur, no alpha, pure Ink. */
    val Shadow = 6.dp
    val ShadowSmall = 4.dp

    /** 8pt spine. */
    val Gap = 8.dp
    val GapWide = 16.dp
    val GapSection = 24.dp
    val Page = 20.dp
}
