package com.yingcolor.lumanga.ui

import android.os.SystemClock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import kotlinx.coroutines.delay

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.yingcolor.lumanga.S
import com.yingcolor.lumanga.data.UiSettings
import com.yingcolor.lumanga.theme.Palettes
import com.yingcolor.lumanga.data.ZoomMode


object SettingsRoute {
    const val CLOSED = 0

    
    const val ROOT = 1

    
    const val READING = 2

    
    const val LIBRARIES = 3

    
    const val PERSONALIZE = 4
}











@Composable
internal fun SettingsDialog(
    route: Int,
    settings: UiSettings,
    folders: List<Pair<String, String>>,
    backdrop: BackdropShot?,
    onClose: () -> Unit,
    onOpenReading: () -> Unit,
    onOpenLibraries: () -> Unit,
    onOpenPersonalize: () -> Unit,
    onBackToRoot: () -> Unit,
    onClearProgress: () -> Unit,
    onAddFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit
) {
    val scheme = MaterialTheme.colorScheme

    
    
    val pageFade = motionTween<Float>(MotionSpec.SHORT)
    var shown by remember { mutableIntStateOf(if (route == 0) SettingsRoute.ROOT else route) }
    LaunchedEffect(route) {
        if (route != SettingsRoute.CLOSED) shown = route
    }

    val frosted = backdrop != null
    val shape = RoundedCornerShape(28.dp)
    val glass = scheme.surfaceContainerLow
    
    
    
    
    
    
    val topAlpha = if (frosted) 0.68f else 0.97f
    val bottomAlpha = if (frosted) 0.56f else 0.93f
    val rowAlpha = if (frosted) 0.58f else 0.72f
    val scrimAlpha = if (frosted) 0.10f else 0.30f

    
    
    var cardOrigin by remember { mutableStateOf(IntOffset.Zero) }

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
            
            
            
            
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha))
                    .clickable(onClick = onClose)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.93f)
                    .heightIn(max = 620.dp)
                    .shadow(24.dp, shape, clip = false)
                    .clip(shape)
                    .onGloballyPositioned { cardOrigin = it.positionInWindow().round() }
                    
                    
                    
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
            ) {
                if (backdrop != null) {
                    Image(
                        bitmap = backdrop.image,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        filterQuality = FilterQuality.High,
                        modifier = Modifier.layout { measurable, _ ->
                            
                            
                            
                            
                            val placeable = measurable.measure(
                                Constraints.fixed(backdrop.windowWidth, backdrop.windowHeight)
                            )
                            layout(0, 0) {
                                placeable.place(-cardOrigin.x, -cardOrigin.y)
                            }
                        }
                    )
                }
                
                
                
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    glass.copy(alpha = topAlpha),
                                    glass.copy(alpha = bottomAlpha)
                                )
                            )
                        )
                )

                
                
                
                
                CompositionLocalProvider(LocalContentColor provides scheme.onSurface) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (shown != SettingsRoute.ROOT) {
                                PillButton(S.back, onBackToRoot, filled = false)
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(
                                text = when (shown) {
                                    SettingsRoute.READING -> S.settings
                                    SettingsRoute.LIBRARIES -> S.libraries
                                    SettingsRoute.PERSONALIZE -> S.personalize
                                    else -> S.settingsRoot
                                },
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
                                when (page) {
                                    SettingsRoute.READING -> ReadingSettings(settings)
                                    SettingsRoute.PERSONALIZE -> PersonalizeSettings(settings)
                                    SettingsRoute.LIBRARIES -> LibrarySettings(
                                        folders = folders,
                                        rowAlpha = rowAlpha,
                                        onAddFolder = onAddFolder,
                                        onRemoveFolder = onRemoveFolder
                                    )
                                    else -> RootSettings(
                                        settings = settings,
                                        folderCount = folders.size,
                                        rowAlpha = rowAlpha,
                                        onOpenReading = onOpenReading,
                                        onOpenLibraries = onOpenLibraries,
                                        onClearProgress = onClearProgress,
                                        onOpenPersonalize = onOpenPersonalize
                                    )
                                }
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                    }
                }

                
                
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .border(1.dp, scheme.onSurface.copy(alpha = 0.10f), shape)
                )
            }
        }
    }
}

@Composable
private fun RootSettings(
    settings: UiSettings,
    folderCount: Int,
    rowAlpha: Float,
    onOpenReading: () -> Unit,
    onOpenLibraries: () -> Unit,
    onOpenPersonalize: () -> Unit,
    onClearProgress: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme

    
    
    var clearTaps by remember { mutableIntStateOf(0) }
    var lastClearTapAt by remember { mutableStateOf(0L) }
    var showClearWarning by remember { mutableStateOf(false) }
    var clearSecondsLeft by remember { mutableIntStateOf(3) }

    LaunchedEffect(showClearWarning) {
        if (!showClearWarning) return@LaunchedEffect
        clearSecondsLeft = 3
        while (clearSecondsLeft > 0) {
            delay(1000L)
            clearSecondsLeft--
        }
    }

    if (showClearWarning) {
        AlertDialog(
            onDismissRequest = { showClearWarning = false; clearTaps = 0 },
            title = { Text(S.clearWarningTitle) },
            text = { Text(S.clearWarningBody) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearWarning = false
                        clearTaps = 0
                        onClearProgress()
                    },
                    enabled = clearSecondsLeft == 0
                ) {
                    Text(
                        if (clearSecondsLeft > 0) S.clearConfirmCount(clearSecondsLeft)
                        else S.clearConfirm
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearWarning = false; clearTaps = 0 }) {
                    Text(S.cancel)
                }
            }
        )
    }

    MenuRow(
        title = S.settings,
        subtitle = S.settingsReadingSummary,
        rowAlpha = rowAlpha,
        onClick = onOpenReading
    )
    Spacer(Modifier.height(10.dp))
    MenuRow(
        title = S.libraries,
        subtitle = S.foldersCount(folderCount),
        rowAlpha = rowAlpha,
        onClick = onOpenLibraries
    )
    Spacer(Modifier.height(10.dp))
    MenuRow(
        title = S.personalize,
        subtitle = S.personalizeSummary,
        rowAlpha = rowAlpha,
        onClick = onOpenPersonalize
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
        text = if (clearTaps > 0) S.clearProgressTapHint(3 - clearTaps) else S.clearProgress,
        onClick = {
            val now = SystemClock.uptimeMillis()
            
            clearTaps = if (now - lastClearTapAt > 2000L) 1 else clearTaps + 1
            lastClearTapAt = now
            if (clearTaps >= 3) {
                clearTaps = 0
                showClearWarning = true
            }
        },
        filled = false,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = S.clearProgressHint,
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant
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
private fun LibrarySettings(
    folders: List<Pair<String, String>>,
    rowAlpha: Float,
    onAddFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit
) {
    val scheme = MaterialTheme.colorScheme

    Text(
        text = S.librariesHint,
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant
    )
    Spacer(Modifier.height(14.dp))

    folders.forEach { (uri, label) ->
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = scheme.surfaceContainerHigh.copy(alpha = rowAlpha),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp)
                )
                if (folders.size > 1) {
                    Text(
                        text = S.removeFolder,
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.error,
                        modifier = Modifier
                            .clickable { onRemoveFolder(uri) }
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }

    Spacer(Modifier.height(6.dp))
    PillButton(S.addFolder, onAddFolder, filled = true, modifier = Modifier.fillMaxWidth())
    if (folders.size <= 1) {
        Spacer(Modifier.height(8.dp))
        Text(
            text = S.lastFolderTip,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant
        )
    }
}






@Composable
private fun PersonalizeSettings(settings: UiSettings) {
    val scheme = MaterialTheme.colorScheme

    SectionLabel(S.appearance)
    SegmentedChoice(
        options = listOf(S.themeSystem, S.themeLight, S.themeDark),
        selected = settings.themeMode,
        onSelect = settings::setThemeMode,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(20.dp))

    SectionLabel(S.themeColor)
    SwatchRow(selected = settings.themeColor, onSelect = settings::setThemeColor)
    Spacer(Modifier.height(10.dp))
    Text(
        text = S.themeColors.getOrElse(settings.themeColor) { "" },
        style = MaterialTheme.typography.labelLarge,
        color = scheme.primary,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = S.themeColorTip,
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant
    )
}


@Composable
private fun SwatchRow(selected: Int, onSelect: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (index in 0 until Palettes.COUNT) {
            val active = index == selected
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Palettes.accent(index))
                    .border(
                        width = if (active) 3.dp else 1.dp,
                        color = if (active) scheme.onSurface else scheme.onSurface.copy(alpha = 0.15f),
                        shape = CircleShape
                    )
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center
            ) {
                if (active) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
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

    SectionLabel(S.pageAnim)
    SegmentedChoice(
        options = S.pageAnimOptions,
        selected = settings.pageAnim,
        onSelect = settings::setPageAnim,
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = S.pageAnimTip + "，" + S.pageAnimPreview,
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant
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
        selected = S.orientationValues.indexOf(settings.screenOrientation).coerceAtLeast(0),
        onSelect = { settings.setScreenOrientation(S.orientationValues[it]) },
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = S.orientationTip,
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    SmallAction(
        text = S.resetOrientationMemory,
        onClick = settings::clearBookOrientations,
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
    Spacer(Modifier.height(22.dp))
    TranslationSettings(settings)
}

@Composable
private fun MenuRow(
    title: String,
    subtitle: String,
    rowAlpha: Float,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceContainerHigh.copy(alpha = rowAlpha),
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
