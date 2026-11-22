package com.yingcolor.lumanga.ui

import android.graphics.Rect
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.yingcolor.lumanga.BuildConfig
import com.yingcolor.lumanga.S
import com.yingcolor.lumanga.data.ApiClient
import com.yingcolor.lumanga.data.ApiProtocol
import com.yingcolor.lumanga.data.CoverStyle
import com.yingcolor.lumanga.data.OcrModel
import com.yingcolor.lumanga.data.SourceLanguage
import com.yingcolor.lumanga.data.TextLayout
import com.yingcolor.lumanga.data.TextRegion
import com.yingcolor.lumanga.data.TranslateMode
import com.yingcolor.lumanga.data.TranslationEngine
import com.yingcolor.lumanga.data.UiSettings
import kotlinx.coroutines.launch

/** Recognition only: which engine reads the page, and what language is printed on it. */
@Composable
fun OcrSettingsPage(settings: UiSettings) {
    val scheme = MaterialTheme.colorScheme
    val online = settings.ocrOnline
    Column {
        // 先回答「我现在用的是哪个」——选择器本身做得再清楚，也要先有一句结论。
        OcrInUseBanner(settings)
        Spacer(Modifier.height(16.dp))
        Text(S.ocrEngineTitle, style = MaterialTheme.typography.titleSmall, color = scheme.primary)
        Spacer(Modifier.height(8.dp))
        if (BuildConfig.HAS_LOCAL_OCR) {
            OcrModeCard(
                title = S.ocrLocalModel,
                desc = S.ocrLocalModelDesc,
                selected = !online,
                onClick = { settings.setOcrOnline(false) }
            )
            Spacer(Modifier.height(8.dp))
        } else {
            Text(
                text = S.ocrLiteNotice,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }
        OcrModeCard(
            title = S.ocrOnlineModel,
            desc = S.ocrOnlineModelDesc,
            selected = online,
            onClick = { settings.setOcrOnline(true) }
        )
        Spacer(Modifier.height(16.dp))
        if (online) OnlineOcrSettings(settings) else OcrModelPicker(settings)
        Spacer(Modifier.height(18.dp))
        SourceLanguagePicker(settings)
    }
}

/** 「当前使用」：一眼看出是手机在跑还是服务器在跑，以及具体是哪个模型。 */
@Composable
private fun OcrInUseBanner(settings: UiSettings) {
    val scheme = MaterialTheme.colorScheme
    val online = settings.ocrOnline
    val host = S.ocrEndpointHost(settings.ocrApi)
    val model = settings.ocrApiModel.trim()
    val background = if (online) scheme.tertiaryContainer else scheme.secondaryContainer
    val onBackground = if (online) scheme.onTertiaryContainer else scheme.onSecondaryContainer
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = S.ocrInUseTitle,
            style = MaterialTheme.typography.labelSmall,
            color = onBackground.copy(alpha = 0.75f)
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = when {
                !online -> S.ocrInUseLocal(OcrModel.from(settings.ocrModel).label)
                host.isBlank() -> S.ocrInUseOnlineHostOff
                else -> S.ocrInUseOnline(host)
            },
            style = MaterialTheme.typography.titleSmall,
            color = onBackground
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = when {
                !online -> S.ocrInUseLocalNote
                host.isBlank() -> S.ocrFillEndpoint
                else -> S.ocrOnlineModelLine(model)
            },
            style = MaterialTheme.typography.bodySmall,
            color = onBackground.copy(alpha = 0.85f)
        )
    }
}

/** 一张可点的卡片顶替以前的两个 chip：单选圈 + 说明，选中的那行整块高亮并标「当前」。 */
@Composable
private fun OcrModeCard(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) scheme.primaryContainer else scheme.surfaceContainerHighest)
            .border(
                width = 1.5.dp,
                color = if (selected) scheme.primary else Color.Transparent,
                shape = RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 12.dp, top = 8.dp, bottom = 8.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) scheme.onPrimaryContainer else scheme.onSurface
                )
                if (selected) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = S.ocrCurrentTag,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onPrimary,
                        modifier = Modifier
                            .clip(RoundedCornerShape(5.dp))
                            .background(scheme.primary)
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) scheme.onPrimaryContainer.copy(alpha = 0.8f)
                else scheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Endpoint for the vision model that reads pages.
 *
 * It is deliberately separate from 翻译大模型配置: the model that reads a picture is almost never
 * the model that translates text, and mixing the two would make one of them wrong. The test only
 * asks the service for its model list — sending a page here would cost tokens on every tap.
 */
@Composable
private fun OnlineOcrSettings(settings: UiSettings) {
    val scheme = MaterialTheme.colorScheme
    var api by remember { mutableStateOf(settings.ocrApi) }
    var apiKey by remember { mutableStateOf(settings.ocrApiKey) }
    var model by remember { mutableStateOf(settings.ocrApiModel) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf<Boolean?>(null) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column {
        ApiProtocolPicker(
            current = settings.ocrProtocol,
            onSelect = {
                settings.setOcrProtocol(it)
                testResult = null
                testOk = null
            }
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = api,
            onValueChange = {
                api = it
                settings.setOcrApi(it)
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
                settings.setOcrApiKey(it)
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
                settings.setOcrApiModel(it)
            },
            api = api,
            apiKey = apiKey,
            protocol = settings.ocrProtocol,
            enabled = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = S.onlineOcrTip,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                testing = true
                testResult = null
                testOk = null
                scope.launch {
                    val result = TranslationEngine.fetchModels(
                        api.trim(), apiKey, ApiProtocol.from(settings.ocrProtocol)
                    )
                    testOk = result is ApiClient.ModelsResult.Ok
                    testResult = when (result) {
                        is ApiClient.ModelsResult.Ok ->
                            S.onlineOcrTestOk(result.ids.size)
                        is ApiClient.ModelsResult.Fail ->
                            S.onlineOcrTestFail(result.reason)
                    }
                    testing = false
                }
            },
            enabled = !testing && api.isNotBlank()
        ) {
            Text(if (testing) S.translateTesting else S.translateTest)
        }
        testResult?.let { text ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = if (testOk == true) scheme.primary else scheme.error
            )
        }
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
        // Same kind of preference: the stored page keeps groups and text only, so switching this
        // redraws immediately. Off keeps the balloon outline intact, on hides stray kana with it.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = S.coverWholeBalloon, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = S.coverWholeBalloonTip,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.coverStyle == CoverStyle.BALLOON,
                onCheckedChange = {
                    settings.setCoverStyle(if (it) CoverStyle.BALLOON else CoverStyle.LETTERING)
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
    }
}
