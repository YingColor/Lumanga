package com.yingcolor.lumanga.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * A one- or two-digit field for a pre-translation concurrency, shared by the single-volume and
 * batch panels. Kept next to those panels rather than in the global translation settings because
 * nothing but pre-translation reads the value.
 */
@Composable
internal fun ConcurrencyField(
    label: String,
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
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                modifier = Modifier.width(92.dp)
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
