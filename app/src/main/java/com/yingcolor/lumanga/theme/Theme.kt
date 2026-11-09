package com.yingcolor.lumanga.theme

import android.app.Activity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.yingcolor.lumanga.ui.LocalMotionScale
import com.yingcolor.lumanga.ui.MotionSpec
import com.yingcolor.lumanga.ui.motionTween
import com.yingcolor.lumanga.ui.rememberMotionScale

/**
 * Material 3 Expressive shapes and motion, with the light accent palettes in
 * [Palettes]. Generous corner radii and springy motion are the defining traits.
 */
private val BaseTypography = Typography()

private val AppTypography = Typography(
    displayLarge = BaseTypography.displayLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp),
    headlineLarge = BaseTypography.headlineLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp),
    headlineMedium = BaseTypography.headlineMedium.copy(fontWeight = FontWeight.Medium),
    titleLarge = BaseTypography.titleLarge.copy(fontWeight = FontWeight.Medium),
    titleMedium = BaseTypography.titleMedium.copy(fontWeight = FontWeight.Medium),
    bodyLarge = BaseTypography.bodyLarge.copy(lineHeight = 24.sp),
    labelLarge = BaseTypography.labelLarge.copy(fontWeight = FontWeight.Medium)
)

val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

/** Bouncy spring used for press feedback and page transitions. */
fun <T> expressiveSpring() = spring<T>(
    dampingRatio = Spring.DampingRatioMediumBouncy,
    stiffness = Spring.StiffnessMedium
)

/**
 * Interpolates every visible colour of the scheme instead of swapping the whole
 * palette in one frame. This is what turns the light/dark switch into a gradual
 * fade rather than a hard cut.
 */
@Composable
private fun animatedColorScheme(target: ColorScheme): ColorScheme {
    val spec = motionTween<Color>(MotionSpec.LONG, MotionSpec.Emphasized)
    val primary = animateColorAsState(target.primary, spec, "csPrimary").value
    val onPrimary = animateColorAsState(target.onPrimary, spec, "csOnPrimary").value
    val primaryContainer = animateColorAsState(target.primaryContainer, spec, "csPrimaryContainer").value
    val onPrimaryContainer = animateColorAsState(target.onPrimaryContainer, spec, "csOnPrimaryContainer").value
    val secondary = animateColorAsState(target.secondary, spec, "csSecondary").value
    val onSecondary = animateColorAsState(target.onSecondary, spec, "csOnSecondary").value
    val secondaryContainer = animateColorAsState(target.secondaryContainer, spec, "csSecondaryContainer").value
    val onSecondaryContainer = animateColorAsState(target.onSecondaryContainer, spec, "csOnSecondaryContainer").value
    val tertiary = animateColorAsState(target.tertiary, spec, "csTertiary").value
    val onTertiary = animateColorAsState(target.onTertiary, spec, "csOnTertiary").value
    val tertiaryContainer = animateColorAsState(target.tertiaryContainer, spec, "csTertiaryContainer").value
    val onTertiaryContainer = animateColorAsState(target.onTertiaryContainer, spec, "csOnTertiaryContainer").value
    val background = animateColorAsState(target.background, spec, "csBackground").value
    val onBackground = animateColorAsState(target.onBackground, spec, "csOnBackground").value
    val surface = animateColorAsState(target.surface, spec, "csSurface").value
    val onSurface = animateColorAsState(target.onSurface, spec, "csOnSurface").value
    val surfaceVariant = animateColorAsState(target.surfaceVariant, spec, "csSurfaceVariant").value
    val onSurfaceVariant = animateColorAsState(target.onSurfaceVariant, spec, "csOnSurfaceVariant").value
    val surfaceContainerLowest = animateColorAsState(target.surfaceContainerLowest, spec, "csSurfaceLowest").value
    val surfaceContainerLow = animateColorAsState(target.surfaceContainerLow, spec, "csSurfaceLow").value
    val surfaceContainer = animateColorAsState(target.surfaceContainer, spec, "csSurfaceContainer").value
    val surfaceContainerHigh = animateColorAsState(target.surfaceContainerHigh, spec, "csSurfaceHigh").value
    val surfaceContainerHighest = animateColorAsState(target.surfaceContainerHighest, spec, "csSurfaceHighest").value
    val outline = animateColorAsState(target.outline, spec, "csOutline").value
    val outlineVariant = animateColorAsState(target.outlineVariant, spec, "csOutlineVariant").value
    val error = animateColorAsState(target.error, spec, "csError").value
    val onError = animateColorAsState(target.onError, spec, "csOnError").value

    return target.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        secondary = secondary,
        onSecondary = onSecondary,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = onTertiary,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = background,
        onBackground = onBackground,
        surface = surface,
        onSurface = onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = onSurfaceVariant,
        surfaceContainerLowest = surfaceContainerLowest,
        surfaceContainerLow = surfaceContainerLow,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerHighest = surfaceContainerHighest,
        outline = outline,
        outlineVariant = outlineVariant,
        error = error,
        onError = onError
    )
}

@Composable
fun ComicTheme(
    mode: Int = 0,
    accent: Int = 0,
    content: @Composable () -> Unit
) {
    val dark = when (mode) {
        1 -> false
        2 -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    SideEffect {
        val window = (context as? Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }
    CompositionLocalProvider(LocalMotionScale provides rememberMotionScale()) {
        MaterialTheme(
            colorScheme = animatedColorScheme(Palettes.schemeFor(accent, dark)),
            shapes = ExpressiveShapes,
            typography = AppTypography,
            content = content
        )
    }
}
