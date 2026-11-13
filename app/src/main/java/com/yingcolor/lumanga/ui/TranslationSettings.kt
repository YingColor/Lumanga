package com.yingcolor.lumanga.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yingcolor.lumanga.S
import com.yingcolor.lumanga.data.ApiProtocol
import com.yingcolor.lumanga.data.OcrModel
import com.yingcolor.lumanga.data.SourceLanguage
import com.yingcolor.lumanga.data.UiSettings

/**
 * The front page of the translation settings: two entries that open their own pages, because
 * recognition and translation are configured for different reasons, and eight controls on one
 * scroll turned into a wall of text.
 *
 * There is deliberately no on/off switch here. Turning translation on is the reader's own
 * control (the 译 button); a second switch on this panel would disagree with it.
 */
@Composable
fun TranslationSettings(
    settings: UiSettings,
    onOpenOcr: () -> Unit,
    onOpenModel: () -> Unit
) {
    Column {
        Text(
            text = S.translateSummary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(14.dp))
        SettingEntry(
            title = S.translateConfigOcr,
            detail = OcrModel.from(settings.ocrModel).label + " · " +
                SourceLanguage.from(settings.sourceLanguage).label,
            onClick = onOpenOcr
        )
        Spacer(Modifier.height(10.dp))
        SettingEntry(
            title = S.translateConfigModel,
            detail = ApiProtocol.from(settings.translateProtocol).label + " · " +
                settings.translateModel.ifBlank { S.translateModelEmpty },
            onClick = onOpenModel
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = S.translateConfigTip,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** One row of the front page: name, what is currently set, and a chevron that says "opens". */
@Composable
private fun SettingEntry(title: String, detail: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(scheme.surfaceContainerHighest)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(3.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Text(
            text = "›",
            style = MaterialTheme.typography.titleMedium,
            color = scheme.onSurfaceVariant
        )
    }
}
