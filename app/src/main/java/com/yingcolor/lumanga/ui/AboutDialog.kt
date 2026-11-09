package com.yingcolor.lumanga.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yingcolor.lumanga.S
import com.yingcolor.lumanga.data.RemoteRelease
import com.yingcolor.lumanga.data.UpdateChecker
import com.yingcolor.lumanga.data.UpdateResult
import kotlinx.coroutines.launch

private const val RELEASES_PAGE =
    "https://github.com/YingColor/Lumanga/releases/latest"

/**
 * Short "about" card. Checking for updates is a deliberate, single tap: the app
 * stays offline until this button is pressed, and the result never installs
 * anything by itself — a newer build is handed to the browser, so the user
 * decides whether to download and install it.
 */
@Composable
fun AboutDialog(
    versionName: String,
    versionCode: Int,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var newer by remember { mutableStateOf<RemoteRelease?>(null) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(S.about) },
        text = {
            Column {
                Text(
                    text = S.versionLabel(versionName, versionCode),
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = S.aboutBody,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val line = status
                if (line != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                // Some networks (or per-app proxies) let the browser reach
                // GitHub while the app itself cannot resolve it. Never leave the
                // reader without a way forward: hand the release page to the
                // browser instead.
                if (failed) {
                    TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse(RELEASES_PAGE)
                                    )
                                )
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 0.dp)
                    ) { Text(S.openUpdatePage) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onClose) { Text(S.close) }
        },
        dismissButton = {
            TextButton(
                enabled = !checking,
                onClick = {
                    checking = true
                    status = null
                    failed = false
                    scope.launch {
                        when (val result = UpdateChecker.check()) {
                            is UpdateResult.Available -> {
                                status = null
                                newer = result.release
                            }
                            UpdateResult.UpToDate -> status = S.upToDate
                            is UpdateResult.Failed -> {
                                status = S.updateFailed
                                failed = true
                            }
                        }
                        checking = false
                    }
                }
            ) {
                Text(if (checking) S.checkingUpdate else S.checkUpdate)
            }
        }
    )

    val release = newer
    if (release != null) {
        AlertDialog(
            onDismissRequest = { newer = null },
            title = { Text(S.updateFound) },
            text = {
                Text(S.updateFoundBody(release.versionName, release.versionCode))
            },
            confirmButton = {
                TextButton(onClick = {
                    val url = release.apkUrl ?: release.pageUrl
                    if (url.isNotEmpty()) {
                        runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                            )
                        }
                    }
                    newer = null
                }) { Text(S.goUpdate) }
            },
            dismissButton = {
                TextButton(onClick = { newer = null }) { Text(S.later) }
            }
        )
    }
}
