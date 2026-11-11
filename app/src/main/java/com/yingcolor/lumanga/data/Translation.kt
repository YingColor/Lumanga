package com.yingcolor.lumanga.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

object TranslationConfig {
    /**
     * No service is bundled. The app talks to whatever OpenAI-compatible endpoint the user
     * configures, so nothing is sent anywhere until they set it up themselves.
     */
    const val DEFAULT_API = ""
    const val DEFAULT_MODEL = ""
}

data class TextRegion(
    val text: String,
    val box: Rect,
    val confidence: Float = 1f,
    val vertical: Boolean = false
)

data class BubbleGroup(
    val regions: List<TextRegion>,
    val bubble: Rect,
    val source: String,
    val vertical: Boolean
)

object TranslationEngine {
    private const val SYSTEM_PROMPT =
        "你是漫画翻译助手。输入按【编号】分组，每组是一个对话气泡里 OCR 出来的日文片段。" +
            "漫画竖排文字常被切碎、顺序颠倒，也可能有识别错字。" +
            "请先按漫画竖排的正确顺序（从右到左、从上到下）重排并纠正明显错字，" +
            "理解整句含义后，为每个气泡输出一句通顺自然的简体中文。" +
            "只输出译文，条数必须与气泡数一致，每行严格按「编号. 译文」格式。" +
            "不要解释、不要复述原文、不要逐字硬译、不要添加原文没有的内容。" +
            "若有上一页译文，人名和称呼必须与之一致。"

    @Volatile
    var ocr: OcrEngine = NoOpOcrEngine

    suspend fun recognize(bitmap: Bitmap): List<TextRegion> = ocr.recognize(bitmap)

    /** Drops detection noise, sound effects and single stray glyphs before translating. */
    fun isUsable(region: TextRegion): Boolean {
        if (region.confidence < 0.55f) return false
        if (region.box.width() < 10 || region.box.height() < 10) return false
        if (region.vertical && region.box.height() < region.box.width() * 1.2f) return false
        var letters = 0
        for (ch in region.text) {
            if (isJapaneseLetter(ch)) letters++
        }
        if (letters < 2) return false
        val meaningful = region.text.count { it !in PUNCTUATION && !it.isDigit() && !it.isWhitespace() }
        if (meaningful < 2) return false
        return true
    }

    /** Pages that are already Chinese (or English) must not be re-translated into gibberish. */
    fun looksJapanese(regions: List<TextRegion>): Boolean {
        var letters = 0
        var kana = 0
        for (region in regions) {
            for (ch in region.text) {
                if (!isJapaneseLetter(ch)) continue
                letters++
                val code = ch.code
                if (code in 0x3041..0x309F || code in 0x30A0..0x30FF) kana++
            }
        }
        if (letters < 16) return true
        return kana.toFloat() / letters >= 0.04f
    }

    fun groupBubbles(regions: List<TextRegion>, width: Int, height: Int): List<BubbleGroup> {
        val usable = regions.filter { isUsable(it) }
        if (usable.isEmpty()) return emptyList()
        val ordered = usable.sortedWith(
            compareByDescending<TextRegion> { it.box.centerX() }.thenBy { it.box.top }
        )
        val clusters = ArrayList<MutableList<TextRegion>>()
        for (region in ordered) {
            var target: MutableList<TextRegion>? = null
            var bestGap = Float.MAX_VALUE
            for (cluster in clusters) {
                for (member in cluster) {
                    // columns of one bubble sit side by side and share the same vertical span;
                    // they often overlap slightly, so a negative gap counts as touching
                    val gap = horizontalGap(region.box, member.box)
                    val verticalOverlap = overlap(region.box, member.box)
                    if (verticalOverlap < 0.45f) continue
                    val unit = min(min(region.box.width(), member.box.width()).toFloat(), 96f)
                        .coerceAtLeast(12f)
                    if (gap < unit * 0.75f && gap < bestGap) {
                        target = cluster
                        bestGap = gap
                    }
                }
            }
            if (target != null) target.add(region) else clusters.add(mutableListOf(region))
        }
        return clusters.map { cluster ->
            cluster.sortWith(compareByDescending<TextRegion> { it.box.centerX() }.thenBy { it.box.top })
            val union = Rect(cluster[0].box)
            cluster.forEach { union.union(it.box) }
            val vertical = cluster.count { it.vertical } * 2 >= cluster.size
            BubbleGroup(cluster, union, cluster.joinToString("") { it.text }, vertical)
        }.sortedWith(compareByDescending<BubbleGroup> { it.bubble.centerX() }.thenBy { it.bubble.top })
    }

    private fun horizontalGap(a: Rect, b: Rect): Float =
        maxOf(0f, maxOf(a.left - b.right, b.left - a.right).toFloat())

    private fun overlap(a: Rect, b: Rect): Float {
        val top = maxOf(a.top, b.top)
        val bottom = minOf(a.bottom, b.bottom)
        val shared = bottom - top
        val shorter = minOf(a.height(), b.height()).coerceAtLeast(1)
        return (shared.toFloat() / shorter).coerceAtLeast(0f)
    }

    suspend fun translate(
        groups: List<BubbleGroup>,
        api: String,
        model: String,
        context: List<String> = emptyList(),
        apiKey: String = ""
    ): List<String>? = withContext(Dispatchers.IO) {
        if (groups.isEmpty()) return@withContext emptyList()
        val numbered = buildString {
            if (context.isNotEmpty()) {
                append("【上一页译文，仅供保持人名、称呼和语气一致】\n")
                context.forEach { append(it).append('\n') }
                append("\n【本页原文】\n")
            }
            groups.forEachIndexed { i, group ->
                append(i + 1).append(". ").append(group.source).append('\n')
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
        var lastError = "unknown"
        repeat(2) { attempt ->
            // A wrong key or a wrong base URL fails identically on the second try, so retrying it
            // only doubles the wait before the user finally sees "翻译失败".
            var retryable = true
            val outcome = runCatching {
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 6000
                    readTimeout = 12000
                    doOutput = true
                    setFixedLengthStreamingMode(payload.size)
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "Lumanga/1.0")
                    if (apiKey.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer $apiKey")
                    }
                }
                connection.outputStream.use { it.write(payload) }
                val code = connection.responseCode
                if (code !in 200..299) {
                    val detail = runCatching {
                        connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    }.getOrNull()
                    lastError = "http $code"
                    retryable = code == 429 || code >= 500
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
                parse(content, groups.size)
            }
            val value = outcome.getOrNull()
            if (value != null) return@withContext value
            outcome.exceptionOrNull()?.let {
                lastError = it.javaClass.simpleName
                android.util.Log.w("Translate", "TR fail ${it.javaClass.simpleName}: ${it.message}")
            }
            if (!retryable) {
                android.util.Log.w("Translate", "TR no retry: $lastError")
                return@withContext null
            }
            if (attempt == 0) delay(600)
        }
        android.util.Log.w("Translate", "TR gave up: $lastError")
        null
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

    fun render(source: Bitmap, groups: List<BubbleGroup>, translated: List<String>): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        groups.forEachIndexed { i, group ->
            val value = translated.getOrNull(i)?.trim().orEmpty()
            if (value.isEmpty()) return@forEachIndexed
            // light lettering on dark artwork (titles, black bubbles) cannot be erased without
            // painting a bright patch over the drawing, so it is left alone
            if (medianLuminance(source, group.bubble) < 100) return@forEachIndexed
            var background = Color.WHITE
            for (region in group.regions) {
                val box = Rect(region.box)
                box.inset(-2, -2)
                box.left = box.left.coerceAtLeast(0)
                box.top = box.top.coerceAtLeast(0)
                box.right = box.right.coerceAtMost(source.width)
                box.bottom = box.bottom.coerceAtMost(source.height)
                if (box.width() < 6 || box.height() < 6) continue
                background = sampleBubbleColor(out, box)
                eraseInk(out, box, background)
            }
            paint.color = if (luminance(background) > 140) Color.rgb(18, 18, 18) else Color.WHITE
            val glyph = group.regions.maxOf {
                if (group.vertical) it.box.width() else it.box.height()
            }.toFloat()
            drawFittedText(canvas, paint, value, group.bubble, group.vertical, glyph * 1.15f)
        }
        return out
    }

    private fun medianLuminance(source: Bitmap, box: Rect): Int {
        val right = box.right.coerceAtMost(source.width)
        val bottom = box.bottom.coerceAtMost(source.height)
        if (right <= box.left || bottom <= box.top) return 255
        val stepX = ((right - box.left) / 12).coerceAtLeast(1)
        val stepY = ((bottom - box.top) / 12).coerceAtLeast(1)
        val samples = ArrayList<Int>(256)
        var y = box.top
        while (y < bottom) {
            var x = box.left
            while (x < right) {
                samples.add(luminance(source.getPixel(x, y)))
                x += stepX
            }
            y += stepY
        }
        if (samples.isEmpty()) return 255
        samples.sort()
        return samples[samples.size / 2]
    }

    private fun luminance(color: Int): Int =
        (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000

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

    /**
     * Removes the original lettering inside [box]: find ink, grow it by a few pixels to catch
     * the anti-aliased fringe, then paint those pixels with the bubble colour. The halo is what
     * used to survive as a grey ghost.
     */
    private fun eraseInk(bitmap: Bitmap, box: Rect, background: Int) {
        val w = box.width().coerceAtLeast(1)
        val h = box.height().coerceAtLeast(1)
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, box.left, box.top, w, h)
        val backgroundLum = luminance(background)
        if (backgroundLum < 90) return
        val core = BooleanArray(w * h)
        val coreThreshold = (backgroundLum * 0.80f).toInt()
        for (i in pixels.indices) {
            if (luminance(pixels[i]) < coreThreshold) core[i] = true
        }
        val reach = 2
        var changed = false
        for (y in 0 until h) {
            for (x in 0 until w) {
                val index = y * w + x
                if (luminance(pixels[index]) >= backgroundLum - 6) continue
                var nearInk = false
                for (dy in -reach..reach) {
                    if (nearInk) break
                    val ny = y + dy
                    if (ny < 0 || ny >= h) continue
                    for (dx in -reach..reach) {
                        val nx = x + dx
                        if (nx < 0 || nx >= w) continue
                        if (core[ny * w + nx]) {
                            nearInk = true
                            break
                        }
                    }
                }
                if (nearInk) {
                    pixels[index] = background
                    changed = true
                }
            }
        }
        if (changed) bitmap.setPixels(pixels, 0, w, box.left, box.top, w, h)
    }

    private class Fit(
        val columns: List<String>,
        val lines: List<String>,
        val size: Float
    )

    private fun drawFittedText(
        canvas: Canvas,
        paint: Paint,
        text: String,
        box: Rect,
        vertical: Boolean,
        maxGlyph: Float
    ) {
        if (box.width() < 10 || box.height() < 10) return
        val pad = maxOf(1.5f, minOf(box.width(), box.height()) * 0.035f)
        val availW = box.width() - 2f * pad
        val availH = box.height() - 2f * pad
        if (availW < 8f || availH < 8f) return

        var fit: Fit? = null
        // never blow the lettering up beyond what the original text occupied
        var size = min(min(min(availW, availH) * 1.15f, 96f), maxGlyph.coerceAtLeast(9f))
        while (size >= 7f) {
            fit = layout(text, paint, size, availW, availH, vertical)
            if (fit != null) break
            size -= 1f
        }
        if (fit == null) {
            // nothing fits even at the floor: shorten instead of drawing outside the bubble
            var keep = text.length
            while (keep > 1) {
                keep -= 1
                fit = layout(text.take(keep) + "…", paint, 7f, availW, availH, vertical)
                if (fit != null) break
            }
        }
        val chosen = fit ?: return
        paint.textSize = chosen.size

        canvas.save()
        canvas.clipRect(box)
        val metrics = paint.fontMetrics
        val glyphH = metrics.descent - metrics.ascent
        if (vertical) {
            val colW = chosen.size * 1.10f
            val lineH = chosen.size * 1.12f
            val totalW = chosen.columns.size * colW
            var x = box.left + (box.width() - totalW) / 2f + totalW - colW
            for (column in chosen.columns) {
                val blockH = column.length * lineH
                var y = box.top + (box.height() - blockH) / 2f
                for (ch in column) {
                    val gx = x + (colW - chosen.size) / 2f
                    val gy = y + (lineH - glyphH) / 2f - metrics.ascent
                    canvas.drawText(ch.toString(), gx, gy, paint)
                    y += lineH
                }
                x -= colW
            }
        } else {
            val lineH = chosen.size * 1.18f
            val blockH = chosen.lines.size * lineH
            var y = box.top + (box.height() - blockH) / 2f
            for (line in chosen.lines) {
                val width = paint.measureText(line)
                val gx = box.left + (box.width() - width) / 2f
                val gy = y + (lineH - glyphH) / 2f - metrics.ascent
                canvas.drawText(line, gx, gy, paint)
                y += lineH
            }
        }
        canvas.restore()
    }

    private fun layout(
        text: String,
        paint: Paint,
        size: Float,
        availW: Float,
        availH: Float,
        vertical: Boolean
    ): Fit? {
        if (text.isEmpty()) return null
        if (vertical) {
            val lineH = size * 1.12f
            val colW = size * 1.10f
            val per = floor(availH / lineH).toInt()
            if (per < 1) return null
            val columns = chunk(text, per)
            if (columns.size * colW > availW + 0.5f) return null
            val longest = columns.maxOf { it.length }
            if (longest * lineH > availH + 0.5f) return null
            return Fit(columns, emptyList(), size)
        }
        paint.textSize = size
        val lineH = size * 1.18f
        val lines = wrap(text, paint, availW)
        if (lines.size * lineH > availH + 0.5f) return null
        if (lines.size == 1 && paint.measureText(lines[0]) > availW + 0.5f) return null
        return Fit(emptyList(), lines, size)
    }

    private val PUNCTUATION = "，。！？、；：,.!?;:…‥「」『』（）()[]【】〈〉《》—―-~～·・"

    private fun isJapaneseLetter(ch: Char): Boolean {
        val code = ch.code
        return (code in 0x3041..0x309F) || (code in 0x30A0..0x30FF) || (code in 0x4E00..0x9FFF)
    }

    private fun isPunctuation(ch: Char): Boolean = ch in PUNCTUATION

    private fun chunk(text: String, per: Int): List<String> {
        val columns = ArrayList<String>(4)
        var i = 0
        while (i < text.length) {
            val end = min(i + per, text.length)
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
