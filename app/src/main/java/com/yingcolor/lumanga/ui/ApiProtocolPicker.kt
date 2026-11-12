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
import com.yingcolor.lumanga.data.ApiProtocol

/**
 * Which wire format the endpoint speaks.
 *
 * The line under each option is only shown for the selected one — four two-line rows would push
 * the address and key fields off the panel, and the explanation is only needed while choosing.
 * Shared by the reader's settings and both pre-translate panels, because they all talk to the
 * same service.
 */
@Composable
fun ApiProtocolPicker(
    current: Int,
    onSelect: (Int) -> Unit,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    Column {
        Text(S.translateProtocol, style = MaterialTheme.typography.titleSmall, color = scheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(
            S.translateProtocolTip,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        ApiProtocol.entries.forEach { protocol ->
            val selected = protocol.id == current
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { onSelect(protocol.id) }
                    .padding(vertical = 1.dp)
            ) {
                RadioButton(
                    selected = selected,
                    enabled = enabled,
                    onClick = { onSelect(protocol.id) }
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = protocol.label,
                        style = MaterialTheme.typography.bodyLarge
                    )
                    if (selected) {
                        Text(
                            text = protocolTip(protocol),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** One line of guidance per protocol: what to pick it for, and what the address looks like. */
fun protocolTip(protocol: ApiProtocol): String = when (protocol) {
    ApiProtocol.OPENAI -> S.translateProtocolOpenAi
    ApiProtocol.ANTHROPIC -> S.translateProtocolAnthropic
    ApiProtocol.GEMINI -> S.translateProtocolGemini
    ApiProtocol.OLLAMA -> S.translateProtocolOllama
}
