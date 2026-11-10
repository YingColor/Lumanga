package com.yingcolor.lumanga.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
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
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        regions.forEachIndexed { i, region ->
            val value = translated.getOrNull(i)?.trim().orEmpty()
            if (value.isEmpty()) return@forEachIndexed
            val box = Rect(region.box)
            val grow = (minOf(box.width(), box.height()) * 0.08f).toInt().coerceIn(2, 10)
            box.inset(-grow, -grow)
            box.left = box.left.coerceAtLeast(0)
            box.top = box.top.coerceAtLeast(0)
            box.right = box.right.coerceAtMost(source.width)
            box.bottom = box.bottom.coerceAtMost(source.height)
            if (box.width() < 8 || box.height() < 8) return@forEachIndexed

            val background = sampleBubbleColor(out, box)
            eraseInk(out, box, background)
            textPaint.color = if (luminance(background) > 140) {
                Color.rgb(20, 20, 20)
            } else {
                Color.WHITE
            }
            drawFittedText(
                canvas,
                textPaint,
                value,
                box,
                box.height() >= box.width() * 1.35f
            )
        }
        return out
    }

    private fun luminance(color: Int): Int =
        (
            Color.red(color) * 299 +
                Color.green(color) * 587 +
                Color.blue(color) * 114
            ) / 1000

    private fun sampleBubbleColor(source: Bitmap, box: Rect): Int {
        val w = box.width().coerceAtLeast(1)
        val h = box.height().coerceAtLeast(1)
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, box.left, box.top, w, h)
        val bright = ArrayList<Int>(pixels.size / 4)
        for (pixel in pixels) {
            if (luminance(pixel) > 120) bright.add(pixel)
        }
        val pool = if (bright.size >= pixels.size / 6) bright else pixels.toList()
        val sorted = pool.sortedBy { luminance(it) }
        val picked = sorted[sorted.size / 2]
        return Color.rgb(Color.red(picked), Color.green(picked), Color.blue(picked))
    }

    private fun eraseInk(bitmap: Bitmap, box: Rect, background: Int) {
        val w = box.width().coerceAtLeast(1)
        val h = box.height().coerceAtLeast(1)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, box.left, box.top, w, h)
        val threshold = maxOf(120, (luminance(background) * 0.88f).toInt())
        var changed = false
        for (i in pixels.indices) {
            if (luminance(pixels[i]) < threshold) {
                pixels[i] = background
                changed = true
            }
        }
        if (changed) bitmap.setPixels(pixels, 0, w, box.left, box.top, w, h)
    }

    private fun drawFittedText(
        canvas: Canvas,
        paint: Paint,
        text: String,
        box: Rect,
        vertical: Boolean
    ) {
        val pad = maxOf(3f, minOf(box.width(), box.height()) * 0.06f)
        val availW = (box.width() - 2f * pad).coerceAtLeast(12f)
        val availH = (box.height() - 2f * pad).coerceAtLeast(12f)
        var size = if (vertical) {
            minOf(availW / 2.1f, availH / 6f).coerceIn(11f, 60f)
        } else {
            minOf(availH / 2.6f, availW / 3.2f).coerceIn(11f, 76f)
        }
        for (attempt in 0 until 28) {
            paint.textSize = size
            val lineH = size * 1.18f
            val colW = size * 1.12f
            val fits = if (vertical) {
                val perColumn = (availH / lineH).toInt().coerceAtLeast(1)
                chunk(text, perColumn).size * colW <= availW
            } else {
                wrap(text, paint, availW).size * lineH <= availH
            }
            if (fits) break
            size = (size * 0.88f).coerceAtLeast(9f)
        }
        paint.textSize = size
        val lineH = size * 1.18f
        val colW = size * 1.12f
        if (vertical) {
            val perColumn = (availH / lineH).toInt().coerceAtLeast(1)
            val columns = chunk(text, perColumn)
            val totalW = columns.size * colW
            var x = box.left + (box.width() - totalW) / 2f + totalW - colW
            for (column in columns) {
                var y = box.top + (box.height() - column.length * lineH) / 2f + size * 0.86f
                for (ch in column) {
                    val px = x + (colW - size) / 2f
                    if (px >= box.left && px + size <= box.right && y - size <= box.bottom) {
                        canvas.drawText(ch.toString(), px, y, paint)
                    }
                    y += lineH
                }
                x -= colW
            }
        } else {
            val lines = wrap(text, paint, availW)
            var y = box.top + (box.height() - lines.size * lineH) / 2f + size * 0.86f
            for (line in lines) {
                val tw = paint.measureText(line)
                val px = box.left + (box.width() - tw) / 2f
                if (y - size <= box.bottom && px >= box.left) {
                    canvas.drawText(line, px, y, paint)
                }
                y += lineH
            }
        }
    }

    private fun isPunctuation(ch: Char): Boolean =
        "，。！？、；：,.!?;:」』）)]}".contains(ch)

    private fun chunk(text: String, per: Int): List<String> {
        val columns = ArrayList<String>(4)
        var i = 0
        while (i < text.length) {
            val end = (i + per).coerceAtMost(text.length)
            columns.add(text.substring(i, end))
            i = end
        }
        if (columns.size > 1) {
            val last = columns.last()
            if (last.length == 1 && isPunctuation(last[0])) {
                columns[columns.size - 2] = columns[columns.size - 2] + last
                columns.removeAt(columns.size - 1)
            }
        }
        return columns
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = ArrayList<String>(4)
        val current = StringBuilder()
        for (ch in text) {
            val exceeds = current.isNotEmpty() &&
                paint.measureText(current.toString() + ch) > maxWidth
            if (exceeds && !isPunctuation(ch)) {
                lines.add(current.toString())
                current.setLength(0)
            }
            current.append(ch)
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }
}
