package com.yingcolor.lumanga.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object TranslationConfig {
    const val DEFAULT_API = "https://index-translate.bilibili.com/v1"
    const val DEFAULT_MODEL = "Index-Translate-9B"
}

data class TextRegion(val text: String, val box: Rect)

object TranslationEngine {
    private const val SYSTEM_PROMPT =
        "你是漫画翻译助手。输入是从漫画页面识别出来的日文，可能含识别错字。" +
            "请结合上下文先纠正明显的错字，再逐条翻译成简体中文。" +
            "只输出译文，条数必须与输入一致，每行严格按「编号. 译文」格式，" +
            "不要解释、不要复述原文、不要添加原文没有的内容。" +
            "若有上一页译文，人名和称呼必须与之一致。"

    @Volatile
    var ocr: OcrEngine = MlKitOcrEngine

    suspend fun recognize(bitmap: Bitmap): List<TextRegion> = ocr.recognize(bitmap)

    suspend fun translate(
        regions: List<TextRegion>,
        api: String,
        model: String,
        context: List<String> = emptyList()
    ): List<String>? = withContext(Dispatchers.IO) {
        if (regions.isEmpty()) return@withContext emptyList()
        val numbered = buildString {
            if (context.isNotEmpty()) {
                append("【上一页译文，仅供保持人名、称呼和语气一致】\n")
                context.forEach { append(it).append('\n') }
                append("\n【本页原文】\n")
            }
            regions.forEachIndexed { i, region ->
                append(i + 1).append(". ").append(region.text).append('\n')
            }
        }
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("max_tokens", 1200)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                    .put(JSONObject().put("role", "user").put("content", numbered))
            )
            .toString()
        val endpoint = api.trim().trimEnd('/') + "/chat/completions"
        val payload = body.toByteArray(Charsets.UTF_8)
        val outcome = runCatching {
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 8000
                readTimeout = 25000
                doOutput = true
                setFixedLengthStreamingMode(payload.size)
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "Lumanga/1.0")
            }
            connection.outputStream.use { it.write(payload) }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = runCatching {
                    connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                }.getOrNull()
                android.util.Log.w("Translate", "TR http=$code body=${detail?.take(180)}")
                connection.disconnect()
                return@runCatching null
            }
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            connection.disconnect()
            val content = JSONObject(response)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .optString("content")
            parse(content, regions.size)
        }
        outcome.exceptionOrNull()?.let {
            android.util.Log.w("Translate", "TR fail ${it.javaClass.simpleName}: ${it.message}")
        }
        outcome.getOrNull()
    }

    private fun parse(content: String, count: Int): List<String> {
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val byIndex = HashMap<Int, String>()
        val numbered = Regex("^(\\d+)[.、)]\\s*(.+)$")
        lines.forEach { line ->
            val match = numbered.find(line) ?: return@forEach
            val index = match.groupValues[1].toIntOrNull()?.minus(1) ?: return@forEach
            byIndex[index] = match.groupValues[2].trim()
        }
        if (byIndex.isNotEmpty()) return List(count) { byIndex[it].orEmpty() }
        return List(count) { lines.getOrNull(it).orEmpty() }
    }

    fun render(source: Bitmap, regions: List<TextRegion>, translated: List<String>): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(232, 250, 250, 250)
            style = Paint.Style.FILL
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(110, 25, 25, 25)
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(18, 18, 18)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        regions.forEachIndexed { i, region ->
            val value = translated.getOrNull(i)?.trim().orEmpty()
            if (value.isEmpty()) return@forEachIndexed
            val box = region.box
            val cx = box.centerX().toFloat()
            val cy = box.centerY().toFloat()
            val boxW = maxOf(box.width() + 40f, minOf(source.width / 3f, 460f))
                .coerceAtMost(source.width - 24f)
            val pad = 16f
            textPaint.textSize = (boxW / 14f).coerceIn(24f, 46f)
            val lines = wrap(value, textPaint, (boxW - 2f * pad).coerceAtLeast(40f))
            if (lines.isEmpty()) return@forEachIndexed
            val lineH = textPaint.fontSpacing
            val boxH = (lines.size * lineH + 2f * pad).coerceAtMost(source.height - 24f)
            val maxLeft = (source.width - boxW - 8f).coerceAtLeast(8f)
            val maxTop = (source.height - boxH - 8f).coerceAtLeast(8f)
            val left = (cx - boxW / 2f).coerceIn(8f, maxLeft)
            val top = (cy - boxH / 2f).coerceIn(8f, maxTop)
            val rect = RectF(left, top, left + boxW, top + boxH)
            canvas.drawRoundRect(rect, 14f, 14f, background)
            canvas.drawRoundRect(rect, 14f, 14f, border)
            val visible = ((boxH - 2f * pad) / lineH).toInt().coerceAtMost(lines.size)
            for (k in 0 until visible) {
                canvas.drawText(
                    lines[k],
                    left + pad,
                    top + pad + textPaint.textSize + k * lineH,
                    textPaint
                )
            }
        }
        return out
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = ArrayList<String>(4)
        val current = StringBuilder()
        for (ch in text) {
            if (current.isNotEmpty() && paint.measureText(current.toString() + ch) > maxWidth) {
                lines.add(current.toString())
                current.setLength(0)
            }
            current.append(ch)
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }
}
