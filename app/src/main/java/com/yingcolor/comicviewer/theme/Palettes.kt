package com.yingcolor.comicviewer.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * Accent colours for the whole app.
 *
 * The first palette is the hand-tuned violet the app shipped with; the rest are
 * generated from a single hue so every option keeps exactly the same contrast
 * structure (which tone each role uses) and only the hue changes. Generating
 * them beats hand-writing five more schemes: a new accent is one line here.
 */
object Palettes {
    const val COUNT = 6

    private const val VIOLET = 0
    private const val BLUE = 1
    private const val TEAL = 2
    private const val GREEN = 3
    private const val ORANGE = 4
    private const val ROSE = 5

    /** Seed hue per palette, in degrees. The violet one is unused (hand-tuned). */
    private val hues = floatArrayOf(258f, 216f, 186f, 140f, 28f, 338f)

    fun coerce(index: Int): Int = index.coerceIn(0, COUNT - 1)

    /** A representative colour for the swatch picker. */
    fun accent(index: Int): Color =
        if (coerce(index) == VIOLET) Color(0xFF6750A4) else hsl(hues[coerce(index)], 0.46f, 0.42f)

    fun schemeFor(index: Int, dark: Boolean): ColorScheme {
        val safe = coerce(index)
        if (safe == VIOLET) return if (dark) DarkScheme else LightScheme
        val hue = hues[safe]
        return if (dark) generatedDark(hue) else generatedLight(hue)
    }

    // --- Hand-tuned baseline -------------------------------------------------

    val LightScheme = lightColorScheme(
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

    val DarkScheme = darkColorScheme(
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

    // --- Generated accents ---------------------------------------------------

    private fun generatedLight(hue: Float): ColorScheme {
        val neutral = 0.09f
        return lightColorScheme(
            primary = hsl(hue, 0.46f, 0.40f),
            onPrimary = Color.White,
            primaryContainer = hsl(hue, 0.55f, 0.91f),
            onPrimaryContainer = hsl(hue, 0.45f, 0.12f),
            secondary = hsl(hue, 0.20f, 0.40f),
            onSecondary = Color.White,
            secondaryContainer = hsl(hue, 0.26f, 0.91f),
            onSecondaryContainer = hsl(hue, 0.22f, 0.15f),
            tertiary = hsl(hue + 54f, 0.34f, 0.40f),
            onTertiary = Color.White,
            tertiaryContainer = hsl(hue + 54f, 0.42f, 0.91f),
            onTertiaryContainer = hsl(hue + 54f, 0.34f, 0.13f),
            background = hsl(hue, neutral, 0.99f),
            onBackground = hsl(hue, neutral, 0.11f),
            surface = hsl(hue, neutral, 0.99f),
            onSurface = hsl(hue, neutral, 0.11f),
            surfaceVariant = hsl(hue, 0.10f, 0.90f),
            onSurfaceVariant = hsl(hue, 0.08f, 0.30f),
            surfaceContainerLowest = hsl(hue, neutral, 1f),
            surfaceContainerLow = hsl(hue, neutral, 0.96f),
            surfaceContainer = hsl(hue, neutral, 0.94f),
            surfaceContainerHigh = hsl(hue, neutral, 0.91f),
            surfaceContainerHighest = hsl(hue, neutral, 0.89f),
            outline = hsl(hue, 0.07f, 0.50f),
            outlineVariant = hsl(hue, 0.08f, 0.80f),
            error = Color(0xFFB3261E),
            onError = Color.White
        )
    }

    private fun generatedDark(hue: Float): ColorScheme {
        val neutral = 0.10f
        return darkColorScheme(
            primary = hsl(hue, 0.36f, 0.78f),
            onPrimary = hsl(hue, 0.40f, 0.18f),
            primaryContainer = hsl(hue, 0.34f, 0.32f),
            onPrimaryContainer = hsl(hue, 0.50f, 0.92f),
            secondary = hsl(hue, 0.16f, 0.80f),
            onSecondary = hsl(hue, 0.16f, 0.18f),
            secondaryContainer = hsl(hue, 0.16f, 0.30f),
            onSecondaryContainer = hsl(hue, 0.24f, 0.90f),
            tertiary = hsl(hue + 54f, 0.26f, 0.78f),
            onTertiary = hsl(hue + 54f, 0.30f, 0.18f),
            tertiaryContainer = hsl(hue + 54f, 0.26f, 0.32f),
            onTertiaryContainer = hsl(hue + 54f, 0.36f, 0.92f),
            background = hsl(hue, neutral, 0.09f),
            onBackground = hsl(hue, 0.05f, 0.90f),
            surface = hsl(hue, neutral, 0.09f),
            onSurface = hsl(hue, 0.05f, 0.90f),
            surfaceVariant = hsl(hue, 0.06f, 0.30f),
            onSurfaceVariant = hsl(hue, 0.05f, 0.79f),
            surfaceContainerLowest = hsl(hue, neutral, 0.05f),
            surfaceContainerLow = hsl(hue, neutral, 0.11f),
            surfaceContainer = hsl(hue, neutral, 0.14f),
            surfaceContainerHigh = hsl(hue, neutral, 0.18f),
            surfaceContainerHighest = hsl(hue, neutral, 0.22f),
            outline = hsl(hue, 0.05f, 0.60f),
            outlineVariant = hsl(hue, 0.05f, 0.30f),
            error = Color(0xFFF2B8B5),
            onError = Color(0xFF601410)
        )
    }

    /** HSL colour; lightness stands in for Material's tone, which is close enough. */
    private fun hsl(hue: Float, saturation: Float, lightness: Float): Color {
        val h = ((hue % 360f) + 360f) % 360f
        val c = (1f - abs(2f * lightness - 1f)) * saturation
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = lightness - c / 2f
        val (r, g, b) = when {
            h < 60f -> Triple(c, x, 0f)
            h < 120f -> Triple(x, c, 0f)
            h < 180f -> Triple(0f, c, x)
            h < 240f -> Triple(0f, x, c)
            h < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Color(r + m, g + m, b + m)
    }
}
