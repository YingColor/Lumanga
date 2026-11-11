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
import com.yingcolor.lumanga.data.Book
import com.yingcolor.lumanga.data.BookPreview
import com.yingcolor.lumanga.data.PreTranslate
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

    var api by remember { mutableStateOf(settings.translateApi) }
    var apiKey by remember { mutableStateOf(settings.translateApiKey) }
    var model by remember { mutableStateOf(settings.translateModel) }

    var preview by remember { mutableStateOf<BookPreview?>(null) }
    var selected by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var storedCount by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var doneCount by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }

    val thumbs = remember { LruCache<Int, Bitmap>(48) }

    LaunchedEffect(book.id) {
        storedCount = TranslationStore.count(context, book.id)
        val opened = withContext(Dispatchers.IO) { runCatching { BookPreview(context, book) }.getOrNull() }
        preview = opened
        selected = (0 until (opened?.pageCount ?: 0)).toSet()
    }

    DisposableEffect(book.id) {
        onDispose {
            job?.cancel()
            preview?.close()
        }
    }

    val pageCount = preview?.pageCount ?: 0
    val runningText = if (running) S.preTranslateRunning(doneCount, selected.size) else null

    Dialog(
        onDismissRequest = { if (!running) { job?.cancel(); onDismiss() } },
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

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = api,
                    onValueChange = { api = it },
                    label = { Text(S.translateApi) },
                    singleLine = true,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
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
                    onValueChange = { model = it; settings.setTranslateModel(it) },
                    api = api,
                    apiKey = apiKey,
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = S.preTranslateOcr,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = S.preTranslateOcrValue,
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
                        Button(onClick = { job?.cancel() }) { Text(S.preTranslateStop) }
                    } else {
                        Button(
                            enabled = selected.isNotEmpty(),
                            onClick = {
                                if (api.isBlank()) {
                                    message = S.preTranslateNeedEndpoint
                                    return@Button
                                }
                                if (selected.isEmpty()) {
                                    message = S.preTranslateNeedPages
                                    return@Button
                                }
                                settings.setTranslateApi(api.trim())
                                settings.setTranslateModel(model.trim())
                                settings.setTranslateApiKey(apiKey.trim())
                                message = null
                                doneCount = 0
                                running = true
                                val pages = selected.sorted()
                                job = scope.launch {
                                    val result = PreTranslate.run(
                                        context = context,
                                        book = book,
                                        pages = pages,
                                        api = api.trim(),
                                        apiKey = apiKey.trim(),
                                        model = model.trim()
                                    ) { done, _ ->
                                        withContext(Dispatchers.Main) { doneCount = done }
                                    }
                                    running = false
                                    storedCount = TranslationStore.count(context, book.id)
                                    message = when (result) {
                                        PreTranslate.Result.Done -> S.preTranslateDone(storedCount)
                                        is PreTranslate.Result.Failed ->
                                            S.preTranslateFailed(result.page)
                                        PreTranslate.Result.Cancelled -> S.preTranslateCancelled
                                    }
                                }
                            }
                        ) { Text(S.preTranslateStart) }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        enabled = !running,
                        onClick = { job?.cancel(); onDismiss() }
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
