package com.yingcolor.lumanga.data

import android.content.Context
import android.graphics.Rect
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Translation of one page: the bubble layout plus the finished lines.
 *
 * Storing the layout instead of a rendered bitmap keeps a whole volume at a few hundred
 * kilobytes and lets the reader re-render at whatever size the current screen needs.
 */
data class PageTranslation(
    val width: Int,
    val height: Int,
    val groups: List<BubbleGroup>,
    val lines: List<String>
) {
    /** A page that was checked and has nothing to translate. */
    val isEmpty: Boolean get() = groups.isEmpty()
}

/**
 * Per-book translation storage, so a volume is translated once and then reads offline.
 *
 * One JSON file per book under `filesDir/translations`. The in-memory copy is authoritative
 * while the app runs; every [save] rewrites the file, which is small enough to stay cheap and
 * keeps a killed process from losing more than the page in flight.
 */
object TranslationStore {
    private const val VERSION = 8
    private const val DIR = "translations"

    private val cache = ConcurrentHashMap<String, MutableMap<Int, PageTranslation>>()
    private val locks = ConcurrentHashMap<String, Any>()

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    private fun fileFor(context: Context, bookId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bookId.toByteArray(Charsets.UTF_8))
        val name = digest.take(8).joinToString("") { "%02x".format(it) }
        return File(dir(context), "$name.json")
    }

    private fun lock(bookId: String): Any = locks.getOrPut(bookId) { Any() }

    private fun pages(context: Context, bookId: String): MutableMap<Int, PageTranslation> =
        cache.getOrPut(bookId) {
            read(context, bookId).toMutableMap()
        }

    fun page(context: Context, bookId: String, index: Int): PageTranslation? =
        pages(context, bookId)[index]

    fun translatedIndexes(context: Context, bookId: String): Set<Int> =
        pages(context, bookId).keys.toSet()

    fun count(context: Context, bookId: String): Int = pages(context, bookId).size

    fun save(context: Context, bookId: String, index: Int, page: PageTranslation) {
        val map = pages(context, bookId)
        synchronized(lock(bookId)) {
            map[index] = page
            write(context, bookId, map)
        }
    }

    fun clear(context: Context, bookId: String) {
        synchronized(lock(bookId)) {
            pages(context, bookId).clear()
            cache.remove(bookId)
            runCatching { fileFor(context, bookId).delete() }
        }
    }

    /** Drops only the in-memory copy; the file is read again on next access. */
    fun invalidate(bookId: String) {
        cache.remove(bookId)
    }

    private fun read(context: Context, bookId: String): Map<Int, PageTranslation> {
        val file = fileFor(context, bookId)
        if (!file.isFile) return emptyMap()
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            // Layouts saved by an older grouping rule can hold two sentences for one bubble.
            if (root.optInt("v", 0) != VERSION) return@runCatching emptyMap()
            val pages = root.optJSONObject("pages") ?: return emptyMap()
            val out = HashMap<Int, PageTranslation>(pages.length())
            for (key in pages.keys()) {
                val index = key.toIntOrNull() ?: continue
                out[index] = decodePage(pages.getJSONObject(key))
            }
            out
        }.getOrDefault(emptyMap())
    }

    private fun write(context: Context, bookId: String, pages: Map<Int, PageTranslation>) {
        val root = JSONObject().put("v", VERSION).put("bookId", bookId)
        val encoded = JSONObject()
        pages.forEach { (index, page) -> encoded.put(index.toString(), encodePage(page)) }
        root.put("pages", encoded)
        val target = fileFor(context, bookId)
        val temp = File(target.parentFile, target.name + ".tmp")
        runCatching {
            temp.writeText(root.toString(), Charsets.UTF_8)
            if (target.exists()) target.delete()
            temp.renameTo(target)
        }
    }

    private fun encodePage(page: PageTranslation): JSONObject {
        val groups = JSONArray()
        page.groups.forEach { group ->
            val regions = JSONArray()
            group.regions.forEach { region ->
                regions.put(
                    JSONArray()
                        .put(region.box.left).put(region.box.top)
                        .put(region.box.right).put(region.box.bottom)
                        .put(region.text)
                        .put(region.vertical)
                )
            }
            groups.put(
                JSONObject()
                    .put("b", JSONArray().put(group.bubble.left).put(group.bubble.top)
                        .put(group.bubble.right).put(group.bubble.bottom))
                    .put("v", group.vertical)
                    .put("s", group.source)
                    .put("r", regions)
            )
        }
        return JSONObject()
            .put("w", page.width)
            .put("h", page.height)
            .put("lines", JSONArray(page.lines))
            .put("groups", groups)
    }

    private fun decodePage(json: JSONObject): PageTranslation {
        val groupArray = json.optJSONArray("groups") ?: JSONArray()
        val groups = ArrayList<BubbleGroup>(groupArray.length())
        for (i in 0 until groupArray.length()) {
            val g = groupArray.getJSONObject(i)
            val bubble = g.getJSONArray("b")
            val bubbleRect = Rect(
                bubble.getInt(0), bubble.getInt(1), bubble.getInt(2), bubble.getInt(3)
            )
            val regionArray = g.optJSONArray("r") ?: JSONArray()
            val regions = ArrayList<TextRegion>(regionArray.length())
            for (r in 0 until regionArray.length()) {
                val item = regionArray.getJSONArray(r)
                regions.add(
                    TextRegion(
                        text = item.getString(4),
                        box = Rect(item.getInt(0), item.getInt(1), item.getInt(2), item.getInt(3)),
                        vertical = item.optBoolean(5, false)
                    )
                )
            }
            groups.add(
                BubbleGroup(
                    regions = regions,
                    bubble = bubbleRect,
                    source = g.optString("s"),
                    vertical = g.optBoolean("v", false)
                )
            )
        }
        val linesArray = json.optJSONArray("lines") ?: JSONArray()
        val lines = List(linesArray.length()) { linesArray.optString(it) }
        return PageTranslation(
            width = json.optInt("w"),
            height = json.optInt("h"),
            groups = groups,
            lines = lines
        )
    }
}
