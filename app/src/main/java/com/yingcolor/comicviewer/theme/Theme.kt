package com.yingcolor.comicviewer.theme

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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.yingcolor.comicviewer.ui.LocalMotionScale
import com.yingcolor.comicviewer.ui.MotionSpec
import com.yingcolor.comicviewer.ui.motionTween
import com.yingcolor.comicviewer.ui.rememberMotionScale

/**
 * Material 3 Expressive inspired palette, taken from the "purple" preset of m3e-canvas
 * (seed #6750A4). Generous corner radii and springy motion are the defining traits.
 */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF6750A4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEADDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF625B71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1D192B),
    tertiary = Color(0xFF7D5260),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFD8E4),
    onTertiaryContainer = Color(0xFF31111D),
    background = Color(0xFFFFFBFE),
    onBackground = Color(0xFF1D1B20),
    surface = Color(0xFFFFFBFE),
    onSurface = Color(0xFF1D1B20),
    surfaceVariant = Color(0xFFE7E0EC),
    onSurfaceVariant = Color(0xFF49454F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainer = Color(0xFFF3EDF7),
    surfaceContainerHigh = Color(0xFFECE6F0),
    surfaceContainerHighest = Color(0xFFE6E0E9),
    outline = Color(0xFF79747E),
    outlineVariant = Color(0xFFCAC4D0),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondary = Color(0xFFCCC2DC),
    onSecondary = Color(0xFF332D41),
    secondaryContainer = Color(0xFF4A4458),
    onSecondaryContainer = Color(0xFFE8DEF8),
    tertiary = Color(0xFFEFB8C8),
    onTertiary = Color(0xFF492532),
    tertiaryContainer = Color(0xFF633B48),
    onTertiaryContainer = Color(0xFFFFD8E4),
    background = Color(0xFF1D1B20),
    onBackground = Color(0xFFE6E0E9),
    surface = Color(0xFF1D1B20),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceContainerLowest = Color(0xFF141218),
    surfaceContainerLow = Color(0xFF211F26),
    surfaceContainer = Color(0xFF28262E),
    surfaceContainerHigh = Color(0xFF302D38),
    surfaceContainerHighest = Color(0xFF3B3743),
    outline = Color(0xFF938F99),
    outlineVariant = Color(0xFF49454F),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410)
)

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
            colorScheme = animatedColorScheme(if (dark) DarkScheme else LightScheme),
            shapes = ExpressiveShapes,
            typography = AppTypography,
            content = content
        )
    }
}
