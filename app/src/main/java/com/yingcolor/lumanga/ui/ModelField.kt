package com.yingcolor.lumanga.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yingcolor.lumanga.S
import com.yingcolor.lumanga.data.ApiClient
import com.yingcolor.lumanga.data.ApiProtocol
import com.yingcolor.lumanga.data.TranslationEngine
import kotlinx.coroutines.launch

/**
 * Model name field with a "fetch models" button.
 *
 * The endpoint usually serves several models, and typing the id by hand is the easiest way to
 * get it wrong, so the button asks the service for its list and lets the user pick one.
 * Shared by the reader's translation settings and the pre-translate panel.
 */
@Composable
fun ModelField(
    value: String,
    onValueChange: (String) -> Unit,
    api: String,
    apiKey: String,
    protocol: Int,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(S.translateModel) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(6.dp))
        TextButton(
            enabled = enabled && !loading && api.isNotBlank(),
            onClick = {
                loading = true
                error = null
                scope.launch {
                    when (val result = TranslationEngine.fetchModels(
                        api.trim(), apiKey.trim(), ApiProtocol.from(protocol)
                    )) {
                        // The reason ("端口没开" / "Key 不对" / "HTTP 404") tells the user what to
                        // fix; a generic message would send them checking all three at once.
                        is ApiClient.ModelsResult.Fail -> error = S.fetchModelsReason + result.reason
                        is ApiClient.ModelsResult.Ok ->
                            if (result.ids.isEmpty()) error = S.fetchModelsEmpty
                            else options = result.ids
                    }
                    loading = false
                }
            }
        ) {
            Text(if (loading) S.fetchingModels else S.fetchModels)
        }
    }

    error?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(start = 4.dp)
        )
    }

    val list = options
    if (list != null) {
        AlertDialog(
            onDismissRequest = { options = null },
            title = { Text(S.modelPickerTitle) },
            text = {
                Column {
                    Text(
                        text = S.modelPickerCount(list.size),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                        items(list) { id ->
                            Text(
                                text = id,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (id == value) {
                                    MaterialTheme.colorScheme.primary
                                } else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onValueChange(id)
                                        options = null
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { options = null }) { Text(S.cancel) }
            }
        )
    }
}
