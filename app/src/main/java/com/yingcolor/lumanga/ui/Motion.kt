package com.yingcolor.lumanga.ui

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










object MotionSpec {
    
    val Emphasized: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    
    val EaseOutCubic: Easing = CubicBezierEasing(0.215f, 0.61f, 0.355f, 1f)

    const val SHORT = 150
    const val MEDIUM = 220
    const val LONG = 300
}


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


@Composable
fun <T> motionTween(
    durationMillis: Int,
    easing: Easing = MotionSpec.Emphasized
): TweenSpec<T> = tween(
    durationMillis = (durationMillis * LocalMotionScale.current).toInt(),
    easing = easing
)





fun selectionOf(position: Float, index: Int): Float =
    (1f - abs(position - index)).coerceIn(0f, 1f)
