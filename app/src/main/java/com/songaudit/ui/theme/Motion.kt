package com.songaudit.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * The surfaces are rigid; only their timing is gentle.
 *
 * Nothing in the app moves at a constant rate. Every tween names one of the
 * curves below, and everything else is a spring.
 *
 * Springs are described the way SwiftUI and the HIG describe them -- how long
 * it takes and how much it wobbles -- and converted to Compose's stiffness:
 *   stiffness = (2 * PI / response)^2      damping ratio = dampingFraction
 */
object Motion {

    private const val TWO_PI = 2.0 * Math.PI

    fun <T> iosSpring(response: Float, dampingFraction: Float = 0.86f): SpringSpec<T> = spring(
        dampingRatio = dampingFraction,
        stiffness = ((TWO_PI / response) * (TWO_PI / response)).toFloat(),
    )

    /** UIKit's `curveEaseOut`: quick to start, settles gently. */
    val EaseOut: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)

    /** UIKit's `curveEaseInOut`. Anything that both starts and ends at rest. */
    val EaseInOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** UIKit's `curveEaseIn`. Exits, so a leaving thing accelerates away. */
    val EaseIn: Easing = CubicBezierEasing(0.42f, 0f, 1f, 1f)

    /** Presses. Fast enough to feel connected to the finger, no overshoot. */
    fun <T> press(): SpringSpec<T> = iosSpring(response = 0.22f, dampingFraction = 1f)

    /** A thing settling into a new place: a token making room, a die rising. */
    fun <T> settle(): SpringSpec<T> = iosSpring(response = 0.38f, dampingFraction = 0.88f)

    const val FADE_MS = 200

    /** A dialog card coming up into place. */
    const val RISE_MS = 260

    fun <T> fade(): TweenSpec<T> = tween(FADE_MS, easing = EaseInOut)
    fun <T> enter(ms: Int = FADE_MS): TweenSpec<T> = tween(ms, easing = EaseOut)
    fun <T> exit(ms: Int = FADE_MS): TweenSpec<T> = tween(ms, easing = EaseIn)

    /** Half a breath: rest to full, or back. */
    const val PULSE_MS = 700
}
