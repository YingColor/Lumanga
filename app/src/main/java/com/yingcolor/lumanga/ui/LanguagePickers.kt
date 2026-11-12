package com.yingcolor.lumanga.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yingcolor.lumanga.S
import com.yingcolor.lumanga.data.SourceLanguage
import com.yingcolor.lumanga.data.TargetLanguage
import com.yingcolor.lumanga.data.UiSettings

/**
 * Two drop-downs instead of two rows of buttons. Four languages laid out side by side took a
 * whole line each and made the panel scroll; a closed drop-down costs one line and shows the
 * value that is actually in effect. Reading settings.sourceLanguage / targetLanguage here also
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
    var expanded by remember { mutableStateOf(false) }
    val currentLabel = options.firstOrNull { it.first == current }?.second
        ?: options.firstOrNull()?.second.orEmpty()
    Column {
        Text(text = title, style = MaterialTheme.typography.titleSmall, color = scheme.primary)
        Spacer(Modifier.height(4.dp))
        Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = currentLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (expanded) "▴" else "▾",
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant
                    )
                }
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (id, label) ->
                    val selected = id == current
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = if (selected) "✓ $label" else label,
                                color = if (selected) scheme.primary else scheme.onSurface
                            )
                        },
                        onClick = {
                            expanded = false
                            onSelect(id)
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = tip,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp)
        )
    }
}
