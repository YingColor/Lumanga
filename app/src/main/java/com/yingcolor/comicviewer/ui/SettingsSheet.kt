package com.yingcolor.comicviewer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yingcolor.comicviewer.S
import com.yingcolor.comicviewer.data.UiSettings

@Composable
fun SettingsSheet(
    visible: Boolean,
    settings: UiSettings,
    onClose: () -> Unit,
    onClearProgress: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme

    // The sheet stays in the composition so both entry and exit can animate.
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(motionTween(MotionSpec.SHORT)) +
            slideInVertically(
                motionTween(MotionSpec.LONG, MotionSpec.Emphasized)
            ) { it / 3 } +
            scaleIn(
                initialScale = 0.96f,
                animationSpec = motionTween(MotionSpec.LONG, MotionSpec.Emphasized)
            ),
        exit = fadeOut(motionTween(MotionSpec.SHORT)) +
            slideOutVertically(
                motionTween(MotionSpec.MEDIUM, MotionSpec.Emphasized)
            ) { it / 3 } +
            scaleOut(
                targetScale = 0.98f,
                animationSpec = motionTween(MotionSpec.MEDIUM, MotionSpec.Emphasized)
            )
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(onClick = onClose)
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
                color = scheme.surfaceContainerLow
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = S.settings,
                            style = MaterialTheme.typography.headlineSmall,
                            modifier = Modifier.weight(1f)
                        )
                        PillButton(S.close, onClose, filled = false)
                    }
                    Spacer(Modifier.height(24.dp))

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
                    SegmentedChoice(
                        options = S.fitOptions,
                        selected = settings.fitMode,
                        onSelect = settings::setFitMode,
                        modifier = Modifier.fillMaxWidth()
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
                    Spacer(Modifier.height(22.dp))

                    SectionLabel(S.direction)
                    SegmentedChoice(
                        options = listOf(S.ltr, S.rtl),
                        selected = settings.readingDirection,
                        onSelect = settings::setReadingDirection,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(22.dp))

                    SectionLabel(S.pageMode)
                    SegmentedChoice(
                        options = listOf(S.single, S.double),
                        selected = if (settings.doublePage) 1 else 0,
                        onSelect = { settings.setDoublePage(it == 1) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(18.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = S.tapToTurn,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = settings.tapToTurn,
                            onCheckedChange = settings::setTapToTurn
                        )
                    }
                    Spacer(Modifier.height(18.dp))

                    SectionLabel(S.gridColumns)
                    SegmentedChoice(
                        options = S.columnOptions,
                        selected = settings.gridColumns.coerceIn(2, 5) - 2,
                        onSelect = { settings.setGridColumns(it + 2) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(22.dp))

                    SectionLabel(S.appearance)
                    SegmentedChoice(
                        options = listOf(S.themeSystem, S.themeLight, S.themeDark),
                        selected = settings.themeMode,
                        onSelect = settings::setThemeMode,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(18.dp))

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
                    Spacer(Modifier.height(16.dp))

                    PillButton(
                        S.clearProgress,
                        onClearProgress,
                        filled = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(24.dp))

                    Text(S.aboutTitle, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = S.aboutBody,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
}
