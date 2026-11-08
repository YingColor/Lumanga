package com.yingcolor.comicviewer.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yingcolor.comicviewer.S
import com.yingcolor.comicviewer.data.UiSettings
import com.yingcolor.comicviewer.data.ZoomMode

/** Which page of the settings window is shown. */
object SettingsRoute {
    const val CLOSED = 0

    /** Menu with the entry points (reading, appearance, shelf…). */
    const val ROOT = 1

    /** All reading options; the reader's gear button opens this page directly. */
    const val READING = 2
}

/**
 * A floating settings window rather than a full-screen sheet: the page stays
 * visible around it, so a change can be judged without closing the window.
 * Reading options live on their own page, reachable from the menu and opened
 * directly by the gear button in the reader.
 */
@Composable
fun SettingsDialog(
    route: Int,
    settings: UiSettings,
    onClose: () -> Unit,
    onOpenReading: () -> Unit,
    onBackToRoot: () -> Unit,
    onClearProgress: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme

    // Keep the last visible page during the exit animation, otherwise the
    // content would snap back to the menu while the window fades out.
    val pageFade = motionTween<Float>(MotionSpec.SHORT)
    var shown by remember { mutableIntStateOf(if (route == 0) SettingsRoute.ROOT else route) }
    LaunchedEffect(route) {
        if (route != SettingsRoute.CLOSED) shown = route
    }

    AnimatedVisibility(
        visible = route != SettingsRoute.CLOSED,
        enter = fadeIn(motionTween(MotionSpec.SHORT)) +
            scaleIn(
                initialScale = 0.94f,
                animationSpec = motionTween(MotionSpec.MEDIUM, MotionSpec.Emphasized)
            ),
        exit = fadeOut(motionTween(MotionSpec.SHORT)) +
            scaleOut(
                targetScale = 0.96f,
                animationSpec = motionTween(MotionSpec.MEDIUM, MotionSpec.Emphasized)
            )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Only a light scrim: the window itself is translucent, so the page
            // behind it has to stay visible — that is the whole point of a
            // floating window rather than a full-screen panel.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.38f))
                    .clickable(onClick = onClose)
            )
            val shape = RoundedCornerShape(28.dp)
            val glass = scheme.surfaceContainerLow
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.93f)
                    .heightIn(max = 620.dp)
                    .shadow(24.dp, shape, clip = false)
                    .clip(shape)
                    // A vertical fade instead of one flat alpha: slightly denser
                    // at the top where the title sits, thinner at the bottom so
                    // the artwork keeps showing through.
                    .background(
                        Brush.verticalGradient(
                            listOf(glass.copy(alpha = 0.88f), glass.copy(alpha = 0.74f))
                        )
                    )
                    .border(1.dp, scheme.onSurface.copy(alpha = 0.10f), shape)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (shown == SettingsRoute.READING) {
                            PillButton(S.back, onBackToRoot, filled = false)
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            text = if (shown == SettingsRoute.READING) {
                                S.settings
                            } else S.settingsRoot,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.weight(1f)
                        )
                        PillButton(S.close, onClose, filled = false)
                    }
                    Spacer(Modifier.height(16.dp))

                    AnimatedContent(
                        targetState = shown,
                        transitionSpec = { fadeIn(pageFade) togetherWith fadeOut(pageFade) },
                        label = "settingsPage"
                    ) { page ->
                        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                            if (page == SettingsRoute.READING) {
                                ReadingSettings(settings)
                            } else {
                                RootSettings(
                                    settings = settings,
                                    onOpenReading = onOpenReading,
                                    onClearProgress = onClearProgress
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RootSettings(
    settings: UiSettings,
    onOpenReading: () -> Unit,
    onClearProgress: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme

    MenuRow(
        title = S.settings,
        subtitle = S.settingsReadingSummary,
        onClick = onOpenReading
    )
    Spacer(Modifier.height(22.dp))

    SectionLabel(S.appearance)
    SegmentedChoice(
        options = listOf(S.themeSystem, S.themeLight, S.themeDark),
        selected = settings.themeMode,
        onSelect = settings::setThemeMode,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(22.dp))

    SectionLabel(S.gridColumns)
    SegmentedChoice(
        options = S.columnOptions,
        selected = settings.gridColumns.coerceIn(2, 5) - 2,
        onSelect = { settings.setGridColumns(it + 2) },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(22.dp))

    PillButton(
        S.clearProgress,
        onClearProgress,
        filled = false,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(20.dp))

    Text(S.aboutTitle, style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(6.dp))
    Text(
        text = S.aboutBody,
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant
    )
}

@Composable
private fun ReadingSettings(settings: UiSettings) {
    val scheme = MaterialTheme.colorScheme

    SectionLabel(S.readMode)
    SegmentedChoice(
        options = S.readModeOptions,
        selected = settings.readMode,
        onSelect = settings::setReadMode,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = S.readModeTip,
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant
    )
    Spacer(Modifier.height(18.dp))

    SectionLabel(S.fitMode)
    ChoiceChips(
        options = S.fitOptions,
        selected = settings.fitMode,
        onSelect = settings::setFitMode,
        modifier = Modifier.fillMaxWidth()
    )
    if (settings.fitMode == ZoomMode.FIXED) {
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = S.zoomPercent,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "${settings.zoomPercent}%",
                style = MaterialTheme.typography.titleMedium,
                color = scheme.primary
            )
        }
        Slider(
            value = settings.zoomPercent.toFloat(),
            onValueChange = { settings.setZoomPercent(it.toInt()) },
            valueRange = 40f..400f,
            steps = 35,
            colors = SliderDefaults.colors(
                thumbColor = scheme.primary,
                activeTrackColor = scheme.primary
            )
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = S.zoomTip,
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant
    )
    Spacer(Modifier.height(18.dp))

    SectionLabel(S.alignMode)
    SegmentedChoice(
        options = S.alignOptions,
        selected = settings.alignment,
        onSelect = settings::setAlignment,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(18.dp))

    SectionLabel(S.direction)
    SegmentedChoice(
        options = listOf(S.ltr, S.rtl),
        selected = settings.readingDirection,
        onSelect = settings::setReadingDirection,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(18.dp))

    SectionLabel(S.pageMode)
    SegmentedChoice(
        options = listOf(S.single, S.double),
        selected = if (settings.doublePage) 1 else 0,
        onSelect = { settings.setDoublePage(it == 1) },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(18.dp))

    SectionLabel(S.screenOrientation)
    SegmentedChoice(
        options = S.orientationOptions,
        selected = settings.screenOrientation,
        onSelect = settings::setScreenOrientation,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(18.dp))

    SectionLabel(S.smoothing)
    SegmentedChoice(
        options = S.smoothOptions,
        selected = settings.smoothing,
        onSelect = settings::setSmoothing,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(18.dp))

    SectionLabel(S.brightness)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = S.brightnessSystem,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = settings.brightness < 0,
            onCheckedChange = { follow ->
                settings.setBrightness(if (follow) -1 else 50)
            }
        )
    }
    if (settings.brightness >= 0) {
        Slider(
            value = settings.brightness.coerceIn(1, 100).toFloat(),
            onValueChange = { settings.setBrightness(it.toInt()) },
            valueRange = 1f..100f,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "${settings.brightness}%",
            style = MaterialTheme.typography.labelMedium,
            color = scheme.onSurfaceVariant
        )
    }
    Spacer(Modifier.height(18.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = S.tapToTurn, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = S.tapToTurnTip,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
        }
        Switch(
            checked = settings.tapToTurn,
            onCheckedChange = settings::setTapToTurn
        )
    }
    Spacer(Modifier.height(12.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = S.keepAwake,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = settings.keepAwake,
            onCheckedChange = settings::setKeepAwake
        )
    }
}

@Composable
private fun MenuRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceContainerHigh.copy(alpha = 0.72f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.headlineSmall,
                color = scheme.onSurfaceVariant
            )
        }
    }
}
