package com.yingcolor.comicviewer.data

import android.content.Context
import org.json.JSONArray

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("comic_viewer", Context.MODE_PRIVATE)

    init {
        migrateZoomMode()
    }

    /**
     * Older builds stored only three scroll-mode values (0 = fit width, 1 = fit
     * screen, 2 = original size). The list now has seven entries in a different
     * order, so the stored index is translated once; otherwise a fit-width user
     * would suddenly get 1:1 pixels after updating.
     */
    private fun migrateZoomMode() {
        if (sp.getBoolean(KEY_ZOOM_MIGRATED, false)) return
        val mapped = when (sp.getInt(KEY_FIT_MODE, -1)) {
            0 -> ZoomMode.FIT_WIDTH
            1 -> ZoomMode.FIT_SCREEN
            2 -> ZoomMode.ORIGINAL
            else -> ZoomMode.FIT_SCREEN
        }
        sp.edit()
            .putInt(KEY_FIT_MODE, mapped)
            .putBoolean(KEY_ZOOM_MIGRATED, true)
            .apply()
    }

    /**
     * The library can be spread over several folders (one per author, one per
     * genre, internal storage plus an SD card). They are all scanned into a
     * single shelf; the first folder below each root becomes its category.
     */
    var folderUris: List<String>
        get() {
            val stored = sp.getString(KEY_FOLDERS, null)
            val parsed = if (stored.isNullOrBlank()) {
                emptyList()
            } else {
                runCatching {
                    val array = JSONArray(stored)
                    (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                }.getOrDefault(emptyList())
            }
            if (parsed.isNotEmpty()) return parsed
            // One-folder installs from older builds.
            val legacy = sp.getString(KEY_FOLDER, null)
            return if (legacy.isNullOrBlank()) emptyList() else listOf(legacy)
        }
        set(value) {
            val clean = value.filter { it.isNotBlank() }.distinct()
            sp.edit()
                .putString(KEY_FOLDERS, JSONArray(clean).toString())
                .putString(KEY_FOLDER, clean.firstOrNull())
                .apply()
        }

    /** Kept for the single-folder call sites; returns the first library folder. */
    val folderUri: String?
        get() = folderUris.firstOrNull()

    fun addFolder(uri: String): List<String> {
        val next = (folderUris + uri).distinct()
        folderUris = next
        return next
    }

    fun removeFolder(uri: String): List<String> {
        val next = folderUris.filter { it != uri }
        folderUris = next
        return next
    }

    var themeMode: Int
        get() = sp.getInt(KEY_THEME, 0)
        set(value) = sp.edit().putInt(KEY_THEME, value).apply()

    /** Accent palette index; see `theme/Palettes.kt`. */
    var themeColor: Int
        get() = sp.getInt(KEY_THEME_COLOR, 0)
        set(value) = sp.edit().putInt(KEY_THEME_COLOR, value).apply()

    /** Page-turn animation; see [PageAnimation]. */
    var pageAnim: Int
        get() = sp.getInt(KEY_PAGE_ANIM, 0)
        set(value) = sp.edit().putInt(KEY_PAGE_ANIM, value).apply()

    /** 0 = left to right, 1 = right to left (manga) */
    var readingDirection: Int
        get() = sp.getInt(KEY_DIRECTION, 1)
        set(value) = sp.edit().putInt(KEY_DIRECTION, value).apply()

    var doublePage: Boolean
        get() = sp.getBoolean(KEY_DOUBLE, false)
        set(value) = sp.edit().putBoolean(KEY_DOUBLE, value).apply()

    var keepAwake: Boolean
        get() = sp.getBoolean(KEY_AWAKE, true)
        set(value) = sp.edit().putBoolean(KEY_AWAKE, value).apply()

    /** Number of book covers per row: 2 (default), 3, 4 or 5. */
    var gridColumns: Int
        get() = sp.getInt(KEY_COLUMNS, 2).coerceIn(2, 5)
        set(value) = sp.edit().putInt(KEY_COLUMNS, value.coerceIn(2, 5)).apply()

    /** See S.sortOptions for the index meaning; 0 = name A..Z. */
    var sortMode: Int
        get() = sp.getInt(KEY_SORT, 0).coerceIn(0, 6)
        set(value) = sp.edit().putInt(KEY_SORT, value.coerceIn(0, 6)).apply()

    /** Tap the left / right edge of the reader to turn pages. */
    var tapToTurn: Boolean
        get() = sp.getBoolean(KEY_TAP_TURN, true)
        set(value) = sp.edit().putBoolean(KEY_TAP_TURN, value).apply()

    /** 0 = page-by-page, 1 = continuous vertical scroll (Webtoon / long strips). */
    var readMode: Int
        get() = sp.getInt(KEY_READ_MODE, 0).coerceIn(0, 1)
        set(value) = sp.edit().putInt(KEY_READ_MODE, value.coerceIn(0, 1)).apply()

    /** See [ZoomMode]; applies to both page mode and continuous scroll mode. */
    var fitMode: Int
        get() = ZoomMode.coerce(sp.getInt(KEY_FIT_MODE, ZoomMode.FIT_SCREEN))
        set(value) = sp.edit().putInt(KEY_FIT_MODE, ZoomMode.coerce(value)).apply()

    /** Percentage of the screen width used by [ZoomMode.FIXED]. */
    var zoomPercent: Int
        get() = sp.getInt(KEY_ZOOM_PERCENT, 100).coerceIn(MIN_ZOOM_PERCENT, MAX_ZOOM_PERCENT)
        set(value) = sp.edit()
            .putInt(KEY_ZOOM_PERCENT, value.coerceIn(MIN_ZOOM_PERCENT, MAX_ZOOM_PERCENT))
            .apply()

    /** Scroll mode horizontal alignment when a page is narrower than the screen. */
    var alignment: Int
        get() = sp.getInt(KEY_ALIGNMENT, 0).coerceIn(0, 2)
        set(value) = sp.edit().putInt(KEY_ALIGNMENT, value.coerceIn(0, 2)).apply()

    /** 0 = follow system, 1 = portrait, 2 = landscape. */
    var screenOrientation: Int
        get() = sp.getInt(KEY_ORIENTATION, 0).coerceIn(0, 2)
        set(value) = sp.edit().putInt(KEY_ORIENTATION, value.coerceIn(0, 2)).apply()

    /** 0 = smooth (bilinear), 1 = sharp (nearest neighbour). */
    var smoothing: Int
        get() = sp.getInt(KEY_SMOOTHING, 0).coerceIn(0, 1)
        set(value) = sp.edit().putInt(KEY_SMOOTHING, value.coerceIn(0, 1)).apply()

    /** -1 = follow the system brightness, 0..100 = app-only override. */
    var brightness: Int
        get() = sp.getInt(KEY_BRIGHTNESS, -1).coerceIn(-1, 100)
        set(value) = sp.edit().putInt(KEY_BRIGHTNESS, value.coerceIn(-1, 100)).apply()

    fun progress(bookId: String): Int = sp.getInt(progressKey(bookId), 0)

    /** Total pages are stored alongside the position so the shelf can show a real ratio. */
    fun pageCount(bookId: String): Int = sp.getInt(countKey(bookId), 0)

    /** Timestamp of the most recent reading session, used by the "recently read" sort. */
    fun lastRead(bookId: String): Long = sp.getLong(readKey(bookId), 0L)

    /**
     * Page 0 means "unread": turning back to the very first page removes every
     * trace of the book, so it leaves the reading tab and loses its shelf bar.
     */
    fun setProgress(bookId: String, page: Int, totalPages: Int) {
        if (page <= 0) {
            clearProgress(bookId)
            return
        }
        sp.edit()
            .putInt(progressKey(bookId), page)
            .putInt(countKey(bookId), totalPages)
            .putLong(readKey(bookId), System.currentTimeMillis())
            .apply()
    }

    /** Forget a single book (page, total page count and last-read time). */
    fun clearProgress(bookId: String) {
        sp.edit()
            .remove(progressKey(bookId))
            .remove(countKey(bookId))
            .remove(readKey(bookId))
            .apply()
    }

    /**
     * One bulk snapshot for filtering the shelf. Individual getInt() calls are
     * cheap for ten books but painfully slow for tens of thousands.
     * Keys are keyed by the book id's hashCode, exactly as progressKey() does.
     */
    fun progressSnapshot(): Map<Int, Pair<Int, Int>> {
        val all = sp.all
        val result = HashMap<Int, Pair<Int, Int>>()
        for ((key, value) in all) {
            if (!key.startsWith("pg_")) continue
            val hash = key.removePrefix("pg_").toIntOrNull() ?: continue
            val page = value as? Int ?: continue
            val total = all["pc_$hash"] as? Int ?: 0
            if (page > 0) result[hash] = page to total
        }
        return result
    }

    fun clearProgress() {
        val editor = sp.edit()
        sp.all.keys
            .filter { it.startsWith("pg_") || it.startsWith("pc_") || it.startsWith("lr_") }
            .forEach { editor.remove(it) }
        editor.apply()
    }

    private fun progressKey(bookId: String) = "pg_" + bookId.hashCode().toString()
    private fun countKey(bookId: String) = "pc_" + bookId.hashCode().toString()
    private fun readKey(bookId: String) = "lr_" + bookId.hashCode().toString()

    private companion object {
        const val KEY_FOLDER = "folder_uri"
        const val KEY_FOLDERS = "folder_uris"
        const val KEY_THEME = "theme_mode"
        const val KEY_THEME_COLOR = "theme_color"
        const val KEY_PAGE_ANIM = "page_anim"
        const val KEY_DIRECTION = "reading_direction"
        const val KEY_DOUBLE = "double_page"
        const val KEY_AWAKE = "keep_awake"
        const val KEY_COLUMNS = "grid_columns"
        const val KEY_SORT = "sort_mode"
        const val KEY_TAP_TURN = "tap_to_turn"
        const val KEY_READ_MODE = "read_mode"
        const val KEY_FIT_MODE = "fit_mode"
        const val KEY_ZOOM_MIGRATED = "fit_mode_migrated"
        const val KEY_ZOOM_PERCENT = "zoom_percent"
        const val KEY_ALIGNMENT = "alignment"
        const val KEY_ORIENTATION = "screen_orientation"
        const val KEY_SMOOTHING = "smoothing"
        const val KEY_BRIGHTNESS = "brightness"

        const val MIN_ZOOM_PERCENT = 40
        const val MAX_ZOOM_PERCENT = 400
    }
}
