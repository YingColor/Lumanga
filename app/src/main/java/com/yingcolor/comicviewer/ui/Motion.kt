package com.yingcolor.comicviewer.ui

import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.math.abs

/**
 * Shared motion vocabulary.
 *
 * The guiding idea is that a visual state is a *continuous* value driven by a
 * gesture or an animated position, not a pair of discrete states that each play
 * their own tween. Colours, corner radii and sizes are interpolated from that
 * value, so an indicator slides with the finger instead of fading out here and
 * fading in there. Only genuinely discrete events (a press) get a short tween.
 */
object MotionSpec {
    /** Material 3 emphasized: quick departure, long settle. */
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Short, crisp ease-out used for press shape morphing. */
    val EaseOutCubic: Easing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

    const val SHORT = 150
    const val MEDIUM = 220
    const val LONG = 300
}

/** 0 when the user turned off animations in system accessibility settings. */
val LocalMotionScale = compositionLocalOf { 1f }

@Composable
fun rememberMotionScale(): Float {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        }.getOrDefault(1f).coerceIn(0f, 1f)
    }
}

/** A tween whose duration collapses to zero when system animations are disabled. */
@Composable
fun <T> motionTween(
    durationMillis: Int,
    easing: Easing = MotionSpec.Emphasized
): TweenSpec<T> = tween(
    durationMillis = (durationMillis * LocalMotionScale.current).toInt(),
    easing = easing
)

/**
 * How "selected" an item is for a fractional position: 1 when the indicator is
 * exactly on it, fading linearly to 0 once it is one slot away.
 */
fun selectionOf(position: Float, index: Int): Float =
    (1f - abs(position - index)).coerceIn(0f, 1f)
