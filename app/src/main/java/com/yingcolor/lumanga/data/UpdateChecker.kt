package com.yingcolor.lumanga.data

import com.yingcolor.lumanga.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL








data class RemoteRelease(
    val versionName: String,
    val versionCode: Int,
    val pageUrl: String,
    val apkUrl: String?
)

sealed interface UpdateResult {
    data class Available(val release: RemoteRelease) : UpdateResult
    object UpToDate : UpdateResult
    data class Failed(val reason: String) : UpdateResult
}






object UpdateChecker {
    private const val API =
        "https://api.github.com/repos/YingColor/Lumanga/releases?per_page=10"

    suspend fun check(): UpdateResult = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(API).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "Lumanga-Android")
            }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                android.util.Log.w("UpdateCheck", "HTTP $code from $API")
                return@withContext UpdateResult.Failed("HTTP $code")
            }
            val releases = JSONArray(
                connection.inputStream.bufferedReader().use { it.readText() }
            )
            if (releases.length() == 0) {
                return@withContext UpdateResult.Failed("没有找到发布")
            }
            // Newest first, pre-releases included: the current line is still a beta.
            val json = releases.getJSONObject(0)
            val tag = json.optString("tag_name").trim()
                .removePrefix("v").removePrefix("V")
            val notes = json.optString("body")
            val remoteCode = Regex(
                "versionCode\\s*[=:]\\s*(\\d+)",
                RegexOption.IGNORE_CASE
            ).find(notes)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val pageUrl = json.optString("html_url")
            val apkUrl = json.optJSONArray("assets")?.let { assets ->
                (0 until assets.length())
                    .map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk", true) }
                    ?.optString("browser_download_url")
                    ?.takeIf { it.isNotEmpty() }
            }
            val release = RemoteRelease(
                versionName = tag.ifEmpty { "?" },
                versionCode = remoteCode,
                pageUrl = pageUrl,
                apkUrl = apkUrl
            )
            val newer = if (remoteCode > 0) {
                remoteCode > BuildConfig.VERSION_CODE
            } else {
                
                compareVersions(tag, BuildConfig.VERSION_NAME) > 0
            }
            if (newer) UpdateResult.Available(release) else UpdateResult.UpToDate
        } catch (t: Throwable) {
            android.util.Log.w(
                "UpdateCheck",
                "check failed: ${t::class.java.name}: ${t.message}",
                t
            )
            UpdateResult.Failed(t.message ?: t::class.java.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    private fun compareVersions(a: String, b: String): Int {
        val left = a.split('.')
        val right = b.split('.')
        for (i in 0 until maxOf(left.size, right.size)) {
            val x = left.getOrNull(i)?.toIntOrNull() ?: 0
            val y = right.getOrNull(i)?.toIntOrNull() ?: 0
            if (x != y) return x - y
        }
        return 0
    }
}
