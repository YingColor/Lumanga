package com.yingcolor.lumanga.data

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Online OCR: one vision model reads the page and returns text with boxes.
 *
 * A local PP-OCR pass is fast on a phone but still costs seconds per page, and on a large page it
 * can take a minute. A hosted vision model answers a whole page in one request. There is no
 * standard "OCR" wire format across providers, so the request asks the model for JSON and the
 * reply is parsed here; the rest of the pipeline (grouping, erasing, drawing) is unchanged, which
 * is why this only has to produce [TextRegion]s.
 *
 * Coordinates are the fragile part. Models answer in one of three ways — 0..1 fractions, 0..1000
 * integers (Qwen-VL), or raw pixels — so the parser detects which one it got instead of trusting
 * the prompt.
 */
object OnlineOcr {
    private const val TAG = "OnlineOcr"

    /** Long edge of the JPEG sent to the service. Small text still reads, the request stays small. */
    private const val MAX_SIDE = 1800

    private const val SYSTEM = "你是漫画 OCR 引擎。读这张漫画页上的所有对话文字。" +
        "忽略拟声词、音效字和印在画面上的装饰字，只要气泡里的台词和旁白。" +
        "按从上到下、从右到左的顺序，为每一段文字输出一项：" +
        "{\"text\":\"识别出的原文\",\"box\":[x0,y0,x1,y1],\"vertical\":true}。" +
        "box 是这一整段文字的外接矩形，坐标用相对整张图宽高的 0 到 1 的小数；" +
        "竖排文字 vertical 为 true，横排为 false。一个气泡是一段，不要把相邻气泡合并，" +
        "也不要把一段拆成多段。只输出 JSON 数组，不要代码块、不要解释。"

    private const val USER = "请识别这一页的全部对话文字，按约定格式返回 JSON 数组。"

    /** Returns an empty list on failure: the caller then leaves the page untranslated. */
    suspend fun recognize(
        prefs: Prefs,
        bitmap: Bitmap,
        token: ApiClient.CancelToken? = null
    ): List<TextRegion> = withContext(Dispatchers.IO) {
        val api = prefs.ocrApi.trim()
        val model = prefs.ocrApiModel.trim()
        if (api.isEmpty() || model.isEmpty()) {
            Log.w(TAG, "missing endpoint or model")
            return@withContext emptyList()
        }
        val source = if (max(bitmap.width, bitmap.height) > MAX_SIDE) {
            val ratio = MAX_SIDE.toFloat() / max(bitmap.width, bitmap.height)
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
                (bitmap.height * ratio).roundToInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }
        try {
            val bytes = ByteArrayOutputStream().use { out ->
                source.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.toByteArray()
            }
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            Log.i(TAG, "page=${bitmap.width}x${bitmap.height} sent=${source.width}x${source.height} bytes=${bytes.size}")
            when (
                val result = ApiClient.vision(
                    protocol = ApiProtocol.from(prefs.ocrProtocol),
                    api = api,
                    model = model,
                    apiKey = prefs.ocrApiKey,
                    system = SYSTEM,
                    user = USER,
                    imageBase64 = base64,
                    mime = "image/jpeg",
                    token = token
                )
            ) {
                is ApiClient.ChatResult.Ok -> {
                    val regions = parse(
                        result.text, source.width, source.height, bitmap.width, bitmap.height
                    )
                    Log.i(TAG, "regions=${regions.size} raw=${result.text.take(300)}")
                    regions
                }
                is ApiClient.ChatResult.Fail -> {
                    Log.w(TAG, "fail ${result.reason}")
                    emptyList()
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "error ${t.javaClass.simpleName}: ${t.message}")
            emptyList()
        } finally {
            if (source !== bitmap) source.recycle()
        }
    }

    /**
     * Reads the JSON answer into regions in the caller's bitmap space.
     *
     * The reply may be 0..1 fractions, 0..1000 integers (Qwen-VL), or pixels of the image that
     * was sent; detecting the unit is more reliable than telling the model which one to use.
     */
    private fun parse(
        content: String,
        scaledWidth: Int,
        scaledHeight: Int,
        originalWidth: Int,
        originalHeight: Int
    ): List<TextRegion> {
        val text = content
            .replace("```json", "")
            .replace("```JSON", "")
            .replace("```", "")
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val array = runCatching { JSONArray(text.substring(start, end + 1)) }.getOrNull()
            ?: return emptyList()
        val regions = ArrayList<TextRegion>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val value = item.optString("text").ifBlank { item.optString("content") }.trim()
            if (value.isEmpty()) continue
            val box = item.optJSONArray("box") ?: item.optJSONArray("bbox")
                ?: item.optJSONArray("bbox_2d") ?: continue
            if (box.length() < 4) continue
            val raw = FloatArray(4) { box.optDouble(it, -1.0).toFloat() }
            if (raw.any { it < 0f }) continue
            val largest = raw.maxOrNull() ?: 0f
            val unit = when {
                largest <= 1.5f -> 1f      // fractions of the page
                largest <= 1000f -> 1000f  // 0..1000 coordinates
                else -> 0f                 // pixels of the image that was sent
            }
            fun norm(value: Float, axis: Int): Float =
                if (unit == 0f) {
                    value / (if (axis == 0) scaledWidth else scaledHeight)
                } else {
                    value / unit
                }
            val rect = Rect(
                (norm(raw[0], 0) * originalWidth).roundToInt(),
                (norm(raw[1], 1) * originalHeight).roundToInt(),
                (norm(raw[2], 0) * originalWidth).roundToInt(),
                (norm(raw[3], 1) * originalHeight).roundToInt()
            )
            if (rect.width() < 4 || rect.height() < 4) continue
            val vertical = if (item.has("vertical")) item.optBoolean("vertical")
            else rect.height() > rect.width() * 1.2f
            // Optional: a service that reports its own confidence is taken at its word, and one
            // that does not keeps the old assumption. Without this every reply looked perfect,
            // so the low-score filter that throws away artwork never fired on this path.
            val score = item.optDouble("score", item.optDouble("confidence", -1.0))
            val confidence = when {
                score < 0.0 -> 1f
                score > 1.5 -> (score / 100.0).toFloat().coerceIn(0f, 1f)
                else -> score.toFloat().coerceIn(0f, 1f)
            }
            regions.add(TextRegion(value, rect, confidence, vertical))
        }
        return regions
    }
}
