package com.yingcolor.lumanga.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yingcolor.lumanga.S
import com.yingcolor.lumanga.data.OcrModel
import com.yingcolor.lumanga.data.UiSettings

/** Four bundled OCR models; the choice is shared by live translation and pre-translate. */
@Composable
fun OcrModelPicker(settings: UiSettings) {
    val scheme = MaterialTheme.colorScheme
    Column {
        Text(S.ocrModelTitle, style = MaterialTheme.typography.titleSmall, color = scheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(
            S.ocrModelTip,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        // Default first, so the recommended model is the first row a user sees.
        OcrModel.choices.forEach { model ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { settings.setOcrModel(model.id) }
                    .padding(vertical = 2.dp)
            ) {
                RadioButton(
                    selected = settings.ocrModel == model.id,
                    onClick = { settings.setOcrModel(model.id) }
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.label + if (model == OcrModel.DEFAULT) " · ${S.ocrModelDefault}" else "",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    val tip = when (model) {
                        OcrModel.MEDIUM -> S.ocrModelMediumTip
                        OcrModel.HYBRID -> S.ocrModelHybridTip
                        OcrModel.MANGA -> S.ocrModelMangaTip
                        else -> S.ocrModelSmallTip
                    }
                    Text(
                        tip,
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/**
 * The same choice as 翻译配置 → OCR 配置, dropped into the pre-translate panels.
 *
 * It writes the global model on purpose: someone picking a model here expects the reader to use
 * it too, and the alternative — a second override that silently disagrees with the reader — is
 * how "pre-translate used a different model" bug reports start. The hint under the row says so.
 */
@Composable
fun OcrModelRow(settings: UiSettings, enabled: Boolean) {
    val scheme = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    val current = OcrModel.from(settings.ocrModel)
    val label = if (settings.ocrOnline) S.ocrOnlineModel else current.label
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = S.preTranslateOcr,
                style = MaterialTheme.typography.labelMedium,
                color = scheme.onSurfaceVariant
            )
            Spacer(Modifier.width(10.dp))
            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    enabled = enabled
                ) {
                    Text(label, style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = if (expanded) " ▴" else " ▾",
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant
                    )
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    val host = S.ocrEndpointHost(settings.ocrApi)
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (host.isBlank()) S.ocrOnlineModel
                                else "${S.ocrOnlineModel} · $host",
                                color = if (settings.ocrOnline) scheme.primary else scheme.onSurface
                            )
                        },
                        onClick = {
                            expanded = false
                            settings.setOcrOnline(true)
                        }
                    )
                    OcrModel.choices.forEach { model ->
                        val selected = !settings.ocrOnline && model.id == settings.ocrModel
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = model.label +
                                        if (model == OcrModel.DEFAULT) " · ${S.ocrModelDefault}" else "",
                                    color = if (selected) scheme.primary else scheme.onSurface
                                )
                            },
                            onClick = {
                                expanded = false
                                // Also leaves online mode; the two are exclusive.
                                settings.setOcrModel(model.id)
                            }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = if (settings.ocrOnline) {
                S.onlineOcrRowHint(S.ocrEndpointHost(settings.ocrApi))
            } else {
                S.ocrModelSharedHint
            },
            style = MaterialTheme.typography.labelSmall,
            color = scheme.onSurfaceVariant
        )
    }
}
