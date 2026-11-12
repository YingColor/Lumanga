package com.yingcolor.lumanga.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yingcolor.lumanga.S
import com.yingcolor.lumanga.data.OcrModel
import com.yingcolor.lumanga.data.UiSettings

/** Three bundled OCR models; the choice is shared by live translation and pre-translate. */
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
        OcrModel.entries.forEach { model ->
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
                    Text(model.label, style = MaterialTheme.typography.bodyLarge)
                    val tip = when (model) {
                        OcrModel.MEDIUM -> S.ocrModelMediumTip
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
