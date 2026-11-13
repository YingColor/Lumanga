package com.yingcolor.lumanga.data

import android.content.Context
import org.json.JSONArray

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("comic_viewer", Context.MODE_PRIVATE)

    init {
        migrateZoomMode()
        migrateOrientation()
    }

    





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

    







    private fun migrateOrientation() {
        if (sp.getBoolean(KEY_ORIENTATION_MIGRATED, false)) return
        val stored = sp.getInt(KEY_ORIENTATION, 0)
        sp.edit()
            .putInt(KEY_ORIENTATION, if (stored == 0) 1 else stored)
            .putBoolean(KEY_ORIENTATION_MIGRATED, true)
            .apply()
    }

    




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

    
    var themeColor: Int
        get() = sp.getInt(KEY_THEME_COLOR, 0)
        set(value) = sp.edit().putInt(KEY_THEME_COLOR, value).apply()

    
    var pageAnim: Int
        get() = sp.getInt(KEY_PAGE_ANIM, 0)
        set(value) = sp.edit().putInt(KEY_PAGE_ANIM, value).apply()

    
    var favorites: Set<String>
        get() {
            val stored = sp.getString(KEY_FAVORITES, null) ?: return emptySet()
            return runCatching {
                val array = JSONArray(stored)
                (0 until array.length())
                    .mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
                    .toSet()
            }.getOrDefault(emptySet())
        }
        set(value) = sp.edit()
            .putString(KEY_FAVORITES, JSONArray(value.sorted()).toString())
            .apply()

    
    var readingDirection: Int
        get() = sp.getInt(KEY_DIRECTION, 1)
        set(value) = sp.edit().putInt(KEY_DIRECTION, value).apply()

    var doublePage: Boolean
        get() = sp.getBoolean(KEY_DOUBLE, false)
        set(value) = sp.edit().putBoolean(KEY_DOUBLE, value).apply()

    var keepAwake: Boolean
        get() = sp.getBoolean(KEY_AWAKE, true)
        set(value) = sp.edit().putBoolean(KEY_AWAKE, value).apply()

    
    var gridColumns: Int
        get() = sp.getInt(KEY_COLUMNS, 2).coerceIn(2, 5)
        set(value) = sp.edit().putInt(KEY_COLUMNS, value.coerceIn(2, 5)).apply()

    
    var sortMode: Int
        get() = sp.getInt(KEY_SORT, 0).coerceIn(0, 6)
        set(value) = sp.edit().putInt(KEY_SORT, value.coerceIn(0, 6)).apply()

    
    var tapToTurn: Boolean
        get() = sp.getBoolean(KEY_TAP_TURN, true)
        set(value) = sp.edit().putBoolean(KEY_TAP_TURN, value).apply()

    
    var translateEnabled: Boolean
        get() = sp.getBoolean(KEY_TRANSLATE, false)
        set(value) = sp.edit().putBoolean(KEY_TRANSLATE, value).apply()

    var translateApi: String
        get() = sp.getString(KEY_TRANSLATE_API, TranslationConfig.DEFAULT_API)
            ?: TranslationConfig.DEFAULT_API
        set(value) = sp.edit().putString(KEY_TRANSLATE_API, value.trim()).apply()

    var translateModel: String
        get() = sp.getString(KEY_TRANSLATE_MODEL, TranslationConfig.DEFAULT_MODEL)
            ?: TranslationConfig.DEFAULT_MODEL
        set(value) = sp.edit().putString(KEY_TRANSLATE_MODEL, value.trim()).apply()

    /** Which wire format the endpoint speaks; see [ApiProtocol]. */
    var translateProtocol: Int
        get() = sp.getInt(KEY_TRANSLATE_PROTOCOL, ApiProtocol.DEFAULT.id)
        set(value) = sp.edit().putInt(KEY_TRANSLATE_PROTOCOL, ApiProtocol.coerce(value)).apply()

    /** Whole-page request or one per bubble group; see [TranslateMode]. */
    var translateMode: Int
        get() = sp.getInt(KEY_TRANSLATE_MODE, TranslateMode.PAGE)
        set(value) = sp.edit().putInt(KEY_TRANSLATE_MODE, TranslateMode.coerce(value)).apply()

    /** Language printed on the page; see [SourceLanguage]. */
    var sourceLanguage: Int
        get() = sp.getInt(KEY_SOURCE_LANGUAGE, SourceLanguage.DEFAULT.id)
        set(value) = sp.edit().putInt(KEY_SOURCE_LANGUAGE, SourceLanguage.coerce(value)).apply()

    /** Language the translation service should write; see [TargetLanguage]. */
    var targetLanguage: Int
        get() = sp.getInt(KEY_TARGET_LANGUAGE, TargetLanguage.DEFAULT.id)
        set(value) = sp.edit().putInt(KEY_TARGET_LANGUAGE, TargetLanguage.coerce(value)).apply()

    /** Sent as `Authorization: Bearer …` when the service needs one; empty means no header. */
    var translateApiKey: String
        get() = sp.getString(KEY_TRANSLATE_KEY, "") ?: ""
        set(value) = sp.edit().putString(KEY_TRANSLATE_KEY, value.trim()).apply()

    /**
     * Pre-translate keeps its own endpoint settings. `null` means "still following the global
     * config"; the first edit writes a value and from then on that item is independent. The
     * global keys above are never written by a pre-translate panel.
     */
    var preTranslateApi: String?
        get() = if (sp.contains(KEY_PRE_API)) sp.getString(KEY_PRE_API, null) else null
        set(value) {
            val edit = sp.edit()
            if (value == null) edit.remove(KEY_PRE_API) else edit.putString(KEY_PRE_API, value.trim())
            edit.apply()
        }

    var preTranslateApiKey: String?
        get() = if (sp.contains(KEY_PRE_KEY)) sp.getString(KEY_PRE_KEY, null) else null
        set(value) {
            val edit = sp.edit()
            if (value == null) edit.remove(KEY_PRE_KEY) else edit.putString(KEY_PRE_KEY, value.trim())
            edit.apply()
        }

    var preTranslateModel: String?
        get() = if (sp.contains(KEY_PRE_MODEL)) sp.getString(KEY_PRE_MODEL, null) else null
        set(value) {
            val edit = sp.edit()
            if (value == null) edit.remove(KEY_PRE_MODEL) else edit.putString(KEY_PRE_MODEL, value.trim())
            edit.apply()
        }

    var preTranslateProtocol: Int?
        get() = if (sp.contains(KEY_PRE_PROTOCOL))
            sp.getInt(KEY_PRE_PROTOCOL, ApiProtocol.DEFAULT.id) else null
        set(value) {
            val edit = sp.edit()
            if (value == null) edit.remove(KEY_PRE_PROTOCOL)
            else edit.putInt(KEY_PRE_PROTOCOL, ApiProtocol.coerce(value))
            edit.apply()
        }

    /** 0 = PP-OCRv6 Small (default), 1 = PP-OCRv6 Medium, 2 = MangaOCR_mobile. */
    var ocrModel: Int
        get() = sp.getInt(KEY_OCR_MODEL, OcrModel.DEFAULT.id)
        set(value) = sp.edit().putInt(KEY_OCR_MODEL, OcrModel.from(value).id).apply()

    /**
     * How translated text is laid out on the page. 0 keeps the source orientation (vertical
     * columns for a Japanese bubble), 1 forces horizontal lines read left to right.
     */
    var textLayout: Int
        get() = sp.getInt(KEY_TEXT_LAYOUT, TextLayout.VERTICAL)
        set(value) = sp.edit().putInt(KEY_TEXT_LAYOUT, TextLayout.coerce(value)).apply()

    var readMode: Int
        get() = sp.getInt(KEY_READ_MODE, 0).coerceIn(0, 2)
        set(value) = sp.edit().putInt(KEY_READ_MODE, value.coerceIn(0, 2)).apply()

    
    var fitMode: Int
        get() = ZoomMode.coerce(sp.getInt(KEY_FIT_MODE, ZoomMode.FIT_SCREEN))
        set(value) = sp.edit().putInt(KEY_FIT_MODE, ZoomMode.coerce(value)).apply()

    
    var zoomPercent: Int
        get() = sp.getInt(KEY_ZOOM_PERCENT, 100).coerceIn(MIN_ZOOM_PERCENT, MAX_ZOOM_PERCENT)
        set(value) = sp.edit()
            .putInt(KEY_ZOOM_PERCENT, value.coerceIn(MIN_ZOOM_PERCENT, MAX_ZOOM_PERCENT))
            .apply()

    
    var alignment: Int
        get() = sp.getInt(KEY_ALIGNMENT, 0).coerceIn(0, 2)
        set(value) = sp.edit().putInt(KEY_ALIGNMENT, value.coerceIn(0, 2)).apply()

    
    var screenOrientation: Int
        get() = sp.getInt(KEY_ORIENTATION, 1).coerceIn(0, 2)
        set(value) = sp.edit()
            .putInt(KEY_ORIENTATION, value.coerceIn(0, 2))
            .putBoolean(KEY_ORIENTATION_MIGRATED, true)
            .apply()

    




    fun bookOrientation(bookId: String): Int =
        sp.getInt("bo_${bookId.hashCode()}", -1).coerceIn(-1, 2)

    fun setBookOrientation(bookId: String, value: Int) {
        sp.edit().putInt("bo_${bookId.hashCode()}", value.coerceIn(-1, 2)).apply()
    }

    
    fun clearBookOrientations() {
        val editor = sp.edit()
        sp.all.keys.filter { it.startsWith("bo_") }.forEach { editor.remove(it) }
        editor.apply()
    }

    
    var smoothing: Int
        get() = sp.getInt(KEY_SMOOTHING, 0).coerceIn(0, 1)
        set(value) = sp.edit().putInt(KEY_SMOOTHING, value.coerceIn(0, 1)).apply()

    
    var brightness: Int
        get() = sp.getInt(KEY_BRIGHTNESS, -1).coerceIn(-1, 100)
        set(value) = sp.edit().putInt(KEY_BRIGHTNESS, value.coerceIn(-1, 100)).apply()

    fun progress(bookId: String): Int = sp.getInt(progressKey(bookId), 0)

    
    fun pageCount(bookId: String): Int = sp.getInt(countKey(bookId), 0)

    
    fun lastRead(bookId: String): Long = sp.getLong(readKey(bookId), 0L)

    



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

    
    fun clearProgress(bookId: String) {
        sp.edit()
            .remove(progressKey(bookId))
            .remove(countKey(bookId))
            .remove(readKey(bookId))
            .apply()
    }

    




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
        const val KEY_ORIENTATION_MIGRATED = "screen_orientation_migrated"
        const val KEY_FAVORITES = "favorites"
        const val KEY_SMOOTHING = "smoothing"
        const val KEY_BRIGHTNESS = "brightness"
        const val KEY_TRANSLATE = "translate_enabled"
        const val KEY_TRANSLATE_API = "translate_api"
        const val KEY_TRANSLATE_PROTOCOL = "translate_protocol"
        const val KEY_TRANSLATE_MODE = "translate_mode"
        const val KEY_TRANSLATE_MODEL = "translate_model"
        const val KEY_TRANSLATE_KEY = "translate_api_key"
        const val KEY_OCR_MODEL = "ocr_model"
        const val KEY_TEXT_LAYOUT = "text_layout"
        const val KEY_SOURCE_LANGUAGE = "source_language"
        const val KEY_TARGET_LANGUAGE = "target_language"
        const val KEY_PRE_API = "pretranslate_api"
        const val KEY_PRE_KEY = "pretranslate_api_key"
        const val KEY_PRE_MODEL = "pretranslate_model"
        const val KEY_PRE_PROTOCOL = "pretranslate_protocol"

        const val MIN_ZOOM_PERCENT = 40
        const val MAX_ZOOM_PERCENT = 400
    }
}
