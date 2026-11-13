package com.yingcolor.lumanga.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yingcolor.lumanga.S
import com.yingcolor.lumanga.data.UiSettings

/** The three screens of the dialog: the menu, then recognition, then the service. */
private const val PAGE_ROOT = 0
private const val PAGE_OCR = 1
private const val PAGE_MODEL = 2

/**
 * Translation is about a service and a key, not about how pages are drawn, so it lives
 * outside the reading settings. Opened from the shelf drawer, and from the reader's
 * translate button.
 *
 * Two sub-pages rather than one long scroll: recognition (model, printed language) and the
 * translation service (format, endpoint, model, output) have nothing to do with each other, and
 * eight controls stacked together read as a wall of text. The menu stays one screen tall and
 * shows what is currently in effect, so the common case needs no scrolling at all.
 *
 * @param onPreTranslate when set, a shortcut to the single-volume pre-translate panel is
 *   shown. The reader passes it so the panel stays reachable without a second long-press
 *   gesture; the shelf drawer leaves it null because no volume is open there.
 */
@Composable
fun TranslateConfigDialog(
    settings: UiSettings,
    onDismiss: () -> Unit,
    onPreTranslate: (() -> Unit)? = null
) {
    var page by remember { mutableIntStateOf(PAGE_ROOT) }
    val scroll = rememberScrollState()
    // A sub-page must start at its top; without this it would inherit the previous offset.
    LaunchedEffect(page) { scroll.scrollTo(0) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 20.dp, vertical = 20.dp)
                    .heightIn(max = 660.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (page != PAGE_ROOT) {
                        IconButton(
                            onClick = { page = PAGE_ROOT },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                                contentDescription = S.back,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = when (page) {
                            PAGE_OCR -> S.translateConfigOcr
                            PAGE_MODEL -> S.translateConfigModel
                            else -> S.translateConfig
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(scroll)
                ) {
                    when (page) {
                        PAGE_OCR -> OcrSettingsPage(settings)
                        PAGE_MODEL -> ModelSettingsPage(settings)
                        else -> TranslationSettings(
                            settings = settings,
                            onOpenOcr = { page = PAGE_OCR },
                            onOpenModel = { page = PAGE_MODEL }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (onPreTranslate != null) {
                        TextButton(onClick = onPreTranslate) { Text(S.preTranslateTitle) }
                        Spacer(Modifier.width(4.dp))
                    }
                    TextButton(onClick = onDismiss) { Text(S.done) }
                }
            }
        }
    }
}
