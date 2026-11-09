package com.yingcolor.lumanga.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Fresh, low-saturation accent palettes.
 *
 * Every palette shares one clean neutral skeleton: near-white paper with only a
 * whisper of colour in light mode, cool charcoal in dark mode. Only the accent
 * roles change between presets, so switching colours never changes contrast or
 * legibility. Heavy tinted surfaces and muddy mid-tone primaries are avoided:
 * the accent stays bright, the surfaces stay quiet.
 */
object Palettes {
    const val COUNT = 6

    private data class Accent(
        val seed: Color,      // light-mode primary
        val darkSeed: Color,  // dark-mode primary (bright pastel)
        val mate: Color,      // light-mode tertiary
        val darkMate: Color   // dark-mode tertiary
    )

    /** Mint green, lake cyan, sky blue, lavender, sakura pink, apricot orange. */
    private val accents = arrayOf(
        Accent(Color(0xFF2FA276), Color(0xFF8EDCB2), Color(0xFF48A9C6), Color(0xFF8FD3E6)),
        Accent(Color(0xFF1E9FAE), Color(0xFF82D6E2), Color(0xFF5B8FD8), Color(0xFFA8C7F2)),
        Accent(Color(0xFF3B86D9), Color(0xFF9CC5F6), Color(0xFF8279D6), Color(0xFFBEB8F2)),
        Accent(Color(0xFF8275D6), Color(0xFFC0B9F4), Color(0xFFD27EAC), Color(0xFFEDB4D2)),
        Accent(Color(0xFFDC7193), Color(0xFFF3B1C7), Color(0xFFE39A68), Color(0xFFF2C7A4)),
        Accent(Color(0xFFDD8A3E), Color(0xFFF2C089), Color(0xFFC8A24B), Color(0xFFEAD697))
    )

    private val Ink = Color(0xFF111A1C)
    private val Paper = Color(0xFFFFFFFF)
    private val LightText = Color(0xFF16201D)
    private val LightMuted = Color(0xFF5B6862)
    private val Night = Color(0xFF0F1619)
    private val NightText = Color(0xFFE4EDE9)
    private val NightMuted = Color(0xFFB2C2BB)

    fun coerce(index: Int): Int = index.coerceIn(0, COUNT - 1)

    /** A representative colour for the swatch picker. */
    fun accent(index: Int): Color = accents[coerce(index)].seed

    fun schemeFor(index: Int, dark: Boolean): ColorScheme {
        val a = accents[coerce(index)]
        return if (dark) darkScheme(a) else lightScheme(a)
    }

    private fun lightScheme(a: Accent): ColorScheme = lightColorScheme(
        primary = a.seed,
        onPrimary = Paper,
        primaryContainer = mix(Paper, a.seed, 0.17f),
        onPrimaryContainer = mix(Ink, a.seed, 0.26f),
        secondary = mix(a.seed, LightMuted, 0.45f),
        onSecondary = Paper,
        secondaryContainer = mix(Paper, a.seed, 0.10f),
        onSecondaryContainer = mix(Ink, a.seed, 0.22f),
        tertiary = a.mate,
        onTertiary = Paper,
        tertiaryContainer = mix(Paper, a.mate, 0.17f),
        onTertiaryContainer = mix(Ink, a.mate, 0.26f),
        background = mix(Paper, a.seed, 0.025f),
        onBackground = LightText,
        surface = mix(Paper, a.seed, 0.025f),
        onSurface = LightText,
        surfaceVariant = mix(Paper, a.seed, 0.10f),
        onSurfaceVariant = LightMuted,
        surfaceContainerLowest = Paper,
        surfaceContainerLow = mix(Paper, a.seed, 0.045f),
        surfaceContainer = mix(Paper, a.seed, 0.075f),
        surfaceContainerHigh = mix(Paper, a.seed, 0.105f),
        surfaceContainerHighest = mix(Paper, a.seed, 0.14f),
        outline = Color(0xFF8A978F),
        outlineVariant = mix(Paper, a.seed, 0.19f),
        error = Color(0xFFB3261E),
        onError = Paper
    )

    private fun darkScheme(a: Accent): ColorScheme = darkColorScheme(
        primary = a.darkSeed,
        onPrimary = mix(Ink, a.seed, 0.28f),
        primaryContainer = mix(Night, a.seed, 0.32f),
        onPrimaryContainer = a.darkSeed,
        secondary = mix(a.darkSeed, NightMuted, 0.30f),
        onSecondary = mix(Ink, a.seed, 0.25f),
        secondaryContainer = mix(Night, a.seed, 0.22f),
        onSecondaryContainer = a.darkSeed,
        tertiary = a.darkMate,
        onTertiary = mix(Ink, a.mate, 0.28f),
        tertiaryContainer = mix(Night, a.mate, 0.28f),
        onTertiaryContainer = a.darkMate,
        background = Night,
        onBackground = NightText,
        surface = Night,
        onSurface = NightText,
        surfaceVariant = Color(0xFF28363A),
        onSurfaceVariant = NightMuted,
        surfaceContainerLowest = Color(0xFF0A1114),
        surfaceContainerLow = Color(0xFF151E21),
        surfaceContainer = Color(0xFF1A2428),
        surfaceContainerHigh = Color(0xFF202C30),
        surfaceContainerHighest = Color(0xFF263439),
        outline = Color(0xFF89988F),
        outlineVariant = Color(0xFF39484B),
        error = Color(0xFFF2B8B5),
        onError = Color(0xFF601410)
    )

    /** Linear blend in sRGB, used for the pale tinted containers and surfaces. */
    private fun mix(from: Color, to: Color, amount: Float): Color {
        val a = from.toArgb()
        val b = to.toArgb()
        val t = amount.coerceIn(0f, 1f)
        fun channel(shift: Int): Float {
            val x = (a ushr shift) and 0xFF
            val y = (b ushr shift) and 0xFF
            return (x + (y - x) * t) / 255f
        }
        return Color(red = channel(16), green = channel(8), blue = channel(0))
    }
}
