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
import com.yingcolor.lumanga.data.ApiProtocol
import com.yingcolor.lumanga.data.SourceLanguage
import com.yingcolor.lumanga.data.TextLayout
import com.yingcolor.lumanga.data.TextRegion
import com.yingcolor.lumanga.data.TranslateMode
import com.yingcolor.lumanga.data.TranslationEngine
import com.yingcolor.lumanga.data.UiSettings
import kotlinx.coroutines.launch

/**
 * Everything the translator needs: recognition model, languages, rendering and the service.
 *
 * There is deliberately no on/off switch here. Turning translation on is the reader's own
 * control (the 译 button, or plain reading with it already filled in); a second switch on this
 * panel could disagree with it and hid the whole service section behind a toggle, which made
 * the panel look empty until it was found.
 */
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

    Spacer(Modifier.height(16.dp))
    OcrModelPicker(settings)
    Spacer(Modifier.height(16.dp))
    LanguagePickers(settings)
    Spacer(Modifier.height(16.dp))

    // A reading preference, not a recognition one: stored pages keep their layout and text, so
    // flipping this redraws them immediately without another OCR pass or network call.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = S.translateLayoutHorizontal, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = S.translateLayoutHorizontalTip,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
        }
        Switch(
            checked = settings.textLayout == TextLayout.HORIZONTAL,
            onCheckedChange = {
                settings.setTextLayout(if (it) TextLayout.HORIZONTAL else TextLayout.VERTICAL)
            }
        )
    }

    Spacer(Modifier.height(16.dp))

    ApiProtocolPicker(
        current = settings.translateProtocol,
        onSelect = {
            settings.setTranslateProtocol(it)
            // A result from the previous wire format says nothing about this one.
            testResult = null
        }
    )
    Spacer(Modifier.height(12.dp))
    // A request strategy rather than a service setting, so it sits right under the protocol.
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = S.translateModeGroup, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = S.translateModeGroupTip,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
        }
        Switch(
            checked = settings.translateMode == TranslateMode.GROUP,
            onCheckedChange = {
                settings.setTranslateMode(if (it) TranslateMode.GROUP else TranslateMode.PAGE)
                testResult = null
            }
        )
    }
    Spacer(Modifier.height(12.dp))
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
    ModelField(
        value = model,
        onValueChange = {
            model = it
            settings.setTranslateModel(it)
        },
        api = api,
        apiKey = apiKey,
        protocol = settings.translateProtocol,
        enabled = true,
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
                // The probe has to be written in the language the page is printed in, or
                // isUsable() rejects it and the connection test reports a false failure.
                // "Auto" gets the Japanese sample: it is the pair this app is built for, and
                // resolveSource() then settles the probe exactly like it would settle a page.
                val probeText = when (SourceLanguage.from(settings.sourceLanguage)) {
                    SourceLanguage.ENGLISH -> "Hello, how are you today?"
                    SourceLanguage.CHINESE -> "今天天气真好啊。"
                    SourceLanguage.JAPANESE, SourceLanguage.AUTO -> "今日はいい天気ですね。"
                }
                val probe = listOf(TextRegion(probeText, Rect(0, 0, 100, 40)))
                val probeLang = TranslationEngine.resolveSource(probe)
                val groups = TranslationEngine.groupBubbles(
                    probe, 200, 80, bitmap = null, lang = probeLang
                )
                val result = TranslationEngine.translate(
                    groups, api, model, emptyList(), apiKey,
                    protocol = ApiProtocol.from(settings.translateProtocol),
                    sourceLang = probeLang
                )
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
