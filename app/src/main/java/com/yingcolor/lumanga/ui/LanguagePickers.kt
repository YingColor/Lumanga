package com.yingcolor.lumanga.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yingcolor.lumanga.S
import com.yingcolor.lumanga.data.SourceLanguage
import com.yingcolor.lumanga.data.TargetLanguage
import com.yingcolor.lumanga.data.UiSettings

/**
 * A compact pair of radio-like rows. Reading settings.sourceLanguage / targetLanguage here also
 * subscribes the screen to the change, which is what makes the reader recompute its page keys.
 */
@Composable
fun LanguagePickers(settings: UiSettings) {
    Column {
        LanguageRow(
            title = S.translateSourceLanguage,
            tip = S.translateSourceLanguageTip,
            options = SourceLanguage.entries.map { it.id to it.label },
            current = settings.sourceLanguage,
            onSelect = settings::setSourceLanguage
        )
        Spacer(Modifier.height(14.dp))
        LanguageRow(
            title = S.translateTargetLanguage,
            tip = S.translateTargetLanguageTip,
            options = TargetLanguage.entries.map { it.id to it.label },
            current = settings.targetLanguage,
            onSelect = settings::setTargetLanguage
        )
    }
}

@Composable
private fun LanguageRow(
    title: String,
    tip: String,
    options: List<Pair<Int, String>>,
    current: Int,
    onSelect: (Int) -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    Column {
        Text(text = title, style = MaterialTheme.typography.titleSmall, color = scheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(
            text = tip,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            options.forEach { (id, label) ->
                val selected = id == current
                TextButton(
                    onClick = { onSelect(id) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = if (selected) "● $label" else label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) scheme.primary else scheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
