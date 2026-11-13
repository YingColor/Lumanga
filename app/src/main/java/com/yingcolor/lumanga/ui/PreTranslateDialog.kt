package com.yingcolor.lumanga.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yingcolor.lumanga.S
import com.yingcolor.lumanga.data.ApiProtocol
import com.yingcolor.lumanga.data.OcrModel
import com.yingcolor.lumanga.data.BatchPreTranslate
import com.yingcolor.lumanga.data.Book
import com.yingcolor.lumanga.data.BookPreview
import com.yingcolor.lumanga.data.PreTranslate
import com.yingcolor.lumanga.data.TextLayout
import com.yingcolor.lumanga.data.TranslateMode
import com.yingcolor.lumanga.data.TranslationStore
import com.yingcolor.lumanga.data.UiSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Whole-book translation: pick pages off a thumbnail strip, fill in the endpoint, run it once.
 *
 * The run writes into [TranslationStore], so the reader finds finished pages later without
 * touching OCR or the network again.
 */
@Composable
fun PreTranslateDialog(
    book: Book,
    settings: UiSettings,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // These three (plus the protocol below) belong to pre-translate alone. An untouched item
    // reads the global value, so it keeps following changes made in 翻译配置; the first edit
    // stores an override and leaves the global config alone.
    var api by remember { mutableStateOf(settings.preApi) }
    var apiKey by remember { mutableStateOf(settings.preApiKey) }
    var model by remember { mutableStateOf(settings.preModel) }

    var preview by remember { mutableStateOf<BookPreview?>(null) }
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var storedCount by remember { mutableIntStateOf(0) }
    var resumeOnly by remember { mutableStateOf(true) }
    var running by remember { mutableStateOf(false) }
    var doneCount by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    val thumbs = remember { LruCache<Int, Bitmap>(48) }

    LaunchedEffect(book.id) {
        storedCount = TranslationStore.count(context, book.id)
        val opened = withContext(Dispatchers.IO) { runCatching { BookPreview(context, book) }.getOrNull() }
        preview = opened
        val count = opened?.pageCount ?: 0
        selected = (0 until count).toSet()
        // Volumes finished by an older build have no "done" flag yet; the picker knows the
        // page count here, so light up the shelf badge without making the user re-run them.
        if (count > 0 && storedCount >= count) {
            withContext(Dispatchers.IO) { TranslationStore.markComplete(context, book.id, count) }
        }
    }

    DisposableEffect(book.id) {
        onDispose {
            job?.cancel()
            PreTranslate.stop()
            preview?.close()
        }
    }

    val pageCount = preview?.pageCount ?: 0
    val runningText = if (running) S.preTranslateRunning(doneCount, selected.size) else null

    Dialog(
        onDismissRequest = {
            if (!running) {
                job?.cancel()
                PreTranslate.stop()
                onDismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 20.dp)
                    .heightIn(max = 700.dp)
            ) {
              // Only the settings and the picker scroll; the action row stays reachable at the
              // bottom once a run has produced a status line.
              Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
              ) {
                Text(S.preTranslateTitle, style = MaterialTheme.typography.titleMedium)
                Text(
                    text = book.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = S.preTranslateIntro,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (storedCount > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = S.preTranslateStored(storedCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = S.preTranslateResumeToggle,
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            text = S.preTranslateResumeHint,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = resumeOnly,
                        onCheckedChange = { resumeOnly = it },
                        enabled = !running
                    )
                }

                Spacer(Modifier.height(12.dp))
                ApiProtocolPicker(
                    current = settings.preProtocol,
                    onSelect = settings::setPreProtocol,
                    enabled = !running
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = api,
                    onValueChange = { api = it; settings.setPreApi(it) },
                    label = { Text(S.translateApi) },
                    singleLine = true,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it; settings.setPreApiKey(it) },
                    label = { Text(S.preTranslateApiKey) },
                    singleLine = true,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                ModelField(
                    value = model,
                    // Persist right away so a model picked here is still selected next time,
                    // even if the panel is closed without starting a run.
                    onValueChange = { model = it; settings.setPreModel(it) },
                    api = api,
                    apiKey = apiKey,
                    protocol = settings.preProtocol,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = S.preTranslateConfigTip,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (settings.preCustomised) {
                    TextButton(
                        enabled = !running,
                        onClick = {
                            settings.resetPreConfig()
                            api = settings.preApi
                            apiKey = settings.preApiKey
                            model = settings.preModel
                            message = S.preTranslateFollowing
                        }
                    ) { Text(S.preTranslateFollowGlobal) }
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = S.preTranslateOcr,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = S.preTranslateOcrValue(OcrModel.from(settings.ocrModel).label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = S.preTranslateMode,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = if (settings.translateMode == TranslateMode.GROUP) {
                            S.translateModeGroupShort
                        } else S.translateModePageShort,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = S.preTranslateLayout,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = S.preTranslateOcrValue(
                            if (settings.textLayout == TextLayout.HORIZONTAL) {
                                S.translateLayoutHorizontalShort
                            } else S.translateLayoutVerticalShort
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = S.preTranslateLanguage,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = S.preTranslateLanguageValue(
                            com.yingcolor.lumanga.data.SourceLanguage.from(settings.sourceLanguage)
                                .let {
                                    if (it.detected) S.translateSourceLanguageAutoShort else it.label
                                },
                            com.yingcolor.lumanga.data.TargetLanguage.from(settings.targetLanguage).label
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = S.preTranslateSelected(selected.size, pageCount),
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        enabled = !running && pageCount > 0,
                        onClick = { selected = (0 until pageCount).toSet() }
                    ) { Text(S.preTranslateSelectAll) }
                    TextButton(
                        enabled = !running && pageCount > 0,
                        onClick = { selected = emptySet() }
                    ) { Text(S.preTranslateSelectNone) }
                }
                Text(
                    text = S.preTranslatePages,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))

                // The same idea as the reader's page strip: tap a thumbnail to include or drop
                // that page. Everything is selected up front, so "translate everything" is one tap.
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(pageCount) { index ->
                        val checked = index in selected
                        PageThumb(
                            index = index,
                            preview = preview,
                            checked = checked,
                            thumbs = thumbs,
                            onClick = {
                                if (!running) {
                                    selected = if (checked) selected - index else selected + index
                                }
                            }
                        )
                    }
                }

                if (running || message != null) {
                    Spacer(Modifier.height(12.dp))
                    if (running) {
                        LinearProgressIndicator(
                            progress = {
                                if (selected.isEmpty()) 0f
                                else doneCount.toFloat() / selected.size
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(
                        text = runningText ?: message.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (message == null) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else MaterialTheme.colorScheme.primary
                    )
                    if (running) {
                        Text(
                            text = S.preTranslateKeepOpen,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

              }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (running) {
                        Button(onClick = {
                            // Reset the UI here, not after the coroutine returns: a cancelled
                            // job never reaches the code below, which used to leave the panel
                            // stuck on "running" until the app was restarted.
                            PreTranslate.stop()
                            job?.cancel()
                            running = false
                            message = S.preTranslateCancelled
                        }) { Text(S.preTranslateStop) }
                    } else {
                        Button(
                            enabled = selected.isNotEmpty(),
                            onClick = {
                                if (BatchPreTranslate.state.value.running) {
                                    message = S.preTranslateBusy
                                    return@Button
                                }
                                if (api.isBlank()) {
                                    message = S.preTranslateNeedEndpoint
                                    return@Button
                                }
                                if (selected.isEmpty()) {
                                    message = S.preTranslateNeedPages
                                    return@Button
                                }
                                // The endpoint fields above are already stored as pre-translate
                                // overrides; never write them into the global config.
                                message = null
                                doneCount = 0
                                running = true
                                val pages = selected.sorted()
                                job = scope.launch {
                                    try {
                                        val result = PreTranslate.run(
                                            context = context,
                                            book = book,
                                            pages = pages,
                                            api = api.trim(),
                                            apiKey = apiKey.trim(),
                                            model = model.trim(),
                                            protocol = ApiProtocol.from(settings.preProtocol),
                                            groupMode = settings.translateMode == TranslateMode.GROUP,
                                            skipStored = resumeOnly
                                        ) { done, _ ->
                                            withContext(Dispatchers.Main) { doneCount = done }
                                        }
                                        storedCount = TranslationStore.count(context, book.id)
                                        message = when (result) {
                                            is PreTranslate.Result.Done -> when {
                                                // A page that failed is skipped, so the count can be
                                                // short of the volume — say so instead of "完成".
                                                result.failed.isNotEmpty() ->
                                                    S.preTranslateDoneWithFailures(
                                                        storedCount, result.failed.size
                                                    )
                                                result.skipped > 0 ->
                                                    S.preTranslateDoneResume(result.translated, result.skipped)
                                                else -> S.preTranslateDone(storedCount)
                                            }
                                            is PreTranslate.Result.Failed ->
                                                S.preTranslateFailed(result.page)
                                            PreTranslate.Result.Cancelled -> S.preTranslateCancelled
                                        }
                                    } finally {
                                        // Cancellation is a normal way out of this coroutine; the
                                        // panel must not stay frozen in its "running" state.
                                        running = false
                                    }
                                }
                            }
                        ) { Text(S.preTranslateStart) }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        enabled = !running,
                        onClick = {
                            job?.cancel()
                            PreTranslate.stop()
                            onDismiss()
                        }
                    ) { Text(S.cancel) }
                }
            }
        }
    }
}

@Composable
private fun PageThumb(
    index: Int,
    preview: BookPreview?,
    checked: Boolean,
    thumbs: LruCache<Int, Bitmap>,
    onClick: () -> Unit
) {
    val bitmap by produceState<Bitmap?>(initialValue = thumbs.get(index), key1 = index, key2 = preview) {
        if (value == null && preview != null) {
            value = withContext(Dispatchers.IO) {
                preview.thumbnail(index, 96)?.also { thumbs.put(index, it) }
            }
        }
    }
    val border = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Box(
        modifier = Modifier
            .width(66.dp)
            .aspectRatio(0.7f)
            .clickable(onClick = onClick)
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp)
            )
            .border(
                width = if (checked) 2.dp else 1.dp,
                color = border,
                shape = RoundedCornerShape(8.dp)
            )
    ) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }
        // Dim the pages that will be skipped so the selection reads at a glance.
        if (!checked) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(8.dp))
            )
        }
        Text(
            text = (index + 1).toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(3.dp)
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
                .padding(horizontal = 3.dp)
        )
        if (checked) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
                    .size(16.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                    ,
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(11.dp)
                )
            }
        }
    }
}
