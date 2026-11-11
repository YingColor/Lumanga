package com.yingcolor.lumanga.ui

import android.graphics.Rect
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import com.yingcolor.lumanga.S
import com.yingcolor.lumanga.data.TextRegion
import com.yingcolor.lumanga.data.TranslationEngine
import com.yingcolor.lumanga.data.UiSettings
import kotlinx.coroutines.launch

@Composable
fun TranslationSettings(settings: UiSettings) {
    val scheme = MaterialTheme.colorScheme
    var api by remember { mutableStateOf(settings.translateApi) }
    var model by remember { mutableStateOf(settings.translateModel) }
    var apiKey by remember { mutableStateOf(settings.translateApiKey) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Text(text = S.translate, style = MaterialTheme.typography.titleSmall, color = scheme.primary)
    Spacer(Modifier.height(6.dp))
    Text(
        text = S.translateSummary,
        style = MaterialTheme.typography.bodySmall,
        color = scheme.onSurfaceVariant
    )
    Spacer(Modifier.height(12.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = S.translateEnable, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = S.translateEnableTip,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
        }
        Switch(
            checked = settings.translateEnabled,
            onCheckedChange = settings::setTranslateEnabled
        )
    }

    if (settings.translateEnabled) {
        Spacer(Modifier.height(14.dp))
        OutlinedTextField(
            value = api,
            onValueChange = {
                api = it
                settings.setTranslateApi(it)
            },
            label = { Text(S.translateApi) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = apiKey,
            onValueChange = {
                apiKey = it
                settings.setTranslateApiKey(it)
            },
            label = { Text(S.preTranslateApiKey) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = S.preTranslateApiKeyTip,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = model,
            onValueChange = {
                model = it
                settings.setTranslateModel(it)
            },
            label = { Text(S.translateModel) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = S.translateApiTip,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant
        )
        Button(
            onClick = {
                testing = true
                testResult = null
                scope.launch {
                    val probe = listOf(TextRegion("今日はいい天気ですね。", Rect(0, 0, 100, 40)))
                    val groups = TranslationEngine.groupBubbles(probe, 200, 80)
                    val result = TranslationEngine.translate(groups, api, model, emptyList(), apiKey)
                    testResult = if (result != null && result.firstOrNull()?.isNotBlank() == true)
                        S.translateTestOk
                    else
                        S.translateTestFail
                    testing = false
                }
            },
            enabled = !testing
        ) {
            Text(if (testing) S.translateTesting else S.translateTest)
        }
        testResult?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = if (it == S.translateTestOk) scheme.primary else scheme.error
            )
        }
        Text(
            text = S.translateOnlyText,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.primary
        )
    }
}
