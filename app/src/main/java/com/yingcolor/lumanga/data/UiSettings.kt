package com.yingcolor.lumanga.data

import com.yingcolor.lumanga.theme.Palettes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue













class UiSettings(private val prefs: Prefs) {
    
    private var stateFavorites by mutableStateOf(prefs.favorites)
    val favorites: Set<String> get() = stateFavorites

    private var stateThemeMode by mutableStateOf(prefs.themeMode)
    val themeMode: Int get() = stateThemeMode

    private var stateThemeColor by mutableIntStateOf(Palettes.coerce(prefs.themeColor))
    val themeColor: Int get() = stateThemeColor

    private var statePageAnim by mutableIntStateOf(PageAnimation.coerce(prefs.pageAnim))
    val pageAnim: Int get() = statePageAnim

    private var stateReadingDirection by mutableStateOf(prefs.readingDirection)
    val readingDirection: Int get() = stateReadingDirection

    private var stateDoublePage by mutableStateOf(prefs.doublePage)
    val doublePage: Boolean get() = stateDoublePage

    private var stateKeepAwake by mutableStateOf(prefs.keepAwake)
    val keepAwake: Boolean get() = stateKeepAwake

    private var stateGridColumns by mutableStateOf(prefs.gridColumns)
    val gridColumns: Int get() = stateGridColumns

    private var stateSortMode by mutableStateOf(prefs.sortMode)
    val sortMode: Int get() = stateSortMode

    private var stateTapToTurn by mutableStateOf(prefs.tapToTurn)
    val tapToTurn: Boolean get() = stateTapToTurn

    private var stateTranslateEnabled by mutableStateOf(prefs.translateEnabled)
    val translateEnabled: Boolean get() = stateTranslateEnabled

    private var stateTranslateApi by mutableStateOf(prefs.translateApi)
    val translateApi: String get() = stateTranslateApi

    private var stateTranslateModel by mutableStateOf(prefs.translateModel)
    val translateModel: String get() = stateTranslateModel

    private var stateTranslateApiKey by mutableStateOf(prefs.translateApiKey)
    val translateApiKey: String get() = stateTranslateApiKey

    private var stateTranslateProtocol by mutableIntStateOf(ApiProtocol.from(prefs.translateProtocol).id)
    val translateProtocol: Int get() = stateTranslateProtocol

    private var stateTranslateMode by mutableIntStateOf(TranslateMode.coerce(prefs.translateMode))
    val translateMode: Int get() = stateTranslateMode

    private var stateSourceLanguage by mutableIntStateOf(SourceLanguage.coerce(prefs.sourceLanguage))
    val sourceLanguage: Int get() = stateSourceLanguage

    private var stateTargetLanguage by mutableIntStateOf(TargetLanguage.coerce(prefs.targetLanguage))
    val targetLanguage: Int get() = stateTargetLanguage

    // Pre-translate endpoint: a null override means "follow the global config". Reading these
    // effective values also subscribes the panel to global changes while it is still following.
    private var statePreApi by mutableStateOf(prefs.preTranslateApi)
    private var statePreApiKey by mutableStateOf(prefs.preTranslateApiKey)
    private var statePreModel by mutableStateOf(prefs.preTranslateModel)
    private var statePreProtocol by mutableStateOf(prefs.preTranslateProtocol)

    /** Effective values: an item with no override keeps following the global config live. */
    val preApi: String get() = statePreApi ?: translateApi
    val preApiKey: String get() = statePreApiKey ?: translateApiKey
    val preModel: String get() = statePreModel ?: translateModel
    val preProtocol: Int get() = statePreProtocol ?: translateProtocol

    /** True once any endpoint item has been edited away from the global value. */
    val preCustomised: Boolean get() =
        statePreApi != null || statePreApiKey != null ||
            statePreModel != null || statePreProtocol != null

    fun setPreApi(value: String) {
        val trimmed = value.trim()
        statePreApi = trimmed
        prefs.preTranslateApi = trimmed
    }

    fun setPreApiKey(value: String) {
        val trimmed = value.trim()
        statePreApiKey = trimmed
        prefs.preTranslateApiKey = trimmed
    }

    fun setPreModel(value: String) {
        val trimmed = value.trim()
        statePreModel = trimmed
        prefs.preTranslateModel = trimmed
    }

    fun setPreProtocol(value: Int) {
        val safe = ApiProtocol.coerce(value)
        statePreProtocol = safe
        prefs.preTranslateProtocol = safe
    }

    /** Clears every override so the pre-translate panels follow the global config again. */
    fun resetPreConfig() {
        prefs.preTranslateApi = null
        prefs.preTranslateApiKey = null
        prefs.preTranslateModel = null
        prefs.preTranslateProtocol = null
        statePreApi = null
        statePreApiKey = null
        statePreModel = null
        statePreProtocol = null
    }

    /** Part of every reader cache key, so switching languages can never serve a stale page. */
    val languageKey: String
        get() = if (stateSourceLanguage == SourceLanguage.DEFAULT.id &&
            stateTargetLanguage == TargetLanguage.DEFAULT.id
        ) "" else "s${stateSourceLanguage}t${stateTargetLanguage}"

    init {
        // The engine and the store read these globals rather than threading a language argument
        // through every call. Publishing them here also makes a cold start pick up saved prefs.
        syncLanguages()
    }

    private var stateOcrModel by mutableIntStateOf(OcrModel.from(prefs.ocrModel).id)
    val ocrModel: Int get() = stateOcrModel

    private var stateTextLayout by mutableIntStateOf(TextLayout.coerce(prefs.textLayout))
    val textLayout: Int get() = stateTextLayout

    fun setTextLayout(value: Int) {
        val safe = TextLayout.coerce(value)
        stateTextLayout = safe
        prefs.textLayout = safe
    }

    fun setTranslateEnabled(value: Boolean) {
        stateTranslateEnabled = value
        prefs.translateEnabled = value
    }

    fun setTranslateApi(value: String) {
        stateTranslateApi = value
        prefs.translateApi = value
    }

    fun setTranslateModel(value: String) {
        stateTranslateModel = value
        prefs.translateModel = value
    }

    fun setTranslateApiKey(value: String) {
        stateTranslateApiKey = value
        prefs.translateApiKey = value
    }

    fun setTranslateProtocol(value: Int) {
        val safe = ApiProtocol.from(value).id
        stateTranslateProtocol = safe
        prefs.translateProtocol = safe
    }

    fun setTranslateMode(value: Int) {
        val safe = TranslateMode.coerce(value)
        stateTranslateMode = safe
        prefs.translateMode = safe
    }

    fun setSourceLanguage(value: Int) {
        val safe = SourceLanguage.coerce(value)
        stateSourceLanguage = safe
        prefs.sourceLanguage = safe
        syncLanguages()
    }

    fun setTargetLanguage(value: Int) {
        val safe = TargetLanguage.coerce(value)
        stateTargetLanguage = safe
        prefs.targetLanguage = safe
        syncLanguages()
    }

    private fun syncLanguages() {
        TranslationEngine.source = SourceLanguage.from(stateSourceLanguage)
        TranslationEngine.target = TargetLanguage.from(stateTargetLanguage)
    }

    fun setOcrModel(value: Int) {
        val safe = OcrModel.from(value).id
        stateOcrModel = safe
        prefs.ocrModel = safe
    }

    private var stateReadMode by mutableStateOf(prefs.readMode)
    val readMode: Int get() = stateReadMode

    private var stateFitMode by mutableStateOf(prefs.fitMode)
    val fitMode: Int get() = stateFitMode

    private var stateZoomPercent by mutableStateOf(prefs.zoomPercent)
    val zoomPercent: Int get() = stateZoomPercent

    private var stateAlignment by mutableStateOf(prefs.alignment)
    val alignment: Int get() = stateAlignment

    private var stateScreenOrientation by mutableStateOf(prefs.screenOrientation)
    val screenOrientation: Int get() = stateScreenOrientation

    
    
    private var stateBookOrientationRev by mutableIntStateOf(0)
    val bookOrientationRev: Int get() = stateBookOrientationRev

    private var stateSmoothing by mutableStateOf(prefs.smoothing)
    val smoothing: Int get() = stateSmoothing

    private var stateBrightness by mutableStateOf(prefs.brightness)
    val brightness: Int get() = stateBrightness

    fun toggleFavorite(bookId: String) {
        val next = stateFavorites.toMutableSet()
        if (!next.add(bookId)) next.remove(bookId)
        stateFavorites = next
        prefs.favorites = next
    }

    fun setThemeMode(value: Int) {
        stateThemeMode = value
        prefs.themeMode = value
    }

    fun setThemeColor(value: Int) {
        val safe = Palettes.coerce(value)
        stateThemeColor = safe
        prefs.themeColor = safe
    }

    fun setPageAnim(value: Int) {
        val safe = PageAnimation.coerce(value)
        statePageAnim = safe
        prefs.pageAnim = safe
    }

    fun setReadingDirection(value: Int) {
        stateReadingDirection = value
        prefs.readingDirection = value
    }

    fun setDoublePage(value: Boolean) {
        stateDoublePage = value
        prefs.doublePage = value
    }

    fun setKeepAwake(value: Boolean) {
        stateKeepAwake = value
        prefs.keepAwake = value
    }

    fun setGridColumns(value: Int) {
        val safe = value.coerceIn(2, 5)
        stateGridColumns = safe
        prefs.gridColumns = safe
    }

    fun setSortMode(value: Int) {
        val safe = value.coerceIn(0, 6)
        stateSortMode = safe
        prefs.sortMode = safe
    }

    fun setTapToTurn(value: Boolean) {
        stateTapToTurn = value
        prefs.tapToTurn = value
    }

    fun setReadMode(value: Int) {
        val safe = value.coerceIn(0, 2)
        stateReadMode = safe
        prefs.readMode = safe
    }

    fun setFitMode(value: Int) {
        val safe = ZoomMode.coerce(value)
        stateFitMode = safe
        prefs.fitMode = safe
    }

    fun setZoomPercent(value: Int) {
        val safe = value.coerceIn(40, 400)
        stateZoomPercent = safe
        prefs.zoomPercent = safe
    }

    fun setAlignment(value: Int) {
        val safe = value.coerceIn(0, 2)
        stateAlignment = safe
        prefs.alignment = safe
    }

    fun setScreenOrientation(value: Int) {
        val safe = value.coerceIn(0, 2)
        stateScreenOrientation = safe
        prefs.screenOrientation = safe
    }

    
    fun bookOrientation(bookId: String): Int = prefs.bookOrientation(bookId)

    fun setBookOrientation(bookId: String, value: Int) {
        prefs.setBookOrientation(bookId, value)
        stateBookOrientationRev++
    }

    fun clearBookOrientations() {
        prefs.clearBookOrientations()
        stateBookOrientationRev++
    }

    fun setSmoothing(value: Int) {
        val safe = value.coerceIn(0, 1)
        stateSmoothing = safe
        prefs.smoothing = safe
    }

    fun setBrightness(value: Int) {
        val safe = value.coerceIn(-1, 100)
        stateBrightness = safe
        prefs.brightness = safe
    }
}
