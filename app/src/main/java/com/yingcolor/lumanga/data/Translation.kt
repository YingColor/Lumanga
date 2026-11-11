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

    fun groupBubbles(
        regions: List<TextRegion>,
        width: Int,
        height: Int,
        bitmap: Bitmap? = null
    ): List<BubbleGroup> {
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
        // When the detector drops a column between two others, the surviving columns end up too
        // far apart for the pass above and are translated as two bubbles. Both translations then
        // fill the same bubble and overwrite each other. Joining them here keeps one sentence per
        // bubble; the paper between them tells whether that is really one bubble.
        val groups = if (bitmap == null) {
            clusters
        } else {
            val merged = mergeNeighbours(clusters, bitmap)
            if (merged.size != clusters.size) {
                android.util.Log.i("Translate", "TR merge ${clusters.size}->${merged.size}")
            }
            merged
        }
        return groups.map { cluster ->
            cluster.sortWith(compareByDescending<TextRegion> { it.box.centerX() }.thenBy { it.box.top })
            val union = Rect(cluster[0].box)
            cluster.forEach { union.union(it.box) }
            val vertical = cluster.count { it.vertical } * 2 >= cluster.size
            BubbleGroup(cluster, union, cluster.joinToString("") { it.text }, vertical)
        }.sortedWith(compareByDescending<BubbleGroup> { it.bubble.centerX() }.thenBy { it.bubble.top })
    }

    private fun mergeNeighbours(
        clusters: List<MutableList<TextRegion>>,
        source: Bitmap
    ): List<MutableList<TextRegion>> {
        val groups = clusters.map { ArrayList(it) }.toMutableList()
        var merged = true
        while (merged) {
            merged = false
            var i = 0
            while (i < groups.size && !merged) {
                var j = i + 1
                while (j < groups.size) {
                    if (sameBubble(source, unionOf(groups[i]), unionOf(groups[j]))) {
                        groups[i].addAll(groups[j])
                        groups.removeAt(j)
                        merged = true
                        break
                    }
                    j++
                }
                i++
            }
        }
        return groups
    }

    private fun unionOf(cluster: List<TextRegion>): Rect {
        val union = Rect(cluster[0].box)
        cluster.forEach { union.union(it.box) }
        return union
    }

    /** True when the strip between two boxes is bare paper instead of a bubble outline. */
    private fun sameBubble(source: Bitmap, a: Rect, b: Rect): Boolean {
        val top = maxOf(a.top, b.top)
        val bottom = minOf(a.bottom, b.bottom)
        if (bottom - top < minOf(a.height(), b.height()) * 0.25f) return false
        val gapLeft = minOf(a.right, b.right)
        val gapRight = maxOf(a.left, b.left)
        val gap = gapRight - gapLeft
        if (gap <= 2) return false
        val unit = minOf(a.width(), b.width()).coerceAtLeast(8)
        if (gap > unit * 2.5f) return false
        val x0 = gapLeft.coerceAtLeast(0)
        val x1 = gapRight.coerceAtMost(source.width)
        val y0 = top.coerceAtLeast(0)
        val y1 = bottom.coerceAtMost(source.height)
        if (x1 - x0 < 1 || y1 - y0 < 1) return false
        val w = x1 - x0
        val h = y1 - y0
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, x0, y0, w, h)
        var dark = 0
        for (pixel in pixels) {
            if (luminance(pixel) < 165) dark++
        }
        return dark.toFloat() / pixels.size < 0.16f
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

    /**
     * Lists the models the endpoint offers (`GET {api}/models`, the OpenAI-compatible route).
     * Returns null when the request itself failed, and an empty list when the service answered
     * but exposed nothing — the two cases need different messages in the UI.
     */
    suspend fun fetchModels(api: String, apiKey: String): List<String>? =
        withContext(Dispatchers.IO) {
            if (api.isBlank()) return@withContext null
            val endpoint = api.trim().trimEnd('/') + "/models"
            runCatching {
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 6000
                    readTimeout = 12000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "Lumanga/1.0")
                    if (apiKey.isNotBlank()) {
                        setRequestProperty("Authorization", "Bearer $apiKey")
                    }
                }
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) {
                        android.util.Log.w("Translate", "models http=$code")
                        return@runCatching null
                    }
                    val response = connection.inputStream.bufferedReader(Charsets.UTF_8)
                        .use { it.readText() }
                    val data = JSONObject(response).optJSONArray("data") ?: JSONArray()
                    val ids = LinkedHashSet<String>()
                    for (i in 0 until data.length()) {
                        val id = data.optJSONObject(i)?.optString("id").orEmpty()
                        if (id.isNotBlank()) ids.add(id)
                    }
                    ids.toList()
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()
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

    private class Patch(
        val text: String,
        val area: Rect,
        val background: Int,
        val vertical: Boolean,
        val maxGlyph: Float
    )

    fun render(source: Bitmap, groups: List<BubbleGroup>, translated: List<String>): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val patches = ArrayList<Patch>()
        val seeds = groups.map { it.bubble }
        val placed = ArrayList<Rect>()
        groups.forEachIndexed { i, group ->
            val value = translated.getOrNull(i)?.trim().orEmpty()
            if (value.isEmpty()) return@forEachIndexed
            // light lettering on dark artwork (titles, black bubbles) cannot be erased without
            // painting a bright patch over the drawing, so it is left alone
            if (medianLuminance(source, group.bubble) < 100) return@forEachIndexed

            // The detector returns one tight box per Japanese column. A bubble is drawn around
            // all of its columns, but short and low-confidence columns are dropped, so using
            // those boxes as the patch left stray kana beside the translation and squeezed the
            // Chinese into a sliver (down to the 7 px floor, with the text truncated). Grow the
            // patch across the blank interior of the bubble, then clear all lettering in it.
            val unit = columnWidth(group)
            val clean = insideWhiteBubble(source, group.bubble)
            val area = if (clean) {
                bubbleSafeArea(
                    source, group.bubble, unit,
                    seeds.filterIndexed { j, _ -> j != i } + placed
                )
            } else {
                conservativeArea(source, group.bubble)
            }
            placed.add(area)
            // Sample from the patch itself: a halftone bubble needs its own grey as the fill,
            // otherwise erased lettering turns into bright white glyph-shaped holes.
            val background = sampleBubbleColor(out, area)
            patches.add(Patch(value, area, background, group.vertical, unit * 1.45f))
            android.util.Log.d(
                "Translate",
                "TR patch i=$i clean=$clean " +
                    "seed=${group.bubble.width()}x${group.bubble.height()} " +
                    "area=${area.width()}x${area.height()} unit=$unit"
            )
        }
        // Erase every patch before drawing any text: a later patch overlapping an earlier one
        // must not wipe the already-drawn Chinese off the page.
        patches.forEach { eraseLettering(out, it.area, it.background) }
        patches.forEach { patch ->
            paint.color = if (luminance(patch.background) > 140) {
                Color.rgb(18, 18, 18)
            } else {
                Color.WHITE
            }
            drawFittedText(
                canvas, paint, patch.text, patch.area, patch.vertical, patch.maxGlyph
            )
        }
        return out
    }

    /** Typical width of one Japanese column, used as the yardstick for growing the patch. */
    private fun columnWidth(group: BubbleGroup): Int {
        val sizes = group.regions.map {
            if (group.vertical) it.box.width() else it.box.height()
        }.sorted()
        if (sizes.isEmpty()) return 24
        return sizes[sizes.size / 2].coerceIn(10, 96)
    }

    /**
     * Only a clean white bubble may be grown. Lettering printed directly on the artwork has no
     * outline to stop at, so growing it would paint a rectangle over the drawing.
     */
    private fun insideWhiteBubble(source: Bitmap, seed: Rect): Boolean {
        val pad = 10
        val l = (seed.left - pad).coerceAtLeast(0)
        val t = (seed.top - pad).coerceAtLeast(0)
        val r = (seed.right + pad).coerceAtMost(source.width)
        val b = (seed.bottom + pad).coerceAtMost(source.height)
        if (r - l < 2 || b - t < 2) return false
        val w = r - l
        val h = b - t
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, l, t, w, h)
        var dark = 0
        var total = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val gx = l + x
                val gy = t + y
                if (gx >= seed.left && gx < seed.right &&
                    gy >= seed.top && gy < seed.bottom
                ) continue
                total++
                if (luminance(pixels[y * w + x]) < 165) dark++
            }
        }
        return total > 0 && dark.toFloat() / total < 0.12f
    }

    /** Just enough room to clear the anti-aliased fringe when the text sits on artwork. */
    private fun conservativeArea(source: Bitmap, seed: Rect): Rect {
        val area = Rect(seed)
        area.inset(-2, -2)
        area.left = area.left.coerceAtLeast(0)
        area.top = area.top.coerceAtLeast(0)
        area.right = area.right.coerceAtMost(source.width)
        area.bottom = area.bottom.coerceAtMost(source.height)
        return area
    }

    /**
     * Expands the OCR footprint into the blank interior of its bubble. Each step samples the
     * strip just outside the rectangle and stops at a continuous dark line (the bubble outline
     * or an artwork stroke); scattered ink is lettering, which we cross and erase. Growth is
     * capped so a patch can never swallow a neighbouring panel.
     */
    private fun bubbleSafeArea(
        source: Bitmap,
        seed: Rect,
        unit: Int,
        obstacles: List<Rect>
    ): Rect {
        var left = seed.left
        var right = seed.right
        var top = seed.top
        var bottom = seed.bottom
        val step = 2
        val maxX = (unit * 1.8f).toInt().coerceIn(12, source.width / 5)
        val maxY = (unit * 0.5f).toInt().coerceIn(6, source.height / 12)
        var grown = 0
        while (grown + step <= maxX && left - step >= 0 &&
            blankStrip(source, left - step, left, top, bottom, true) &&
            !touchesObstacle(left - step, top, right, bottom, obstacles)
        ) { left -= step; grown += step }
        grown = 0
        while (grown + step <= maxX && right + step <= source.width &&
            blankStrip(source, right, right + step, top, bottom, true) &&
            !touchesObstacle(left, top, right + step, bottom, obstacles)
        ) { right += step; grown += step }
        grown = 0
        while (grown + step <= maxY && top - step >= 0 &&
            blankStrip(source, left, right, top - step, top, false) &&
            !touchesObstacle(left, top - step, right, bottom, obstacles)
        ) { top -= step; grown += step }
        grown = 0
        while (grown + step <= maxY && bottom + step <= source.height &&
            blankStrip(source, left, right, bottom, bottom + step, false) &&
            !touchesObstacle(left, top, right, bottom + step, obstacles)
        ) { bottom += step; grown += step }

        // Keep clear of the outline; never return less than what OCR actually found.
        val area = Rect(left + 2, top + 2, right - 2, bottom - 2)
        if (area.width() < seed.width() || area.height() < seed.height()) return Rect(seed)
        area.left = area.left.coerceAtLeast(0)
        area.top = area.top.coerceAtLeast(0)
        area.right = area.right.coerceAtMost(source.width)
        area.bottom = area.bottom.coerceAtMost(source.height)
        return area
    }

    /** A patch must never grow into a neighbouring bubble's lettering. */
    private fun touchesObstacle(
        l: Int, t: Int, r: Int, b: Int, obstacles: List<Rect>
    ): Boolean = obstacles.any {
        it.left < r + 3 && it.right > l - 3 && it.top < b + 3 && it.bottom > t - 3
    }

    /**
     * A bubble outline (or an artwork stroke) is one long continuous dark run, while letters
     * are scattered. Only scattered ink may be crossed.
     */
    private fun blankStrip(
        source: Bitmap, x0: Int, x1: Int, y0: Int, y1: Int, alongY: Boolean
    ): Boolean {
        val w = x1 - x0
        val h = y1 - y0
        if (w <= 0 || h <= 0) return false
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, x0, y0, w, h)
        val length = if (alongY) h else w
        val thickness = if (alongY) w else h
        var longest = 0
        var run = 0
        for (i in 0 until length) {
            var dark = false
            for (j in 0 until thickness) {
                val index = if (alongY) i * w + j else j * w + i
                if (luminance(pixels[index]) < 165) { dark = true; break }
            }
            if (dark) {
                run++
                if (run > longest) longest = run
            } else {
                run = 0
            }
        }
        return longest.toFloat() / length < 0.5f
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
     * Clears every ink pixel inside the patch (not only the columns the detector found) plus
     * its dark fringe. Screen tones and light grey artwork must survive: erasing them produced
     * a conspicuous white rectangle inside halftone bubbles.
     */
    private fun eraseLettering(bitmap: Bitmap, box: Rect, background: Int) {
        val w = box.width()
        val h = box.height()
        if (w < 2 || h < 2) return
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, box.left, box.top, w, h)
        val backgroundLum = luminance(background)
        if (backgroundLum < 90) return
        // Halftone dots sit around luminance 200. Only genuinely black lettering and its dark
        // fringe may be cleared, otherwise the whole patch turns into a white rectangle.
        val coreThreshold = (backgroundLum * 0.46f).toInt()
        val haloThreshold = min(170, backgroundLum - 60)
        val core = BooleanArray(w * h)
        for (i in pixels.indices) {
            if (luminance(pixels[i]) < coreThreshold) core[i] = true
        }
        val reach = 1
        var changed = false
        for (y in 0 until h) {
            for (x in 0 until w) {
                val index = y * w + x
                if (luminance(pixels[index]) >= haloThreshold) continue
                var nearInk = core[index]
                if (!nearInk) {
                    for (dy in -reach..reach) {
                        if (nearInk) break
                        val ny = y + dy
                        if (ny < 0 || ny >= h) continue
                        for (dx in -reach..reach) {
                            val nx = x + dx
                            if (nx < 0 || nx >= w) continue
                            if (core[ny * w + nx]) { nearInk = true; break }
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
        android.util.Log.d(
            "Translate",
            "TR fit size=${chosen.size} box=${box.width()}x${box.height()} " +
                "chars=${chosen.columns.sumOf { it.length } + chosen.lines.sumOf { it.length }}"
        )

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
