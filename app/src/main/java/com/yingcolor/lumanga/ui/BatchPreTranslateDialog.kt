package com.yingcolor.lumanga.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yingcolor.lumanga.S
import com.yingcolor.lumanga.data.BatchPreTranslate
import com.yingcolor.lumanga.data.Book
import com.yingcolor.lumanga.data.OcrModel
import com.yingcolor.lumanga.data.TextLayout
import com.yingcolor.lumanga.data.TranslationStore
import com.yingcolor.lumanga.data.UiSettings
import com.yingcolor.lumanga.util.folderLabel

/**
 * Folder-wide pre-translate. The work itself lives in [BatchPreTranslate], so this window can be
 * closed at any time; the shelf banner is then the way back in.
 */
@Composable
fun BatchPreTranslateDialog(
    books: List<Book>,
    settings: UiSettings,
    initialGroup: String = "",
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val appContext = context.applicationContext

    var api by remember { mutableStateOf(settings.translateApi) }
    var apiKey by remember { mutableStateOf(settings.translateApiKey) }
    var model by remember { mutableStateOf(settings.translateModel) }
    var scope by remember {
        mutableStateOf(if (initialGroup.isEmpty()) "" else "group:$initialGroup")
    }
    var skipDone by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmStart by remember { mutableStateOf(false) }
    var queuedTargets by remember { mutableStateOf<List<Book>>(emptyList()) }

    val state by BatchPreTranslate.state.collectAsState()
    val completedRev by TranslationStore.completedRev.collectAsState()

    // A library often keeps every volume directly under an added folder, so the group name is
    // empty. Offering the added folders alongside real sub-groups keeps "by folder" useful in
    // both layouts.
    val scopes = remember(books) {
        val groups = books.mapNotNull { it.group.takeIf { g -> g.isNotEmpty() } }
            .distinct().sorted().map { "group:$it" to it }
        val roots = books.mapNotNull { it.rootId.takeIf { r -> r.isNotEmpty() } }
            .distinct().sorted().map { "root:$it" to folderLabel(it) }
        listOf("" to S.batchScopeAll) + groups + roots
    }
    val scopeLabel = remember(scope, scopes) {
        scopes.firstOrNull { it.first == scope }?.second ?: S.batchScopeAll
    }
    val scoped = remember(books, scope) {
        when {
            scope.isEmpty() -> books
            scope.startsWith("group:") ->
                books.filter { it.group == scope.removePrefix("group:") }
            scope.startsWith("root:") ->
                books.filter { it.rootId == scope.removePrefix("root:") }
            else -> books
        }
    }
    val doneCount = remember(scoped, completedRev) {
        scoped.count { TranslationStore.isComplete(appContext, it.id) }
    }

    if (confirmStart) {
        AlertDialog(
            onDismissRequest = { confirmStart = false },
            title = { Text(S.batchConfirmTitle) },
            text = { Text(S.batchConfirmBody(queuedTargets.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmStart = false
                    message = null
                    BatchPreTranslate.start(
                        appContext, scopeLabel, queuedTargets,
                        api.trim(), apiKey.trim(), model.trim()
                    )
                }) { Text(S.batchConfirmStart) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStart = false }) { Text(S.cancel) }
            }
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(S.batchDiscardTitle) },
            text = { Text(S.batchDiscardBody) },
            confirmButton = {
                TextButton(onClick = {
                    BatchPreTranslate.discard(appContext)
                    confirmDiscard = false
                    message = null
                }) { Text(S.batchDiscardConfirm) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(S.cancel) }
            }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 20.dp, vertical = 20.dp).heightIn(max = 700.dp)) {
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(S.batchTitle, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        S.batchIntro,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(Modifier.height(14.dp))
                    Text(S.batchScope, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item {
                            ScopeChip(
                                label = S.batchScopeAll,
                                selected = scope.isEmpty(),
                                onClick = { scope = "" }
                            )
                        }
                        items(scopes.size - 1) { index ->
                            val (key, label) = scopes[index + 1]
                            ScopeChip(
                                label = label,
                                selected = scope == key,
                                onClick = { scope = key }
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        S.batchBookCount(scoped.size, doneCount),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            S.batchSkipDone,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = skipDone,
                            onCheckedChange = { skipDone = it },
                            enabled = !state.running
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = api,
                        onValueChange = { api = it },
                        label = { Text(S.translateApi) },
                        singleLine = true,
                        enabled = !state.running,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(S.preTranslateApiKey) },
                        singleLine = true,
                        enabled = !state.running,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    ModelField(
                        value = model,
                        onValueChange = { model = it; settings.setTranslateModel(it) },
                        api = api,
                        apiKey = apiKey,
                        enabled = !state.running,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            S.preTranslateOcr,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            S.preTranslateOcrValue(OcrModel.from(settings.ocrModel).label),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            S.preTranslateLayout,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            S.preTranslateOcrValue(
                                if (settings.textLayout == TextLayout.HORIZONTAL) {
                                    S.translateLayoutHorizontalShort
                                } else S.translateLayoutVerticalShort
                            ),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }

                    Spacer(Modifier.height(14.dp))
                    if (state.running) {
                        LinearProgressIndicator(
                            progress = {
                                if (state.pageTotal > 0) {
                                    state.pageDone.toFloat() / state.pageTotal
                                } else 0f
                            },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            S.batchRunning(
                                state.doneBooks + 1,
                                state.totalBooks,
                                state.bookName,
                                state.pageDone,
                                state.pageTotal
                            ),
                            style = MaterialTheme.typography.labelMedium
                        )
                        Text(
                            S.batchKeepOpen,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else if (state.pending.isNotEmpty()) {
                        Text(
                            S.batchPending(state.pending.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        TextButton(onClick = { confirmDiscard = true }) {
                            Text(S.batchDiscard)
                        }
                    }
                    val status = message ?: state.message.takeIf { it.isNotEmpty() }
                    if (status != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            status,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (state.running) {
                        Button(onClick = { BatchPreTranslate.cancel() }) { Text(S.batchStop) }
                    } else {
                        Button(
                            onClick = {
                                if (api.isBlank()) {
                                    message = S.batchNeedEndpoint
                                    return@Button
                                }
                                val targets = if (skipDone) {
                                    scoped.filterNot { TranslationStore.isComplete(appContext, it.id) }
                                } else scoped
                                if (targets.isEmpty()) {
                                    message = if (scoped.isEmpty()) S.batchNoBooks else S.batchAllDone
                                    return@Button
                                }
                                settings.setTranslateApi(api.trim())
                                settings.setTranslateModel(model.trim())
                                settings.setTranslateApiKey(apiKey.trim())
                                queuedTargets = targets
                                confirmStart = true
                            }
                        ) { Text(S.batchStart) }
                        if (state.pending.isNotEmpty()) {
                            Spacer(Modifier.width(6.dp))
                            TextButton(
                                onClick = {
                                    if (api.isBlank()) {
                                        message = S.batchNeedEndpoint
                                    } else {
                                        settings.setTranslateApi(api.trim())
                                        settings.setTranslateModel(model.trim())
                                        settings.setTranslateApiKey(apiKey.trim())
                                        message = null
                                        BatchPreTranslate.resume(
                                            appContext, api.trim(), apiKey.trim(), model.trim()
                                        )
                                    }
                                }
                            ) { Text(S.batchResume) }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(S.batchClose) }
                }
            }
        }
    }
}

@Composable
private fun ScopeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .background(
                if (selected) scheme.primaryContainer else scheme.surfaceVariant,
                RoundedCornerShape(50)
            )
            .border(
                width = 1.dp,
                color = if (selected) scheme.primary else scheme.outlineVariant,
                shape = RoundedCornerShape(50)
            )
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) scheme.onPrimaryContainer else scheme.onSurfaceVariant
        )
    }
}
