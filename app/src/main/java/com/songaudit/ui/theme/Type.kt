package com.songaudit.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp

/**
 * One family, one axis of variation: weight. Bauhaus signage rather than
 * a UI type scale -- everything display-level is uppercase with wide tracking.
 */
private val Grotesque = FontFamily.SansSerif

val GridTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = Grotesque,
        fontWeight = FontWeight.Black,
        fontSize = 56.sp,
        lineHeight = 54.sp,
        letterSpacing = (-2).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = Grotesque,
        fontWeight = FontWeight.Black,
        fontSize = 34.sp,
        lineHeight = 34.sp,
        letterSpacing = (-1).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Grotesque,
        fontWeight = FontWeight.Black,
        fontSize = 18.sp,
        lineHeight = 20.sp,
        letterSpacing = 2.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = Grotesque,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        lineHeight = 14.sp,
        letterSpacing = 3.sp,
        textAlign = TextAlign.Center,
    ),
    bodyLarge = TextStyle(
        fontFamily = Grotesque,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = Grotesque,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 1.sp,
    ),
)
