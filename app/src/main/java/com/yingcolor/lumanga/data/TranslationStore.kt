package com.yingcolor.lumanga.data

import android.content.Context
import android.graphics.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    private const val VERSION = 15
    private const val DIR = "translations"
    private const val STATUS_FILE = "completed.json"

    private val cache = ConcurrentHashMap<String, MutableMap<Int, PageTranslation>>()
    private val locks = ConcurrentHashMap<String, Any>()

    /**
     * Which volumes have been translated end to end. Kept in a tiny file next to the per-book
     * data so the shelf can show a badge without parsing a whole volume's layout. It carries the
     * same [VERSION] as the layouts: when a grouping rule changes and the layouts are discarded,
     * the badges go with them.
     */
    private val statusLock = Any()
    private val completed = HashMap<String, Int>()
    private var statusLoaded = false
    private val completedRevFlow = MutableStateFlow(0)
    val completedRev: StateFlow<Int> get() = completedRevFlow

    private fun dir(context: Context): File =
        File(context.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /**
     * The original Japanese -> Chinese pair keeps the unmodified key, so every volume already
     * translated still points at the same file and badge. Other language pairs get their own
     * files: switching languages re-translates without erasing the Chinese work.
     */
    private fun scoped(bookId: String): String {
        val key = TranslationEngine.languageKey
        return if (key.isEmpty()) bookId else "$bookId@$key"
    }

    private fun rawFile(context: Context, scopedId: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(scopedId.toByteArray(Charsets.UTF_8))
        val name = digest.take(8).joinToString("") { "%02x".format(it) }
        return File(dir(context), "$name.json")
    }

    private fun fileFor(context: Context, bookId: String): File = rawFile(context, scoped(bookId))

    private fun lock(bookId: String): Any = locks.getOrPut(bookId) { Any() }

    private fun pages(context: Context, bookId: String): MutableMap<Int, PageTranslation> {
        val id = scoped(bookId)
        return cache.getOrPut(id) { read(context, id).toMutableMap() }
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
            cache.remove(scoped(bookId))
            runCatching { fileFor(context, bookId).delete() }
        }
        unmarkComplete(context, bookId)
    }

    /** Drops only the in-memory copy; the file is read again on next access. */
    fun invalidate(bookId: String) {
        cache.remove(scoped(bookId))
    }

    /** True once a whole volume has been translated in the current language pair. */
    fun isComplete(context: Context, bookId: String): Boolean = synchronized(statusLock) {
        loadStatus(context)
        completed.containsKey(scoped(bookId))
    }

    /**
     * Records a finished volume. The caller must have saved every page; the count check keeps a
     * partially translated volume from wearing a "done" badge after an interrupted run.
     */
    fun markComplete(context: Context, bookId: String, pageCount: Int) {
        if (pageCount <= 0 || count(context, bookId) < pageCount) return
        val id = scoped(bookId)
        synchronized(statusLock) {
            loadStatus(context)
            if (completed[id] == pageCount) return
            completed[id] = pageCount
            writeStatus(context)
        }
        completedRevFlow.value++
    }

    private fun unmarkComplete(context: Context, bookId: String) {
        synchronized(statusLock) {
            loadStatus(context)
            if (completed.remove(scoped(bookId)) == null) return
            writeStatus(context)
        }
        completedRevFlow.value++
    }

    private fun loadStatus(context: Context) {
        if (statusLoaded) return
        statusLoaded = true
        val file = File(dir(context), STATUS_FILE)
        if (!file.isFile) return
        val root = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return
        if (root.optInt("v", 0) != VERSION) {
            runCatching { file.delete() }
            return
        }
        val books = root.optJSONObject("books") ?: return
        for (key in books.keys()) {
            val pages = books.optInt(key, 0)
            if (pages > 0) completed[key] = pages
        }
    }

    private fun writeStatus(context: Context) {
        val books = JSONObject()
        completed.forEach { (id, pages) -> books.put(id, pages) }
        val root = JSONObject().put("v", VERSION).put("books", books)
        val target = File(dir(context), STATUS_FILE)
        val temp = File(target.parentFile, target.name + ".tmp")
        runCatching {
            temp.writeText(root.toString(), Charsets.UTF_8)
            if (target.exists()) target.delete()
            temp.renameTo(target)
        }
    }

    private fun read(context: Context, scopedId: String): Map<Int, PageTranslation> {
        // pages() already applied scoped(); hashing it again would look in the wrong file.
        val file = rawFile(context, scopedId)
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
