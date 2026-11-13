package com.yingcolor.lumanga.ui

import android.graphics.Rect
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.yingcolor.lumanga.S
import com.yingcolor.lumanga.data.ApiProtocol
import com.yingcolor.lumanga.data.Concurrency
import com.yingcolor.lumanga.data.OcrModel
import com.yingcolor.lumanga.data.SourceLanguage
import com.yingcolor.lumanga.data.TextLayout
import com.yingcolor.lumanga.data.TextRegion
import com.yingcolor.lumanga.data.TranslateMode
import com.yingcolor.lumanga.data.TranslationEngine
import com.yingcolor.lumanga.data.UiSettings
import kotlinx.coroutines.launch

/** Recognition only: which model reads the page, and what language is printed on it. */
@Composable
fun OcrSettingsPage(settings: UiSettings) {
    Column {
        OcrModelPicker(settings)
        Spacer(Modifier.height(18.dp))
        ConcurrencyField(
            label = S.concurrencyOcr,
            tip = S.concurrencyOcrTip,
            value = settings.ocrConcurrency,
            min = Concurrency.OCR_MIN,
            max = Concurrency.OCR_MAX,
            enabled = settings.ocrModel == OcrModel.SMALL.id,
            disabledHint = S.concurrencyOcrUnavailable,
            onChange = settings::setOcrConcurrency
        )
        Spacer(Modifier.height(18.dp))
        SourceLanguagePicker(settings)
    }
}

/**
 * Everything about the translation service and the text it returns: wire format, endpoint,
 * key, model, a live connection test, the language to translate into, how the result is laid
 * out, and whether each bubble is sent on its own.
 */
@Composable
fun ModelSettingsPage(settings: UiSettings) {
    val scheme = MaterialTheme.colorScheme
    var api by remember { mutableStateOf(settings.translateApi) }
    var model by remember { mutableStateOf(settings.translateModel) }
    var apiKey by remember { mutableStateOf(settings.translateApiKey) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column {
        ApiProtocolPicker(
            current = settings.translateProtocol,
            onSelect = {
                settings.setTranslateProtocol(it)
                // A result from the previous wire format says nothing about this one.
                testResult = null
            }
        )
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
        Spacer(Modifier.height(10.dp))
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
        Spacer(Modifier.height(6.dp))
        Text(
            text = S.translateOnlyText,
            style = MaterialTheme.typography.labelSmall,
            color = scheme.primary
        )

        Spacer(Modifier.height(20.dp))
        TargetLanguagePicker(settings)

        Spacer(Modifier.height(16.dp))
        // A reading preference, not a recognition one: stored pages keep their layout and text,
        // so flipping this redraws them immediately without another OCR pass or network call.
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
        // A request strategy rather than a service setting: it changes how many calls a page
        // costs, so it belongs to this page but not next to the endpoint fields.
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

        Spacer(Modifier.height(16.dp))
        // Only pre-translation reads this; the reader translates the page it shows.
        ConcurrencyField(
            label = S.concurrencyTranslate,
            tip = S.concurrencyTranslateTip,
            value = settings.translateConcurrency,
            min = Concurrency.TRANSLATE_MIN,
            max = Concurrency.TRANSLATE_MAX,
            onChange = settings::setTranslateConcurrency
        )
    }
}

/**
 * A one- or two-digit field next to its label. Input is clamped to [min]..[max] as it is typed;
 * [remember] keyed on [value] snaps the text back to the stored number whenever it is clamped.
 */
@Composable
private fun ConcurrencyField(
    label: String,
    tip: String,
    value: Int,
    min: Int,
    max: Int,
    enabled: Boolean = true,
    disabledHint: String? = null,
    onChange: (Int) -> Unit
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = tip,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(10.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(2)
                    text = digits
                    digits.toIntOrNull()?.let { onChange(it.coerceIn(min, max)) }
                },
                singleLine = true,
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(76.dp)
            )
        }
        if (!enabled && disabledHint != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = disabledHint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
